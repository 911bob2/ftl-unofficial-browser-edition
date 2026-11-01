package ftl.android.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import ftl.android.app.game.GameRenderer
import ftl.browser.BrowserEngine
import xyz.znix.xftl.game.InGameState

/**
 * Debug builds only: runs a debug console command on the game thread, for driving the app on an emulator. The console's
 * own key is a game hotkey under Hyperspace.
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
