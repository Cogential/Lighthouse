#!/bin/sh
# Starts Lighthouse (Banjo-Kazooie) on the Steam Frame. The game keeps data and saves in ~/.local/share/Lighthouse,
# so reinstalling this folder keeps them.
D="$(cd "$(dirname "$0")" && pwd)"
cd "$D" || exit 1

# Copies via Windows/FAT drives drop the executable bit; restore it.
chmod +x ./Lighthouse 2>/dev/null

exec ./Lighthouse "$@"
