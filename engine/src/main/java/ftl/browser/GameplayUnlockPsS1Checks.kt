package ftl.browser

import org.jdom2.Element
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.DialogueWindow
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.game.SaveProfile
import xyz.znix.xftl.sector.Event
import xyz.znix.xftl.sector.EventText
import xyz.znix.xftl.sector.ImageList
import xyz.znix.xftl.sys.GameContainer

/**
 * Parity sweep S1 (hangar, progression): an event's `<unlockShip>` as WorldManager 1.6.14 0x61b080 / 0x6183a0 runs it
 * (HS CustomStore.cpp:2187 the same): the unlock line and "Ship Unlocked!" only for a ship not yet unlocked, the quest
 * achievement either way and again on a harder difficulty; vanilla's ship popup whatever the popup option says.
 * Native captures ps-s1-unlock (u-cr1, u-zq, u-cr2) and ps-s1-unlock2 (q-cr, q-zq, n-cr).
 */
internal object GameplayUnlockPsS1Checks {
    fun run(main: MainGame, container: GameContainer): String {
        val original = main.profile.snapshot()
        val advanced = main.gameContent.enableAdvancedEdition
        val rng = BrowserRandom.snapshot()
        val nativeRng = NativeRandom.snapshot()
        try {
            val unlocks = unlockEvents(main, container)
            val scrap = laniusScrap(main, container)
            val starts = if (xyz.znix.xftl.hs.HS.active) "starts: Hyperspace's against the oracle (hs-ships)" else starts(main)
            val victories = if (xyz.znix.xftl.hs.HS.active) "; " + hsVictories(main, container) else ""
            return "ps-s1 unlocks ok: $unlocks; $scrap; $starts$victories"
        } finally {
            main.profile.restore(original)
            if (main.gameContent.enableAdvancedEdition != advanced) {
                val lastAe = main.profile.lastAdvancedEdition
                main.changeHangarMode(advanced, "PLAYER_SHIP_HARD", Difficulty.NORMAL)
                main.profile.restore(original)
                main.profile.lastAdvancedEdition = lastAe
            }
            BrowserRandom.restore(rng)
            NativeRandom.restore(nativeRng)
        }
    }

