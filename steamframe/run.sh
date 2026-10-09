#!/bin/sh
# Starts Lighthouse (Banjo-Kazooie) on the Steam Frame. The game keeps data and saves in ~/.local/share/Lighthouse,
# so reinstalling this folder keeps them.
D="$(cd "$(dirname "$0")" && pwd)"
cd "$D" || exit 1

# Copies via Windows/FAT drives drop the executable bit; restore it.
chmod +x ./Lighthouse 2>/dev/null

# Steam describes its virtual Xbox pad as "Steam Frame Controllers" (28de:11e0), which SDL has no mapping for, so
# only D-pad up, A and B would work. (The game does this too, for installs that start ./Lighthouse directly.)
unset SteamVirtualGamepadInfo

exec ./Lighthouse "$@"
