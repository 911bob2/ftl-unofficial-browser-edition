package ftl.android.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import xyz.znix.xftl.Ship
import xyz.znix.xftl.drones.DroneRuntime
import xyz.znix.xftl.drones.HackingNativeDrone
import xyz.znix.xftl.drones.NativeDrone
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.systems.Hacking
import java.lang.ref.WeakReference
import java.util.IdentityHashMap

/**
 * A replaced run is not kept alive (O-INFRA-6): DroneRuntime's static per-ship maps held the ships of every run a new
 * game or a loaded save replaced, each with its whole InGameState (map, events, ships), until the new run's first
 * unpaused frame. MainGame.setCurrentState now calls DroneRuntime.runInstalled. The same shared code runs on Android
 * and in the browser; the browser's check is the vanilla / ae engine modes (BrowserDiagnostics.probe).
 */
class ReplacedRunsTest {
    private val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
    @Suppress("UNCHECKED_CAST")
    private fun <T> blank(c: Class<T>): T = unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, c) as T

    /** A ship of [game] (no constructor: only its `sys`, which is all the maps look at). */
    private fun ship(game: InGameState): Ship = blank(Ship::class.java).also {
        Ship::class.java.getDeclaredField("sys").apply { isAccessible = true }.set(it, game)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <K, V> map(name: String): IdentityHashMap<K, V> =
        DroneRuntime::class.java.getDeclaredField(name).apply { isAccessible = true }.get(null) as IdentityHashMap<K, V>

    /** What join / joinHacking leave for a run's ship: its bodies in shipDrones and arrivals, a hacking system's body. */
    private fun joinRun(game: InGameState) {
        val s = ship(game)
        map<Ship, ArrayList<NativeDrone>>("shipDrones")[s] = ArrayList()
        map<Ship, ArrayList<NativeDrone>>("arrivals")[s] = ArrayList()
        val body = blank(HackingNativeDrone::class.java).also { it.standaloneOwner = s }
        map<Hacking, HackingNativeDrone>("hackingBodies")[blank(Hacking::class.java)] = body
    }

    private fun collected(ref: WeakReference<*>): Boolean {
        for (i in 0 until 50) {
            System.gc()
            if (ref.get() == null) return true
            @Suppress("UNUSED_VARIABLE") val pressure = ByteArray(1 shl 20)
            Thread.sleep(10)
        }
        return false
    }

    @Test
    fun aReplacedRunIsCollected() {
        DroneRuntime.reset()
        try {
            var first: InGameState? = blank(InGameState::class.java)
            joinRun(first!!)
            val firstRef = WeakReference(first)
            first = null
            // Ten more runs replace it, as new games and loads do, each installed in turn.
            var current: InGameState = blank(InGameState::class.java)
            val replaced = ArrayList<WeakReference<InGameState>>()
            for (i in 0 until 10) {
                val next = blank(InGameState::class.java)
                joinRun(next)
                replaced += WeakReference(current)
                current = next
                DroneRuntime.runInstalled(current)
            }
            assertEquals(0, DroneRuntime.otherRunEntries(current))
            assertEquals(1, map<Ship, Any>("shipDrones").size)
            assertEquals(1, map<Ship, Any>("arrivals").size)
            assertEquals(1, map<Hacking, Any>("hackingBodies").size)
            assertNotNull("the current run stays", map<Ship, Any>("shipDrones").keys.single().sys)
            if (!collected(firstRef)) throw AssertionError("the first run is still reachable after 10 replacements")
            for (r in replaced) if (!collected(r)) throw AssertionError("a replaced run is still reachable")
            assertNull(firstRef.get())
        } finally {
            DroneRuntime.reset()
        }
    }

    @Test
    fun withoutTheInstallTheMapsKeepEveryRun() {
        // How the leak looked on the JVM too: the maps alone keep a run reachable when nothing installs the next one.
        DroneRuntime.reset()
        try {
            var first: InGameState? = blank(InGameState::class.java)
            joinRun(first!!)
            val firstRef = WeakReference(first)
            first = null
            for (i in 0 until 10) joinRun(blank(InGameState::class.java))
            System.gc()
            assertNotNull(firstRef.get())
            assertEquals(11 * 3, DroneRuntime.otherRunEntries(blank(InGameState::class.java)))
        } finally {
            DroneRuntime.reset()
        }
    }
}
