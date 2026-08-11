#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
if [[ ! -d .venv ]]; then
  python3 -m venv .venv
  . .venv/bin/activate
  pip install -r backend/requirements.txt
else
  . .venv/bin/activate
fi
export PYTHONPATH="$ROOT"
exec uvicorn backend.app.main:app --host 0.0.0.0 --port 8000 --reload
