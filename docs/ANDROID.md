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
and x86_64, native libraries with 16 KB page alignment. Permissions: `INTERNET`, `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED` (for Random only, see App icon). Plain HTTP and
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
   saves made while playing offline into the account, where they upload ("Enable cloud saves").
2. **Game data**: Admin/User download the server's prepared assets once; External users and offline players import
   their own `ftl.dat` (Storage Access Framework). It is checked against the expected SHA-256 and never uploaded.
3. **Saves**: the one list of [SAVES.md](SAVES.md) with sync status, locks, New save, Advanced.
4. **Mods**: catalogue files (server or MEGA, pinned) and any local `.ftl`/`.zip` (badged Verified or Custom).
   Back returns to the saves; a file being added carries on, its progress shown on the saves list and again on coming back.
   MEGA uses our own reader (`android/mods/.../Mega.kt`, the Kotlin twin of `web/mods/mega.mjs`): 16 MiB ranges
   decrypted on the device, resumable after the last whole range, accepted only when the catalogue's SHA-256 matches.

The launcher opens in the device's orientation (a phone held upright or with rotation locked: vertical) and turns with
it; dialogs scroll inside what the bars and keyboard leave, with their buttons always on screen.

## Settings

The cog in the header, on every launcher screen after the notice, so the log can be reached even if sign-in or the game files
fail. All of it belongs to the device, not to a save or an account. Settings lies over the screen it was opened from, which stays
as it was (typed text, a mod download, the place in the list). One page in the launcher's frames, two columns where it is wide
enough: **General** (Screen, Language, App icon, each a row that opens its dialog), **Saves** (not before the game files are in),
**Help** (debug log). The title and Back stay on screen while the page scrolls; Back, the system's back or the cog return. The
launcher's strings are plain English in the code: it has no translation system.

**Language** writes the same `language` value the game's own list writes into `settings.xml` (`GameLanguage`); the game reads it
at its next start and no longer asks at the first. Options in the game still changes it, and its choice shows here. A language a
mod adds is chosen in the game and is kept here as it stands. The launcher itself stays in English.

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

- Crew: tap to select, tap a room to move, box select with the buttons by the list.
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
- Art and text come from the player's `ftl.dat` (`img/ipad/...`, `*_ipad` ids). A mod that blanks the wrench icon
  (Multiverse) gets a labelled Options tab instead.
- Names are typed on the system keyboard, which sends single characters; the game slides up to keep the field in view
  (`TouchMode.keyboardFocusY`). A mouse's left button acts as a finger.

## Testing

- `./gradlew --offline :savecore:test` (shared vectors), `:mods:test` (merge and MEGA against the website's
  fixtures), `:engine:testDebugUnitTest`, `:app:testDebugUnitTest`.
- Emulator: AVD on the android-36 x86_64 image; `adb` installs `app-debug.apk`.
- Multi-touch: real fingers through the input stack on the emulator, [TESTS.md](TESTS.md#android-multi-touch-on-the-emulator).
- Lua patch runtime (a JVM test cannot load the `.so`, so a debug-only `main` runs on the device): lua-cases and L1
  against ftlman's results, parity against the website's runtime and merge (recorded when the vectors are exported),
  security, bombs. After `:mods:modVectors` and installing the debug APK:
  ```
  adb push android/mods/build/mod-vectors.json /data/local/tmp/
  APK=$(adb shell pm path dev.ftl911bob.unofficial | tr -d '\r' | sed s/package://)
  adb shell "CLASSPATH=$APK app_process / ftl.android.app.debug.LuaVectors /data/local/tmp/mod-vectors.json '$APK!/lib/x86_64/libftlmanlua.so' [cases|l1|parity|security|native|bombs|smallstack]"
  ```
  It prints PASS/FAIL per case and a total. In git bash, `export MSYS_NO_PATHCONV=1` first.
- Settings on the emulator: icons, Random, files and the log are driven by the debug console too: `appicon NN`, `iconrandom on|off`, `iconhome <package>|clear` (pretend another home app), `iconrefused on|off`, `crash-main`, `crash-thread`, `crash-native`, `logspam N`. `am crash` works in release builds. Open the app with `monkey -p <package> -c android.intent.category.LAUNCHER 1`, as the launcher entry's name changes.
- Debug console from adb (debug builds): `adb shell am broadcast -n dev.ftl911bob.unofficial/ftl.android.app.debug.ConsoleReceiver --es cmd "'store'"`.
- Hyperspace Lua bridge under ASan on the emulator: [tools/hs-lua-fuzz](../tools/hs-lua-fuzz/README.md).
