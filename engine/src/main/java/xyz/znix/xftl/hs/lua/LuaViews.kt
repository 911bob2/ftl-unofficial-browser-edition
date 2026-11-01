// Ported from FTL-Hyperspace (GPL-3.0-or-later) commit 1b05a07 (lua/ script API and .i members, src/ files named below),
// not from the 1.23.2 tag.
package xyz.znix.xftl.hs.lua

import java.util.IdentityHashMap

fun interface FGet { fun get(): Float }
fun interface FSet { fun set(v: Float) }
fun interface IGet { fun get(): Int }
fun interface ISet { fun set(v: Int) }

/**
 * Lua-side views of values that are plain members in HS's structs (a `std::pair<float,float>`, a `Pointf`, a
 * `std::vector<T*>`): SWIG returns a pointer into the owner, so writes through the view change the owner.
 * One view per (owner, attribute) is cached so the same member keeps the same userdata, as long as it is used.
 */
class FloatPairView(override val luaType: Int, private val g1: FGet, private val s1: FSet?, private val g2: FGet, private val s2: FSet?) : LuaTyped {
    // A member the engine computes: a write reads back until the engine's own value changes.
    private var h1: Float? = null; private var b1 = 0f
    private var h2: Float? = null; private var b2 = 0f
    var first: Float
        get() { val e = g1.get(); val h = h1; if (h != null && e == b1) return h; h1 = null; return e }
        set(v) { val s = s1; if (s != null) s.set(v) else { h1 = v; b1 = g1.get() } }
    var second: Float
        get() { val e = g2.get(); val h = h2; if (h != null && e == b2) return h; h2 = null; return e }
        set(v) { val s = s2; if (s != null) s.set(v) else { h2 = v; b2 = g2.get() } }
}

class IntPairView(override val luaType: Int, private val g1: IGet, private val s1: ISet?, private val g2: IGet, private val s2: ISet?) : LuaTyped {
    private var h1: Int? = null; private var b1 = 0
    private var h2: Int? = null; private var b2 = 0
    var first: Int
        get() { val e = g1.get(); val h = h1; if (h != null && e == b1) return h; h1 = null; return e }
        set(v) { val s = s1; if (s != null) s.set(v) else { h1 = v; b1 = g1.get() } }
    var second: Int
        get() { val e = g2.get(); val h = h2; if (h != null && e == b2) return h; h2 = null; return e }
        set(v) { val s = s2; if (s != null) s.set(v) else { h2 = v; b2 = g2.get() } }
}

/** A `std::vector<T*>` member: indexing is zero-based and checked as SWIG's std_vector.i does. */
class ListView(override val luaType: Int, @JvmField val elementType: Int, private val list: () -> List<Any?>) : LuaTyped {
    /** A vector owned by Lua (a struct member or `new vector`). */
    constructor(luaType: Int, elementType: Int, owned: MutableList<Any?>) : this(luaType, elementType, { owned })

    fun size(): Int = list().size
    fun get(i: Int): Any? {
        val l = list()
        if (i < 0 || i >= l.size) throw LuaError("SWIG_IndexError:in vector::__getitem__()")
        val e = l[i]
        // std::vector<Point> / <Pointf> hold values: indexing hands out a copy (oracle row vec-Point: a write through it is lost)
        if (e is PointValue && luaType == LuaApi.T_vector_Point) return PointValue(e.x, e.y)
        if (e is PointfValue && luaType == LuaApi.T_vector_Pointf) return PointfValue(e.x, e.y)
        // std::vector<CrewPlacementDefinition> holds values too (oracle hw-g-ih).
        if (e is xyz.znix.xftl.hs.ship.CrewPlacement) return e.luaCopy()
        return e
    }

    @Suppress("UNCHECKED_CAST")
    private fun mutable(): MutableList<Any?> = list() as? MutableList<Any?> ?: throw LuaError("This vector is immutable")
    // SWIG's vector<int> arguments are converted to C++ int before insertion; the bridge reads numbers as Double.
    private fun element(v: Any?): Any? = if (luaType == LuaApi.T_vector_int)
        (v as? Number)?.toInt() ?: throw LuaError("expected int vector element") else v

    fun add(v: Any?) { mutable().add(element(v)) }
    fun removeLast() { val l = mutable(); if (l.isNotEmpty()) l.removeAt(l.size - 1) }
    fun clear() { mutable().clear() }
    fun set(i: Int, v: Any?) {
        val l = mutable()
        if (i < 0 || i >= l.size) throw LuaError("SWIG_IndexError:in vector::__setitem__()")
        l[i] = element(v)
    }
}

