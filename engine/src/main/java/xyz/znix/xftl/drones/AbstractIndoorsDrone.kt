package xyz.znix.xftl.drones

import org.jdom2.Element
import xyz.znix.xftl.Ship
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.crew.AbstractCrewDamage
import xyz.znix.xftl.crew.CrewBlueprint
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.hs.crew.powerDamageArea
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.savegame.SaveUtil
import xyz.znix.xftl.systems.Drones
import xyz.znix.xftl.weapons.DroneBlueprint

/**
 * A drone that operates on the inside of a ship, like a repair or boarding drone.
 */
abstract class AbstractIndoorsDrone(type: DroneBlueprint) : AbstractDrone(type) {
    /**
     * The type of slot that represents whether this drone counts as a crewmember
     * or an enemy for the purposes of occupying a cell in a room (this is where
     * you can have an enemy and a crewmember standing in the same cell, but not
     * two crewmembers in the same cell).
     */
    abstract val occupancySlotType: AbstractCrew.SlotType

    /**
     * The name of the blueprint for the fake crew race that's used for drawing the drone.
     *
     * This matches up with the spritesheets in img/ship/drones, and with
     * the crewBlueprint in the XML.
     */
    abstract val pawnCodename: String

    /**
     * The ship this drone is aboard. This may be different to [ownerShip],
     * in the case of boarding drones.
     */
    lateinit var ship: Ship
        private set

    var pawn: Pawn? = null

    /** Hyperspace: a system-less pod whose crew drone died (see Pawn.removeFromShip); the pod itself stays. */
    var hsCrewLost = false
        private set

    /**
     * Spawn in [targetRoom], or one of the nearby rooms if that's full.
     */
    protected fun spawn(targetRoom: Room) {
        ship = targetRoom.ship

        val emptySpace = ship.findSpaceForCrew(targetRoom, occupancySlotType)

        // Hyperspace: the CrewDrone made when the drone joined the ship deploys (hsIdlePawn; constructed then)
        val kept = hsIdle
        if (kept != null) {
            pawn = kept
            kept.hsRedeployed()
        } else {
            pawn = makePawn(emptySpace.room)
            // Hyperspace CONSTRUCT_CREWMEMBER: a crew drone's body is a CrewMember too.
            if (xyz.znix.xftl.hs.HS.luaActive) xyz.znix.xftl.hs.lua.LuaCrewEvents.constructCrewMember(pawn!!)
            // the body stays the drone's from now on (a drone deployed before it had one, e.g. after a load)
            if (xyz.znix.xftl.hs.HS.active && (this is AntiPersonnelDrone || this is RepairDrone)) hsIdle = pawn
        }
        ship.crew += pawn!!

        // Move the drone to it's starting position
        pawn!!.jumpTo(emptySpace)
    }

    protected open fun makePawn(room: Room): Pawn {
        return Pawn(room)
    }

    private var hsIdle: Pawn? = null

    /**
     * Hyperspace `ShipManager.vCrewList`: ShipManager::CreateCrewDrone puts an anti-personnel or repair
     * drone's CrewDrone into vCrewList when the drone is added, so until it is deployed scripts see it there, dead,
     * roomless and at 0,0 (oracle sw4-cdrone d0). The body that stands for it (never on the ship); null once deployed.
     */
    fun hsIdlePawn(room: Room): Pawn? {
        if (pawn != null || (this !is AntiPersonnelDrone && this !is RepairDrone && this !is BoardingDrone)) return null
        hsIdle?.let { return it }
        if (!::ship.isInitialized) ship = room.ship
        hsBindOwner(room.ship)
        // the CrewMember constructor: its selfId and CONSTRUCT_CREWMEMBER, once per drone (NativeDrone.hsCrewBody)
        return makePawn(room).also {
            hsIdle = it
            if (xyz.znix.xftl.hs.HS.luaActive) xyz.znix.xftl.hs.lua.LuaCrewEvents.constructCrewMember(it)
        }
    }

