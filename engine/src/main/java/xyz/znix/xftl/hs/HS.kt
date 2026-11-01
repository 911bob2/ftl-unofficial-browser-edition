package xyz.znix.xftl.hs

import ftl.browser.BrowserDebug
import ftl.browser.BrowserFiles
import ftl.browser.BrowserMods
import ftl.browser.BrowserXml

/**
 * Hyperspace/mod gate. Every Hyperspace call site checks [active] (or [luaActive]) first, so vanilla play reads
 * one static field and runs nothing else; see .build/hs/PLAN.md I2.
 */
object HS {
    /** Any mod archive is loaded (content mods included). */
    @JvmField var modded = false
    /** The merged files contain `data/hyperspace.xml`: Hyperspace rules apply. */
    @JvmField var active = false
    /** Mod Lua scripts are loaded and callbacks may run. Set by the Lua runtime once it is up. */
    @JvmField var luaActive = false

    /** Mod-set identity for saves, null in vanilla. */
    @JvmField var modSetHash: String? = null
    @JvmField var hsVersion: String? = null
    @JvmField var mods: List<String> = emptyList()
    @JvmField var scripts: List<String> = emptyList()

    /** The parsed `data/hyperspace.xml` while [active], else null. */
    @JvmField var config: HsConfig? = null

    const val HYPERSPACE_XML = "data/hyperspace.xml"

    /** Hyperspace `<forceDlc enabled="true">`: Advanced Edition is always on and the hangar hides its toggle. */
    @JvmStatic
    fun forceDlc(): Boolean = active && config?.forceDlc == true

    /** [config] for code that only runs when [active]. */
    @JvmStatic
    fun cfg(): HsConfig = config ?: error("Hyperspace is not active")

    /** Read the page's mod session at engine start. Vanilla: `window.ftlModSession` is undefined. */
    @JvmStatic
    fun init() {
        modded = BrowserMods.modded()
        active = false; luaActive = false
        modSetHash = null; hsVersion = null; mods = emptyList(); scripts = emptyList(); config = null
        capability.clear()
        HsLoad.wrapped = 0
        HsLoad.wrappedLate = 0
        if (!modded) return
        modSetHash = BrowserMods.hash()
        hsVersion = BrowserMods.hsVersion()
        mods = lines(BrowserMods.names())
        scripts = lines(BrowserMods.scripts())
        val declared = BrowserMods.hyperspace()
        val present = BrowserFiles.contains(HYPERSPACE_XML)
        active = declared && present
        if (declared != present) unsupported("session", "ftlModSession",
            if (present) "hyperspace:false but $HYPERSPACE_XML is present" else "hyperspace:true but $HYPERSPACE_XML is missing")
        if (!active) return
        val cfg = try {
            HsConfig.parse(BrowserXml.parse(String(BrowserFiles.read(HYPERSPACE_XML), Charsets.UTF_8)).rootElement)
        } catch (e: Exception) {
            // HS: "Failed parsing hyperspace.xml" (rapidxml error) and every setting keeps its default.
            unsupported("config", HYPERSPACE_XML, "unparsable: ${e.message}")
            HsConfig.defaults()
        }
        config = cfg
        cfg.versionMessage?.let { println("Hyperspace: $it"); BrowserDebug.warning("Hyperspace: $it") }
        for (p in cfg.problems) unsupported("config", HYPERSPACE_XML, p)
        xyz.znix.xftl.hs.ship.HsShips.registerCapabilities()
        xyz.znix.xftl.hs.weapons.HsWeapons.registerImplemented()
        xyz.znix.xftl.hs.render.HsRender.registerImplemented()
        HsCapability.run(cfg)
        if (capability.isNotEmpty())
            println("Hyperspace: ${capability.size} unsupported feature uses recorded (BrowserEngine.hsCapabilityReport())")
    }

    private fun lines(s: String) = if (s.isEmpty()) emptyList() else s.split('\n')

    /** Unsupported tags/APIs seen in the loaded mods: key "kind|where|name" -> count. */
    val capability = LinkedHashMap<String, Int>()

    /** Record a Hyperspace feature the engine does not implement yet; logged once per key (debug log). */
    @JvmStatic
    @JvmOverloads
    fun unsupported(kind: String, where: String, name: String, count: Int = 1) {
        val key = "$kind|$where|$name"
        val n = capability[key]
        capability[key] = (n ?: 0) + count
        if (n == null) BrowserDebug.warning("Hyperspace: unsupported $kind '$name' in $where")
    }

