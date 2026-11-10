package xyz.znix.xftl.environment

import xyz.znix.xftl.hs.HS
import org.jdom2.Element
import xyz.znix.xftl.Constants
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

    /** SetPlanetaryDefense's first draw (TimerHelper::Start, `random32() % 5000` over 20000..25000) for an ASB. */
    protected var pdsTimerDraw = 0
    /** The planet's place, drawn size (the texture's, as SwitchPlanet) and turn, for the parity checks. */
    val diagnosticPlanet: List<Int>? get() = planetImage?.let { planet ->
        val img = planet.getImg(game)
        listOf(planetX, planetY, img.width, img.height, if (planetTurned) 1 else 0)
    }
    val diagnosticPlanetImage: EnvironmentImage? get() = planetImage
    /** Whether the scene shows the rebel or Federation fleet in the background, for the parity checks. */
    val diagnosticHasFleet: Boolean get() = backgroundShips.isNotEmpty()
    /** The planet's centre on screen, or null without a planet. */
    protected val planetCentre: ConstPoint? get() = diagnosticPlanet?.let { (x, y, w, h) -> ConstPoint(x + w / 2, y + h / 2) }
    protected val hasFleet: Boolean get() = backgroundShips.isNotEmpty()
    private val backgroundShips = ArrayList<BackgroundShip>()

    init {
        // WorldManager::CreateLocation stores the scene on the Location the first time the ship arrives (and the arrival
        // draws it, see [BeaconScene.create]); a scene built here instead (an old save, a test) draws now.
        val scene = beacon.scene ?: BeaconScene.create(game, beacon)

        backgroundImage = scene.backdrop?.let { EnvironmentImage(it) }
        planetImage = scene.planet?.let { EnvironmentImage(it, scene.planetW, scene.planetH) }
        planetX = scene.planetX
        planetY = scene.planetY
        planetTurned = scene.planetTurned

        // SetDangerZone's fleet (an event that shows a fleet the arrival did not, such as a wait at an overtaken beacon,
        // sets it up now).
        backgroundShips.clear()
        val fleet = BeaconScene.arrivalFleet(beacon)
        if (fleet != null) {
            if (scene.fleet == null || scene.fleetType != fleet) BeaconScene.drawFleet(scene, fleet)
            initFleet(scene)
        }

        // SpaceManager::SetPlanetaryDefense (0x418ca0): an ASB with no fleet behind it (SpaceManager +0xb4) swaps the
        // planet for one of PLANET_POPULATED, at its image size, placed at (600, 247) and turned (rand % 2) * 180
        // degrees; the ASB's shots leave from its centre. The pick is drawn once and kept with the scene.
        if (type == Beacon.EnvironmentType.PDS) pdsTimerDraw = ftl.browser.NativeRandom.next31() % 5000
        // Hyperspace <noASBPlanet> (also set by a firing custom fleet) leaves the planet alone.
        if (type == Beacon.EnvironmentType.PDS && backgroundShips.isEmpty() && !xyz.znix.xftl.hs.events.HsFlow.noAsbPlanet) {
            if (scene.pdsPlanet == null) BeaconScene.drawPopulated(game, scene)
            scene.pdsPlanet?.takeIf { it.isNotEmpty() }?.let { path ->
                planetImage = EnvironmentImage(path, scene.pdsW, scene.pdsH)
                planetX = 600
                planetY = 247
                planetTurned = scene.pdsTurned
            }
        }
    }

    /** SpaceManager's backdrop (+0xb8) as this scene leaves it: the dull star field after a pulsar, or a removeHazards. */
    val spaceBackdropId: String? get() =
        if (this is PulsarEnvironment || keptDull) BeaconScene.DULL_STARS else backgroundImage?.path

    /** SpaceManager's planet (+0xbc) as this scene leaves it. */
    val spacePlanetId: String? get() = planetImage?.path

    private var keptDull = false

    /**
     * SpaceManager::SetDangerZone (0x0041a560) fills nine slots, three per column: slot i sits at
     * (rand%1280/3 + (i/3)*1280/3, (i%3)*720/3 + rand%720/3) and shows one of the four rebel images (OnInit
     * 0x0041ac40's order: 1_med, 2_med, 1_small, 2_small) or of the four Federation ones. A mixed fleet is
     * rebel in the first column, Federation in the last and either (rand%2) in the middle. The draws come in
     * that order: x, y, image, then side. The beacon's own seed keeps the picture across a reload.
     */
    private fun initFleet(scene: BeaconScene) {
        val rebelShips = listOf("fleet_1_med", "fleet_2_med", "fleet_1_small", "fleet_2_small")
        val federationShips = listOf("fleetfed_1_med", "fleetfed_2_med", "fleetfed_1_small", "fleetfed_2_small")

        for (slot in scene.fleet ?: return) {
            val name = if (slot[2] >= 4) federationShips[slot[2] - 4] else rebelShips[slot[2]]
            backgroundShips.add(BackgroundShip(game.getImg("img/ship/fleet/$name.png"), slot[0], slot[1]))
        }
    }

    /** Diagnostics: the backdrop image drawn now. */
    val diagnosticBackgroundPath: String? get() = backgroundImage?.path

    private fun hsImageList(name: String): ImageList? = if (game.eventManager.hasImageList(name)) game.eventManager.getImageList(name) else null

    /**
     * Hyperspace `<changeBackground>` (CustomCreateLocation: SpaceManager::SwitchBackground): the backdrop becomes one
     * of the named image list's, drawn from the global stream; an unknown list leaves the backdrop.
     */
    fun hsChangeBackground(name: String) {
        // SpaceManager::SwitchBackground: picks again while the pick is the backdrop shown.
        switchBackground(name) { ftl.browser.NativeRandom.next31() }
    }

    /**
     * Hyperspace SpaceStatus (CustomSpaceStatus.cpp): the hazards SpaceManager has up, in g_hazards' order
     * (asteroids, sun, pulsar, ASB / allied ASB, nebula, storm), as [xyz.znix.xftl.hs.sector.HsSpaceStatus] ids.
     */
    open val hsHazards: List<Int> get() = when (type) {
        Beacon.EnvironmentType.ASTEROID -> listOf(xyz.znix.xftl.hs.sector.HsSpaceStatus.ASTEROIDS)
        Beacon.EnvironmentType.SUN -> listOf(xyz.znix.xftl.hs.sector.HsSpaceStatus.SUN)
        Beacon.EnvironmentType.PULSAR -> listOf(xyz.znix.xftl.hs.sector.HsSpaceStatus.PULSAR)
        // SpaceManager::bPDS: an enemy-only ASB switches itself off without a live enemy (0x41b920).
        Beacon.EnvironmentType.PDS -> if (!hsPdsOn) emptyList() else listOf(if ((this as? AsbEnvironment)?.pdsTarget == 1)
            xyz.znix.xftl.hs.sector.HsSpaceStatus.PDS_FRIENDLY else xyz.znix.xftl.hs.sector.HsSpaceStatus.PDS)
        Beacon.EnvironmentType.NEBULA -> listOf(xyz.znix.xftl.hs.sector.HsSpaceStatus.NEBULA)
        Beacon.EnvironmentType.ION_STORM -> listOf(xyz.znix.xftl.hs.sector.HsSpaceStatus.NEBULA, xyz.znix.xftl.hs.sector.HsSpaceStatus.STORM)
        else -> emptyList()
    }

    // ---- Hyperspace Lua: SpaceManager::currentBack / currentPlanet, SwitchBackground, UpdatePlanetImage ----

    /** SpaceManager::currentBack's texture: the backdrop image path under img/, null without one. */
    val hsBackdropPath: String? get() = backgroundImage?.path?.removePrefix("img/")
    /** SpaceManager::currentBack written by a script. */
    fun hsSetBackdrop(path: String?) { backgroundImage = path?.let { EnvironmentImage(if (it.startsWith("img/")) it else "img/$it") } }

    /** SpaceManager::currentPlanet: path under img/ (null: none), w, h, x, y and the rotation in degrees. */
    fun hsPlanetDesc(): Pair<String?, IntArray> {
        val planet = planetImage ?: return null to IntArray(5)
        val img = planet.getImg(game)
        return planet.path.removePrefix("img/") to intArrayOf(img.width, img.height,
            planetX, planetY, if (planetTurned) 180 else 0)
    }

    /**
     * SpaceManager::UpdatePlanetImage (1.6.9 0x416c30): the planet primitive is rebuilt from currentPlanet's texture
     * (none: no planet), position and rotation. Rotations other than 0 / 180 are drawn as the nearest of the two.
     */
    fun hsUpdatePlanet(path: String?, x: Int, y: Int, rot: Int) {
        if (path == null) { planetImage = null; return }
        val current = planetImage
        if (current == null || current.path != "img/$path") planetImage = EnvironmentImage("img/$path")
        planetX = x; planetY = y
        val r = ((rot % 360) + 360) % 360
        planetTurned = r in 90 until 270
    }

    /**
     * SpaceManager::SwitchBackground(name) (1.6.9 0x416e10): an image of the list is picked until it differs from the
     * backdrop shown (EventGenerator::GetImageFromList draws on the global stream for each pick), shown, and returned
     * as the ImageDesc's texture (w, h, x, y and rotation 0). An unknown list returns no texture and keeps the backdrop.
     */
    fun hsSwitchBackground(name: String): String? = switchBackground(name) { xyz.znix.xftl.hs.lua.LuaRng.rand32() }

    private fun switchBackground(name: String, draw: () -> Int): String? {
        val list = hsImageList(name) ?: return null
        val current = backgroundImage?.path
        var pick = list.getRandom(draw()) ?: return null
        // The original loops until the pick differs (forever on a one-image list holding the backdrop): capped here.
        var tries = 0
        while (pick.path == current && list.images.size > 1 && ++tries < 64) pick = list.getRandom(draw()) ?: return null
        backgroundImage = pick
        keptBackdrop = null
        keptDull = false
        return pick.path.removePrefix("img/")
    }

    /**
     * SpaceManager::SwitchPlanet (1.6.14 0x00418280, 1.6.9 0x00416860), reached from Lua (MV's custom star hazards call it
     * with "NONE"): "NONE" clears the planet without a draw; another name picks from that image list until the pick
     * differs from the current planet (native loops forever on a one-image list holding it; capped here), then places it
     * like the arrival pick (random32 % (1280 - w), random32 % (720 - h), turned random32 % 2: x, y, turn in that
     * order). The draws come from `random32` as a script's `math.random` does: the generation stream while a
     * generation-time callback runs, the presentation stream otherwise ([xyz.znix.xftl.hs.lua.LuaRng]). An unknown list
     * clears the planet (GetImageId fails). Returns the ImageDesc the native function returns: w, h, x, y and the
     * rotation in degrees (all 0 for a cleared planet).
     */
    fun hsSwitchPlanet(name: String): IntArray {
        if (name == "NONE") { planetImage = null; return IntArray(5) }
        val list = hsImageList(name)
        if (list == null) { planetImage = null; return IntArray(5) }
        val current = planetImage
        var pick = list.getRandom(xyz.znix.xftl.hs.lua.LuaRng.rand32())
        var tries = 0
        while (pick != null && current != null && pick.path == current.path && ++tries < 64)
            pick = list.getRandom(xyz.znix.xftl.hs.lua.LuaRng.rand32())
        planetImage = pick ?: return IntArray(5)
        val img = pick.getImg(game)
        val w = img.width
        val h = img.height
        planetX = xyz.znix.xftl.hs.lua.LuaRng.rand32() % maxOf(1, 1280 - w)
        planetY = xyz.znix.xftl.hs.lua.LuaRng.rand32() % maxOf(1, 720 - h)
        planetTurned = xyz.znix.xftl.hs.lua.LuaRng.rand32() % 2 == 1
        return intArrayOf(w, h, planetX, planetY, if (planetTurned) 180 else 0)
    }

    /**
     * Whether SpaceManager's planet flag (bPDS, +0x298) is set: only a live ASB sets it. [hsRemoveHazardsFrom] reads it
     * from the scene that is left.
     */
    open val hsPdsOn: Boolean get() = false

    /** The backdrop this scene shows (the hazard classes draw their own: [hsRemoveHazardsFrom] keeps what was drawn). */
    protected open fun sceneBackdrop(): Image? = backgroundImage?.getImg(game)

    /** A backdrop taken over from the scene a removeHazards / removeNebula left ([hsKeepScene]). */
    private var keptBackdrop: Image? = null

    /**
     * Hyperspace removeHazards / removeNebula do not rebuild the scene: SpaceManager keeps its backdrop, planet and
     * fleet, and only the hazard flags go out. [old] is the environment the beacon had; this one (built for the
     * beacon's environment now) takes its backdrop, planet and fleet.
     */
    private fun hsKeepScene(old: AbstractEnvironment) {
        keptBackdrop = old.sceneBackdrop()
        keptDull = old.keptDull
        // SpaceManager's planet (+0xbc) stays whatever hides it (the sun, a pulsar, a nebula: OnRenderForeground
        // 0x419e30 only skips drawing it), so it shows again once they are gone.
        planetImage = old.planetImage; planetX = old.planetX; planetY = old.planetY; planetTurned = old.planetTurned
        backgroundShips.clear()
        backgroundShips.addAll(old.backgroundShips)
    }

    /**
     * The SpaceManager part of Hyperspace's `<removeHazards/>` (CustomEvents.cpp UpdateLocation, 1b05a07): the
     * asteroid generator stops, SetPulsarLevel(false), SetPlanetaryDefense(false, 0) and SetFireLevel(false), clouds
     * and storm off, and SetNebula(true) again when the scene is a nebula. The planet and fleet are kept
     * ([hsKeepScene]); the backdrop becomes the dull starfield (SetPulsarLevel). Each
     * of the three setters starts a TimerHelper (`Start(-1)` draws `random32() % (max - min)`), and
     * SetPlanetaryDefense draws the 0.1 s pulse offset (`random32() % 200`) last; with no ASB up and no fleet behind
     * it (bPDS and +0xb4 both clear) it also swaps in one of PLANET_POPULATED at (600, 247), turned `random32() % 2`,
     * whatever its argument (1.6.9 0x417170, 1.6.14 0x418ca0). All on the global stream, in HS's order.
     */
    fun hsRemoveHazardsFrom(old: AbstractEnvironment) {
        hsKeepScene(old)
        // SetPulsarLevel (1.6.9 0x417440) also sets the backdrop (+0xb8) to stars/bg_dullstars.png, whatever its argument:
        // the oracle's frame after a removeHazards on a plain beacon shows the dull starfield and the planet swap below.
        keptBackdrop = game.getImg("img/stars/bg_dullstars.png")
        keptDull = true
        val rand = ftl.browser.NativeRandom
        rand.next31() // SetPulsarLevel(false): 11000..18000
        rand.next31() // SetPlanetaryDefense(false, 0): 20000..25000
        if (!old.hsPdsOn && backgroundShips.isEmpty()) {
            game.eventManager.getImageList("PLANET_POPULATED").getRandom(rand.next31())?.let { planet ->
                planetImage = planet
                planetX = 600
                planetY = 247
                planetTurned = rand.next31() % 2 == 1
            }
        }
        rand.next31() // SetPlanetaryDefense: random32() % 200
        rand.next31() // SetFireLevel(false): 28000..34000
        // if (space.bNebula) space.SetNebula(true)
        (this as? NebulaEnvironment)?.setNebula()
    }

    /** `<removeNebula/>`: bNebula and bStorm off, the clouds cleared, nothing else touched; no draws. */
    fun hsRemoveNebulaFrom(old: AbstractEnvironment) {
        hsKeepScene(old)
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
        if (game.isShadePaused)
            return Colour(lost * 0.4f + 0.4f, 0.4f, 0.4f)

        val gb = 1.0f - lost * lost * 0.5f
        return Colour(1.0f, gb, gb)
    }

    /**
     * Hyperspace render events split this into SpaceManager's three layers (hs.render.HsRenderEvents): 0 draws
     * everything (vanilla), 1 only OnRenderBackground (the backdrop), 2 only OnRenderForeground (planet, sun, fleet),
     * 3 only OnRenderAsteroids.
     */
    @JvmField var hsPhase = 0
    protected val hsBackdrop: Boolean get() = hsPhase == 0 || hsPhase == 1
    protected val hsForeground: Boolean get() = hsPhase == 0 || hsPhase == 2
    protected val hsAsteroids: Boolean get() = hsPhase == 0 || hsPhase == 3

    /**
     * SpaceManager::OnRenderBackground (1.6.14 0x4194c0) draws the backdrop (+0xb8) under every hazard but the nebula:
     * only SetPulsarLevel (0x418f70) changes it (to the dull star field); the sun (SetFireLevel 0x418ae0) and the
     * asteroid field (StartAsteroids 0x418a40) keep the beacon's. OnRenderForeground (0x419e30) then draws the planet,
     * or the sun or the pulsar in its place ([renderForegroundArt]), and the background fleet in every case.
     */
    open fun renderBackground(gc: GameContainer, g: Graphics) {
        // Here the background scenery is rendered with a
        // pause-darkness that depends on the hull health.
        val ps = pauseShade(game)
        if (hsBackdrop) (keptBackdrop ?: backgroundImage?.getImg(game))?.let { it.draw(0f, 0f, ps); browserDrawLog?.add("back" to it) }
        if (!hsForeground) return
        renderForegroundArt(g, ps)
        renderFleet(g, ps)
    }

    /** OnRenderForeground's first part: SpaceManager's planet (the sun overrides it with its own art). */
    protected open fun renderForegroundArt(g: Graphics, ps: Colour) {
        planetImage?.let { planet ->
            val img = planet.getImg(game)
            val w = img.width.toFloat()
            val h = img.height.toFloat()
            g.pushTransform()
            game.applyJumpScene(g)
            if (planetTurned)
                g.rotate(planetX + w / 2f, planetY + h / 2f, 180f)
            img.draw(planetX.toFloat(), planetY.toFloat(), w, h, ps)
            g.popTransform()
            browserDrawLog?.add("planet" to img)
        }
    }

    /** OnRenderForeground's last part, under every hazard but the nebula. */
    protected fun renderFleet(g: Graphics, ps: Colour) {
        if (backgroundShips.isNotEmpty()) browserDrawLog?.add("fleet" to backgroundShips.size)
        // SpaceManager's fleet render (0x00419ca0): each ship 150 px up and left of its slot, under the same
        // tint as the background; the flagship at (550, 447), untinted, while the ASB is live.
        g.pushTransform()
        game.applyJumpScene(g)
        // Hyperspace: an event's custom fleet replaces the vanilla one (CustomFleetShips.cpp OnRenderFleet).
        if (!(HS.active && xyz.znix.xftl.hs.events.HsCustomFleet.render(game, g, ps, game.isPaused, showsFleetFlagship))) {
            for (ship in backgroundShips)
                ship.image.draw(ship.x - 150f, ship.y - 150f, ps)
            if (backgroundShips.isNotEmpty() && showsFleetFlagship)
                game.getImg("img/ship/fleet/fleet_big.png").draw(550f, 447f)
        }
        g.popTransform()
    }

    /**
     * SpaceManager::GetFlashOpacity (0x417860) before Hyperspace's hook: the alpha of the solar flare or pulsar pulse
     * on screen, 0 outside one.
     */
    protected open fun nativeFlashOpacity(): Float = 0f

    /** The colour CommandGui::RenderStatic draws the flash in: the sun's, or the pulsar's. */
    protected open val flashFilter: Colour get() = Constants.SOLAR_FLARE_FILTER

    /**
     * Render something on top of everything else, including the UI: CommandGui::RenderStatic's flash. It calls
     * GetFlashOpacity, and when that is above zero scales the flare image 4x at (-360, -640) and calls it again for the
     * alpha. Hyperspace hooks GetFlashOpacity (GET_HAZARD_FLASH): a callback that returns four values replaces the
     * alpha with its alpha and the flare colour with its red, green and blue (InternalEvents.cpp).
     */
    open fun renderOverlay(gc: GameContainer, g: Graphics) {
        var opacity = nativeFlashOpacity()
        if (HS.luaActive) opacity = xyz.znix.xftl.hs.lua.LuaAdaptersWP1.flashOpacity(opacity)
        if (opacity <= 0f)
            return
        var colour: Colour? = null
        if (HS.luaActive) {
            opacity = xyz.znix.xftl.hs.lua.LuaAdaptersWP1.flashOpacity(nativeFlashOpacity())
            colour = xyz.znix.xftl.hs.lua.LuaAdaptersWP1.flashColour
        }
        val filter = Colour(colour ?: flashFilter)
        filter.a = opacity
        val flare = game.getImg("img/stars/planet_sun_flare.png")
        flare.draw(
            -360f, -640f,
            flare.width * 4f, flare.height * 4f,
            filter
        )
    }

    open fun update(dt: Float) {
    }

    // ---- Hyperspace: hazards stacked on the scene ----

    /**
     * Hyperspace (WorldManager::ModifyEnvironment, 1b05a07 / 1.6.9): a later event at the beacon adds its hazard to the
     * ones SpaceManager has up (an ASB and then asteroids: both run) instead of replacing them. The scene (backdrop,
     * planet, fleet) stays this one's; each stacked environment runs its own hazard and draws its own layer.
     */
    val stacked = ArrayList<AbstractEnvironment>()

    /** This scene's environment followed by the stacked ones. */
    val layers: List<AbstractEnvironment> get() = if (stacked.isEmpty()) listOf(this) else listOf(this) + stacked

    /**
     * Add [env] to the stack (not of the scene's own family, see [Beacon.addEnvironment]): an environment of the same
     * family (the nebula and the ion storm, which has a nebula) as a stacked one takes its place, otherwise it comes on top.
     */
    fun stack(env: AbstractEnvironment) {
        fun family(e: AbstractEnvironment) = if (e.type.isNebula) Beacon.EnvironmentType.NEBULA else e.type
        val f = family(env)
        // Of the hazards only SetPulsarLevel (1.6.14 0x418f70) puts the dull starfield behind the scene; the asteroid
        // field and the sun keep the backdrop
        if (env.type == Beacon.EnvironmentType.PULSAR) {
            keptBackdrop = env.sceneBackdrop()
            keptDull = true
        }
        val i = stacked.indexOfFirst { family(it) == f }
        if (i >= 0) stacked[i] = env else stacked += env
    }

    /** The update of the whole stack. */
    fun updateStack(dt: Float) {
        update(dt)
        for (e in stacked) e.update(dt)
    }

    /** The overlays (a solar flare, a pulsar pulse) of the whole stack. */
    fun overlayStack(gc: GameContainer, g: Graphics) {
        renderOverlay(gc, g)
        for (e in stacked) e.renderOverlay(gc, g)
    }

    fun adjustPowerStack(ship: Ship, powerAvailableTypes: HashMap<EnergySource, Int>) {
        adjustShipPower(ship, powerAvailableTypes)
        for (e in stacked) e.adjustShipPower(ship, powerAvailableTypes)
    }

    /**
     * SpaceManager::OnRenderAsteroids(1) (1.6.14 0x41a770: layers 3 - n .. 2, so only the front one), which
     * CommandGui::RenderStatic (0x535070) draws over the player's ship while the jump zoom runs; nothing outside an
     * asteroid field.
     */
    open fun renderFrontLayer(gc: GameContainer, g: Graphics) {}

    /** [renderFrontLayer] for this scene and the hazards stacked on it. */
    fun renderFrontStack(gc: GameContainer, g: Graphics) {
        for (env in layers) env.renderFrontLayer(gc, g)
    }

    /**
     * [renderBackground] with the stacked layers: with [hsPhase] set (or 0, all three in turn) the scene draws its own
     * part, then each stacked hazard draws its own layer: nebula clouds with the backdrop, the sun, pulsar or ASB with the
     * foreground, asteroids with the asteroid layer.
     */
    fun renderStack(gc: GameContainer, g: Graphics) {
        if (stacked.isEmpty()) { renderBackground(gc, g); return }
        if (hsPhase == 0) {
            try {
                for (ph in 1..3) {
                    hsPhase = ph
                    // the asteroid pass at the jump's asteroid scale (AsteroidEnvironment.renderBackground)
                    if (ph == 3) { g.pushTransform(); game.applyJumpAsteroids(g) }
                    try { renderStack(gc, g) } finally { if (ph == 3) g.popTransform() }
                }
            } finally { hsPhase = 0 }
            return
        }
        // SpaceManager::OnRenderBackground / OnRenderForeground (1.6.14 0x4194c0 / 0x419e30): while a nebula is up only
        // its clouds are drawn, no backdrop, planet, sun, pulsar or fleet; the asteroid layer stays.
        val nebulaUp = layers.any { it.type.isNebula }
        val ph = hsPhase
        if (!nebulaUp || type.isNebula || ph == 3) renderBackground(gc, g)
        for (e in stacked) {
            val mine = when (e.type) {
                Beacon.EnvironmentType.NEBULA, Beacon.EnvironmentType.ION_STORM -> 1
                Beacon.EnvironmentType.ASTEROID -> 3
                else -> 2
            }
            if (mine != ph) continue
            if (nebulaUp && !e.type.isNebula && ph != 3) continue
            e.hsPhase = ph
            try { e.renderBackground(gc, g) } finally { e.hsPhase = 0 }
        }
    }

    /** SpaceStatus hazards of the whole stack, in g_hazards' order. */
    val hsStackHazards: List<Int> get() = if (stacked.isEmpty()) hsHazards else layers.flatMap { it.hsHazards }.distinct().sortedBy {
        when (it) { xyz.znix.xftl.hs.sector.HsSpaceStatus.ASTEROIDS -> 0; xyz.znix.xftl.hs.sector.HsSpaceStatus.SUN -> 1
            xyz.znix.xftl.hs.sector.HsSpaceStatus.PULSAR -> 2; xyz.znix.xftl.hs.sector.HsSpaceStatus.NEBULA -> 4
            xyz.znix.xftl.hs.sector.HsSpaceStatus.STORM -> 5; else -> 3 }
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

    companion object {
        /** Diagnostics (GameplayParityPsW5Checks): while set, what the scene renders draw: backdrop, planet, sun, fleet... */
        @JvmField var browserDrawLog: MutableList<Pair<String, Any?>>? = null
    }
}
