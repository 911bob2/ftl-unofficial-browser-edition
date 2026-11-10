package xyz.znix.xftl.hs.lua;

import ftl.android.AndroidHost;
import ftl.android.GameFiles;
import java.nio.charset.StandardCharsets;

/**
 * Calls into the Hyperspace Lua runtime: on Android the same C sources as the browser's wasm (hs-lua/ and Lua 5.3.6),
 * built into libhslua.so and reached through JNI. The stack functions mirror the Lua C API on the runtime's single
 * lua_State, so engine hooks read like Hyperspace's own. Strings cross as UTF-8 bytes. Only reached when mod scripts
 * are active.
 */
public final class LuaJs {
    private LuaJs() {}

    private static final boolean LOADED;

    static {
        boolean loaded;
        try {
            System.loadLibrary("hslua");
            loaded = true;
        } catch (UnsatisfiedLinkError e) {
            loaded = false;
        }
        LOADED = loaded;
    }

    private static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    private static String text(byte[] b) { return b == null ? null : new String(b, StandardCharsets.UTF_8); }

    public static boolean available() { return LOADED; }

    /** Runs a script from the game files (bytes as stored); null or the error message. */
    public static String runFile(String name) {
        byte[] bytes = GameFiles.current().read(name);
        if (bytes == null) {
            String msg = "cannot open " + name;
            HsLua.log(2, "Failed to load lua code from file '" + name + "'. Error: '" + msg + "'");
            return msg;
        }
        return text(nRun(utf8(name), bytes, false));
    }

    /**
     * A fresh lua_State (HS's LuaScriptInit), dropping every registration of the previous one. The private folder of
     * mod scripts is written out first and loaded again from the app's storage, with this game's sandbox settings
     * ({@link ftl.android.LuaLibs}).
     */
    public static void reinit() {
        if (mounted) nVfsSync();
        nInit();
        ftl.android.LuaLibs cfg = ftl.android.LuaLibs.current;
        mounted = cfg != null && cfg.folder != null;
        if (mounted) nVfsMount(utf8(cfg.folder));
        nLibsReal(cfg != null && cfg.real, cfg != null && cfg.real && cfg.root != null ? utf8(cfg.root) : null);
        nLibsAllow(null);
        if (cfg != null && cfg.real) for (String s : cfg.sources) nLibsAllow(utf8(s));
    }

    private static boolean mounted;

    /** Writes what the scripts changed in their private folder to the app's storage. */
    public static void vfsSync() { if (LOADED && mounted) nVfsSync(); }

    /** Bytes the private folder holds. */
    public static double vfsUsed() { return LOADED ? nVfsUsed() : 0; }

    /** A script called os.exit (the session ends). */
    public static boolean exitRequested() { return LOADED && nExitRequested(); }

    /** The chunk name of the script that called os.exit. */
    public static String exitSource() { return text(nExitSource()); }

    /** os.exit: the app ends the game session with a message naming the mod. */
    public static void hostExit(String source, int code) { AndroidHost.get().luaExit(source); }

    public static void pageLog(int kind, String text) { AndroidHost.get().luaLog(kind, text); }

    /** Hyperspace.setWindowTitle: the app's task title. */
    public static void setWindowTitle(String t) { AndroidHost.get().setWindowTitle(t); }

    public static String run(String name, String code, boolean console) { return text(nRun(utf8(name), utf8(code), console)); }

    public static void gameEvent(String name, boolean loading) { nGameEvent(utf8(name), loading); }

    public static String profile(boolean reset) {
        byte[] b = nProfile(reset);
        return b == null ? "{}" : text(b);
    }

    private static String error = "";

    /** Error text for the next negative return of a bridge call. */
    public static void setError(String message) { error = String.valueOf(message); }

    /** Getter failures: the runtime raises this as a Lua error right after the getter returns. */
    public static void raise(String message) { nRaise(utf8(String.valueOf(message))); }

    public static double now() { return System.nanoTime() / 1_000_000.0; }

    public static String tostring(int i) { return text(nTostring(i)); }

    public static void pushstring(String s) { nPushstring(utf8(String.valueOf(s))); }

    public static void setfield(int t, String key) { nSetfield(t, utf8(String.valueOf(key))); }

