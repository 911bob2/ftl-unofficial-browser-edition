# Notices & licenses

This browser fork directly maintains the transformed Project Wormhole / Pocket
Wormhole sources. Upstream notices below are retained for provenance. The engine
build no longer rewrites a duplicate upstream checkout. Original FTL content and
private reverse-engineering output are not distributed in application images.

The website uses Chakra Petch (SIL Open Font License), Tailwind CSS and daisyUI (MIT), and browser compression/export
uses fflate (MIT). Their license texts are included beside this notice. Roboto's
Apache-2.0 license is also retained. The packaged notices can be read at `/Licenses`.

Pocket Wormhole is a native Android port of [Project Wormhole]
(https://gitlab.com/znixian/xftl), a clean-room reimplementation of the FTL
engine. The website shell uses three public images from Subset Games' own website (the FTL
logo, a star background and a honeycomb texture); their source URLs are recorded in
`web/ftl/README.md`. No game files are included: neither the source nor the Docker
image contains `ftl.dat` or anything extracted from it. A server operator prepares
their instance from their own legally obtained copy; every player, including guests,
supplies `ftl.dat` from their own copy of FTL: Faster Than Light, and it stays in
their browser.

FTL: Faster Than Light is © Subset Games. This is an unofficial fan project, not
affiliated with, endorsed by, or connected to Subset Games in any way. If you
represent Subset Games and would like it taken down, email ftl@911bob.dev and it
will be.

Discord and GitHub logos are trademarks of their owners, used unmodified to link to
the project's community server and source code.

## This project

FTL: Unofficial Browser Edition, source at
https://github.com/911bob2/ftl-unofficial-browser-edition.
Copyright © 2026 911bob2 and the Pocket Wormhole and Project Wormhole contributors.

Licensed under the [GNU Affero General Public License v3.0](AGPL-3.0.txt) or (at your
option) any later version. The upstream Project Wormhole / Pocket Wormhole code is
GPL-2.0-or-later ([text](LICENSE)) and is used here under version 3 terms. Anyone
who runs a modified version for users over a network must offer those users its
source (AGPL section 13).

## Third-party components

| Component | Copyright | License |
|---|---|---|
| [Project Wormhole (xftl)](https://gitlab.com/znixian/xftl) | Campbell Suter (ZNix) and contributors | GPL-2.0 (or later, per upstream) |
| [Slipstream Mod Manager](https://github.com/Vhati/Slipstream-Mod-Manager) (partial, `net.vhati.*`) | Vhati and contributors | GPL-2.0 |
| [JOrbis / JOgg](http://www.jcraft.com/jorbis/) (`com.jcraft.*`, `libs/jorbis-core.jar`) | ymnk, JCraft,Inc. | LGPL-2.1 (modifications included with the source) |
| PNGDecoder (`org.newdawn.slick.opengl.PNGDecoder`) | 2008-2010 Matthias Mann | BSD-3-clause |
| [JDOM 2](https://github.com/hunterhacker/jdom) | JDOM authors | JDOM license (Apache-style) |
| [SLF4J](https://www.slf4j.org/) | QOS.ch | MIT |
| [Jackson](https://github.com/FasterXML/jackson) | FasterXML | Apache-2.0 |
| [JetBrains Annotations](https://github.com/JetBrains/java-annotations) | JetBrains | Apache-2.0 |
| Roboto font (baked for the ship editor) | Google | Apache-2.0 |
| Android, JDK | Google / OpenJDK contributors | Android SDK & OpenJDK licenses |

`libs/jorbis-core.jar` is JOrbis 0.0.17 repackaged (JOgg + JOrbis without the
engine's patched `VorbisFile`, which is compiled from source in-tree).

The Android port layer (`com.pocketwormhole.android.*`, `org.lwjgl.*` shims)
is original code written for this port, licensed under the same GPL-2.0
(or later) terms as the rest of the project.
