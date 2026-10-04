# Lighthouse: notes for Claude

Lighthouse is a libultraship (LUS) PC port of the Banjo-Kazooie decomp. This fork (github.com/Cogential/Lighthouse,
branch `android`) adds an Android app (`android/`) and a native **Steam Frame** build (ARM64 Linux, SteamOS).

**This repo is public. Never commit or upload a ROM, `bk.o2r`, or any build that bundles one** (the `*-PRIVATE.*`
packages below). They are for the owner's own device only.

## Steam Frame: what we learned

The Steam Frame is Valve's VR headset: Snapdragon 8 Gen 3 (Cortex-X4/A720/A520, ARMv9.2), Adreno 750 on Mesa
(Turnip Vulkan 1.4, freedreno GL), SteamOS 0.4.x for arm64 (glibc 2.39, libstdc++ from gcc 15, `VARIANT_ID="vr"`),
games shown on a 1280x720 virtual screen through gamescope.

- **Android APKs run in Lepton**, Valve's Android 11 container. It gives apps only Wayland pointer, touch and keyboard
  devices: **no gamepads ever reach an Android app**, and there is no clipboard service and no document picker. SDL's
  Java must null-check `CLIPBOARD_SERVICE`, `SENSOR_SERVICE` and `UI_MODE_SERVICE` (done in `android/.../org/libsdl/app`),
  and file pickers must be avoided (bundle the ROM instead: `-PlighthouseRomDir`). The Frame APK variant
  (`-PlighthouseFrame`) swaps the touch pad for a pointer-clickable ☰ menu button and logs input devices.
- **Native ARM64 Linux is the build that gets controllers** (Steam Input hands them to the game as a normal gamepad).
  That is the build to use.

### Building the native Frame build (cross-compiled on x86-64 Linux)

1. **Sysroot**: Ubuntu 24.04 arm64 with the dev packages, via debootstrap (needs `qemu-user-static` + binfmt for the
   second stage; old debootstrap needs `ln -s gutsy /usr/share/debootstrap/scripts/noble`):
   ```sh
   sudo debootstrap --arch=arm64 --variant=minbase --components=main,universe \
     --include=libc6-dev,libstdc++-13-dev,libgcc-13-dev,libsdl2-dev,libsdl2-net-dev,libpng-dev,libzip-dev,nlohmann-json3-dev,libtinyxml2-dev,libspdlog-dev,libfmt-dev,libboost-dev,libgl-dev,libopengl-dev,libegl-dev,libogg-dev,libvorbis-dev,zlib1g-dev,libbz2-dev \
     noble $SYSROOT http://ports.ubuntu.com/ubuntu-ports
   for t in zipcmp zipmerge ziptool; do sudo touch $SYSROOT/usr/bin/$t; done  # libzip's CMake config checks they exist
   ```
2. **Toolchain**: any clang ≥ 18 with lld (the Android NDK's works) and a CMake toolchain file: `CMAKE_SYSTEM_NAME
   Linux`, `CMAKE_SYSTEM_PROCESSOR aarch64`, `CMAKE_SYSROOT $SYSROOT`, `CMAKE_{C,CXX}_COMPILER_TARGET aarch64-linux-gnu`,
   `-fuse-ld=lld`, `CMAKE_FIND_ROOT_PATH_MODE_{LIBRARY,INCLUDE,PACKAGE} ONLY`, and `PKG_CONFIG_SYSROOT_DIR`/`PKG_CONFIG_LIBDIR`
   pointing into the sysroot. CMake ≥ 3.25 (Ubuntu 22.04's 3.22 is too old).
3. **SDL2**: build SDL2 2.30.x from source with the same toolchain. Ubuntu's libSDL2 links X11, Wayland, PulseAudio,
   libdecor, libsamplerate etc. directly; a source build only needs libc/libm and loads the rest at run time.
4. **Configure and build**:
   ```sh
   cmake -S . -B build-linux-arm64 -G Ninja -DCMAKE_TOOLCHAIN_FILE=<file> -DCMAKE_BUILD_TYPE=Release \
     -DCPU_OPTION=-march=armv8.2-a -DLIGHTHOUSE_SELF_CONTAINED=ON '-DCMAKE_BUILD_RPATH=$ORIGIN/lib' -DCMAKE_BUILD_RPATH_USE_ORIGIN=ON
   cmake --build build-linux-arm64 --target Lighthouse
   ```
   - `CPU_OPTION` must be set: the default for aarch64 is `-mcpu=native`, which means the build machine.
   - The host-side Torch tool (`TorchExternal`) is skipped when `CMAKE_CROSSCOMPILING`; ship prebuilt `lighthouse.o2r`.
   - `LIGHTHOUSE_SELF_CONTAINED` (see `src/port/Game.cpp`): at startup, seed `~/.local/share/Lighthouse` from the files
     next to the executable (o2r, `config.yml`, `assets/`, `gamecontrollerdb.txt`, a bundled `baserom.us.z64`), set
     `SHIP_HOME` to it, extract a bundled ROM without prompts (`ExtractFlow.cpp`) and default "Menu Controller
     Navigation" on so Back/View opens the menu (`Engine.cpp`).
5. **Package** a folder: stripped `Lighthouse`, `lib/` (our SDL2 plus SDL2_net, libzip, tinyxml2, spdlog, fmt, ogg,
   vorbis*; the Frame lacks the first five), `lighthouse.o2r`, `config.yml`, `assets/`, `gamecontrollerdb.txt`, `run.sh`
   (`cd` to its folder, `chmod +x ./Lighthouse`, exec it), plus `baserom.us.z64` for a private build. **Don't bundle
   glibc, libstdc++, libgcc_s or GL/Vulkan/X11/Wayland libraries**: the Frame's are newer, and Mesa needs its own.

### Installing on the Frame

- The owner uses **FrameDrop** on a Windows PC, from a USB drive: it unzips, copies to `~/devkit-game/<Name>/`, sets
  `argv ["./<binary>"]` and the compat tool **Steam Linux Runtime 4.0 (arm64)** (correct; tested working). It offers
  no start-command field, and the Windows unzip **drops the executable bit**. A user systemd helper on the Frame
  (`~/.local/bin/devkit-exec-fix.sh`, `devkit-exec-fix.{service,path,timer}`) re-marks ELF/`#!` files executable.
- Launching outside Steam (over SSH) runs on gamescope's `DISPLAY=:1` with `XDG_RUNTIME_DIR=/run/user/1000`, but
  Steam Input only applies to games Steam launches.
- LUS only goes fullscreen automatically for `VARIANT_ID=steamdeck`; on the Frame the player turns fullscreen on once
  (otherwise a 640x480 window is stretched and the menu looks huge). The setting persists in `~/.local/share/Lighthouse`.

Other recomp ports for the Frame (Wind Waker / BlueWake, Digimon World) follow the same pattern: native ARM64 Linux,
cross-compiled against the same kind of sysroot, data in `~/.local/share/<Game>`, installed via FrameDrop.
