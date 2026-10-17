package ftl.browser

import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.game.*
import xyz.znix.xftl.sys.GameContainer

/** Real early-lesson effects/actions; encounter setup and full tutorial remain separate. */
object BrowserTutorialEntryDiagnostics {
    fun run(main: MainGame, container: GameContainer) {
        val profile = main.profile
        val profileXml = BrowserXml.write(profile.save())
        val storedProfile = BrowserFiles.getProfile()
        val run = BrowserFiles.getRun()
        val rng = BrowserRandom.snapshot()
        val mode = main.gameContent.enableAdvancedEdition
        val browserMode = BrowserFiles.enableAE
        try {
            main.switchToMainMenu()
            var state = main.startTutorialSession()
            val adapter = main.tutorialAdapter!!
            val controller = adapter.controller
            val effects = adapter.entryEffects
            fun tick() = main.update(container, 0f)
            fun next() { check(adapter.continueStep(state) != null) { "BrowserTutorialEntryDiagnostics.kt:25" } }
            fun unchanged(action: () -> Unit) {
                val before = BrowserXml.write(state.saveGameState())
                val beforeRng = BrowserRandom.snapshot()
                action()
                check(BrowserXml.write(state.saveGameState()) == before && BrowserRandom.snapshot() == beforeRng) {
                    "Rejected/repeated tutorial setup changed state or RNG"
                }
            }
            fun reload(step: String) {
                check(controller.step.id == step) { "BrowserTutorialEntryDiagnostics.kt:35" }
                // Loading performs a zero-time ship update. Settle derived
                // crew actions first: a depowered engine stops pilot manning,
                // and a damaged room starts repair on the next ship update.
                state.player.update(0f)
                val before = BrowserXml.write(state.saveGameState())
                check(main.doSaveLoadGame()) { "BrowserTutorialEntryDiagnostics.kt:41" }
                state = main.currentState as InGameState
                check(main.tutorialAdapter === adapter && adapter.entryEffects === effects) { "BrowserTutorialEntryDiagnostics.kt:43" }
                val after = BrowserXml.write(state.saveGameState())
                check(after == before) {
                    val difference = (0 until minOf(before.length, after.length)).firstOrNull { before[it] != after[it] } ?: minOf(before.length, after.length)
                    "Tutorial restore differs at $step / $difference: ${before.substring(maxOf(0, difference - 40), minOf(before.length, difference + 100))} -> ${after.substring(maxOf(0, difference - 40), minOf(after.length, difference + 100))}"
                }
                unchanged { check(!effects.apply(state, step)) { "BrowserTutorialEntryDiagnostics.kt:49" } }
            }
            repeat(90) {
                main.update(container, .05f)
                if (state.shipUI.isWindowOpen) {
                    BrowserEngine.key(49, '1'.code, true); BrowserEngine.step(0f)
                    BrowserEngine.key(49, '1'.code, false); BrowserEngine.step(0f)
                }
            }
            check(!state.shipUI.isWindowOpen && !state.debugFlags.noDmg.set) { "BrowserTutorialEntryDiagnostics.kt:58" }
            unchanged {
                var rejected = false
                try { effects.apply(state, "UNKNOWN") } catch (expected: IllegalArgumentException) { rejected = true }
                check(rejected) { "BrowserTutorialEntryDiagnostics.kt:62" }
                rejected = false
                try { effects.apply(state, "DAMAGE_SYSTEM_1") } catch (expected: IllegalStateException) { rejected = true }
                check(rejected && !effects.isApplied("DAMAGE_SYSTEM_1")) { "BrowserTutorialEntryDiagnostics.kt:65" }
            }
            BrowserEngine.diagnosticProgress("tutorial entry timing, power, damage and replacement save state")
            // The tutorial ship starts like any new ship: the engines at one bar (original tutorial, first frames).
            check(state.player.engines!!.powerSelected == 1 && state.player.health == 30) { "BrowserTutorialEntryDiagnostics.kt:68" }
            check(state.playerCrew.size == 1) { "BrowserTutorialEntryDiagnostics.kt:69" }
            repeat(4) { next(); check(state.player.engines!!.powerSelected == 1) { "BrowserTutorialEntryDiagnostics.kt:70" } }
            next()
            check(controller.step.id == "POWER_SYSTEM_1" && state.player.engines!!.powerSelected == 0) { "BrowserTutorialEntryDiagnostics.kt:72" }
            reload("POWER_SYSTEM_1")
            next(); check(controller.step.id == "POWER_SYSTEM_2" && !controller.canContinue) { "BrowserTutorialEntryDiagnostics.kt:74" }
            tick(); check(!controller.canContinue) { "BrowserTutorialEntryDiagnostics.kt:75" }
            state.player.engines!!.increasePower(); tick(); check(controller.canContinue) { "BrowserTutorialEntryDiagnostics.kt:76" }
            // A crew member in the damaged room receives the native base hull
            // contribution (30 HP), despite no extra personnel damage field.
            var crew = state.playerCrew.first() as LivingCrew
            crew.jumpTo(state.player.findSpaceForCrew(state.player.engines!!.room!!, AbstractCrew.SlotType.CREW))
            state.player.engines!!.room!!.updateCrewInRoom()
            val beforeCrewHealth = crew.health
            val shields = state.player.shields!!.activeShields
            next()
            check(controller.step.id == "DAMAGE_SYSTEM_1") { "BrowserTutorialEntryDiagnostics.kt:85" }
            check(state.player.health == 28 && state.player.engines!!.damagedEnergyLevels == 2) { "BrowserTutorialEntryDiagnostics.kt:86" }
            check(crew.health == beforeCrewHealth - 30f && state.player.shields!!.activeShields == shields) { "BrowserTutorialEntryDiagnostics.kt:87" }
            check(state.player.rooms.all { r -> r.fires.all { it == null } && r.breaches.all { it == null } }) { "BrowserTutorialEntryDiagnostics.kt:88" }
            check(state.player.engines!!.ionTimer == 0f) { "BrowserTutorialEntryDiagnostics.kt:89" }
            reload("DAMAGE_SYSTEM_1")
            val stale = state
            check(main.doSaveLoadGame()) { "BrowserTutorialEntryDiagnostics.kt:92" }; state = main.currentState as InGameState
            unchanged {
                var rejected = false
                try { effects.apply(stale, "DAMAGE_SYSTEM_1") } catch (expected: IllegalStateException) { rejected = true }
                check(rejected) { "BrowserTutorialEntryDiagnostics.kt:96" }
            }
            BrowserEngine.diagnosticProgress("tutorial two localized crew, room 1 placement, gender and recruitment idempotence")
            val recruited = main.profile.browserLifetimeCrew
            next()
            check(controller.step.id == "DAMAGE_SYSTEM_2" && state.playerCrew.size == 3) { "BrowserTutorialEntryDiagnostics.kt:101" }
            val added = state.playerCrew.filterIsInstance<LivingCrew>().takeLast(2)
            check(added.map { it.info.name } == listOf(state.translator["tutorial_extra_crew_1"], state.translator["tutorial_extra_crew_2"])) { "BrowserTutorialEntryDiagnostics.kt:103" }
            check(added.all { it.info.race.name == "human" && it.room.id == 1 } && !added[0].info.isFemale && added[1].info.isFemale) { "BrowserTutorialEntryDiagnostics.kt:104" }
            check(added[0].standingPosition != added[1].standingPosition && main.profile.browserLifetimeCrew == recruited) { "BrowserTutorialEntryDiagnostics.kt:105" }
            reload("DAMAGE_SYSTEM_2")
            check(state.playerCrew.size == 3 && main.profile.browserLifetimeCrew == recruited) { "BrowserTutorialEntryDiagnostics.kt:107" }
            state.shipUI.hotkeyPressed(state.hotkeyManager.byID.getValue(VanillaHotkeys.SELECT_CREW.first()), container.input)
            tick(); check(controller.step.id == "DAMAGE_SYSTEM_3") { "BrowserTutorialEntryDiagnostics.kt:109" }
            tick(); check(controller.step.id == "DAMAGE_SYSTEM_4" && !controller.canContinue) { "BrowserTutorialEntryDiagnostics.kt:110" }
            repeat(1200) { if (state.player.engines!!.damaged) state.updateGameState(.05f) }
            check(!state.player.engines!!.damaged && state.player.health == 28) { "BrowserTutorialEntryDiagnostics.kt:112" }
            tick(); check(controller.canContinue) { "BrowserTutorialEntryDiagnostics.kt:113" }
            next(); next()
            check(controller.step.id == "DAMAGE_SYSTEM_5_5") { "BrowserTutorialEntryDiagnostics.kt:115" }
            BrowserEngine.diagnosticProgress("tutorial random fire attempts, duplicate tiles, venting and save idempotence")
            var room = state.player.rooms.single { it.id == 9 }
            room.doors.filter { it.isAirlock }.forEach { it.open = false }
            check(room.fires.all { it == null }) { "BrowserTutorialEntryDiagnostics.kt:119" }
            // Exercise a collision: native samples cells with replacement,
            // rather than forcing two distinct fires.
            var expectedSlot = -1
            var seed = 1
            while (expectedSlot < 0 && seed < 200) {
                BrowserRandom.restore((seed++).toString())
                val first = room.fires.indices.random(BrowserRandom)
                if (first == room.fires.indices.random(BrowserRandom)) expectedSlot = first
            }
            check(expectedSlot >= 0) { "BrowserTutorialEntryDiagnostics.kt:129" }
            BrowserRandom.restore((seed - 1).toString())
            next()
            check(controller.step.id == "FIRE_PC_1" && !controller.canContinue) { "BrowserTutorialEntryDiagnostics.kt:132" }
            check(room.fires.count { it != null } == 1 && room.fires[expectedSlot] != null) { "BrowserTutorialEntryDiagnostics.kt:133" }
            room.fires[expectedSlot]!!.health = .5f
            reload("FIRE_PC_1")
            room = state.player.rooms.single { it.id == 9 }
            check(room.fires[expectedSlot]!!.health == .5f) { "Repeated setup refreshed an existing fire" }
            room.doors.first { it.isAirlock }.open = true; tick()
            check(controller.step.id == "FIRE_END") { "BrowserTutorialEntryDiagnostics.kt:139" }
            repeat(1600) { if (room.fires.any { it != null }) state.updateGameState(.05f) }
            check(room.fires.all { it == null }) { "Actual tutorial venting did not extinguish the fire" }
            tick(); check(controller.canContinue) { "BrowserTutorialEntryDiagnostics.kt:142" }
            check(BrowserFiles.getRun() == run && BrowserFiles.getProfile() == storedProfile) { "BrowserTutorialEntryDiagnostics.kt:143" }
            main.endTutorialSession()
            check(main.profile === profile && BrowserXml.write(profile.save()) == profileXml) { "BrowserTutorialEntryDiagnostics.kt:145" }
            check(BrowserRandom.snapshot() == rng && main.gameContent.enableAdvancedEdition == mode) { "BrowserTutorialEntryDiagnostics.kt:146" }
            val ordinaryRng = BrowserRandom.snapshot()
            var rejected = false
            try { effects.apply(state, "FIRE_PC_1") } catch (expected: IllegalStateException) { rejected = true }
            check(rejected && BrowserRandom.snapshot() == ordinaryRng) { "BrowserTutorialEntryDiagnostics.kt:150" }
            // Restart creates new effect history, rather than retaining the
            // previous tutorial's applied-step set.
            state = main.startTutorialSession()
            val fresh = main.tutorialAdapter!!
            check(fresh.entryEffects !== effects && BrowserTutorialEntryEffects.SUPPORTED_STEPS.none { fresh.entryEffects.isApplied(it) }) { "BrowserTutorialEntryDiagnostics.kt:155" }
            // The native start event is modal, including on a fresh restart.
            // Instruction Continue cannot skip its event choices.
            check(state.shipUI.tutorialDialogueOpen && fresh.continueStep(state) == null) { "BrowserTutorialEntryDiagnostics.kt:158" }
            repeat(90) {
                main.update(container, .05f)
                if (state.shipUI.tutorialDialogueOpen) {
                    BrowserEngine.key(49, '1'.code, true); BrowserEngine.step(0f)
                    BrowserEngine.key(49, '1'.code, false); BrowserEngine.step(0f)
                }
            }
            check(!state.shipUI.tutorialDialogueOpen) { "BrowserTutorialEntryDiagnostics.kt:166" }
            repeat(5) { check(fresh.continueStep(state) != null) { "BrowserTutorialEntryDiagnostics.kt:167" } }
            check(fresh.controller.step.id == "POWER_SYSTEM_1" && state.player.engines!!.powerSelected == 0) { "BrowserTutorialEntryDiagnostics.kt:168" }
        } finally {
            main.endTutorialSession()
            BrowserFiles.hydrate(run, storedProfile)
            BrowserFiles.enableAE = browserMode
            BrowserRandom.restore(rng)
        }
    }
}