    // callbacks
    public static native int fire(int id, int nArg, int nRet);
    public static native boolean fireChain(int id, int nArg, int nRet);
    public static native int renderPre(int id, int nArg);
    public static native void renderPost(int id, int idx, int nArg);
    public static native int has(int kind, int id);
    public static native void onLoad();
    public static native void onInit(boolean newGame);
    public static native void setCaps(int perCallback, int perScript);
    public static native void setProfiling(boolean on);
    public static native void dropTable(int tableId);
    public static native int gcKiB();
    /** A full Lua collection (finalizes unreferenced userdata, which releases their handles). */
    public static native void fullGc();
    /** Collector work as if [kb] KiB were allocated in Lua. */
    public static native void gcStep(int kb);

    // stack
    public static native int gettop();
    public static native void settop(int i);
    public static native void pop(int n);
    public static native int type(int i);
    public static native boolean isnumber(int i);
    public static native boolean isinteger(int i);
    public static native boolean isstring(int i);
    public static native double tonumber(int i);
    public static native double tointeger(int i);
    public static native boolean toboolean(int i);
    public static native int tohandle(int i);
    public static native int toobjtype(int i);
    public static native void pushnil();
    public static native void pushnumber(double v);
    public static native void pushinteger(double v);
    public static native void pushboolean(boolean b);
    public static native void pushobj(int handle, int type);
    public static native void pushvalue(int i);
    /** lua_replace: pops the top value into stack index i. */
    public static native void replace(int i);
    public static native void newtable();
    public static native void rawseti(int t, int i);

    private static native void nInit();
    private static native int nVfsMount(byte[] dir);
    private static native int nVfsSync();
    private static native double nVfsUsed();
    private static native void nLibsReal(boolean on, byte[] root);
    private static native void nLibsAllow(byte[] source);
    private static native boolean nExitRequested();
    private static native byte[] nExitSource();
    private static native byte[] nRun(byte[] name, byte[] code, boolean console);
    private static native void nGameEvent(byte[] name, boolean loading);
    private static native byte[] nProfile(boolean reset);
    private static native void nRaise(byte[] message);
    private static native byte[] nTostring(int i);
    private static native void nPushstring(byte[] s);
    private static native void nSetfield(int t, byte[] key);

    // ---- the runtime's imports (the browser's wasm env), called from C on the game thread ----

    static void hostLog(int kind, byte[] text) { HsLua.log(kind, text(text)); }

    /** os.date's %Z: the device zone's long English name, standard or summer, as msvcrt names the zone. */
    static byte[] hostTzName(boolean dst) {
        java.util.TimeZone z = java.util.TimeZone.getDefault();
        return utf8(z.getDisplayName(dst && z.useDaylightTime(), java.util.TimeZone.LONG, java.util.Locale.US));
    }

    static int hostRand32() { return LuaRng.rand32(); }

    static void hostSrand32(int seed) { LuaRng.srand32(seed); }

    static double hostGetn(int h, int a) { return LuaBridge.getn(h, a); }

    static byte[] hostGets(int h, int a) {
        String s = LuaBridge.gets(h, a);
        return s == null ? null : utf8(s);
    }

    static double hostGeto(int h, int a) { return LuaBridge.geto(h, a); }

    static byte[] hostSetn(int h, int a, double v) { return bytesOrNull(LuaBridge.setn(h, a, v)); }

    static byte[] hostSets(int h, int a, byte[] v) { return bytesOrNull(LuaBridge.sets(h, a, text(v))); }

    static byte[] hostSeto(int h, int a, int h2, int t2) { return bytesOrNull(LuaBridge.seto(h, a, h2, t2)); }

    /** Results pushed (>= 0), or -1 with the error text in {@link #hostError()}. */
    static int hostCall(int mid, int n) { return LuaBridge.call(mid, n); }

    static byte[] hostError() { return utf8(error); }

    static void hostRelease(int h) { Handles.release(h); }

    static int hostTableid(int h) { return Handles.tableId(h); }

    static void hostRegistered(int kind, int id) { HsLua.registered(kind, id); }

    private static byte[] bytesOrNull(String s) { return s == null ? null : utf8(s); }
}
