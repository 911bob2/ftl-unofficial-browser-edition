package xyz.znix.xftl.systems

import xyz.znix.xftl.AbstractSystem
import xyz.znix.xftl.ai.nativeSystemId
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.game.InGameState

/** The room layouts' console glow (data/rooms.xml, FTL's RoomLayout table) and which of its three images a system shows. */
object ConsoleGlow {
    class Glow(@JvmField val name: String, @JvmField val x: Int, @JvmField val y: Int, @JvmField val dir: String)

    private var table: Map<String, Glow>? = null
    private var tableOf: InGameState? = null

    private fun load(game: InGameState): Map<String, Glow> {
        if (tableOf === game) table?.let { return it }
        return parse(game.datafile).also { table = it; tableOf = game }
    }

    private var hangarTable: Map<String, Glow>? = null
    private var hangarTableOf: xyz.znix.xftl.Datafile? = null

    /** The glow of a room image path in the hangar, which has no [InGameState]. */
    fun of(df: xyz.znix.xftl.Datafile, interiorImage: String): Glow? {
        val m = if (hangarTableOf === df) hangarTable!! else parse(df).also { hangarTable = it; hangarTableOf = df }
        return m[interiorImage.substringAfterLast('/').removeSuffix(".png").removePrefix("room_")]
    }

    private fun parse(df: xyz.znix.xftl.Datafile): Map<String, Glow> {
        val m = HashMap<String, Glow>()
        val file = df.getOrNull("data/rooms.xml")
        if (file != null) {
            for (layout in df.parseXML(file).rootElement.getChildren("roomLayout")) {
                val name = layout.getAttributeValue("name") ?: continue
                val glow = layout.getChild("computerGlow") ?: continue
                m[name] = Glow(glow.getAttributeValue("name") ?: "glow", glow.getAttributeValue("x")?.toIntOrNull() ?: 0,
                    glow.getAttributeValue("y")?.toIntOrNull() ?: 0, glow.getAttributeValue("dir") ?: "DOWN")
            }
        }
        return m
    }

    /** The glow of the room image [system] draws: `ship/interior/room_<layout>.png`. */
    fun of(game: InGameState, system: AbstractSystem): Glow? {
        val path = system.configuration.spec.interiorImage ?: return null
        return load(game)[path.substringAfterLast('/').removeSuffix(".png").removePrefix("room_")]
    }

    private fun boostable(s: AbstractSystem, id: Int) = s.hsState?.bBoostable ?: when (id) {
        0, 1, 3, 6, 7, 8 -> true
        11 -> s.room?.ship?.isPlayerShip != true
        else -> false
    }

    /** ShipSystem::OnRenderFloor's computerLevel: 0 nothing lit, 1 the console on, 2 and 3 its crew member's skill. */
    fun level(s: AbstractSystem): Int {
        val id = nativeSystemId(s)
        if (!boostable(s, id)) return 0
        // Doors stay dark; the sensors' console shows its light (hs-gaps cr frames).
        if (id != 7 && (s.hsState?.bLevelBoostable ?: (id == 8))) return 0
        if (s.damagedEnergyLevels != 0 || blocked(s)) return 0
        val manned = xyz.znix.xftl.hs.systems.HsSystemsLua.activeManned(s)
        return if (manned == 2 || manned == 3) manned else 1
    }

    /** ShipSystem::BlockedBoosted(true) for a system that is not level boostable. */
    private fun blocked(s: AbstractSystem): Boolean {
        if ((s.scriptedPowerLimit ?: Int.MAX_VALUE) < s.energyLevels) return true
        val room = s.room ?: return true
        if (room.ship.crew.any { it.room === room && it is LivingCrew && it.mode == AbstractCrew.SlotType.INTRUDER && it.health > 0f }) return true
        if (room.fires.any { it != null }) return true
        if (s.ionTimer > 0f || s.isHackActive) return true
        if (xyz.znix.xftl.hs.systems.HsSystemsLua.lockCount(s) != 0) return true
        if (s.scriptedPowerLoss >= 1) return true
        return when (s) {
            is MainSystem -> s.powerSupplied <= 0
            is SubSystem -> s.effectivePower <= 0
            else -> true
        }
    }
}
