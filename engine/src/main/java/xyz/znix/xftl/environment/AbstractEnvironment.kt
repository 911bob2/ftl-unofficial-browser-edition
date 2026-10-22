package xyz.znix.xftl.environment

import org.jdom2.Element
import xyz.znix.xftl.Ship
import xyz.znix.xftl.game.EnergySource
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.Image
import xyz.znix.xftl.sector.Beacon
import xyz.znix.xftl.sector.EnvironmentImage
import xyz.znix.xftl.sector.FleetBackground
import xyz.znix.xftl.sector.ImageList
import xyz.znix.xftl.sys.GameContainer
import kotlin.random.Random

abstract class AbstractEnvironment(val game: InGameState, val beacon: Beacon) {
    abstract val type: Beacon.EnvironmentType

    /**
     * Whether this environment stops the player from jumping at will
     * (SpaceManager::DangerousEnvironment, 0x4191c0).
     */
    open val isDangerous: Boolean get() = type.isDangerous

    private var backgroundImage: EnvironmentImage? = null
    private var planetImage: EnvironmentImage? = null
    private var planetX = 0
    private var planetY = 0
    private var planetTurned = false
    /** The planet's list size, place and turn, for the parity checks. */
    val diagnosticPlanet: List<Int>? get() = planetImage?.let { planet ->
        val img = planet.getImg(game)
        listOf(planetX, planetY, planet.width ?: img.width, planet.height ?: img.height, if (planetTurned) 1 else 0)
    }
    val diagnosticPlanetImage: EnvironmentImage? get() = planetImage
    /** Whether the scene shows the rebel or Federation fleet in the background, for the parity checks. */
    val diagnosticHasFleet: Boolean get() = backgroundShips.isNotEmpty()
    /** The planet's centre on screen, or null without a planet. */
    protected val planetCentre: ConstPoint? get() = diagnosticPlanet?.let { (x, y, w, h) -> ConstPoint(x + w / 2, y + h / 2) }
    protected val hasFleet: Boolean get() = backgroundShips.isNotEmpty()
    private val backgroundShips = ArrayList<BackgroundShip>()

    init {
        var backgroundList: ImageList = game.eventManager.getImageList("BACKGROUND")
        var planetList: ImageList = game.eventManager.getImageList("PLANET")

        beacon.event.backImg?.let { backgroundList = it }
        beacon.event.planetImg?.let { planetList = it }

        // A picked text with its own planet or back image list overrides the event's.
        val text = beacon.createdEvent.text
        text?.back?.let { backgroundList = game.eventManager.getImageList(it) }
        text?.planet?.let { planetList = game.eventManager.getImageList(it) }

        val rand = Random(beacon.environmentSeed)

        backgroundImage = backgroundList.getRandom(rand.nextInt())
        planetImage = planetList.getRandom(rand.nextInt())

        // SpaceManager::SwitchPlanet (0x00418280): the planet, at its list size, goes to (rand % (1280 - w),
        // rand % (720 - h)), turned (rand % 2) * 180 degrees.
        planetImage?.let { planet ->
            val img = planet.getImg(game)
            val w = planet.width ?: img.width
            val h = planet.height ?: img.height
            planetX = rand.nextInt(Int.MAX_VALUE) % maxOf(1, 1280 - w)
            planetY = rand.nextInt(Int.MAX_VALUE) % maxOf(1, 720 - h)
            planetTurned = rand.nextInt(Int.MAX_VALUE) % 2 == 1
        }

        backgroundShips.clear()
        val fleet = beacon.event.fleetBackground
        if (fleet != null) {
            initFleet(fleet, rand)
        }

        // SpaceManager::SetPlanetaryDefense (0x418ca0): an ASB with no fleet behind it (SpaceManager +0xb4) swaps the
        // planet for one of PLANET_POPULATED, at its image size, placed at (600, 247) and turned (rand % 2) * 180
        // degrees; the ASB's shots leave from its centre. The beacon's own seed keeps the picture across a reload.
        if (type == Beacon.EnvironmentType.PDS && backgroundShips.isEmpty()) {
            game.eventManager.getImageList("PLANET_POPULATED").getRandom(rand.nextInt())?.let { planet ->
                planetImage = planet
                planetX = 600
                planetY = 247
                planetTurned = rand.nextInt(Int.MAX_VALUE) % 2 == 1
            }
        }
    }

