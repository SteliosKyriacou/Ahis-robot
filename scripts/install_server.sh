#!/usr/bin/env bash
# Install (or update) Rosey-Cherry's brain server on the machine that runs Claude Code.
#
#   git clone git@github.com:SteliosKyriacou/Ahis-robot.git && cd Ahis-robot
#   ./scripts/install_server.sh            # install + start as a background service
#   ./scripts/install_server.sh --no-service   # install only; run with ./server/run.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SERVER="$ROOT/server"
PORT="${ROSEY_PORT:-8765}"
SERVICE=1
[ "${1:-}" = "--no-service" ] && SERVICE=0

say() { printf '\n\033[1;35m[rosey]\033[0m %s\n' "$*"; }

# 1. Requirements -------------------------------------------------------------
command -v python3 >/dev/null || { echo "python3 (3.10+) is required"; exit 1; }
CLAUDE_BIN="$(command -v claude || true)"
[ -z "$CLAUDE_BIN" ] && [ -x "$HOME/.local/bin/claude" ] && CLAUDE_BIN="$HOME/.local/bin/claude"
if [ -z "$CLAUDE_BIN" ]; then
  echo "Claude Code CLI not found. Install it (https://docs.claude.com/en/docs/claude-code) and run 'claude' once to log in."
  exit 1
fi
say "Using Claude Code at $CLAUDE_BIN"

# 2. Python env --------------------------------------------------------------
say "Creating Python virtualenv"
python3 -m venv "$SERVER/.venv"
"$SERVER/.venv/bin/pip" install -q --upgrade pip
"$SERVER/.venv/bin/pip" install -q -r "$SERVER/requirements.txt"

# 3. Config ------------------------------------------------------------------
if [ ! -f "$SERVER/.env" ]; then
  TOKEN="$(python3 -c 'import secrets; print(secrets.token_urlsafe(18))')"
  cat > "$SERVER/.env" <<ENV
ROSEY_TOKEN=$TOKEN
ROSEY_PORT=$PORT
ROSEY_MODEL=sonnet
ROSEY_CLAUDE_BIN=$CLAUDE_BIN
ENV
  chmod 600 "$SERVER/.env"
  say "Wrote $SERVER/.env with a new tablet token"
fi
chmod +x "$SERVER/run.sh"
set -a; . "$SERVER/.env"; set +a

# 4. Check Claude Code works ---------------------------------------------------
say "Checking that Claude Code is logged in..."
if echo "Say just: ok" | "$CLAUDE_BIN" -p --tools "" --output-format json 2>/dev/null | grep -q '"is_error":false'; then
  say "Claude Code answered. 🧠"
else
  say "WARNING: Claude Code did not answer. Run 'claude' on this machine and log in, then re-run this script."
fi

# 5. Background service ------------------------------------------------------
if [ "$SERVICE" = 1 ]; then
  if [ "$(uname)" = "Linux" ] && command -v systemctl >/dev/null; then
    mkdir -p "$HOME/.config/systemd/user"
    cat > "$HOME/.config/systemd/user/rosey-cherry.service" <<UNIT
[Unit]
Description=Rosey-Cherry brain server
After=network-online.target

[Service]
ExecStart=$SERVER/run.sh
Restart=always
RestartSec=3
Environment=PATH=$(dirname "$CLAUDE_BIN"):/usr/local/bin:/usr/bin:/bin

[Install]
WantedBy=default.target
UNIT
    systemctl --user daemon-reload
    systemctl --user enable --now rosey-cherry.service
    systemctl --user restart rosey-cherry.service
    loginctl enable-linger "$USER" 2>/dev/null || true
    say "Installed systemd user service 'rosey-cherry' (logs: journalctl --user -u rosey-cherry -f)"
  elif [ "$(uname)" = "Darwin" ]; then
    PLIST="$HOME/Library/LaunchAgents/ai.rosey.cherry.plist"
    mkdir -p "$HOME/Library/LaunchAgents" "$SERVER/logs"
    cat > "$PLIST" <<PL
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>ai.rosey.cherry</string>
  <key>ProgramArguments</key><array><string>$SERVER/run.sh</string></array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>EnvironmentVariables</key><dict>
    <key>PATH</key><string>$(dirname "$CLAUDE_BIN"):/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin</string>
  </dict>
  <key>StandardOutPath</key><string>$SERVER/logs/rosey.log</string>
  <key>StandardErrorPath</key><string>$SERVER/logs/rosey.log</string>
</dict></plist>
PL
    launchctl unload "$PLIST" 2>/dev/null || true
    launchctl load "$PLIST"
    say "Installed launchd agent (logs: $SERVER/logs/rosey.log)"
  else
    say "No systemd/launchd found; start it yourself with: $SERVER/run.sh"
  fi
fi

# 6. Tell the grown-up what to put in the tablet -------------------------------
IP="$( (hostname -I 2>/dev/null | awk '{print $1}') || true)"
[ -z "$IP" ] && IP="$(ipconfig getifaddr en0 2>/dev/null || echo '<this-machine-ip>')"
say "Done! Tablet settings:"
echo "    Server address: http://$IP:$ROSEY_PORT"
echo "    Token:          $ROSEY_TOKEN"
echo
echo "Test it:  curl -s -H 'X-Rosey-Token: $ROSEY_TOKEN' -H 'Content-Type: application/json' \\"
echo "            -d '{\"text\":\"tell me a joke\"}' http://localhost:$ROSEY_PORT/chat"
