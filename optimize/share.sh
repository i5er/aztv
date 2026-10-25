#!/usr/bin/env bash
# How the setup went, for the TV's owner to send to UrTV's author if they want to: the TV model and Android
# version, how long each step took and where it stopped, memory before and after, the protected-features check,
# and the agent's notes. Nothing personal (no city, clubs, apps, names, network addresses or paths).
#   optimize/share.sh "what went wrong, how long it cost, how it was fixed"
# Writes optimize/run/share.md and a QR code for the phone (most people use X there, not on this computer): it
# opens docs/share/ on GitHub Pages with the summary, whose button copies it and opens a message to the author.
set -euo pipefail
cd "$(dirname "$0")/.."
PY="${PY:-optimize/venv/bin/python}"
"$PY" -c "import segno" 2> /dev/null || "$PY" -m pip install -q segno || true   # the QR code (older setups)
"$PY" -I optimize/share.py "$@"
