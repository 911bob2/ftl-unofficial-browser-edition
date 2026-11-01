package ftl.browser

import org.jdom2.Element
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.game.StoreData
import xyz.znix.xftl.game.StoreWindow
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.ship.HsStoreState
import xyz.znix.xftl.hs.ship.HsStores
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.sys.Input

/**
 * Hyperspace custom stores (`hsShipsProbe("store <ship> <seed> [ids]")`): every `<customStore>` generated for a run of
 * [ship] with the global stream seeded, as JSON (tests/hs-ships.mjs checks it against its own reading of the definitions),
 * generation determinism, the beacon save round trip, and the buy page: drawn, a page flip, a purchase counted against the
 * purchase limit.
 */
object BrowserHsStoreDiagnostics {
    private val q = BrowserDebug::quote

    fun json(s: HsStoreState): String {
        val sb = StringBuilder("{\"id\":").append(q(s.id))
        sb.append(",\"limit\":${s.itemPurchaseLimit},\"purchased\":${s.itemsPurchased},\"repair\":${s.repairPrice}")
        sb.append(",\"resources\":[").append(s.resources.joinToString(",") { "[${q(it.resource.name)},${it.count},${it.cost}]" }).append(']')
        sb.append(",\"pages\":[").append(s.pages.joinToString(",") { p ->
            "[" + p.sections.joinToString(",") { sec ->
                "{\"category\":${sec.category},\"title\":${q(sec.customTitle)},\"boxes\":[" + sec.storeBoxes.joinToString(",") { list ->
                    "[" + list.joinToString(",") { b ->
                        "[${b.blueprint?.name?.let(q) ?: "null"},${b.cost},${b.mysteryItem},${b.freeDrone?.let(q) ?: "null"},${b.count},${b.originalPrice}]"
                    } + "]"
                } + "]}"
            } + "]"
        }).append("]}")
        return sb.toString()
    }

    fun run(main: MainGame, container: GameContainer, graphics: Graphics, ship: String, seed: Int, only: List<String>): String {
        check(HS.active) { "Hyperspace is not active" }
        val rng = BrowserRandom.snapshot(); val native = NativeRandom.snapshot()
        try {
            main.startDiagnosticGame(ship, Difficulty.NORMAL)
            main.update(container, 0f)
            val game = main.currentState as InGameState
            val level = game.eventCreator.currentLevel()
            val out = ArrayList<String>()
            val ids = if (only.isNotEmpty()) only else HsStores.storeDefs.keys.sorted()
            for (id in ids) {
                val def = HsStores.storeDefs[id] ?: error("no customStore $id")
                NativeRandom.seed(seed)
                val a = HsStores.generate(game, def, level)
                NativeRandom.seed(seed)
                val b = HsStores.generate(game, def, level)
                val ja = json(a)
                check(ja == json(b)) { "$id: not deterministic" }
                // Beacon save round trip.
                val data = StoreData().also { it.hs = a }
                val e = Element("store"); data.saveToXML(e)
                val loaded = StoreData(); loaded.loadFromXML(game, BrowserXml.parse(BrowserXml.write(org.jdom2.Document(e))).rootElement)
                check(loaded.hs?.let { json(it) } == ja) { "$id: save round trip" }
                // StoreComplete::LoadStore makes the crew boxes' members anew: same races, new members.
                fun crewOf(s: HsStoreState) = s.pages.flatMap { p -> p.sections.flatMap { it.storeBoxes.flatten() } }.filter { it.category == 3 }.map { it.crew }
                val before = crewOf(a); val after = crewOf(loaded.hs!!)
                check(before.map { it?.race?.name } == after.map { it?.race?.name }) { "$id: crew races changed over save/load" }
                val named = before.zip(after).filter { (x, y) -> x != null && y != null }
                check(named.size < 2 || named.any { (x, y) -> x!!.name != y!!.name }) { "$id: crew boxes kept their members over save/load" }
                out += ja
            }

            // The buy page of the first store with two pages and an affordable section box.
            val ui = StringBuilder()
            for (id in ids) {
                val def = HsStores.storeDefs.getValue(id)
                NativeRandom.seed(seed)
                val s = HsStores.generate(game, def, level)
                if (s.pages.size < 2) continue
                // A weapon, drone or augment box (buying crew of a race whose art the engine lacks is statcrew's).
                val secIndex = s.pages[0].sections.indexOfFirst { it.category <= 2 && it.storeBoxes.firstOrNull()?.isNotEmpty() == true }
                if (secIndex < 0) continue
                val box = s.pages[0].sections[secIndex].storeBoxes[0][0]
                game.player.scrap = 100000
                val window = StoreWindow(game, game.player, StoreData().also { it.hs = s }) {}
                window.draw(graphics)
                val sec = s.pages[0].sections[secIndex]
                val equipment = sec.category != 2 && sec.category != 4
                val (x, y) = if (equipment) Pair(208 + 20, 103 + 20 + secIndex * 209) else Pair(203 + 60, 83 + 20 + secIndex * 209)
                window.updateUI(x, y)
                val before = game.player.scrap
                val disabled = (window.buttons.firstOrNull { it.contains(x, y) }?.disabled) ?: true
                window.mouseClick(Input.MOUSE_LEFT_BUTTON, x, y)
                val bought = box.count == 0
                ui.append("{\"id\":${q(id)},\"box\":${q(box.blueprint?.name ?: "")},\"disabled\":$disabled,\"bought\":$bought,")
                ui.append("\"paid\":${before - game.player.scrap},\"cost\":${box.cost},\"purchased\":${s.itemsPurchased},")
                window.updateUI(552, 17)
                window.mouseClick(Input.MOUSE_LEFT_BUTTON, 552, 17)
                ui.append("\"page\":${s.currentPage}}")
                window.draw(graphics)
                break
            }
            return "{\"level\":$level,\"stores\":[${out.joinToString(",")}],\"ui\":${if (ui.isEmpty()) "null" else ui}}"
        } finally {
            BrowserRandom.restore(rng); NativeRandom.restore(native)
            main.switchToMainMenu()
        }
    }
}
