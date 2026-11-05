# Android app

Status: in development (branch `claude/android-app-*`). A native app, not a WebView: the same engine sources as the
website, the same accounts and cloud saves, and fully playable offline. Saves follow [SAVES.md](SAVES.md).

## Build

`android/` is its own Gradle build (Gradle 9.7, AGP 9.3.3, Kotlin 2.4.21, JDK 17, compile/target SDK 36, min SDK 26,
NDK 28.2). It resolves from the local Gradle cache: `cd android && ./gradlew --offline :app:assembleDebug`.

| Module | What |
| --- | --- |
| `:savecore` | `../savecore` (shared save and sync rules, also compiled to `savecore.js` for the website) |
| `:engine` | `../engine/src/main/java` copied **without** the TeaVM host files, plus their Android twins in `android/engine/src/main/java` and the Hyperspace Lua runtime (`../hs-lua` + Lua 5.3.6) built with ndk-build |
| `:app` | activity, GL surface, input, lifecycle, storage, sync, and the launcher UI in Compose |

The engine core is compiled unchanged. Only these browser host classes have twins (same package, same public API):
`BrowserFiles` (game files and save callbacks), `BrowserXml` (pull parser into JDOM, DTDs refused), `BrowserAudio`
(software mixer), `BrowserEngine` (driver without the browser test probes), `BrowserDebug`, `BrowserClock`,
`BrowserHeap`, `BrowserPixels`, `BrowserInflater`, `BrowserCheckpoints`, `BrowserMods`, `BrowserHsScan` (the same regex
scan as the page), `BrowserJsStack`, and `xyz.znix.xftl.hs.lua.LuaJs` (JNI instead of wasm). `android.opengl.GLES30`
and `android.util.Log` are the platform's own. Browser test probes (`*Diagnostics.kt`, `Gameplay*Checks.kt`,
`LuaProbe.kt`) are left out. When the browser host API changes, the twin must follow; the build fails otherwise.

## Release build

`cd android && ./gradlew --offline :app:assembleRelease` (APK) or `:app:bundleRelease` (AAB). R8 and resource shrinking
are on; `android/app/proguard-rules.pro` and `android/engine/src/main/jni/jni-keep.pro` hold the rules. ABIs: arm64-v8a
and x86_64, native libraries with 16 KB page alignment. Permissions: `INTERNET`, `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED` (for Random only, see App icon), `VIBRATE` (Haptics). Plain HTTP and
the adb console command receiver exist only in debug builds (`app/src/debug`).

Signing needs the owner's key, in `android/signing.properties` (ignored by Git; `storeFile`, `storePassword`, `keyAlias`,
`keyPassword`). Without that file the release build comes out unsigned. Raise `versionCode` for each published build.

## Runtime