    /** Hyperspace: the kept body goes on the ship again (an undeployed or rebuilt crew drone redeploying). */
    fun hsRespawn() {
        hsCrewLost = false
        isPowered = false
        val room = ownerShip.rooms.firstOrNull { it.system is Drones } ?: return
        spawn(room)
    }

    /**
     * The stand-in's health: CrewDrone's own maximum, as the CrewDrone is made; HS's race stats apply once it is deployed
     * (oracle sw4-cdrone d0-c3: MV battle drone 150/150 undeployed, 175/175 deployed).
     */
    fun hsIdleHealth(crew: AbstractCrew): Float =
        (crew as? Pawn)?.hsBaseMaxHealth ?: crew.maxHealth

    /** [crew] is a stand-in of [hsIdlePawn], not a deployed body. */
    fun hsIsIdle(crew: AbstractCrew): Boolean = crew === hsIdle && pawn !== crew

    /** Hyperspace: the drone a stand-in instance of [hsIdlePawn] stands for (deployed instances have [body]). */
    @JvmField var hsStandsFor: NativeDrone? = null

    /**
     * Hyperspace: the names (short, long) a Lua CrewMember:SetName gave this drone's body, kept on the drone (the
     * stand-in and every deployed body are its one CrewDrone); null keeps [hsCrewName]'s.
     */
    var hsNameOverride: Pair<String, String>?
        get() = (hsStandsFor ?: body)?.hsCrewNameOverride ?: ownNameOverride
        set(value) { (hsStandsFor ?: body)?.let { it.hsCrewNameOverride = value } ?: run { ownNameOverride = value } }
    private var ownNameOverride: Pair<String, String>? = null

    /**
     * Hyperspace CrewMember::GetName / GetLongName of this drone's body (stand-in and deployed alike): the CrewDrone
     * constructors name it with a forced SetName by text id, anti_person_drone, crew_repair_drone,
     * boarder_drone; HS CustomDrones.cpp names a `<drones>` definition's body by its tooltipName.
     */
    fun hsCrewName(long: Boolean, translate: (String) -> String): String {
        hsNameOverride?.let { return if (long) it.second else it.first }
        xyz.znix.xftl.hs.weapons.HsDrones.customDrones()[type.name]?.tooltipName?.takeIf { it.isNotEmpty() }?.let { return it }
        return translate(
            when (this) {
                is AntiPersonnelDrone -> "anti_person_drone"
                is RepairDrone -> "crew_repair_drone"
                else -> "boarder_drone"
            }
        )
    }

    override fun removeInstance(keepDeployed: Boolean) {
        super.removeInstance(keepDeployed)

        // If the pawn was never spawned, there's nothing to clean up.
        val pawn = this.pawn ?: return
        this.pawn = null

        // Destroy the pawn
        pawn.removeFromShip()
    }

    override fun update(dt: Float) {
        super.update(dt)

        // If something weird happens and our pawn disappears
        // from the enemy ship, assume it was destroyed.
        val pawn = this.pawn
        if (pawn != null && !pawn.room.ship.crew.contains(pawn)) {
            destroy()
        }
    }

    override fun onEnemyShipUpdated() {
        super.onEnemyShipUpdated()

        // If we're on the former enemy ship, destroy ourselves.
        if (pawn != null && ship != ownerShip && ship != ownerShip.sys.getEnemyOf(ownerShip)) {
            destroy()
        }
    }

    override fun onPowerChanged() {
        super.onPowerChanged()
        pawn?.syncPowerAnimation()
    }

    protected abstract fun updatePawn(dt: Float)

    /** Intrinsic crew behavior continues when HS suppresses autonomous drone orders. */
    protected open fun updatePawnPassive(dt: Float) {}

    protected abstract fun drawPawn()

