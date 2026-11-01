package xyz.znix.xftl.environment

import ftl.browser.NativeRandom
import org.jdom2.Element
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.sector.Beacon
import xyz.znix.xftl.sector.EnvironmentImage
import xyz.znix.xftl.sector.FleetBackground
import xyz.znix.xftl.sector.ImageList

/**
 * What WorldManager::CreateLocation stores on a Location the first time the ship arrives (1.6.14 0x6183a0): the
 * backdrop (SpaceManager::SwitchBackground, 0x4188d0), the planet (SwitchPlanet, 0x418280) with its place and turn, and
 * the background fleet's nine slots (SetDangerZone, 0x41a560). A revisit applies these without drawing; the first
 * visit draws them from the game's one stream, in that order, ahead of the arrival ship (which reseeds it).
 *
 * The Location keeps the picks across saves, so [save] and [load] give them a place in the browser's save format.
 */
class BeaconScene {
    /** The backdrop's image list (Location::spaceImage, Hyperspace Lua). */
    @JvmField var backList: String = "BACKGROUND"
    @JvmField var backdrop: String? = null
    @JvmField var planet: String? = null
    @JvmField var planetW = 0
    @JvmField var planetH = 0
    @JvmField var planetX = 0
    @JvmField var planetY = 0
    @JvmField var planetTurned = false

    /** SetDangerZone's fleet: x, y and the ship sheet (0-3 rebel, 4-7 Federation) of each of the nine slots. */
    @JvmField var fleetType: FleetBackground? = null
    @JvmField var fleet: List<IntArray>? = null

    /** SetPlanetaryDefense's swap to one of PLANET_POPULATED, drawn once when an ASB first comes up with no fleet. */
    @JvmField var pdsPlanet: String? = null
    @JvmField var pdsW = 0
    @JvmField var pdsH = 0
    @JvmField var pdsTurned = false

    fun save(): Element {
        val e = Element("scene")
        e.setAttribute("backList", backList)
        backdrop?.let { e.setAttribute("back", it) }
        planet?.let {
            e.setAttribute("planet", it)
            e.setAttribute("planetBox", "$planetW,$planetH,$planetX,$planetY,${if (planetTurned) 1 else 0}")
        }
        fleetType?.let { e.setAttribute("fleetType", it.name) }
        fleet?.let { f -> e.setAttribute("fleet", f.joinToString(";") { it.joinToString(",") }) }
        pdsPlanet?.let {
            e.setAttribute("pds", it)
            e.setAttribute("pdsBox", "$pdsW,$pdsH,${if (pdsTurned) 1 else 0}")
        }
        return e
    }