- **Graphics**: `GLSurfaceView`, OpenGL ES 3.0, RGB8 + depth + stencil 8; the engine draws its 1280×720 frame into an
  offscreen framebuffer scaled to the game view (or the touch layout's W×768, see Touch and Screen).
- **Draw batching** (engine, both builds): `rendering/DrawQueue.kt` holds a frame's draws, uploads their vertices as one
  buffer, joins neighbours with the same state, and drains before any GL state change, texture change or pixel read
  (`GL11` does that for every call that goes through it; code calling GLES directly drains itself). A debug `gfxcheck`
  console command compares a batched and an unbatched frame pixel by pixel; `gfx` prints flushes, GL draws and uploads.
- **Loop**: the GL thread runs the browser loop: queued input, `step(dt ≤ 0.05)` per frame, render at the settings'
  frame limit.
- **Audio**: `AudioMixer` mirrors `web/audio.mjs` (sources, buffers, gain, pitch, loop, offsets); Ogg sounds are decoded by
  the engine's Java decoder and other files by MediaCodec (music on a thread of its own), into direct buffers, which ART
  keeps in the Java heap (cache with eviction, a quarter of the heap at most, 192 MB at the very most), and mixed into one low-latency `AudioTrack`. The mixer lives as
  long as the process: when a game ends it forgets its sounds and queued decodes, and keeps the sources, which the
  engine's `SoundStore` creates once per process. A debug `audio` console command prints what it loaded and played;
  `adb shell setprop log.tag.FTL-Audio DEBUG` logs each decoded sound.
- **Audio focus**: the game holds the focus while resumed and goes silent when it loses it (a call, another app's sound).
- **Lifecycle**: `onPause` saves (as the browser does on blur/hidden) and silences audio; `onResume` restores. The
  process may die in the background; the save already reached the device store.
- **Hyperspace Lua**: the same C sources as the wasm build (`hs-lua/hslua.c`, `gen/hs_api.c`, `linit.c`, Lua 5.3.6
  with `hsconf.h`) built as `libhslua.so`; `android/engine/src/main/jni/hslua_jni.c` answers the runtime's imports
  through the engine's bridge (`LuaBridge`, `Handles`, `LuaRng`). Lua runs natively here, outside the wasm sandbox,
  so the sandbox rules matter more: no `io`, `os`, `package`, `debug`, `load`, `loadfile`, `dofile`; text chunks only;
  the per-callback instruction cap stays, and counts `__gc` handlers too (hooks are off there in stock Lua; `hs-lua/hslua-lvm.c`).
- **Lua patch scripts** (`X.append.lua`, [MODS.md](MODS.md#lua-patch-scripts-ftlman-xappendlua)): `hs-lua/ftlman.c` and
  `ftlman-lvm.c` with the sources and defines of the wasm build, as `libftlmanlua.so`; `android/engine/src/main/jni/ftlman_jni.c`
  is its host, `FtlmanLua` (`:app`) the JNI class, `ftl.mods.PatchRuntime` (`Lua.kt`, `:mods`) the twin of
  `web/mods/lua.mjs`. Output equals the website's: the wasm build's hash seed (`ftlman_lstate_jni.c`, a constant of that
  binary, `tools/mods/ftlman-wasm-seed.mjs` prints it after a rebuild), `math.random`, and wasm's `char` and arithmetic.
  Same limits as the website: 1,000 million instructions per script, 512 MiB for the Lua heap and trees, text chunks
  only. What the wasm sandbox gave is added natively: every plain allocation of the runtime is capped too (twice the
  limit), an error in a `__gc` after a script ends that handler (both builds), a userdata resurrected by `__gc` fails cleanly,
  Java exceptions stop at the host, an unprotected Lua error stops the runtime instead of aborting, and each merge runs
  on its own thread with a 256 MiB stack (64, then 16 where that cannot start), so a nesting bomb fails the merge with
  an overflow or out-of-memory error. One runtime per merge, closed at its end; the state is process-global, so merges
  take turns. Not bounded, as on the website: CPU inside one C call (a pathological Lua pattern) holds the merge until
  the app is closed. A native crash would end the app's process; the merge runs in-process. Differences a script could
  notice on 64-bit ABIs: `string.pack` with native sizes (`l`, `L`, `T`, and `string.dump`), memory and instruction
  accounting (pointers are larger, so limits come slightly earlier), `pairs` order over table, function or userdata
  keys and `tostring` of them (addresses), and the sign of a NaN in text (bionic prints `nan`).

## Memory

Multiverse, x86_64 emulator, after a forced collection. The manifest asks for `largeHeap`; the emulator then gives 576 MB
(192 MB without it).

| What | MB |
| --- | --- |
| Java heap in a run, no music decoded | 168 |
| The same with a late-run save and its two tracks | 210 to 225 |
| Audio cache, in the Java heap | at most a quarter of the heap, 192 at most, plus the sounds in use |
| Native heap (Lua included) | about 30 |
| Live textures, 55-beacon run (measured in the browser, same loader) | 416 |

A run of Multiverse fits a Java heap of 192 MB, not 128; all music decoded on a 384 MB heap lost no sound, on a 256 MB one
10 of 23 decodes failed, though the game ran on. The debug console's `mem` prints these; `ballast <MB>` takes that much of the
heap, to try a smaller limit. No real phone has been measured.

Vanilla and Hyperspace alone take about 190 and 230 MB in the process (80 of it Java heap), plus textures: 25 MB at the start
of a run, about 90 at the end. Both left about 400 MB available on the 2 GB stand-in (below). Multiverse takes about 300 MB
plus up to 416 of textures: on the stand-in a new run and eight Continues lived, with the emulator's 1.9 GB of swap used up and
80 MB available, and 470 MB more got it killed. That is too close for a real run, so:

- A save whose mod archives add up to 100 MB or more, on a device reporting under 2500 MB of RAM or with a Java heap limit
  under 192 MB, warns once before it starts (`MemoryNotice`). By size, not by mod name.
- Running out shows "Out of memory" over the game when it happens on the game thread, or over the saves list at the next
  start when another thread hit it or Android killed the game on screen (the exit reason, from Android 11; below that,
  any death on screen on a small phone). GL_OUT_OF_MEMORY, read every 64 frames, counts as running out.

The debug console's `hold <MB>` takes that much RAM outside the Java heap. A 2 GB stand-in is this 2.5 GB emulator with 520 MB
held, plus the textures, which live on the host with `-gpu host`.

## Launcher

Native Compose UI styled like the website (dark FTL design: space banner, logo, honeycomb panels, Chakra Petch,
daisyUI colours and spacing from `web/styles.css`). Steps, each one screen:

1. **Account**: sign in (same accounts; the server's cookie session, stored encrypted on the device), register, or
   **Play offline** (no account). Each account's saves are kept apart on the device (`accounts/<scope>/`, scope =
   hashed server and user id; play without an account uses `local-device`, as the browser does); the settings
   (language, volumes, hotkeys) are the device's (`settings.xml`). Signing in moves the
   saves made while playing offline into the account, where they upload ("Enable cloud saves"). **Sign out** (the top
   bar, the only place it is) always asks first, with the website's words (`SignOut.kt`, `web/sign-out.mjs`): Cancel or
   Sign out, and a stronger line when a save of the account has progress or a name the account does not have yet. Then
   it gives back the locks this game holds and signs out.
2. **Game data**: Admin/User download the server's prepared assets once; External users and offline players import
   their own `ftl.dat` (Storage Access Framework). It is checked against the expected SHA-256 and never uploaded.
3. **Saves**: the one list of [SAVES.md](SAVES.md) with sync status, locks, New save, Advanced. **Import from FTL** (on the
   empty list and in New save) takes the files of FTL on a computer, copied to the device, through the system's file picker
   ([SAVES.md](SAVES.md#saves-from-the-original-game)). Each save's ⋯ menu has **Recovery snapshots** when the save is in the
   account: the last 10 versions (`GET /api/saves/{id}/snapshots`), each but the current one with Restore, as on the website.
   Restore takes the lock (never from another device: it names the device instead), posts
   `/snapshots/{rev}/restore` with `If-Match`, replaces this device's copy with the result (unsynced progress too, after a
   warning) and gives the lock back. Offline or signed out the dialog says the snapshots are in the account.
4. **Mods**: catalogue files (server or MEGA, pinned) and any local `.ftl`/`.zip` (badged Verified or Custom). A set that uses
   Hyperspace without Hyperspace.ftl gets a hint in the picker and a warning on Play, Start and New save's Create
   ([MODS.md](MODS.md#hyperspace-mod-without-hyperspace)).
   Back returns to the saves; a file being added carries on, its progress shown on the saves list and again on coming back.
   MEGA uses our own reader (`android/mods/.../Mega.kt`, the Kotlin twin of `web/mods/mega.mjs`): 16 MiB ranges
   decrypted on the device, resumable after the last whole range, accepted only when the catalogue's SHA-256 matches.

The launcher opens in the device's orientation (a phone held upright or with rotation locked: vertical) and turns with
it; dialogs scroll inside what the bars and keyboard leave, with their buttons always on screen.

## Settings

The cog in the header, on every launcher screen after the notice, so the log can be reached even if sign-in or the game files
fail. All of it belongs to the device, not to a save or an account. Settings lies over the screen it was opened from, which stays
as it was (typed text, a mod download, the place in the list). One page in the launcher's frames, two columns where it is wide
enough: **General** (Screen, Haptics, Language, App icon, each a row that opens its dialog, and the Magnifier switch), **Saves** (not before the game files are in),
**Updates** (Check for updates, the automatic check's switch), **Help** (debug log, Enable debug console). The title and Back stay on screen while the page scrolls; Back, the system's back or the cog return. The
launcher's strings are plain English in the code: it has no translation system.

**Language** writes the same `language` value the game's own list writes into `settings.xml` (`GameLanguage`); the game reads it
at its next start and no longer asks at the first. Options in the game still changes it, and its choice shows here. A language a
mod adds is chosen in the game and is kept here as it stands. The launcher itself stays in English.

### Haptics

Settings > **Haptics** (`game/Haptics.kt`, its own preferences file, never synced): Off, Light, Medium (the default) or Strong,
then a switch per action, each on by default (`Haptics.Action`). Off turns every action off; an action switched off plays
nothing at any strength. The Settings row adds which actions are off. A new action is a `TouchHaptics` kind and an entry in
`Haptics.Action`. There are two. **System power**: a tick on the touch controls for each power step a system box actually
gains or loses (a drag, or the flick for all or nothing; for shields a layer). Nothing when the game refuses the step: no free
reactor power, at the cap, ionised, or a finger held on the same step. **Magnifier**: a tick when the magnifier comes up
(below). The engine calls `AndroidHost.haptic` (`TouchHaptics`, next to the touch HUD's power change); the browser build
plays nothing. FTL on iPad has no haptics at all (no haptic or vibration API in its binary), so there is no native setting.

The strength comes from what the vibrator can do: a click primitive at scale 0.35, 0.65 or 1 (Android 11 and later, where the
vibrator has it), else a 10, 15 or 20 ms pulse at amplitude 90, 170 or 255, else a plain 20 ms pulse: then the setting says
the device cannot vary the strength and offers Off and On. A device without a vibrator shows "This device cannot vibrate".
Choosing a strength plays one tick. The vibrations are touch feedback (`USAGE_TOUCH`), so Android's Touch feedback setting
applies on top. Several steps at once (a flick) play that many ticks, 45 ms apart, at most 16.

### Magnifier

Settings > **Magnifier** (`game/Magnifier.kt`, its own preferences file, off by default, from the next game started). Neither
FTL on iPad nor on Windows has one; it follows the third-party ftl-android runner's magnifier (GPL-3.0, behaviour only, none of
its code). While the first finger is held on the game for 0.22 s, or once it has moved 3 % of the picture's height, a square
32 % of the picture's height shows the picture under it at twice the size, 10 % above the finger (beside it, away from the
nearer edge, when there is no room above), kept on the picture, with an amber mark on the touch point; lifting the finger
hides it. `GameRenderer` copies it from the frame it shows (a framebuffer blit), over everything.

It only looks: the engine gets every finger event exactly as without it (`Input` hands the same events to both), so the power
drags, the beam, crew selection, doors and the held finger's hover all stay as they are, and a slide then lift does what it
always did. That is a press where the touch HUD already takes a finger sliding onto something: the HUD buttons, a system box
(it opens), doors in door control (a drag over them toggles them), a window's buttons (they act on lift), a room. A crew box
does not take a finger that slides onto it (box selection starts instead), so the magnifier does not help there. It
comes up during drags too, as the runner's does. Settings > Haptics' **Magnifier** switch: one tick when it comes up. With
the setting off there is no magnifier at all (`Magnifier.forGame`). The website has no touch layout turned on, so it has none.

### App icon

Settings > App icon: Random and a grid of nine; a tap applies, with no confirmation beyond the warning below. Nine icons from `tools/android-icons/import.py` (adaptive only, as the minimum is Android 8; 36 WebP layers, about 550 KB in
the APK, one APK for every phone). Number 02 is the default, and is re-fitted from the whole art, since the set's own
foreground cuts its letters. No monochrome layer: Android 13 to 16 show no themed version, Android 16 QPR 2 and later make one.

- Android has no call to set an app's icon. Each icon is an `activity-alias` (`AppIcon02`, ...) of the launcher entry, each
  with its own icon and the same label, and exactly one is enabled (`AppIcons`, `setComponentEnabledSettings` from Android 13,
  two calls below it, new one first). What Android has enabled is the truth; each start repairs an interrupted switch. Never
  rename or remove an alias: Android keeps the choice by name, through updates and even through clearing the app's data.
- Every alias opens `LaunchActivity`, which starts `MainActivity` in its own task and ends. Android closes a task whose root
  entry gets disabled, with whatever is in it. So nothing of ours is rooted in an alias, and a switch closes nothing, a running
  game included.
- **Random** changes the icon when the player leaves the launcher (Home, Recents, Back; never for the game or a file picker opening
  over it) with no game open, and after a reboot (`RandomIconReceiver`, disabled unless Random is on; the boot count tells a real
  reboot from the broadcast Android 15 sends when a stopped app is next opened).
- What a launcher does is up to the launcher, and an app cannot ask it. Pixel Launcher (Android 16, tried on the emulator) keeps
  icons placed on the home screen through a change, also with the launcher stopped during it, and shows the new image within two
  seconds. Other launchers may drop a placed icon. On any home app other than those tried (`IconRules.homeTested`) the first
  change asks first, once.
- The section is hidden, and the default stays, where there is no ordinary home screen (TV, watch, car) or Android refused a
  switch once. This is not by Android version: every version the app runs on has the calls.
- The start screen shows no icon from Android 12 on: it could only be the application's own, not the chosen one.

### Save files

The website's backup format (`{schema: 3, kind: "ftl-save", name, setup, meta, run, profile, savedUtc}`, `SaveFile` in savecore), so
a file moves between the website and the app both ways. Schema 2 and 1 read too. Export is in each save's ⋯ menu and under Settings
(which asks which save only when there are several); import is on New save, on the empty list and under Settings, all through the
system's file picker, no permission. Importing from Settings ends on the saves list, showing the new save.

- Export writes the save as the device has it (the account's copy if the device lacks it, as on the website), without
  `meta.playedOn`, which holds the device id.
- A file is untrusted. At most 41 MiB read, strict UTF-8, JSON nesting at most 16, run and profile checked by `XmlShape` to the
  server's limits (`SaveValidation`: 16 and 4 million characters, nesting 64, no DTD, elements and attributes of a profile), the
  mod list to the server's rules. Refused with a plain message; nothing is stored. The setup key, Verified marks and metadata are
  worked out here, never taken from the file, and settings in an old profile are dropped. A run must also have what any loadable run has (`RunShape`: a valid difficulty, the player
  ship, map, sector and visited sectors). Beyond that its content is the engine's to judge: a run that fails to load
  (`RunLoadException`) gets "This run cannot be loaded", with Back to saves or Delete this save, which asks the usual question in the launcher. A file of a whole account
  (`ftl-saves`) is refused, as on the website.
- Importing always makes a new save with a new id and a name not on the list, so nothing is overwritten and it asks nothing,
  except when the save's mods are missing here: then it names them and offers Import anyway. Such a save shows the usual
  "Missing mod" and soft lock until they are added.
- Signed in, sync uploads it as any new save (`base` 0, dirty), so the account's limit of 30 applies. It is refused beforehand when
  the device knows the limit is reached.

### Debug log

Off by default, works in release builds. `DebugLog`, in app-private `files/log/`:

- **Log**, while on: this process's own lines from the system log (`logcat --pid`, which needs no permission), from whatever wrote
  them: launcher, sync, engine, Lua and mod script prints, the platform's libraries. Two files of 1 MiB each.
- **Crash reports**, always, on or off, two files of 128 KiB each: uncaught exceptions, errors that stop the game, and at the next
  start what Android recorded about the last death from Android 11 (native crash, not responding with its trace, killed for
  memory). Release builds have shrunk names; the build's `mapping.txt` reads them.
- Everything passes `Redact` on the way in: header lines of cookies and tokens, keys naming a secret, the site's cookies by name,
  query and fragment of web addresses (a MEGA key is a fragment), user:password@, email addresses, long opaque values. The app logs
  no credentials itself, and neither the username nor the device id; this is the second line for an exception text or a mod's print.
- It leaves only by Export log, with a header of versions, model and memory, no account or device id. Clear deletes it.

### Debug console

Help, **Enable debug console**: off by default, a device setting (shared preferences `debug-console`), in release builds
too, read when a game starts. On, a floating `>_` button (`ConsoleButton`, 48 dp) lies over a run, not over the menus or
the hangar: a tap opens the debug console with the keyboard (another tap closes it; Back first hides the keyboard, as
Android does, then closes it), a drag moves it. Its place is
kept as fractions of the screen's free room, so it comes back after a restart or a rotation and never leaves the screen;
the default is the right edge a little below the middle, clear of Jump, Options and Pause. It is an Android view of its
own, so only its square takes touches. The commands and the gating rule: [CONSOLE.md](CONSOLE.md).

### Updates

The app asks GitHub itself whether a newer release is out, never our server (`update/UpdateCheck.kt`, `update/Updates.kt`):
`GET https://api.github.com/repos/911bob2/ftl-unofficial-browser-edition/releases/latest`, the public repo's latest
release, which is never a draft or a pre-release.

- **What it sends**: `Accept`, `X-GitHub-Api-Version` and `User-Agent: FTL-Unofficial-Android/<versionName>`, plus
  `If-None-Match` with the last answer's ETag. No cookie, account, email or device id. GitHub sees the device's IP address,
  as with any request.
- **How often**: the launcher asks when it comes to the front (`MainActivity.onResume`), in the background, after the notice
  is agreed, never with a game open, and at most once a day, counted from the last attempt (failed ones too, so nothing is
  retried before the next day; a clock set back allows the next one). An unchanged release answers 304, which does not count
  against GitHub's 60 an hour for unauthenticated requests. Offline, rate-limited (403, 429), another status, or an answer
  that does not read: nothing shows, one `FTL-Update` debug log line, and the last answer stays.
- **What counts as newer**: the tag against `versionName`, numeric parts only, `v` optional, a missing part 0 (`2.0` equals
  `2.0.0`). A version name with a suffix after `-` or `+` (`2.0.1-beta`) is a build before that release, so `v2.0.1`
  is offered to it. A tag with a suffix, or anything that does not parse, never prompts. Offered only with an APK asset (name
  `*.apk` or the APK content type) and a page on `https://github.com/`.
- **The launcher**: a row above the screen's content, after the notice: "Version X is available", **Not now** and **Download**.
  Download opens the release's page (`html_url`) in the browser; the app never installs anything (no install-packages
  permission). Not now hides that version until a newer release appears, through restarts. Up to date: nothing.
- **Settings > Updates**: **Check for updates** shows this version, asks at once (whatever the switch and the day), then
  "You have the latest version", "Version X is available" with Download (Not now does not hide this), or why it failed.
  **Check for updates automatically**: on by default, a device setting (preferences `updates`); off, no request is made but
  Check for updates'. The banner still shows a release already seen.
- Debug builds take a stand-in for GitHub (`tools/android-update/stub.py`): debug console `updateurl <url>|clear`,
  `updatereset` (forgets the last check, ETag, release and Not now), `updatestate` (logs them). A test server's pages may be
  plain `http`; the release build has no way to change the address.

## Screen

Settings > **Screen**, the device's setting (`game/ScreenFit.kt`, its own preferences file; it never reaches a save or a mod
hash). A running game keeps its shape; the next start reads the setting.

| Option | Picture | Canvas (height 768) |
| --- | --- | --- |
| Default | The game's own shape with black borders: 16:9 (its art and desktop layout) or 4:3 (its iPad layout), whichever gives the larger picture on this screen. 20:9, 16:9 and 16:10 get 16:9; 3:2 and 4:3 get 4:3. | 1365 or 1024 |
| Stretch | The Default picture pulled to the whole screen, unevenly. | 1365 or 1024 |
| Custom (experimental) | A canvas of the chosen shape, in even scale, with borders if it differs from the screen. Phones (16:9 and wider) are offered 16:9, 18:9, 19.5:9, 20:9, 21:9, tablets 4:3, 3:2, 16:10, in both cases only those not wider than the screen, plus the screen's own shape if none matches. | 768 × shape, at least 1024 |

The game view is sized to the picture, so the borders are outside it and take no fingers; a drag that leaves the view
continues. Screens without a touch layout (1280×720) fit evenly inside the view, or stretch under Stretch. The HUD stays a
1024 wide block in the middle in every mode; the scene covers the canvas (`InGameState`, `TouchArt.cover`).

Resolution, on screens at least 900 pixels tall: **Sharp** (the default) draws the frame at twice the canvas's pixels and
scales it down, **Standard** draws it at the canvas's pixels and scales it up. Same layout and touch; Sharp keeps the
art's and text's hard edges and costs four times the pixels. Nothing above double exists because the art is 1:1 at 768.

## Touch controls

The iPad version on phones and tablets (iOS has no separate iPhone layout): a 1024×768 HUD centred in a W×768 canvas
(W from Screen above), so a phone gets the iPad layout scaled, with more space at the sides when the canvas is wide. All of it sits behind `TouchMode.enabled`
in the engine (`game/TouchHud.kt`, `game/TouchRouter.kt`, the windows' touch branches); the website does not turn it
on yet.

- Crew: tap to select, tap a room to move, box select with the buttons by the list. Under Hyperspace a finger held
  still on a crew box for 0.5 s picks the crew member up and a slide moves them up or down the list: HS's right-drag
  reorder (CrewMemberFactory::MoveCrewMemberToSpot on the box under the finger), kept and saved as the desktop keeps it
  (`PlayerShipUI.hsCrewOrder`, [HS_UI.md](HS_UI.md)). The touch only becomes a reorder once the crew member reaches another box; held and
  lifted on the same box it selects, and the box's tooltip shows while held, as before (`TouchHud.reorderTouch`).
- Systems: tap a box to open it, tap again or on empty space to close; drag up or down for power, flick for all or
  nothing; drag sideways or tap the tab for the info drawer. Doors: open the box, then tap or drag over doors.
  An open box dims the rest of the bar; an open drawer taller than the box grows the box. Hazards show at the top of the
  screen, between the ship button and the options button.
- Weapons and drones: open the box for the column; tap a slot to arm, tap a room to aim (with auto-pause banners when
  the option is on), the power tab or a drag on the slot to power and reorder.
  A beam: press, drag, lift to fire. With exactly one more finger down anywhere, the black border included, moving the
  first finger slides the whole line, kept inside the enemy ship. Other fingers do nothing, as on iOS: the first owns the
  gesture. A finger that begins on the border is not a game finger, so a hand holding the phone blocks nothing.
  If the system takes the fingers (Home), the line goes and the weapon stays armed.
  Each slot and AUTO FIRE has its own info drawer.
- Crew powers: a lone selected crystal shows a lockdown button on its room; Hyperspace's power buttons are on the panel
  beside the touched crew box.
- Windows take a finger as the left mouse button, acting on lift; two-step store buying, map and hangar as on the iPad.
- Over capacity (`ShipWindow`, `ShipEquipmentPanel`): the crew screen's over-capacity box sits under the window's left
  half, where the iPad game puts it (30, 484); Hyperspace's box for the crew member past the limit (on the
  desktop at x 146 under that box) goes right of it, as below it would be off the screen, and its dismiss confirmation
  opens above it. The equipment screen's item and augment boxes stay in the info column under the info box, where the
  iPad has them (its (600, 320) under its info box; ours follow our info box). The original and the iPad draw those two
  boxes at one place, the augment box over the item box; on touch one shows at a time, with a pager of two arrows over
  the box (the iPad's `img/ipad/customizeUI/button_arrow`, the art Hyperspace's pager uses at desktop size), turning to
  the box that took an item last. Multiverse's multipleOverCapacity keeps Hyperspace's own pager. The desktop keeps the
  original's places and overlap.
- Art and text come from the player's `ftl.dat` (`img/ipad/...`, `*_ipad` ids). A mod that blanks the wrench icon
  (Multiverse) gets a labelled Options tab instead.
- A Hyperspace system over eight levels (Multiverse's shields go to 16) keeps the height of eight in its box: the bars
  shrink, so the column does not reach the ship. The iPad's drag (a level per 16 px) holds up to eight levels; past eight a
  drag step is the drawn bars' spacing (for shields with their pair gaps), so the finger stays on the bar it set
  (`TouchPowerDrag`, `TouchPowerDragTest`).
- **Desktop HUD elements** the iPad never had (`game/TouchExtras.kt`), with no code per element: every button of the desktop
  HUD's list that the touch HUD does not draw itself (Hyperspace's event buttons, from any mod; the crew list's page
  arrows), the Lua drawing of the FTL_BUTTON and SHIP_STATUS render stages (Multiverse's speed control and addon icons),
  Hyperspace's More Info button and triggered-event boxes. (SPACE_STATUS drawing follows the touch hazards spot.) Each is drawn by its own desktop code under a transform that
  puts it in its touch place; what it drew is measured (`rendering/DrawProbe`) and fitted there the next frame. The measure
  goes back through the piece's own transform, so a new one moves the piece only when it differs by more than float noise
  (`TouchExtras.settle`): before, edges that came back as 529.9999 one frame and 530.0001 the next were floored and ceiled,
  the piece moved, the next measure differed again, and Multiverse's speed control shook by up to 8 screen pixels. A tap is the
  desktop click at the matching desktop point: a button's own mouseDown, or for Lua drawing the mouse resting there for a
  drawn frame, then ON_MOUSE_L_BUTTON_DOWN and UP. A held finger shows the hover image and tooltip.
  Places: event buttons by their `<location>` in the top row, in Hyperspace's order (1 before the ship button, 2 after it,
  3 after the store, 4 on; 0 a second row), all smaller when they would reach Options; the hazards go in the gap left.
  Anything else by where the desktop draws it: top bar to the top row, status corner (Multiverse's jukebox, toggle and
  recall buttons, the addon icons) to a shelf under the touch status, the FTL_BUTTON drawing at the shelf's right end
  under the scrap counter, the crew corner under the station buttons (page arrows beside them), the rest (More Info, a
  mod's customLocation button) to a dock above Pause. A piece drawn in several far places, or larger than a button, keeps
  the desktop layout fitted to the canvas.
  The shelf keeps the desktop's sizes relative to each other: one scale for all its pieces (the tallest image fills the
  shelf's 46 px), each one's solid art (pixels at least half opaque, `Texture.solid`, worked out for images up to 256x256 as
  they load) centred on the shelf's middle line, 12 px between neighbours' art (room for two 5 px glows); the FTL_BUTTON
  drawing's art ends at the shelf's right end on the same line. So Multiverse's jukebox (art 32x30, a tab cut flat at the
  top that hangs under the desktop's hull box) and toggle (32x33 inside a glow) sit level and the same size although their
  images' transparent margins differ. The order is the desktop HUD's list (event buttons as Hyperspace keeps them, then the
  Lua drawing). The small grey ship with "TRC" is Multiverse's addon icon for The Renegade Collection, an add-on that is not
  installed (`ADDON_TRC_DISABLED` in `data/events_addon_icon.xml`, drawn by `addon-icon.lua`); a tap shows its info event.
- **Tutorials** on touch. Vanilla plays the iPad lessons (`BrowserTutorialDefinition`, IPAD order and lines): like the iOS
  game it opens the engines box for IPAD_TOOLTIPS and the weapons box for COMBAT_1 and keeps them open (a finger elsewhere
  does not close them), opens the weapons box for INVENTORY_1 and COMBAT_5, closes the boxes at DAMAGE_SYSTEM_1 and after
  INVENTORY_2, and shows the hand only while a lesson waits for its action. Any text id `<name>` reads `<name>_TOUCH`
  where the files have one (a mod's or our touch wording, for any touch layout), else `<name>_IPAD` (the original's own
  iPad lines), unless the language translates `<name>` but not the touch line (`Translator.touchKey`). A script's pointers (`game/TouchPointers.kt`): a `Hyperspace.TutorialArrow` or
  `GL_DrawRectOutline` drawn by Lua HUD drawing over a desktop HUD element the touch HUD knows (crew boxes, top buttons,
  the player ship, the target box, the pieces above) is drawn at that element's touch place, an arrow's tip at most 10 px
  from it, from the side where it covers least of the script's own text boxes; over anything else it keeps the fitted
  desktop layout. Lua drawing with a `WindowFrame` (a script's text boxes) keeps the fitted desktop layout and takes
  fingers only on what it drew. `Hyperspace.Mouse.position` and the Lua mouse events give the desktop point of what is
  under the finger (a window's point where the desktop places it), so a script's hover and click areas work.
  Mods whose lines name mouse or keys get our own wording as a merge layer over exactly the archive it was written for
  (`mods/TouchPatches.kt`, `assets/touch-patches/`): Multiverse 5.5.1's tutorial, seven lines. Report and step tables:
  `.build/hs/reports/touch-tutorial.md` (ignored).
- Names are typed on the system keyboard, which sends single characters; the game slides up to keep the field in view
  (`TouchMode.keyboardFocusY`). A mouse's left button acts as a finger.

## Testing

- `./gradlew --offline :savecore:test` (shared vectors), `:mods:test` (merge and MEGA against the website's
  fixtures), `:engine:testDebugUnitTest`, `:app:testDebugUnitTest`.
- Emulator: AVD on the android-36 x86_64 image; `adb` installs `app-debug.apk`.
- Multi-touch: real fingers through the input stack on the emulator, [TESTS.md](TESTS.md#android-multi-touch-on-the-emulator).
- Over capacity on touch: `node tools/android-touch/overcap.mjs --serial <serial> --out <dir> --name <shape> [--multiple]`
  in flight on a new run (`--multiple` for Multiverse): the debug console's get fills augments, cargo and crew, the
  receiver's `overcap` gives the open ship window's boxes (`overcappage` turns the equipment pager); checks one box at a
  time, a tap on the pager's arrow, every crew member's box and dismiss button clear of the over-capacity box, and a
  dismiss by taps with the confirmation on the screen; phone and tablet shapes through `wm size` ([TESTS.md](TESTS.md#over-capacity-boxes)).
- Desktop HUD elements on touch: `TouchExtrasTest` (places, the event button restore and the storage crate an older save
  lost, the settled measure, the shelf's alignment, the probe's solid art) and, on the emulator,
  `node tools/android-touch/extras.mjs --serial <serial> --out <dir>` (one finger through `adb input`, no gRPC): in flight on
  a Hyperspace save it adds a made-up mod's two event buttons (debug console `modbutton <name> <location>` or
  `modbutton <name> <x> <y>`), checks their places and that a tap runs each one's click; on Multiverse also the storage crate
  (drawn after the ship button, active exactly out of danger), the shelf's alignment (one scale, the art's middles on one
  line, equal gaps), the speed control and More Info; and on any save that no piece moves over 4 seconds of frames
  (`moves` in `touchstate`: changes of place between drawn frames). `--only-layout` leaves out the taps (combat, after a
  jump). `touchstate` answers longer than logcat's line come in numbered parts, which `device.mjs` joins. `hscmd <command>` runs a Hyperspace console command (`hscmd LUA ...`, `hscmd STORE`).
- Lua patch runtime (a JVM test cannot load the `.so`, so a debug-only `main` runs on the device): lua-cases and L1
  against ftlman's results, parity against the website's runtime and merge (recorded when the vectors are exported),
  security, bombs. After `:mods:modVectors` and installing the debug APK:
  ```
  adb push android/mods/build/mod-vectors.json /data/local/tmp/
  APK=$(adb shell pm path dev.ftl911bob.unofficial | tr -d '\r' | sed s/package://)
  adb shell "CLASSPATH=$APK app_process / ftl.android.app.debug.LuaVectors /data/local/tmp/mod-vectors.json '$APK!/lib/x86_64/libftlmanlua.so' [cases|l1|parity|security|native|bombs|smallstack]"
  ```
  It prints PASS/FAIL per case and a total. In git bash, `export MSYS_NO_PATHCONV=1` first.
- Haptics: `HapticsTest` (when the power tick fires, what each strength plays, which action plays with which switch) and, on
  the emulator, logcat's `FTL-Haptic` lines (debug builds log every tick, and `skip ... action off` for a switched-off one; `adb shell dumpsys vibrator_manager` lists what the vibrator played). Debug console:
  `power` logs each system's power and the free reactor power, `ion <system> <units>` ionises one, `hapticdevice
  PRIMITIVES|AMPLITUDE|ON_OFF|NONE|auto` pretends a weaker vibrator for the setting's texts.
- Sign out: `SignOutTest` (the unsynced line's rule, the words equal `web/sign-out.mjs`'s); the dialog on the emulator through
  `uiautomator` taps on Sign out, Cancel and Sign out.
- Magnifier: `MagnifierTest` (when it shows: never for a quick tap, after the hold or a move, once per hold for its haptic;
  only the first finger; where it sits and what it shows; off by default and absent when off). On the emulator: debug
  builds log `FTL-Magnifier shown at ...` with the haptic's `FTL-Haptic` line; `adb shell input motionevent DOWN|MOVE|UP x y`
  holds a finger for screenshots.
- Tutorial pointers: `TouchPointersTest` (an arrow's element, its place and side; an outline over one element or several;
  the `_TOUCH` / `_IPAD` rule; the touch layers' archives) and, on the emulator in Multiverse's tutorial,
  `node tools/android-touch/tutorial.mjs --serial <serial> --out <dir> [--fight]`: starts each lesson with pointers through
  the debug console and checks every arrow's tip is within 10 px of its element's touch place and every outline on it
  (`pointers` in `touchstate`: kind, desktop and canvas places, the element). `touchstate` also has `tutorial` (the vanilla
  lesson, whether Continue shows), `crewOrder` and `crewHeld`. Crew reorder:
  `node tools/android-touch/reorder.mjs --serial <serial> --out <dir>` on a Hyperspace run with three crew.
- Power drag past eight levels: `TouchPowerDragTest` (the iPad's step up to eight; past eight the finger on the drawn bar,
  up and down, on every count to 20 with and without pairs).
- Updates: `UpdateCheckTest` (versions: equal, newer, older, `v`, `2.0` against `2.0.0`, junk; once a day, failures
  included; Not now per version; the ETag and a 304; the setting off makes no request; only an APK with a GitHub page). On
  the emulator, against `tools/android-update/stub.py`: [TESTS.md](TESTS.md#android-update-check).
- Settings on the emulator: icons, Random, files and the log are driven by the debug console too: `appicon NN`, `iconrandom on|off`, `iconhome <package>|clear` (pretend another home app), `iconrefused on|off`, `crash-main`, `crash-thread`, `crash-native`, `logspam N`. `am crash` works in release builds. Open the app with `monkey -p <package> -c android.intent.category.LAUNCHER 1`, as the launcher entry's name changes.
- Debug console from adb (debug builds): `adb shell am broadcast -n dev.ftl911bob.unofficial/ftl.android.app.debug.ConsoleReceiver --es cmd "'STORE'"`; the same commands as the in-game console ([CONSOLE.md](CONSOLE.md)).
  Debug-only extras for the weapon bars: `weaponpower <slot>`, `weaponcharge <slot> <fraction>`, `touchweapons` (opens the
  touch weapon column); `reload` saves and loads the run as Continue does.
- The in-game console (release builds too): Settings, Help, **Enable debug console** (off by default, from the next game started)
  puts a floating `>_` button over a run; a tap opens the console with the keyboard, a drag moves the button (kept for the next
  start, clamped to the screen). `ConsoleTest` checks the place arithmetic; the emulator check is in [CONSOLE.md](CONSOLE.md).
- Hyperspace Lua bridge under ASan on the emulator: [tools/hs-lua-fuzz](../tools/hs-lua-fuzz/README.md).
