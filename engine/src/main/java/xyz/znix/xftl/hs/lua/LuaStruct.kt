// Ported from FTL-Hyperspace (GPL-3.0-or-later) commit 1b05a07 (lua/ script API and .i members, src/ files named below),
// not from the 1.23.2 tag.
package xyz.znix.xftl.hs.lua

import xyz.znix.xftl.hs.lua.LuaApi as A
import xyz.znix.xftl.hs.lua.LuaArgs as arg
import xyz.znix.xftl.hs.lua.LuaPush as ret

/**
 * A Hyperspace data type with no engine object behind it (StatBoostDefinition, Rect, TextString, ...): plain
 * fields by attribute id. Objects made with `new` start as SWIG's `new T()` leaves them: value-initialised (zero,
 * false, "", nil) except the fields [LuaStructs.defaults] lists with the C++ default member initialisers.
 * Member structs and vectors (`def.healthReq.first`, `def.whiteList:push_back(...)`) are owned sub-objects, so
 * writes through them change the owner, as SWIG's member pointers do.
 */
class LuaStruct(override val luaType: Int) : LuaTyped {
    @JvmField val values = HashMap<Int, Any?>()
    /** A struct standing for engine state: runs after each script write to one of its fields. */
    @JvmField var onWrite: (() -> Unit)? = null
}

object LuaStructs {
    /** Field defaults (C++ default member initialisers) per type name, by field name. */
    private val defaults: Map<String, Map<String, Any>> = mapOf(
        // Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Ship/StatBoost.h
        "StatBoostDefinition" to mapOf(
            "priority" to -1.0, "duration" to -1.0, "cloneClear" to true, "powerScalingShipTarget" to 5.0,
            "droneTarget" to 2.0, "dangerRating" to -1.0, "realBoostId" to -1.0, "maxStacks" to 2147483647.0,
            "healthReq" to listOf(-1.0, -1.0), "healthFractionReq" to listOf(-1.0, -1.0),
            "oxygenReq" to listOf(-1.0, -1.0), "fireCount" to listOf(-1.0, -1.0),
        ),
        // TextString() : TextString("", true) (HS 1b05a07 FTLGameWin32.h / FTLGameELF64.h)
        "TextString" to mapOf("isLiteral" to true),
        // FTL's GenericButton constructor: buttons start active
        "Button" to mapOf("bActive" to true, "allowAnyTouch" to true),
        "TextButton" to mapOf("bActive" to true, "allowAnyTouch" to true),
        "FTLButton" to mapOf("bActive" to true, "allowAnyTouch" to true),
        "GenericButton" to mapOf("bActive" to true, "allowAnyTouch" to true),
    )

    private var classAttrs: HashMap<Int, IntArray>? = null
    private var classBases: HashMap<Int, IntArray>? = null
    private val registered = HashSet<Int>()

    private fun index() {
        if (classAttrs != null) return
        val attrs = HashMap<Int, IntArray>(); val bases = HashMap<Int, IntArray>()
        val p = A.classAttrs()
        var i = 0
        while (i < p.size) {
            val t = p[i]; val n = p[i + 1]
            attrs[t] = p.copyOfRange(i + 2, i + 2 + n)
            val nb = p[i + 2 + n]
            bases[t] = p.copyOfRange(i + 3 + n, i + 3 + n + nb)
            i += 3 + n + nb
        }
        classAttrs = attrs; classBases = bases
    }

    /** Attribute ids of [type] and its bases. */
    fun attrsOf(type: Int): IntArray {
        index()
        val own = classAttrs!![type] ?: IntArray(0)
        val bases = classBases!![type] ?: return own
        var all = own
        for (b in bases) all += attrsOf(b)
        return all
    }

    private fun fieldName(a: Int): String = A.attrName(a).substringAfter('.')

