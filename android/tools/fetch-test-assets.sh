#!/usr/bin/env bash
# The debug build's stand-in for the camera: MediaPipe's own test portrait, fetched
# rather than committed. Debug builds only; a release APK never contains it.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p app/src/debug/assets/test
curl -fsSL -o app/src/debug/assets/test/portrait.jpg https://storage.googleapis.com/mediapipe-assets/portrait.jpg
echo "fetched app/src/debug/assets/test/portrait.jpg"
