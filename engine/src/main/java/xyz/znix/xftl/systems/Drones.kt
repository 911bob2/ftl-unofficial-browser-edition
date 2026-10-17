package xyz.znix.xftl.systems

import org.jdom2.Element
import xyz.znix.xftl.Ship
import xyz.znix.xftl.SystemInfo
import xyz.znix.xftl.Translator
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.crew.MedbayHealing
import xyz.znix.xftl.drones.AbstractDrone
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.savegame.SaveUtil
import xyz.znix.xftl.utils.WeaponPowerManager
import xyz.znix.xftl.weapons.DroneBlueprint
import kotlin.math.max

class Drones(blueprint: SystemBlueprint) : MainSystem(blueprint) {
    override val sortingType: SortingType get() = SortingType.DRONES

    val drones = ArrayList<DroneInfo?>()

    private val droneLaunchSound by onInit { it.sounds.getSample("droneLaunch") }

    private var playDroneLaunchSound = false

    private lateinit var powerManager: WeaponPowerManager

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

            info.instance?.update(dt)

            val oldCooldown = info.cooldown
            if (oldCooldown != null) {
                val newCooldown = oldCooldown - dt
                if (newCooldown <= 0) {
                    info.cooldown = null
                } else {
                    info.cooldown = newCooldown
                }
            }
        }

        // If a drone has turned itself on or off, find out why and fix it.
        if (powerSupplied != powerManager.currentPower) {
            powerStateChanged()
        }

        if (playDroneLaunchSound) {
            droneLaunchSound.play()
            playDroneLaunchSound = false
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
            crew.dealDamage(MedbayHealing(-0.4f * 16f * 0.2f * dt))
        }
    }

    /**
     * DroneSystem's swap (0x6394c0, from DroneControl::SwapArmaments 0x54d7f0): two entries of the
     * drone list trade places, deployed drones and power included.
     */
    fun swapSlots(from: Int, to: Int): Boolean {
        if (from !in drones.indices || to !in drones.indices || from == to || drones[from] == null) return false
        val first = drones[from]
        drones[from] = drones[to]
        drones[to] = first
        powerManager.powerStateChanged()
        ship.sys.shipUI.dronesSlotsChanged()
        ftl.browser.BrowserDebug.record("drones", "slots.swap", "{\"from\":$from,\"to\":$to}")
        return true
    }

    /**
     * Attempt to set the power status of the drone in the nth slot,
     * considering the system and reactor power limits.
     *
     * @return True if the given change was made.
     */
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

        // If we're allowed to deploy a drone, it only counts once
        val allowDeploy = allowDroneDeploy == info
        allowDroneDeploy = null

        // If the drone is already powered appropriately, nothing needs to be done.
        // This includes 'off' when the drone hasn't been spawned.
        val currentPower = info.instance?.isPowered ?: false
        if (currentPower == power)
            return

        var deployed = false

        if (power) {
            // If this drone attacks hostile ships but there isn't
            // one present, we can't turn it on.
            val hostileShip = ship.sys.enemy
            if (info.type.type.needsHostileShip && hostileShip == null) {
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

                info.instance = info.type.makeInstance()
                info.instance!!.init(ship)

                // We should only play the launch sound when unpaused, both
                // to match vanilla and to only play the sound once if multiple
                // drones are selected in the same update.
                playDroneLaunchSound = true
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

    class DroneInfo(val type: DroneBlueprint, var instance: AbstractDrone? = null) {
        /**
         * The cooldown (counting down) for how long until the player can
         * deploy this drone again, after it was destroyed.
         */
        var cooldown: Float? = null
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
            return drones.getOrNull(slot)?.type?.power ?: 0
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