    /** `new T()`: a value-initialised struct with T's default member initialisers. */
    @JvmStatic
    fun create(type: Int): LuaStruct {
        register(type)
        val s = LuaStruct(type)
        val d = defaults[A.typeName(type)] ?: return s
        for (a in attrsOf(type)) {
            val v = d[fieldName(a)] ?: continue
            if (v is List<*>) {
                val sub = memberOf(s, a) as? LuaStruct ?: continue
                val sa = attrsOf(sub.luaType)
                v.forEachIndexed { k, x -> if (k < sa.size) sub.values[sa[k]] = x }
            } else s.values[a] = v
        }
        return s
    }

    /** The owned member struct/vector behind a member attribute, created on first use. */
    @JvmStatic
    fun member(s: LuaStruct, a: Int): Any? = memberOf(s, a)

    /** Sets the first fields of [s] (in declaration order, bases last) to numbers. */
    @JvmStatic
    fun setFields(s: LuaStruct, vararg v: Double) {
        val a = attrsOf(s.luaType)
        for (k in v.indices) if (k < a.size) s.values[a[k]] = v[k]
    }

    /** The numeric fields of [s] in declaration order (0 for unset or non-numbers). */
    @JvmStatic
    fun fields(s: LuaStruct): DoubleArray {
        val a = attrsOf(s.luaType)
        return DoubleArray(a.size) { (s.values[a[it]] as? Double) ?: 0.0 }
    }

    private fun memberOf(s: LuaStruct, a: Int): Any? {
        s.values[a]?.let { return it }
        val t = A.attrType(a)
        if (t == 0) return null
        val v: Any = vectorElement(t)?.let { el -> ListView(t, el, ArrayList<Any?>()) } ?: create(t)
        s.values[a] = v
        return v
    }

    private var vectorElems: HashMap<Int, Int>? = null

    /** Element type (0 for numbers/strings) when [t] is a std::vector type, else null. */
    @JvmStatic
    fun vectorElement(t: Int): Int? {
        var m = vectorElems
        if (m == null) {
            m = HashMap()
            val v = A.vectors()
            var i = 0
            while (i + 3 < v.size) { m[v[i]] = v[i + 3]; i += 4 }
            vectorElems = m
        }
        return m[t]
    }

    /**
     * Generic accessors for every attribute of [type]: struct objects read and write their own fields; any other
     * object goes to the adapter that was registered before (so a type shared with engine objects keeps working).
     */
    @JvmStatic
    fun register(type: Int) {
        if (!registered.add(type)) return
        for (a in attrsOf(type)) {
            when (A.attrKind(a)) {
                1, 2 -> {
                    val prevG = LuaReg.numGet[a]; val prevS = LuaReg.numSet[a]
                    val bool = A.attrKind(a) == 2
                    LuaReg.numGet[a] = NumGet { o ->
                        if (o is LuaStruct) when (val v = o.values[a]) { is Double -> v; is Boolean -> if (v) 1.0 else 0.0; else -> 0.0 }
                        else (prevG ?: throw LuaError("Hyperspace Lua API not available in the browser engine: " + A.attrName(a))).get(o)
                    }
                    LuaReg.numSet[a] = NumSet { o, v ->
                        if (o is LuaStruct) { o.values[a] = if (bool) v != 0.0 else v; o.onWrite?.invoke() }
                        else if (prevS != null) prevS.set(o, v) else LuaWritable.fallbackNum(o, a, v)
                    }
                }
                3 -> {
                    val prevG = LuaReg.strGet[a]; val prevS = LuaReg.strSet[a]
                    LuaReg.strGet[a] = StrGet { o ->
                        if (o is LuaStruct) (o.values[a] as? String) ?: ""
                        else (prevG ?: throw LuaError("Hyperspace Lua API not available in the browser engine: " + A.attrName(a))).get(o)
                    }
                    LuaReg.strSet[a] = StrSet { o, v ->
                        if (o is LuaStruct) { o.values[a] = v; o.onWrite?.invoke() }
                        else if (prevS != null) prevS.set(o, v) else LuaWritable.fallbackStr(o, a, v)
                    }
                }
                4 -> {
                    val prevG = LuaReg.objGet[a]; val prevS = LuaReg.objSet[a]
                    LuaReg.objGet[a] = ObjGet { o ->
                        if (o is LuaStruct) o.values[a]
                        else (prevG ?: throw LuaError("Hyperspace Lua API not available in the browser engine: " + A.attrName(a))).get(o)
                    }
                    LuaReg.objSet[a] = ObjSet { o, v ->
                        if (o is LuaStruct) { o.values[a] = v; o.onWrite?.invoke() }
                        else if (prevS != null) prevS.set(o, v) else LuaWritable.fallbackObj(o, a, v)
                    }
                }
                5, 6 -> {
                    val t = A.attrType(a)
                    if (t != 0 && vectorElement(t) == null) register(t)
                    val prevG = LuaReg.objGet[a]; val prevS = LuaReg.objSet[a]
                    LuaReg.objGet[a] = ObjGet { o ->
                        if (o is LuaStruct) memberOf(o, a)
                        else (prevG ?: throw LuaError("Hyperspace Lua API not available in the browser engine: " + A.attrName(a))).get(o)
                    }
                    // a struct member assigned from another struct is a copy (SWIG copies member values)
                    LuaReg.objSet[a] = ObjSet { o, v ->
                        if (o is LuaStruct) { o.values[a] = if (v is LuaStruct) copy(v) else v; o.onWrite?.invoke() }
                        else if (prevS != null) prevS.set(o, v) else LuaWritable.fallbackObj(o, a, v)
                    }
                }
            }
        }
    }

