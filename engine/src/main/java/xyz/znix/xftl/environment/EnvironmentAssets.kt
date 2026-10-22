package xyz.znix.xftl.environment

import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.sector.Beacon
import xyz.znix.xftl.sector.FleetBackground
import xyz.znix.xftl.sector.ImageList
import kotlin.random.Random

/**
 * The images a beacon's environment will draw on the first frame after the jump arrives, so the game can load them
 * while the ship is still jumping away (see [xyz.znix.xftl.game.ImagePrefetcher]).
 *
 * This only names files. It does not build the environment: the nebula's set-up, for one, depends on the frame that
 * creates it, so that stays where it was. The choice of backdrop and planet repeats the two draws that
 * [AbstractEnvironment] makes from the beacon's own seed, and cannot disagree with them except by loading a picture
 * too many or too few early; neither changes what is drawn.
 */
object EnvironmentAssets {
    private const val SKY = "img/stars/bg_dullstars.png"

    /** In the order they are needed: the full-screen backdrop first, as it takes the longest. */
    fun paths(game: InGameState, beacon: Beacon): List<String> {
        val out = LinkedHashSet<String>()
        val type = beacon.environmentType

        // AbstractEnvironment's picks. The backdrop is only drawn by the plain kinds; the others draw their own.
        var backgroundList: ImageList = game.eventManager.getImageList("BACKGROUND")
        var planetList: ImageList = game.eventManager.getImageList("PLANET")
        beacon.event.backImg?.let { backgroundList = it }
        beacon.event.planetImg?.let { planetList = it }
        val text = beacon.createdEvent.text
        text?.back?.let { backgroundList = game.eventManager.getImageList(it) }
        text?.planet?.let { planetList = game.eventManager.getImageList(it) }

        val rand = Random(beacon.environmentSeed)
        val background = backgroundList.getRandom(rand.nextInt())
        val planet = planetList.getRandom(rand.nextInt())

        if (usesPickedBackground(type)) background?.let { out += it.path }
        out += typeBackdrop(type)
        planet?.let { out += it.path }
        out += typeAssets(type)
        out += fleetAssets(beacon.event.fleetBackground, type == Beacon.EnvironmentType.PDS)
        // An ASB with no fleet swaps in one of the populated planets (AbstractEnvironment, from later draws).
        if (type == Beacon.EnvironmentType.PDS && beacon.event.fleetBackground == null)
            game.eventManager.getImageList("PLANET_POPULATED").images.forEach { out += it.path }
        return out.toList()
    }

    /**
     * Pictures a run is bound to want soon whatever the next beacon is, and that are big enough to be felt when they
     * load on the frame that first draws them: the store's two boards (about 180 KB each). They go after the
     * beacon's own pictures.
     */
    fun warmPaths(): List<String> = listOf("img/storeUI/store_buy_main.png", "img/storeUI/store_sell_main.png")

    /** The plain environments draw the picked backdrop; the others draw their own scenery. */
    fun usesPickedBackground(type: Beacon.EnvironmentType): Boolean =
        type == Beacon.EnvironmentType.NORMAL || type == Beacon.EnvironmentType.PDS

    /** The star field that the asteroid, sun and pulsar beacons draw in place of the picked backdrop. */
    fun typeBackdrop(type: Beacon.EnvironmentType): List<String> = when (type) {
        Beacon.EnvironmentType.ASTEROID, Beacon.EnvironmentType.SUN, Beacon.EnvironmentType.PULSAR -> listOf(SKY)
        else -> emptyList()
    }

    /** What each special environment loads when it is built. */
    fun typeAssets(type: Beacon.EnvironmentType): List<String> = when (type) {
        Beacon.EnvironmentType.ASTEROID -> (1..3).map { "img/asteroids/asteroid_back$it.png" }
        Beacon.EnvironmentType.SUN -> listOf(
            "img/stars/planet_sun1.png", "img/stars/planet_sun2.png", "img/stars/planet_sun_flare.png"
        )
        Beacon.EnvironmentType.PULSAR -> listOf(
            "img/effects/pulsar_black.png", "img/effects/pulsar_white.png", "img/effects/pulsar_backL.png",
            "img/effects/pulsar_frontL.png", "img/stars/planet_sun_flare.png"
        )
        Beacon.EnvironmentType.NEBULA, Beacon.EnvironmentType.ION_STORM -> listOf(
            "img/stars/nebula_large_c.png", "img/stars/nebula_large_b.png", "img/stars/nebula_large_a.png",
            "img/stars/lightning.png"
        )
        Beacon.EnvironmentType.NORMAL, Beacon.EnvironmentType.PDS -> emptyList()
    }

    /**
     * The background fleet picks its ships from four of each side by draws that come later, so take all four; the
     * flagship shows over it while the ASB is live.
     */
    fun fleetAssets(fleet: FleetBackground?, asb: Boolean): List<String> {
        val out = ArrayList<String>()
        if (fleet == FleetBackground.REBEL || fleet == FleetBackground.BOTH) out += fleetShips(false)
        if (fleet == FleetBackground.FEDERATION || fleet == FleetBackground.BOTH) out += fleetShips(true)
        if (asb || fleet != null) out += "img/ship/fleet/fleet_big.png"
        return out
    }

    private fun fleetShips(federation: Boolean): List<String> =
        listOf("1_med", "2_med", "1_small", "2_small").map { "img/ship/fleet/fleet${if (federation) "fed" else ""}_$it.png" }
}
