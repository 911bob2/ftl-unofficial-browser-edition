package xyz.znix.xftl.systems

import org.jdom2.Element
import xyz.znix.xftl.Ship
import xyz.znix.xftl.SystemInfo
import xyz.znix.xftl.Translator
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.crew.MedbayHealing
import xyz.znix.xftl.hs.crew.droneHealFactor
import xyz.znix.xftl.drones.AbstractDrone
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.savegame.SaveUtil
import xyz.znix.xftl.utils.WeaponPowerManager
import xyz.znix.xftl.weapons.DroneBlueprint
import kotlin.math.max

class Drones(blueprint: SystemBlueprint) : MainSystem(blueprint) {
    override val sortingType: SortingType get() = SortingType.DRONES

    /** The slots; assigning a DroneInfo equips it (DroneSystem::AddDrone after ShipManager::CreateSpaceDrone). */
    val drones: ArrayList<DroneInfo?> = xyz.znix.xftl.drones.DroneRuntime.SlotList(this)

    /** The ship once the system is installed (the slot list attaches bodies to it). */
    val attachedShip: Ship? get() = room?.ship

    private val droneLaunchSound by onInit { it.sounds.getSample("droneLaunch") }

    lateinit var powerManager: WeaponPowerManager
        private set

    // This is kinda horrible, but it's a convenient way to prevent
    // zoltan power from deploying drones automatically.
    private var allowDroneDeploy: DroneInfo? = null

    override fun initialise(ship: Ship) {
        super.initialise(ship)

        // Resize the blueprints list to match the ship
        // On some enemy ships (eg REBEL_FAT) there's a drone system but
        // no number of drone slots. The slot count is ShipManager+0x72c,
        // which the constructor (0x4a0fb0) defaults to four.
        val droneSlots = ship.droneSlots ?: 4
        while (drones.size < droneSlots)
            drones.add(null)

        powerManager = WeaponPowerManager(this, DronePowerAccess(), requeueOnLostForcedPower = true)
        xyz.znix.xftl.drones.DroneRuntime.attachPending(this)
    }

    override fun powerStateChanged() {
        super.powerStateChanged()

        // Don't adjust the power if the drones haven't yet been loaded.
        if (ship.sys.isCurrentlyLoadingSave)
            return

        powerManager.powerStateChanged()
    }

    override fun increasePower() {
        powerManager.increasePower()
    }

    override fun decreasePower() {
        powerManager.decreasePower()
    }

    override fun update(dt: Float) {
        super.update(dt)

        // DroneSystem::OnLoop (0x639a50): a drone that 0x6391e0 switched off
        // because its forced (Zoltan) share shrank gets one chance to be
        // powered on again from the reactor.
        powerManager.update()

        for (info in drones) {
            if (info == null)
                continue

            // Space drones run their OnLoop in the space loop (DroneRuntime.spaceLoop) before the ships; zero-time
            // updates (save restoration) still reach them here.
            if (dt <= 0f || !xyz.znix.xftl.drones.DroneRuntime.loopsInSpace(info)) info.instance?.update(dt)

            // DroneSystem::OnLoop (0x639a50): SetHacked(bUnderAttack ? iHackEffect : 0), Drone::UpdateRebuilding
            // (0x462470: the timer only counts while above zero, so it ends just below it), poweredAtLocation
            // latches once powered, and a dead drone (Lua SetDestroyed) is undeployed and revived.
            info.iHackLevel = if (hackedBy == null && hsHackLevel <= 0) 0 else if (isHackActive) 2 else 1
            if (info.destroyedTimer > 0f) info.destroyedTimer -= dt
            if (info.powered) info.poweredAtLocation = true
            // Only a deployed dead drone is undeployed and revived (0x639a50 checks GetDeployed). A combat drone whose
            // passes are used up keeps looping undeployed, and CombatDrone's loop (0x4285b0: lifespan < 1 ->
            // SetDestroyed(true, false)) marks it dead again the frame after, for good (native 1.6.14 drone-order
            // probe 2: life 0, undeployed with bDead 0, then bDead 1).
            if (info.bDead && info.deployed) {
                info.bDead = false
                info.instance?.let { it.isPowered = false; it.removeInstance() }
                info.storedPowered = false
            } else if (dt > 0f && !info.bDead && !info.deployed && info.instance == null &&
                info.type.type == DroneBlueprint.DroneType.COMBAT && info.storedLifespan < 1) {
                info.bDead = true
            }
        }

        // If a drone has turned itself on or off, find out why and fix it.
        if (powerSupplied != powerManager.currentPower) {
            powerStateChanged()
        }

        healCrewDrones(dt)
    }

