#!/usr/bin/env bash
# Give Rosey-Cherry a grown-up's voice (voice clone made locally with XTTS-v2). Run on the server,
# after scripts/install_server.sh. Takes a while the first time (~3 GB of downloads).
#
#   ./scripts/install_voice.sh                   # use the default "Stories for my daughter" playlist
#   ./scripts/install_voice.sh --from-file me.wav   # or your own recording (1-2 minutes, no music)
#   (any options are passed to: python -m rosey.voice_setup --help)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SERVER="$ROOT/server"
PY="$SERVER/.venv/bin/python"
say() { printf '\n\033[1;35m[rosey]\033[0m %s\n' "$*"; }

[ -x "$PY" ] || { echo "Run ./scripts/install_server.sh first."; exit 1; }
for t in ffmpeg ffprobe; do
  command -v $t >/dev/null || { echo "$t is needed: sudo apt install ffmpeg   (macOS: brew install ffmpeg)"; exit 1; }
done
"$PY" -c 'import sys; sys.exit(0 if (3,10) <= sys.version_info[:2] <= (3,13) else 1)' || {
  echo "The voice model needs Python 3.10-3.13 in server/.venv. Recreate it with e.g. python3.12 -m venv server/.venv"; exit 1; }

say "Installing the voice model software (CPU build of PyTorch)..."
if [ "$(uname)" = "Linux" ] && ! "$PY" -c 'import torch' 2>/dev/null; then
  "$SERVER/.venv/bin/pip" install -q torch torchaudio --index-url https://download.pytorch.org/whl/cpu
fi
"$SERVER/.venv/bin/pip" install -q -r "$SERVER/requirements-voice.txt"

say "Making the voice sample and a preview..."
echo "(The first run downloads the XTTS-v2 model and shows its licence - the Coqui Public Model"
echo " Licence, non-commercial use only. Read it and type 'y' yourself if you agree.)"
cd "$SERVER"
"$PY" -m rosey.voice_setup "$@"

grep -q '^ROSEY_VOICE=' "$SERVER/.env" 2>/dev/null && sed -i.bak 's/^ROSEY_VOICE=.*/ROSEY_VOICE=1/' "$SERVER/.env" \
  || echo "ROSEY_VOICE=1" >> "$SERVER/.env"
rm -f "$SERVER/.env.bak"

say "Restarting the server with the new voice..."
if command -v systemctl >/dev/null && systemctl --user is-enabled rosey-cherry >/dev/null 2>&1; then
  systemctl --user restart rosey-cherry
elif [ -f "$HOME/Library/LaunchAgents/ai.rosey.cherry.plist" ]; then
  launchctl unload "$HOME/Library/LaunchAgents/ai.rosey.cherry.plist"; launchctl load "$HOME/Library/LaunchAgents/ai.rosey.cherry.plist"
else
  echo "Restart ./server/run.sh yourself."
fi
say "Done. The voice takes ~1 minute to load after a restart; check: curl -s localhost:\${ROSEY_PORT:-8765}/health"
