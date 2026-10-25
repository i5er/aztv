#!/usr/bin/env bash
# The AZ TV download site: copies the page, the APKs from android/out, fonts and screenshots into site/out, and serves it
# on this computer's Wi-Fi so the TV can open it in Downloader. Upload site/out as-is to any static host instead.
#   android/build-local.sh && site/serve.sh [port]      → http://<this computer>:8000
set -euo pipefail
cd "$(dirname "$0")/.."
PORT=${1:-8000}
O=site/out
rm -rf "$O"; mkdir -p "$O/img" "$O/fonts"
cp site/index.html "$O/"
cp android/out/urtv-home.apk "$O/az-tv-home.apk"
cp android/out/urtv-screensaver.apk "$O/az-tv-screensaver.apk"
cp assets/fonts/Thmanyah*.woff2 "$O/fonts/"
cp site/img/* "$O/img/"
[ "${NO_SERVE:-}" = 1 ] && { echo "built $O"; exit 0; }
IP=$(ipconfig getifaddr en0 2>/dev/null || hostname -I 2>/dev/null | awk '{print $1}')
echo "On the TV, in Downloader: ${IP:-<this computer>}:$PORT"
exec python3 -m http.server "$PORT" --bind 0.0.0.0 -d "$O"
