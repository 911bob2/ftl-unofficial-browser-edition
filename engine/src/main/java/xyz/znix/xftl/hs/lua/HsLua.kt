// Ported from FTL-Hyperspace (GPL-3.0-or-later) commit 1b05a07 (lua/ script API and .i members, src/ files named below),
// not from the 1.23.2 tag.
package xyz.znix.xftl.hs.lua

import ftl.browser.BrowserDebug
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.rendering.Graphics

/**
 * The Hyperspace Lua runtime as the engine sees it (LuaScriptInit + LuaLibScript in HS). Started from
 * BrowserEngine.start whenever Hyperspace is active, with or without mod scripts, as HS creates its Lua state at
 * start-up (Global.cpp:138); vanilla never starts it, and until then nothing in this package is initialised. Engine hooks go through [LuaEvents], each guarded by `HS.luaActive`.
 */
object HsLua {
    /** Registered callbacks per InternalEvents / RenderEvents id: hooks skip all work when false. */
    @JvmField val hasInt = BooleanArray(128)
    @JvmField val hasRen = BooleanArray(64)
    @JvmField var hasGameEvents = false
    @JvmField var hasInit = false
    @JvmField var game: MainGame? = null
    @JvmField var graphics: Graphics? = null

    /** Script load errors (HS shows each in an error dialog) and the last log lines. */
    @JvmField val scriptErrors = ArrayList<String>()
    private val logLines = ArrayList<String>()
    /** Distinct error messages (first line of the cause) and how often each occurred, for the profile. */
    private val errorKinds = LinkedHashMap<String, Int>()
    private var logStart = 0
    private const val LOG_KEEP = 400
    private val engineErrors = LinkedHashMap<String, Int>()
    @JvmField var loadedScripts = 0

    /** BrowserEngine.start: run the session's scripts (hyperspace.xml <scripts> order, none on plain Hyperspace) and the on_load callbacks. */
    @JvmStatic
    fun startForSession(game: MainGame, graphics: Graphics) {
        if (!HS.active && HS.scripts.isEmpty()) return
        if (!LuaJs.available()) {
            HS.unsupported("lua", "runtime", "web/vendor/hs-lua.wasm was not loaded")
            return
        }
        start(game, graphics)
        // hyperspace.xml <scripts> order (HsConfig); the session list only decides whether the wasm is loaded
        for (path in HS.config?.scripts ?: HS.scripts) runScript(path)
        refreshRegistrations()
        LuaJs.onLoad()
        LuaGraphics.restore()
        // HS prints these lines (hyperspace.i's "Hyperspace SWIG Lua loaded", script prints) before the resources
        // load; the loading screen outlasts PrintHelper's 5 s, so the menu shows none of them (oracle frames
        // 00-welcome and 04-beacon1 of mv-seed-12345, render round 2).
        LuaPrint.messages.clear(); LuaPrint.timer = 0f
        // HS loads the scripts before the main menu first opens, so MAIN_MENU fires for that first opening too
        if (game.currentState is xyz.znix.xftl.game.MainMenuState) LuaEvents.mainMenu()
    }

    /** A fresh Lua state with HS's libraries and API, no scripts yet (also used by the diagnostics probe). */
    @JvmStatic
    fun start(game: MainGame, graphics: Graphics) {
        this.game = game; this.graphics = graphics
        Handles.reset(); LuaViews.reset(); LuaStored.reset(); LuaReg.clear(); LuaStructs.reset(); LuaPrint.reset(); LuaGraphics.reset(); LuaFps.reset(); LuaStarMap.reset(); xyz.znix.xftl.hs.sector.HsStarMap.reset()
        scriptErrors.clear(); logLines.clear(); logStart = 0; engineErrors.clear(); errorKinds.clear(); loadedScripts = 0
        hasInt.fill(false); hasRen.fill(false); hasGameEvents = false; hasInit = false
        LuaAdapters.install()
        LuaJs.reinit()
        syncTicks = 0; exitSent = false
        HS.luaActive = true
    }

    private var syncTicks = 0
    private var exitSent = false

    /**
     * Once per engine step: the private folder of mod scripts goes to storage every two seconds or so (and at the end,
     * [shutdown]); a script's os.exit ends the session (the original closes FTL) with a message naming the mod.
     */
    @JvmStatic
    fun tick() {
        if (!exitSent && LuaJs.exitRequested()) {
            exitSent = true
            LuaJs.vfsSync()
            LuaJs.hostExit(LuaJs.exitSource(), 0)
        }
        if (++syncTicks >= 120) { syncTicks = 0; LuaJs.vfsSync() }
    }

    /** The engine stops: what the scripts wrote is kept. */
    @JvmStatic
    fun shutdown() {
        if (HS.luaActive && LuaJs.available()) LuaJs.vfsSync()
    }

    /** Lua is switched off (diagnostics); hooks stop firing. */
    @JvmStatic
    fun stop() {
        HS.luaActive = false
        hasInt.fill(false); hasRen.fill(false); hasGameEvents = false; hasInit = false
        Handles.reset(); LuaViews.reset(); LuaStored.reset()
    }

    @JvmStatic
    fun runScript(path: String): String? {
        val err = LuaJs.runFile(path)
        if (err != null) scriptErrors.add("$path: $err") else loadedScripts++
        return err
    }

    @JvmStatic
    fun runString(name: String, code: String): String? {
        val err = LuaJs.run(name, code, false)
        LuaGraphics.restore()
        refreshRegistrations()
        return err
    }

    @JvmStatic
    fun refreshRegistrations() {
        for (i in 1 until hasInt.size) hasInt[i] = LuaJs.has(0, i) > 0
        for (i in 1 until hasRen.size) hasRen[i] = LuaJs.has(1, i) > 0
        hasInit = LuaJs.has(3, 0) > 0
        hasGameEvents = LuaJs.has(4, 0) > 0
    }