    private fun unlockEvents(main: MainGame, container: GameContainer): String {
        val profile = main.profile
        val families = main.gameContent.shipFamilies
        val crystal = families.families.first { it.unlockId == "8" }
        val zoltan = families.families.first { it.unlockId == "7" }
        val hs = xyz.znix.xftl.hs.HS.active

        fun blank(popups: Boolean = true) {
            profile.restore(SaveProfile.createBlank().snapshot())
            profile.configureProgression(families, main.gameContent.achievements)
            profile.achievementPopups = popups
            profile.clearNotifications()
        }
        fun drain(): List<String> {
            val out = ArrayList<String>()
            while (true) {
                val n = profile.pollNotification() ?: break
                out += when {
                    n.questShip != null -> "quest " + n.questShip
                    n.ship != null -> "ship " + n.ship
                    else -> "other"
                }
            }
            return out
        }
        // The event as the box shows it: its text, then the unlock line.
        fun show(state: InGameState, id: String): String {
            val elem = Element("event").addContent(Element("unlockShip").setAttribute("id", id))
            val event = Event(EventText("Diagnostic unlock"), emptyList(), elem, "ps-s1-unlock", "ps-s1-unlock", { ImageList.NONE }, { EventText(it.textTrim) })
            val box = DialogueWindow(state, state.player, event, 1) {}
            return box.mainText
        }
        // Hyperspace (CustomStore.cpp:2193) puts a space before the break.
        val line8 = (if (hs) " \n\n" else "\n\n") + main.gameContent.translator["unlock_ship_8"]

        // u-cr1: a new ship: the line, "Ship Unlocked!", then the quest achievement.
        blank()
        var state = BrowserDiagnostics.freshState(main, container, "PLAYER_SHIP_HARD", Difficulty.NORMAL)
        profile.clearNotifications()
        var text = show(state, "8")
        check(text.endsWith(line8)) { "First unlock lost its line: $text" }
        var got = drain()
        check(got == listOf("ship ${crystal.ships[0]}", "quest ${crystal.ships[0]}")) { "First unlock popups $got" }
        check(profile.getShipUnlock(crystal)?.difficulty == Difficulty.NORMAL) { "Quest not recorded" }
        // u-cr2: again at the same difficulty: no line, no popup.
        text = show(state, "8")
        got = drain()
        check(!text.contains(line8.trim()) && got.isEmpty()) { "Repeated unlock: $got $text" }

        // u-ship then u-zq: the ship unlocked another way (the console's SHIP): no line, no ship popup, the quest only.
        profile.unlockLayout(zoltan.ships[0], Difficulty.NORMAL)
        drain()
        text = show(state, "7")
        got = drain()
        check(!text.contains(main.gameContent.translator["unlock_ship_7"])) { "Unlocked ship got the unlock line: $text" }
        check(got == listOf("quest ${zoltan.ships[0]}")) { "Unlocked ship's quest popups $got" }

        // q-cr / q-zq: a harder difficulty earns the quest again with its popup; the same one does not.
        state = BrowserDiagnostics.freshState(main, container, "PLAYER_SHIP_HARD", Difficulty.HARD)
        profile.clearNotifications()
        show(state, "8")
        got = drain()
        check(got == listOf("quest ${crystal.ships[0]}") && profile.getShipUnlock(crystal)?.difficulty == Difficulty.HARD) {
            "Harder quest: $got ${profile.getShipUnlock(crystal)?.difficulty}" }
        state = BrowserDiagnostics.freshState(main, container, "PLAYER_SHIP_HARD", Difficulty.EASY)
        profile.clearNotifications()
        show(state, "8")
        check(drain().isEmpty() && profile.getShipUnlock(crystal)?.difficulty == Difficulty.HARD) { "Easier quest changed the record" }

        // n-cr: popups off. Vanilla still shows "Ship Unlocked!" (and keeps it queued); Hyperspace's hook does not.
        blank(popups = false)
        state = BrowserDiagnostics.freshState(main, container, "PLAYER_SHIP_HARD", Difficulty.NORMAL)
        profile.achievementPopups = false
        profile.clearNotifications()
        text = show(state, "8")
        check(text.endsWith(line8)) { "Popups off lost the unlock line" }
        main.progressNotifications.update(0f)
        val shown = main.progressNotifications.active
        got = drain()
        check(shown == !hs && got.isEmpty()) { "Popups off: shown=$shown queued=$got (Hyperspace $hs)" }
        check(profile.getShipUnlock(crystal) != null && profile.isVanillaShipUnlocked(crystal.ships[0])) { "Popups off unlocked nothing" }
        main.progressNotifications.update(5f)
        check(!main.progressNotifications.active) { "Ship popup outlived three seconds" }
        return "event unlock line and popups (new, repeated, already unlocked, harder, easier), popups off " +
            (if (hs) "silent (Hyperspace)" else "keeps the ship popup")
    }

