#!/usr/bin/env bash
# One-line installer for the TV de-ad kit (no Claude needed):
#
#   curl -fsSL https://raw.githubusercontent.com/dhruv2003/sony-tv-launcher/main/install.sh | bash
#
# It sets up adb if you don't have it, downloads the scripts to ~/tv-de-ad-kit and starts the guided
# setup (scripts/setup.sh), which asks before it changes anything on the TV.
#
# Optional environment variables:
#   TVKIT_DIR=<folder>   where to put the kit (default ~/tv-de-ad-kit)
#   TVKIT_REPO=<owner/repo>, TVKIT_REF=<branch or tag>   download a fork or another version
#   TVKIT_SRC=<folder>   copy the kit from a local checkout instead of downloading (for development)
set -euo pipefail

REPO="${TVKIT_REPO:-dhruv2003/sony-tv-launcher}"
REF="${TVKIT_REF:-main}"
DIR="${TVKIT_DIR:-$HOME/tv-de-ad-kit}"

say() { printf '%s\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

# Questions go to the terminal even though this script arrives through a pipe.
if { : </dev/tty; } 2>/dev/null; then TTY=/dev/tty; else TTY=""; fi
confirm() {   # confirm "question" -> yes by default (also when there is no terminal to ask)
  local a=""
  if [[ -n "$TTY" ]]; then
    printf '%s [Y/n] ' "$1" >&2
    read -r a <"$TTY" || true
  fi
  [[ -z "$a" || "$a" == y || "$a" == Y || "$a" == yes ]]
}

case "$(uname -s)" in
  Darwin) os=darwin ;;
  Linux)  os=linux ;;
  *) die "this works on macOS and Linux. On Windows, install WSL (https://learn.microsoft.com/windows/wsl/install) and run the command there." ;;
esac
for tool in curl tar; do command -v "$tool" >/dev/null 2>&1 || die "'$tool' is required but not installed."; done

# ---- 1. the scripts ----------------------------------------------------------------------------
say "Installing the TV de-ad kit into $DIR"
mkdir -p "$DIR"
if [[ -n "${TVKIT_SRC:-}" ]]; then
  (cd "$TVKIT_SRC" && tar --exclude=./state --exclude=./.git --exclude=./platform-tools -cf - .) | tar -xf - -C "$DIR"
else
  # Extracting over an existing copy updates the scripts and keeps state/ (your TV's address and undo list).
  curl -fsSL "https://codeload.github.com/$REPO/tar.gz/$REF" | tar -xz --strip-components=1 -C "$DIR" \
    || die "couldn't download $REPO ($REF). Check your connection."
fi
chmod +x "$DIR"/scripts/*.sh

# ---- 2. adb ------------------------------------------------------------------------------------
if command -v adb >/dev/null 2>&1; then
  say "adb found: $(command -v adb)"
elif [[ -x "$DIR/platform-tools/adb" ]]; then
  say "adb found: $DIR/platform-tools/adb"
else
  command -v unzip >/dev/null 2>&1 || die "'unzip' is required to set up adb. Install it (e.g. 'sudo apt install unzip') and run this again."
  if [[ "$os" == linux && "$(uname -m)" != x86_64 ]]; then
    die "Google only ships adb for x86_64 Linux. On this machine run 'sudo apt install adb' (or your package manager's equivalent), then run this command again."
  fi
  say "adb (Android Debug Bridge) is how this kit talks to your TV over Wi-Fi."
  confirm "Download Google's platform-tools (about 15 MB) into $DIR/platform-tools? No admin rights needed." \
    || die "adb is required. Install it yourself and run this again."
  zip="$(mktemp -t platform-tools.XXXXXX)"
  trap 'rm -f "$zip"' EXIT
  curl -fL --progress-bar -o "$zip" "https://dl.google.com/android/repository/platform-tools-latest-$os.zip" \
    || die "couldn't download platform-tools from dl.google.com."
  unzip -q -o "$zip" -d "$DIR"
  [[ -x "$DIR/platform-tools/adb" ]] || die "platform-tools did not unpack as expected."
  say "adb installed: $DIR/platform-tools/adb"
fi

# ---- 3. start the guided setup -----------------------------------------------------------------
say ""
if [[ -n "$TTY" ]]; then
  exec bash "$DIR/scripts/setup.sh" <"$TTY"
else
  say "No terminal to ask questions on. Run this yourself: $DIR/scripts/setup.sh"
fi
