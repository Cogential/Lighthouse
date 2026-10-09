#!/bin/sh
# Packages the Steam Frame build: package.sh <build dir> <lighthouse.o2r> <libSDL2-2.0.so.0> <gamecontrollerdb.txt> <out.zip>
# Makes lighthouse-steam-frame/ with Lighthouse, run.sh, the port's data and lib/ (our SDL2 plus the libraries SteamOS
# lacks). Never add a ROM or bk.o2r here: the release is public.
set -eu
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD="$1" O2R="$2" SDL="$3" DB="$4" OUT="$5"
STAGE="$(mktemp -d)"
APP="$STAGE/lighthouse-steam-frame"
mkdir -p "$APP/lib"

install -m 755 "$BUILD/Lighthouse" "$APP/Lighthouse"
strip "$APP/Lighthouse"
install -m 755 "$ROOT/steamframe/run.sh" "$APP/run.sh"
cp "$O2R" "$APP/lighthouse.o2r"
cp "$ROOT/config.yml" "$APP/"
cp -r "$ROOT/assets" "$APP/assets"
cp "$DB" "$APP/gamecontrollerdb.txt"
cp "$ROOT/steamframe/README-frame.txt" "$APP/README.txt"
cp "$ROOT/LICENSE.md" "$APP/LICENSE.md"

# Our SDL2 loads X11/Wayland/audio at run time; the rest the Frame doesn't have. Not glibc, libstdc++, libgcc_s
# or GL: the Frame's are newer.
cp -L "$SDL" "$APP/lib/libSDL2-2.0.so.0"
for lib in libSDL2_net-2.0.so.0 libzip.so.4 libtinyxml2.so.10 libspdlog.so.1.12 libfmt.so.9 \
           libogg.so.0 libvorbis.so.0 libvorbisenc.so.2 libvorbisfile.so.3; do
    cp -L "/usr/lib/aarch64-linux-gnu/$lib" "$APP/lib/"
done

if find "$APP" \( -iname '*.z64' -o -iname '*.n64' -o -iname '*.v64' -o -name 'bk.o2r' \) | grep -q .; then
    echo "a ROM or bk.o2r ended up in the package" >&2
    exit 1
fi
rm -f "$OUT"
(cd "$STAGE" && zip -qr9 "$OUT" lighthouse-steam-frame)
rm -rf "$STAGE"
