package xyz.znix.xftl.hs.lua;

import org.teavm.jso.JSBody;

/**
 * Calls into the Hyperspace Lua runtime (web/hs-lua.mjs, window.ftlHsLua). The stack functions mirror the Lua C API
 * on the runtime's single lua_State, so engine hooks read like Hyperspace's own (push arguments, fire, read results).
 * Only reached when mod scripts are active.
 */
public final class LuaJs {
    private LuaJs() {}

    @JSBody(script = "return !!window.ftlHsLua;")
    public static native boolean available();

    /** Runs a script from window.ftlFiles (bytes as stored); null or the error message. */
    @JSBody(params = "name", script = "return ftlHsLua.runFile(name);")
    public static native String runFile(String name);
    /** A fresh lua_State (HS's LuaScriptInit), dropping every registration of the previous one. */
    @JSBody(script = "ftlHsLua.reinit();")
    public static native void reinit();
    @JSBody(params = {"kind", "text"}, script = "if(window.ftlHsLuaLog)window.ftlHsLuaLog(kind,text);else if(kind>=2)console.warn('[Lua] '+text);")
    public static native void pageLog(int kind, String text);
    /** Hyperspace.setWindowTitle (sys_graphics_set_window_title): the browser window is the tab. */
    @JSBody(params = {"t"}, script = "document.title=t;")
    public static native void setWindowTitle(String t);

    // callbacks
    @JSBody(params = {"id", "n", "r"}, script = "return ftlHsLua.e.hs_fire(id,n,r);")
    public static native int fire(int id, int nArg, int nRet);
    @JSBody(params = {"id", "n", "r"}, script = "return ftlHsLua.e.hs_fire_chain(id,n,r)!==0;")
    public static native boolean fireChain(int id, int nArg, int nRet);
    @JSBody(params = {"id", "n"}, script = "return ftlHsLua.e.hs_render_pre(id,n);")
    public static native int renderPre(int id, int nArg);
    @JSBody(params = {"id", "i", "n"}, script = "ftlHsLua.e.hs_render_post(id,i,n);")
    public static native void renderPost(int id, int idx, int nArg);
    @JSBody(params = {"k", "id"}, script = "return ftlHsLua.e.hs_has(k,id);")
    public static native int has(int kind, int id);
    @JSBody(script = "ftlHsLua.e.hs_on_load();")
    public static native void onLoad();
    @JSBody(params = "b", script = "ftlHsLua.e.hs_on_init(b?1:0);")
    public static native void onInit(boolean newGame);
    @JSBody(params = {"name", "loading"}, script = "ftlHsLua.gameEvent(name,loading);")
    public static native void gameEvent(String name, boolean loading);
    @JSBody(params = {"name", "code", "console"}, script = "return ftlHsLua.run(name,code,console);")
    public static native String run(String name, String code, boolean console);
    @JSBody(params = "reset", script = "return ftlHsLua.profile(reset);")
    public static native String profile(boolean reset);
    @JSBody(params = {"cb", "script"}, script = "ftlHsLua.e.hs_set_caps(cb,script);")
    public static native void setCaps(int perCallback, int perScript);
    @JSBody(params = "on", script = "ftlHsLua.e.hs_set_profiling(on?1:0);")
    public static native void setProfiling(boolean on);
    /** Writes what the scripts changed in their private folder to this browser's storage (web/lua-files.mjs). */
    @JSBody(script = "if(window.ftlHsLua&&ftlHsLua.syncFiles)ftlHsLua.syncFiles();")
    public static native void vfsSync();
    /** A script called os.exit (the session ends). */
    @JSBody(script = "return !!window.ftlHsLua&&ftlHsLua.e.hs_exit_state()!==0;")
    public static native boolean exitRequested();
    /** The chunk name of the script that called os.exit. */
    @JSBody(script = "var s=ftlHsLua.exitState();return s?s.source:'';")
    public static native String exitSource();
    /** os.exit: the page ends the game session with a message naming the mod (web/app.mjs window.ftlLuaExit). */
    @JSBody(params = {"source", "code"}, script = "if(window.ftlLuaExit)window.ftlLuaExit(source,code);")
    public static native void hostExit(String source, int code);
    @JSBody(params = "msg", script = "ftlHsLua.setError(msg);")
    public static native void setError(String message);
    /** Getter failures: the runtime raises this as a Lua error right after the getter returns. */
    @JSBody(params = "msg", script = "ftlHsLua.raise(msg);")
    public static native void raise(String message);
    @JSBody(params = "tid", script = "ftlHsLua.e.hs_drop_table(tid);")
    public static native void dropTable(int tableId);
    @JSBody(script = "return ftlHsLua.e.hs_gc_count();")
    public static native int gcKiB();
    /** A full Lua collection (finalizes unreferenced userdata, which releases their handles). */
    @JSBody(script = "ftlHsLua.e.hs_full_gc();")
    public static native void fullGc();
    /** Collector work as if [kb] KiB were allocated in Lua. */
    @JSBody(params = "kb", script = "ftlHsLua.e.hs_gc_step(kb);")
    public static native void gcStep(int kb);

