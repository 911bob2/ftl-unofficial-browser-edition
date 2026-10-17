package xyz.znix.xftl.environment

import org.jdom2.Element
import xyz.znix.xftl.Ship
import xyz.znix.xftl.TWO_PI
import xyz.znix.xftl.game.FTLSound
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.random
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.Image
import xyz.znix.xftl.rollChance
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.savegame.SaveUtil
import xyz.znix.xftl.sector.Beacon
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint
import xyz.znix.xftl.weapons.AbstractWeaponProjectile
import xyz.znix.xftl.weapons.MissileBlueprint
import xyz.znix.xftl.weapons.ProjectileLoadCallback
import kotlin.random.Random
import kotlin.random.nextInt

class AsteroidEnvironment(game: InGameState, beacon: Beacon) : AbstractEnvironment(game, beacon) {
    // See doc/asteroids for more information about the mechanics here

    override val type: Beacon.EnvironmentType get() = Beacon.EnvironmentType.ASTEROID

    private var asteroidAnimationTimer = 0f

    // The actual background
    private val realBackground = game.getImg("img/stars/bg_dullstars.png")

    private val layer1 = game.getImg("img/asteroids/asteroid_back1.png")
    private val layer2 = game.getImg("img/asteroids/asteroid_back2.png")
    private val layer3 = game.getImg("img/asteroids/asteroid_back3.png")

    // AsteroidGenerator constructor (0x40a7d0): the shield bars (half the
    // shield system's installed level) are picked once, when the field starts.
    private var shieldCount: Int = game.player.shields?.energyLevels?.let { it / 2 } ?: 0

    // AsteroidGenerator state (0x40b960): 0 is idle, 1 fires the slow
    // cadence and 2 the fast one. The state picked on arrival is random.
    private var state: Int = if (ftl.browser.BrowserRandom.rollChance(50)) 1 else 2
    private var phaseTimeRemaining: Float = 0f

    // Starts below zero, so the first firing state fires at once.
    private var firingTimer: Float = -1f

    // Ships present when the field started (SpaceManager::StartAsteroids
    // 0x418a40), and the index of the one the next asteroid is aimed at.
    // Shots go to ship 0 (the player) first, then round-robin.
    private var numShips: Int = if (game.enemy != null) 2 else 1
    private var targetIndex: Int = 0

    // The five ranges the generator draws from, see [Range].
    private val ranges: Array<Range> = createRanges(shieldCount)
    private val slowPeriod: Range get() = ranges[0]
    private val fastPeriod: Range get() = ranges[1]
    private val idleTime: Range get() = ranges[2]
    private val slowTime: Range get() = ranges[3]
    private val fastTime: Range get() = ranges[4]

    init {
        phaseTimeRemaining = (if (state == 1) slowTime else fastTime).next().toFloat()
    }

    override fun renderBackground(gc: GameContainer, g: Graphics) {
        val ps = pauseShade(game)

        // This draws the normal background with all its adjustments
        realBackground.draw(0f, 0f, ps)

        // Rough speeds measured from FTL
        // back img = ~13sec to traverse weapons bar (~400px)
        // middle img = ~10sec
        // foreground img = ~8sec

        // Rough speeds measured from FTL
        // back img = ~13sec to traverse weapons bar (~400px)
        // middle img = ~10sec
        // foreground img = ~8sec
        renderAsteroid(gc, layer1, 400f / 13, ps)
        renderAsteroid(gc, layer2, 400f / 10, ps)
        renderAsteroid(gc, layer3, 400f / 8, ps)

        if (game.debugFlags.showSunTimer.set) {
            game.getFont("HL2", 2f).drawString(
                550f, 20f,
                "state $state p=$phaseTimeRemaining",
                Colour.white
            )
            game.getFont("HL2", 2f).drawString(
                850f, 20f,
                "f=$firingTimer",
                Colour.white
            )
        }
    }

    override fun update(dt: Float) {
        super.update(dt)
        asteroidAnimationTimer += dt

        // AsteroidGenerator::OnLoop (0x40b960): the state timer runs out
        // and the state flips between idle and a random firing cadence.
        phaseTimeRemaining -= dt
        if (phaseTimeRemaining < 0f) {
            if (state == 0) {
                state = if (ftl.browser.BrowserRandom.rollChance(50)) 1 else 2
                phaseTimeRemaining = (if (state == 1) slowTime else fastTime).next().toFloat()
            } else {
                state = 0
                phaseTimeRemaining = idleTime.next().toFloat()
            }
        }

        // The firing timer only runs while firing, so what is left of it
        // carries over the idle gap.
        if (state != 0) {
            if (firingTimer <= 0f) {
                fireAsteroid()

                firingTimer = (if (state == 1) slowPeriod else fastPeriod).next() / 1000f
                targetIndex = if (targetIndex == numShips - 1) 0 else targetIndex + 1
            } else {
                firingTimer -= dt
            }
        }
    }