/** An owned value (SWIG `new X(...)` or a by-value result): plain mutable fields. */
open class PointfValue(@JvmField var x: Float, @JvmField var y: Float) : LuaTyped { override val luaType get() = LuaApi.T_Pointf }
open class PointValue(@JvmField var x: Int, @JvmField var y: Int) : LuaTyped { override val luaType get() = LuaApi.T_Point }
/** A value whose field writes go on to an engine object. */
interface LuaWriteThrough { fun written() }

open class GlColorValue(@JvmField var r: Float, @JvmField var g: Float, @JvmField var b: Float, @JvmField var a: Float) : LuaTyped {
    override val luaType get() = LuaApi.T_GL_Color
}

object LuaViews {
    /** The views of one owner: a few (attribute id, view) pairs searched linearly. */
    private class Slots { @JvmField var keys = IntArray(4); @JvmField var vals = arrayOfNulls<Any>(4); @JvmField var n = 0 }

    private val cache = IdentityHashMap<Any, Slots>()
    private var lastOwner: Any? = null
    private var lastSlots: Slots? = null

    /** The view for ([owner], [key]); [create] (keep it non-capturing, it gets the owner) runs once. No allocation on hits. */
    @JvmStatic
    fun <T : Any> cached(owner: Any, key: Int, create: (Any) -> T): T {
        var s = if (owner === lastOwner) lastSlots else null
        if (s == null) {
            s = cache[owner]
            if (s == null) { s = Slots(); cache[owner] = s }
            lastOwner = owner; lastSlots = s
        }
        val keys = s.keys
        for (i in 0 until s.n) if (keys[i] == key) {
            @Suppress("UNCHECKED_CAST")
            return s.vals[i] as T
        }
        val v = create(owner)
        if (s.n == keys.size) { s.keys = keys.copyOf(s.n * 2); s.vals = s.vals.copyOf(s.n * 2) }
        s.keys[s.n] = key; s.vals[s.n] = v; s.n++
        return v
    }

    fun forget(owner: Any) { cache.remove(owner); if (owner === lastOwner) { lastOwner = null; lastSlots = null } }
    fun reset() { cache.clear(); lastOwner = null; lastSlots = null }

    /** The run is over: its ships, beacons and crew go, and with them everything they keep alive. Blueprints stay, they outlive runs. */
    fun endRun() {
        val done = cache.keys.filter { LuaRunScope.of(it) }
        for (k in done) cache.remove(k)
        lastOwner = null; lastSlots = null
    }
}

/** What belongs to one run, so a closed run's objects are let go by the bridge's session-long tables: everything but the game's data. */
object LuaRunScope {
    private val kept = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())

    /** Session-long singletons (the mouse, the GUI's fixed windows) whose table entries outlive every run. */
    @JvmStatic fun keep(o: Any) { kept += o }

    @JvmStatic
    fun of(o: Any): Boolean = o !in kept && !(o is xyz.znix.xftl.IBlueprint || o is LuaStruct || o is xyz.znix.xftl.rendering.Image ||
        o is xyz.znix.xftl.hs.crew.CrewDefinition || o is xyz.znix.xftl.hs.crew.ActivatedPowerDefinition ||
        o is xyz.znix.xftl.hs.crew.PowerResourceDefinition || o is xyz.znix.xftl.hs.crew.TemporaryPowerDefinition ||
        o is xyz.znix.xftl.hs.crew.ActivatedPowerRequirements || o is xyz.znix.xftl.hs.stat.StatBoostDefinition)
}

/** The engine's static tables keyed by run objects. Their values often lead back to the key, which a weak map cannot let go by itself. */
object HsRunMaps {
    private val whole = ArrayList<MutableMap<*, *>>()
    private val scoped = ArrayList<MutableMap<*, *>>()

    /** A table keyed only by run objects: emptied when the run ends. */
    @JvmStatic fun <M : MutableMap<*, *>> run(m: M): M { whole += m; return m }
    /** A table keyed by anything: entries of the run's objects go when the run ends. */
    @JvmStatic fun <M : MutableMap<*, *>> any(m: M): M { scoped += m; return m }

    fun endRun() {
        for (m in whole) m.clear()
        for (m in scoped) { val k = m.keys.filter { it != null && LuaRunScope.of(it) }; for (x in k) m.remove(x) }
    }
}
