package ftl.browser

import org.jdom2.Element
import xyz.znix.xftl.crew.*
import xyz.znix.xftl.game.*
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.sys.Input

object BrowserCrewRecordDiagnostics {
    fun run(main: MainGame, container: GameContainer, graphics: Graphics) {
        val profile = main.profile
        fun reset(): InGameState {
            profile.load(SaveProfile.createBlank().save())
            main.startDiagnosticGame("PLAYER_SHIP_HARD", Difficulty.NORMAL)
            repeat(90) {
                main.update(container, .05f)
                if ((main.currentState as InGameState).shipUI.isWindowOpen) {
                    BrowserEngine.key(49, '1'.code, true); BrowserEngine.step(0f)
                    BrowserEngine.key(49, '1'.code, false); BrowserEngine.step(0f)
                }
            }
            return (main.currentState as InGameState).also { it.debugFlags.noEnemyFire.set = true }
        }
        fun reload(state: InGameState): InGameState {
            main.loadSavedGame(BrowserXml.parse(BrowserXml.write(state.saveGameState())))
            return (main.currentState as InGameState).also { it.debugFlags.noEnemyFire.set = true }
        }
        BrowserEngine.diagnosticProgress("crew records repair and ties")
        var state = reset()
        var engineer = state.player.engines!!.manningCrew!!
        engineer.info.name = "First record"
        state.player.engines!!.damagedEnergyLevels = 1
        repeat(130) { engineer.update(.05f) }
        check(engineer.info.browserCounters[CrewRecordKind.REPAIRS] == 0) { "BrowserCrewRecordDiagnostics.kt:36" }
        state = reload(state); engineer = state.player.crew.filterIsInstance<LivingCrew>().first { it.info.name == "First record" }
        repeat(400) { if (state.player.engines!!.damaged) engineer.update(.05f) }
        check(engineer.info.browserCounters[CrewRecordKind.REPAIRS] == 1) { "BrowserCrewRecordDiagnostics.kt:39" }
        check(profile.crewRecord(CrewRecordKind.REPAIRS)?.name == "First record") { "BrowserCrewRecordDiagnostics.kt:40" }
        engineer.info.name = "Renamed"
        profile.markSaveComplete(); profile.updateCrewRecords(engineer.info)
        check(!profile.dirty && profile.crewRecord(CrewRecordKind.REPAIRS)?.name == "First record") { "Tie replaced native record identity" }
        state.player.engines!!.damagedEnergyLevels = 1
        repeat(400) { if (state.player.engines!!.damaged) engineer.update(.05f) }
        check(profile.crewRecord(CrewRecordKind.REPAIRS)?.let { it.value == 2 && it.name == "Renamed" && it.female == engineer.info.isFemale } == true) { "BrowserCrewRecordDiagnostics.kt:46" }
        // Ordinary breach/fire completion does not return the native system
        // repair success flag. Do not award a system-bar record for either.
        engineer.room.spawnBreachAt(0)
        repeat(400) { engineer.update(.05f) }
        check(engineer.room.breaches.all { it == null } && engineer.info.browserCounters[CrewRecordKind.REPAIRS] == 2) { "Breach completion changed system repair record or did not finish" }
        engineer.room.spawnFireAt(0)
        state.debugFlags.noCrewDamage.set = true
        repeat(800) { engineer.update(.05f) }
        // Room cleanup removes extinguished fire instances in its next update;
        // this fixture drives the crew action directly, so inspect health too.
        check(engineer.room.fires.all { it == null || it.health == 0f } && engineer.info.browserCounters[CrewRecordKind.REPAIRS] == 2) { "Fire completion changed system repair record or did not finish" }

        BrowserEngine.diagnosticProgress("crew records combat and owner filtering")
        state = reset()
        var seed = 71
        do { state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 4, seed++) }
        while (state.enemy!!.isAutoScout || state.enemy!!.isAutomated || state.enemy!!.rooms.none { it.cellCount >= 4 })
        val enemy = state.enemy!!
        val opponent = enemy.crew.filterIsInstance<LivingCrew>().first()
        opponent.info.browserCounters.increment(CrewRecordKind.REPAIRS)
        opponent.update(.05f)
        check(profile.crewRecord(CrewRecordKind.REPAIRS) == null) { "Enemy crew published player records" }
        val attacker = state.player.crew.filterIsInstance<LivingCrew>().first()
        val room = enemy.rooms.first { it.cellCount >= 4 }
        attacker.teleportAnimatedTo(room)
        state.debugFlags.noCrewDamage.set = true
        repeat(40) { attacker.update(.05f) }
        enemy.crew.filter { it !== attacker && it !== opponent }.toList().forEach { it.removeFromShip() }
        attacker.setTargetRoom(room, ConstPoint.ZERO); opponent.setTargetRoom(room, ConstPoint.ZERO)
        attacker.jumpTo(room, ConstPoint.ZERO); opponent.jumpTo(room, ConstPoint.ZERO)
        attacker.nextTargetPos = null; opponent.nextTargetPos = null
        room.updateCrewInRoom(); enemy.updateCrewReservedSlots()
        state.debugFlags.noCrewDamage.set = false
        opponent.health = .01f
        repeat(100) { if (opponent.health > 0f) attacker.update(.05f) }
        check(opponent.health == 0f && attacker.info.browserCounters[CrewRecordKind.KILLS] == 1) { "BrowserCrewRecordDiagnostics.kt:82" }
        check(profile.crewRecord(CrewRecordKind.KILLS)?.value == 1) { "BrowserCrewRecordDiagnostics.kt:83" }
        profile.markSaveComplete()
        main.render(container, graphics)
        check(!profile.dirty) { "Rendering changed crew records" }
        val counterBefore = BrowserXml.write(org.jdom2.Document(attacker.info.browserCounters.save()))
        state = reload(state)
        check(BrowserXml.write(org.jdom2.Document(state.enemy!!.crew.filterIsInstance<LivingCrew>().first { it.ownerShip === state.player }.info.browserCounters.save())) == counterBefore) { "Saved combat counters changed" }
        check(profile.crewRecord(CrewRecordKind.KILLS)?.value == 1) { "BrowserCrewRecordDiagnostics.kt:90" }