    private fun fireAsteroid() {
        // If the enemy ship is missing, discard its asteroids.
        val ship = (if (targetIndex == 0) game.player else game.enemy) ?: return

        val asteroid = AsteroidProjectile(ship.rooms.random(ftl.browser.BrowserRandom))

        // AsteroidGenerator::OnLoop (0x40b960) and Projectile::RandomSidePoint
        // (0x45ce70): the player's asteroids come from any side, but every
        // other ship is always hit from the right, in the upper half.
        val side = if (targetIndex == 0) ftl.browser.BrowserRandom.nextInt(4) else 1
        val startPos = when (side) {
            0 -> ConstPoint(-400, ftl.browser.BrowserRandom.nextInt(800))
            1 -> ConstPoint(800, ftl.browser.BrowserRandom.nextInt(800) / 2)
            2 -> ConstPoint(ftl.browser.BrowserRandom.nextInt(800), -400)
            else -> ConstPoint(ftl.browser.BrowserRandom.nextInt(800), 800)
        }

        asteroid.setInitialPath(startPos, asteroid.target.pixelCentre)

        ship.projectiles.add(asteroid)
    }

    private fun renderAsteroid(gc: GameContainer, img: Image, speed: Float, colour: Colour) {
        val offset = (asteroidAnimationTimer * speed).toInt() % img.width
        for (x in -offset until gc.width step img.width) {
            for (y in 0 until gc.height step img.height) {
                img.draw(x, y, colour)
            }
        }
    }

    /**
     * RandomAmount::Get (0x5caf40): an integer in min until the (bumped)
     * max. The max grows by one on every draw, so the range slowly widens
     * for as long as the field lasts.
     */
    class Range(val min: Int, var max: Int) {
        fun next(): Int {
            if (max == 0)
                return min
            max++
            return min + ftl.browser.BrowserRandom.nextInt(max - min)
        }
    }

    override fun saveToXML(elem: Element) {
        super.saveToXML(elem)

        SaveUtil.addAttrInt(elem, "shieldCount", shieldCount)
        SaveUtil.addAttr(elem, "currentPhase", PHASE_NAMES[state])
        SaveUtil.addAttrFloat(elem, "phaseTimer", phaseTimeRemaining)
        SaveUtil.addAttrFloat(elem, "firingTimer", firingTimer)
        SaveUtil.addAttrBool(elem, "fireAtEnemy", targetIndex != 0)
        SaveUtil.addAttrInt(elem, "browserNumShips", numShips)
        SaveUtil.addAttrInt(elem, "browserTargetIndex", targetIndex)
        elem.setAttribute("browserRangeMax", ranges.joinToString(",") { it.max.toString() })
    }

    override fun loadFromXML(elem: Element) {
        super.loadFromXML(elem)

        shieldCount = SaveUtil.getAttrInt(elem, "shieldCount")
        state = PHASE_NAMES.indexOf(SaveUtil.getAttr(elem, "currentPhase")).coerceAtLeast(0)
        phaseTimeRemaining = SaveUtil.getAttrFloat(elem, "phaseTimer")
        firingTimer = SaveUtil.getAttrFloat(elem, "firingTimer")

        // Older saves only have the alternating flag, and always had two ships.
        numShips = elem.getAttributeValue("browserNumShips")?.toIntOrNull() ?: 2
        targetIndex = elem.getAttributeValue("browserTargetIndex")?.toIntOrNull()
            ?: if (SaveUtil.getAttrBool(elem, "fireAtEnemy")) 1 else 0
        targetIndex = targetIndex.coerceIn(0, numShips - 1)

        // The ranges depend on the shield count, which was just loaded.
        val loaded = createRanges(shieldCount)
        for (i in loaded.indices)
            ranges[i] = loaded[i]
        elem.getAttributeValue("browserRangeMax")?.split(',')?.forEachIndexed { i, v ->
            v.toIntOrNull()?.let { if (i < ranges.size) ranges[i].max = it }
        }
    }

    private companion object {
        val PHASE_NAMES = listOf("IDLE", "FIRING_SLOW", "FIRING_FAST")

        /**
         * The ranges set by the AsteroidGenerator constructor (0x40a7d0),
         * picked by half the shield level: the slow and fast firing periods
         * in milliseconds, then the idle, slow and fast state times in
         * seconds.
         */
        fun createRanges(shieldCount: Int): Array<Range> {
            return when {
                shieldCount < 2 -> arrayOf(
                    Range(1800, 2200), Range(1400, 1600),
                    Range(5, 10), Range(8, 16), Range(5, 8)
                )

                shieldCount == 2 -> arrayOf(
                    Range(1000, 1800), Range(900, 1300),
                    Range(5, 10), Range(10, 20), Range(5, 10)
                )

                shieldCount == 3 -> arrayOf(
                    Range(900, 1400), Range(900, 1300),
                    Range(12, 15), Range(16, 28), Range(8, 13)
                )

                else -> arrayOf(
                    Range(720, 1350), Range(540, 950),
                    Range(12, 15), Range(16, 30), Range(8, 11)
                )
            }
        }
    }
}

