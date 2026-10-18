#!/usr/bin/env bash
# Disables packages for user 0 (reversible: nothing is uninstalled or deleted).
# Takes list files (one package per line, '#' comments allowed) and/or package names.
# Everything disabled is recorded in state/disabled.txt so scripts/restore.sh can undo it.
#   scripts/debloat.sh lists/google-tv-common.txt lists/sony-google-tv.txt
#   scripts/debloat.sh -y com.example.ads        # -y: skip the confirmation prompt
#   scripts/debloat.sh --dry-run lists/*.txt     # only print what would be disabled
source "$(dirname "$0")/lib.sh"
ensure_connected

# Never touch these: inputs, tuner, settings, Play services, the keyboard, remote pairing, etc.
# Home-screen apps are handled by tv-home.sh, which can put them back.
PROTECTED='^(android|com\.android\.(systemui|settings|tv\.settings|providers\..*|inputdevices|bluetooth|shell|packageinstaller|permissioncontroller|externalstorage|networkstack.*|wifi.*|se|keychain)|com\.google\.android\.(gms|gsf|webview|packageinstaller|permissioncontroller|ext\..*|tv\.remote\.service|inputmethod\..*|apps\.tv\.launcherx|tungsten\.setupwraith|tvlauncher|leanbacklauncher)|com\.android\.vending|.*(tvinput|tvapi|mediatek|mtk|systemservice|settingsprovider|frameworkextension).*)$'

yes=0
dry=0
pkgs=()
for arg in "$@"; do
  if [[ "$arg" == "-y" ]]; then yes=1
  elif [[ "$arg" == "--dry-run" ]]; then dry=1
  elif [[ -f "$arg" ]]; then
    while IFS= read -r line; do
      line="${line%%#*}"; line="$(echo "$line" | tr -d '[:space:]')"
      [[ -n "$line" ]] && pkgs+=("$line")
    done < "$arg"
  else pkgs+=("$arg"); fi
done
[[ ${#pkgs[@]} -gt 0 ]] || die "usage: $0 [-y|--dry-run] <list-file|package>..."

installed="$(adb shell pm list packages </dev/null | sed 's/^package://' | tr -d '\r')"
enabled="$(adb shell pm list packages -e </dev/null | sed 's/^package://' | tr -d '\r')"
todo=()
for p in "${pkgs[@]}"; do
  if echo "$p" | grep -qE "$PROTECTED"; then echo "skip (protected): $p"; continue; fi
  if ! echo "$installed" | grep -qxF "$p"; then echo "skip (not installed): $p"; continue; fi
  if ! echo "$enabled" | grep -qxF "$p"; then echo "skip (already disabled): $p"; continue; fi
  todo+=("$p")
done
[[ ${#todo[@]} -gt 0 ]] || { echo "nothing to disable"; exit 0; }

echo "Will disable on $ANDROID_SERIAL:"; printf '  %s\n' "${todo[@]}"
[[ $dry -eq 1 ]] && exit 0
if [[ $yes -eq 0 ]]; then
  ok=""
  read -r -p "Proceed? [y/N] " ok </dev/tty 2>/dev/null || true
  [[ "$ok" == y || "$ok" == Y ]] || { echo "aborted (pass -y to run without a prompt)"; exit 1; }
fi

for p in "${todo[@]}"; do
  if adb shell pm disable-user --user 0 "$p" | tr -d '\r' | grep -q 'disabled'; then
    remember "$STATE/disabled.txt" "$p"
    echo "disabled: $p"
  else
    echo "FAILED:   $p"
  fi
done
echo "Recorded in state/disabled.txt. Undo with scripts/restore.sh."
