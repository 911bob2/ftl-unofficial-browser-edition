package ftl.browser

import org.newdawn.slick.opengl.InternalTextureLoader
import org.newdawn.slick.opengl.PNGDecoder
import xyz.znix.xftl.environment.EnvironmentAssets
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.ImagePrefetcher
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.hangar.SelectShipState
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.Image
import xyz.znix.xftl.rendering.StagedImageLoad
import xyz.znix.xftl.sector.Beacon
import xyz.znix.xftl.sector.FleetBackground
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.sys.ResourceContext
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * Round 6, jump agent. The ship's jump-away animation (ShipManager::OnRender 0x4a3c00 -> Ship::OnRenderJump
 * 0x48cad0, CommandGui::RenderStatic 0x535070): the zoom curve, the flash, and the planet scene shrinking with the
 * ship, on the player's leave and arrival and on an enemy ship.
 *
 * And the lag at the jump between beacons: the new beacon's pictures load a few milliseconds a frame while the ship
 * is jumping away instead of all on the first frame of the arrival. That changes when pixels are decoded and never
 * what is drawn or drawn from a random stream, so the checks compare the staged load pixel for pixel with a plain
 * one, and the random streams before and after.
 *
 * And the hangar's Advanced Edition toggle, which used to rebuild the game content and the hangar on every click.
 */
internal object GameplayParityJump6Checks {
    fun run(main: MainGame, container: GameContainer, graphics: Graphics): String {
        val rng = BrowserRandom.snapshot()
        val nativeRng = NativeRandom.snapshot()
        try {
            val zoom = zoomCurve()
            val flare = flareCurve(main, container)
            val scene = sceneTimeline(main, container, graphics)
            val staged = stagedLoad(main, container)
            val queue = prefetchQueue(main, container)
            val assets = environmentAssets(main, container)
            val ahead = jumpPrefetch(main, container)
            val toggle = aeToggle(main, container, graphics)
            return "jump6 ok: $zoom, $flare, $scene, $staged, $queue, $assets, $ahead, $toggle"
        } finally {
            BrowserRandom.restore(rng)
            NativeRandom.restore(nativeRng)
        }
    }

    /**
     * The zoom rises linearly over the first quarter of the 2 s tracker (0.5 s) and holds: the ship shrinks by
     * 0.75 of it (scale 0.25 at the top), the planet scene by 0.05 of it (0.95).
     */
    private fun zoomCurve(): String {
        check(InGameState.browserJumpZoom(0f) == 0f) { "zoom at 0 s: ${InGameState.browserJumpZoom(0f)}" }
        check(InGameState.browserJumpZoom(0.25f) == 0.5f) { "zoom at 0.25 s: ${InGameState.browserJumpZoom(0.25f)}" }
        check(InGameState.browserJumpZoom(0.5f) == 1f && InGameState.browserJumpZoom(1.2f) == 1f &&
                InGameState.browserJumpZoom(2f) == 1f) { "zoom does not hold at 1 after 0.5 s" }
        check(1f - 0.75f * InGameState.browserJumpZoom(0.25f) == 0.625f &&
                1f - 0.75f * InGameState.browserJumpZoom(0.5f) == 0.25f) { "ship scale" }
        return "zoom curve"
    }