    /**
     * ShipManager's crew update (0x4aa670): the ship's own crew drones
     * standing in a working drone control room with no intruders heal at
     * the one-power medbay rate times 0.2 (CrewMember health update
     * 0x47c240), 1.28 health per second.
     */
    private fun healCrewDrones(dt: Float) {
        val room = room ?: return
        if (powerSupplied == 0)
            return
        if (room.crew.any { it.mode == AbstractCrew.SlotType.INTRUDER })
            return

        for (crew in room.crew) {
            if (crew is LivingCrew || crew.mode != AbstractCrew.SlotType.CREW)
                continue
            if (crew.health <= 0f || crew.health >= crew.maxHealth)
                continue
            crew.dealDamage(MedbayHealing(-0.4f * 16f * (if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.crew.HsCrew.droneHealFactor(crew) else 0.2f) * dt))
        }
    }

    /**
     * DroneSystem's swap (0x6394c0, from DroneControl::SwapArmaments 0x54d7f0): two entries of the
     * drone list trade places, deployed drones and power included.
     */
    fun swapSlots(from: Int, to: Int): Boolean {
        if (from !in drones.indices || to !in drones.indices || from == to || drones[from] == null) return false
        (drones as xyz.znix.xftl.drones.DroneRuntime.SlotList).swap(from, to)
        powerManager.powerStateChanged()
        ship.sys.shipUI.dronesSlotsChanged()
        ftl.browser.BrowserDebug.record("drones", "slots.swap", "{\"from\":$from,\"to\":$to}")
        return true
    }

    /**
     * DroneSystem::RemoveDrone (Hyperspace Lua): the [index]th equipped drone leaves (depowered, undeployed) and the
     * drones after it move up a slot, as the native vector closes the gap. Returns the removed drone.
     */
    fun luaRemoveDrone(index: Int): DroneInfo? {
        val slots = drones.indices.filter { drones[it] != null }
        val slot = slots.getOrNull(index) ?: return null
        val info = drones[slot]!!
        if (info.powered) setDronePower(slot, false)
        info.instance?.removeInstance()
        drones.removeAt(slot)
        drones.add(null)
        powerManager.powerStateChanged()
        ship.sys.shipUI.dronesSlotsChanged()
        return info
    }

    /**
     * ShipManager::AddDrone (0x4b1020) -> DroneSystem::AddDrone (0x639640): a slot inside the drone vector replaces
     * that drone (depowered and undeployed first), otherwise the drone is appended while there is room.
     */
    fun luaAddDrone(bp: DroneBlueprint, slot: Int) {
        val slots = drones.indices.filter { drones[it] != null }
        val target = if (slot >= 0 && slot < slots.size) slots[slot] else drones.indexOfFirst { it == null }
        if (target < 0) return
        drones[target]?.let { old ->
            if (old.powered) setDronePower(target, false)
            old.instance?.removeInstance()
        }
        drones[target] = DroneInfo(bp)
        powerManager.powerStateChanged()
        ship.sys.shipUI.dronesSlotsChanged()
    }

    /**
     * Attempt to set the power status of the drone in the nth slot,
     * considering the system and reactor power limits.
     *
     * @return True if the given change was made.
     */
    /** DroneSystem::userPowered: slots the player powered. */
    @JvmField val hsUserPowered = HashMap<Int, Boolean>()

    fun setDronePower(slot: Int, power: Boolean): Boolean {
        val info = drones.getOrNull(slot) ?: return false
        if (info.type.type == DroneBlueprint.DroneType.HACKING) return false
        allowDroneDeploy = info
        try {
            return powerManager.setItemPower(slot, power) && (info.instance?.isPowered == true) == power
        } finally {
            allowDroneDeploy = null
        }
    }

