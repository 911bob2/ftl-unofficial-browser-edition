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
            out += loaderRules(main)

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

    /**
     * What the loaders accept (parity sweep M2), on names of our own appended to this session's content:
     * - a blueprintList takes every child's value, whatever its name (1.6.14 0x607950, 1.6.9 0x5e3e20);
     * - `<item_modify>` reads every child's type and range, whatever its name (ProcessModifyItem 0x5e1ed0 / 0x5bd9c0);
     * - an eventList's entries are its `<event>` children only (ProcessEventList 0x5e7d10 / 0x5c3af0); modded, a list
     *   with none (but other children) stays unregistered;
     * - every `<seeds>` node of hyperspace.xml counts, with EventsParser::ParseBoolean (Resources.cpp);
     * - Hyperspace: every `<defaults><customLockdown>` is parsed onto the default lockdown (Resources.cpp).
     */
    private fun loaderRules(main: MainGame): String {
        val content = main.gameContent
        val bm = content.blueprintManager
        val em = content.eventManager
        bm.diagnosticAppend(em.df, BrowserXml.parse(
            "<FTL><blueprintList name=\"PS_M2_LIST\"><name>LASER_BURST_1</name><bane>MISSILES_1</bane><name>ION_1</name></blueprintList></FTL>"
        ).rootElement.clone() as Element)
        val list = bm.getOrNull("PS_M2_LIST") as? xyz.znix.xftl.BlueprintList
        check(list?.names == listOf("LASER_BURST_1", "MISSILES_1", "ION_1")) { "blueprintList entries: ${list?.names}" }

        em.diagnosticAppend(BrowserXml.parse(
            "<FTL><event name=\"PS_M2_ITEM\"><text>t</text><item_modify><iten type=\"scrap\" min=\"7\" max=\"9\"/><item type=\"fuel\" min=\"1\" max=\"2\"/></item_modify></event>" +
                "<eventList name=\"PS_M2_EVLIST\"><text>stray</text><event><text>a</text></event><autoReward level=\"LOW\">standard</autoReward><event><text>b</text></event></eventList>" +
                (if (HS.modded) "<eventList name=\"PS_M2_NOLIST\"><text>t</text><choice><text>c</text></choice></eventList>" else "") + "</FTL>"))
        val item = em.getOrNull("PS_M2_ITEM") as? xyz.znix.xftl.sector.Event ?: error("PS_M2_ITEM was not loaded")
        val scrap = item.resourceRanges[xyz.znix.xftl.game.Resource.SCRAP]!!
        val fuel = item.resourceRanges[xyz.znix.xftl.game.Resource.FUEL]!!
        check(scrap.min == 7 && scrap.max == 9 && fuel.min == 1 && fuel.max == 2) { "item_modify ranges: scrap ${scrap.min}-${scrap.max}, fuel ${fuel.min}-${fuel.max}" }
        val evList = em.getOrNull("PS_M2_EVLIST") as? xyz.znix.xftl.sector.EventList ?: error("PS_M2_EVLIST was not loaded")
        check(evList.events.size == 2) { "eventList entries: ${evList.events.size}" }
        if (HS.modded) check(em.getOrNull("PS_M2_NOLIST") == null) { "an eventList without <event> children was registered" }

        val seeds = xyz.znix.xftl.hs.sector.HsSeeds
        val enabled = seeds.enabled; val metaVars = seeds.allowMetaVars
        try {
            seeds.readConfig(BrowserXml.parse("<FTL><seeds enabled=\"false\"/><seeds allowMetaVars=\"false\"/><seeds enabled=\"True\"/></FTL>").rootElement)
            check(seeds.enabled && !seeds.allowMetaVars) { "seeds blocks: enabled=${seeds.enabled} allowMetaVars=${seeds.allowMetaVars}" }
            seeds.readConfig(BrowserXml.parse("<FTL><seeds enabled=\" true\" allowMetaVars=\"tRUE\"/></FTL>").rootElement)
            check(!seeds.enabled && !seeds.allowMetaVars) { "seeds ParseBoolean: enabled=${seeds.enabled} allowMetaVars=${seeds.allowMetaVars}" }
        } finally {
            seeds.enabled = enabled; seeds.allowMetaVars = metaVars
        }

        if (HS.active) {
            val config = HS.config
            try {
                HS.config = xyz.znix.xftl.hs.HsConfig.parse(BrowserXml.parse(
                    "<FTL><version>^1.23.2</version><defaults><customLockdown><duration>3</duration></customLockdown></defaults>" +
                        "<defaults><customLockdown><health>77</health></customLockdown><customLockdown><canDilate>false</canDilate></customLockdown></defaults></FTL>").rootElement)
                val d = xyz.znix.xftl.hs.ship.HsLockdowns.defaults()
                check(d.duration == 3f && d.health == 77 && !d.canDilate) { "default lockdown from two <defaults>: $d" }
            } finally {
                HS.config = config
            }
        }
        configErrorRules()
        return "loader rules ok"
    }

    /**
     * Parity sweep M2b: hyperspace.xml errors as Resources.cpp has them (checked against the original with the oracle's
     * ps-m2b sessions): a bad number ends its pass with HS's box, later settings of that pass are lost; the version box.
     */
    private fun configErrorRules() {
        val parse = { body: String -> xyz.znix.xftl.hs.HsConfig.parse(BrowserXml.parse("<FTL>$body</FTL>").rootElement) }
        val cast = xyz.znix.xftl.hs.HsConfig.BAD_CAST
        val wrong = xyz.znix.xftl.hs.HsConfig.WRONG_VERSION
        // The ps-m2b-num fixture mod over HS's own settings: both passes stop, the second script never runs.
        val num = parse("<version>^1.23.2</version><scripts><script>a.lua</script></scripts><purchaseLimitNumber fontSize=\"0\" x=\"-16\" y=\"106\"/>" +
            "<events><eventFile>x</eventFile></events><defaults><checkCargo>true</checkCargo><erosion><speed>2</speed><growBreachMultiplier>x</growBreachMultiplier></erosion></defaults>" +
            "<defaults><choiceRequiresCrew>true</choiceRequiresCrew></defaults><version>^1.99.0</version>" +
            "<purchaseLimitNumber fontSize=\"abc\" x=\"1\" y=\"2\"/><scripts><script>b.lua</script></scripts><forceDlc enabled=\"true\"/>")
        check(num.errorBoxes == listOf("Failed parsing hyperspace.xml\n$cast", "Error parsing hyperspace.xml\n$cast")) { "boxes: ${num.errorBoxes}" }
        check(num.earlyStop == 4 && num.mainStop == 7) { "stops: ${num.earlyStop} ${num.mainStop}" }
        check(num.scripts == listOf("a.lua") && !num.forceDlc) { "main pass went on: ${num.scripts} forceDlc=${num.forceDlc}" }
        check(num.defaultCheckCargo && !num.defaultChoiceRequiresCrew && num.erosionGrowBreachMultiplier == 1f) { "early pass went on" }
        check(num.eventFiles.isEmpty()) { "ReadCustomEventFiles ran after the early error: ${num.eventFiles}" }
        check(num.purchaseLimitFontSize == 0 && num.purchaseLimitX == -16 && num.purchaseLimitY == 106) { "purchase limit ${num.purchaseLimitY}" }
        check(num.problems.any { "'x'" in it } && num.problems.any { "'abc'" in it }) { "problems: ${num.problems}" }
        val cut = num.nodesOf("defaults")
        check(cut.size == 1 && cut[0].getChild("checkCargo") != null && cut[0].getChild("erosion")?.children?.map { it.name } == listOf("speed")) { "defaults kept: ${cut.map { d -> d.children.map { it.name } }}" }
        val root = BrowserXml.parse("<FTL><events><a/></events><defaults><erosion><time>y</time></erosion></defaults><events><b/></events></FTL>").rootElement
        val early = xyz.znix.xftl.hs.HsConfig.parse(root)
        check(early.passRoot(root, true)!!.children.map { it.name } == listOf("events", "defaults") && early.passRoot(root, false) === root) { "pass roots" }
        // Attributes in turn: the ones before the bad one stay set.
        val limit = parse("<version>^1.23.2</version><purchaseLimitNumber fontSize=\"3\" x=\"5\" y=\"q\"/>")
        check(limit.purchaseLimitFontSize == 3 && limit.purchaseLimitX == 5 && limit.purchaseLimitY == 60) { "attributes in turn" }
        // The version: a newer one is the warning box (after the early pass), a bad syntax ends the early pass.
        check(parse("<version>^1.99.0</version>").errorBoxes == listOf(wrong)) { "wrong version box" }
        check(parse("<version>^1.23.0</version><version>=1.23.2</version>").errorBoxes.isEmpty()) { "accepted version" }
        check(parse("").errorBoxes == listOf(wrong)) { "no <version>" }
        val syntax = parse("<version>1.23</version><defaults><checkCargo>true</checkCargo></defaults>")
        check(syntax.errorBoxes == listOf(xyz.znix.xftl.hs.HsConfig.VERSION_SYNTAX) && !syntax.defaultCheckCargo && syntax.earlyStop == 0) { "version syntax" }
        check(parse("<version>332</version>").errorBoxes.isEmpty() && parse("<version>3x2</version>").errorBoxes == listOf("Failed parsing hyperspace.xml\n$cast")) { "integer version" }
        // Strict casts: lexical_cast takes no spaces.
        check(parse("<version>^1.23.2</version><showDummyEquipmentSlots enabled=\"true\" opacity=\" 0.5\"/>").errorBoxes.size == 1) { "a spaced number" }
        // The store reads PurchaseLimitIndicatorInfo from the config (HS: x -16, y 106 in Hyperspace's own file).
        val stores = xyz.znix.xftl.hs.ship.HsStores
        stores.load(num)
        check(stores.limitY == 106) { "store purchase limit y ${stores.limitY}" }
        stores.load(HS.config)
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
