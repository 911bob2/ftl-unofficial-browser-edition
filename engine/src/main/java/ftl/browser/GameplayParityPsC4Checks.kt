package ftl.browser

import org.jdom2.Element
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.crew.CrewBlueprint
import xyz.znix.xftl.crew.LivingCrewInfo
import xyz.znix.xftl.drones.AbstractIndoorsDrone
import xyz.znix.xftl.drones.BoardingDrone
import xyz.znix.xftl.drones.DefenceDrone
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.systems.Drones
import xyz.znix.xftl.weapons.DroneBlueprint

/**
 * Parity sweep C4 (drones): the defence drone target types (DefenseDrone::ValidTargetObject 0x421020 and Hyperspace's
 * hook), the ion intruder's pulse as an area hit (IonDrone::GetRoomDamage 1.6.9 0x4374b0), the player's boarding
 * drone at the end of a fight (DroneSystem::OnLoop 0x639a50, Hyperspace's BOARDER_RECOVERY) and the crew drone light
 * colours (CrewDrone::OnRender 0x4410a0).
 */
internal object GameplayParityPsC4Checks {
    fun run(main: MainGame, container: GameContainer): String {
        val rng = BrowserRandom.snapshot()
        val nativeRng = NativeRandom.snapshot()
        try {
            val targets = targetTypes()
            val ion = ionPulse(main, container)
            val boarder = boarderFightEnd(main, container)
            val lights = lights()
            val ability = abilityParse()
            return "ps-c4 ok: $targets, $ion, $boarder, $lights, $ability"
        } finally {
            BrowserRandom.restore(rng)
            NativeRandom.restore(nativeRng)
        }
    }

    private fun drone(vararg targets: String): DroneBlueprint {
        val e = Element("droneBlueprint").setAttribute("name", "PS_C4_DEFENCE")
        e.addContent(Element("type").setText("DEFENSE"))
        for (t in targets) e.addContent(Element("target").setText(t))
        return DroneBlueprint(e)
    }

    /**
     * BlueprintManager::ProcessDroneBlueprint: LASERS 4, DRONES 3, anything else 1; Hyperspace's hook goes through
     * every `<target>`: ASTEROIDS 2, SOLID 5, ALL 6 (O-M2-3). ValidTargetObject per type and Targetable class
     * (1 missile, 2 asteroid, 3 drone, 4 laser, 5 other, 6 pod).
     */
    private fun targetTypes(): String {
        val hs = xyz.znix.xftl.hs.HS.active
        val expect = linkedMapOf(
            arrayOf<String>() to 1, arrayOf("LASERS") to 4, arrayOf("DRONES") to 3,
            arrayOf("ASTEROIDS") to (if (hs) 2 else 1), arrayOf("SOLID") to (if (hs) 5 else 1), arrayOf("ALL") to (if (hs) 6 else 1),
            arrayOf("LASERS", "SOLID") to (if (hs) 5 else 4),
        )
        for ((targets, type) in expect) {
            val got = drone(*targets).targetType
            check(got == type) { "targetType of ${targets.toList()} is $got, expected $type" }
        }
        // Classes each type accepts, in class order 1..6.
        val accepts = mapOf(
            1 to "110001", 4 to "110101", 3 to "001001",
            2 to "010000", 5 to "111001", 6 to "111101",
        )
        for ((type, row) in accepts) {
            val got = (1..6).joinToString("") { if (DefenceDrone.validTargetClass(type, it)) "1" else "0" }
            check(got == row) { "Target type $type accepts classes $got, expected $row" }
        }
        return "defence target types (${if (hs) "Hyperspace" else "vanilla"})"
    }

    /** The ion intruder's pulse: 3 ion to the room's system and a 6 s stun to every crew member not of its ship. */
    private fun ionPulse(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val player = state.player
        val room = player.rooms.first { it.system != null && it.system!!.ionTimer == 0f && it.cellCount >= 2 }
        val crew = player.addCrewMemberInRoom(LivingCrewInfo.generateRandom(state.blueprintManager["human"] as CrewBlueprint, state), room, false)
        room.updateCrewInRoom()
        check(crew in room.crew) { "Fixture crew member is not in the room" }
        state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 4, 71)
        val enemy = state.enemy!!

        // The player's own drone: its crew are not stunned, the system still takes the ion.
        player.damage(room, BoardingDrone.ionPulseDamage(player))
        check(crew.stunTime == 0f) { "The pulse stunned crew of the drone's own ship (${crew.stunTime})" }
        val ionAfterOwn = room.system!!.ionTimer
        check(ionAfterOwn == 15f) { "Three ion should lock the system for 15 s, got $ionAfterOwn" }

