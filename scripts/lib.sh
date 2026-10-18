#!/usr/bin/env bash
# Shared helpers, sourced by the other scripts: which TV to talk to and where per-TV state lives.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export PATH="$ROOT/platform-tools:$PATH"   # adb downloaded by install.sh, if any
STATE="$ROOT/state"            # git-ignored: this TV's address and everything we changed on it
mkdir -p "$STATE"

LAUNCHER_PKG="dev.dhruv.tvhome"
LAUNCHER_HOME="$LAUNCHER_PKG/.MainActivity"
APK_URL="https://github.com/dhruv2003/sony-tv-launcher/releases/latest/download/tv-home.apk"

die() { echo "error: $*" >&2; exit 1; }

need_adb() {
  command -v adb >/dev/null 2>&1 && return
  die "adb not found. Run install.sh, or install it: 'brew install --cask android-platform-tools' (macOS) or 'sudo apt install adb' (Debian/Ubuntu)."
}

# TV=<ip>[:port] in the environment wins, else state/tv (written by connect.sh).
resolve_tv() {
  local t="${TV:-}"
  if [[ -z "$t" && -f "$STATE/tv" ]]; then t="$(cat "$STATE/tv")"; fi
  [[ -n "$t" ]] || die "no TV selected. Run scripts/find-tv.sh, then scripts/connect.sh <ip>."
  [[ "$t" == *:* ]] || t="$t:5555"
  export ANDROID_SERIAL="$t"
}

ensure_connected() {
  need_adb
  resolve_tv
  adb connect "$ANDROID_SERIAL" >/dev/null 2>&1 || true
  [[ "$(adb get-state 2>/dev/null || true)" == "device" ]] \
    || die "TV $ANDROID_SERIAL is not connected or not authorized. Run scripts/connect.sh."
}

# Appends a line to a state file unless it is already there.
remember() {
  local file="$1" line="$2"
  touch "$file"
  grep -qxF "$line" "$file" || echo "$line" >> "$file"
}

current_home() {
  adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN \
    -c android.intent.category.HOME | tail -1 | tr -d '\r'
}

resumed_activity() {
  adb shell dumpsys activity activities | grep -m1 -E 'mResumedActivity|topResumedActivity' \
    | grep -oE '[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+' | head -1
}

press_home() {
  adb shell input keyevent KEYCODE_HOME
  sleep 3
}
