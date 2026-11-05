package xyz.znix.xftl.hs.ship

import org.jdom2.Element
import xyz.znix.xftl.Blueprint
import xyz.znix.xftl.Ship
import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.hs.HS

// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/UserInterface/CustomEquipment.cpp (the augment and cargo slot
// counts of CustomEquipment::OnInit / OnLoop, multipleOverCapacity: AddOverCapacityItem, UpdateOverCapacityItems, the
// IsCompletelyFull and GetCargoHold hooks, Equipment::Jump) and src/game/Ship/CustomAugments.cpp (locked augments).
// This engine is AGPL-3.0-or-later.

/**
 * Hyperspace's equipment rules for a ship: the augment slots (`augSlots` plus AUGMENT_SLOT) and ordinary cargo slots
 * (`cargoSlots` plus CARGO_SLOT) instead of vanilla's three and four, and `multipleOverCapacity` (every item that does
 * not fit is kept, one is shown in the over-capacity box at a time).
 *
 * Ship state lives in [State] (`ship.hs.equipment`). The vanilla [Ship.cargoBlueprints] keeps the first four cargo slots and
 * the two over-capacity boxes (index 4 = item box, 5 = augment box): with multipleOverCapacity those two mirror the page of
 * [State.over] that is shown, as the native boxes do. Cargo slots beyond four are in [State.extraCargo].
 */
object HsEquipment {
    class State {
        /** Ordinary cargo slots 4, 5, ... (index = slot - 4). */
        @JvmField val extraCargo = ArrayList<Blueprint?>()

        /** `overCapacityItems`: every over-capacity item in arrival order (an emptied entry stays until a jump or a new item). */
        @JvmField val over = ArrayList<Blueprint?>()
        @JvmField var overPage = 0

        /** Guards [normalize] against the re-adds it causes. */
        internal var busy = false
    }

    @JvmStatic
    fun active(ship: Ship): Boolean = HS.active && ship.hs != null

    @JvmStatic
    fun multipleOver(ship: Ship): Boolean = active(ship) && HS.cfg().multipleOverCapacity

    /** `GetDefinition(name).augSlots` plus the AUGMENT_SLOT value, never below zero (CustomEquipment::OnLoop). */
    @JvmStatic
    fun augCapacity(ship: Ship): Int {
        if (!active(ship)) return Ship.MAX_AUGMENTS
        return maxOf(0, HsShips.def(ship.type.name).augSlots + ship.getAugmentValue("AUGMENT_SLOT").toInt())
    }

    /** `GetDefinition(name).cargoSlots` plus the CARGO_SLOT value, never below zero. */
    @JvmStatic
    fun cargoCapacity(ship: Ship): Int {
        if (!active(ship)) return Ship.CARGO_SLOTS
        return maxOf(0, HsShips.def(ship.type.name).cargoSlots + ship.getAugmentValue("CARGO_SLOT").toInt())
    }

    private fun state(ship: Ship): State? = ship.hs?.equipment

    // ---------------------------------------------------------------- cargo slots

    /** The ordinary cargo slot [slot] (0 until [cargoCapacity]). */
    @JvmStatic
    fun cargoAt(ship: Ship, slot: Int): Blueprint? =
        if (slot < 4) ship.cargoBlueprints[slot] else state(ship)?.extraCargo?.getOrNull(slot - 4)

    @JvmStatic
    fun setCargoAt(ship: Ship, slot: Int, item: Blueprint?) {
        if (slot < 4) { ship.cargoBlueprints[slot] = item; return }
        val extra = state(ship)?.extraCargo ?: return
        while (extra.size <= slot - 4) extra.add(null)
        extra[slot - 4] = item
    }

    // ---------------------------------------------------------------- over capacity

    /** An over-capacity item is held on some page. */
    @JvmStatic
    fun hasOverItems(ship: Ship): Boolean = state(ship)?.over?.any { it != null } == true

    /** AddOverCapacityItem: the item goes into the first empty entry (or the end), then page 0 is shown. */
    @JvmStatic
    fun addOver(ship: Ship, item: Blueprint) {
        val st = state(ship) ?: return
        syncOver(ship)
        val empty = st.over.indexOfFirst { it == null }
        if (empty >= 0) st.over[empty] = item else st.over += item
        st.overPage = 0
        showOver(ship)
    }

