package ftl.browser

import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.game.*
import xyz.znix.xftl.hangar.SelectShipState
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.sys.Input

/** Disposable preview/name/save fixture. No earned progress survives this probe. */
object BrowserHangarDiagnostics {
    fun run(main: MainGame,container: GameContainer,graphics: Graphics): String {
        val profile=BrowserXml.write(main.profile.save())
        val storedProfile=BrowserFiles.getProfile();val storedRun=BrowserFiles.getRun()
        val rng=BrowserRandom.snapshot();val mode=main.gameContent.enableAdvancedEdition
        val originalRun=(main.currentState as? InGameState)?.let { BrowserXml.write(it.saveGameState()) }
        var phase="preview"
        try {
            main.profile.load(SaveProfile.createBlank().save())
            for(ae in listOf(false,true)) {
                main.changeHangarMode(ae,ShipProgression.KESTREL,Difficulty.NORMAL)
                val hangar=main.currentState as SelectShipState
                val before=BrowserXml.write(main.profile.save());val seed=BrowserRandom.snapshot()
                check(hangar.ships.size==if(ae) 28 else 18) { "BrowserHangarDiagnostics.kt:24" }
                for(ship in hangar.ships) {
                    phase="preview $ae ${ship.name}"
                    hangar.restoreSelection(ship.name,Difficulty.NORMAL)
                    main.render(container,graphics)
                    val ui=hangar.hangarPresentation
                    check(ui.crew.size==hangar.blueprint.initialCrew.sumOf { it.amount }) { "BrowserHangarDiagnostics.kt:30" }
                    check(ui.crew.all { it.name.isNotBlank() }) { "BrowserHangarDiagnostics.kt:31" }
                    check(ui.shipName.isNotBlank()) { "BrowserHangarDiagnostics.kt:32" }
                }
                check(before==BrowserXml.write(main.profile.save())) { "Preview changed progression" }
                check(seed==BrowserRandom.snapshot()) { "Preview consumed gameplay RNG" }
                hangar.restoreSelection(ShipProgression.KESTREL,Difficulty.NORMAL)
                hangar.mouseClicked(0,92,264,1) // Locked Layout B.
                check(hangar.selectedShipId==ShipProgression.KESTREL) { "BrowserHangarDiagnostics.kt:38" }
                hangar.restoreSelection("PLAYER_SHIP_HARD_2",Difficulty.NORMAL)
                hangar.mouseClicked(0,1170,50,1)
                check(main.currentState===hangar && !hangar.isSelectedUnlocked) { "BrowserHangarDiagnostics.kt:41" }
                check(hangar.lockReason.contains("2/3")) { "BrowserHangarDiagnostics.kt:42" }
                if(ae) {
                    hangar.restoreSelection("PLAYER_SHIP_HARD_3",Difficulty.NORMAL)
                    check(hangar.lockReason.contains("Sector 8") && hangar.lockReason.contains("Advanced Mode")) { "BrowserHangarDiagnostics.kt:45" }
                }
                hangar.restoreSelection(ShipProgression.KESTREL,Difficulty.NORMAL)
                repeat(5) { hangar.selectRandomShip(kotlin.random.Random(it));check(hangar.selectedShipId==ShipProgression.KESTREL) { "BrowserHangarDiagnostics.kt:48" } }
                hangar.selectNextShip(1);check(hangar.selectedShipId==ShipProgression.KESTREL) { "BrowserHangarDiagnostics.kt:49" }
                main.profile.unlockLayout("PLAYER_SHIP_HARD_2",Difficulty.NORMAL)
                main.profile.unlockLayout("PLAYER_SHIP_CIRCLE_2",Difficulty.NORMAL)
                hangar.restoreSelection("PLAYER_SHIP_HARD_2",Difficulty.NORMAL)
                hangar.selectNextShip(1);check(hangar.selectedShipId=="PLAYER_SHIP_CIRCLE_2") { "BrowserHangarDiagnostics.kt:53" }
                hangar.selectNextShip(-1);check(hangar.selectedShipId=="PLAYER_SHIP_HARD_2") { "BrowserHangarDiagnostics.kt:54" }
                main.profile.load(SaveProfile.createBlank().save())
            }
            phase="name input"
            main.changeHangarMode(true,ShipProgression.KESTREL,Difficulty.HARD)
            var hangar=main.currentState as SelectShipState
            val ui=hangar.hangarPresentation
            val default=ui.shipName
            hangar.mouseClicked(2,50,30,1);check(!ui.isEditing) { "BrowserHangarDiagnostics.kt:62" }
            hangar.mouseClicked(0,50,30,1)
            "x".forEach { hangar.keyPressed(0,it) }
            hangar.keyPressed(Input.KEY_ESCAPE,'\u0000');check(ui.shipName==(default+"x").take(16)) { "BrowserHangarDiagnostics.kt:65" }
            fun clearName() { hangar.keyPressed(Input.KEY_END,'\u0000');repeat(16) { hangar.keyPressed(Input.KEY_BACK,'\u0000') } }
            hangar.mouseClicked(0,50,30,1)
            clearName()
            "1234567890123456789".forEach { hangar.keyPressed(0,it) }
            hangar.keyPressed(Input.KEY_ENTER,'\u0000');check(ui.shipName=="1234567890123456") { "BrowserHangarDiagnostics.kt:70" }
            hangar.mouseClicked(0,50,30,1);clearName()
            hangar.keyPressed(Input.KEY_ENTER,'\u0000');check(ui.shipName==hangar.translator["nameless_one"]) { "BrowserHangarDiagnostics.kt:72" }
            hangar.mouseClicked(0,50,30,1);clearName();"Parity ship".forEach { hangar.keyPressed(0,it) };hangar.keyPressed(Input.KEY_ENTER,'\u0000')
            hangar.mouseClicked(0,100,590,1);check(ui.isCustomizing) { "BrowserHangarDiagnostics.kt:74" }
            hangar.mouseClicked(0,1170,50,1);check(main.currentState===hangar) { "BrowserHangarDiagnostics.kt:75" }
            hangar.mouseClicked(0,150,649,1);clearName();"Parity crew".forEach { hangar.keyPressed(0,it) };hangar.keyPressed(Input.KEY_ENTER,'\u0000')
            check(ui.crew.first().name=="Parity crew") { "BrowserHangarDiagnostics.kt:77" }
            hangar.mouseClicked(0,276,574,1)
            hangar.mouseClicked(0,253,649,1);check(!ui.isCustomizing) { "BrowserHangarDiagnostics.kt:79" }
            val choices=ui.crew.map { it.copy() }
            hangar.setRoomsVisible(false)
            val sounds=main.gameContent.sounds
            hangar.changeMode(false)
            check(main.gameContent.sounds===sounds) { "AE toggle replaced active sound manager" }
            hangar=main.currentState as SelectShipState
            check(hangar.hangarPresentation.shipName=="Parity ship" && hangar.hangarPresentation.crew==choices && !hangar.roomsVisible) { "BrowserHangarDiagnostics.kt:86" }
            hangar.changeMode(true);hangar=main.currentState as SelectShipState
            phase="start and restore"
            hangar.startGame(Difficulty.HARD)
            var game=main.currentState as InGameState
            check(game.player.name==ShipProgression.KESTREL && game.player.displayName=="Parity ship") { "BrowserHangarDiagnostics.kt:91" }
            check(game.player.crew.filterIsInstance<LivingCrew>().map { it.info.name }==choices.map { it.name }) { "BrowserHangarDiagnostics.kt:92" }
            check(game.player.crew.filterIsInstance<LivingCrew>().map { it.info.colour }==choices.map { it.colour }) { "BrowserHangarDiagnostics.kt:93" }
            val saved=BrowserXml.write(game.saveGameState())
            main.loadSavedGame(BrowserXml.parse(saved));game=main.currentState as InGameState
            check(game.player.displayName=="Parity ship") { "BrowserHangarDiagnostics.kt:96" }
            val legacy=BrowserXml.parse(saved);legacy.rootElement.getChild("playerShip").removeAttribute("displayName")
            main.loadSavedGame(legacy)
            check((main.currentState as InGameState).player.displayName==null) { "BrowserHangarDiagnostics.kt:99" }
            main.loadSavedGame(BrowserXml.parse(saved))
            check(saved.contains("displayName=\"Parity ship\"")) { "BrowserHangarDiagnostics.kt:101" }
            main.restartGame();game=main.currentState as InGameState
            check(game.player.displayName=="Parity ship" && game.player.name==ShipProgression.KESTREL) { "BrowserHangarDiagnostics.kt:103" }
            return "PASS 18 vanilla/28 AE previews, unchanged profile and gameplay RNG, locked selection/Start, unlocked random selection, ship/crew naming and colour, append/confirm/blank/16-character input, crew modal blocking, AE music/choice retention, blueprint identity and display-name save/restore/restart with legacy default"
        } catch(e: Exception) { throw IllegalStateException("Hangar $phase: ${e.message}",e) }
        finally {
            main.changeHangarMode(mode,ShipProgression.KESTREL,Difficulty.NORMAL)
            main.profile.load(BrowserXml.parse(profile))
            if(originalRun!=null) main.loadSavedGame(BrowserXml.parse(originalRun)) else main.switchToMainMenu()
            BrowserFiles.setRun(storedRun);BrowserFiles.setProfile(storedProfile);BrowserRandom.restore(rng)
        }
    }
}