        BrowserEngine.diagnosticProgress("crew records evasions and jumps")
        state = reset()
        val pilot = state.player.piloting!!.manningCrew!!
        // The dodge event (EventSystem 7, ShipManager 0x4a0219) is only raised with an enemy ship or a living
        // intruder aboard (ShipManager+0x7f0), and the evasion record is counted by CrewMember::CheckSkills when it
        // reads the event on the next frame - so the fixture brings an enemy ship and steps one frame per roll.
        state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 4, 71)
        state.player.update(0f)
        check(!state.player.pickMissed(-100)) { "BrowserCrewRecordDiagnostics.kt:95" }
        state.player.update(.05f)
        check(pilot.info.browserCounters[CrewRecordKind.EVASIONS] == 0) { "BrowserCrewRecordDiagnostics.kt:96" }
        check(state.player.pickMissed(100)) { "BrowserCrewRecordDiagnostics.kt:97" }
        state.player.update(.05f)
        check(pilot.info.browserCounters[CrewRecordKind.EVASIONS] == 1 && profile.crewRecord(CrewRecordKind.EVASIONS)?.value == 1) { "BrowserCrewRecordDiagnostics.kt:98" }
        val home = state.player.crew.filterIsInstance<LivingCrew>().filter { it.mode == AbstractCrew.SlotType.CREW }
        check(home.all { it.info.browserCounters[CrewRecordKind.JUMPS] == 0 }) { "BrowserCrewRecordDiagnostics.kt:100" }
        val next = state.currentBeacon.neighbours.first()
        state.currentBeacon = next
        state.currentBeacon = next
        check(home.all { it.info.browserCounters[CrewRecordKind.JUMPS] == 1 }) { "BrowserCrewRecordDiagnostics.kt:104" }
        state = reload(state)
        check(state.player.crew.filterIsInstance<LivingCrew>().all { it.info.browserCounters[CrewRecordKind.JUMPS] == 1 }) { "BrowserCrewRecordDiagnostics.kt:106" }

        BrowserEngine.diagnosticProgress("crew records skill milestones and cloning")
        state = reset()
        var trainee = state.player.crew.filterIsInstance<LivingCrew>().first()
        repeat(40) { trainee.addSkillPoint(Skill.PILOTING) }
        check(trainee.info.browserCounters[CrewRecordKind.SKILLS] == 2) { "BrowserCrewRecordDiagnostics.kt:112" }
        trainee.onCloned()
        repeat(40) { trainee.addSkillPoint(Skill.PILOTING) }
        check(trainee.info.browserCounters[CrewRecordKind.SKILLS] == 2) { "Retraining after cloning duplicated a milestone" }
        state = reload(state); trainee = state.player.crew.filterIsInstance<LivingCrew>().first()
        repeat(40) { trainee.addSkillPoint(Skill.ENGINES) }
        trainee.update(.05f)
        check(trainee.info.browserCounters[CrewRecordKind.SKILLS] == 4 && profile.crewRecord(CrewRecordKind.SKILLS)?.value == 4) { "BrowserCrewRecordDiagnostics.kt:119" }
        val restored = SaveProfile.createBlank().also { it.load(BrowserXml.parse(BrowserXml.write(profile.save()))) }
        for (kind in CrewRecordKind.entries) check(restored.crewRecord(kind) == profile.crewRecord(kind)) { "BrowserCrewRecordDiagnostics.kt:121" }
        profile.markSaveComplete(); profile.updateCrewRecords(trainee.info)
        check(!profile.dirty) { "Unchanged crew dirtied the profile every update" }

        BrowserEngine.diagnosticProgress("crew records legacy history and statistics navigation")
        state = reset()
        val old = state.saveGameState()
        fun removeCounters(elem: Element) { elem.removeChild("browserCrewCounters"); elem.children.forEach { removeCounters(it) } }
        removeCounters(old.rootElement)
        main.loadSavedGame(old); state = main.currentState as InGameState
        trainee = state.player.crew.filterIsInstance<LivingCrew>().first()
        repeat(40) { trainee.addSkillPoint(Skill.PILOTING) }; trainee.update(.05f)
        check(!trainee.info.browserCounters.complete && profile.crewRecord(CrewRecordKind.SKILLS) == null) { "Unknown older crew history became a complete lifetime record" }
        val stats = BrowserStatisticsState(main, state)
        main.setCurrentState(stats)
        // Exercise every original portrait sheet, including the separately
        // stored female human image, without generating random crew identities.
        for ((index, portrait) in listOf("human" to false, "human" to true, "engi" to false,
            "mantis" to false, "rock" to false, "energy" to false, "slug" to false,
            "crystal" to false, "anaerobic" to false).withIndex()) {
            val race = state.blueprintManager[portrait.first] as CrewBlueprint
            val info = LivingCrewInfo(state, race, "Portrait ${portrait.first}", if (portrait.second) race.baseNumberOfColours else 0)
            repeat(index + 1) { info.browserCounters.increment(CrewRecordKind.REPAIRS) }
            profile.updateCrewRecords(info)
            val before = BrowserXml.write(profile.save())
            val random = BrowserRandom.snapshot()
            main.render(container, graphics)
            check(BrowserRandom.snapshot() == random && BrowserXml.write(profile.save()) == before) { "Portrait rendering changed progression or simulation RNG" }
        }
        val navigationProfile = BrowserXml.write(profile.save())
        val navigationRandom = BrowserRandom.snapshot()
        stats.mouseClicked(Input.MOUSE_LEFT_BUTTON, 800, 80, 1)
        check(stats.diagnosticTab == "STATISTICS") { "BrowserCrewRecordDiagnostics.kt:153" }
        main.render(container, graphics)
        stats.mouseClicked(Input.MOUSE_LEFT_BUTTON, 1000, 80, 1)
        check(stats.diagnosticTab == "ACHIEVEMENTS") { "BrowserCrewRecordDiagnostics.kt:156" }
        main.render(container, graphics)
        stats.mouseClicked(Input.MOUSE_LEFT_BUTTON, 480, 65, 1)
        check(stats.diagnosticScoresTab == "SHIP_BEST" && stats.diagnosticTab == "ACHIEVEMENTS") { "BrowserCrewRecordDiagnostics.kt:159" }
        main.render(container, graphics)
        stats.mouseClicked(Input.MOUSE_LEFT_BUTTON, 140, 170, 1)
        check(stats.diagnosticShipList) { "BrowserCrewRecordDiagnostics.kt:162" }
        main.render(container, graphics)
        stats.keyPressed(Input.KEY_ESCAPE, '\u0000')
        check(!stats.diagnosticShipList && main.currentState === stats) { "Nested statistics list closed its parent" }
        stats.mouseClicked(Input.MOUSE_LEFT_BUTTON, 180, 65, 1)
        check(stats.diagnosticScoresTab == "TOP_SCORES" && stats.diagnosticTab == "ACHIEVEMENTS") { "BrowserCrewRecordDiagnostics.kt:167" }
        main.render(container, graphics)
        // Ship Best's LIST opens the hangar's ShipSelect, whose Open (0x00529940) marks the list tutorial shown.
        check(profile.listTutorialState == 2) { "Ship Best list did not mark its tutorial shown" }
        val withoutListTutorial = { xml: String -> xml.replace(Regex(" listTutorial=\"[0-9]\""), "") }
        check(withoutListTutorial(BrowserXml.write(profile.save())) == withoutListTutorial(navigationProfile)) { "Statistics navigation changed progression" }
        check(BrowserRandom.snapshot() == navigationRandom) { "Statistics navigation changed simulation RNG" }
        stats.close(); check(main.currentState === state) { "BrowserCrewRecordDiagnostics.kt:170" }
    }
}