    /**
     * ACH_LANIUS_SCRAP (ShipManager::OnLoop 1.6.14 0x4abd40: scrap > 599 on the Lanius), a multi-difficulty achievement
     * recorded on Easy: native ps-s1-ach a-lan-599 no popup, a-lan-600 "Scrap Hoarder" again on Normal; a-kes-1010 the
     * Kestrel with 1010 scrap earns nothing.
     */
    /**
     * Hyperspace's victories without the flagship (CustomEvents.cpp): an event's `<win>` sets the victory type to its
     * `ach` (5645) and calls AchievementTracker::SetVictoryAchievement alone, so the ship's victory and that typed
     * victory are recorded, no ACH_WIN_* and no "flagship" typed victory; a `<finalBoss>` win sets the type the same way
     * (6957, 7025); a new game sets it back to "flagship" (6091).
     */
    private fun hsVictories(main: MainGame, container: GameContainer): String {
        val profile = main.profile
        val unlocks = xyz.znix.xftl.hs.ship.HsShipUnlocks
        profile.restore(SaveProfile.createBlank().snapshot())
        unlocks.setCustomVictoryType = "PSS1"
        val state = BrowserDiagnostics.freshState(main, container, "PLAYER_SHIP_HARD", Difficulty.NORMAL)
        check(unlocks.setCustomVictoryType == "flagship") { "A new game kept victory type ${unlocks.setCustomVictoryType}" }
        val type = xyz.znix.xftl.hs.ship.HsVictories.types.firstOrNull() ?: ""
        val ship = state.player.type.name
        val typedBefore = if (type.isEmpty()) -1 else unlocks.checkCustomShipVictory(ship, type)
        val flagshipBefore = unlocks.checkCustomShipVictory(ship, "flagship")
        val wins = listOf("ACH_WIN_EASY", "ACH_WIN_NORMAL").map { main.gameContent.achievements[it] }
        val def = xyz.znix.xftl.hs.events.EventGameOver().apply { enabled = true; victory = true; ach = type }
        xyz.znix.xftl.hs.events.HsEventEffects.gameOver(state, def)
        check(state.browserProgress.outcome == xyz.znix.xftl.game.GameOverWindow.Outcome.WIN) { "Event win: outcome ${state.browserProgress.outcome}" }
        check(wins.none { profile.getAchievement(it) != null }) { "Event win awarded ${wins.filter { profile.getAchievement(it) != null }.map { it.id }}" }
        check(profile.getLayoutVictory(ship) != null) { "Event win: no ship victory" }
        check(unlocks.checkCustomShipVictory(ship, "flagship") == flagshipBefore) { "Event win recorded the flagship victory type" }
        if (type.isNotEmpty()) check(unlocks.checkCustomShipVictory(ship, type) > typedBefore || typedBefore >= Difficulty.NORMAL.ordinal) {
            "Event win did not record its type $type" }
        check(unlocks.setCustomVictoryType == "flagship") { "The victory type was not reset: ${unlocks.setCustomVictoryType}" }
        main.switchToMainMenu()
        return "Hyperspace event win: the ship's victory and its type ${type.ifEmpty { "(none)" }}, no ACH_WIN_*, the type reset; a new game resets it"
    }

    private fun laniusScrap(main: MainGame, container: GameContainer): String {
        val profile = main.profile
        val table = main.gameContent.achievements
        val ach = table["ACH_LANIUS_SCRAP"]
        profile.restore(SaveProfile.createBlank().snapshot())
        profile.achievementPopups = true
        profile.unlockAchievement(ach, Difficulty.EASY)
        var state = BrowserDiagnostics.freshState(main, container, "PLAYER_SHIP_ANAEROBIC", Difficulty.NORMAL)
        profile.clearNotifications()
        state.player.scrap = 599; state.browserAchievements.update()
        check(profile.pendingNotificationCount == 0 && profile.getAchievement(ach)?.difficulty == Difficulty.EASY) { "599 scrap re-earned Scrap Hoarder" }
        state.player.scrap = 600; state.browserAchievements.update()
        val popup = profile.pollNotification()
        check(popup?.achievement == "ACH_LANIUS_SCRAP" && profile.getAchievement(ach)?.difficulty == Difficulty.NORMAL) {
            "600 scrap on Normal: popup ${popup?.achievement}, recorded ${profile.getAchievement(ach)?.difficulty}" }
        profile.restore(SaveProfile.createBlank().snapshot())
        state = BrowserDiagnostics.freshState(main, container, "PLAYER_SHIP_HARD", Difficulty.NORMAL)
        state.player.scrap = 1010; state.browserAchievements.update()
        check(profile.getAchievement(ach) == null) { "The Kestrel earned Scrap Hoarder" }
        return "Scrap Hoarder at 600 (again on a harder difficulty), not at 599 nor on the Kestrel"
    }

