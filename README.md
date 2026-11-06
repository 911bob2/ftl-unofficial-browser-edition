# FTL: Unofficial Browser Edition

A fan-made version of [FTL: Faster Than Light](https://subsetgames.com/ftl.html) for desktop browsers and Android,
including Advanced Edition content and mods: Hyperspace, Multiverse and Slipstream mods. Cloud or local saves.

- **Play:** <https://ftl.911bob.dev/>
- **Feedback and bug reports:** [Discord](https://discord.gg/KzYfUHfugJ)
- **License:** [AGPL-3.0-or-later](LICENSE)

> **Unofficial.** FTL: Faster Than Light is © Subset Games. This project is not made, endorsed or supported by
> Subset Games. If you represent Subset Games and would like it taken down, email **ftl@911bob.dev** and it will be.

## You need your own copy of FTL

This repository and its Docker image contain **no game files**: no `ftl.dat` and nothing extracted from it.
The game's graphics, sound and text come from the `ftl.dat` in your own installation of FTL 1.6 (Steam, GOG,
Humble or the official site). On Windows and Linux it sits in the game's install folder; on macOS it is inside
the app bundle under `Contents/Resources`.

- **Android and guests or local-only players** choose their own `ftl.dat` once in the browser. It is cached on that device
  and never uploaded.
- **The hosted instance** at ftl.911bob.dev is prepared from the operator's own legally obtained copy for
  personal use. Those server-side game assets are only served to accounts the operator creates; self-registered
  and local-only players always bring their own file.

## Status

Version 2.0.5. The goal is 1:1 behavior with desktop FTL 1.6.14 and, with mods, Hyperspace 1.23.2 and Multiverse 5.5.1 (gameplay,
generation from seeds, menus, controls, visuals and sound). Most of it is playable end to end, but full parity is
**not** established; see [feature status](docs/STATUS.md) and the [parity matrix](docs/parity/README.md). Original FTL
saves are not interchangeable, and multiplayer is not supported.

## Mods

Mods are merged when the game starts, over your clean `ftl.dat`, with [ftlman](https://github.com/afishhh/ftlman)'s
rules. [Hyperspace](https://github.com/FTL-Hyperspace/FTL-Hyperspace)'s features are reimplemented in the engine, and
mods' Lua scripts run unchanged. Add any Slipstream `.ftl` or `.zip` as a local file.

**No mod files are in this repository or image.** Multiverse downloads from its own official release to your device.
A server operator may place the unmodified `Hyperspace.ftl` on their server to offer it to signed-in players
([notice](third_party/notices/Hyperspace-NOTICE.md)). A save is tied to its mod list; details: [mods](docs/MODS.md).

## Android

A native app with the same engine, accounts and cloud saves, fully playable offline, with touch controls in the style
of FTL's iPad version. It is built from `android/`; see [Android](docs/ANDROID.md).

## How it works

The engine is Java/Kotlin from [Project Wormhole](https://gitlab.com/znixian/xftl) via
[Pocket Wormhole](https://github.com/adamkulik/pocket-wormhole), compiled to JavaScript with TeaVM and rendered
through WebGL2 and Web Audio. An ASP.NET Core server provides accounts, cloud saves with revision history, and
the game assets prepared from the operator's `ftl.dat`. The website is Tailwind/daisyUI. Everything is packaged
with Docker; no reverse proxy is bundled.

Behavior is checked against the original game. Comments cite the functions in FTL 1.6.14 and Hyperspace that a rule
was matched against.

## Self-hosting

Docker Desktop (Linux containers) or Docker Engine is all you need; no Java, .NET or Node installation.

1. Put your own game file at `game-files/ftl.dat` (ignored by Git and excluded from the Docker build context).
2. `docker compose --profile prepare run --build --rm prepare-assets` prepares `runtime/assets` privately.
3. `docker compose up -d --build app`, then open <http://localhost:8080>.
4. A new instance creates one account, `admin`. Copy its temporary password out with
   `docker compose cp app:/data/bootstrap-credentials.txt ./runtime/bootstrap-credentials.txt`, sign in, set a new
   password, and delete both copies of the file.

Visitors can register themselves as External users (they bring their own `ftl.dat`) or play local-only without
an account. Administrators create Normal accounts that may use the server's prepared assets. Roles, registration
limits and the first-visit flow are described in [website and accounts](docs/WEBSITE.md).

The `ftl-data` volume holds the SQLite database, cookie encryption keys, accounts and saves (the last ten
revisions of each save). Recreating the container keeps it; `docker compose down -v` deletes it.

### Behind a reverse proxy

Copy `.env.example` to `.env`. `PUBLIC_ORIGIN` is the exact HTTPS origin without a trailing slash;
`TRUSTED_PROXIES` is your proxy container's fixed IP and `NPM_NETWORK` its Docker network. Run
`docker compose -f compose.yml -f compose.vps.yml up -d --build app`, point the proxy at `app:8080` over HTTP and
terminate HTTPS there. Disable proxy caching of `/api/` and allow requests up to 20 MiB. Production requires an
HTTPS origin and explicit proxy trust; cookies are `__Host-`, Secure, HttpOnly and SameSite=Strict.

If you run a modified version for other people, the AGPL requires you to offer them its source.

### Maintenance

- `docker compose exec app dotnet Ftl.Server.dll backup` writes a consistent database backup plus the encryption
  keys to `/data/backups/<UTC timestamp>`. Restore only while the app is stopped.
- `docker compose exec app dotnet Ftl.Server.dll recover-admin` re-enables the administrator and writes a fresh
  temporary password to `/data/admin-recovery.txt`; passwords never go to container logs.

## Saves

Every save the game writes goes to this browser first (IndexedDB) and syncs to your account in the background, so
the network never pauses the game. An account holds up to 30 saves (256 MB with their snapshots), each with its last
ten versions as recovery snapshots, its ordered mod list and a summary (ship, sector, scrap, hull, play time). As in
the original, a save holds its own unlocks, achievements, scores and statistics and one active run: a new save starts
with only the default ships, and language, volumes and hotkeys stay with the device. One game plays a save at a time: another device or tab shows
"In use on …" and may override the lock; a save changed in two places asks which whole copy to keep, unlocks included. Local-only saves stay
in the browser and move into an account with **Enable cloud saves**. Each save downloads as a backup; the account
page offers account deletion with a backup of every save. Details: [saves](docs/SAVES.md).

## Development

`gradle :engine:generateJavaScript` (JDK 17, Gradle 8.7) compiles the engine; `npm ci && npm run build:web`
builds the stylesheet. The Dockerfile does both. The packaged test runner, its suites and the in-site **Tests**
page are described in [testing](docs/TESTS.md). Point mutating tests only at disposable instances, never at a
real account database.

## Credits and licenses

- **FTL: Faster Than Light** and its artwork are © [Subset Games](https://subsetgames.com/). The website shell
  uses three images from Subset's public FTL website (logo, star background, honeycomb texture), with sources in
  [web/ftl/README.md](web/ftl/README.md). No game files are distributed.
- **Engine:** [Project Wormhole / XFTL](https://gitlab.com/znixian/xftl) by Campbell Suter (ZNix) and contributors,
  and [Pocket Wormhole](https://github.com/adamkulik/pocket-wormhole), both GPL-2.0-or-later, used here under
  version 3 terms. Parts of [Slipstream Mod Manager](https://github.com/Vhati/Slipstream-Mod-Manager) by Vhati.
  Provenance: [upstream notes](docs/UPSTREAM.md).
- **Mod support:** engine code and Lua API ported from [FTL Hyperspace](https://github.com/FTL-Hyperspace/FTL-Hyperspace)
  (GPL-3.0-or-later) by its contributors; merge rules and Lua patch API ported from
  [ftlman](https://github.com/afishhh/ftlman) (GPL-2.0) by afishhh and contributors, used here under the AGPL.
  [Multiverse](https://ftlmultiverse.boards.net/thread/155/multiverse-base-version-stars-stripes) is by its own team; it is supported, not included.
- **Libraries and fonts:** Lua 5.3.6 (MIT), JOrbis (LGPL-2.1), JDOM, SLF4J, Jackson, fflate, Tailwind CSS,
  daisyUI, Chakra Petch (SIL OFL) and Roboto (Apache-2.0). Full notices: [third_party/notices](third_party/notices/NOTICE.md), also served
  at `/Licenses`.
- Discord and GitHub logos are trademarks of their owners, used to link to this project's server and source.

This project is licensed under the **GNU Affero General Public License v3.0 or later**; see [LICENSE](LICENSE).

Contact: **ftl@911bob.dev** (takedown or other requests) · [Discord](https://discord.gg/KzYfUHfugJ) for feedback.
