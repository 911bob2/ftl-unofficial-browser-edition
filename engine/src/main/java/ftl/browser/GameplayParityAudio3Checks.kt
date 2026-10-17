package ftl.browser

import xyz.znix.xftl.environment.AsbEnvironment
import xyz.znix.xftl.environment.NebulaEnvironment
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.GameOverWindow
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.game.RealSoundManager
import xyz.znix.xftl.sys.GameContainer

/**
 * Sound and music rules of the original (round 4 audio batch), checked with the
 * sound manager's trace sink: which sound an event plays, and when.
 */
internal object GameplayParityAudio3Checks {
    fun run(main: MainGame, container: GameContainer): String {
        val sounds = main.gameContent.sounds as? RealSoundManager ?: return "audio: no sound manager"
        val log = ArrayList<Pair<Float, String>>()
        var clock = 0f
        sounds.traceSink = { kind, name -> log += clock to "$kind $name" }
        fun frames(state: InGameState?, seconds: Float) {
            repeat((seconds / .05f).toInt()) {
                main.update(container, .05f)
                clock += .05f
            }
        }
        fun played(name: String) = log.count { it.second == "play $name" }
        val parts = ArrayList<String>()
        try {
            // A new run is silent: no arrival or engine sounds (U30); the drive fills when the opening event
            // closes, ringing jumpReady once (native sound trace r5-snd: Kestrel A and Lanius A).
            var state = BrowserGameplayParityDiagnostics.fresh(main, container)
            check(played("jumpArrive") == 0 && played("enginesOn") == 0 && played("jumpReady") == 1) {
                "new run played ${log.filter { it.second.startsWith("play jump") || it.second.startsWith("play engines") }}"
            }
            // Continuing it rings jumpReady once, with no arrival sound.
            log.clear()
            state = BrowserGameplayParityDiagnostics.reload(main, state)
            frames(state, 1f)
            check(played("jumpReady") == 1 && played("jumpArrive") == 0) { "continue played ${log.map { it.second }}" }
            parts += "start"

            // Engines off: enginesOff once when the dodge factor drops to 0, nothing when it returns (U30).
            log.clear()
            val engines = state.player.engines!!
            val power = engines.powerSelected
            repeat(power) { engines.decreasePower() }
            frames(state, .5f)
            repeat(power) { engines.increasePower() }
            frames(state, .5f)
            check(played("enginesOff") == 1 && played("enginesOn") == 0) { "engine power cycle played ${log.map { it.second }}" }
            parts += "engines"

            // weaponReady when a weapon finishes charging and has nothing to fire at (U25).
            log.clear()
            val weapon = state.player.hardpoints.firstNotNullOf { it.weapon }
            state.player.weapons!!.setWeaponPower(weapon, true)
            frames(state, weapon.chargeTime + 1f)
            check(played("weaponReady") == 1) { "weapon charge played ${log.map { it.second }}" }
            parts += "weaponReady"

            // batteryStop when the battery runs out (U17).
            state = BrowserGameplayParityDiagnostics.fresh(main, container, "PLAYER_SHIP_ENERGY")
            state.player.rooms.filter { it.system == null }.forEach { room -> room.systemSlots.firstOrNull()?.let { room.setSystem(it) } }
            val battery = state.player.backupBattery!!
            log.clear()
            battery.startBattery()
            frames(state, 31f)
            check(played("batteryStart") == 1 && played("batteryStop") == 1) { "battery played ${log.map { it.second }}" }
            parts += "battery"

            // The distress beacon loop runs while it is on and the ship has no fuel,
            // paused or not, and stops with the toggle (U31).
            log.clear()
            state.player.fuelCount = 0
            state.fuelDistressBeacon = true
            frames(state, 2f)
            state.toggleQuickPause()
            frames(state, 1f)
            state.toggleQuickPause()
            state.fuelDistressBeacon = false
            frames(state, .2f)
            check(log.map { it.second }.filter { it.endsWith("distress") } == listOf("loop+ distress", "loop- distress")) {
                "distress played ${log.map { it.second }}"
            }
            // The toggle is saved.
            state.fuelDistressBeacon = true
            state = BrowserGameplayParityDiagnostics.reload(main, state)
            check(state.fuelDistressBeacon) { "distress beacon not saved" }
            state.fuelDistressBeacon = false
            state.player.fuelCount = 10
            parts += "distress"

            // Ship destruction: small explosions for 1.5 s, then explosion1 (U27).
            state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 0, 71)
            frames(state, .2f)
            log.clear()
            clock = 0f
            val dying = state.enemy!!
            dying.health = 0
            // Until the wreck is gone (the 3 s ExplosionAnimation), the dying ship is updated every frame.
            for (frame in 0 until 160) {
                if (state.enemy !== dying) break
                main.update(container, .05f)
                clock += .05f
            }
            val blasts = log.filter { it.second.startsWith("play explosion") }
            check(blasts.size in 3..7 && blasts.last().second == "play explosion1" && blasts.first().first < .1f) { "destruction played $blasts" }
            parts += "destruction"

            // Nebula ambience at a nebula, lightning in a storm (U26).
            state = BrowserGameplayParityDiagnostics.fresh(main, container)
            // The fresh run's start beacon can already be a nebula, whose loop then just carries on.
            val nebulaPlaying = state.currentBeacon.environmentType.isNebula
            log.clear()
            state.currentBeacon.debugSetEnvironment(NebulaEnvironment(state, state.currentBeacon, true))
            frames(state, 20f)
            val nebulaLoop = log.count { it.second == "loop+ nebula" } == 1 ||
                (nebulaPlaying && log.none { it.second.startsWith("loop") && it.second.endsWith(" nebula") })
            check(nebulaLoop && played("lightning1") + played("lightning2") +
                    played("lightning3") + played("lightning4") >= 3) { "storm played ${log.map { it.second }.distinct()}" }
            parts += "storm"

            // Fleet / ASB volleys: each queued shot plays fleetFireLight or fleetFireHeavy (U26).
            log.clear()
            state.currentBeacon.debugSetEnvironment(AsbEnvironment(state, state.currentBeacon, 0))
            frames(state, 6f)
            check(played("fleetFireLight") + played("fleetFireHeavy") >= 1) { "ASB played ${log.map { it.second }.distinct()}" }
            parts += "fleetFire"

            // Playlists (U13): a new list waits 2 s after the old one fades, the same
            // list changes nothing, and a list starting with the current track keeps it.
            val title = sounds.getTrack("title")
            val civilian = sounds.getTrack("civilian")
            val cosmos = sounds.getTrack("cosmos")
            sounds.switchMusicList(listOf(title))
            sounds.updateMusic(3f)
            log.clear()
            clock = 0f
            sounds.switchMusicList(listOf(civilian, cosmos))
            sounds.updateMusic(1.9f)
            check(log.none { it.second.startsWith("track") }) { "track started early: $log" }
            sounds.updateMusic(.2f)
            check(log.count { it.second.startsWith("track civilian") && it.second.endsWith("volume=1.0") } == 1) { "playlist switch: $log" }
            log.clear()
            sounds.switchMusicList(listOf(civilian, cosmos))
            sounds.switchMusicList(listOf(civilian, title))
            check(log.none { it.second.startsWith("track") }) { "current track restarted: $log" }
            parts += "playlists"

            // Victory: the title playlist 12.5 s after the window opens (U32).
            log.clear()
            val window = GameOverWindow(state, GameOverWindow.Outcome.WIN)
            window.tick(12.4f)
            check(log.none { it.second.startsWith("playlist") }) { "title music before 12.5 s" }
            window.tick(.2f)
            check(log.count { it.second == "playlist title" } == 1) { "victory music: $log" }
            parts += "victory"
        } finally {
            sounds.traceSink = null
        }
        return "audio: " + parts.joinToString(", ")
    }
}