    /**
     * Every vanilla layout's start (ps-s1-starts: the original's continue.sav as the opening event box opens, read by
     * the save reader): reactor, hull, fuel, missiles, drone parts, scrap; each installed system's level and reactor
     * power (the Zoltan bars are not reactor power, nor saved; a subsystem's level only); each crew member's race, room
     * and slot; the weapons, drones and augments in order.
     */
    private fun starts(main: MainGame): String {
        val profile = main.profile
        profile.restore(SaveProfile.createBlank().snapshot())
        profile.configureProgression(main.gameContent.shipFamilies, main.gameContent.achievements)
        val bad = ArrayList<String>()
        for ((ship, want) in NATIVE_STARTS) {
            profile.unlockLayout(ship, Difficulty.EASY, popup = false)
            main.startNewGame(xyz.znix.xftl.game.RunConfiguration(ship, Difficulty.NORMAL, true), null)
            val got = startLine((main.currentState as InGameState).player)
            if (got != want) bad += "$ship\n  native $want\n  ours   $got"
        }
        check(bad.isEmpty()) { "Start states differ from the original:\n" + bad.joinToString("\n") }
        return "${NATIVE_STARTS.size} vanilla layouts start as the original"
    }

    internal fun startLine(ship: xyz.znix.xftl.Ship): String {
        val systems = ship.systems.sortedBy { it.codename }.joinToString(",") { sys ->
            // A subsystem's saved power follows the beacon (a start in a nebula: sensors 0, native st-fed-a): level only.
            if (sys is xyz.znix.xftl.systems.MainSystem) "${sys.codename}:${sys.energyLevels}/${sys.reactorPowerDemand}"
            else "${sys.codename}:${sys.energyLevels}"
        }
        val crew = ship.crew.filter { it.room.ship === ship }.map { c ->
            val slot = c.currentSlotPoint
            "${c.blueprint.name}@${slot.room.id}.${slot.y * slot.room.width + slot.x}"
        }.sorted().joinToString(",")
        val weapons = ship.hardpoints.mapNotNull { it.weapon?.type?.name }.joinToString(",")
        val drones = ship.drones?.drones?.mapNotNull { it?.type?.name }?.joinToString(",") ?: ""
        // The augments by name: the original keeps them in ShipInfo::augList, a std::map, so its save lists them sorted
        // (Stealth A: ADV_SCANNERS before SYSTEM_CASING, the blueprint's other way round). Their order in the equipment
        // window is the augment list's rule (sweep W3), left open there.
        val augments = ship.augments.map { it.name }.sorted().joinToString(",")
        return "r=${ship.purchasedReactorPower} h=${ship.health} f=${ship.fuelCount} m=${ship.missilesCount} d=${ship.dronesCount} " +
            "s=${ship.scrap} sys=$systems crew=$crew w=$weapons dr=$drones aug=$augments"
    }