    /**
     * JSON: `{modded, hyperspace, lua, modSet, hsVersion, config: {version, versionOk, versionMessage, problems},
     * unsupported: [{kind, where, name, count}], byName: [{kind, name, files, count}], ignored: [{where, name, count}]}`.
     * `byName` sums `unsupported` over files, most used first; `ignored` lists names neither vanilla nor HS reads.
     */
    @JvmStatic
    fun capabilityReport(): String {
        val q = BrowserDebug::quote
        // XML names a package marks implemented after the start scan (while its content loads) leave the report too.
        val current = capability.entries.filter { (key, _) ->
            val (kind, _, name) = key.split('|', limit = 3)
            !((kind == "xml" || kind == "xml-attr") && HsCapability.isImplemented(name))
        }
        val items = current.joinToString(",") { (key, count) ->
            val (kind, where, name) = key.split('|', limit = 3)
            "{\"kind\":${q(kind)},\"where\":${q(where)},\"name\":${q(name)},\"count\":$count}"
        }
        class Agg(var files: Int = 0, var count: Int = 0)
        val agg = LinkedHashMap<String, Agg>()
        for ((key, count) in current) {
            val (kind, _, name) = key.split('|', limit = 3)
            val a = agg.getOrPut("$kind|$name") { Agg() }
            a.files++; a.count += count
        }
        val byName = agg.entries.sortedByDescending { it.value.count }.joinToString(",") { (key, a) ->
            val (kind, name) = key.split('|', limit = 2)
            "{\"kind\":${q(kind)},\"name\":${q(name)},\"files\":${a.files},\"count\":${a.count}}"
        }
        val ignored = HsCapability.ignored.entries.joinToString(",") { (key, count) ->
            val (where, name) = key.split('|', limit = 2)
            "{\"where\":${q(where)},\"name\":${q(name)},\"count\":$count}"
        }
        val c = config
        val cfgJson = if (c == null) "null" else
            "{\"version\":${c.versionRequirement?.let(q) ?: "null"},\"versionOk\":${c.versionOk}," +
                "\"versionMessage\":${c.versionMessage?.let(q) ?: "null"},\"problems\":[${c.problems.joinToString(",") { q(it) }}]," +
                "\"values\":{\"forceDlc\":${c.forceDlc},\"seedsEnabled\":${c.seedsEnabled},\"defaultCheckCargo\":${c.defaultCheckCargo}," +
                "\"controllableIonDroneDelay\":${c.controllableIonDroneDelay},\"controllableIonDroneDelayInitial\":${c.controllableIonDroneDelayInitial}," +
                "\"dummyEquipmentSlotsOpacity\":${c.dummyEquipmentSlotsOpacity},\"consoleInvertCaps\":${c.consoleInvertCaps}," +
                "\"advancedCrewTooltipRounding\":${c.advancedCrewTooltipRounding}," +
                "\"purchaseLimit\":[${c.purchaseLimitFontSize},${c.purchaseLimitX},${c.purchaseLimitY}]," +
                "\"systemNoPurchaseThreshold\":[${c.systemNoPurchaseThreshold},${c.systemNoPurchaseThresholdValue},${q(c.systemNoPurchaseThresholdReplace)}]," +
                "\"savePrefix\":${q(c.savePrefix)},\"saveInheritMode\":${c.saveInheritMode},\"scripts\":${c.scripts.size}," +
                "\"eventFiles\":${c.eventFiles.size},\"nodes\":{${c.nodes.entries.joinToString(",") { "${q(it.key)}:${it.value.size}" }}}}}"
        return "{\"modded\":$modded,\"hyperspace\":$active,\"lua\":$luaActive," +
            "\"modSet\":${modSetHash?.let(q) ?: "null"},\"hsVersion\":${hsVersion?.let(q) ?: "null"},\"config\":$cfgJson," +
            "\"xmlWrapped\":${HsLoad.wrapped},\"xmlWrappedLate\":${HsLoad.wrappedLate},\"unsupported\":[$items],\"byName\":[$byName],\"ignored\":[$ignored]}"
    }
}