class AsteroidProjectile(target: Room) : AbstractWeaponProjectile(TYPE, target) {
    override val defaultSpeed: Int get() = 35

    // Asteroids hit everyone's drones
    override val antiDroneExemption: Ship? get() = null

    override val isMissileForDD: Boolean get() = true

    // Asteroids can kill ships that have surrendered
    override val missDeadEnemies: Boolean get() = false

    // We need a custom serialisation type, since our fake blueprint isn't
    // in the blueprint manager and thus can't otherwise be deserialised.
    override val serialisationType: String get() = SERIALISATION_TYPE

    private var angle: Float = ftl.browser.BrowserRandom.nextFloat() * TWO_PI
    private var imageId = ftl.browser.BrowserRandom.nextInt(10)

    // I'm not certain it uses all the hitHull/hitShield sounds,
    // but this should be good enough.
    // Note that we can't put these in the blueprint, as they requires
    // post-loading which we can't do for our blueprint (since it must
    // remain independent of any one game instance, as it's a static
    // field).
    private val hitHullSound: FTLSound = listOf(
        ship.sys.sounds.getSample("hitHull1"),
        ship.sys.sounds.getSample("hitHull2"),
        ship.sys.sounds.getSample("hitHull3")
    ).random(ftl.browser.BrowserRandom)
    private val hitShieldSound: FTLSound = listOf(
        ship.sys.sounds.getSample("hitShield1"),
        ship.sys.sounds.getSample("hitShield2"),
        ship.sys.sounds.getSample("hitShield3")
    ).random(ftl.browser.BrowserRandom)

    init {
        drawUnderShip = false
    }

    override fun renderPreTranslated(g: Graphics) {
        g.pushTransform()

        // This applies our spinning rotation on top of the existing
        // rotation from our direction, but that's fine - it's all
        // randomised anyway.
        g.rotateRadians(0f, 0f, angle)

        val img = ship.sys.getImg("img/asteroids/asteroid$imageId.png")
        img.draw(-img.width / 2f, -img.height / 2f)

        g.popTransform()
    }

    override fun update(dt: Float, currentSpace: Ship) {
        super.update(dt, currentSpace)

        // Asteroids spin at 160°/sec in vanilla
        angle += (160f / 360f * TWO_PI) * dt
    }

    override fun hitHull() {
        super.hitHull()
        hitHullSound.play()
    }

    override fun hitShields() {
        super.hitShields()
        hitShieldSound.play()
    }

    override fun saveToXML(elem: Element, refs: ObjectRefs) {
        super.saveToXML(elem, refs)
        SaveUtil.addRoomRef(elem, "target", refs, target)
        SaveUtil.addAttrInt(elem, "asteroidType", imageId)
        SaveUtil.addAttrFloat(elem, "asteroidAngle", angle)
    }

    override fun loadPropertiesFromXML(elem: Element, refs: RefLoader) {
        super.loadPropertiesFromXML(elem, refs)
        imageId = SaveUtil.getAttrInt(elem, "asteroidType")
        angle = SaveUtil.getAttrFloat(elem, "asteroidAngle")
    }

    companion object {
        const val SERIALISATION_TYPE = "asteroid"

        private val TYPE: AbstractWeaponBlueprint

        init {
            fun addElem(elem: Element, name: String, value: String) {
                val content = Element(name)
                content.text = value
                elem.addContent(content)
            }

            // This is a pretty horrible way of creating the type, but it's
            // cleaner than making some new way to create blueprints just
            // for this one projectile.
            // Note the speed is set via the defaultSpeed property.
            val elem = Element("weaponBlueprint")
            elem.setAttribute("name", "!!INTERNAL Asteroid")
            addElem(elem, "explosion", "explosion1")
            addElem(elem, "weaponArt", "<<ASTEROID - NONE>>")
            addElem(elem, "damage", "1")
            addElem(elem, "fireChance", "2")
            addElem(elem, "breachChance", "1")

            TYPE = MissileBlueprint(elem)
        }

        fun loadFromXML(elem: Element, refs: RefLoader, callback: ProjectileLoadCallback) {
            SaveUtil.getRoomRef(elem, "target", refs) { target ->
                val projectile = AsteroidProjectile(target)
                projectile.loadPropertiesFromXML(elem, refs)
                callback(projectile)
            }
        }
    }
}