    companion object {
        private const val SKY = "img/stars/bg_dullstars.png"

        fun load(e: Element): BeaconScene {
            val s = BeaconScene()
            s.backList = e.getAttributeValue("backList") ?: "BACKGROUND"
            s.backdrop = e.getAttributeValue("back")
            s.planet = e.getAttributeValue("planet")
            e.getAttributeValue("planetBox")?.split(',')?.map { it.toInt() }?.let {
                s.planetW = it[0]; s.planetH = it[1]; s.planetX = it[2]; s.planetY = it[3]; s.planetTurned = it[4] == 1
            }
            s.fleetType = e.getAttributeValue("fleetType")?.let { n -> FleetBackground.values().firstOrNull { it.name == n } }
            s.fleet = e.getAttributeValue("fleet")?.split(';')?.map { slot -> slot.split(',').map { it.toInt() }.toIntArray() }
            s.pdsPlanet = e.getAttributeValue("pds")
            e.getAttributeValue("pdsBox")?.split(',')?.map { it.toInt() }?.let { s.pdsW = it[0]; s.pdsH = it[1]; s.pdsTurned = it[2] == 1 }
            return s
        }

        /** The dull star field SetPulsarLevel puts behind a pulsar: SpaceManager's backdrop then. */
        const val DULL_STARS = SKY

        /**
         * GetImageFromList (1.6.14 0x5d9f20): a list the generator knows gives `random32() % size`; the unknown list
         * ("NONE" and so on) gives no image and no draw.
         */
        private fun pickFrom(list: ImageList): EnvironmentImage? {
            if (list === ImageList.NONE || list.images.isEmpty())
                return null
            return list.images[NativeRandom.next31() % list.images.size]
        }

        /** The backdrop and planet image lists an arrival at [beacon] uses (the event's, then its text's, then a script's). */
        fun lists(game: InGameState, beacon: Beacon): Pair<ImageList, ImageList> {
            var backgroundList: ImageList = game.eventManager.getImageList("BACKGROUND")
            var planetList: ImageList = game.eventManager.getImageList("PLANET")
            beacon.event.backImg?.let { backgroundList = it }
            beacon.event.planetImg?.let { planetList = it }
            // A picked text with its own planet or back image list overrides the event's.
            val text = beacon.createdEvent.text
            text?.back?.let { name -> game.eventManager.getImageListOrNull(name)?.let { backgroundList = it } }
            text?.planet?.let { name -> game.eventManager.getImageListOrNull(name)?.let { planetList = it } }
            // Hyperspace: a changeBackground image list stays the beacon's (Location::spaceImage) across a reload.
            beacon.hsSpaceImage?.let { name ->
                if (game.eventManager.hasImageList(name)) backgroundList = game.eventManager.getImageList(name)
            }
            return backgroundList to planetList
        }

        /** The backdrop and planet image lists of [event] alone (the flagship's arrival event). */
        private fun eventLists(game: InGameState, event: xyz.znix.xftl.sector.Event): Pair<ImageList, ImageList> =
            (event.backImg ?: game.eventManager.getImageList("BACKGROUND")) to (event.planetImg ?: game.eventManager.getImageList("PLANET"))

        /** SwitchBackground: pick until it differs from the backdrop SpaceManager shows; an unknown list draws nothing. */
        private fun pickBackdrop(map: xyz.znix.xftl.sector.GameMap, scene: BeaconScene, list: ImageList) {
            // the loop is capped here, native spins on a one-image list that holds the backdrop
            var pick = pickFrom(list)
            var tries = 0
            while (pick != null && pick.path == map.spaceBack && ++tries < 64) pick = pickFrom(list)
            scene.backdrop = pick?.path
            if (pick != null) map.spaceBack = pick.path
        }

        /** SwitchPlanet: "NONE" clears the planet with no draw; otherwise pick until it differs, then x, y and the turn. */
        private fun pickPlanet(game: InGameState, map: xyz.znix.xftl.sector.GameMap, scene: BeaconScene, list: ImageList) {
            if (list === ImageList.NONE || list.name == "NONE") {
                map.spacePlanet = null
                return
            }
            var planet = pickFrom(list)
            var tries = 0
            while (planet != null && planet.path == map.spacePlanet && ++tries < 64) planet = pickFrom(list)
            if (planet == null) {
                map.spacePlanet = null
                return
            }
            val img = planet.getImg(game)
            val w = planet.width ?: img.width
            val h = planet.height ?: img.height
            scene.planet = planet.path
            scene.planetW = w
            scene.planetH = h
            scene.planetX = NativeRandom.next31() % maxOf(1, 1280 - w)
            scene.planetY = NativeRandom.next31() % maxOf(1, 720 - h)
            scene.planetTurned = NativeRandom.next31() % 2 == 1
            map.spacePlanet = planet.path
        }

        /**
         * CreateLocation's first part for a Location with no stored scene: SwitchBackground, SwitchPlanet, then
         * SetDangerZone with the event's fleet. The scene is kept on the [beacon] and SpaceManager's current backdrop
         * and planet ([xyz.znix.xftl.sector.GameMap.spaceBack] / `spacePlanet`) follow.
         */
        /** The flagship when it waits at [beacon]. */
        fun flagshipAt(beacon: Beacon): xyz.znix.xftl.game.FlagshipBoss? =
            if (!beacon.hasSector) null else beacon.sector.bosses.firstOrNull { it is xyz.znix.xftl.game.FlagshipBoss && it.beacon === beacon } as xyz.znix.xftl.game.FlagshipBoss?

        /**
         * The fleet SetDangerZone shows for the arrival event: where the flagship waits, BossShip::GetEvent makes its
         * event a rebel fleet location (the native flagship arrival draws the rebel fleet behind it), otherwise the
         * beacon event's `<fleet>`.
         */
        @JvmStatic
        fun arrivalFleet(beacon: Beacon): FleetBackground? =
            if (flagshipAt(beacon) != null) FleetBackground.REBEL else beacon.event.fleetBackground

        @JvmStatic
        fun create(game: InGameState, beacon: Beacon): BeaconScene {
            val map = game.gameMap
            // Where the flagship waits, the scene is the flagship's arrival event's (BOSS_TEXT_<stage>), not the beacon's.
            val bossEvent = flagshipAt(beacon)?.let { game.eventManager.getOrNull("BOSS_TEXT_" + it.stage)?.resolve() }
            val (backgroundList, planetList) = if (bossEvent != null) eventLists(game, bossEvent) else lists(game, beacon)
            val scene = BeaconScene()
            scene.backList = backgroundList.name

            // SwitchBackground (0x4188d0): pick until it differs from the backdrop SpaceManager shows. An unknown list
            // gives no image (nothing is drawn, the current backdrop stays); the loop is capped here, native spins on
            // a one-image list that holds the backdrop.
            pickBackdrop(map, scene, backgroundList)

            // SwitchPlanet (0x418280): "NONE" clears the planet with no draw; otherwise pick until it differs from the
            // planet shown, then x, y and the turn.
            pickPlanet(game, map, scene, planetList)

            arrivalFleet(beacon)?.let { drawFleet(scene, it) }

            // Hyperspace (WorldManager::CreateLocation hook): a first arrival where the flagship already reached the
            // base switches to the base event's own backdrop and planet afterwards.
            if (bossEvent != null && xyz.znix.xftl.hs.HS.active && (beacon.isBase || beacon.isExit)) {
                val (baseBack, basePlanet) = lists(game, beacon)
                scene.backList = baseBack.name
                pickBackdrop(map, scene, baseBack)
                pickPlanet(game, map, scene, basePlanet)
            }
            beacon.scene = scene
            beacon.hsSceneFrom(game, scene)
            return scene
        }

        /**
         * WorldManager::CreateLocation's scene part on the ship's arrival at [beacon]: the first visit draws the scene
         * ([create]); a revisit applies the stored one (0x4187c0), which makes it SpaceManager's current backdrop and
         * planet without a draw.
         */
        @JvmStatic
        fun arrive(game: InGameState, beacon: Beacon) {
            val stored = beacon.scene
            if (stored == null) {
                create(game, beacon)
                return
            }
            game.gameMap.spaceBack = stored.backdrop
            game.gameMap.spacePlanet = stored.planet
        }

        /**
         * SetDangerZone (0x41a560): nine slots, three per column: slot i sits at (random32 % 1280 / 3 + (i / 3) * 1280 / 3,
         * (i % 3) * 720 / 3 + random32 % 720 / 3) and shows one of four rebel images (random32 % 4) or of four Federation
         * ones. A mixed fleet is rebel in the first column, Federation in the last and either (random32 % 2, drawn after
         * the image) in the middle.
         */
        @JvmStatic
        fun drawFleet(scene: BeaconScene, fleet: FleetBackground) {
            val slots = ArrayList<IntArray>()
            for (i in 0 until 9) {
                val x = NativeRandom.next31() % 1280 / 3 + i / 3 * 1280 / 3
                val y = i % 3 * 720 / 3 + NativeRandom.next31() % 720 / 3
                val image = NativeRandom.next31() % 4
                val fed = when (fleet) {
                    FleetBackground.REBEL -> false
                    FleetBackground.FEDERATION -> true
                    FleetBackground.BOTH -> i > 5 || (i > 2 && NativeRandom.next31() % 2 == 1)
                }
                slots.add(intArrayOf(x, y, image + if (fed) 4 else 0))
            }
            scene.fleetType = fleet
            scene.fleet = slots
        }

        /**
         * SetPlanetaryDefense (0x418ca0), the part that draws the planet: with no ASB up and no fleet behind it one of
         * PLANET_POPULATED and its turn. The timer (`random32() % 5000`, first) and the final `% 200` offset are the ASB's
         * own ([AsbEnvironment]). Done once per scene; a rebuilt scene finds the pick kept.
         */
        @JvmStatic
        fun drawPopulated(game: InGameState, scene: BeaconScene) {
            val planet = pickFrom(game.eventManager.getImageList("PLANET_POPULATED"))
            scene.pdsPlanet = planet?.path ?: ""
            if (planet != null) {
                val img = planet.getImg(game)
                scene.pdsW = planet.width ?: img.width
                scene.pdsH = planet.height ?: img.height
                scene.pdsTurned = NativeRandom.next31() % 2 == 1
            }
        }
    }
}