    /**
     * SpaceManager::SetDangerZone (0x0041a560) fills nine slots, three per column: slot i sits at
     * (rand%1280/3 + (i/3)*1280/3, (i%3)*720/3 + rand%720/3) and shows one of the four rebel images (OnInit
     * 0x0041ac40's order: 1_med, 2_med, 1_small, 2_small) or of the four Federation ones. A mixed fleet is
     * rebel in the first column, Federation in the last and either (rand%2) in the middle. The draws come in
     * that order: x, y, image, then side. The beacon's own seed keeps the picture across a reload.
     */
    private fun initFleet(fleet: FleetBackground, rand: Random) {
        val rebelShips = listOf("fleet_1_med", "fleet_2_med", "fleet_1_small", "fleet_2_small")
        val federationShips = listOf("fleetfed_1_med", "fleetfed_2_med", "fleetfed_1_small", "fleetfed_2_small")

        for (i in 0 until 9) {
            val x = rand.nextInt(Int.MAX_VALUE) % 1280 / 3 + i / 3 * 1280 / 3
            val y = i % 3 * 720 / 3 + rand.nextInt(Int.MAX_VALUE) % 720 / 3
            val image = rand.nextInt(Int.MAX_VALUE) % 4
            val fed = when (fleet) {
                FleetBackground.REBEL -> false
                FleetBackground.FEDERATION -> true
                FleetBackground.BOTH -> i > 5 || (i > 2 && rand.nextInt(Int.MAX_VALUE) % 2 == 1)
            }
            val name = (if (fed) federationShips else rebelShips)[image]
            backgroundShips.add(BackgroundShip(game.getImg("img/ship/fleet/$name.png"), x, y))
        }
    }

    /**
     * SpaceManager's PDS flag (+0x298): the fleet's flagship, fleet_big, shows over the fleet while the beacon's
     * ASB is live.
     */
    protected open val showsFleetFlagship: Boolean get() = false

    /**
     * SpaceManager's background tint (0x004194c0, also the planet 0x00419440 and asteroids 0x0041a770), from
     * the hull fraction h and the pause flag CommandGui::RenderStatic passes in: running it reddens as the hull
     * drops, (1, 1 - 0.5(1-h)^2, 1 - 0.5(1-h)^2); paused it is (0.4 + 0.4(1-h), 0.4, 0.4).
     */
    open fun pauseShade(game: InGameState): Colour {
        val s = game.player // s for ship
        val lost = 1.0f - s.health.toFloat() / s.maxHealth.toFloat()
        if (game.isPaused)
            return Colour(lost * 0.4f + 0.4f, 0.4f, 0.4f)

        val gb = 1.0f - lost * lost * 0.5f
        return Colour(1.0f, gb, gb)
    }

    open fun renderBackground(gc: GameContainer, g: Graphics) {
        // Here the background scenery is rendered with a
        // pause-darkness that depends on the hull health.
        val ps = pauseShade(game)
        backgroundImage?.getImg(game)?.draw(0f, 0f, ps)
        planetImage?.let { planet ->
            val img = planet.getImg(game)
            val w = (planet.width ?: img.width).toFloat()
            val h = (planet.height ?: img.height).toFloat()
            g.pushTransform()
            game.applyJumpScene(g)
            if (planetTurned)
                g.rotate(planetX + w / 2f, planetY + h / 2f, 180f)
            img.draw(planetX.toFloat(), planetY.toFloat(), w, h, ps)
            g.popTransform()
        }

        // SpaceManager's fleet render (0x00419ca0): each ship 150 px up and left of its slot, under the same
        // tint as the background; the flagship at (550, 447), untinted, while the ASB is live.
        g.pushTransform()
        game.applyJumpScene(g)
        for (ship in backgroundShips)
            ship.image.draw(ship.x - 150f, ship.y - 150f, ps)
        if (backgroundShips.isNotEmpty() && showsFleetFlagship)
            game.getImg("img/ship/fleet/fleet_big.png").draw(550f, 447f)
        g.popTransform()
    }

    /**
     * Render something on top of everything else, including the UI.
     *
     * Used for solar flares and pulsars.
     */
    open fun renderOverlay(gc: GameContainer, g: Graphics) {
    }

    open fun update(dt: Float) {
    }

    /**
     * Adjust a ship's available power, before systems use it.
     *
     * This is how plasma storms reduce a ship's power.
     */
    open fun adjustShipPower(ship: Ship, powerAvailableTypes: HashMap<EnergySource, Int>) {
    }

    open fun saveToXML(elem: Element) {
    }

    open fun loadFromXML(elem: Element) {
    }

    private class BackgroundShip(val image: Image, val x: Int, val y: Int)
}