    @JvmStatic
    fun copy(s: LuaStruct): LuaStruct {
        val c = LuaStruct(s.luaType)
        for ((k, v) in s.values) c.values[k] = if (v is LuaStruct) copy(v) else v
        return c
    }

    /** std::vector methods for every vector type: push_back, pop_back, clear, empty, front, back, __setitem__. */
    fun installVectors() {
        val byName = HashMap<String, Int>()
        val v = A.vectors()
        var i = 0
        while (i + 3 < v.size) { byName[A.typeName(v[i])] = v[i]; i += 4 }
        for (m in 1..A.METHOD_COUNT) {
            val name = A.methodName(m)
            val dot = name.indexOf('.')
            if (dot < 0) continue
            val vt = byName[name.substring(0, dot)] ?: continue
            val el = vectorElement(vt) ?: 0
            val str = name.startsWith("vector_string.")
            val bool = name.startsWith("vector_bool.")
            val read: (Int) -> Any? = when {
                el != 0 -> { k -> arg.obj(k) }
                str -> { k -> arg.str(k) }
                bool -> { k -> arg.bool(k) }
                else -> { k -> arg.num(k) }
            }
            when (name.substring(dot + 1)) {
                "push_back" -> LuaReg.method(m, LuaMethod { (arg.obj(1) as ListView).add(read(2)); 0 })
                "pop_back" -> LuaReg.method(m, LuaMethod { (arg.obj(1) as ListView).removeLast(); 0 })
                "clear" -> LuaReg.method(m, LuaMethod { (arg.obj(1) as ListView).clear(); 0 })
                "empty" -> LuaReg.method(m, LuaMethod { ret.bool((arg.obj(1) as ListView).size() == 0) })
                "front" -> LuaReg.method(m, LuaMethod { push((arg.obj(1) as ListView).get(0), el) })
                "back" -> LuaReg.method(m, LuaMethod { val l = arg.obj(1) as ListView; push(l.get(l.size() - 1), el) })
                "__setitem" -> LuaReg.method(m, LuaMethod { (arg.obj(1) as ListView).set(arg.int(2), read(3)); 0 })
            }
        }
    }

    private fun push(e: Any?, el: Int): Int = when {
        el != 0 -> ret.obj(e, el)
        e is Number -> ret.num(e.toDouble())
        e is Boolean -> ret.bool(e)
        else -> ret.str(e?.toString())
    }

    fun reset() { registered.clear() }
}
