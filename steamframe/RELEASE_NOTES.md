Native ARM64 Linux build of [Lighthouse](https://github.com/IsleOPorts/Lighthouse) (Banjo-Kazooie PC port) for the Steam Frame.

**No ROM included.** Put your own Banjo-Kazooie ROM (US, `.z64`) in `~/.local/share/Lighthouse/` or next to `run.sh`; the first launch turns it into `bk.o2r` with no prompts. Saves and settings stay in `~/.local/share/Lighthouse`.

Download `lighthouse-steam-frame-arm64.zip`, unzip it into `~/devkit-game/<name>/` and point Steam at `run.sh` (no compatibility tool, or Steam Linux Runtime 4.0 arm64), or install it with Frametanium.

**Controller fix:** Steam describes its virtual pad as "Steam Frame Controllers", which SDL has no mapping for, so only D-pad up, A and B worked. The game now ignores that description, so the whole controller works.

Built natively on GitHub's `ubuntu-24.04-arm` runner (glibc 2.39, as SteamOS on the Frame) for ARMv8.2.
