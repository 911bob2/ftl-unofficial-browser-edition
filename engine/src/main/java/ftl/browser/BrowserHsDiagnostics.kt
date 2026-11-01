package ftl.browser

import org.jdom2.Document
import org.jdom2.Element
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.game.SaveProfile
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.save.HsSave

/**
 * hscore checks (`BrowserEngine.hsCoreProbe`): the HS gate matches the page's mod session, and run/profile saves carry
 * the `<hyperspace>` block exactly when modded, round-trip registered [HsSave] state and refuse other mod sets.
 * Runs in vanilla too (then: no block, a modded save is refused). Leaves the engine at the main menu.
 */
object BrowserHsDiagnostics {
    private const val KEY = "hscore-probe"

    /** Where two saves first differ, with some context of each. */
    private fun firstDiff(a: String, b: String): String {
        var i = 0
        while (i < a.length && i < b.length && a[i] == b[i]) i++
        val from = maxOf(0, i - 80)
        return "at $i of ${a.length}/${b.length}: [${a.substring(from, minOf(a.length, i + 120))}] vs [${b.substring(from, minOf(b.length, i + 120))}]"
    }

    fun run(main: MainGame, ship: String): String {
        check(HS.modded == BrowserMods.modded()) { "HS.modded differs from the session" }
        check(HS.active == (HS.modded && BrowserMods.hyperspace() && BrowserFiles.contains(HS.HYPERSPACE_XML))) { "HS.active differs from the session" }
        check(!HS.active || HS.config != null) { "HS active without a parsed hyperspace.xml" }
        check(HS.active || HS.config == null) { "hyperspace.xml config present while HS is inactive" }
        var runRead: String? = "unset"
        var profileRead: String? = "unset"
        HsSave.register(KEY, HsSave.Scope.RUN, { it.setAttribute("v", "run-42") }, { runRead = it?.getAttributeValue("v") })
        HsSave.register(KEY, HsSave.Scope.PROFILE, { it.setAttribute("v", "profile-7") }, { profileRead = it?.getAttributeValue("v") })
        val storedProfile = BrowserFiles.getProfile()
        val storedRun = BrowserFiles.getRun()
        val rng = BrowserRandom.snapshot()
        val out = ArrayList<String>()
        try {
            // Profile: block only when modded, state round trip, byte-identical re-save, another set's data not read.
            val profileXml = BrowserXml.write(main.profile.snapshot())
            check(profileXml.contains("<hyperspace ") == HS.modded) { "profile <hyperspace> block present=${!HS.modded} (modded=${HS.modded})" }
            val copy = SaveProfile.createBlank()
            copy.restore(BrowserXml.parse(profileXml))
            check(profileRead == (if (HS.modded) "profile-7" else "unset")) { "profile state read back as $profileRead" }
            val resaved = BrowserXml.write(copy.snapshot())
            // Everything outside <hyperspace> byte-identical; inside it, the same elements in any order (a workstream
            // writing a HashMap may reorder them; reported as a note so its owner can make the order stable).
            if (resaved != profileXml) {
                val a = canonical(profileXml); val b = canonical(resaved)
                check(a == b) { "profile does not re-save identically: ${firstDiff(a, b)}" }
                out += "note: profile <hyperspace> children reordered on re-save (${firstDiff(profileXml, resaved).take(160)})"
            }
            val other = BrowserXml.parse(profileXml)
            if (HS.modded) {
                other.rootElement.getChild(HsSave.ELEMENT).setAttribute("modSet", "0".repeat(64))
                SaveProfile.createBlank().load(other)
                check(profileRead == null) { "a profile of another set handed over its data: $profileRead" }
                val plain = BrowserXml.parse(profileXml); plain.rootElement.removeChild(HsSave.ELEMENT)
                SaveProfile.createBlank().load(plain)
                check(profileRead == null) { "a profile without a set handed over data: $profileRead" }
            } else {
                other.rootElement.addContent(Element(HsSave.ELEMENT).setAttribute("modSet", "f".repeat(64)))
                SaveProfile.createBlank().load(other)
            }
            out += "profile ok (block=${HS.modded})"

            // Run: start, save, reload through MainGame.loadSavedGame, compare the block, refusal. Empty ship: skipped.
            if (ship.isEmpty()) { out += "run skipped"; return "PASS hscore: modded=${HS.modded} hyperspace=${HS.active} ${out.joinToString("; ")}" }
            main.startDiagnosticGame(ship, Difficulty.EASY)
            val state = main.currentState as InGameState
            val runXml = BrowserXml.write(state.saveGameState())
            check(runXml.contains("<hyperspace ") == HS.modded) { "run <hyperspace> block present=${!HS.modded} (modded=${HS.modded})" }
            main.loadSavedGame(BrowserXml.parse(runXml))
            check(runRead == (if (HS.modded) "run-42" else "unset")) { "run state read back as $runRead" }
            val again = BrowserXml.write((main.currentState as InGameState).saveGameState())
            if (block(again) != block(runXml)) {
                // Per key: the probe's own key and the set of keys must survive; another workstream's key that
                // changes is listed as a note for its owner.
                val a = keyed(runXml); val b = keyed(again)
                check(a.keys == b.keys) { "run <hyperspace> keys changed across save/load: ${a.keys} -> ${b.keys}" }
                check(a[KEY] == b[KEY]) { "run state of the probe key changed across save/load" }
                val changed = a.keys.filter { a[it] != b[it] }
                out += if (changed.isEmpty()) "note: run <hyperspace> children reordered on re-save"
                else "note: run <hyperspace> data changed across save/load for ${changed.joinToString(",")} (" +
                    changed.joinToString("; ") { firstDiff(a[it]!!, b[it]!!).take(200) } + ")"
            }
            refused("run from another set") { d -> main.loadSavedGame(d) }.let { expectRefused ->
                val other = BrowserXml.parse(runXml)
                if (HS.modded) other.rootElement.getChild(HsSave.ELEMENT).setAttribute("modSet", "0".repeat(64))
                else other.rootElement.addContent(Element(HsSave.ELEMENT).setAttribute("modSet", "f".repeat(64)))
                expectRefused(other)
            }
            out += "run ok (ship=$ship, block=${HS.modded})"
        } finally {
            HsSave.unregister(KEY, HsSave.Scope.RUN)
            HsSave.unregister(KEY, HsSave.Scope.PROFILE)
            BrowserFiles.setRun(storedRun)
            BrowserFiles.setProfile(storedProfile)
            BrowserRandom.restore(rng)
            main.switchToMainMenu()
        }
        return "PASS hscore: modded=${HS.modded} hyperspace=${HS.active} ${out.joinToString("; ")}"
    }

