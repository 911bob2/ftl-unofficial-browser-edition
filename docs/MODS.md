# Mods (Slipstream / ftlman archives)

Status: experimental. The loader reproduces what **ftlman 0.7.4** writes into `ftl.dat`; which Hyperspace features
the engine then implements is tracked separately (`hsCapabilityReport()`).

## Player flow

Play has a **Mods** panel (its code loads only when the panel is opened). Nothing downloads until the player presses a
button. The panel has three parts:

- **Presets** (`web/mods/catalogue.json`), e.g. *Multiverse 5.5.1* = Hyperspace 1.23.2, Multiverse 5.5 Assets,
  Multiverse 5.5.1 Data. **Enable** turns on exactly those archives in that order (other mods stay listed, disabled).
  Missing files open a dialog: how many files and MB, the source of each (for MEGA a line that this connects the browser
  to mega.nz), with Download / Choose files / Cancel. The dialog also opens from the Saves tab (Play on a save whose mods
  are missing).
- **Mod files**: one row per catalogue file with **Download**, **Choose file**, official page, licence (Hyperspace)
  and credits; a progress bar with MB done / total, speed and time left; Pause/Resume; Delete once cached.
- **Your mods**: archives in this browser (catalogue files and any `.ftl`/`.zip` the player adds), per account, in patch
  order with enable boxes, move up/down, size, source, a **Verified** or **Custom** badge and Remove. Hints (not enforced): a mod that
  uses Hyperspace wants Hyperspace.ftl above it ([Hyperspace mod without Hyperspace](#hyperspace-mod-without-hyperspace));
  Multiverse Data wants Multiverse Assets above it.

Buttons that change their label with their state (Enable / Enabled, Download / Resume, the preset dialog's Download /
Downloading… and Cancel / Pause and close, Launch FTL / Launch FTL with mods) keep one width: the other labels sit
invisibly in the same grid cell (`fixedButton`, `.btn-fixed` in `web/styles.css`).

**One Launch button.** Play has a single **Launch FTL** button. It reads **Launch FTL with mods** while any mod is
enabled and then launches with the enabled mods in their order; to play without mods the player turns them off. With
none enabled it is the launch without mods, unchanged, and no mod code loads: the Mods panel mirrors each account's
enabled archives into localStorage (`ftl-mods-enabled:<account>`, `web/mods-enabled.mjs`); a library from before the
mirror is read once from IndexedDB (only when that database exists). With mods the stored list is read again at the
click. "Play" on a save without mods turns the enabled mods off.

**Accounts in one browser.** Archive files are shared by the accounts of a browser profile; each account has its own
list. **Remove** (Your mods) takes the archive off this account's list; its file is deleted only when no other account
lists it, and the confirmation says which. **Delete** (Mod files) deletes the file for everyone in this browser, after
warning when another account still lists it, and detaches it from those lists (and their Launch button mirror).
Before 2026-10-24 Delete only removed the file from the deleting account's list, so a file another account listed
stayed with no message.

Archives are stored in this browser (IndexedDB `ftl-mods`); nothing is uploaded. Any local mod file is accepted (size,
zip and image limits, SafeXML and the Lua sandbox apply as to every file). It is **Verified** when its SHA-256 is a
catalogue file's (it then carries the catalogue's name and version, whatever the file is called) and **Custom**
otherwise (savecore `classify`). A save names its mods with these flags; a Custom file never uploads, so another device
shows "missing mod: name, sha256" and plays the save only after the same file is added there. Server and MEGA
downloads stay pinned to the catalogue's SHA-256.

## Catalogue and sources

`web/mods/catalogue.json` pins each file (name, version, file name, size, SHA-256, official page, credits, licence,
sources). No mod content is in Git or the image. Per file the player picks:

| Source | Who | Notes |
| --- | --- | --- |
| This server | Hyperspace: every signed-in role. Never Multiverse: we do not host it | Enforced by the server (`server/ModFiles.cs`, 403 `mod_not_permitted`); the file must be in the server's mods directory |
| mega.nz | Multiverse (the official links from MV's forum thread) | Our own reader `web/mods/mega.mjs`; no MEGA library |
| A local file | everyone, every file | Any file is accepted: the catalogue's (SHA-256 equal, whatever its name; for Hyperspace the GitHub release zip also works) is Verified, another is kept as a Custom mod and the row says it is not that file |

Local-only visitors (no account) get the public catalogue
(`/api/public/mods/catalogue`) without server downloads.

**Server hosting.** The operator places the files in `FTL_MODS_DIR` (default `/mods`, mounted read-only, outside Git and
the image). At startup the server checks each file's size and SHA-256 in the background; a missing or different file
is not offered. `GET /api/mods/catalogue` tells the account which files it may download; `GET /api/mods/files/{id}`
streams one with HTTP ranges (two concurrent requests per account). Multiverse files are refused for every role, even
if they are in that folder: players download them from MV's own release on mega.nz or choose a local file. Hyperspace is redistributed under GPL-3.0-or-later with its notice
(`third_party/notices/Hyperspace-NOTICE.md`, `GPL-3.0.txt`, on `/Licenses`).

`node tools/mods/fetch-mods.mjs <mods-dir> [--only id,id] [--from <dir>] [--check]` fetches the pinned files from their
official sources (Hyperspace: the GitHub release zip, checked, Hyperspace.ftl extracted; Multiverse: the MEGA links)
or copies matching files from a folder, and verifies every file.

**MEGA.** `megaFileInfo` posts `[{a:"g",g:1,ssl:2,p:<handle>}]` to `https://g.api.mega.co.nz/cs` (as `text/plain`, a
simple CORS request) and gets the size, encrypted attributes and a temporary `https://gfs…userstorage.mega.co.nz` URL.
The file key from the link fragment gives the AES key (`k0..3 xor k4..7`) and the CTR nonce (`k4, k5`); each 16 MiB
range (`<url>/<start>-<end>`) is decrypted with WebCrypto AES-128-CTR at its block offset. Attributes (AES-CBC, zero
padded) give the file name. The integrity check is the catalogue SHA-256. The CSP adds only
`connect-src https://g.api.mega.co.nz https://*.userstorage.mega.co.nz`.

**Downloads** (`web/mods/download.mjs`): ranges are fetched up to 2 (server) / 4 (MEGA) at a time, hashed
incrementally in a worker and stored as parts in IndexedDB together with the resumable hash state, so a 712 MB file
is never held twice in memory and an interrupted download resumes after its last stored part (from either source).
The finished parts become one Blob archive only when the SHA-256 matches. Errors carry a next step: cannot connect
(offline, firewall or extension blocking mega.nz) → Retry / other source / local file; MEGA codes (file removed or
taken down, transfer quota with the wait time when MEGA gives one, rate limits, proof-of-work challenge) → retry later
/ other source; interrupted → Resume / Restart; checksum mismatch → discarded, Retry / other source; browser storage
full (checked with `navigator.storage.estimate` before starting and on QuotaExceededError) → delete cached files.

**Launch FTL with mods** merges the enabled archives over this deployment's game files (server assets or the player's
local `ftl.dat`, same provider as vanilla), then starts the engine with:

- `window.ftlFiles`: the vanilla map with merged files replaced/added (and ftlman's rare deletions applied).
- `window.ftlModSession` (PLAN I1): `{hash, loader: 1, mods: [{name, version, sha256, size}], hyperspace,
  hsVersion, scripts}`; `hash` = modSetHash = sha256 of `{"loader":1,"mods":[archive sha256 in order]}`;
  `hyperspace` = merged files contain `data/hyperspace.xml`; `scripts` = `<script>` paths of its `<scripts>` blocks.
- Mod audio in the audio manifest, read lazily from the stored archive when first played.

The merge result is cached per (mod set, game files, merge version); later launches skip the merge and only read
the binary files from the stored archives. The Launch FTL button is unchanged and never loads mod code.

## Saves

Saves follow save model 3 ([SAVES.md](SAVES.md)): one plain list per account (up to 30), every save on the device first
and synced in the background, modded saves included. A save is **locked to its mod setup**: "No mods" or an ordered
mod list (name, version, SHA-256, size, Verified) with its mod-set hash. Each save holds its own profile.

**One model.** The enabled mods decide which saves Play uses; picking a save anywhere (Play on a row) switches the
enabled mods to that save's setup (missing catalogue files open the download dialog first; a missing Custom file stops
with "missing mod: name, sha256", see below).

**Saves tab** (`web/saves-ui.mjs`, data in `web/save-service.mjs`). One list of all saves, enabled setup first. A row: the
name, a badge for its setup ("No mods", a preset's title, one mod's name, or "3 mods" with the names in the tooltip),
**Current** on the save Play uses now, a status badge (**This browser**, **Not synced**, **Changed in two places**,
**Taken over**, **Deleted elsewhere**, **Cloud**), a muted line ("Kestrel A · sector 3 · 120 scrap · hull 25/30 · 1 h 5 min
· 2 h ago" or "Empty"), where it was last played, the lock line ("In use on Pixel 8 · last seen 14:05"), the mod files this
browser lacks ("Downloads when played: …" for catalogue files, "missing mod: name, sha256" for Custom ones), **Play**
(**Start** for an empty save) and a **⋯** menu with **Rename**, **Download backup**, **Recovery snapshots** (saves in the
account), **Advanced: mods** and **Delete**. While a game runs, Play and New save are disabled ("A game is running: use
Save and close first"). **New save** opens a dialog: **Name** (prefilled with the next free "Save N") and **Mods**, the
enabled mods greyed out (other mods are chosen in the mod picker, **Change mods**). **Create** makes an empty save with
the enabled mods and makes it current for them; nothing downloads.

**Advanced: mods** is the only place a save's mods change: the list in order with Verified/Custom and SHA-256, move
up/down, Remove, and Add for any mod file in this browser, behind the warning that a changed set can break the run (what
a removed mod added is dropped when it loads, and the game lists it). The save keeps its unlocks; Hyperspace data of the old
set is not read.

**Play tab.** One line under Launch FTL: "Save: **Save 2** · Multiverse 5.5.1" ("none" when the enabled mods have no save)
and **Change**: a dialog with the same list filtered to the enabled setup, New save and "Show all saves". **Launch FTL**
plays the enabled setup's current save. **Launching with mods that have no save** never creates one silently: the
dialog "No save for Multiverse 5.5.1" asks "Create a save for these mods?" with **Create save** and **Cancel**. Only for
a mod set without Hyperspace, where a run made without mods may be copied, it also offers **Use a save without mods**
("It may not work with these mods."): the chosen save (run and unlocks) is copied into a new save of the mods ("Copy of Save 1"); the
original stays untouched. The vanilla launch loads no mod code.

**Hyperspace profiles.** Every new save starts like a fresh install, also in a mod set whose `<saveFile><inheritMode>` asks
to take over vanilla progress: a save holds its own profile, so there is no shared one to inherit from
([SAVES.md](SAVES.md)).

**Fresh data.** Play, Saves and Mods re-read saves, enabled mods and downloaded files whenever they become visible, on
`visibilitychange` and `focus`, and after every action; other tabs of the browser follow through
`BroadcastChannel('ftl-saves')` and the `storage` event.

## Hyperspace mod without Hyperspace

The engine has Hyperspace's program built in and turns it on whenever the merged files hold `data/hyperspace.xml`, which
Multiverse ships too. `Hyperspace.ftl` is Hyperspace's data (images, fonts, translations, sounds, default settings); the
native game needs both, Hyperspace first. A set without it starts and plays with that data missing, so it stays allowed, with
a warning (`web/mods/hs-needed.mjs`, Android `mods/HyperspaceRule.kt`, the same rule and words):

- **When**: a mod uses Hyperspace (`data/hyperspace.xml`, or a patch of it: `.xml.append`, `.append.xml`, raw append or
  clobber, `.append.lua`) and no mod of the set is Hyperspace.ftl: the catalogue's file of `mod: "hyperspace"` by
  SHA-256 (any file name), or an archive whose own metadata title is "Hyperspace" (another build). Never by file name.
  Archives not on the device are not looked into.
- **Quiet hint** in the mod picker while such a set is enabled (website: Your mods; Android: the picker of New save and
  Advanced). The order hint (Hyperspace.ftl present but below) stays as it was.
- **Warning**, once per start, never per toggle: "Hyperspace mod without Hyperspace", naming the mod, with **Enable
  Hyperspace**, **Run anyway** and Cancel. Website: Launch FTL with mods, Play on a save, New save's Create. Android: Play or
  Start on a save, New save's Create.
- **Enable Hyperspace** first gets the file when it is missing, asking before anything downloads: its name, version, size and
  this server as source (Hyperspace may be hosted; Multiverse never is). Signed out or playing offline (the catalogue says
  `sign-in`): Sign in, or the official release page. Server unreachable: Try again. Not offered (`absent`, `mismatch`) or
  still `verifying`: the official page (and Try again while verifying). A failed download: its error and Try again.
- Then, **for a new save** (Launch with no save yet for the set, New save): Hyperspace.ftl is enabled first in the picker
  (website: the Mods panel opens with its row marked, and the start goes on to "No save for ..."; New save shows the new
  list and Create goes on). **For a save that exists**: its mods change only through Advanced, so the next question is
  **Change this save's mods** (Advanced opens with Hyperspace.ftl added first; nothing changes until the player confirms
  there, then the save starts) or **New save with Hyperspace** (website: the set with Hyperspace enabled and the start goes
  on; Android: New save with that list).

## Merge rules (ftlman 0.7.4 `apply_ftl`, ported in `web/mods/merge.mjs`)

Archives in order, entries in ZIP central-directory order; `\` becomes `/`.

- Skipped: `mod-appendix/…`; entries under a top-level directory other than `audio/ data/ fonts/ img/`
  (case-sensitive; top-level files are kept).
- `X.xml.append` / `X.append.xml`: patch `X.xml` (exact name, else case-insensitive among base names and plain
  `.xml`/`.txt`/rawclobber files added so far; ignored with a warning if absent). The document is unwrapped
  (`<?xml …?>` and every `<FTL>`/`</FTL>` removed), wrapped in one `<FTL>`, parsed, patched, and re-emitted (with the
  `<FTL>` root only if the original had one). All comments are dropped from patched documents.
- `X.xml.rawappend` / `X.rawappend.xml`: text concatenation with ftlman's declaration and separator comment.
- `X.xml.rawclobber` / `X.rawclobber.xml`: write `X.xml` as given (text-decoded).
- `X.append.lua`: an ftlman Lua patch script run on `X.xml` (target found as for appends; see Lua patch scripts).
- Other `.xml`: re-emitted through the event writer (declarations/PIs dropped, tag whitespace normalised, end tags
  renamed to their start tags, unclosed elements closed).
- `.txt`: text-decoded, lone LF converted to CRLF. Other files: copied byte for byte, unless the name matches
  ftlman's ignore list (`.DS_Store`, `thumbs.db`, `.dropbox`, `~` prefix/suffix, `#…#`), in which case an existing
  file of that name is deleted.
- Text decoding: UTF-16 by BOM, UTF-8 BOM stripped, otherwise strict UTF-8; like ftlman the first two bytes are
  validated on their own (a file whose second byte starts a multi-byte character fails). Non-ASCII file names fail
  (ftl.dat paths are ASCII).

Append scripts: `mod:findName` (default reverse, limit 1), `findLike`, `findWithChildLike`, `findComposite` with
`mod:par` AND/OR/NOR/NAND, `mod:selector`, `regex`, `start`/`limit`/`reverse`/`panic`; commands `setAttributes`,
`removeAttributes`, `setValue`, `removeTag`, `insertByFind` (`mod-before`/`mod-after`, `addAnyway`),
`mod-prepend:`/`mod-append:`/`mod-overwrite:`; plain content is appended to the root. Invalid commands fail only
when they are reached (a broken command inside a find that matches nothing is ignored), as in ftlman.

ftlman behaviours kept bug for bug because they change output: `start > 0` with the default `limit="-1"` matches
nothing (usize wrap-around); text before an unresolved `&` is lost when a later entity resolves; attribute values
are written with text escaping (a `"` stays raw); attributes are written sorted and de-duplicated; `>` in comments
becomes `&gt;`; numeric entities for surrogates or beyond U+10FFFF stay literal; prefixed attributes (`a:b="…"`)
are a parse error. Rust `regex` patterns run as JS `RegExp` with Unicode `\d \w \s` and leading `(?i)`-style flags;
other Rust-only syntax is unsupported.

Not replicated: ftlman's GUI-installed Hyperspace skips `example_layout_syntax.xml` (CLI and a user-added
Hyperspace.ftl keep it; HS 1.23.2 never reads it); ZIP CRCs are not verified; ftlman's UTF-16 reader can corrupt a
code unit split across read buffers, ours decodes correctly; inputs on which ftlman panics may produce output here.

## Lua patch scripts (ftlman `X.append.lua`)

ftlman 0.7.4's Lua patch API, ported from its `src/lua.rs`, `src/lua/{xml,io,debug,util,meta}.rs`, the builtin Lua
in `src/lua/` and `src/xmltree/dom.rs` to C (`hs-lua/ftlman.c`, its own module `web/vendor/ftlman-lua.wasm`); the
host side is `web/mods/lua.mjs`. Android builds the same C natively, with the differences a script could notice listed
in [ANDROID.md](ANDROID.md).

- A script runs where its entry comes in the merge, against `X.xml` found as for appends (missing target: warning,
  ignored). One runtime per merge, as ftlman: base, table, string and math only, without `dofile`, `require`,
  `collectgarbage`, `loadfile`, `rawset`; `string`, `table`, `math` and `mod` are read-only. Each script gets its own
  environment over the shared globals with `document` (root element of the file), `mod.xml`, `mod.iter`, `mod.table`,
  `mod.util`, `mod.debug`, `mod.meta.current_path`, and `mod.vfs.pkg` (the merged files so far; writes add files) and
  `mod.vfs.mod` (the script's archive, read-only), with ftlman's VirtualFileTree paths and error texts. The patched
  root is written like an append's result (`<FTL>` root only if the file had one). print and pretty_print output goes
  to the console (`[mod script]`).
- Lua is 5.3.6 with Lua 5.4's string arithmetic (`"10" + 1` is 11, `hs-lua/ftlman-lvm.c`). Differences from ftlman's
  Lua 5.4 that a script could notice: `_VERSION`; 5.4-only syntax (`<const>`, `<close>`); error values are strings
  (ftlman's print as "runtime error: ..." with a traceback); bitwise operators on strings fail with another message;
  `math.random` sequences; bytecode never loads. **Table order:** `pairs` order is fixed here (hash seed 0), while
  ftlman's Lua seeds its string hash randomly per run, so a script that iterates `pairs` over string keys can write a
  different order on every ftlman run (Outer Expansion 7.1.10 adds its two systems this way).
- Safety (not in ftlman): its own module and Lua state; `load` reads text only; an instruction limit per script
  (`LUA_LIMITS`: 1,000 million; every 16 bytes allocated or written count as one), which `pcall` cannot catch; 512 MB
  for the Lua heap and the document trees together; trees are walked without recursion; the sibling loops ftlman's
  tree can form (ftlman then never finishes) fail with a message. A failing or stopped script fails the merge:
  "Could not patch XML file `<target>` according to `<script>` from `<archive>`: `<error>`". The heaviest scripts seen
  (Old Guard, Darkest Desire, Outer Expansion) use up to 6.5 million instructions and 36 MB each.
- In the browser, scripts read files synchronously: the merge worker gets the base `.xml` and `.txt` files and every
  base file's size, and reads archive entries with FileReaderSync; other base files (images, audio) are not readable
  by scripts ("... is not available to patch scripts here").

**Host interface** (for another host, e.g. a native build for Android). Build `hs-lua/ftlman.c` and
`hs-lua/ftlman-lvm.c` with Lua 5.3.6's core except `lvm.c` plus `lauxlib lbaselib lmathlib lstrlib ltablib`, using
`-DLUA_COMPAT_MATHLIB -include hs-lua/hsconf.h` (`hs-lua/Dockerfile`). The module exports:

| Function | Purpose |
| --- | --- |
| `mp_init(instructions, memoryBytes)` | create the runtime (0, or -(1 + error length)) |
| `mp_buf()`, `mp_buf_cap()`, `mp_buf_grow(n)` | the one shared buffer for arguments and results |
| `mp_run(codeLen, nameLen, pathLen, docLen, fsLen, flags)` | run one script (below); returns the patched root as tree events, or -(1 + error length) |
| `mp_instructions()`, `mp_memory()`, `mp_memory_peak()`, `mp_nodes()` | statistics of the last run |

`mp_run`'s buffer holds the script, its chunk name (`@data/x.append.lua`), `mod.meta.current_path`
(`/data/x.append.lua`; `pathLen` -1 = none), the root element as tree events, and the `mod.vfs` names
(`{u32 length, name}`...; the host's filesystem index is the position). Flags: 1 = return only the root's children
(the file had no `<FTL>` root), 4 = no document (ftlman's `lua-run`).

The module imports (module `env`):

- `log(kind, ptr, len)`: print (0), pretty_print (1), Lua warning (2).
- `fs(fs, op, pathLen, dataLen)`: stat (0), ls (1), read (2), write (3) on filesystem `fs`; the path (without its
  leading `/`) is in the buffer, write data after it. Results: stat `d` or `f<length>`; ls `{type byte 'f'/'d', u32
  length, name}`...; read the bytes. -1 = no such path (stat); <= -2 = error, message in the first -2 - r bytes.
- `xml_parse(len, topLevelText)` (text to events, mod.xml.parse), `xml_write(len)` (events to text,
  mod.xml.stringify), `xml_append(lowerLen, upperLen, kind)` (ftlman's apply_one_xml, mod.xml.append; kind 1 =
  rawappend): r >= 0 is the result length, r < 0 an error of -1 - r bytes.

Tree events: `S` u32 prefix length (all bits set = none), prefix, u32 name length, name, u32 attribute count,
`{u32 length, key, u32 length, value}`..., children, `E`; `T`/`D`/`C` u32 length, text (text, CDATA, comment).
Integers are little-endian. Built natively, the imports are ordinary C functions with these signatures (the
import/export attributes only matter for WebAssembly).

## What the loaders accept

The merged files then go through the engine's loaders, which accept what FTL's (1.6.14, and 1.6.9 under Hyperspace)
accept rather than what a strict reading of the format would:

- A `<blueprintList>` takes the value of every child, whatever its name (the list reader of
  `BlueprintManager::LoadBlueprints`): Outer Expansion 7.1.10's `<bane>` entries count.
- `<item_modify>` reads the `type`, `min` and `max` of every child, whatever its name
  (`EventsParser::ProcessModifyItem`): Outer Expansion's `<iten type="scrap">` gives scrap.
- An `<eventList>`'s entries are its `<event>` children only (`EventsParser::ProcessEventList`); other children are
  not entries. A modded list with no `<event>` at all (Alder's Additions 2.1.1 wrote an event as a list) is left out and
  reported: the original would divide by its size of 0 when the list is used.
- Files with several top-level elements, unknown element names, bad or odd PNGs and lenient numbers: see
  [HYPERSPACE.md](HYPERSPACE.md#capability-report-hscapability-browserenginehscapabilityreport) (load problems) and the
  mod-fix round (`HsLoad`, `PNGDecoder`, `NativeNumbers`).

`hsCoreProbe` checks the three list rules above, and `hyperspace.xml`'s repeated `<seeds>` and `<defaults>` blocks, on
names of its own (`BrowserHsDiagnostics.loaderRules`).

## Verification

- `tests/mods-merge.mjs` (unit group): 69 append cases and the F1 fixture archives (`tests/fixtures/mods/`) against
  outputs recorded from the real ftlman 0.7.4 binary (`tools/mods/ftlman-cases.mjs`, `tools/mods/f1-ground-truth.mjs`);
  40 Lua patch-script cases and the L1 archives (scripts, mod.vfs, appends after scripts) against ftlman's `append`
  and `patch` (`tools/mods/ftlman-lua-cases.mjs`, `tests/lib/mods-lua.mjs`).
- `tools/mods/ftlman-lua-tests.mjs <ftlman source>` (developer): ftlman's own `lua/tests` against its recorded output.
- `tests/mods-mega.mjs` (unit): MEGA links, AES-CTR at every range size and attribute decryption against a file
  encrypted as MEGA stores it, API/storage error mapping, resumable SHA-256.
- `tests/mods-browser.mjs` (Menus, Chrome and Firefox, test catalogue of our F1 archives, fake MEGA): no automatic
  download, server and MEGA downloads in ranges with progress, source choice, blocked host / quota / removed / bad
  checksum / interrupted + resume / storage full, local files with any name (another file kept as Custom), preset
  dialog, External-user rules, launch progress, "No save for ..." (nothing created by the dialog or Cancel; "Use a save
  without mods" copies a vanilla run into a modded save that syncs), vanilla launch with a modded save present (no
  question, no mod module); equal widths of toggled buttons, the one Launch button and its label; the Saves list
  (badges, Current), New save for No mods, a preset with saves, the enabled mods (nothing downloaded) and a preset on a
  fresh account, Rename and Delete in the page's dialogs, Restore of a recovery snapshot, Play on a modded row and on a
  vanilla row (mods switch), Play's Save line with Change and Show all saves, a save created and one renamed in another
  tab shown without a reload, and two accounts in one browser (B removes a shared file from its list, A keeps it; B
  deletes it with a warning and it leaves A's list). No native prompt, confirm or alert appears.
- `tests/saves-browser.mjs` (Menus, Chrome and Firefox): Verified and Custom local files, a modded save synced with its
  ordered mod list, "missing mod: name, sha256" on another device (Play refuses), and Saves > Advanced.
- `tests/mods-security.mjs` (Security): signed-out and role rules on the server (External users get 403 for
  Multiverse-rule files), CSP, the saves API (sign-in required; the old slot, lease and current-save endpoints answer 410),
  and Lua patch scripts (`tests/lib/mods-lua-security.mjs`: absent libraries, locked metatables, no bytecode,
  instruction limit through pcall, memory limit, sibling loops and deep trees, mod.vfs bounds, merge error messages).
  `tests/server-security` checks the catalogue SHA-256 gate and the redistribution flag.
- `tests/mods-ui.mjs` (Menus group, Chrome and Firefox): import, order, enable, launch, files map equal to the Node
  merge, lazy mod audio, the modded save stored and synced under its setup, merge cache on reload, and an
  unchanged vanilla launch (no session, identical files map, no mod module requested).
- `tools/mods/compare-ftlman.mjs` (developer, needs `ftl.dat` and the archives): every file of a merge against
  ftlman's patched `ftl.dat`. Hyperspace 1.23.2 and Hyperspace + Multiverse 5.5 Assets + 5.5.1 Data are
  byte-identical (3,468 and 13,307 files), and so is every other mod set of the packaged runner (2026-11-06):
  Vertex 6.4 with a tag test mod, Speed Control 1.0.0, SimpleGraphics with a test wrapper (over Hyperspace), and over
  Multiverse: Judge Blue Options 1.16 + Boon Selector 1.10.0, Fusion 0.1.6, Fusion + Alder's Additions 2.1.1, Hektar
  Bundle 0.1.7, Hektar + Universal Weapon Modularity 0.2, Jub 1.0.22, Trash Compactor Collection 1.1 (its five appends
  to files Multiverse 5.5.1 no longer has are ignored with ftlman's warning), More Mannable Systems 2.1 and R&D 1.5.8. With Lua patch scripts, MV 5.5.1 + The Old Guard 3.4 + its System Patch
  Last and MV 5.5.1 + Darkest Desire 4.1.4 + its System Patcher are byte-identical (14,343 and 14,760 files);
  MV 5.5.1 + Outer Expansion 7.1.10 + its Patch After All is identical in 14,727 of 14,728 files, and
  `data/blueprints.xml` is identical once the two systems its script adds to 324 ships are in ftlman's order (`pairs`
  order, see Lua patch scripts).
- Android: `RealModsTest` (`android/mods`, opt-in with `FTL_MODS_REAL`) merges the same sets with the Kotlin merge and
  compares every file with the website's merge (`tools/mods/merge-manifest.mjs`); all 14 sets without Lua patch scripts
  are equal (2026-11-06). The three with Lua patch scripts run through the device runner ([ANDROID.md](ANDROID.md#testing)).
- `tools/mods/build-modded-assets.mjs` (developer/test): a modded server-asset directory plus session JSON for the
  packaged test runner; production never serves it.
