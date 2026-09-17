#!/bin/sh
# The source model for Cat Hat: "Kitten" by FainoDS on Sketchfab, CC BY 4.0
# (https://sketchfab.com/3d-models/kitten-4ae3704cd65b423ab994545988d4db1a).
# Sketchfab downloads need an account, so this takes Objaverse's copy of the same upload. Only
# tools/cat/build_cat.py reads it; the app ships what that script writes to assets/cat/.
set -e
cd "$(dirname "$0")"
[ -s kitten.glb ] && exit 0
curl -fL -o kitten.glb \
  "https://huggingface.co/datasets/allenai/objaverse/resolve/main/glbs/000-137/4ae3704cd65b423ab994545988d4db1a.glb"
