#!/usr/bin/env bash
# One-time setup: the pure-Python ADB client (no adb binary needed, works where macOS blocks adb).
# The APKs are built by GitHub Actions, so no Android SDK is needed here. Windows: run it in Git Bash.
# Adapted from github.com/mihirgupta0900/tv-home (MIT).
set -euo pipefail
cd "$(dirname "$0")"
. ./python.sh
pick_python || exit 1
"${SYS_PY[@]}" -m venv venv
if [ ! -e venv/bin/python ] && [ -e venv/Scripts/python.exe ]; then
  # Windows puts it in venv\Scripts: every script calls optimize/venv/bin/python, so point that there
  mkdir -p venv/bin
  printf '#!/usr/bin/env bash\nexec "$(dirname "$0")/../Scripts/python.exe" "$@"\n' > venv/bin/python
  chmod +x venv/bin/python
fi
venv/bin/python -m pip install -q --no-cache-dir -r requirements.txt
echo "✓ Python ADB client in optimize/venv"