    private fun setDronePowerInternal(slot: Int, power: Boolean) {
        val info = drones[slot] ?: return

        // If we're allowed to deploy a drone, it only counts once. Hyperspace: only for that drone, since the power
        // update in between re-powers 0-power drones (MV's MANNING) through here (oracle sweep-crew4-drone)
        val allowDeploy = allowDroneDeploy == info
        if (allowDeploy || !xyz.znix.xftl.hs.HS.active) allowDroneDeploy = null

        // If the drone is already powered appropriately, nothing needs to be done.
        // This includes 'off' when the drone hasn't been spawned.
        val currentPower = info.instance?.isPowered ?: false
        if (currentPower == power)
            return

        var deployed = false

        if (power) {
            // If this drone attacks hostile ships but there isn't
            // one present, we can't turn it on. A ship that is present but no longer hostile (surrendered, crew
            // dead) is no target either: WorldManager::OnLoop (0x61b930) ends the fight with the player's
            // ShipManager::SetTarget(0) (0x4a2b30), and AbstractExternalDrone.init would fail on it.
            val hostileShip = ship.sys.getEnemyOf(ship)
            if (info.type.type.needsHostileShip && hostileShip == null) {
                return
            }

            // DroneSystem::PowerDrone (1.6.14 0x638b80): a drone that cannot be deployed (CanBeDeployed) and is not
            // out already stays off. A combat or boarding drone equipped after the other ship came has no target.
            if (info.browserTargetless && info.instance == null) {
                return
            }

            // Check the drone isn't on it's destroy cooldown
            if (info.cooldown != null) {
                return
            }

            // Create the drone instance if it's not already, and we're allowed to.
            if (info.instance == null) {
                deployed = true
                // Don't let zoltan power deploy drones.
                if (!allowDeploy) {
                    return
                }

                // Consume a drone part.
                if (!ship.sys.debugFlags.infiniteDrones.set) {
                    if (ship.dronesCount <= 0)
                        return
                    ship.dronesCount--
                }

                // Drone::SetDeployed(true) on the equipped body: the engine builds the drone's actor now.
                info.deployActor(ship)

                // DroneSystem's PowerDrone (0x638b80) plays "droneLaunch" right there in its not-yet-deployed
                // branch, once per launched drone and also while the game is paused.
                droneLaunchSound.play()
            }
        }

        info.instance!!.isPowered = power
        if (power && ship.isPlayerShip) {
            // FUN_00638b80: 'used_drone' is only counted when the drone is
            // launched (the not-yet-deployed branch), 'offensive_drone' on
            // every power-on.
            if (deployed)
                ship.sys.browserAchievements.record(ftl.browser.BrowserAchievementProgress.Action.USED_DRONE)
            if (info.type.type == DroneBlueprint.DroneType.COMBAT || info.type.type == DroneBlueprint.DroneType.BOARDER)
                ship.sys.browserAchievements.record(ftl.browser.BrowserAchievementProgress.Action.OFFENSIVE_DRONE)
        }
        return
    }

    fun enemyShipUpdated() {
        for (drone in drones) {
            drone?.instance?.onEnemyShipUpdated()
        }
    }

    /**
     * ShipManager::SetTarget (1.6.14 0x4a2b30), called when another ship comes to the beacon or leaves it
     * (CombatControl::AddEnemyShip, CombatControl::Clear; not when it turns hostile): every equipped space drone takes
     * that ship as its movement and weapon target, so every drone has one again.
     */
    fun browserTargetShipChanged() {
        for (drone in drones) drone?.browserTargetless = false
    }

    /**
     * A drone equipped now (ShipManager::AddDrone 0x4b1020: the equipment screen, an event's reward, the console).
     * In vanilla ShipManager::CreateSpaceDrone (0x4b0b60) gives a new combat or boarding drone no target, and
     * ShipManager::SetTarget only runs when a ship comes, so while one is at the beacon the drone cannot be deployed
     * (CombatDrone::CanBeDeployed 0x4202c0 is HasTarget, BoarderPodDrone::CanBeDeployed 1.6.9 0x41e860 needs the
     * target) until the next ship. Hyperspace's CreateSpaceDrone hook (CustomDrones.cpp) aims both at the current
     * target, see [browserHsTargetless].
     */
    fun browserDroneEquipped(info: DroneInfo) {
        val type = info.type.type
        val present = ship.sys.enemy != null && ship.sys.enemy !== ship
        info.browserTargetless = present && (type == DroneBlueprint.DroneType.COMBAT || type == DroneBlueprint.DroneType.BOARDER) &&
            (!xyz.znix.xftl.hs.HS.active || browserHsTargetless(type))
    }

