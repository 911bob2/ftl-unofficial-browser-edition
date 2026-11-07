package ftl.browser

import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.sector.Event
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint

/**
 * Parity sweep W3 (stores, upgrades, equipment and augments): rule checks for what the sweep fixed.
 * - CreateChoiceBox (1.6.14 0x616680) with ShipObject::HasEquipment (0x46d350): a choice's req counts the equipment
 *   list (each weapon, drone and augment once, never the cargo hold) between lvl and max_lvl; 1.6.14 session
 *   ps-w3-van w3v-req-cargo / w3v-req-slot (BOMB_FIRE in the hold: QUEST_CREWDEAD_START_2 without its BOMB_FIRE choice).
 * - SystemStoreBox::Activate (1.6.14 0x519250): the vanilla store asks before a medical system replaces the other and
 *   before a main system takes the eighth slot; ps-w3-van w3v-confirm.
 * - WeaponSystem::RemoveWeapon: a weapon dragged from its slot to the hold leaves no gap, the weapons after it move up
 *   (ps-w3-van w3v-equip e-3-to-hold).
 */
internal object GameplayParityPsW3Checks {
    fun run(main: MainGame, container: GameContainer): String {
        val rng = BrowserRandom.snapshot()
        val nativeRng = NativeRandom.snapshot()
        try {
            val req = BrowserGameplayParityDiagnostics.vanillaOnly("req counts") { requirementCounts(main, container) }
            val confirm = BrowserGameplayParityDiagnostics.vanillaOnly("store confirms") { storeConfirms(main, container) }
            val gap = weaponGap(main, container)
            return "psW3 ok: $req, $confirm, $gap"
        } finally {
            BrowserRandom.restore(rng)
            NativeRandom.restore(nativeRng)
        }
    }

    /**
     * How many choices QUEST_CREWDEAD_START_2's box keeps: two plain ones, plus BOMB_FIRE's while it counts (the teleporter's
     * and BEAM_BIO's are never met here).
     */
    private fun cropsChoices(main: MainGame, container: GameContainer, state: InGameState): Int {
        val ui = state.shipUI
        val event = state.eventManager["QUEST_CREWDEAD_START_2"] as Event
        ui.showEventDialogue(state.eventCreator.create(event, 0, true) ?: error("No QUEST_CREWDEAD_START_2"))
        val n = (ui.diagnosticDialogue ?: error("No QUEST_CREWDEAD_START_2 box")).diagnosticChoices().size
        BrowserDiagnostics.closeBoxes(main, container)
        return n
    }

    private fun requirementCounts(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val ship = state.player
        if (state.enemy != null) state.browserEndHostility()
        val bomb = state.blueprintManager["BOMB_FIRE"] as AbstractWeaponBlueprint
        val filler = state.blueprintManager["LASER_BURST_1"] as AbstractWeaponBlueprint
        // Every weapon slot full, then BOMB_FIRE goes to the hold.
        val slots = minOf(ship.weaponSlots ?: ship.hardpoints.size, ship.hardpoints.size)
        while ((0 until slots).any { ship.hardpoints[it].weapon == null }) check(ship.addBlueprint(filler, false)) { "No room for LASER_BURST_1" }
        check(ship.addBlueprint(bomb, false) && ship.allCargo().contains(bomb)) { "BOMB_FIRE did not go to the hold" }
        val inHold = cropsChoices(main, container, state)
        check(inHold == 2) { "BOMB_FIRE in the hold: $inHold choices (native: 2, the hold does not count)" }

        // In a weapon slot it counts once.
        val slot = ship.hardpoints.first { it.weapon?.type == filler }
        slot.weapon = null
        ship.cargoBlueprints[ship.cargoBlueprints.indexOf(bomb)] = null
        ship.cargoUpdated()
        check(ship.addBlueprint(bomb, false) && ship.hardpoints.any { it.weapon?.type == bomb }) { "BOMB_FIRE did not go to a slot" }
        val inSlot = cropsChoices(main, container, state)
        check(inSlot == 3) { "BOMB_FIRE in a slot: $inSlot choices (native: 3)" }

        // lvl and max_lvl bound the count (EventsParser::ProcessChoice defaults 1 and INT_MAX).
        val choice = (state.eventManager["QUEST_CREWDEAD_START_2"] as Event).choices.first { it.req == "BOMB_FIRE" }
        val (min, max) = choice.minLevel to choice.maxLevel
        try {
            choice.minLevel = 2
            val needTwo = cropsChoices(main, container, state)
            check(needTwo == 2) { "lvl 2 with one BOMB_FIRE: $needTwo" }
            choice.minLevel = 0; choice.maxLevel = 0
            val noneWanted = cropsChoices(main, container, state)
            check(noneWanted == 2) { "max_lvl 0 with one BOMB_FIRE: $noneWanted" }
        } finally {
            choice.minLevel = min; choice.maxLevel = max
        }
        return "req counts (hold not counted, slot once, lvl/max_lvl)"
    }