    @JSBody(script = "return performance.now();")
    public static native double now();

    // stack
    @JSBody(script = "return ftlHsLua.e.hs_gettop();")
    public static native int gettop();
    @JSBody(params = "i", script = "ftlHsLua.e.hs_settop(i);")
    public static native void settop(int i);
    @JSBody(params = "n", script = "ftlHsLua.e.hs_pop(n);")
    public static native void pop(int n);
    @JSBody(params = "i", script = "return ftlHsLua.e.hs_type(i);")
    public static native int type(int i);
    @JSBody(params = "i", script = "return ftlHsLua.e.hs_isnumber(i)!==0;")
    public static native boolean isnumber(int i);
    @JSBody(params = "i", script = "return ftlHsLua.e.hs_isinteger(i)!==0;")
    public static native boolean isinteger(int i);
    @JSBody(params = "i", script = "return ftlHsLua.e.hs_isstring(i)!==0;")
    public static native boolean isstring(int i);
    @JSBody(params = "i", script = "return ftlHsLua.e.hs_tonumber(i);")
    public static native double tonumber(int i);
    @JSBody(params = "i", script = "return ftlHsLua.e.hs_tointeger(i);")
    public static native double tointeger(int i);
    @JSBody(params = "i", script = "return ftlHsLua.e.hs_toboolean(i)!==0;")
    public static native boolean toboolean(int i);
    @JSBody(params = "i", script = "return ftlHsLua.toString(i);")
    public static native String tostring(int i);
    @JSBody(params = "i", script = "return ftlHsLua.e.hs_tohandle(i);")
    public static native int tohandle(int i);
    @JSBody(params = "i", script = "return ftlHsLua.e.hs_toobjtype(i);")
    public static native int toobjtype(int i);
    @JSBody(script = "ftlHsLua.e.hs_pushnil();")
    public static native void pushnil();
    @JSBody(params = "v", script = "ftlHsLua.e.hs_pushnumber(v);")
    public static native void pushnumber(double v);
    @JSBody(params = "v", script = "ftlHsLua.e.hs_pushinteger(v);")
    public static native void pushinteger(double v);
    @JSBody(params = "b", script = "ftlHsLua.e.hs_pushboolean(b?1:0);")
    public static native void pushboolean(boolean b);
    @JSBody(params = "s", script = "ftlHsLua.pushString(s);")
    public static native void pushstring(String s);
    @JSBody(params = {"h", "t"}, script = "ftlHsLua.e.hs_pushobj(h,t);")
    public static native void pushobj(int handle, int type);
    @JSBody(params = "i", script = "ftlHsLua.e.hs_pushvalue(i);")
    public static native void pushvalue(int i);
    /** lua_replace: pops the top value into stack index i. */
    @JSBody(params = "i", script = "ftlHsLua.e.hs_replace(i);")
    public static native void replace(int i);
    @JSBody(script = "ftlHsLua.e.hs_newtable();")
    public static native void newtable();
    @JSBody(params = {"t", "i"}, script = "ftlHsLua.e.hs_rawseti(t,i);")
    public static native void rawseti(int t, int i);
    @JSBody(params = {"t", "k"}, script = "ftlHsLua.setField(t,k);")
    public static native void setfield(int t, String key);
}
