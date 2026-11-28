package ftl.browser

import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.ship.HsAugments
import xyz.znix.xftl.sys.GameContainer

/**
 * Round 27 powers, stat boosts and augments sweep (BrowserAbilitySweepDiagnostics and the r27-powers oracle driver
 * find them; these hold each one found, with Hyperspace):
 *
 * - a removed augment keeps its key: ShipInfo::RemoveAugmentation (0x4637f0) only decrements `augList[name]`, and HS's
 *   UpdateAugments copies every key into augListWithHidden, so GetAugmentationValue's pick of the highest (lowest with
 *   `preferHigher="false"`) function value still sees the removed augment's value while HasAugmentation adds nothing
 *   for it (CustomAugments.cpp). Oracle r27p-smoke: after IMMUNE (SLUG_GEL 999) was added and removed, NANITE_GAS
 *   (SLUG_GEL 0.25) gives GetAugmentationValue("SLUG_GEL") 999 and HasAugmentation("SLUG_GEL") 1. Ours dropped the
 *   key and gave 0.25.
 * - a save with a crew member at a console action (MANNING) standing in a room without a system loads (vanilla too):
 *   the load set the action before any update, and its animation read the room's system unchecked, so the whole run
 *   failed to load (RunLoadException: NullPointerException, ability sweep r27p-ab6..ab8 at unique_mafan and blobcrystal,
 *   a crew member moved off its console in the frame the save was written). The original's load restores the saved
 *   state without such a test (CrewMember::LoadState).
 */
internal object GameplayR27PowersChecks {
    fun run(main: MainGame, container: GameContainer): String {
        val rng = BrowserRandom.snapshot()
        val nativeRng = NativeRandom.snapshot()
        try {
            val manned = mannedOffConsoleLoads(main, container)
            if (!HS.active) return "$manned; removedAugmentKeepsKey n/a (no Hyperspace)"
            return manned + "; " + removedAugmentKeepsKey(main, container)
        } finally {
            BrowserEngine.resetInput()
            BrowserRandom.restore(rng)
            NativeRandom.restore(nativeRng)
        }
    }

    /** A saved crew member set MANNING at a cell of a room without a system: the save loads, the crew member there. */
    private fun mannedOffConsoleLoads(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val bare = state.player.rooms.firstOrNull { it.system == null }
            ?: return "mannedOffConsoleLoads n/a (no room without a system)"
        val doc = state.saveGameState()
        val elem = doc.rootElement.getChild("playerShip")?.getChild("crew")?.getChildren("crewMember")?.firstOrNull()
            ?: return "mannedOffConsoleLoads n/a (no crew)"
        elem.setAttribute("x", bare.offsetX.toFloat().toString())
        elem.setAttribute("y", bare.offsetY.toFloat().toString())
        elem.setAttribute("action", "MANNING")
        val loaded = try {
            main.loadSavedGame(BrowserXml.parse(BrowserXml.write(doc)))
            main.currentState as InGameState
        } catch (e: Throwable) {
            error("a save with a MANNING crew member in room ${bare.id} (no system) did not load: $e / ${e.cause}")
        }
        val there = loaded.player.crew.firstOrNull { it.room.id == bare.id }
        check(there != null) { "the crew member did not load into room ${bare.id}: ${loaded.player.crew.map { "${it.codename} r${it.room.id} ${it.currentAction}" }}" }
        BrowserEngine.step(1f / 60f)
        return "mannedOffConsoleLoads (room ${bare.id}, ${there.codename} ${there.currentAction})"
    }

    /** (function, high augment, its value, low augment, its value): the data's pair, IMMUNE/NANITE_GAS when present. */
    private fun pair(state: InGameState): List<Any>? {
        val bm = state.blueprintManager
        val augs = bm.blueprints.values.filterIsInstance<AugmentBlueprint>().map { it.name }.distinct().sorted()
        fun fns(name: String) = HsAugments.definition(name)?.functions?.filter { it.second.sys == null } ?: emptyList()
        val hi = fns("IMMUNE").firstOrNull { it.first == "SLUG_GEL" }
        val lo = fns("NANITE_GAS").firstOrNull { it.first == "SLUG_GEL" }
        if (hi != null && lo != null && hi.second.preferHigher && lo.second.preferHigher && hi.second.value > lo.second.value)
            return listOf("SLUG_GEL", "IMMUNE", hi.second.value, "NANITE_GAS", lo.second.value)
        for (a in augs) for ((fn, fa) in fns(a)) {
            if (!fa.preferHigher || (bm.getOrNull(fn) as? AugmentBlueprint)?.stackable == true) continue
            for (b in augs) {
                if (b == a) continue
                val fb = fns(b).firstOrNull { it.first == fn }?.second ?: continue
                if (fb.preferHigher && fb.value < fa.value && fns(a).count { it.first == fn } == 1) return listOf(fn, a, fa.value, b, fb.value)
            }
        }
        return null
    }

    private fun removedAugmentKeepsKey(main: MainGame, container: GameContainer): String {
        var state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val p = pair(state) ?: return "removedAugmentKeepsKey n/a (no two augments share a function)"
        val fn = p[0] as String; val hi = p[1] as String; val hv = p[2] as Float; val lo = p[3] as String; val lv = p[4] as Float
        fun add(name: String) { val s = state.player; s.augments.add(state.blueprintManager.getOrNull(name) as AugmentBlueprint); s.cargoUpdated() }
        fun remove(name: String) { val s = state.player; s.augments.remove(s.augments.first { it.name == name }); s.cargoUpdated() }
        fun fnBp() = state.blueprintManager.getOrNull(fn) as? AugmentBlueprint
        // control: the low one alone
        add(lo)
        val alone = HsAugments.value(state.player, fn, fnBp())
        check(alone == lv) { "$lo alone: GetAugmentationValue($fn) $alone, want $lv" }
        remove(lo)
        // the high one added and removed first, then the low one: its key is still there at 0
        state = BrowserGameplayParityDiagnostics.fresh(main, container)
        add(hi); remove(hi); add(lo)
        val v = HsAugments.value(state.player, fn, fnBp())
        val n = HsAugments.count(state.player, fn)
        check(v == hv && n == 1) { "$hi added and removed, then $lo: GetAugmentationValue($fn) $v (want $hv), HasAugmentation $n (want 1)" }
        check(state.player.augments.none { it.name == hi }) { "$hi still installed" }
        return "removedAugmentKeepsKey ($hi then $lo: $fn $v, has $n; $lo alone $alone)"
    }
}
