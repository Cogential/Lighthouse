# Lighthouse for Android (unofficial build)

Wraps the Lighthouse 1.1.0 source in an SDL2 Android app (arm64, OpenGL ES 3).

## Build

Requirements: JDK 17, Android SDK with `platforms;android-35`, `build-tools;35.0.1`,
`ndk;28.2.13676358`, `cmake;3.31.6` (set `sdk.dir` in `local.properties`).

1. Stage the platform-independent game data (`lighthouse.o2r` from an official Lighthouse
   release, plus this repo's `assets/` and `config.yml`):

       ./scripts/stage-assets.sh <dir containing lighthouse.o2r>

   (From the Linux release: `./lighthouse.appimage --appimage-extract`, then use `squashfs-root/usr/bin`.)

2. Build:

       ./gradlew assembleRelease

   Output: `app/build/outputs/apk/release/app-release.apk` (signed with the debug key).

## Using it

On first launch the app unpacks its data into `Android/data/com.cogent.lighthouse/files/` and
asks for your Banjo-Kazooie (USA) ROM (.z64/.n64/.v64). The ROM is copied there as
`baserom.us.z64`, then Lighthouse's own extractor builds `bk.o2r` on-device.
Alternatively, copy an existing `bk.o2r` from a desktop install into that folder over USB.

A gamepad is recommended; there is no on-screen touch controller.

## Source changes vs upstream

- `CMakeLists.txt`: Android builds `libmain.so` (SDL convention), uses GLES3, pulls
  ogg/vorbis/SDL2_net via `cmake/android-deps.cmake`, skips the host-side Torch tool
  and `-mcpu=native`, downgrades NDK's `-Werror=format-security`.
- `src/port/Game.cpp`: keep the `SDL_main` symbol on Android.
- `FlagTracer.cpp` / `AssetTrace.cpp`: skip `execinfo.h` backtraces (bionic needs API 33+).
- `SaveManager.cpp`, `Rando.cpp`, `RefreshOptions.cpp`: save/randomizer folder paths resolved on
  first use instead of in static initializers (on Android they need SDL's JNI bridge, which isn't
  up yet when `libmain.so` loads — this crashed at startup).
- `FilePicker.h`: Android uses the in-game ImGui file browser (no desktop file dialog exists).
- `Engine.cpp` / `LighthouseMenuSettings.cpp`: default menu scale 2.0x on Android, matching
  libultraship's Android pre-scale (otherwise Lighthouse reset menus to tiny 1.0x).

- `AndroidManifest.xml`: `allowNativeHeapPointerTagging="false"`. On arm64 Android 11+ the
  allocator tags the top byte of heap pointers; the renderer's segmented-address handling then
  read texture data from the wrong place, so every 3D model texture (and in-game text) came out
  scrambled. Turning tagging off for the app fixed it (verified on a Galaxy S24 Ultra).
- `libultraship/src/fast/backends/gfx_opengl.cpp` (submodule, Cogential/libultraship fork):
  GLES fragment shaders use `highp` instead of `mediump`. Mobile GPUs evaluate mediump as fp16,
  which is too little precision for texel-space texture coordinates.

- `GameExtractor.cpp` / `ExtractFlow.cpp`: ROMs picked in the in-game browser are checked
  (known SHA-1 or Banjo's Backpack romhack) and rejected with a "Wrong ROM" popup; .n64/.v64
  byte order is normalised on load.
- `SetupActivity.java`: the Android ROM importer only accepts the four supported vanilla dumps
  (by SHA-1) and explains what's wrong otherwise; a previously imported unsupported
  `baserom.us.z64` is discarded on launch.

## Testing

`./gradlew assembleRelease -Pabis=x86_64` builds an emulator-friendly APK. Verified on an
Android 14 x86_64 emulator: setup/ROM prompt, ROM validation, data unpacking, engine boot
(GLES3 + ImGui), touch input and the in-game ROM browser. Verified on a Galaxy S24 Ultra
(Snapdragon/Adreno): on-device extraction, gameplay, audio, Bluetooth controller, correct textures.