    /** UpdateOverCapacityItems: the shown page takes what the box holds now (after a drop). */
    @JvmStatic
    fun syncOver(ship: Ship) {
        val st = state(ship) ?: return
        if (st.over.isEmpty()) return
        val page = st.overPage.coerceIn(0, st.over.size - 1)
        if (ship.overCapacityBoxShown[0]) st.over[page] = ship.cargoBlueprints[4]
        else if (ship.overCapacityBoxShown[1]) st.over[page] = ship.cargoBlueprints[5]
    }

    /** Put the entry of the current page into its box (an augment into the augment box, everything else into the item box). */
    private fun showOver(ship: Ship) {
        val st = state(ship) ?: return
        ship.cargoBlueprints[4] = null
        ship.cargoBlueprints[5] = null
        if (st.over.isEmpty()) return
        st.overPage = st.overPage.coerceIn(0, st.over.size - 1)
        val item = st.over[st.overPage]
        val aug = item is AugmentBlueprint
        ship.overCapacityBoxShown[0] = !aug
        ship.overCapacityBoxShown[1] = aug
        ship.cargoBlueprints[if (aug) 5 else 4] = item
    }

    /** The arrows of the over-capacity box: store the page, show the previous or next one (wrapping). */
    @JvmStatic
    fun turnOverPage(ship: Ship, forward: Boolean) {
        val st = state(ship) ?: return
        if (st.over.size <= 1) return
        syncOver(ship)
        ship.overCapacityBoxShown.fill(false)
        st.overPage = if (forward) (st.overPage + 1) % st.over.size else (st.overPage + st.over.size - 1) % st.over.size
        showOver(ship)
    }

    @JvmStatic
    fun overCount(ship: Ship): Int = state(ship)?.over?.size ?: 0

    /** The items of every over-capacity page (multipleOverCapacity), the shown page's as its box holds it now. */
    @JvmStatic
    fun overItems(ship: Ship): List<Blueprint> {
        syncOver(ship)
        return state(ship)?.over?.filterNotNull().orEmpty()
    }

    /** Equipment::Jump: the list and the shown boxes are cleared. */
    @JvmStatic
    fun onJump(ship: Ship) {
        val st = state(ship) ?: return
        st.over.clear()
        st.overPage = 0
    }

    /** Equipment::GetCargoHold: every cargo item, including the over-capacity ones not on the shown page. */
    @JvmStatic
    fun cargoItems(ship: Ship): List<Blueprint> {
        val st = state(ship)
        val out = ArrayList<Blueprint>()
        for (b in ship.cargoBlueprints) if (b != null) out += b
        st?.extraCargo?.forEach { if (it != null) out += it }
        if (st != null && multipleOver(ship)) {
            val shown = st.overPage
            st.over.forEachIndexed { i, b -> if (i != shown && b != null) out += b }
        }
        return out
    }

    /** Remove the first copy of [item] from a place [Ship.removeBlueprint] does not know. */
    @JvmStatic
    fun removeExtra(ship: Ship, item: Blueprint): Boolean {
        val st = state(ship) ?: return false
        val i = st.extraCargo.indexOf(item)
        if (i >= 0) { st.extraCargo[i] = null; return true }
        val j = st.over.indexOf(item)
        if (j >= 0) {
            if (j == st.overPage) {
                ship.cargoBlueprints[if (item is AugmentBlueprint) 5 else 4] = null
            }
            st.over[j] = null
            return true
        }
        return false
    }

    // ---------------------------------------------------------------- CustomEquipment::OnLoop