    /** script.on_* registered something (also during callbacks). */
    @JvmStatic
    fun registered(kind: Int, id: Int) {
        when (kind) {
            0 -> if (id in hasInt.indices) hasInt[id] = true
            1 -> if (id in hasRen.indices) hasRen[id] = true
            3 -> hasInit = true
            4 -> hasGameEvents = true
        }
    }

    /** Diagnostics: receives every log/print/error line as it is written (BrowserStatCrewDiagnostics). */
    @JvmField var sink: ((Int, String) -> Unit)? = null

    /** log (0), print (1), callback/script errors (2). HS writes these to stdout and ftl_hs.log. */
    @JvmStatic
    fun log(kind: Int, text: String) {
        sink?.invoke(kind, text)
        val line = (if (kind == 2) "[error] " else if (kind == 1) "[print] " else "") + text.trimEnd()
        if (logLines.size < LOG_KEEP) logLines.add(line) else { logLines[logStart] = line; logStart = (logStart + 1) % LOG_KEEP }
        if (kind >= 2) {
            BrowserDebug.warning("Lua: $line")
            val key = text.trim().lines().let { l -> if (l.size > 1) l[0] + " | " + l[1].trim() else l[0] }.take(300)
            val n = errorKinds[key]
            if (n != null) errorKinds[key] = n + 1 else if (errorKinds.size < 300) errorKinds[key] = 1
        }
        LuaJs.pageLog(kind, line)
        if (kind == 1) LuaPrint.add(text)
    }

    fun engineError(where: String, e: Throwable) {
        val key = where + ": " + e
        val n = engineErrors[key]
        engineErrors[key] = (n ?: 0) + 1
        if (n == null) BrowserDebug.warning("Lua bridge: engine error in $where: $e")
    }

    fun recentLog(): List<String> = logLines.subList(logStart, logLines.size) + logLines.subList(0, logStart)

    /** BrowserEngine.hsProfile(): per-callback calls/instructions/time from the runtime plus bridge state. */
    @JvmStatic
    fun profileJson(reset: Boolean): String {
        if (!HS.luaActive || !LuaJs.available()) return "{\"active\":false}"
        val q = BrowserDebug::quote
        val sb = StringBuilder()
        sb.append("{\"active\":true,\"scripts\":").append(loadedScripts)
        sb.append(",\"scriptErrors\":[").append(scriptErrors.joinToString(",") { q(it) }).append(']')
        sb.append(",\"engineErrors\":[").append(engineErrors.entries.joinToString(",") { "{\"error\":${q(it.key)},\"count\":${it.value}}" }).append(']')
        sb.append(",\"handles\":").append(Handles.live)
        sb.append(",\"handlePeak\":").append(Handles.peak).append(",\"forcedCollections\":").append(Handles.forcedCollections)
        sb.append(",\"errorKinds\":[").append(errorKinds.entries.joinToString(",") { "{\"error\":${q(it.key)},\"count\":${it.value}}" }).append(']')
        sb.append(",\"runtime\":").append(LuaJs.profile(reset))
        sb.append(",\"log\":[").append(recentLog().takeLast(50).joinToString(",") { q(it) }).append("]}")
        return sb.toString()
    }
}

/**
 * HS's PrintHelper (UserInterface/CommandConsole.cpp): `print` queues its text (arguments joined with four spaces,
 * trailing ones included) for on-screen display; the fields are the Lua-visible settings with HS's defaults.
 */
object LuaPrint : LuaTyped {
    override val luaType get() = LuaApi.T_PrintHelper
    @JvmField var x = 100
    @JvmField var y = 100
    @JvmField var font = 10
    @JvmField var lineLength = 400
    @JvmField var messageLimit = 10
    @JvmField var duration = 5
    @JvmField var useSpeed = false
    @JvmField val messages = ArrayDeque<String>()
    @JvmField var timer = 0f

    fun reset() {
        x = 100; y = 100; font = 10; lineLength = 400; messageLimit = 10; duration = 5; useSpeed = false
        messages.clear(); timer = 0f
    }

    private val white = xyz.znix.xftl.rendering.Colour(1f, 1f, 1f, 1f)

    /**
     * PrintHelper::Render (MouseControl::OnRender hook): the queued messages, one per line, while the timer is
     * within [duration]; then the oldest message is dropped. The timer advances 1/NumFrames per render, or
     * SpeedFactor x 0.0625 with useSpeed (HS 1b05a07 CommandConsole.cpp PrintHelper::Render).
     */
    @JvmStatic
    fun render() {
        if (messages.isEmpty()) return
        if (timer <= duration) {
            val sb = StringBuilder()
            for (m in messages) sb.append(m).append('\n')
            LuaGraphics.printAutoNewlines(font, x.toFloat(), y.toFloat(), lineLength, sb.toString(), white)
            // HS: useSpeed ? SpeedFactor * 0.0625 : 1.0 / NumFrames; NumFrames is 0 during the first second, so the
            // increment is infinite then and a message printed at start-up (HS's "Hyperspace SWIG Lua loaded") goes
            // after one frame
            timer += if (useSpeed) LuaFps.speedFactor * 0.0625f else 1f / LuaFps.numFrames
        } else {
            timer = 0f
            messages.removeFirst()
        }
    }

    /** PrintHelper::AddMessage: `messages.size() > messageLimit` compares as size_t, so a negative limit never trims. */
    fun add(message: String) {
        timer = 0f
        messages.addLast(message)
        if (messageLimit >= 0 && messages.size > messageLimit) messages.removeFirst()
    }
}
