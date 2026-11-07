# Saves: offline-first model, sync and locks (save model 3)

Status: design 2026-10-29; the website client is implemented (2026-10-30, "Browser client" below); since 2026-10-30 a save holds its profile (save model 3). Replaces the single cloud save with slots, the browser-only modded saves and the
pause-on-disconnect behaviour described in [MODS.md](MODS.md) ("Saves") and [WEBSITE.md](WEBSITE.md). The same rules
hold in the browser and in the Android app ([ANDROID.md](ANDROID.md)); they are written once in Kotlin
(`savecore/`, compiled into the app and by TeaVM into `savecore.js` for the website) and tested against shared JSON
vectors (`savecore/vectors/`).

## Principles

1. **The device first.** Every save the engine writes goes to the device store at once (IndexedDB in the browser,
   app storage on Android). The network never pauses the game; a background syncer uploads when it can.
2. **One plain list.** An account has up to **30 saves**. No families, parked slots or slot swaps. Each save is locked
   to its mod set.
3. **A save is everything the original keeps in its profile and run files**, as in the original game: its own unlocks and
   at most one active run. Saves share none of it.
4. **Locks per save.** A save open on one device or tab is locked there; any other save plays freely elsewhere.
5. **Nothing is lost silently.** When both sides changed, the player chooses; an override warns first and the
   overridden device says so when it learns.

## A save

| Field | Meaning |
| --- | --- |
| `id` | UUID made by the device that created it, so offline-created saves sync later without renumbering |
| `name` | 1 to 40 characters, no control characters |
| `setup` | `key` = `vanilla` or the mod-set hash; `mods` = ordered list of `{name, version, sha256, size, verified}` |
| `run` | the engine's run XML (`xftlSaveGame`): the one active run, or none (a new save, or the run ended) |
| `profile` | the engine's profile XML (`xftl-profile`): unlocked ships and layouts, achievements, high scores, statistics, Hyperspace profile data and meta variables; none until the engine first starts it |
| `meta.playedOn` | `{platform: browser\|android, device: "Pixel 8", deviceId, at}` of the last write |
| `meta.engineBuild`, `meta.gameData` | engine build and game-data version (`assetVersion` = SHA-256 of the `ftl.dat`) |
| `meta.summary` | `{ship, shipId, sector, scrap, hull, maxHull, playSeconds}` read from the run (savecore `RunSummary`) |
| `revision` | cloud revision (server counter, `If-Match`); 0 before the first upload |
| `sync` | device only: `base` (cloud revision this copy derives from), `dirty`, `status` (below) |

The **mod-set hash** stays sha256 of `{"loader":1,"mods":[archive sha256 in order]}` (unchanged, so existing modded
saves keep their setup). `vanilla` saves have an empty mod list.

**A new save** starts like a fresh install: only the default unlocks (the Hyperspace "Transfer vanilla progress?" question
is gone with the shared profile). When a run ends, the next run starts in the same save and keeps its unlocks. Starting a
new run while one is active replaces it, after the original's warning.

**Settings are not part of a save**: language, volumes, hotkeys and options belong to the device (the original's
`settings.ini`), for every save and account on it, and do not sync. The first-start language list therefore asks once per
device. Browser: localStorage `ftl-settings`; Android: `settings.xml`. The engine writes them as `xftl-settings` (kind
`settings` of its save callback).

## Sync status and decisions

`savecore` decides from the device copy and the cloud metadata alone; the UI only renders the outcome.

