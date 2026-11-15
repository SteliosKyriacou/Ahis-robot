#!/usr/bin/env bash
# Start Rosey-Cherry's brain server. Settings come from server/.env (created by scripts/install_server.sh).
set -euo pipefail
cd "$(dirname "$0")"
if [ -f .env ]; then set -a; . ./.env; set +a; fi
exec .venv/bin/uvicorn rosey.app:app --host "${ROSEY_HOST:-0.0.0.0}" --port "${ROSEY_PORT:-8765}"