    /**
     * The flash: p = fade / 0.75, 64 + 1200 p px up to p = 0.5 then 1200 (1 - p), none at either end. It runs along
     * the middle line of the player's hull from stern to bow, and up the enemy's hull from the bottom.
     */
    private fun flareCurve(main: MainGame, container: GameContainer): String {
        check(InGameState.browserJumpFlareProgress(0.375f) == 0.5f && InGameState.browserJumpFlareProgress(0.75f) == 1f) {
            "flare progress"
        }
        check(InGameState.browserJumpFlareSize(0f) == 0f && InGameState.browserJumpFlareSize(1f) == 0f &&
                InGameState.browserJumpFlareSize(0.25f) == 364f && InGameState.browserJumpFlareSize(0.5f) == 600f &&
                InGameState.browserJumpFlareSize(0.75f) == 300f) { "flare size curve" }

        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val player = state.player
        val (sternX, sternY) = player.jumpFlareCentre(0f)
        val (bowX, bowY) = player.jumpFlareCentre(1f)
        check(sternX == player.hullOffset.x.toFloat() && bowX == (player.hullOffset.x + player.hullWidth).toFloat()) {
            "player flash runs from $sternX to $bowX over a hull at ${player.hullOffset.x} of ${player.hullWidth}"
        }
        check(sternY == player.hullCentreY && bowY == player.hullCentreY) { "player flash off the middle line: $sternY, $bowY" }

        state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 3, 71)
        val enemy = state.enemy!!
        val (bottomX, bottomY) = enemy.jumpFlareCentre(0f)
        val (topX, topY) = enemy.jumpFlareCentre(1f)
        check(bottomX == enemy.hullCentreX && topX == enemy.hullCentreX) { "enemy flash off the hull centre: $bottomX, $topX" }
        check(bottomY == (enemy.hullOffset.y + enemy.hullHeight).toFloat() && topY == enemy.hullOffset.y.toFloat()) {
            "enemy flash runs from $bottomY to $topY over a hull at ${enemy.hullOffset.y} of ${enemy.hullHeight}"
        }
        return "flare"
    }

    /**
     * The planet scene (planet, fleet, sun) shrinks 5% about the hull centre as the player's ship shrinks, holds
     * while the tracker is above half a second, and comes back for the last half second of the arrival, which runs
     * the tracker backwards. The scene is untouched outside a jump.
     */
    private fun sceneTimeline(main: MainGame, container: GameContainer, graphics: Graphics): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        main.render(container, graphics)
        check(state.jumpSceneScale == 1f && !state.isPlayerJumping) { "scene scaled outside a jump: ${state.jumpSceneScale}" }

        state.player.fuelCount = 5
        state.beginJumpOut(state.currentBeacon.neighbours.first())
        check(state.isPlayerJumping) { "no jump after beginJumpOut" }

        // Steps of 0.05 s do not add up exactly, so the tracker time is read back and the scale checked against it.
        fun sceneAt(seconds: Float, expectedTracker: Float, tolerance: Float): Float {
            var left = seconds
            while (left > 1e-4f) {
                val step = minOf(0.05f, left)
                main.update(container, step)
                left -= step
            }
            main.render(container, graphics)
            val tracker = state.browserPlayerTrackerTime()
            check(kotlin.math.abs(tracker - expectedTracker) <= tolerance) { "tracker $tracker, expected $expectedTracker" }
            val want = 1f - 0.05f * InGameState.browserJumpZoom(tracker)
            check(kotlin.math.abs(state.jumpSceneScale - want) < 1e-5f) { "scene scale ${state.jumpSceneScale} at tracker $tracker, want $want" }
            return state.jumpSceneScale
        }

        // Leaving: tracker 0.25 s (zoom 0.5, scene 0.975), then 0.55 s (zoom 1, scene 0.95), held to the end.
        check(abs(sceneAt(0.25f, 0.25f, 0.001f) - 0.975f) < 0.0005f) { "scene at 0.25 s" }
        check(abs(sceneAt(0.30f, 0.55f, 0.001f) - 0.95f) < 0.0005f) { "scene at 0.55 s" }
        check(abs(sceneAt(1.20f, 1.75f, 0.001f) - 0.95f) < 0.0005f && state.isPlayerJumping) { "scene at 1.75 s" }

        // The location changes at 2 s and the tracker runs backwards: held until its last half second, then back.
        check(abs(sceneAt(0.50f, 1.75f, 0.06f) - 0.95f) < 0.0005f && state.isPlayerJumping) { "scene 0.25 s into the arrival" }
        val back = sceneAt(1.50f, 0.25f, 0.06f)
        check(back > 0.95f && back < 1f) { "scene 0.25 s before the arrival ends: $back" }
        sceneAt(0.60f, 0f, 0.001f)
        check(!state.isPlayerJumping && state.jumpSceneScale == 1f) { "scene still scaled after the arrival: ${state.jumpSceneScale}" }
        return "scene timeline"
    }

    /** Every environment image the prefetch names: what a beacon of each kind and the fleets draw. */
    private fun allEnvironmentPaths(): List<String> {
        val out = LinkedHashSet<String>()
        for (type in Beacon.EnvironmentType.values()) {
            out += EnvironmentAssets.typeBackdrop(type)
            out += EnvironmentAssets.typeAssets(type)
        }
        for (fleet in listOf(null) + FleetBackground.values().toList()) {
            out += EnvironmentAssets.fleetAssets(fleet, false)
            out += EnvironmentAssets.fleetAssets(fleet, true)
        }
        return out.toList()
    }

    /** The plain load, written out separately: decode into a power-of-two buffer, then copy the edge into the padding. */
    private fun plainPixels(bytes: ByteArray): Triple<ByteArray, Int, Int> {
        val decoder = PNGDecoder(ByteArrayInputStream(bytes))
        val w = decoder.width
        val h = decoder.height
        val tw = InternalTextureLoader.get2Fold(w)
        val th = InternalTextureLoader.get2Fold(h)
        val buffer = ByteBuffer.allocate(tw * th * 4)
        decoder.decode(buffer, tw * 4, PNGDecoder.RGBA)
        val a = buffer.array()
        for (y in 0 until h) for (x in w until tw) for (c in 0 until 4) a[(y * tw + x) * 4 + c] = a[(y * tw + w - 1) * 4 + c]
        for (y in h until th) for (x in 0 until tw * 4) a[y * tw * 4 + x] = a[(h - 1) * tw * 4 + x]
        return Triple(a, tw, th)
    }

    private fun stagedMatches(df: xyz.znix.xftl.Datafile, path: String, budget: Double): Int {
        val bytes = df.read(df[path])
        val (want, tw, th) = plainPixels(bytes)
        val context = ResourceContext()
        try {
            val load = StagedImageLoad(context, path, bytes, keepPixels = true)
            var calls = 0
            do {
                calls++
                check(calls < 100000) { "$path: staged load never finishes" }
            } while (!load.step(budget))
            check(load.isDone && load.image != null) { "$path: done without an image" }
            check(load.textureWidth == tw && load.textureHeight == th) { "$path: texture ${load.textureWidth}x${load.textureHeight}, want ${tw}x$th" }
            check(load.uploadedRowCount == th) { "$path: ${load.uploadedRowCount} of $th rows uploaded" }
            val got = load.paddedPixels!!
            check(got.array().copyOfRange(got.arrayOffset(), got.arrayOffset() + tw * th * 4).contentEquals(want)) {
                "$path: staged pixels differ from the plain load (budget $budget)"
            }
            val image = load.image!!
            check(image.width > 0 && image.height > 0 && image.width <= tw && image.height <= th) { "$path: image size" }
            return calls
        } finally {
            context.freeAll()
        }
    }

    /**
     * A load spread over many calls (a budget of nothing does one unit of work a call) and a load in one go leave
     * exactly the pixels the plain decode and edge copy do, uploaded in full, for every picture a beacon can show.
     */
    private fun stagedLoad(main: MainGame, container: GameContainer): String {
        val df = BrowserGameplayParityDiagnostics.fresh(main, container).content.datafile
        val paths = allEnvironmentPaths() + EnvironmentAssets.warmPaths()
        var maxCalls = 0
        var narrow = 0
        var short = 0
        for (path in paths) {
            check(df.getOrNull(path) != null) { "the prefetch names $path, which the data does not have" }
            val calls = stagedMatches(df, path, 0.0)
            stagedMatches(df, path, Double.POSITIVE_INFINITY)
            stagedMatches(df, path, 0.5)
            maxCalls = maxOf(maxCalls, calls)
            val decoder = PNGDecoder(ByteArrayInputStream(df.read(df[path])))
            if (decoder.width < InternalTextureLoader.get2Fold(decoder.width)) narrow++
            if (decoder.height < InternalTextureLoader.get2Fold(decoder.height)) short++
        }
        check(maxCalls > 20) { "the biggest picture took only $maxCalls calls, so nothing is being sliced" }
        check(narrow > 0 && short > 0) { "no picture needs right ($narrow) or bottom ($short) padding, so the edge copy is untested" }
        return "staged load of ${paths.size} pictures (up to $maxCalls slices)"
    }

    /**
     * The queue loads in order, an urgent request jumps ahead, an image asked for while it loads is finished on the
     * spot (the same one the cache then holds), a queued one is simply loaded by its caller, and cancelling frees
     * the half-made texture.
     */
    private fun prefetchQueue(main: MainGame, container: GameContainer): String {
        val df = BrowserGameplayParityDiagnostics.fresh(main, container).content.datafile
        val a = "img/stars/planet_sun_flare.png"
        val b = "img/stars/lightning.png"
        val c = "img/stars/nebula_large_a.png"
        val context = ResourceContext()
        val cache = HashMap<String, Image>()
        try {
            val prefetcher = ImagePrefetcher(df, context, cache)
            check(prefetcher.isIdle && prefetcher.pending == 0) { "a new prefetcher is busy" }
            prefetcher.request("img/stars/does_not_exist.png")
            check(prefetcher.isIdle) { "a missing file was queued" }

            prefetcher.request(a)
            prefetcher.request(b)
            prefetcher.request(a)
            check(prefetcher.pending == 2) { "queued ${prefetcher.pending}, want 2" }
            prefetcher.request(c, urgent = true)
            prefetcher.step(0.0)
            check(cache.isEmpty() && prefetcher.pending == 3) { "a spent budget finished work: ${cache.keys}" }

            // c went to the front, so it is the one in flight.
            val taken = prefetcher.takeNow(c)
            check(taken != null && cache[c] === taken) { "takeNow of the picture in flight" }
            check(prefetcher.takeNow(b) == null && prefetcher.pending == 1) { "takeNow of a queued picture" }

            var spins = 0
            while (!prefetcher.isIdle) {
                prefetcher.step(1.0)
                check(++spins < 100000) { "the queue never empties" }
            }
            check(a in cache && b !in cache && c in cache) { "loaded ${cache.keys}" }
            prefetcher.request(a)
            check(prefetcher.isIdle) { "a loaded picture was queued again" }

            prefetcher.request(b)
            prefetcher.step(0.0)
            prefetcher.cancelAll()
            check(prefetcher.isIdle && b !in cache) { "cancelAll left work behind" }
            return "prefetch queue"
        } finally {
            context.freeAll()
        }
    }

    /**
     * Every beacon's list names pictures that exist, the same each time, and the planet it names is the one the
     * environment really picks. Making them touches no random stream.
     */
    private fun environmentAssets(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val df = state.content.datafile
        val rng = BrowserRandom.snapshot()
        val nativeRng = NativeRandom.snapshot()
        var planets = 0
        val beacons = state.currentBeacon.sector.beacons
        for (beacon in beacons) {
            val paths = EnvironmentAssets.paths(state, beacon)
            check(paths == EnvironmentAssets.paths(state, beacon)) { "the list changes between calls" }
            for (path in paths) check(df.getOrNull(path) != null) { "beacon ${beacons.indexOf(beacon)} names missing $path" }
            // The nebula builds itself from the frame that creates it, so only the plain kinds are built here.
            if (beacon.environmentType != Beacon.EnvironmentType.NORMAL) continue
            val planet = beacon.getEnvironment(state).diagnosticPlanetImage
            if (beacon !== state.currentBeacon) beacon.clearEnvironment()
            if (planet != null) {
                check(planet.path in paths) { "the environment's planet ${planet.path} is not in $paths" }
                planets++
            }
        }
        check(planets > 0) { "no plain beacon had a planet to compare" }
        check(BrowserRandom.snapshot() == rng && NativeRandom.snapshot() == nativeRng) { "naming the pictures drew random numbers" }
        return "environment pictures (${beacons.size} beacons)"
    }

    /**
     * Starting a jump queues the target's pictures, a fraction of a second of frames loads them, and the arrival
     * finds them already in the cache. Loading them draws nothing from either random stream, and the jump itself
     * goes on as before.
     */
    private fun jumpPrefetch(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val target = state.currentBeacon.neighbours.first()
        val paths = EnvironmentAssets.paths(state, target)
        state.player.fuelCount = 5
        val content = state.content

        state.beginJumpOut(target)
        check(state.isPlayerJumping) { "no jump after beginJumpOut" }
        check(!content.prefetcher.isIdle || paths.all { content.hasImage(it) }) { "beginJumpOut queued nothing" }

        val rng = BrowserRandom.snapshot()
        val nativeRng = NativeRandom.snapshot()
        var updates = 0
        // Small steps keep the jump short of its end, while each update spends a few milliseconds on the queue.
        while (!content.prefetcher.isIdle) {
            main.update(container, 0.0005f)
            check(++updates < 5000) { "the pictures never finish loading" }
        }
        check(state.currentBeacon !== target) { "the jump had already finished" }
        for (path in paths) check(content.hasImage(path)) { "$path not loaded before the arrival" }
        for (path in EnvironmentAssets.warmPaths()) check(content.hasImage(path)) { "$path not warmed during the jump" }
        check(BrowserRandom.snapshot() == rng) { "loading the pictures drew from the combat stream" }
        check(NativeRandom.snapshot() == nativeRng) { "loading the pictures drew from the native stream" }
        return "jump prefetch (${paths.size} pictures in $updates updates)"
    }

    /**
     * The hangar's Advanced Edition toggle only flips the choice: the button state, the saved setting and the
     * hangar's own contents (28 ships with AE, 18 without) change at once, and the game content, which costs half a
     * second to build, is left alone until a run starts. Flipping back and forth reuses the loaded blueprints; a
     * run started with the content's own mode never rebuilds it, and one started with the other mode rebuilds it
     * to match.
     */
    private fun aeToggle(main: MainGame, container: GameContainer, graphics: Graphics): String {
        BrowserGameplayParityDiagnostics.fresh(main, container)
        val contentBefore = main.gameContent
        val mode = contentBefore.enableAdvancedEdition
        val savedPreference = main.profile.lastAdvancedEdition
        val savedGlobal = BrowserFiles.enableAE
        try {
            main.switchToShipSelect()
            val first = main.currentState as SelectShipState
            check(first.advancedEdition == savedPreference) { "the hangar opened in ${first.advancedEdition}, the saved choice is $savedPreference" }
            check(main.gameContent === contentBefore) { "opening the hangar rebuilt the content" }

            // Flip to the other mode: shown, saved and loaded in the hangar, not applied to the content.
            val other = !first.advancedEdition
            first.changeMode(other)
            val flipped = main.currentState as SelectShipState
            check(flipped !== first && flipped.advancedEdition == other) { "the toggle did not take" }
            check(main.profile.lastAdvancedEdition == other && BrowserXml.write(main.profile.save()).contains("hangarAE=\"$other\"")) {
                "the choice was not saved at once"
            }
            check(BrowserFiles.enableAE == other) { "the loader's mode is ${BrowserFiles.enableAE}, not $other" }
            check(main.gameContent === contentBefore && contentBefore.enableAdvancedEdition == mode) { "the toggle rebuilt the game content" }
            check(flipped.ships.size == if (other) 28 else 18) { "the hangar holds ${flipped.ships.size} ships with AE $other" }
            check((flipped.blueprints.blueprints["PLAYER_SHIP_HARD_3"] != null) == other) { "AE-only ship in the wrong mode" }
            main.render(container, graphics)

            // Back and forth: the loaded blueprints are reused, nothing is rebuilt.
            val blueprints = flipped.blueprints
            flipped.changeMode(!other)
            val back = main.currentState as SelectShipState
            check(back.advancedEdition == !other && back.ships.size == if (other) 18 else 28) { "toggling back" }
            back.changeMode(other)
            val again = main.currentState as SelectShipState
            check(again.blueprints === blueprints && again.advancedEdition == other) { "the blueprints were loaded again" }
            check(main.gameContent === contentBefore) { "toggling rebuilt the game content" }
            main.update(container, 0f)
            main.render(container, graphics)

            // Start with the content's own mode: no rebuild at all.
            again.changeMode(mode)
            (main.currentState as SelectShipState).startGame(Difficulty.NORMAL)
            val sameMode = main.currentState as InGameState
            check(sameMode.content === contentBefore && sameMode.content.enableAdvancedEdition == mode) { "a run in the content's mode rebuilt it" }

            // Start with the other mode: the content follows it now.
            main.switchToShipSelect()
            (main.currentState as SelectShipState).changeMode(other)
            check(main.gameContent === contentBefore) { "toggling before the start rebuilt the content" }
            (main.currentState as SelectShipState).startGame(Difficulty.NORMAL)
            val switched = main.currentState as InGameState
            check(switched.content !== contentBefore && switched.content.enableAdvancedEdition == other && BrowserFiles.enableAE == other) {
                "the run did not start in AE $other"
            }
            check((switched.content.blueprintManager.blueprints["PLAYER_SHIP_HARD_3"] != null) == other) { "the run's blueprints are not AE $other" }
            return "AE toggle deferred (hangar ${if (other) 28 else 18} ships, content rebuilt at the start)"
        } finally {
            main.changeHangarMode(mode, xyz.znix.xftl.game.ShipProgression.KESTREL, Difficulty.NORMAL)
            main.profile.lastAdvancedEdition = savedPreference
            BrowserFiles.enableAE = savedGlobal
            main.switchToMainMenu()
        }
    }
}