        // The enemy's drone: the crew is stunned for six seconds.
        room.system!!.ionTimer = 0f
        player.damage(room, BoardingDrone.ionPulseDamage(enemy))
        check(crew.stunTime == 6f) { "The pulse stunned the crew for ${crew.stunTime} s, expected 6" }
        return "ion pulse as an area hit"
    }

    /**
     * The player's boarding drone when the fight ends with the other ship still there: undeployed and depowered,
     * not destroyed (no rebuild time); under Hyperspace with BOARDER_RECOVERY the part comes back.
     */
    private fun boarderFightEnd(main: MainGame, container: GameContainer): String {
        val results = ArrayList<String>()
        for (landed in listOf(false, true)) {
            val state = BrowserGameplayParityDiagnostics.fresh(main, container, "PLAYER_SHIP_CIRCLE")
            state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 4, 71)
            val enemy = state.enemy!!
            if (enemy.crew.none { it.health > 0f && it.mode == AbstractCrew.SlotType.CREW })
                enemy.addCrewMember(LivingCrewInfo.generateRandom(state.blueprintManager["human"] as CrewBlueprint, state), false)
            val player = state.player
            // Multiverse's ships carry BOARDER_RECOVERY in the hidden FOR_MULTIVERSE augment.
            val mv = state.blueprintManager.getOrNull("FOR_MULTIVERSE") != null
            if (mv) { player.hs!!.hiddenAugs.merge("FOR_MULTIVERSE", 1, Int::plus); player.hs!!.augListCache = null }
            val drones = player.drones!!
            drones.drones.indices.forEach { drones.setDronePower(it, false); drones.drones[it]?.instance?.removeInstance() }
            player.purchasedReactorPower = 25
            drones.energyLevels = 8
            player.updateAvailablePower()
            player.dronesCount = 5
            val info = Drones.DroneInfo(state.blueprintManager["BOARDER"] as DroneBlueprint)
            drones.drones[0] = info
            check(drones.setDronePower(0, true)) { "The boarding drone did not launch" }
            check(player.dronesCount == 4) { "The launch took ${5 - player.dronesCount} parts" }
            val instance = info.instance as BoardingDrone
            if (landed) {
                state.debugFlags.noCrewDamage.set = true
                repeat(600) { if (instance.pawn == null) state.updateGameState(.05f) }
                state.debugFlags.noCrewDamage.set = false
                checkNotNull(instance.pawn) { "The boarding drone did not land" }
            }
            state.browserEndHostility()
            repeat(2) { state.updateGameState(.05f) }
            val recovery = xyz.znix.xftl.hs.HS.active && player.hasAugment("BOARDER_RECOVERY")
            check(recovery == mv) { "BOARDER_RECOVERY is $recovery with Multiverse $mv" }
            check(info.instance == null && !info.deployed) { "The boarding drone stayed out (landed $landed)" }
            check(info.cooldown == null) { "The boarding drone got a rebuild time ${info.cooldown} (landed $landed)" }
            check(!enemy.crew.any { it is AbstractIndoorsDrone.Pawn }) { "The drone body stayed aboard (landed $landed)" }
            val parts = if (recovery) 5 else 4
            check(player.dronesCount == parts) { "Drone parts ${player.dronesCount}, expected $parts (landed $landed)" }
            results += if (landed) "landed" else "in flight"
            if (recovery) results += "recovered"
        }
        return "boarding drone at the end of a fight (${results.joinToString(", ")})"
    }

    private fun lights(): String {
        val p = AbstractIndoorsDrone.DRONE_LIGHT_PLAYER
        val e = AbstractIndoorsDrone.DRONE_LIGHT_ENEMY
        check(Math.round(p.r * 255) == 100 && Math.round(p.g * 255) == 255 && Math.round(p.b * 255) == 100) { "Player drone light" }
        check(Math.round(e.r * 255) == 255 && Math.round(e.g * 255) == 50 && Math.round(e.b * 255) == 50) { "Enemy drone light" }
        return "drone light colours"
    }

    /** CustomDroneManager::ParseDroneNode: `<ability ai>` is read; the last crewBlueprint and tooltipName win (O-M2-3). */
    private fun abilityParse(): String {
        val d = Element("drone").setAttribute("name", "PS_C4_ABILITY")
        d.addContent(Element("crewBlueprint").setText("first"))
        d.addContent(Element("crewBlueprint").setText("roomba"))
        d.addContent(Element("ability").setAttribute("ai", "heal"))
        val def = checkNotNull(xyz.znix.xftl.hs.weapons.HsDrones.parseDrone(d))
        check(def.crewBlueprint == "roomba" && def.hasAbility && def.abilityAi == "heal") { "Ability drone parsed as $def" }
        val plain = checkNotNull(xyz.znix.xftl.hs.weapons.HsDrones.parseDrone(Element("drone").setAttribute("name", "X")))
        check(!plain.hasAbility && plain.abilityAi == null) { "A drone without <ability> has one" }
        val odd = Element("drone").setAttribute("name", "Y").addContent(Element("ability").setAttribute("ai", "nonsense"))
        check(xyz.znix.xftl.hs.weapons.HsDrones.parseDrone(odd)!!.let { it.hasAbility && it.abilityAi == null }) { "Unknown ability ai" }
        return "drone ability parse"
    }
}