    /**
     * The slot counts follow the definition and the AUGMENT_SLOT / CARGO_SLOT augments: augments beyond the augment slots and
     * items in removed cargo slots go back through AddToCargo (so into a free slot or the over-capacity box).
     */
    @JvmStatic
    fun normalize(ship: Ship) {
        val st = state(ship) ?: return
        if (st.busy) return
        st.busy = true
        try {
            val buffer = ArrayList<Blueprint>()
            val augCap = augCapacity(ship)
            while (ship.augments.size > augCap) buffer += ship.augments.removeAt(ship.augments.size - 1)
            val cargoCap = cargoCapacity(ship)
            for (i in cargoCap until 4) {
                ship.cargoBlueprints[i]?.let { buffer += it }
                ship.cargoBlueprints[i] = null
            }
            for (i in maxOf(0, cargoCap - 4) until st.extraCargo.size) st.extraCargo[i]?.let { buffer += it }
            while (st.extraCargo.size > maxOf(0, cargoCap - 4)) st.extraCargo.removeAt(st.extraCargo.size - 1)
            if (buffer.isEmpty()) return
            ship.hs?.augListCache = null
            st.busy = false
            for (item in buffer) ship.addBlueprint(item, true)
        } finally {
            st.busy = false
        }
    }

    // ---------------------------------------------------------------- locked augments (CustomAugments.cpp, Equipment::OnLoop)

    /**
     * A locked augment cannot be moved. When one sits in the over-capacity augment box (or in an over-capacity page), it is
     * swapped with the augment in the first augment slot, so it ends up in a slot.
     */
    @JvmStatic
    fun swapLocked(ship: Ship) {
        val first = ship.augments.firstOrNull() ?: return
        val box = ship.cargoBlueprints[5] as? AugmentBlueprint
        if (box != null && HsAugments.isLocked(box.name)) {
            ship.cargoBlueprints[5] = first
            ship.augments[0] = box
            syncOver(ship)
            ship.cargoUpdated()
            return
        }
        val st = state(ship) ?: return
        for (i in st.over.indices) {
            val b = st.over[i] as? AugmentBlueprint ?: continue
            if (HsAugments.isLocked(b.name)) {
                st.over[i] = first
                ship.augments[0] = b
                ship.cargoUpdated()
                return
            }
        }
    }

    /** TextLibrary::GetText("max_augments") hook (CustomShipSelect.cpp): the "3" of the text becomes the definition's augSlots. */
    @JvmStatic
    fun maxAugmentsText(text: String, shipName: String): String {
        val slots = HsShips.def(shipName).augSlots
        if (slots == 3) return text
        val p = text.indexOf('3')
        return if (p >= 0) text.substring(0, p) + slots + text.substring(p + 1) else text
    }

    // ---------------------------------------------------------------- save

    fun save(ship: Ship, parent: Element) {
        val st = state(ship) ?: return
        for ((i, b) in st.extraCargo.withIndex()) {
            if (b == null) continue
            parent.addContent(Element("hsCargo").setAttribute("slot", (i + 4).toString()).setAttribute("name", b.name))
        }
        if (st.over.isEmpty()) return
        syncOver(ship)
        val over = Element("hsOver").setAttribute("page", st.overPage.toString())
        for (b in st.over) over.addContent(Element("item").also { e -> if (b != null) e.setAttribute("name", b.name) })
        parent.addContent(over)
    }

    fun load(ship: Ship, parent: Element?, blueprint: (String) -> Blueprint?) {
        val st = state(ship) ?: return
        st.extraCargo.clear(); st.over.clear(); st.overPage = 0
        parent?.getChildren("hsCargo")?.forEach { e ->
            val slot = e.getAttributeValue("slot")?.toIntOrNull() ?: return@forEach
            val bp = blueprint(e.getAttributeValue("name") ?: return@forEach) ?: return@forEach
            setCargoAt(ship, slot, bp)
        }
        val over = parent?.getChild("hsOver")
        if (over != null) {
            for (e in over.getChildren("item")) st.over += e.getAttributeValue("name")?.let(blueprint)
            st.overPage = over.getAttributeValue("page")?.toIntOrNull() ?: 0
            showOver(ship)
        } else if (multipleOver(ship)) {
            // A save without the list (vanilla or an older mod set): what the two boxes hold.
            val item = ship.cargoBlueprints[4] ?: ship.cargoBlueprints[5]
            if (item != null) { st.over += item; showOver(ship) }
        }
    }
}