    /**
     * Hyperspace: CreateSpaceDrone's hook gives a combat drone both targets, so it launches; a boarding drone equipped
     * during the fight still does not launch until the next ship (HS oracle ps-fu3-pdrone and -pdrone2: COMBAT_1 powers,
     * BOARDER stays off with the reactor and the drone system free, clicked first or second, no drone part spent).
     */
    private fun browserHsTargetless(type: DroneBlueprint.DroneType): Boolean = type == DroneBlueprint.DroneType.BOARDER

    fun getDroneForcedPower(slot: Int): Int {
        return powerManager.getForcedPower(slot)
    }

    // The drone instances are all serialised separate, but we store the blueprints here.
    override fun saveSystem(elem: Element, refs: ObjectRefs) {
        for ((slot, drone) in drones.withIndex()) {
            if (drone == null)
                continue

            val droneElem = Element("drone")
            SaveUtil.addAttr(droneElem, "type", drone.type.name)
            SaveUtil.addAttrInt(droneElem, "slot", slot)
            drone.cooldown?.let { SaveUtil.addAttrFloat(droneElem, "cooldown", it) }
            elem.addContent(droneElem)
        }
    }

    override fun loadSystem(elem: Element, refs: RefLoader) {
        for (droneElem in elem.getChildren("drone")) {
            val type = SaveUtil.getAttr(droneElem, "type")
            val blueprint = ship.sys.blueprintManager[type] as DroneBlueprint

            // If there aren't enough slots, make a new one
            // This is required for the flagship at least, and any modded ships
            // which have more drones than drone slots.
            // Note that loadDefaultContents does the same thing, adding slots if required.
            var firstSlot = SaveUtil.getAttrIntOrNull(droneElem, "slot") ?: drones.indexOf(null)
            while (firstSlot >= drones.size) drones.add(null)
            if (firstSlot == -1) {
                firstSlot = drones.size
                drones.add(null)
            }
            drones[firstSlot] = DroneInfo(blueprint, null).also {
                it.cooldown = droneElem.getAttributeValue("cooldown")?.toFloat()
            }
        }
    }

    /**
     * One equipped drone: the native Drone object ([xyz.znix.xftl.drones.NativeDrone]), made when the drone joins
     * the ship and kept while it stays equipped; [instance] is its actor while deployed.
     */
    class DroneInfo(type: DroneBlueprint, instance: AbstractDrone? = null) : xyz.znix.xftl.drones.NativeDrone(type, instance) {
        /** No ShipManager::SetTarget since this drone was equipped ([browserDroneEquipped]): it cannot be deployed. */
        @JvmField var browserTargetless = false
    }

    private inner class DronePowerAccess : WeaponPowerManager.ItemAccess {
        // The flagship has more drones than slots, so slots are added either
        // during deserialisation or in loadDefaultContents, and it's critical that this
        // field doesn't change. Thus we lie and say there's more drones than
        // there actually are, so it's OK to add them later.
        override val count: Int = max(drones.size, 8)

        override fun hasItem(slot: Int): Boolean {
            return drones.getOrNull(slot) != null
        }

        override fun getItemPowerDraw(slot: Int): Int {
            // Drone::GetRequiredPower: powerRequired - iBonusPower (both writable from Lua under Hyperspace).
            return drones.getOrNull(slot)?.requiredPower ?: 0
        }

        override fun isItemPowered(slot: Int): Boolean {
            return drones.getOrNull(slot)?.instance?.isPowered ?: false
        }

        override fun setItemPowered(slot: Int, powered: Boolean) {
            setDronePowerInternal(slot, powered)
        }

        override fun setSystemPower(level: Int): Boolean {
            return this@Drones.setSystemPower(level)
        }
    }

    companion object {
        val INFO: SystemInfo = DronesInfo
    }
}

private object DronesInfo : SystemInfo("drones") {
    override val canBeManned: Boolean get() = false

    override fun create(blueprint: SystemBlueprint) = Drones(blueprint)

    override fun getLevelName(level: Int, translator: Translator): String {
        return translator["system_power"]
    }
}