| Device copy | Cloud | Status | Action |
| --- | --- | --- | --- |
| never uploaded | none | `local` | upload (create) when signed in and online |
| clean, base = cloud revision | same | `synced` | none |
| dirty, base = cloud revision | same | `pending` | upload with `If-Match: base` (needs the lock) |
| clean, base < cloud revision | newer | `behind` | fetch the cloud copy (nothing to lose) |
| dirty, base < cloud revision | newer | `conflict` | **ask**: "Use the cloud copy" (this device's unsynced progress is discarded) or "Keep this device's copy" (overwrites the cloud; needs the lock) |
| any | deleted on another device | `deleted` | clean: remove here; dirty: ask "Upload again" or "Delete here too" |
| none | exists | `cloud` | listed; the run downloads when the save is played or "Keep on this device" is on |

The run and the profile are one unit under one revision and one lock: either one changing makes the save dirty, and a
conflict or Override lock keeps **one whole copy**, so the discarded device also loses the unlocks it earned since its last
sync (the discarded cloud version stays a recovery snapshot). Nothing merges.

A signed-out or local-only player has only `local` saves. **Enable cloud saves** uploads all of them (the migration
path for today's local-only saves).

## Locks

A lock names its **holder instance**: one per running game (a browser tab keeps its instance id in
`sessionStorage`, so a reload keeps it; the Android app has one instance per install). It shows the device name and
`lastSeen` (the holder's last contact). Locks **never expire**.

- **Play** takes the lock (`POST /api/saves/{id}/lock`). Free, or already held by this instance: granted.
- Held elsewhere: the save shows **"In use on Pixel 8 · last seen 14:05"** with **Override lock**. The dialog warns:
  "Pixel 8 may have progress and unlocks that have not synced yet. Overriding discards them; nothing is kept." Overriding transfers
  the lock at once (the server records who overrode whom and when).
- **Save and close** uploads the last save and releases the lock. Closing a tab or the app without it keeps the lock.
- An **overridden instance** learns on its next contact (upload, heartbeat or lock refresh: `409 lock_overridden`).
  It stops the game, discards its unsynced progress (no copy is kept, by decision) and says
  "This save was taken over on Pixel 8 at 14:05. Progress and unlocks since your last sync were discarded."
- Offline play needs no lock. On reconnect the device takes the lock if it is free; if another instance holds it, the
  player sees the lock with Override, or chooses "Use the cloud copy".
- Two tabs of one browser follow the same rule as two devices.

## Server API (`/api/saves`)

All under the existing cookie session, CSRF header and same-origin rules; nothing in [SECURITY_HARDENING.md](SECURITY_HARDENING.md)
is relaxed. Requests carry `X-FTL-Instance` (instance id) and `X-FTL-Device` (`platform;deviceId;name`).

| Method and path | Does |
| --- | --- |
| `GET /api/saves` | list: metadata, revision, lock, deleted tombstones (`since=` for deltas) |
| `PUT /api/saves/{id}` | create or update run + profile + metadata; `If-Match` revision (`0` = create); needs the lock when one exists; makes a snapshot |
| `GET /api/saves/{id}` | metadata + run + profile, `ETag` revision |
| `PATCH /api/saves/{id}` | rename; Advanced: change the mod list (the save moves to another setup) |
| `DELETE /api/saves/{id}` | delete (tombstone kept 90 days); refused while another instance holds the lock, unless `?override=true`: the holder then hears `deleted` on its next contact |
| `POST /api/saves/{id}/lock` | `{override}` → lock, or `409 locked` with the holder |
| `POST /api/saves/{id}/unlock` | release (holder only) |
| `GET /api/saves/{id}/snapshots`, `GET …/snapshots/{rev}`, `POST …/snapshots/{rev}/restore` | recovery snapshots of every save, the last 10 each; restore needs the lock |

Limits: 30 saves, 10 snapshots per save, 16 MiB per run, 4 MiB per profile, **256 MiB per account** in total (runs,
profiles, metadata and snapshots, which hold both), 300 save writes a minute per account (`429`), the newest 500 tombstones
per account and 20 unanswered overrides per save. Checks stay structural (`SaveValidation`: root element, size, depth, no
DTD; a profile also has at most 100 000 elements and 256 attributes per element). Custom mod archives never reach the
server; a save names them and another device shows "missing mod: name, sha256".

Wire details (server: `server/CloudSaves.cs`, `server/SavesMigration.cs`; tests:
`tests/saves-v2.mjs`):

- Headers: `X-FTL-Instance` is 1 to 64 of `[A-Za-z0-9-]`; `X-FTL-Device` is `browser|android;deviceId;name` with the
  same id rule and a name of 1 to 60 characters without control characters, percent-encoded (`encodeURIComponent`)
  when it is not ASCII. Both are required on lock, unlock, PUT, restore and DELETE (`400 bad_device`). `If-Match` is
  `"<revision>"` (`400 bad_if_match`). JSON is camelCase; times are UTC ISO with `Z`; errors are `{error, code}`.
- `GET /api/saves` → `{saves: SaveInfo[], deleted: [{id, deletedUtc}], limits: {saves, snapshots, bytes}, usedBytes,
  nextSince}`; `SaveInfo` = `{id, name, setup, createdUtc, updatedUtc, revision, meta, hasRun, runBytes, hasProfile, profileBytes, lock}`,
  `lock` = `{instance, deviceId, device, platform, since, lastSeen}` or null, where `instance` is the caller's own id
  (its `X-FTL-Instance`) or `other`: the id is what acts as the holder, so it is shown to nobody else. `?since=` returns what changed after
  it, lock changes included; pass `nextSince` (a minute behind the server clock, so nothing committed late is missed).
- `setup` = `{key, mods: [{name, version, sha256, size, verified}]}`, at most 64 mods, key `vanilla` exactly when
  there are none (else 64 lowercase hex); `meta` is any JSON object up to 8 KiB.
- Codes: `not_found` (404); `deleted` (410 on reads and lock calls, 409 on PUT, with `deletedUtc`);
  `revision_conflict` (409, with the current `revision`); `saves_full`, `storage_full` (409); `locked` (409, with
  `lock`); `lock_overridden` (409, with `lock` and `overriddenUtc`: this instance was overridden and has not held
  the lock since); `not_holder` (409 on unlock); `already_current` (400, restoring the current revision);
  `bad_name`, `bad_setup`, `bad_meta`, `bad_run`, `bad_profile`, `bad_since` (400); `saves_v2` (410, old endpoints).
- PUT `{name, setup, meta, run, profile, kind}`: `run` null clears the run (it ended); `profile` null or absent keeps the
  stored one (a save this device has not started, an old page). `/api/profiles` answers `410 saves_v2`.
- PUT checks the lock before the revision, so an overridden instance hears `lock_overridden` even when its revision
  is stale. It keeps hearing it on lock, PUT and restore until it holds the lock again, also after the save became
  free (`lock` is then null): the override wins completely. `POST lock {override: true}` takes the lock from a holder;
  `{acknowledge: true}` takes a free lock after the player has seen the takeover notice; plain `{}` takes a free lock
  only when this instance was not overridden. A PUT `kind` is `auto`, `manual` or `import` (anything else is stored as null); restores are `restore`.
- Migrated saves: the active slot's ten newest snapshots are renumbered 1..n, and its save gets revision n (n + 1
  when its run differs from the newest snapshot); other migrated saves start at revision 1. Copying the profile in
  (below) adds one revision.

## Migration

- **Server**, once at startup: a SQLite backup to `/data/backups/pre-saves2-<UTC>` first, then per account the
  active slot (`PlayerSave`) and every parked slot (`SaveSlots`) become saves (setup `vanilla`, names kept), the shared
  profile becomes the `vanilla` profile, and existing snapshots stay with the save that was active. The old tables are
  kept read-only until a later release; `/api/saves/current` and `/api/saves/slots*` answer `410 saves_v2` ("Reload
  the page").
- **Server, profile into the saves**, once at startup after that (flag `saves-profiles-merged`; backup
  `/data/backups/pre-saves3-<UTC>`): each save gets a copy of its setup's old profile and one new revision, so a device
  with nothing unsynced fetches the save, and a device with unsynced progress is asked. A vanilla profile with no vanilla
  save becomes "Save 1" (its unlocks stay). A modded profile with no save has no mod list to build a save from and stays
  in the old read-only table and the backup. Snapshots from before keep their run only; restoring one keeps the current
  profile.
- **The engine reads only the new format.** Every step above and below takes the settings out of an old profile once, outside
  the engine (`OldProfile` in savecore, `SavesMigration.WithoutSettings` on the server); so do imported backups and a write
  a closing tab left behind.
- **Devices, profile into the saves**, once per account (browser: `mergeProfiles` in `web/device-store.mjs`, state
  `profiles-merged|<account>`; Android: `DeviceStore.mergeProfiles`, the folder becomes `profiles.old`): each save gets a
  copy of its setup's profile, unsynced when that profile was. A vanilla profile with no save and no account copy becomes
  "Save 1". The settings the old profiles held become the device's when it has none (one with a language first). The
  server's copies lose theirs, as settings do not sync.
- **Browser**, once per browser profile and account: the local-only save with its parked slots, the modded saves
  (`ftl-mods` stores `saves` and `slots`) and any pending recovery copy become device saves, each with a copy of the old
  profile. A pending copy of the cloud save becomes the unsynced progress of the save the server migrated from that slot
  (or "Recovered progress"). A copy
  of the old data is stored first; the old stores are left untouched and the Saves page offers "Download the old
  saves" and "Remove the old saves".
- The play lease endpoints (`/api/play-lease/*`) go away with the per-account lease.

## Browser client

Modules (all rules come from `savecore.js`, served at `/savecore/savecore.js` and loaded by `web/savecore.mjs`):

| File | Role |
| --- | --- |
| `web/device-store.mjs` | IndexedDB `ftl-saves`: stores `saves`, `runs`, `saveProfiles`, `state`, keyed `account\|id` (`profiles` holds what the migration could not move); local-only play uses the account `local-device`. The device's settings: `readSettings`, `writeSettings`. Changes are announced to the other tabs (BroadcastChannel `ftl-saves`). |
| `web/device.mjs` | Device `{id, name}` in localStorage `ftl-device` ("Firefox on Windows"); the tab's instance in sessionStorage `ftl-instance` (a duplicated tab takes a new one); the `X-FTL-*` headers. |
| `web/sync.mjs` | The syncer (Android's `SyncEngine`): passes one at a time, every 2 minutes and on focus, a few seconds after each save. Before the account's list has been read (a page opened offline) the cloud is unknown, not empty: device copies show as "Not synced", never as deleted. |
| `web/game-session.mjs` | One running game: every engine save goes to the device store in order; a failed write keeps the game running and is retried by the next save; a tab closed with a write still queued keeps it in localStorage `ftl-save-flush` for the next start. |
| `web/save-service.mjs`, `web/saves-ui.mjs`, `web/dialogs.mjs` | The list rows, New save, backups (`{schema: 3, kind: "ftl-save"}` with run and profile; schema 2 imports without a profile, old schema 1 backups as a save without mods; a recovery snapshot's Download is the same schema 3 file with the snapshot's profile), the questions in the page's own dialogs (no `prompt`/`confirm`/`alert`). |
| `web/save-words.mjs` | The words of the save questions and notes (lock, override, changed in two places, taken over, deleted elsewhere, delete, restore, not synced, the limit), the Android launcher's too (`SaveWords.kt`; `SaveWordsTest` keeps them equal). They say "this device" on both. |
| `web/old-saves.mjs` | The migration below; a copy of all old data is stored first (`state` `old-saves-backup`). |

- **Tabs.** A running game holds the Web Lock `ftl-play:<account>:<id>`. Play in another tab sees it ("In use on
  another tab of this browser", also for a game that started offline) and Override steals it: the first tab stops at
  once. As the device store is shared, that tab's progress up to its last save carries on in the new one, so its
  notice says so instead of "discarded". It also gives the account's lock back if it still held it, or notes the
  override's time so it is never announced.
- **Override notices.** The time of an override the player has seen is kept per save in sessionStorage
  (`ftl-overrides:<instance>`). A repeat of that override with a holder is an ordinary "In use on X" with Override;
  with the save free, the lock is taken with `{acknowledge: true}` (and an upload started offline afterwards does the
  same before it uploads).
- **Changed in two places**, as on Android: the answer is part of Play. "Use the cloud copy" applies at once (no lock
  needed); "Keep this device's copy" first takes the lock like any Play, without override: held elsewhere, the usual
  "In use on X" dialog asks, and only its Override lock takes it. Then this device's copy replaces the account's (the
  account's version stays a recovery snapshot) and the game starts. The takeover notice and "deleted on another
  device" return to the list; the next Play starts the game.
- **Deleting** a save the account has (or had: this copy was synced) deletes it there too. Offline nothing is deleted
  ("Connect to delete this save from your account."), since the account's copy would come back at the next sync; while
  another game holds its lock the player is asked ("In use on X", **Delete anyway**). A save the account refuses to take
  (`saves_full`, `storage_full`, a bad run) stays here, and its row says "Not synced: <the server's reason>". The Android
  launcher does the same (`SyncEngine.remove`, `State.refused`).
- **Launch FTL** plays the current save of the enabled mods; without mods the first save is made at once, with mods
  "No save for …" asks. Play's "Change" lists the enabled setup's saves.
- **Local-only play** uses the same store and screens without the network: no recovery snapshots (the account keeps
  them), no account lock (the tab rule still holds). **Enable cloud saves** (or "Move them to this account" on the
  Saves page after a plain sign-in) moves the local saves, with their profiles, into the account as saves of their own.
- **Missing content.** After a run loads, the page asks the engine (`missingContent()`) what it dropped and lists it.

## Save files

One save as a file: the website's backup (`web/save-service.mjs`) and the app's export (`SaveFile` in savecore,
[ANDROID.md](ANDROID.md#save-files)) are one format and each reads the other's. Importing always makes a new save. The app checks
a file to the server's limits before it stores anything, so what imports can also sync.

## Saves from the original game

**Import from FTL** (website: Saves, beside Import save; Android: the empty list and New save) turns the files of FTL on a
computer (Steam, GOG, Humble) into a new save: the profile (`ae_prof.sav`: unlocked ships, achievements, scores) and the run
left with Save and Quit (`continue.sav`), one or both. Hyperspace writes the same files with its save prefix:
`hs_prof.sav`/`hs_continue.sav`, Multiverse `hs_mv_prof.sav`/`hs_mv_continue.sav` (`<saveFile><prefix>` of its
`hyperspace.xml`). The guide in the dialog names the folder: Windows `Documents\My Games\FasterThanLight`, macOS
`~/Library/Application Support/FasterThanLight`, Linux `~/.local/share/FasterThanLight` (Proton/Wine: the Windows folder in
the prefix); Steam Cloud keeps the same files there.

- **On the device only.** savecore `NativeSave` (Kotlin, the same code in the page through TeaVM and in the app) tells the
  files apart by name and content, reads them bounded (run 4 MiB, profile 1 MiB, strings 64 KiB, every count capped) and
  refuses garbage, truncated files, the old `prof.sav`, runs of FTL before 1.6, two runs or two games with a plain reason. It
  needs no game data, so it does not convert: it keeps each original file as Base64 inside the engine's root element
  (`<xftlSaveGame nativeImport="1" prefix ship sector scrap hull><nativeImport kind file bytes sha256>`, the same for
  `xftl-profile`), which the server takes like any run or profile, and gives the summary the list shows.
- **The setup.** No prefix: No mods. `hs`: the catalogue preset Hyperspace only; `hs_mv`: Multiverse 5.5.1; another prefix:
  the player picks (website: the presets and the enabled mods; Android: the presets or mod files). A modded save whose files
  this device lacks shows them as missing and plays once they are added, as any save.
- **The conversion** is the engine's, the first time the save is played, with that save's game files and mods
  (`xyz.znix.xftl.vanillasave`: `NativeImport`, `NativeProfile`, `NativeHsRun`, `VanillaSaveParser`): the profile becomes the
  engine's profile when the game starts; Continue converts the run and saves it in the engine's format at once, so the save
  holds the engine's run and profile from then on. A file that does not convert stops with what it lacks (another version,
  the mods it needs) and the save keeps the original files. Hyperspace runs keep their state through the engine's own run
  block (`HsSave`): variables, event queues, aliases, renamed and regenerated beacons, hidden augments, seeds, triggered
  events, event buttons; the profile's Hyperspace unlocks, victories, achievements and meta variables likewise.
- **The ship at the beacon** of a run without mods comes from its section in the save (hull, crew, weapons), hostile or
  neutral as saved; in a Hyperspace run it is rebuilt from the beacon's event.
- **What does not carry over.** Dead crew waiting in the clonebay; the state of a Hyperspace run's ship in mid-fight;
  the fight in progress of any run (the save's battle state and shots in flight, which the original's ImportBattleState
  and LoadSpace restore): weapons start uncharged and the player's unpowered, so the enemy's first volley comes later
  than in the original (capture `s2-cx`, `.build/hs/reports/ps-s2.md`); Hyperspace custom stores (the beacon keeps a store), background objects, temporal
  and custom system state, crew power cooldowns; crystal lockdown shards (the run is refused). Scores keep the player's score
  as the scrap that gives it. FTL Profile Editor (Vhati, GPL-2.0) was read for the format; no code from it is used.

## Engine requirements

- A save that names content the loaded game files lack (an item, crew race, augment or ship of a missing mod) loads
  with that item dropped and a notice listing what was dropped; it never crashes.
- Saves stay backward compatible (no save-version bump); metadata lives beside the run, not inside it.
- The ship at the beacon is saved whenever there is one and it is not destroyed, hostile or not, with crew or none, as
  WorldManager::SaveGame (1.6.14 0x61a3a0) does: a neutral event ship, an automated guard drone or a crew-killed ship
  reloads as it was, neutral and with its target panel.
- When the engine saves the run, as the original's autosave does: when an event box opens and when it closes, while
  the ship is not jumping (CommandGui::OnLoop's ChoiceBox edge, 1.6.9 0x5220b0 / 1.6.14 0x536770, then
  WorldManager::PrepareAutoSave); besides, at each arrival, when a fight starts, and when the page is hidden or closed or
  the app paused (the original saves when its window closes). Never in the tutorial or after the run ended.
- Every save first completes a jump in progress (WorldManager::SaveGame's CheckForNewLocation(1)): a run saved in the
  2 s jump-out goes on at the new beacon with the fuel spent once, its arrival event shown.
- Continue keeps what the original's load keeps (WorldManager::LoadGame, ShipManager::ImportShip/ImportBattleState,
  SpaceManager::LoadSpace): shots and volleys in flight, an artillery burst between shots, drones, boarders and crew
  mid-teleport, fires and breaches, hacking, mind control and cloak timers, the FTL charge, an enemy jumping away (it
  goes on leaving), the flagship's surge timer and goal (BossShip::LoadBoss draws a goal and then sets the saved one
  back, TimerHelper::SetMaxTime). Windows (store, map, ship) come back closed; an open event box comes back open.
- The original deletes `continue.sav` as Continue reads it (FileHelper::deleteFile in WorldManager::LoadGame) and has no
  run until its next save; the engine keeps the save, so a crash right after Continue loses nothing (choice S2-C1 in
  `.build/hs/reports/DECISIONS.md`).
- The random streams are saved with the run and restored after the load, so a reloaded run goes on as it would have
  without the reload. The original does not save its stream; its generators (sector, beacon events, ships, stores)
  reseed from saved seeds, so what they make is the same either way, and only live draws (combat, crew AI) differ.
