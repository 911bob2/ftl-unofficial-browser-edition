# Browser performance

The Java/Kotlin engine is compiled ahead of time to JavaScript by TeaVM. A JVM
does not run on the player's device. Rendering uses WebGL2 and audio uses Web
Audio. Combat runs on the client; VPS CPU and network latency do not determine
the frame rate once the game assets have loaded.

TeaVM is used to run the maintained Java/Kotlin code in the browser, not because
a measured comparison establishes it as faster than hand-written JavaScript.
This build targets JavaScript, not WebAssembly. TeaVM performs ahead-of-time
optimization, but the browser still executes the generated JavaScript. See
[TeaVM's overview](https://teavm.org/docs/intro/overview.html).

Options → Video's Frame Limit row cycles 30, 60, 90, 120, 240, 360 and Unlimited.
The profile and cloud save retain the selection; a profile without this setting
defaults to 60. A click or Right/Enter/Space steps forwards, Left steps back, and a
cap saved from the earlier slider moves to the nearest step. Unlimited removes the application's drawing
limit. Input/simulation still update on browser animation callbacks regardless
of that drawing limit. Drawing-based UI animations and tooltip delays receive
the accumulated elapsed time between drawings; a real-door regression checks
the same opening progress at 30/60/120/240 drawings per second. The browser normally schedules callbacks at the display
refresh rate, so Unlimited does not guarantee more than 60 FPS on a 60 Hz surface.
See [animation-frame scheduling](https://developer.mozilla.org/en-US/docs/Web/API/Window/requestAnimationFrame).

The benchmark now reports intervals between actual drawings and includes update
work accumulated between them. Current headless checks establish 30 FPS drawing
with independent update callbacks and persisted finite/unlimited preferences in
Chrome, Edge and Firefox. Synthetic timestamps cover eight refresh rates through
500 Hz; real
high-refresh display behavior remains unmeasured. Historical measurements below
precede this setting and are not a benchmark of the current parity candidate.

## Repeatable combat benchmark

The authenticated website **Tests** page (`/Diagnostics`) has a **Benchmark**
preset in the normal application build, including Production. It creates isolated Advanced Edition / Hard runs with
an invulnerable Kestrel, enters each of the three real flagship phases, warms up
for two seconds, then measures 5, 10 or 30 seconds of normal animation-frame simulation
and rendering per phase. Choose a temporary FPS limit or Unlimited. It does not write cloud saves. Keep the tab in the
foreground. Hiding or stopping the game cancels measurement.

Sign in at the root of the same server first, then use **Tests** in the website
navigation. An anonymous account, a disabled account, or one awaiting initial
password rotation receives HTTP 403 from this page. Integration checks use
separate disposable databases and packaged container browsers. Their timings
describe the container; the Benchmark preset measures the current browser and
PC. See [launcher tests](TESTS.md) for selection, cancellation and report export.

The report includes FPS, median and 95th-percentile frame intervals, update and
render work, intervals longer than 33.34 ms, and approximate JavaScript heap
usage where Chromium exposes it. Render work measures CPU time issuing WebGL
commands, not GPU completion. FPS and frame intervals include actual frame
pacing. The heap figure excludes other browser/GPU memory and is not a total
RAM requirement. These short samples do not cover every combat effect, loading,
long-session memory growth, or thermal throttling.

## Testing slower CPUs

In desktop Chrome, open DevTools, then **Performance > Environment settings >
CPU** and select a slowdown. Use the same benchmark for an unthrottled baseline,
then 4x and 6x slowdown. Keep network throttling disabled when comparing combat
performance. Set CPU back to **No throttling** when finished.

Current Chrome also offers calibrated low-tier and mid-tier mobile CPU presets:
**Settings > Throttling > CPU throttling presets > Calibrate**. Calibration
reloads the page; start the game benchmark afterward. See the official
[CPU throttling documentation](https://developer.chrome.com/docs/devtools/settings/throttling/)
and [fixed slowdown controls](https://developer.chrome.com/docs/devtools/device-mode/#throttle-the-cpu-only).

Throttling approximates slower CPU execution relative to the host. It does not
replicate an older integrated GPU, constrained RAM, different CPU architecture,
or laptop thermal behavior. Changing the viewport or choosing a device name by
itself is not a CPU benchmark. A real modest laptop remains the best validation.

The connected Codex in-app browser exposes viewport and visibility controls but
does not expose a CPU-throttling control. Results from that connection must be
labelled **unthrottled**, with any multiplied time budgets labelled estimates.

## Measured baseline — 2026-10-01

Unthrottled in-app Chromium 154 on Windows, Ryzen 9 9900X3D host with Radeon RX
9070 XT and integrated Radeon graphics available. The active WebGL adapter was
not identified. Canvas backing resolution was 1280 × 720. This is a powerful
desktop host, not a measured low-end laptop. The tested image configuration is
`sha256:42993eb8f07d20f67e6694a32ec74d28a645cce82407ec13447beba667516f0c`.

| Flagship phase | Average FPS | Frame interval p95 | Update + render work p95 | Intervals >33.34 ms | Approx. JS heap |
| --- | ---: | ---: | ---: | ---: | ---: |
| 1 | 60.8 | 16.8 ms | 3.9 ms | 0 / 609 | 187 MiB |
| 2 | 59.8 | 16.8 ms | 4.1 ms | 1 / 599 | 177 MiB |
| 3 | 60.8 | 16.8 ms | 4.5 ms | 0 / 609 | 215 MiB |

Simulation update p95 stayed near 0.6 ms in every phase; most measured work was
render submission. One of 1,817 recorded intervals exceeded 33.34 ms. Raw JSON,
text, and the visible browser screenshot are preserved privately in ignored
`.build/performance-results.json`, `.build/performance-results.txt`, and
`.build/browser-performance.png`.

The 60 FPS frame budget is 16.7 ms; the 30 FPS budget is 33.3 ms. Multiplying the
worst measured work p95 (4.5 ms) by four gives 18 ms; multiplying by six gives
27 ms. This is only a rough arithmetic headroom estimate, **not a throttled FPS
measurement** or a calibrated equivalence to any particular laptop. It supports
30 FPS as a reasonable expectation for a modest laptop with hardware WebGL2,
and 60 FPS as plausible. Weak hardware and long sessions still need direct
testing before either becomes a guaranteed requirement.

The benchmark initially exposed stale hacking and boarding-drone owner
references when a flagship phase was removed immediately before an autosave.
The candidate build cleans detached hacking probes and skips orphan boarding
drones during save serialization, matching the existing external-drone guard.
The complete three-phase benchmark passes with those fixes. This
historical benchmark sample predates the latest stationary-wait and hacking
cleanup corrections. Those correctness fixes are now deployed on the main
local port 8080. The measurements above describe the recorded sample, not a
new performance measurement of the final deployed image.

## Firefox and mixed-refresh monitors — 2026-10-05

The Tests workspace now distinguishes **Game FPS**, the requested drawing limit,
and **Browser callbacks/s** during each flagship phase. A separate **Check browser
rate** button measures three seconds of animation callbacks without running the
engine. Repeat it after moving the window, changing display settings, or restarting
the browser; exported samples include time, screen size, pixel ratio and window
position. This measures delivered callbacks, not physical presentation or a panel's
advertised refresh rate. Workload can lower the callback rate too. Stop or a hidden
tab invalidates this browser-only sample. Unlimited draws once per callback; a
30 FPS cap should reduce drawings while leaving callback counts independent.

Read-only inspection of the user's Windows host found installed Firefox **157.0**,
build 20260924084938. Both active displays were configured at **60 Hz**: primary
2560×1440, secondary 1920×1080. The primary advertises modes up to 500 Hz; the
secondary up to 60 Hz. The monitor names are Odyssey G60SF and HP E243. No explicit
frame-rate, resist-fingerprinting, acceleration or WebRender overrides were found
in the active release profile's prefs.js. No display or Firefox settings were changed.
Ignored evidence: `.build/display-snapshot.json`, `.build/display-snapshot.ps1`.

The installed release's source revision is
`8eb25af4acf031ab1e06abf1a912275083c820ed`. In its Windows
`D3DVsyncSource::UpdateVBlankOutput`, the hardware clock selects
`MonitorFromWindow(nullptr, MONITOR_DEFAULTTOPRIMARY)`: the **primary display**,
not the display containing the browser window. Its interval is initialized from
DWM composition timing, with a 60 Hz fallback. This supports the inference that
moving Firefox alone can retain primary-display timing; it does not establish
that the first window's monitor locks the rate. See the
[exact installed Firefox Windows source](https://hg.mozilla.org/releases/mozilla-release/raw-file/8eb25af4acf031ab1e06abf1a912275083c820ed/gfx/thebes/gfxWindowsPlatform.cpp)
and [Mozilla's Windows source browser](https://searchfox.org/firefox-main/source/gfx/thebes/gfxWindowsPlatform.cpp).

Firefox has no universal 60 FPS maximum. `layout.frame_rate` defaults to `-1`
(automatic platform timing); positive values force software vsync. Missing
platform timing falls back to60, and resist-fingerprinting can also force 60.
Do not recommend disabling privacy protections as a performance fix. See
[preference defaults](https://searchfox.org/firefox-main/source/modules/libpref/init/StaticPrefList.yaml)
and [frame-rate selection](https://searchfox.org/firefox-main/source/gfx/thebes/gfxPlatform.cpp).
The initial action on this host is to select the desired supported rate for the
Odyssey in **Windows Settings → System → Display → Advanced display**, then repeat
the browser-rate and Unlimited combat measurements. See
[Microsoft's refresh-rate instructions](https://support.microsoft.com/en-au/windows/hardware/display-graphics/change-the-refresh-rate-on-your-monitor-in-windows).
If callbacks remain60 on a genuinely high-refresh primary display, compare a
restarted Firefox and Chrome before considering a Firefox software-clock override.

Native Firefox monitor-move/restart and actual500 Hz presentation are **unmeasured**.
Windows computer use refused state capture because its URL policy cannot be
enforced for this Firefox session; no UI input was issued and that path was stopped.
The packaged/headless Firefox tests establish instrumentation and drawing/callback
correspondence, not native high-refresh hardware performance. A successful synthetic
500 Hz fixture is also not a 500 FPS performance claim.

## User-reported 20x slowdown

The user's Chrome report measured flagship phases 1/2/3 at approximately
4.63/5.04/5.42 FPS with 20x CPU slowdown. Render work p95 was approximately
271/232/251 ms; update p95 was 2.2/37.4/17.4 ms. This is observed throttled
performance supplied by the user, not an estimate from multiplying the baseline.
It demonstrates that the current renderer does not sustain 30 FPS at that
slowdown. It does not establish how a particular work laptop will perform.

The current release keeps the unoptimized engine as requested. Optimization
or a different compilation target is deferred until actual-device testing
shows it is needed.

## Rendering and gameplay RNG

The 2026-10-04 tutorial damage fixture exposed screen shake using the persistent
simulation RNG once per rendered offset. It now uses a separate cosmetic random
source. Ordinary damage/render checks at 30/60/120/240 FPS and all tutorial
instruction panels preserve gameplay RNG and serialized state in Chrome, Edge
and Firefox. This is a correctness fix; no new speed comparison is claimed.