    /** [xml] with the children of every element inside its `<hyperspace>` block sorted (order-insensitive compare). */
    private fun canonical(xml: String): String {
        val doc = BrowserXml.parse(xml)
        doc.rootElement.getChild(HsSave.ELEMENT)?.let(::sortDeep)
        return BrowserXml.write(doc)
    }

    private fun sortDeep(e: Element) {
        val kids = e.children.toList()
        for (k in kids) sortDeep(k)
        if (kids.size < 2) return
        for (k in kids) k.detach()
        for (k in kids.sortedBy(::key)) e.addContent(k)
    }

    private fun key(e: Element): String =
        e.name + e.attributes.map { it.name + "=" + it.value }.sorted().joinToString(" ", "[", "]") + e.children.joinToString("", "{", "}") { key(it) }

    /** The `<data key>` children of [xml]'s `<hyperspace>` block, each in canonical (order-insensitive) form. */
    private fun keyed(xml: String): Map<String, String> {
        val doc = BrowserXml.parse(xml)
        val hs = doc.rootElement.getChild(HsSave.ELEMENT) ?: return emptyMap()
        sortDeep(hs)
        return hs.getChildren("data").associate { (it.getAttributeValue("key") ?: "") to key(it) }
    }

    private fun block(xml: String): String? {
        val start = xml.indexOf("<hyperspace ")
        if (start < 0) return null
        val end = xml.indexOf("</hyperspace>", start)
        return if (end < 0) xml.substring(start, xml.indexOf("/>", start) + 2) else xml.substring(start, end)
    }

    private fun refused(what: String, load: (Document) -> Unit): (Document) -> Unit = { doc ->
        var refused = false
        try {
            load(doc)
        } catch (e: HsSave.Mismatch) {
            refused = true
        } catch (e: xyz.znix.xftl.savegame.RunLoadException) {
            refused = e.cause is HsSave.Mismatch
        }
        check(refused) { "$what was not refused" }
    }
}
