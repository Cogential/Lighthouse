#!/usr/bin/env bash
# Stage the platform-independent game data that gets bundled into the APK.
# Usage: stage-assets.sh <dir containing lighthouse.o2r>   (e.g. an extracted Linux release AppImage's usr/bin)
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
repo="$(cd "$here/.." && pwd)"
src="${1:?path to dir containing lighthouse.o2r}"
out="$here/game-data"
rm -rf "$out"
mkdir -p "$out"
cp "$src/lighthouse.o2r" "$out/"
cp "$repo/config.yml" "$out/"
cp -r "$repo/assets" "$out/assets"
if [ -f "$src/gamecontrollerdb.txt" ]; then
    cp "$src/gamecontrollerdb.txt" "$out/"
elif [ -f "$src/../../../gamecontrollerdb.txt" ]; then
    cp "$src/../../../gamecontrollerdb.txt" "$out/"
fi
echo "Staged game data in $out"
