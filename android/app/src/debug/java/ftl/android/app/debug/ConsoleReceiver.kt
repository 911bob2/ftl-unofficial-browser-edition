package ftl.android.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import ftl.android.app.game.GameRenderer
import ftl.browser.BrowserEngine
import xyz.znix.xftl.game.InGameState

/**
 * Debug builds only: runs a debug console command on the game thread, for driving the app on an emulator. A command none
 * of the cases below takes goes to the debug console's one registry (devutil/ConsoleRegistry.kt, docs/CONSOLE.md), as if
 * typed there; the release build has the console behind the launcher's "Enable debug console" switch.
 *   adb shell am broadcast -n dev.ftl911bob.unofficial/ftl.android.app.debug.ConsoleReceiver --es cmd "'store'"
 */
class ConsoleReceiver : BroadcastReceiver() {
    private companion object { val ballast = ArrayList<ByteArray>(); val held = ArrayList<java.nio.ByteBuffer>() }

    /** After a full collection: the Java heap and its limits, native allocations, and the process's PSS by kind (KB). */
    private fun memoryLine(context: Context, tag: String): String {
        val r = Runtime.getRuntime()
        repeat(2) { r.gc(); System.runFinalization() }
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val m = android.os.Debug.MemoryInfo()
        android.os.Debug.getMemoryInfo(m)
        val kinds = listOf("java-heap", "native-heap", "code", "stack", "graphics", "private-other", "system", "total-pss", "total-swap")
            .joinToString(" ") { "$it=" + m.getMemoryStat("summary.$it") }
        val mb = 1048576
        return "$tag javaUsedMB=${(r.totalMemory() - r.freeMemory()) / mb} javaTotalMB=${r.totalMemory() / mb} javaMaxMB=${r.maxMemory() / mb} " +
            "memoryClass=${am.memoryClass} largeMemoryClass=${am.largeMemoryClass} nativeAllocMB=${android.os.Debug.getNativeHeapAllocatedSize() / mb} textures=${xyz.znix.xftl.rendering.Texture.liveCount} textureMB=${xyz.znix.xftl.rendering.Texture.liveBytes / mb} luaKiB=${if (xyz.znix.xftl.hs.HS.luaActive) xyz.znix.xftl.hs.lua.LuaJs.gcKiB() else 0} $kinds"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd") ?: return
        if (cmd == "crash-main") throw IllegalStateException("Forced crash on the main thread password=hunter2")
        if (cmd == "crash-thread") { Thread({ throw IllegalStateException("Forced crash on a thread") }, "forced-crash").start(); return }
        if (cmd == "crash-game") {
            GameRenderer.active?.let { r -> r.onFatal(IllegalStateException("Forced game error")) }
            return
        }
        if (cmd == "crash-native") { android.os.Process.sendSignal(android.os.Process.myPid(), 11); return }
        if (cmd.startsWith("logspam ")) {
            // A burst of log lines, for the debug log's size limit.
            val line = "x".repeat(200) + " spam"
            repeat(cmd.substringAfter(' ').toInt()) { Log.i("FTL-Spam", "$it $line") }
            return
        }
        if (cmd.startsWith("iconhome ")) {
            val prefs = context.getSharedPreferences(ftl.android.app.icons.AppIcons.PREFS, Context.MODE_PRIVATE)
            val home = cmd.substringAfter(' ')
            prefs.edit().putString("home-for-tests", home.takeIf { it != "clear" }).apply()
            return
        }
        if (cmd.startsWith("iconrefused ")) {
            context.getSharedPreferences(ftl.android.app.icons.AppIcons.PREFS, Context.MODE_PRIVATE).edit().putBoolean("refused", cmd.endsWith("on")).apply()
            return
        }
        if (cmd.startsWith("hapticdevice ")) {
            // Pretends a weaker vibrator (PRIMITIVES, AMPLITUDE, ON_OFF, NONE) for Settings > Haptics; "auto" is the real one.
            val k = cmd.substringAfter(' ').uppercase()
            ftl.android.app.game.Haptics.setTestKind(context, ftl.android.app.game.Haptics.Kind.entries.firstOrNull { it.name == k })
            val h = ftl.android.app.game.Haptics.load(context)
            Log.i(ftl.android.app.game.Haptics.TAG, "device kind=${h.kind} strength=${h.strength}")
            return
        }
        if (cmd.startsWith("updateurl ") || cmd == "updatereset" || cmd == "updatestate") {
            // The update check against a stub server instead of GitHub (docs/ANDROID.md, "Updates"): updateurl <url>|clear;
            // updatereset forgets the last check, its ETag, the release seen and Not now; updatestate logs what is stored.
            val prefs = context.getSharedPreferences(ftl.android.app.update.Updates.PREFS, Context.MODE_PRIVATE)
            val u = ftl.android.app.update.UpdateCheck
            when {
                cmd.startsWith("updateurl ") -> prefs.edit().putString(ftl.android.app.update.Updates.TEST_URL, cmd.substringAfter(' ').takeIf { it != "clear" }).commit()
                cmd == "updatereset" -> prefs.edit().apply { for (k in listOf(u.LAST, u.ETAG, u.ETAG_URL, u.TAG, u.URL, u.OFFERED, u.DISMISSED)) remove(k) }.commit()
            }
            Log.i(ftl.android.app.update.Updates.TAG, "state " + prefs.all.toSortedMap())
            return
        }
        if (cmd.startsWith("iconrandom ")) {
            ftl.android.app.icons.AppIcons(context).random = cmd.endsWith("on")
            return
        }
        if (cmd.startsWith("appicon ")) {
            val icons = ftl.android.app.icons.AppIcons(context)
            val icon = icons.all.first { it.number == cmd.substringAfter(' ') }
            Log.i("FTL-Icon", "console set ${icon.number} -> ${icons.set(icon)}")
            return
        }
        if (cmd == "audio") {
            Log.i("FTL-Audio", ftl.android.audio.AudioMixer.get().stats())
            return
        }
        if (cmd.startsWith("ballast ")) {
            // Stands in for a smaller heap limit: this many MB of the Java heap stay taken.
            val n = cmd.substring(8).toInt()
            ballast.clear()
            repeat(n) { ballast.add(ByteArray(1 shl 20)) }
            Log.i("FTL-Mem", "ballast ${n}MB")
            return
        }
        if (cmd.startsWith("hold ") && android.os.Build.VERSION.SDK_INT >= 27) {
            // Stands in for a phone with less RAM, or for textures that live on the host here: this many MB of RAM stay taken.
            val n = cmd.substring(5).toInt()
            Thread {
                val noise = ByteArray(1 shl 20).also { java.util.Random().nextBytes(it) }
                val random = java.util.Random()
                repeat(n / 16) {
                    val region = android.os.SharedMemory.create("hold", 16 shl 20)
                    val map = region.mapReadWrite()
                    repeat(16) { noise[random.nextInt(noise.size)] = random.nextInt().toByte(); random.nextBytes(noise); map.put(noise) }
                    held.add(map)
                }
                Log.i("FTL-Mem", "hold ${n}MB done")
            }.start()
            return
        }
        if (cmd.startsWith("pcmcheck ")) {
            Thread { ftl.android.audio.PcmCheck.run(cmd.substring(9)) }.start()
            return
        }
        if (cmd.startsWith("audiobuf ")) {
            // Decodes a sound into the mixer's cache without playing it.
            ftl.android.audio.AudioMixer.get().buffer(cmd.substring(9))
            return
        }
        if (cmd == "mem") {
            Log.i("FTL-Mem", memoryLine(context, "mem"))
            return
        }
        val renderer = GameRenderer.active ?: return
        if (cmd == "frames") {
            Log.i("FTL-Gfx", "frames=${renderer.frames} at=${android.os.SystemClock.elapsedRealtime()}")
            return
        }
        if (cmd.startsWith("touchtrace ")) {
            TouchProbe.trace(cmd.endsWith("on"))
            return
        }
        renderer.events.add(Runnable {
            if (cmd.startsWith("touchstate ")) {
                TouchProbe.state(cmd.substring(11))
                return@Runnable
            }
            if (cmd == "newrun") {
                BrowserEngine.newRun("PLAYER_SHIP_HARD", 1)
                return@Runnable
            }
            if (cmd.startsWith("loadfile ")) {
                val xml = java.io.File(context.filesDir, cmd.substring(9)).readText()
                BrowserEngine.restore(xml)
                Log.i("FTL-Mem", "loaded saveChars=${xml.length}")
                return@Runnable
            }
            if (cmd == "reload") {
                // What Continue does: the run saved, then loaded through the game's own path.
                BrowserEngine.save()
                val xml = BrowserEngine.runSave()
                if (xml == null) Log.w("FTL-Mem", "no run save")
                else {
                    BrowserEngine.restore(xml)
                    Log.i("FTL-Mem", "reloaded saveChars=${xml.length}")
                }
                return@Runnable
            }
            val state = BrowserEngine.getGame()?.currentState as? InGameState
            if (state == null) Log.w("FTL-Debug", "Not in flight: $cmd")
            else if (cmd.startsWith("tutgo ")) {
                val c = BrowserEngine.getGame().tutorialAdapter?.controller
                val def = c?.javaClass?.getDeclaredField("definition")?.apply { isAccessible = true }?.get(c) as? ftl.browser.BrowserTutorialDefinition
                val i = def?.steps?.indexOfFirst { it.id == cmd.substring(6) } ?: -1
                if (i >= 0) {
                    c!!.javaClass.getDeclaredField("index").apply { isAccessible = true }.setInt(c, i)
                    c.javaClass.getDeclaredField("canContinue").apply { isAccessible = true }.setBoolean(c, def!!.steps[i].continueButton)
                }
            }
            else if (cmd.startsWith("showevent ")) state.debugShowEvent(cmd.substring(10))
            else if (cmd == "power") {
                // Each player system's power (selected/max, ion limit, ion timer) and the free reactor power, for the haptics checks.
                Log.i("FTL-Haptic", "power free=${state.player.powerAvailable} " + state.player.systems.filterIsInstance<xyz.znix.xftl.systems.MainSystem>()
                    .joinToString(" ") { "${it.codename}=${it.powerSelected}/${it.energyLevels}" + (it.ionPowerLimit?.let { l -> "(ion$l,${it.ionTimer.toInt()}s)" } ?: "") })
            }
            else if (cmd.startsWith("ion ")) {
                // ion <system codename> <units>: ion damage to a player system, as a hit would deal it.
                val (name, units) = cmd.substring(4).split(' ')
                state.player.systems.first { it.codename == name }.dealDamage(0, units.toInt())
            }
            // The debug console's panel (its kind, view, grid, scroll) and the scroll of its last frames, for the frame strips.
            else if (cmd == "console-panel") Log.i("FTL-Console", "panel " + state.debugConsole.debugPanel())
            else if (cmd == "console-trace") Log.i("FTL-Console", "trace " + state.debugConsole.debugTrace())
            else if (cmd == "state") {
                Log.i("FTL-State", "ship=${state.player.type.name} hull=${state.player.health} scrap=${state.player.scrap} sector=${state.currentBeacon.sector.sectorNumber} " +
                    "enemy=${state.enemy?.type?.name} weapons=${state.player.hardpoints.map { it.weapon?.type?.name }} crew=${state.player.crew.size} dialogue=${state.shipUI.isWindowOpen}")
            }
            else if (cmd == "laststand") {
                val map = state.javaClass.getMethod("getGameMap").invoke(state) as xyz.znix.xftl.sector.GameMap
                val sector = map.generateSector(map.sectors.last().last(), state)
                val jump = state.javaClass.declaredMethods.first { it.name == "beginJumpOut" }
                jump.isAccessible = true
                jump.invoke(state, sector.startBeacon)
            }
            else if (cmd == "gfxcheck") {
                val q = xyz.znix.xftl.rendering.DrawQueue
                val w = BrowserEngine.canvasWidth()
                val h = BrowserEngine.canvasHeight()
                fun frame(unbatched: Boolean): ByteArray {
                    q.unbatched = unbatched
                    BrowserEngine.renderFrame(0f)
                    q.unbatched = false
                    return ftl.browser.BrowserPixels.read(0, 0, w, h)
                }
                fun differ(a: ByteArray, b: ByteArray): Int {
                    var n = 0
                    for (i in a.indices step 4) if (a[i] != b[i] || a[i + 1] != b[i + 1] || a[i + 2] != b[i + 2]) n++
                    return n
                }
                val a = frame(false)
                val control = frame(false)
                val b = frame(true)
                Log.i("FTL-Gfx", "check ${w}x$h batched-again=${differ(a, control)} unbatched=${differ(a, b)} of ${w * h} flushes=${q.lastFrameFlushes}")
            }
            else if (cmd == "gfx") {
                val q = xyz.znix.xftl.rendering.DrawQueue
                Log.i("FTL-Gfx", "flushes=${q.lastFrameFlushes} draws=${q.lastFrameDraws} uploads=${q.lastFrameUploads}")
            }
            else if (cmd.startsWith("envset ")) {
                val beacon = state.currentBeacon
                val type = xyz.znix.xftl.sector.Beacon.EnvironmentType.valueOf(cmd.substring(7))
                beacon.debugSetEnvironment(type.create(state, beacon))
            }
            else if (cmd == "enemyleave") {
                // The enemy jumps away, as one running from a fight does.
                state.hsEnemyJumpLeave()
            }
            else if (cmd == "conprobe") {
                // the debug console's state (tests/console.mjs's consoleProbe): its line, its panel, the ship's gear
                val c = state.debugConsole
                val p = state.player
                Log.i("FTL-Console", c.debugState(state.isDebugConsoleVisible) + " panel=" + c.debugPanel() +
                    " keyboardTop=" + xyz.znix.xftl.game.TouchMode.keyboardTop + " weapons=" + p.hardpoints.mapNotNull { it.weapon?.type?.name } +
                    " cargo=" + p.allCargo().map { it.name } + " augments=" + p.augments.map { it.name } + " crew=" + p.crew.size +
                    " last=" + c.outputText().lastOrNull())
            }
            else if (cmd == "overcap") {
                // the open ship window's over-capacity and crew boxes, as laid out now (tests/overcap.mjs's overcapProbe)
                Log.i("FTL-Overcap", state.shipUI.diagnosticOpenShipWindow?.diagnosticOverCapacity ?: "{\"window\":null}")
            }
            else if (cmd == "overcappage") state.shipUI.diagnosticOpenShipWindow?.diagnosticTurnOverPage()
            else if (cmd.startsWith("conitems ")) {
                // conitems <get|unlock> <kind>: a picker's items (count, keys)
                val a = cmd.split(' ')
                val kind = xyz.znix.xftl.devutil.ConsoleCheats.kindOf(a[1], a[2])
                val list = if (kind == null) emptyList() else xyz.znix.xftl.devutil.ConsoleItems.items(state, kind)
                Log.i("FTL-Console", "items ${a[1]} ${a[2]} ${list.size}: " + list.take(40).joinToString(" ") { it.key + it.tags })
            }
            else if (cmd.startsWith("hscmd ")) {
                // A Hyperspace console command (LUA, LOADEVENT, SYS ALL, ...), as if typed into the console.
                state.debugConsole.execute(cmd.substring(6))
            }
            else if (cmd.startsWith("modbutton ")) {
                // A made-up mod's event button (`modbutton <name> <location>`, or `modbutton <name> <x> <y>` for a
                // customLocation), with the ship button's art and an event of its own name, for the touch HUD tests.
                val a = cmd.split(' ')
                val place = if (a.size >= 4) "<customLocation x=\"${a[2]}\" y=\"${a[3]}\"/>" else "<location>${a.getOrElse(2) { "4" }}</location>"
                val xml = "<eventButton name=\"${a[1]}\"><event load=\"${a[1]}_EVENT\"/><image>statusUI/top_ship</image>$place" +
                    "<tooltip>Made-up mod button</tooltip><jumpClear>false</jumpClear></eventButton>"
                val node = ftl.browser.BrowserXml.parse(xml).rootElement
                xyz.znix.xftl.hs.render.HsEventButtons.parse(node) { e, _ -> e.getAttributeValue("load") ?: "" }
                xyz.znix.xftl.hs.render.HsEventButtons.apply(emptyList(), listOf(node))
            }
            else if (cmd.startsWith("weaponpower ")) {
                // weaponpower <slot>: powers that weapon, as a tap on its unpowered slot does.
                val w = state.player.hardpoints[cmd.substring(12).trim().toInt()].weapon
                if (w != null) Log.i("FTL-Debug", "weaponpower ${w.type.name} ${state.player.weapons?.setWeaponPower(w, true)}")
            }
            else if (cmd.startsWith("dronepower ")) {
                // dronepower <slot> <on|off>: that drone's power, as a tap on its slot does.
                val (slot, on) = cmd.substring(11).trim().split(' ')
                Log.i("FTL-Debug", "dronepower $slot ${state.player.drones?.setDronePower(slot.toInt(), on == "on")}")
            }
            else if (cmd.startsWith("weaponcharge ")) {
                // weaponcharge <slot> <fraction>: that weapon's charge, as a fraction of its charge time.
                val (slot, f) = cmd.substring(13).trim().split(' ')
                state.player.hardpoints[slot.toInt()].weapon?.let { it.timeCharged = it.chargeTime * f.toFloat() }
            }
            else if (cmd == "touchweapons") {
                // Opens the touch HUD's weapon column, as a tap on the weapons system does.
                val hud = state.shipUI.touch
                if (hud != null) {
                    val boxes = hud.javaClass.getDeclaredMethod("boxes").apply { isAccessible = true }.invoke(hud) as List<*>
                    val box = boxes.firstOrNull { b -> b!!.javaClass.getDeclaredMethod("getSystem").apply { isAccessible = true }.invoke(b) is xyz.znix.xftl.systems.Weapons }
                    hud.javaClass.getDeclaredField("selected").apply { isAccessible = true }.set(hud, box)
                    Log.i("FTL-Debug", "touchweapons ${box != null}")
                }
            }
            else if (cmd == "clearstations") {
                for (crew in state.player.crew) crew.savedPosition = null
            }
            else if (cmd == "tut") {
                val adapter = BrowserEngine.getGame().tutorialAdapter
                val overlay = BrowserEngine.getGame().tutorialOverlay
                if (adapter == null || overlay == null) Log.i("FTL-Tut", "none")
                else {
                    val c = adapter.controller
                    val b = overlay.continueBounds(state)
                    Log.i("FTL-Tut", "${c.step.id} ${c.canContinue} ${b.x + b.width / 2} ${b.y + b.height / 2}")
                }
            }
            else {
                state.debugConsole.execute(cmd)
                Log.i("FTL-Debug", "Ran: $cmd")
            }
        })
    }
}