    /** Filled from the native saves by .build/ps-s1/tools/starts.py. */
    private val NATIVE_STARTS: List<Pair<String, String>> = listOf(
        "PLAYER_SHIP_ANAEROBIC" to
            "r=8 h=30 f=16 m=3 d=9 s=10 sys=clonebay:1/1,doors:1,engines:1/1,hacking:1/1,oxygen:1/1,pilot:1,sensors:1,shields:2/2,weapons:3/0 crew=anaerobic@7.3,anaerobic@9.1,human@8.0 w=LASER_CHAINGUN,ION_STUN dr= aug=O2_MASKS",
        "PLAYER_SHIP_ANAEROBIC_2" to
            "r=8 h=30 f=16 m=0 d=0 s=10 sys=clonebay:1/1,doors:1,engines:1/1,mind:1/1,oxygen:1/1,pilot:1,shields:2/2,teleporter:1/1,weapons:1/0 crew=anaerobic@13.0,anaerobic@8.2,engi@9.0 w=SHOTGUN_PLAYER dr= aug=O2_MASKS",
        "PLAYER_SHIP_CIRCLE" to
            "r=10 h=30 f=16 m=0 d=15 s=10 sys=doors:1,drones:3/0,engines:2/1,medbay:1/1,oxygen:1/1,pilot:1,sensors:1,shields:2/2,weapons:3/0 crew=engi@11.1,engi@2.2,human@8.0 w=ION_4 dr=COMBAT_1 aug=NANO_MEDBAY",
        "PLAYER_SHIP_CIRCLE_2" to
            "r=9 h=30 f=16 m=0 d=6 s=10 sys=doors:1,drones:3/0,engines:1/1,medbay:1/1,oxygen:1/1,pilot:1,shields:2/2,weapons:3/0 crew=engi@13.0 w=ION_2,LASER_HEAVY_1 dr=BATTLE,REPAIR,REPAIR aug=DRONE_SPEED",
        "PLAYER_SHIP_CIRCLE_3" to
            "r=9 h=30 f=16 m=0 d=25 s=10 sys=clonebay:1/1,doors:1,drones:2/0,engines:2/1,hacking:1/1,oxygen:1/1,pilot:1,sensors:1,shields:2/2,weapons:1/0 crew=anaerobic@14.0,engi@10.1,engi@6.1 w=LASER_BURST_2 dr=COMBAT_BEAM aug=DEFENSE_SCRAMBLER",
        "PLAYER_SHIP_CRYSTAL" to
            "r=8 h=30 f=16 m=0 d=0 s=10 sys=doors:1,engines:2/1,medbay:1/1,oxygen:1/1,pilot:1,sensors:1,shields:2/2,weapons:3/0 crew=crystal@17.1,crystal@6.0,human@0.2,human@8.0 w=CRYSTAL_BURST_1,CRYSTAL_HEAVY_1 dr= aug=CRYSTAL_SHARDS",
        "PLAYER_SHIP_CRYSTAL_2" to
            "r=8 h=30 f=16 m=0 d=0 s=10 sys=cloaking:1/1,doors:1,engines:2/1,medbay:1/1,oxygen:1/1,pilot:1,sensors:1,shields:2/2,teleporter:1/1,weapons:1/0 crew=crystal@15.0,crystal@2.2,crystal@4.1 w= dr= aug=CRYSTAL_SHARDS",
        "PLAYER_SHIP_ENERGY" to
            "r=5 h=30 f=16 m=12 d=2 s=10 sys=doors:2,engines:1/0,medbay:1/1,oxygen:1/1,pilot:1,sensors:1,shields:2/2,weapons:3/0 crew=energy@11.0,energy@13.2,energy@14.1 w=BEAM_2,MISSILES_1 dr= aug=ENERGY_SHIELD",
        "PLAYER_SHIP_ENERGY_2" to
            "r=5 h=30 f=16 m=0 d=2 s=10 sys=doors:1,engines:2/1,medbay:1/1,oxygen:1/1,pilot:1,sensors:1,shields:1/0,weapons:4/0 crew=energy@14.0,energy@3.0,energy@7.0 w=ION_1,ION_1,BEAM_LONG dr= aug=ENERGY_SHIELD",
        "PLAYER_SHIP_ENERGY_3" to
            "r=2 h=30 f=16 m=2 d=15 s=10 sys=battery:2,clonebay:1/0,doors:1,drones:3/0,engines:2/1,oxygen:1/1,pilot:1,sensors:1,shields:2/0,weapons:2/0 crew=energy@0.0,energy@1.2,energy@2.1,energy@8.0 w=ION_CHARGEGUN dr=COMBAT_BEAM aug=ENERGY_SHIELD",
        "PLAYER_SHIP_FED" to
            "r=8 h=30 f=16 m=5 d=2 s=10 sys=artillery:1/1,doors:1,engines:2/1,medbay:1/1,oxygen:1/1,pilot:1,sensors:1,shields:2/2,weapons:2/0 crew=engi@11.0,human@4.0,mantis@3.2,rock@15.1 w=LASER_BURST_3 dr= aug=",
        "PLAYER_SHIP_FED_2" to
            "r=9 h=30 f=16 m=9 d=0 s=10 sys=artillery:2/1,doors:1,engines:2/1,medbay:1/1,oxygen:1/1,pilot:1,sensors:1,shields:2/2,weapons:2/0 crew=energy@15.2,human@7.1,slug@4.1 w=LASER_BURST_2,MISSILES_1 dr= aug=",
        "PLAYER_SHIP_FED_3" to
            "r=7 h=30 f=16 m=5 d=0 s=10 sys=artillery:1/1,clonebay:2/1,doors:1,engines:2/1,oxygen:1/1,pilot:1,sensors:1,shields:2/2,teleporter:1/1,weapons:1/0 crew=energy@11.1,energy@5.1,human@6.0,mantis@15.0 w= dr= aug=O2_MASKS",
        "PLAYER_SHIP_HARD" to
            "r=8 h=30 f=16 m=8 d=2 s=10 sys=doors:1,engines:2/1,medbay:1/1,oxygen:1/1,pilot:1,sensors:1,shields:2/2,weapons:3/0 crew=human@0.0,human@10.1,human@14.2 w=MISSILES_2_PLAYER,LASER_BURST_3 dr= aug=",
        "PLAYER_SHIP_HARD_2" to
            "r=8 h=30 f=16 m=5 d=0 s=10 sys=doors:1,engines:2/1,medbay:1/1,oxygen:1/1,pilot:1,sensors:1,shields:2/2,weapons:4/0 crew=energy@10.1,human@5.1,human@9.1,mantis@2.1 w=LASER_BURST_1,LASER_BURST_1,LASER_BURST_1,LASER_BURST_1 dr= aug=",
        "PLAYER_SHIP_HARD_3" to
            "r=7 h=30 f=16 m=4 d=3 s=10 sys=clonebay:1/1,doors:1,engines:2/1,oxygen:1/1,pilot:1,sensors:2,shields:2/2,weapons:2/0 crew=anaerobic@5.2,human@11.0,human@2.1 w=LASER_BURST_2,ION_STUN dr= aug=",
        "PLAYER_SHIP_JELLY" to
            "r=8 h=30 f=16 m=15 d=0 s=10 sys=doors:2,engines:2/1,medbay:1/1,oxygen:1/1,pilot:1,shields:2/2,weapons:3/0 crew=slug@6.2,slug@8.0 w=BEAM_BIO,BOMB_BREACH_1,LASER_BURST_2 dr= aug=SLUG_GEL",
        "PLAYER_SHIP_JELLY_2" to
            "r=7 h=30 f=16 m=25 d=0 s=10 sys=doors:2,engines:2/1,oxygen:1/1,pilot:1,shields:2/2,teleporter:1/1,weapons:2/0 crew=slug@11.0,slug@13.0,slug@2.2 w=BOMB_HEAL,MISSILES_2_PLAYER dr= aug=SLUG_GEL",
        "PLAYER_SHIP_JELLY_3" to
            "r=9 h=30 f=16 m=1 d=15 s=10 sys=clonebay:1/1,doors:2,engines:2/1,hacking:1/1,mind:1/1,oxygen:1/1,pilot:1,shields:2/2,weapons:2/0 crew=slug@6.2,slug@8.0,slug@9.1 w=LASER_CHAINGUN dr= aug=SLUG_GEL",
        "PLAYER_SHIP_MANTIS" to
            "r=7 h=30 f=16 m=16 d=0 s=10 sys=doors:1,engines:2/1,medbay:1/1,oxygen:1/1,pilot:1,shields:2/2,teleporter:1/1,weapons:1/0 crew=engi@16.0,mantis@10.1,mantis@13.1,mantis@7.0 w=BOMB_1,LASER_BURST_1 dr= aug=CREW_STIMS",
        "PLAYER_SHIP_MANTIS_2" to
            "r=11 h=30 f=16 m=0 d=15 s=10 sys=doors:1,drones:3/0,engines:1/1,medbay:1/1,oxygen:1/1,pilot:1,sensors:1,shields:4/4,teleporter:1/1,weapons:1/0 crew=mantis@10.2,mantis@8.0 w= dr=BOARDER,DEFENSE_1 aug=CREW_STIMS",
        "PLAYER_SHIP_MANTIS_3" to
            "r=8 h=30 f=16 m=20 d=0 s=10 sys=clonebay:2/1,doors:1,engines:2/1,oxygen:1/1,pilot:1,sensors:1,shields:2/2,teleporter:1/1,weapons:2/0 crew=anaerobic@12.1,engi@7.0,mantis@0.2 w=BOMB_LOCK,BOMB_STUN dr= aug=CREW_STIMS",
        "PLAYER_SHIP_ROCK" to
            "r=8 h=30 f=16 m=28 d=0 s=10 sys=doors:1,engines:2/1,medbay:1/1,oxygen:1/1,pilot:1,sensors:1,shields:2/2,weapons:3/0 crew=rock@14.2,rock@15.1,rock@5.0 w=MISSILES_2_PLAYER,MISSILES_HULL dr= aug=ROCK_ARMOR",
        "PLAYER_SHIP_ROCK_2" to
            "r=8 h=30 f=16 m=18 d=0 s=10 sys=engines:2/1,medbay:1/1,oxygen:2/1,pilot:1,sensors:1,shields:2/2,weapons:3/0 crew=rock@0.2,rock@12.1,rock@2.0,rock@8.1 w=LASER_HEAVY_1_SP,BOMB_FIRE dr= aug=ROCK_ARMOR",
        "PLAYER_SHIP_ROCK_3" to
            "r=8 h=30 f=16 m=15 d=0 s=10 sys=clonebay:1/1,doors:1,engines:2/1,oxygen:1/1,pilot:1,sensors:1,shields:2/2,weapons:3/0 crew=crystal@0.1,rock@4.0,rock@8.2 w=MISSILE_CHARGEGUN,CRYSTAL_HEAVY_1 dr= aug=ROCK_ARMOR",
        "PLAYER_SHIP_STEALTH" to
            "r=8 h=30 f=16 m=0 d=0 s=10 sys=cloaking:1/1,doors:1,engines:4/1,medbay:1/1,oxygen:1/1,pilot:1,sensors:2,weapons:2/0 crew=human@0.0,human@12.2,human@3.1 w=BEAM_1,LASER_BURST_2 dr= aug=ADV_SCANNERS,SYSTEM_CASING",
        "PLAYER_SHIP_STEALTH_2" to
            "r=7 h=30 f=16 m=0 d=0 s=10 sys=cloaking:2/1,doors:1,engines:2/1,medbay:1/1,oxygen:1/1,pilot:1,sensors:2,weapons:4/0 crew=energy@12.0,human@5.1,human@9.1 w=BEAM_3 dr= aug=ADV_SCANNERS",
        "PLAYER_SHIP_STEALTH_3" to
            "r=7 h=30 f=16 m=0 d=16 s=10 sys=clonebay:1/1,doors:1,drones:2/0,engines:3/1,oxygen:1/1,pilot:1,weapons:2/0 crew=human@8.0,rock@5.2,slug@3.1 w=LASER_CHARGEGUN_PLAYER,BEAM_1 dr=DRONE_SHIELD_PLAYER,ANTI_DRONE aug=ADV_SCANNERS",
    )
}
