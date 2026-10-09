Lighthouse (Banjo-Kazooie) for the Steam Frame
==============================================

Native ARM64 Linux build of Lighthouse, the Banjo-Kazooie PC port. No ROM is included.

1. Unzip into ~/devkit-game/<name>/ (Frametanium does this for you) and point Steam at run.sh
   (no compatibility tool, or Steam Linux Runtime 4.0 arm64).
2. Put your own Banjo-Kazooie ROM (US, .z64) in ~/.local/share/Lighthouse/ or next to run.sh. The first start
   turns it into bk.o2r with no prompts.

Data, settings and saves live in ~/.local/share/Lighthouse, so reinstalling or updating keeps them.
View (Back) opens the menu with the controller; turn on fullscreen there once.
