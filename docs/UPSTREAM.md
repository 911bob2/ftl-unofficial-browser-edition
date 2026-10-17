# Engine provenance

The directly maintained engine derives from Pocket Wormhole commit `001b19d99bbbfe71d9d38b557eed12d8625a0dba` (2026-09-25), based on Project Wormhole / XFTL. The first local Git commit preserves the source material used to establish the fork.

The current transformed engine was materialized into `engine/src/main/java` before introducing further changes. All 286 materialized sources matched their generated counterparts byte for byte. The direct-source baseline passes 54 vanilla layout cases, 84 AE layout cases, 137 encounters, focused mechanics/progress/combat checks and twelve accelerated campaign traversals. These checks verify source conversion and existing behavior, not complete original-game parity.

Source-rewriting scripts and the duplicate vendor checkout have been removed from the maintained tree. Java/Kotlin sources, resources under `engine/resources`, and dependencies under `third_party` are authoritative. Normal builds compile these sources directly with TeaVM 0.15.0. Browser adapters supply WebGL2, Web Audio, input and native XML persistence. ASP.NET handles identity, private assets and cloud saves.

The project is AGPL-3.0-or-later; the upstream GPL-2.0-or-later text and embedded third-party notices are retained in `third_party/notices`. Commercial assets, credentials, diagnostic logs, binary analysis and decompiler output are excluded from source history and release images. The browser zlib bridge uses fflate 0.8.3 under MIT; its notice is in `web/vendor/fflate-LICENSE`. Tailwind/daisyUI are pinned in package-lock.json and compiled locally.

Original behavior is investigated against the installed FTL 1.6.14 binary; selected findings and their limitations are documented in ORIGINAL_BEHAVIOR.md. Hyperspace signatures serve as navigation aids, not authority for unmodified-game behavior. Acceptance and inventory records distinguish implemented behavior from verified parity.
