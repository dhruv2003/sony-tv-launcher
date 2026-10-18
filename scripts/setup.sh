#!/usr/bin/env bash
# Guided setup for people who don't use Claude: finds the TV, helps turn on debugging, connects,
# shows what it would disable and waits for your yes, then optionally installs the launcher.
# It follows the same steps as CLAUDE.md and only calls the other scripts in this folder, so
# every change is recorded in state/ and undone by scripts/restore.sh.
#   scripts/setup.sh
source "$(dirname "$0")/lib.sh"
HERE="$(dirname "$0")"

# Questions go to the terminal, even when this script was started from a pipe (curl | bash).
if { : </dev/tty; } 2>/dev/null; then TTY=/dev/tty; else TTY=/dev/stdin; fi

# Fails when there is no answer to read (input closed), so a missing answer never counts as "yes".
ask() {
  local a=""
  printf '%s ' "$1" >&2
  read -r a <"$TTY" || { echo >&2; return 1; }
  printf '%s' "$a"
}

# yes_no "question" y|n   (second argument is the default when you just press Enter)
yes_no() {
  local hint="[y/N]" a
  [[ "$2" == y ]] && hint="[Y/n]"
  a="$(ask "$1 $hint")" || return 1
  a="${a:-$2}"
  [[ "$a" == y || "$a" == Y || "$a" == yes ]]
}

step() { printf '\n== %s\n' "$*"; }

need_adb

cat <<'EOF'
TV de-ad kit: guided setup
Nothing is rooted or uninstalled. You will be asked before anything on the TV changes, and
scripts/restore.sh undoes all of it. Keep the TV remote handy.
EOF

# ---- 1. find the TV ----------------------------------------------------------------------------
step "1/5  Finding your TV (make sure it is on and on the same Wi-Fi as this computer)"
ip=""
while [[ -z "$ip" ]]; do
  named=""
  for _ in 1 2; do   # a scan can miss a sleepy TV once, so look twice before asking
    scan="$("$HERE/find-tv.sh" 2>&1 </dev/null || true)"
    # Devices with a Cast name are TVs/streamers; a bare open port 5555 is usually something else.
    named="$(printf '%s\n' "$scan" | awk '$1 ~ /^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$/ && NF >= 3 {print}')"
    [[ -n "$named" ]] && break
  done
  if [[ -z "$named" ]]; then
    echo "No TV found automatically."
    ip="$(ask "Enter the TV's IP address (Settings > Network > About on most TVs), or q to quit:")"
    [[ "$ip" == q ]] && exit 1
    continue
  fi
  count="$(printf '%s\n' "$named" | wc -l | tr -d ' ')"
  if [[ "$count" -eq 1 ]]; then
    ip="$(printf '%s\n' "$named" | awk '{print $1}')"
    echo "Found: $ip ($(printf '%s' "$named" | awk '{$1=""; $2=""; sub(/^ +/, ""); print}'))"
    yes_no "Is this your TV?" y || ip=""
  else
    echo "Found several devices:"
    printf '%s\n' "$named" | awk '{n=$1; $1=""; $2=""; sub(/^ +/, ""); printf "%2d) %s (%s)\n", NR, n, $0}'
    n="$(ask "Type the number of your TV (or its IP address):")"
    if [[ "$n" =~ ^[0-9]+$ && "$n" -le "$count" && "$n" -ge 1 ]]; then
      ip="$(printf '%s\n' "$named" | sed -n "${n}p" | awk '{print $1}')"
    else
      ip="$n"
    fi
  fi
  [[ -n "$ip" ]] || { yes_no "Search again?" y || exit 1; }
done

# ---- 2. developer mode + connect ---------------------------------------------------------------
dev_mode_help() {
  cat <<'EOF'

Turn on debugging on the TV (no cable needed):
  1. Settings > System > About   (on some TVs: Settings > Device Preferences > About)
  2. Press OK on "Android TV OS build" (or "Build") about 7 times, until it says
     "You are now a developer".
  3. Go back to Settings > System > Developer options (or Device Preferences > Developer options).
  4. Turn on "USB debugging" (Sony calls it "ADB debugging"). If there is a separate
     "Network debugging" / "Wireless debugging" switch, turn that on too.
EOF
}

step "2/5  Connecting to $ip"
connected=0
for attempt in 1 2 3; do
  if [[ "$attempt" -eq 1 ]]; then
    # If debugging is already on we can skip the instructions.
    if ! nc -z -w 2 "$ip" 5555 >/dev/null 2>&1; then
      dev_mode_help
      ask "Press Enter once debugging is on..." >/dev/null
    fi
  fi
  echo "Watch the TV: an 'Allow USB debugging?' prompt appears. Tick 'Always allow from this computer' and press OK."
  if "$HERE/connect.sh" "$ip" </dev/null; then connected=1; break; fi
  dev_mode_help
  yes_no "Try again?" y || break
done
[[ "$connected" -eq 1 ]] || die "couldn't connect to the TV. Fix debugging, then run scripts/setup.sh again."

maker="$(adb -s "$(cat "$STATE/tv")" shell </dev/null getprop ro.product.manufacturer | tr -d '\r' | tr '[:upper:]' '[:lower:]')"

# ---- 3. survey ---------------------------------------------------------------------------------
step "3/5  Looking at what is on the TV (read-only)"
survey="$("$HERE/survey.sh" </dev/null)"
echo "Saved a snapshot to $survey"

# ---- 4. de-bloat (asks first) ------------------------------------------------------------------
step "4/5  Ads and tracking apps"
lists=("$ROOT/lists/google-tv-common.txt")
for f in "$ROOT"/lists/"$maker"-*.txt; do [[ -f "$f" ]] && lists+=("$f"); done
echo "Using lists: $(for f in "${lists[@]}"; do basename "$f"; done | paste -sd' ' -)"
plan="$("$HERE/debloat.sh" --dry-run "${lists[@]}" 2>&1 </dev/null || true)"
printf '%s\n' "$plan" | grep -v '^skip' || true
if printf '%s\n' "$plan" | grep -q '^Will disable'; then
  echo
  echo "These are only disabled, never uninstalled, and the TV's inputs, tuner, settings and Google"
  echo "services are protected."
  if yes_no "Disable the packages listed above?" n; then
    "$HERE/debloat.sh" -y "${lists[@]}" </dev/null | grep -E '^(disabled|FAILED|Recorded)'
  else
    echo "Skipped. Nothing was changed."
  fi
else
  echo "Nothing from the known lists to disable on this TV."
fi

# ---- 5. home screen ----------------------------------------------------------------------------
step "5/5  Home screen"
echo "Disabling packages does not remove ads from the stock home screen itself. The custom launcher"
echo "(dark, Apple TV style, no ads) replaces it, and you can switch back any time."
if yes_no "Install the custom launcher and make it your Home screen?" y; then
  "$HERE/install-launcher.sh" </dev/null
  "$HERE/tv-home.sh" custom </dev/null
  "$HERE/screenshot.sh" >/dev/null 2>&1 </dev/null || true
  echo "Done. Press Home on the remote to see it."
else
  echo "Skipped. On Google TV you can also turn on Apps only mode:"
  echo "Settings > Accounts & sign-in > your profile > Apps only mode."
fi

cat <<EOF

All set. Useful commands (run them from $ROOT):
  scripts/tv-home.sh custom    custom launcher as Home
  scripts/tv-home.sh stock     the TV's own home screen back
  scripts/restore.sh           undo everything
EOF
