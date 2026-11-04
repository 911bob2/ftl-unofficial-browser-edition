package xyz.znix.xftl.vanillasave

import org.jdom2.Document
import org.jdom2.Element
import xyz.znix.xftl.hs.HS

/**
 * A save imported from the original game (docs/SAVES.md, "Saves from the original game"). The device stores the
 * original files wrapped in the engine's root elements (savecore `NativeSave`: `<xftlSaveGame nativeImport="1">` and
 * `<xftl-profile nativeImport="1">`, each with the file as Base64 in a `<nativeImport>` child). The engine converts them
 * the first time the save is played, with the game files and mods of that save: the profile becomes the engine's
 * profile at start ([profileDocument]), the run becomes a run when Continue loads it (MainGame.continueSavedRun), and
 * the next save writes both in the engine's own format.
 */
object NativeImport {
    const val ELEMENT = "nativeImport"

    /** The file a wrapped run or profile holds. [prefix] is "" without mods, else Hyperspace's save prefix. */
    class File(@JvmField val kind: String, @JvmField val prefix: String, @JvmField val fileName: String, @JvmField val bytes: ByteArray) {
        val hyperspace get() = prefix.isNotEmpty()
    }

    /** A wrapped file the engine cannot use; the message is for the player. */
    class Failed(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

    /** The wrapped file of a run or profile document, or null when it is the engine's own. */
    @JvmStatic
    fun unwrap(doc: Document): File? {
        val root = doc.rootElement
        if (root.getAttribute(ELEMENT) == null) return null
        val child = root.getChild(ELEMENT) ?: throw Failed("The imported save is damaged. Import the files from the original game again.")
        val bytes = try {
            java.util.Base64.getDecoder().decode(child.textTrim)
        } catch (e: IllegalArgumentException) {
            throw Failed("The imported save is damaged. Import the files from the original game again.", e)
        }
        child.getAttributeValue("bytes")?.toIntOrNull()?.let { if (it != bytes.size) throw Failed("The imported save is damaged. Import the files from the original game again.") }
        return File(child.getAttributeValue("kind") ?: "", root.getAttributeValue("prefix") ?: "", child.getAttributeValue("file") ?: "", bytes)
    }

    /** The engine's profile document for a wrapped profile; [Failed] when the file does not read. */
    @JvmStatic
    fun profileDocument(file: File): Document {
        sessionMatches(file, "profile")
        val parsed = try {
            NativeProfile.parse(file.bytes, file.hyperspace)
        } catch (e: IllegalArgumentException) {
            throw Failed("The profile from the original game could not be read (${e.message}). Delete this save and import the files again, or import the run alone.", e)
        }
        return NativeProfile.toDocument(parsed, file.hyperspace && HS.modded, HS.modSetHash, HS.hsVersion)
    }

    /** A Hyperspace file needs a Hyperspace session and a file without mods a session without them. */
    @JvmStatic
    fun sessionMatches(file: File, what: String) {
        if (file.hyperspace && !HS.active)
            throw Failed("This $what comes from FTL with Hyperspace. Play it with the same mods (Saves, then Advanced: mods).")
        if (!file.hyperspace && HS.modded)
            throw Failed("This $what comes from FTL without mods. Play it without mods (Saves, then Advanced: mods).")
    }

    /** Parses a wrapped run against the session's game data; [Failed] with what went wrong. */
    @JvmStatic
    fun parseRun(file: File, content: xyz.znix.xftl.game.InGameState.GameContent): VanillaSaveData {
        sessionMatches(file, "run")
        if (file.bytes.size > 4 * 1024 * 1024) throw Failed("The run from the original game is too large.")
        return try {
            VanillaSaveParser.parse(file.bytes, EngineContent(content), file.hyperspace)
        } catch (e: RuntimeException) {
            val missing = Regex("Unknown blueprint (\\S+)|No such ship blueprint '?([^' ]+)").find(e.message ?: "")
            if (missing != null) throw Failed("The run from the original game names a ship these game files lack (" +
                (missing.groupValues[1].ifEmpty { missing.groupValues[2] }) + "). Play it with the mods it was made with.", e)
            throw Failed("The run from the original game could not be read. It may be from another version of FTL or of its mods.", e)
        }
    }

    /** What the run's layout depends on, from the session's game data. */
    private class EngineContent(val content: xyz.znix.xftl.game.InGameState.GameContent) : NativeContent {
        override fun layout(blueprint: String): Pair<List<Int>, Int> {
            val ship = content.blueprintManager.getShip(blueprint)
            return ship.rooms.map { it.size.x * it.size.y } to ship.doors.size
        }

        override fun isShipBlueprint(name: String) = content.blueprintManager.getOrNull(name) is xyz.znix.xftl.LazyShipBlueprint

        override fun hasCrystalTimers(race: String) = race == "crystal" && !(HS.active && xyz.znix.xftl.hs.crew.CustomCrewManager.isRace(race))

        override fun customSystemCount() = if (HS.active) maxOf(0, xyz.znix.xftl.hs.systems.HsSystems.lastSystemId() - xyz.znix.xftl.hs.systems.HsSystems.SYS_CUSTOM_FIRST + 1) else 0

        override fun dummyOxygen() = HS.active && HS.cfg().oxygenWithoutSystem

        override fun backgroundHasHover(def: Int) = xyz.znix.xftl.hs.render.HsBackgroundObjects.defAt(def)?.hoverAnim?.anim?.isNotEmpty() == true

        override fun backgroundHasInactive(def: Int) = xyz.znix.xftl.hs.render.HsBackgroundObjects.defAt(def)?.inactiveAnim?.anim?.isNotEmpty() == true

        override fun triggeredHasTimer(def: Int) = content.eventManager.hs?.triggeredDefs?.getOrNull(def)?.let { it.triggerMinTime != -1f }
            ?: throw IllegalArgumentException("Unknown triggered event $def")
    }

    /**
     * The run's Hyperspace state as the engine's own run block (`<hyperspace>`, HsSave), so every registered reader gets
     * its part the way a saved run gives it: the events block (variables, queues, aliases, beacons, hidden augments,
     * seeds, triggered events), the event buttons and the next object id. Readers without a part get none and start
     * fresh (background objects, credits, the final boss).
     */
    @JvmStatic
    fun hyperspaceRoot(run: NativeHsRun, currentSectorSeed: Int): Element {
        val root = Element("xftlSaveGame")
        val hs = Element(xyz.znix.xftl.hs.save.HsSave.ELEMENT).setAttribute("modSet", HS.modSetHash ?: "")
        if (HS.active) hs.setAttribute("hs", HS.hsVersion ?: xyz.znix.xftl.hs.HsConfig.HS_VERSION)
        root.addContent(hs)
        fun data(key: String) = Element("data").setAttribute("key", key).also { hs.addContent(it) }
        data(xyz.znix.xftl.hs.save.HsSpaceId.KEY).setAttribute("next", run.nextSpaceId.toString())
        val events = data("events")
        val vars = Element("playerVariables")
        for ((k, v) in run.playerVariables.toSortedMap()) if (v != 0) vars.addContent(Element("v").setAttribute("n", k).setAttribute("x", v.toString()))
        events.addContent(vars)
        val jumps = Element("jumpEvents")
        for (j in run.jumpEvents) jumps.addContent(Element("e").setAttribute("event", j.event).setAttribute("label", j.label)
            .setAttribute("loop", j.loop.toString()).setAttribute("priority", j.priority.toString()))
        events.addContent(jumps)
        val deaths = Element("deathEvents")
        for (d in run.deathEvents) deaths.addContent(Element("e").setAttribute("event", d.event).setAttribute("label", d.label)
            .setAttribute("jumpClear", d.jumpClear.toString()).setAttribute("thisFight", d.thisFight.toString()).setAttribute("priority", d.priority.toString()))
        events.addContent(deaths)
        val queue = Element("eventQueue")
        for ((event, label, seed) in run.eventQueue) queue.addContent(Element("e").setAttribute("event", event).setAttribute("label", label).setAttribute("seed", seed.toString()))
        events.addContent(queue)
        val aliases = Element("aliases")
        for ((k, a) in run.aliases) aliases.addContent(Element("a").setAttribute("name", k).setAttribute("event", a.event)
            .setAttribute("jumpClear", a.jumpClear.toString()).setAttribute("once", a.once.toString()))
        events.addContent(aliases)
        val beacons = Element("beacons")
        for ((i, n) in run.renamedBeacons) beacons.addContent(Element("renamed").setAttribute("i", i.toString()).setAttribute("name", n))
        for ((i, p) in run.regeneratedBeacons) beacons.addContent(Element("regenerated").setAttribute("i", i.toString())
            .setAttribute("event", p.first).setAttribute("seed", p.second.toString()))
        for (b in run.priorityEventReq) beacons.addContent(Element("priorityReq").setAttribute("met", b.toString()))
        events.addContent(beacons)
        val hidden = Element("hiddenAugs")
        for ((name, n) in run.hiddenAugments.groupingBy { it }.eachCount()) hidden.addContent(Element("a").setAttribute("n", name).setAttribute("x", n.toString()))
        events.addContent(hidden)
        events.addContent(Element("seeds").setAttribute("currentSeed", run.currentSeed.toString()).setAttribute("customSeed", run.customSeed.toString())
            .setAttribute("sectorSeed", currentSectorSeed.toString()).setAttribute("questSeed", run.questSeed.toString())
            .setAttribute("bossFleetSeed", run.bossFleetSeed.toString())
            .setAttribute("worldRng", "${run.worldRng.first},${run.worldRng.second}").setAttribute("secretRng", "${run.secretRng.first},${run.secretRng.second}"))
        val triggered = Element("triggered")
        for (t in run.triggeredEvents) {
            val v = intArrayOf(t.seed, t.loops) + t.values + intArrayOf(if (t.triggered) 1 else 0)
            val e = Element("t").setAttribute("name", t.name).setAttribute("def", t.defIdx.toString()).setAttribute("v", v.joinToString(","))
            t.timer?.let { (time, goal) -> e.setAttribute("timer", "${java.lang.Float.floatToRawIntBits(time)},${java.lang.Float.floatToRawIntBits(goal)}") }
            triggered.addContent(e)
        }
        events.addContent(triggered)
        val buttons = data("eventButtons")
        for (idx in run.eventButtons) buttons.addContent(Element("b").setAttribute("idx", idx.toString()))
        return root
    }
}
