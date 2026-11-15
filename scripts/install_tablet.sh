#!/usr/bin/env bash
# Install Rosey-Cherry on an Android tablet connected by USB.
#
# Before you start, on the tablet:
#   Settings > About tablet > tap "Build number" 7 times  (turns on Developer options)
#   Settings > Developer options > turn on "USB debugging"
#   Plug in the cable and tap "Allow" on the tablet when asked.
#
# Usage:
#   ./scripts/install_tablet.sh http://SERVER_IP:8765 TOKEN          # downloads the latest built APK
#   APK=path/to/app.apk ./scripts/install_tablet.sh http://SERVER_IP:8765 TOKEN
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
URL="${1:-}"
TOKEN="${2:-}"
PKG=ai.rosey.cherry

command -v adb >/dev/null || {
  echo "adb not found. Install it:"
  echo "  macOS:  brew install --cask android-platform-tools"
  echo "  Linux:  sudo apt install adb"
  exit 1
}

adb get-state >/dev/null 2>&1 || { echo "No tablet found. Check the USB cable and that USB debugging is allowed."; adb devices; exit 1; }

APK="${APK:-}"
if [ -z "$APK" ]; then
  if [ -f "$ROOT/android/app/build/outputs/apk/debug/app-debug.apk" ]; then
    APK="$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
  elif command -v gh >/dev/null; then
    mkdir -p "$ROOT/.apk"
    gh release download tablet-latest --repo SteliosKyriacou/Ahis-robot --pattern 'rosey-cherry.apk' --dir "$ROOT/.apk" --clobber
    APK="$ROOT/.apk/rosey-cherry.apk"
  else
    echo "No APK found. Either build it (cd android && gradle assembleDebug) or install the GitHub CLI (gh) to download it."
    exit 1
  fi
fi

echo "Installing $APK ..."
if ! adb install -r "$APK"; then
  echo "Update failed (probably a different signing key) - reinstalling fresh."
  adb uninstall "$PKG" || true
  adb install "$APK"
fi

adb shell pm grant "$PKG" android.permission.RECORD_AUDIO || true

if [ -n "$URL" ]; then
  adb shell am start -n "$PKG/.MainActivity" --es server_url "$URL" --es token "$TOKEN"
else
  adb shell am start -n "$PKG/.MainActivity"
  echo "Tip: pass the server address and token, or hold Rosey's face for 3 seconds to open settings."
fi
echo "Rosey-Cherry is installed! 🍒"