    /**
     * The race the pawn is drawn and simulated as: [pawnCodename], or under Hyperspace the `<drones><drone
     * name><crewBlueprint>` race of this drone blueprint (CrewDrone / BoarderPodDrone constructor hooks,
     * CustomDrones.cpp) when that race has a crew blueprint.
     */
    val pawnRace: String
        get() {
            if (!xyz.znix.xftl.hs.HS.active) return pawnCodename
            val race = xyz.znix.xftl.hs.weapons.HsDrones.customRace(type.name) ?: return pawnCodename
            return race
        }

    private fun getPawnBlueprint(game: InGameState): CrewBlueprint {
        if (xyz.znix.xftl.hs.HS.active) {
            (game.blueprintManager.getOrNull(pawnRace) as? CrewBlueprint)?.let { return it }
        }
        return game.blueprintManager[pawnCodename] as CrewBlueprint
    }

    override fun saveToXML(elem: Element, refs: ObjectRefs) {
        super.saveToXML(elem, refs)

        if (pawn != null) {
            val pawnElem = Element("pawn")
            pawn!!.saveToXML(pawnElem, refs)
            elem.addContent(pawnElem)
        }
    }

    override fun loadFromXML(elem: Element, refs: RefLoader, containingShip: Ship) {
        super.loadFromXML(elem, refs, containingShip)

        ship = containingShip

        val pawnElem = elem.getChild("pawn")
        if (pawnElem != null) {
            // Pick an arbitrary room to spawn in, we'll move while loading the pawn's XML anyway.
            pawn = makePawn(ship.rooms[0])
            if (xyz.znix.xftl.hs.HS.active && (this is AntiPersonnelDrone || this is RepairDrone)) hsIdle = pawn
            if (xyz.znix.xftl.hs.HS.luaActive) xyz.znix.xftl.hs.lua.LuaCrewEvents.constructCrewMember(pawn!!)
            pawn!!.loadFromXML(pawnElem, refs)

            // Don't add the pawn to the crew list until we've loaded
            // our owner ship, since that's required for most things.
            refs.addOnResolveFunction {
                ship.crew.add(pawn!!)
            }
        }
    }

