// Ported from FTL-Hyperspace (GPL-3.0-or-later) commit 1b05a07 (lua/ script API and .i members, src/ files named below),
// not from the 1.23.2 tag.
package xyz.znix.xftl.hs.lua

import xyz.znix.xftl.hs.HS

/** Attribute and method adapters (one per Hyperspace member id, see [LuaApi]); primitive signatures avoid boxing. */
fun interface NumGet { fun get(o: Any?): Double }
fun interface NumSet { fun set(o: Any?, v: Double) }
fun interface StrGet { fun get(o: Any?): String? }
fun interface StrSet { fun set(o: Any?, v: String) }
fun interface ObjGet { fun get(o: Any?): Any? }
fun interface ObjSet { fun set(o: Any?, v: Any?) }
/** Reads its arguments with [LuaArgs] (Lua stack indices 1..n, `self` first), pushes results with [LuaPush]. */
fun interface LuaMethod { fun call(n: Int): Int }

/** A Lua error raised from an adapter (becomes a Lua error at the call site, like SWIG's lua_error). */
class LuaError(message: String) : RuntimeException(message)

/** Objects that know their own Lua type (views and proxies). */
interface LuaTyped { val luaType: Int }

object LuaReg {
    @JvmField val numGet = arrayOfNulls<NumGet>(LuaApi.ATTR_COUNT + 1)
    @JvmField val numSet = arrayOfNulls<NumSet>(LuaApi.ATTR_COUNT + 1)
    @JvmField val strGet = arrayOfNulls<StrGet>(LuaApi.ATTR_COUNT + 1)
    @JvmField val strSet = arrayOfNulls<StrSet>(LuaApi.ATTR_COUNT + 1)
    @JvmField val objGet = arrayOfNulls<ObjGet>(LuaApi.ATTR_COUNT + 1)
    @JvmField val objSet = arrayOfNulls<ObjSet>(LuaApi.ATTR_COUNT + 1)
    @JvmField val methods = arrayOfNulls<LuaMethod>(LuaApi.METHOD_COUNT + 1)

    fun num(a: Int, get: NumGet, set: NumSet? = null) { numGet[a] = get; numSet[a] = set }
    fun bool(a: Int, get: (Any?) -> Boolean, set: ((Any?, Boolean) -> Unit)? = null) {
        numGet[a] = NumGet { if (get(it)) 1.0 else 0.0 }
        numSet[a] = set?.let { s -> NumSet { o, v -> s(o, v != 0.0) } }
    }
    fun str(a: Int, get: StrGet, set: StrSet? = null) { strGet[a] = get; strSet[a] = set }
    fun obj(a: Int, get: ObjGet, set: ObjSet? = null) { objGet[a] = get; objSet[a] = set }
    fun method(m: Int, f: LuaMethod) { methods[m] = f }

    fun clear() {
        numGet.fill(null); numSet.fill(null); strGet.fill(null); strSet.fill(null); objGet.fill(null); objSet.fill(null); methods.fill(null)
    }
}

/** Arguments of the running method (Lua stack of the current C call). */
object LuaArgs {
    @JvmStatic fun obj(i: Int): Any? = Handles.get(LuaJs.tohandle(i))
    @JvmStatic fun type(i: Int): Int = LuaJs.toobjtype(i)
    @JvmStatic fun num(i: Int): Double = LuaJs.tonumber(i)
    /** SWIG's `(int) lua_tonumber(L, i)`. */
    @JvmStatic fun int(i: Int): Int = LuaJs.tonumber(i).toInt()
    @JvmStatic fun float(i: Int): Float = LuaJs.tonumber(i).toFloat()
    @JvmStatic fun bool(i: Int): Boolean = LuaJs.toboolean(i)
    @JvmStatic fun str(i: Int): String? = LuaJs.tostring(i)
    @JvmStatic fun isNil(i: Int): Boolean = LuaJs.type(i) <= 0
    inline fun <reified T> self(i: Int): T =
        obj(i) as? T ?: throw LuaError("stale or invalid object passed to a Hyperspace method")
}

