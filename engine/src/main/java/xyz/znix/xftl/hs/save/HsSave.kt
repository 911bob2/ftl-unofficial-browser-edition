package xyz.znix.xftl.hs.save

import org.jdom2.Element
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.HsConfig

/**
 * Hyperspace/mod state in run and profile saves (PLAN.md I3, engine side). Only when [HS.modded] does a save get a
 * `<hyperspace modSet="<hash>" hs="1.23.2">` child (hs omitted for content-only mod sets) holding one
 * `<data key="...">` element per registered key; vanilla saves are written exactly as before.
 *
 * A run belongs to one mod set: [verify] refuses a modded run in another set or in vanilla play, and a vanilla run
 * in a modded session, with [Mismatch]. (The loader keeps modded saves apart per set, so this is a second line.) A
 * profile moved to other mods stays usable: its unlocks are keyed by id, and only [readProfile] skips the block.
 *
 * Other packages keep their state here with [register]: the writer fills its `<data>` element, the reader gets it
 * back on load, or null when the save has none (a fresh run/profile, or a save made before the key existed).
 */
object HsSave {
    enum class Scope { RUN, PROFILE }

    class Entry(val key: String, val scope: Scope, val writer: (Element) -> Unit, val reader: (Element?) -> Unit)

    /** A save from a different mod set (or vanilla vs modded); [message] is meant for the player. */
    class Mismatch(message: String) : IllegalStateException(message)

    const val ELEMENT = "hyperspace"

    private val entries = LinkedHashMap<String, Entry>()

    /** Register a run or profile state block under a unique [key]; registering a key again replaces it. */
    @JvmStatic
    fun register(key: String, scope: Scope, writer: (Element) -> Unit, reader: (Element?) -> Unit) {
        require(key.isNotEmpty())
        val first = entries.put("${scope.name}:$key", Entry(key, scope, writer, reader)) == null
        // The profile is read at engine start, before the content (and so the packages that register here) loads:
        // a profile key registered later (the first time) gets the block of the profile already read (null for a new
        // profile). Registering again (a content reload) keeps the state the key already has.
        val profile = lastProfile
        if (first && scope == Scope.PROFILE && profile != null) reader(blocks(profile)[key])
    }

    /** The HS child of the profile read last, or an empty one for a new profile (null before any profile). */
    private var lastProfile: Element? = null

    @JvmStatic
    fun unregister(key: String, scope: Scope) {
        entries.remove("${scope.name}:$key")
    }

    /** Run saves (InGameState.saveGameState): appends the block. Callers check [HS.modded] first. */
    @JvmStatic
    fun writeRun(root: Element) = write(root, Scope.RUN)

    /** Profile saves (SaveProfile.save). Callers check [HS.modded] first. */
    @JvmStatic
    fun writeProfile(root: Element) = write(root, Scope.PROFILE)

    private fun write(root: Element, scope: Scope) {
        val hs = Element(ELEMENT)
        hs.setAttribute("modSet", HS.modSetHash ?: "")
        if (HS.active) hs.setAttribute("hs", HS.hsVersion ?: HsConfig.HS_VERSION)
        for (e in entries.values) {
            if (e.scope != scope) continue
            val data = Element("data").setAttribute("key", e.key)
            e.writer(data)
            hs.addContent(data)
        }
        root.addContent(hs)
    }

    /**
     * Check that a run save belongs to this session's mod set; throws [Mismatch] otherwise. Vanilla
     * play costs one child lookup (vanilla saves have no such child).
     */
    @JvmStatic
    fun verify(root: Element, kind: String) {
        val hs = root.getChild(ELEMENT)
        if (!HS.modded) {
            if (hs != null) throw Mismatch("This $kind was made with mods (mod set ${short(hs.getAttributeValue("modSet"))}). " +
                "Load the same mods to continue it; vanilla play cannot use it.")
            return
        }
        if (hs == null) throw Mismatch("This $kind was made without mods. It cannot be used with the loaded mods " +
            "(mod set ${short(HS.modSetHash)}).")
        val set = hs.getAttributeValue("modSet")
        if (set != HS.modSetHash) throw Mismatch("This $kind was made with a different set of mods (mod set " +
            "${short(set)}, loaded ${short(HS.modSetHash)}). Load the mods it was made with to continue it.")
    }

    /** After a modded run save is loaded: hand each run key its block. Callers check [HS.modded] first. */
    @JvmStatic
    fun readRun(root: Element) = read(root, Scope.RUN)

    @JvmStatic
    fun readProfile(root: Element) {
        // A save moved to other mods (Advanced) keeps its unlocks; the Hyperspace data of another mod set is not read.
        val other = root.getChild(ELEMENT)?.getAttributeValue("modSet").let { it != null && it != HS.modSetHash }
        read(if (other) Element("profile") else root, Scope.PROFILE)
    }

    /** A new profile (none saved yet): profile keys get null, now and when they register later. */
    @JvmStatic
    fun newProfile() = readProfile(Element("profile"))

    private fun read(root: Element, scope: Scope) {
        val hs = root.getChild(ELEMENT) ?: Element(ELEMENT)
        if (scope == Scope.PROFILE) lastProfile = hs
        val blocks = blocks(hs)
        for (e in entries.values) if (e.scope == scope) e.reader(blocks[e.key])
    }

    private fun blocks(hs: Element): Map<String, Element> {
        val blocks = HashMap<String, Element>()
        for (d in hs.getChildren("data")) d.getAttributeValue("key")?.let { blocks.putIfAbsent(it, d) }
        return blocks
    }

    private fun short(hash: String?) = if (hash.isNullOrEmpty()) "unknown" else hash.take(12)
}