    private fun storeConfirms(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        if (state.enemy != null) state.browserEndHostility()
        val ship = state.player
        fun bp(name: String) = state.blueprintManager[name] as xyz.znix.xftl.systems.SystemBlueprint
        fun install(name: String) {
            val slot = checkNotNull(ship.systemSlots.firstOrNull { it.system == bp(name) }) { "No $name slot" }
            slot.room.setSystem(slot)
        }
        check(ship.systems.any { it.codename == "medbay" }) { "The fixture has no medbay" }
        ship.scrap = 500
        val store = xyz.znix.xftl.game.StoreData()
        store.sections += xyz.znix.xftl.game.StoreData.Section.SYSTEMS
        store.systems += listOf(bp("clonebay"), bp("cloaking"), bp("battery"))
        var closed = false
        val window = xyz.znix.xftl.game.StoreWindow(state, ship, store) { closed = true }
        val graphics = BrowserEngine.diagnosticGraphics()
        window.draw(graphics)
        val clone = window.buttons.filterIsInstance<xyz.znix.xftl.game.StoreWindow.BuyButton>()
            .first { runCatching { it.blueprint }.getOrNull() == bp("clonebay") } // repair buttons have no blueprint
        clone.touchBuy() // the click of a left button, with its checks and confirmations
        check(window.diagnosticConfirmKey == "confirm_buy_clonebay" && ship.scrap == 500 && ship.systems.none { it.codename == "clonebay" }) {
            "Clone bay over the medbay: confirm ${window.diagnosticConfirmKey}, scrap ${ship.scrap} (native: asks first)"
        }
        // Escape over the confirm closes the store, nothing bought (ps-w3-van w3v-confirm c-after-escape).
        window.onTextInput(xyz.znix.xftl.sys.Input.KEY_ESCAPE, 27.toChar()) // the key reaches the window first
        check(closed && window.diagnosticConfirmKey == null && ship.scrap == 500 && ship.systems.none { it.codename == "clonebay" }) {
            "Escape over the confirm: store closed $closed, confirm ${window.diagnosticConfirmKey}, scrap ${ship.scrap}"
        }
        check(window.vanillaSystemConfirm(bp("cloaking")) == null) { "Cloaking with ${ship.mainSystems.size} main systems asked" }
        // Seven main systems: the eighth asks (Advanced Edition on or artillery installed), a subsystem never does.
        for (name in listOf("drones", "teleporter")) if (ship.mainSystems.size < 7) install(name)
        check(ship.mainSystems.size == 7) { "The fixture reached ${ship.mainSystems.size} main systems" }
        val ae = state.isAdvancedEdition
        check(window.vanillaSystemConfirm(bp("cloaking")) == (if (ae) "confirm_buy_last_system" else null)) {
            "Cloaking as the eighth main system (AE $ae): ${window.vanillaSystemConfirm(bp("cloaking"))}"
        }
        check(window.vanillaSystemConfirm(bp("battery")) == null) { "A subsystem asked for the last slot" }
        return "store confirms (medical swap, last system slot, AE $ae, Escape closes the store)"
    }

    private fun weaponGap(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val ship = state.player
        val slots = minOf(ship.weaponSlots ?: ship.hardpoints.size, ship.hardpoints.size)
        check(slots >= 3) { "${ship.type.name} has $slots weapon slots" }
        val names = listOf("LASER_BURST_1", "BOMB_FIRE", "ION_1", "LASER_BURST_2")
        for (s in 0 until slots) ship.hardpoints[s].weapon = (state.blueprintManager[names[s % names.size]] as AbstractWeaponBlueprint).buildInstance(ship)
        for (c in 0 until xyz.znix.xftl.Ship.CARGO_SLOTS) ship.cargoBlueprints[c] = null
        ship.cargoUpdated()
        val before = (0 until slots).map { ship.hardpoints[it].weapon!!.type.name }
        val panel = xyz.znix.xftl.game.ShipEquipmentPanel(state, ship)
        panel.diagnosticDragWeaponToCargo(1, 1)
        val after = (0 until slots).map { ship.hardpoints[it].weapon?.type?.name }
        val expected = before.filterIndexed { i, _ -> i != 1 } + listOf(null)
        check(after == expected && ship.allCargo().any { it.name == before[1] }) {
            "Slot 2 to the hold: $before -> $after, hold ${ship.cargoBlueprints.map { it?.name }} (native: $expected)"
        }
        return "weapon gap closes"
    }
}