object LuaPush {
    @JvmStatic fun nil(): Int { LuaJs.pushnil(); return 1 }
    /** SWIG pushes every C number as a Lua float (lua_pushnumber). */
    @JvmStatic fun num(v: Double): Int { LuaJs.pushnumber(v); return 1 }
    @JvmStatic fun num(v: Float): Int { LuaJs.pushnumber(v.toDouble()); return 1 }
    @JvmStatic fun num(v: Int): Int { LuaJs.pushnumber(v.toDouble()); return 1 }
    @JvmStatic fun bool(v: Boolean): Int { LuaJs.pushboolean(v); return 1 }
    @JvmStatic fun str(v: String?): Int { if (v == null) LuaJs.pushnil() else LuaJs.pushstring(v); return 1 }
    /** Pushes [o] as a userdata of its dynamic Lua type (SWIG_NewPointerObj with the DYNAMIC casts); nil for null. */
    @JvmStatic fun obj(o: Any?, staticType: Int): Int {
        if (o == null) { LuaJs.pushnil(); return 1 }
        LuaJs.pushobj(Handles.expose(o), LuaTypes.dynamic(o, staticType))
        return 1
    }
}

/** Entry points for the wasm imports (exported through BrowserEngine as hsLua*). */
object LuaBridge {
    private const val TWO32 = 4294967296.0

    private fun missing(kind: String, name: String): Nothing {
        HS.unsupported("lua", kind, name)
        throw LuaError("Hyperspace Lua API not available in the browser engine: $name")
    }

    private fun failed(name: String, e: Throwable): String {
        val msg = if (e is LuaError) e.message ?: "error" else "engine error in $name: $e"
        if (e !is LuaError) HsLua.engineError(name, e)
        return msg
    }

    /** For getters: object or null (static attribute); throws for a stale handle. */
    private fun target(h: Int, a: Int): Any? {
        if (h == 0) return null
        return Handles.get(h) ?: throw LuaError("stale object (destroyed) read through " + LuaApi.attrName(a))
    }

    @JvmStatic
    fun getn(h: Int, a: Int): Double = try {
        if (LuaStored.active[a]) LuaWritable.readNum(target(h, a), a) ?: 0.0
        else (LuaReg.numGet[a] ?: missing("attribute", LuaApi.attrName(a))).get(target(h, a))
    } catch (e: Throwable) { LuaJs.raise(failed(LuaApi.attrName(a), e)); 0.0 }

    @JvmStatic
    fun gets(h: Int, a: Int): String? = try {
        if (LuaStored.active[a]) LuaWritable.readStr(target(h, a), a)
        else (LuaReg.strGet[a] ?: missing("attribute", LuaApi.attrName(a))).get(target(h, a))
    } catch (e: Throwable) { LuaJs.raise(failed(LuaApi.attrName(a), e)); null }

    @JvmStatic
    fun geto(h: Int, a: Int): Double = try {
        val r = if (LuaStored.active[a]) LuaWritable.readObj(target(h, a), a)
            else (LuaReg.objGet[a] ?: missing("attribute", LuaApi.attrName(a))).get(target(h, a))
        if (r == null) 0.0 else LuaTypes.dynamic(r, LuaApi.attrType(a)) * TWO32 + Handles.expose(r)
    } catch (e: Throwable) { LuaJs.raise(failed(LuaApi.attrName(a), e)); 0.0 }

    @JvmStatic
    fun setn(h: Int, a: Int, v: Double): String? = try {
        val f = LuaReg.numSet[a]
        if (f != null) f.set(target(h, a), v) else LuaWritable.fallbackNum(target(h, a), a, v)
        null
    } catch (e: Throwable) { failed(LuaApi.attrName(a), e) }

    @JvmStatic
    fun sets(h: Int, a: Int, v: String): String? = try {
        val f = LuaReg.strSet[a]
        if (f != null) f.set(target(h, a), v) else LuaWritable.fallbackStr(target(h, a), a, v)
        null
    } catch (e: Throwable) { failed(LuaApi.attrName(a), e) }

    @JvmStatic
    fun seto(h: Int, a: Int, h2: Int, t2: Int): String? = try {
        val f = LuaReg.objSet[a]; val v = if (h2 == 0) null else Handles.get(h2)
        if (f != null) f.set(target(h, a), v) else LuaWritable.fallbackObj(target(h, a), a, v)
        null
    } catch (e: Throwable) { failed(LuaApi.attrName(a), e) }

    @JvmStatic
    fun call(mid: Int, n: Int): Int = try {
        val f = LuaReg.methods[mid] ?: missing("method", LuaApi.methodName(mid))
        f.call(n)
    } catch (e: Throwable) { LuaJs.setError(failed(LuaApi.methodName(mid), e)); -1 }

    @JvmStatic fun release(h: Int) = Handles.release(h)
    @JvmStatic fun tableId(h: Int): Int = Handles.tableId(h)
}