    /**
     * An indoors drone comprises two objects: the drone object itself, and
     * a crewmember 'pawn'. This is to allow drones like boarding drones that
     * first cross the screen and can be shot down, or otherwise don't
     * immediately spawn. Also, [AbstractCrew] needs the initial room in its
     * constructor, which is inconvenient for us.
     */
    open inner class Pawn(room: Room) :
        AbstractCrew(getPawnBlueprint(room.ship.sys), room.ship.sys.animations, room) {

        // A getter, not a field: AbstractCrew's constructor already reads it (Hyperspace max health -> the crew
        // member's ship id), before a field of this subclass would be set.
        val drone: AbstractIndoorsDrone get() = this@AbstractIndoorsDrone

        var powerUpDuration: Float = 0f
        var runningLastUpdate: Boolean = false
        private var hsPowerUpIcon: Any? = null

        // Hyperspace CrewMember_Extend::Initialize (the CrewMember constructor, a CrewDrone's too): the race's powers
        // (oracle sweep-crew4-droneai: MV's roomba has its BREACH power, 0/15 and charges INT_MAX, while undeployed)
        init {
            if (xyz.znix.xftl.hs.HS.active && hsDefinition() != null) xyz.znix.xftl.hs.crew.HsCrew.ex(this).calculatePowerDef()
        }

        /**
         * CrewMember::OnLoop's HS power part (CustomCrew.cpp), which CrewDrone::OnLoop runs as well: the powers' definitions,
         * their charge and activation (a ready activateWhenReady power fires: the roomba's cooldown restarts at 15 s), the
         * activated power's room damage (ShipManager::UpdateCrewMembers) and the power resources.
         */
        private fun hsPowerLoop(dt: Float) {
            if (!xyz.znix.xftl.hs.HS.active || hsDefinition() == null || pawn !== this) return
            val e = xyz.znix.xftl.hs.crew.HsCrew.ex(this)
            e.calculatePowerDef()
            for (p in ArrayList(e.crewPowers)) p.onUpdate(dt)
            for (p in ArrayList(e.crewPowers)) xyz.znix.xftl.hs.crew.HsCrew.powerDamageArea(p)
            for (r in ArrayList(e.powerResources)) r.onUpdate(dt)
        }

        // Hyperspace CrewDrone vtable (DroneVTable.cpp): a raced drone answers from its race's stats.
        override val canManSystem: Boolean
            get() = hsFlag(xyz.znix.xftl.hs.stat.CrewStat.CAN_MAN)?.let {
                it && mode == SlotType.CREW && ship === ownerShip && stunTime == 0f && currentAction != Action.DYING
            } ?: false
        override val movementSpeed: Float get() = BASE_MOVEMENT_SPEED * (hsStat(xyz.znix.xftl.hs.stat.CrewStat.MOVE_SPEED_MULTIPLIER) ?: .5f)
        override val baseMaxHealth: Float
            get() = hsDefinition()?.let { xyz.znix.xftl.hs.crew.HsCrew.ex(this).calculateMaxHealth(it).toFloat() } ?: vanillaMaxHealth
        /** CrewDrone's own maximum health (GetMaxHealth's vanilla values), for drones without a race definition. */
        protected open val vanillaMaxHealth: Float get() = 100f

        /** CrewDrone::GetMaxHealth's own value (the health a CrewDrone is made with, before Hyperspace's race stats). */
        val hsBaseMaxHealth: Float get() = vanillaMaxHealth
        override val fireFightingSpeed: Float get() = hsStat(xyz.znix.xftl.hs.stat.CrewStat.FIRE_REPAIR_MULTIPLIER) ?: 1.2f

        /**
         * Hyperspace: a drone whose race has a definition (hyperspace.xml `<crew><race>`, MV defines battle, repair
         * and the custom drone races) takes its stats from CrewMember_Extend::CalculateStat like any crew member;
         * null keeps the vanilla value.
         */
        protected fun hsStat(stat: xyz.znix.xftl.hs.stat.CrewStat): Float? {
            val def = hsDefinition() ?: return null
            return xyz.znix.xftl.hs.crew.HsCrew.ex(this).calculateStat(stat, def)
        }

        protected fun hsFlag(stat: xyz.znix.xftl.hs.stat.CrewStat): Boolean? {
            val def = hsDefinition() ?: return null
            return xyz.znix.xftl.hs.crew.HsCrew.ex(this).calculateBool(stat, def)
        }

        // HS GPL 1b05a07 DroneVTable.cpp CrewDrone::_HS_GetControllable (requiresFullControl 0/1/-1).
        private val hsControlBase: Boolean
            get() = xyz.znix.xftl.hs.HS.active && ownerShip.isPlayerShip &&
                xyz.znix.xftl.hs.crew.HsCrew.ex(this).functional && hsDefinition() != null

        override val playerControllable: Boolean
            get() = hsControlBase && (hsFlag(xyz.znix.xftl.hs.stat.CrewStat.CONTROLLABLE) == true || hsDefinition()!!.selectable)

        override val canBeOrderedToMove: Boolean
            get() = hsControlBase && hsFlag(xyz.znix.xftl.hs.stat.CrewStat.CONTROLLABLE) == true &&
                hsFlag(xyz.znix.xftl.hs.stat.CrewStat.CAN_MOVE) == true

        override val skippedByCrewAI: Boolean
            get() {
                if (!xyz.znix.xftl.hs.HS.active || hsDefinition() == null || !xyz.znix.xftl.hs.crew.HsCrew.ex(this).functional)
                    return false
                return (hsControlBase && hsFlag(xyz.znix.xftl.hs.stat.CrewStat.CONTROLLABLE) == true) ||
                    hsFlag(xyz.znix.xftl.hs.stat.CrewStat.NO_AI) == true ||
                    hsFlag(xyz.znix.xftl.hs.stat.CrewStat.CAN_MOVE) == false
            }

        /**
         * CustomCrewManager::GetDroneRaceDefinition (DroneVTable.cpp): `boarder_ion` for a BOARDER_ION drone when that race
         * is defined, else the species' race when defined, else null (the drone keeps CrewDrone's own values).
         */
        protected fun hsDefinition(): xyz.znix.xftl.hs.crew.CrewDefinition? {
            if (!xyz.znix.xftl.hs.HS.active) return null
            return xyz.znix.xftl.hs.crew.HsCrew.ex(this).definition()
        }

        /** The power-up/down animations of the pawn's race, or of the vanilla pawn when the race has none. */
        private fun pawnAnim(suffix: String): xyz.znix.xftl.AnimationSpec {
            val anims = ship.sys.animations
            if (xyz.znix.xftl.hs.HS.active) anims.getOrNull("${codename}_$suffix")?.let { return it }
            return anims.crewStrip("${if (xyz.znix.xftl.hs.HS.active) pawnCodename else codename}_$suffix")
        }
        // CrewDrone::CanFight / CanRepair (powered) / CanSabotage (intruders) from the race under Hyperspace.
        override val canFight: Boolean get() = hsFlag(xyz.znix.xftl.hs.stat.CrewStat.CAN_FIGHT) ?: false
        override val canRepair: Boolean get() = hsFlag(xyz.znix.xftl.hs.stat.CrewStat.CAN_REPAIR)?.let { it && isPowered } ?: false
        override val canSabotage: Boolean
            get() = hsFlag(xyz.znix.xftl.hs.stat.CrewStat.CAN_SABOTAGE)?.let { it && mode == SlotType.INTRUDER } ?: super.canSabotage
        override val hasDyingAnimation: Boolean get() = false
        override val suffocationMultiplier: Float
            get() = hsFlag(xyz.znix.xftl.hs.stat.CrewStat.CAN_SUFFOCATE)?.let {
                if (it) hsStat(xyz.znix.xftl.hs.stat.CrewStat.SUFFOCATION_MODIFIER) ?: 0f else 0f
            } ?: 0f
        override val fireDamageMult: Float
            get() = hsFlag(xyz.znix.xftl.hs.stat.CrewStat.CAN_BURN)?.let {
                if (it) hsStat(xyz.znix.xftl.hs.stat.CrewStat.FIRE_DAMAGE_MULTIPLIER) ?: 1f else 0f
            } ?: 0f

        /** Hyperspace: a race with `<animSheet>` is drawn with that sheet (CrewAnimation species, SetupVTable). */
        override val animationRace: String
            get() {
                if (!xyz.znix.xftl.hs.HS.active) return codename
                return xyz.znix.xftl.hs.crew.CustomCrewManager.definitionOrNull(codename)?.animSheet?.get(1)?.ifEmpty { null } ?: codename
            }

        override val mode: SlotType get() = occupancySlotType

        override val providesPlayerVision: Boolean get() = isPowered && ownerShip.isPlayerShip

        override val belongsToPlayer: Boolean get() = ownerShip.isPlayerShip

        // Drone Reactor Booster (DRONE_SPEED): the pawn walks faster.
        override val augmentSpeedMult: Float
            get() = drone.getSpeedMult()

        // Boarding drones stop sabotaging a ship they're no longer at war
        // with - e.g. one that's just been crew-killed (engine-side guard,
        // see [AbstractCrew.standingShipHostile]).
        override val standingShipHostile: Boolean
            get() = game.getEnemyOf(ship) == ownerShip || !ship.sys.isShipPresent(ownerShip)

        override fun update(dt: Float) {
            // If the ship powering this drone has jumped away, destroy it.
            // Also self-destruct if the pawn field no longer points to this
            // pawn for whatever reason. It shouldn't happen, but this could
            // avoid a drone getting stuck on the player ship.
            if (!ship.sys.isShipPresent(ownerShip) || pawn != this) {
                removeFromShip()
                return
            }

            syncPowerAnimation()

            // Only progress on movement, fighting, repairs, etc if we're powered,
            // and the power-up/power-down animation isn't playing.
            // CrewDrone::OnLoop: the power-up animation plays while the crew loop
            // runs, so a drone walks and fights at once (native 1.6.14 drone probe: a system repair drone walks from
            // 0.2 s into its 1 s power-up; HS oracle sweep-crew4-droneai: the roomba leaves the drone room in its first
            // half second)
            val hsPoweringUp = isRunning && powerUpDuration > 0f
            val on = isRunning && (powerUpDuration == 0f || hsPoweringUp)
            if (on) {
                super.update(dt)
                hsPowerLoop(dt)
                if (hsPoweringUp) {
                    powerUpDuration -= dt
                    if (powerUpDuration <= 0f) {
                        powerUpDuration = 0f
                        updateAnimation()
                    }
                }

                // Note the pawn can be null here if it was killed
                // during the update cycle.
                // CustomDrones.cpp CrewAI::UpdateDrones blocks autonomous orders to selectable/controlled drones.
                if (pawn != null) {
                    updatePawnPassive(dt)
                    // GetControllable inside CrewAI::OnLoop (requiresFullControl -1): CONTROLLABLE, NO_AI or !CAN_MOVE,
                    // not selectable (oracle sweep-crew4-droneai: MV's selectable roomba fights and walks home)
                    if (!(xyz.znix.xftl.hs.HS.active && skippedByCrewAI) &&
                        !(xyz.znix.xftl.hs.HS.active && xyz.znix.xftl.hs.crew.HsDroneAI.update(this@AbstractIndoorsDrone, pawn!!))) updatePawn(dt)
                }
            } else if (health == 0f) {
                // Dying is implemented by super.update, so we have to call it
                // when we're dead, otherwise we'd become invincible while
                // powered off.
                super.update(dt)
                hsPowerLoop(dt)
            } else {
                icon.update(dt)

                if (powerUpDuration != 0f && isRunning) {
                    powerUpDuration -= dt
                    if (powerUpDuration < 0f) {
                        powerUpDuration = 0f
                        updateAnimation()
                    }
                }
            }
        }

        /**
         * A drone turned on or off starts its power-up or power-down animation. This also runs from the power change
         * itself ([onPowerChanged]): the original shows the power-up's first frame while the game is still paused
         * (native 1.6.14 frame pair c4v-d, d-powerup-paused).
         */
        fun syncPowerAnimation() {
            if (isRunning == runningLastUpdate) return
            powerUpDuration = if (isRunning) pawnAnim("power_up").totalTime else 0f
            runningLastUpdate = isRunning
            updateAnimation()
        }

        override fun updateAnimation() {
            if (powerUpDuration != 0f) {
                // The crew loop runs during the power-up and changes actions; the animation plays on
                if (!(hsPowerUpIcon != null && icon === hsPowerUpIcon)) {
                    icon = pawnAnim("power_up").startSingle(game)
                    hsPowerUpIcon = icon
                }
                return
            }
            if (!runningLastUpdate) {
                icon = pawnAnim("power_down").startSingle(game)
                return
            }

            super.updateAnimation()
        }

        override fun draw(g: Graphics) {
            super.draw(g)
            drawPawn()
        }

        /**
         * A crew drone is drawn as its base sheet's frame with the same frame of `people/<sheet>_layer1.png` over it,
         * tinted with its ship's colour: CrewDrone::OnRender (1.6.14 0x4410a0) draws baseLayer, then lightLayer in
         * (100, 255, 100) for the player's drones and (255, 50, 50) for the enemy's (the 255-scaled vectors its static
         * initialiser copies from 0x877d00 and 0x877cf0); the walking and fighting frames show the same green lights
         * (native 1.6.14 frames ps-c4-drones c4v-d). The sheets share one layout (the CrewDrone constructor builds
         * both names from the drone type; Hyperspace's hook from the race's animSheet).
         */
        override fun drawImage(x0: Float, y0: Float, x1: Float, y1: Float, baseFrame: xyz.znix.xftl.rendering.Image, alpha: Float) {
            super.drawImage(x0, y0, x1, y1, baseFrame, alpha)
            val base = icon.spec.sheet.sheetPath
            if (!base.endsWith("_base.png")) return
            val layer = game.getImgIfExists(base.removeSuffix("_base.png") + "_layer1.png") ?: return
            val tint = if (drone.ownerShip.isPlayerShip) DRONE_LIGHT_PLAYER else DRONE_LIGHT_ENEMY
            layer.draw(x0, y0, x1, y1, baseFrame.textureOffsetX.toFloat(), baseFrame.textureOffsetY.toFloat(),
                (baseFrame.textureOffsetX + baseFrame.width).toFloat(), (baseFrame.textureOffsetY + baseFrame.height).toFloat(), alpha, tint)
        }

        /** CrewDrone::SetDeployed(true) on the kept body: full health, the power-up animation runs again. */
        fun hsRedeployed() {
            // CrewMember::Restart's HS hooks: cloneClear boosts go, the powers take their respawn charges (roomba: 0)
            if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.crew.HsCrew.ex(this).let {
                xyz.znix.xftl.hs.stat.StatBoostManager.crewRestarted(it)
                xyz.znix.xftl.hs.crew.CrewPowerLogic.restart(it)
            }
            health = maxHealth
            stunTime = 0f
            hsClearFight()
            powerUpDuration = 0f
            runningLastUpdate = false
            hsPowerUpIcon = null
        }

        override fun dealDamage(damage: AbstractCrewDamage) {
            if (damage.halvedForDrone) {
                damage.amount /= 2f
            }

            super.dealDamage(damage)
        }

        override fun removeFromShip() {
            super.removeFromShip()

            // Cleanly kill the drone
            // If pawn==null then we're being called by removeInstance,
            // in which case we're not supposed to explode.
            if (drone.pawn == null) {
                // Call it again to be certain, though.
                removeInstance()
                return
            }

            drone.pawn = null
            // Hyperspace: a boarder pod made by CreateSpaceDrone (MV temporary drones) has no DroneSystem, the only
            // thing that turns its crew drone's death into the pod's SetDestroyed (DroneSystem::OnLoop 0x639a50; the
            // BoarderPodDrone and its CrewDrone are separate native objects). The pod stays deployed and delivered
            // (oracle wp4-drone-fight: the temporary boarder stays deployed to the end).
            if (xyz.znix.xftl.hs.HS.active && drone is BoardingDrone && drone.body?.let { it.system == null } == true)
                drone.hsCrewLost = true
            else
                destroy()

            // Play the explosion animation whenever a drone is killed.
            ship.playCentredAnimation(explodeAnimation, getPixelPositionCentre())
            explodeSound.play()
        }

        override fun saveToXML(elem: Element, refs: ObjectRefs) {
            super.saveToXML(elem, refs)

            SaveUtil.addAttrFloat(elem, "powerUpDuration", powerUpDuration)
            SaveUtil.addAttrBool(elem, "onLastUpdate", runningLastUpdate)
        }

        override fun loadFromXML(elem: Element, refs: RefLoader) {
            // These are used to set the animation, so we have to load them first.
            powerUpDuration = SaveUtil.getAttrFloat(elem, "powerUpDuration")
            runningLastUpdate = SaveUtil.getAttrBool(elem, "onLastUpdate")

            super.loadFromXML(elem, refs)
        }
    }

    companion object {
        /** CrewDrone::OnRender's lightLayer colours (0x913960 player, 0x913970 enemy). */
        val DRONE_LIGHT_PLAYER = xyz.znix.xftl.rendering.Colour(100, 255, 100)
        val DRONE_LIGHT_ENEMY = xyz.znix.xftl.rendering.Colour(255, 50, 50)
    }
}
