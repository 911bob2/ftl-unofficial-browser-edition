// Ported from FTL-Hyperspace (GPL-3.0-or-later) commit 1b05a07 (lua/ script API and .i members, src/ files named below),
// not from the 1.23.2 tag.
package xyz.znix.xftl.hs.lua

import java.util.IdentityHashMap

/**
 * Engine objects exposed to Lua as int32 handles: `generation << 24 | slot` (hs-lua/hslua.c splits them the same way).
 * An object gets a handle on first exposure and keeps it while Lua holds a userdata for it; the runtime's __gc of the
 * last such userdata releases it (see hs-lua/hslua.c). Objects made per access (value copies, `Hyperspace.Point(x, y)`)
 * hold their handle until Lua's collector finalizes their userdata, so the number of live handles follows the
 * collector, not the scripts' live data. When the slots run out, a full collection releases those first; only when
 * Lua really holds [MAX_SLOTS] objects does exposing one more fail, as a Lua error (never a handle that names another
 * object).
 *
 * Choice for destroyed objects: Hyperspace hands Lua raw pointers, so a script keeping a crew member past its
 * death reads freed memory (undefined, usually garbage or a crash). Here a userdata keeps its engine object
 * reachable, so such a read returns the object's last state; [invalidate] (called where the engine destroys an
 * object that Lua may hold) bumps the generation, after which reads see nil/0 and method calls fail with an error.
 */
object Handles {
    private const val SLOT_BITS = 24
    private const val SLOT_MASK = (1 shl SLOT_BITS) - 1
    private const val GEN_MASK = 0x7f
    /** Slot 0 is never used (handle 0 is nil). */
    const val MAX_SLOTS = SLOT_MASK

    private var objs = arrayOfNulls<Any>(256)
    private var gens = IntArray(256)
    private var freeSlots = IntArray(64)
    private var freeCount = 0
    private var next = 1
    private val ids = IdentityHashMap<Any, Int>()

    /** Per-object Lua tables (`obj.table`): permanent ids, independent of handles. */
    private val tableIds = IdentityHashMap<Any, Int>()
    private var nextTable = 1

    /** Full collections forced because the slots ran out (diagnostics). */
    @JvmStatic var forcedCollections = 0
        private set

    /**
     * Lua's collector paces itself on Lua's own heap; an exposed object also holds engine memory (the object, its
     * entry in [ids], the slot), which Lua cannot see. So every [STEP_EVERY] new handles the collector does the work
     * it would do for [ENGINE_BYTES_PER_HANDLE] bytes each, and short-lived objects are finalized at the rate they
     * are made.
     */
    private const val ENGINE_BYTES_PER_HANDLE = 128
    private const val STEP_EVERY = 256
    private var fresh = 0

    @JvmStatic
    fun expose(o: Any?): Int {
        if (o == null) return 0
        if (o is xyz.znix.xftl.drones.AbstractDrone || o is xyz.znix.xftl.hs.weapons.HsHackingDroneCollisionBody) return expose(xyz.znix.xftl.drones.DroneRuntime.canon(o))
        val known = ids[o]
        if (known != null) return known
        if (++fresh >= STEP_EVERY) {
            fresh = 0
            LuaJs.gcStep(STEP_EVERY * ENGINE_BYTES_PER_HANDLE / 1024)
        }
        if (freeCount == 0 && next > MAX_SLOTS) {
            // Every slot is taken: finalize what Lua no longer references, which gives slots back through release.
            forcedCollections++
            LuaJs.fullGc()
            val again = ids[o]
            if (again != null) return again
            if (freeCount == 0) throw LuaError("too many engine objects held by Lua scripts ($MAX_SLOTS)")
        }
        val slot: Int
        if (freeCount > 0) slot = freeSlots[--freeCount]
        else {
            if (next >= objs.size) {
                objs = objs.copyOf(objs.size * 2)
                gens = gens.copyOf(gens.size * 2)
            }
            slot = next++
        }
        var g = (gens[slot] + 1) and GEN_MASK
        if (g == 0) g = 1
        gens[slot] = g
        objs[slot] = o
        val h = (g shl SLOT_BITS) or slot
        ids[o] = h
        return h
    }

    @JvmStatic
    fun get(h: Int): Any? {
        val slot = h and SLOT_MASK
        if (slot == 0 || slot >= next) return null
        return if (gens[slot] == (h ushr SLOT_BITS)) objs[slot] else null
    }

    /** Lua dropped its last userdata for this handle. */
    @JvmStatic
    fun release(h: Int) {
        val slot = h and SLOT_MASK
        if (slot == 0 || slot >= next || gens[slot] != (h ushr SLOT_BITS)) return
        val o = objs[slot]
        if (o != null) ids.remove(o)
        objs[slot] = null
        gens[slot] = (gens[slot] + 1) and GEN_MASK
        if (freeCount == freeSlots.size) freeSlots = freeSlots.copyOf(freeSlots.size * 2)
        freeSlots[freeCount++] = slot
    }

    /** The engine destroyed [o]: existing userdata for it become stale; its `.table` is dropped. */
    @JvmStatic
    fun invalidate(o: Any) {
        val h = ids.remove(o)
        if (h != null) {
            val slot = h and SLOT_MASK
            objs[slot] = null
            gens[slot] = (gens[slot] + 1) and GEN_MASK
            // the slot is reused only after Lua's __gc releases the stale handle (release ignores it: wrong gen),
            // so it is leaked until reset; destruction is rare compared to exposure.
        }
        val t = tableIds.remove(o)
        if (t != null) LuaJs.dropTable(t)
    }

    @JvmStatic
    fun tableId(h: Int): Int {
        val o = get(h) ?: return 0
        val known = tableIds[o]
        if (known != null) return known
        val id = nextTable++
        tableIds[o] = id
        return id
    }

    /** The run is over: per-object tables of its objects are dropped; userdata still held by scripts keep their handles until collected. */
    fun endRun() {
        // The keys first: an IdentityHashMap entry on a JVM is a view of a table slot, gone once the map changes.
        val done = tableIds.keys.filter { LuaRunScope.of(it) }
        for (k in done) tableIds.remove(k)?.let { LuaJs.dropTable(it) }
    }

    val live: Int get() = ids.size
    /** Slots ever handed out (the high-water mark of live handles). */
    val peak: Int get() = next - 1

    fun reset() {
        objs = arrayOfNulls(256); gens = IntArray(256); freeCount = 0; next = 1; fresh = 0; forcedCollections = 0
        ids.clear(); tableIds.clear(); nextTable = 1
    }
}
