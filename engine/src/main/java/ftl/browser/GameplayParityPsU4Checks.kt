package ftl.browser

import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.hs.crew.addCrewMemberFromString
import xyz.znix.xftl.game.RealSoundManager
import xyz.znix.xftl.sys.GameContainer

/**
 * Parity sweep U4 (audio and music), vanilla and modded: SoundControl's rules for one-shot volumes, loops sliding
 * out, names the files lack and the playlist, checked through the sound manager's trace.
 */
internal object GameplayParityPsU4Checks {
    fun run(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val sounds = state.content.sounds as? RealSoundManager ?: return "ps-u4: no sound manager"
        val log = ArrayList<String>()
        sounds.traceSink = { kind, name -> log += "$kind $name" }
        val parts = ArrayList<String>()
        try {
            // PlaySoundMix (0x5d5160) with a volume sets the channel's volume itself: explosion1 (sounds.xml volume 6)
            // at 0.25 plays at 0.25, not 0.25 x 0.6 (Ship::OnLoop's destruction; native sound trace u4-combat).
            val boom = sounds.getSample("explosion1")
            val quiet = sounds.diagnosticChannelVolume(boom.playAt(.25f))
            val own = sounds.diagnosticChannelVolume(boom.play())
            check(quiet == null || (kotlin.math.abs(quiet - .25f) < 1e-4f && own != null && own < 1f)) { "playAt 0.25: $quiet, own $own" }
            parts += "playAt"

            // UpdateSoundLoop (0x5d5800) with 0 slides the loop out over 500 ms of real time; the loop restarts at once.
            val fire = sounds.getLoop("fire")
            log.clear()
            repeat(3) { fire.continueLoopAnyShip(); sounds.updateLoopedSounds(false) }
            sounds.updateLoopedSounds(false)
            check(log.filter { it.endsWith(" fire") } == listOf("loop+ fire", "loop- fire")) { "fire loop: $log" }
            check(sounds.diagnosticFadingLoops >= 1) { "the fire loop stopped without sliding out" }
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < 560) sounds.updateLoopedSounds(false)
            check(sounds.diagnosticFadingLoops == 0) { "a loop still slides after 560 ms" }
            parts += "loop slide-out"

            // Names the files lack play nothing and stop nothing: PlaySoundMix's "Sound name not found", a loop that
            // never starts, and a track that leaves the playlist silent where it is ("Bad track name", 0x5d0210).
            log.clear()
            sounds.getSample("psU4NoSuchSound").play()
            sounds.getSample("psU4NoSuchSound").playAt(.5f)
            repeat(3) { sounds.getLoop("psU4NoSuchLoop").continueLoopAnyShip(); sounds.updateLoopedSounds(false) }
            check(log.filter { "psU4" in it } == listOf("play psU4NoSuchSound", "play psU4NoSuchSound")) { "missing sounds: $log" }
            val civilian = sounds.getTrack("civilian")
            sounds.switchMusicList(listOf(civilian))
            sounds.updateMusic(3f)
            log.clear()
            sounds.switchMusicList(listOf(sounds.getTrack("psU4NoSuchTrack"), civilian))
            repeat(60) { sounds.updateMusic(.25f) }
            check(log.filter { it.startsWith("playlist") || it.startsWith("track") } ==
                listOf("playlist psU4NoSuchTrack,civilian", "track psU4NoSuchTrack missing")) { "missing track: $log" }
            parts += "missing names"

            // An empty list slides the music out and leaves silence.
            sounds.switchMusicList(listOf(civilian))
            sounds.updateMusic(3f)
            log.clear()
            sounds.switchMusicList(emptyList())
            repeat(20) { sounds.updateMusic(.25f) }
            check(log.filter { it.startsWith("playlist") || it.startsWith("track") } == listOf("playlist ") &&
                sounds.currentMusic == null) { "empty list: $log" }

            // The battle flag changes with no track playing (0x5d1140 sets it first): the next list starts in battle.
            sounds.stopPlaylist(0f)
            sounds.setCombatMusic(false)
            log.clear()
            sounds.setCombatMusic(true)
            sounds.switchMusicList(listOf(civilian))
            check(log.filter { !it.startsWith("loop") && !it.startsWith("play ") } ==
                listOf("combat on", "playlist civilian", "track civilian combat=true volume=1.0")) { "combat flag: $log" }
            sounds.setCombatMusic(false)
            parts += "playlist edges"

            // Doors: Door init (0x46eb40) gives the player's doorAnim soundForward "doorOpen" and soundReverse
            // "doorClose"; Animation::Start (0x5bbc90) plays the first from fully closed and the reverse start
            // (0x5bbd90) the second from fully open. So the sound comes as the door starts to move, and a door turned
            // back halfway is silent until it closes from fully open again.
            val door = state.player.doors.first { !it.isAirlock }
            val doorWasOpen = door.open
            try {
                door.open = false
                door.loadSavedOpen(false)
                log.clear()
                door.open = true
                door.stepAnimation(.01f)
                val atStart = log.filter { "door" in it }
                repeat(30) { door.stepAnimation(.02f) }
                door.open = false
                door.stepAnimation(.05f)
                door.open = true
                repeat(30) { door.stepAnimation(.02f) }
                door.open = false
                repeat(30) { door.stepAnimation(.02f) }
                val doorLog = log.filter { "door" in it }
                check(atStart == listOf("play doorOpen") &&
                    doorLog == listOf("play doorOpen", "play doorClose", "play doorClose")) { "door sounds: $log" }
            } finally {
                door.open = doorWasOpen
                door.loadSavedOpen(doorWasOpen)
            }
            parts += "door sounds"

            // Hyperspace crew powers (CrewAbilities.cpp; HS oracle sound trace u4hs): ActivatePower's effect sound and
            // PreparePower's power sound at the use (in that order), the temporary power's finish sound when it ends.
            if (BrowserGameplayParityDiagnostics.modded && xyz.znix.xftl.hs.crew.CustomCrewManager.isRace("unique_tully")) {
                val c = xyz.znix.xftl.hs.crew.HsCrew.addCrewMemberFromString(state.player, "U4 power", "unique_tully", false, 0, true, true)
                try {
                    val p = xyz.znix.xftl.hs.crew.HsCrew.ex(c).crewPowers.first()
                    p.def = xyz.znix.xftl.hs.crew.ActivatedPowerDefinition().also { d ->
                        d.name = "PSU4_PW"
                        d.cooldown = 999f
                        d.sounds = arrayListOf("powerUpSystem")
                        d.effectSounds = arrayListOf("upgradeSystem")
                        d.hasTemporaryPower = true
                        d.tempPower = xyz.znix.xftl.hs.crew.TemporaryPowerDefinition().also { t ->
                            t.duration = 2f
                            t.sounds = arrayListOf("downgradeSystem")
                        }
                    }
                    log.clear()
                    p.preparePower()
                    repeat(60) { main.update(container, .05f) }
                    val powerLog = log.filter { it == "play powerUpSystem" || it == "play upgradeSystem" || it == "play downgradeSystem" }
                    check(powerLog == listOf("play upgradeSystem", "play powerUpSystem", "play downgradeSystem")) { "power sounds: $log" }
                } finally {
                    c.removeFromShip()
                }
                parts += "crew power sounds"
            }
        } finally {
            sounds.traceSink = null
            sounds.stopPlaylist(0f)
        }
        return "ps-u4 audio: " + parts.joinToString(", ")
    }
}
