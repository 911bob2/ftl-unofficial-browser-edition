package xyz.znix.xftl.hs.lua;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import kotlin.Unit;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * libhslua.so on a device: the browser's Hyperspace Lua runtime (hs-lua/hslua.c, Lua 5.3.6) behind LuaJs's JNI
 * natives. Covers the bridge both ways, the sandbox (no loaders, no io/os/package/debug, text chunks only), the
 * instruction caps, UTF-8 strings, and that Lua errors and Java exceptions never cross the JNI boundary.
 */
@RunWith(AndroidJUnit4.class)
public class HsLuaNativeTest {
    private static final List<String> logs = new ArrayList<>();
    private static volatile String throwOn;

    @BeforeClass
    public static void load() {
        assertTrue("libhslua.so loads", LuaJs.available());
    }

    @Before
    public void fresh() {
        synchronized (logs) { logs.clear(); }
        throwOn = null;
        HsLua.sink = (kind, text) -> {
            if (throwOn != null && text.contains(throwOn)) throw new IllegalStateException("sink refused " + throwOn);
            synchronized (logs) { logs.add(kind + ":" + text); }
            return Unit.INSTANCE;
        };
        // what HsLua.start does, minus the game: engine adapters and a fresh lua_State
        Handles.INSTANCE.reset();
        LuaReg.INSTANCE.clear();
        LuaStructs.INSTANCE.reset();
        LuaAdapters.install();
        LuaJs.reinit();
        LuaJs.setCaps(50_000_000, 500_000_000);
    }

    @After
    public void done() {
        HsLua.sink = null;
        assertEquals("stack balanced", 0, LuaJs.gettop());
    }

    private static String logged(String prefix) {
        synchronized (logs) {
            for (String l : logs) if (l.contains(prefix)) return l;
        }
        return null;
    }

    private static String run(String code) { return LuaJs.run("t", code, false); }

    @Test
    public void runsAChunkAndLogs() {
        assertNull(run("x = 1 + 2 ; log('hello ' .. x)"));
        String line = logged("hello ");
        assertNotNull("log reached HsLua.log: " + logs, line);
        assertEquals("0:hello 3    ", line);
        assertNull(run("print('printed', 4)"));
        assertEquals("1:printed    4    ", logged("printed"));
    }

    @Test
    public void scriptErrorsComeBackAsText() {
        String err = run("local t = nil ; return t.x");
        assertNotNull(err);
        assertTrue(err, err.contains("attempt to index a nil value"));
        assertTrue(logged("Lua execution failed in file 't'") != null);
        err = run("this is not lua");
        assertNotNull(err);
        assertTrue(err, err.startsWith("[string \"t\"]:1:"));
    }

    @Test
    public void sandboxHasNoLoadersOrSystemLibraries() {
        assertNull(run("local present = {}\n"
                + "for _, k in ipairs({'load', 'loadfile', 'dofile', 'loadstring', 'require', 'io', 'os', 'package',\n"
                + "                     'debug', 'coroutine', 'collectgarbage', 'rawget', 'rawset', 'rawequal'}) do\n"
                + "  if _G[k] ~= nil then present[#present + 1] = k end\n"
                + "end\n"
                + "log('present:' .. table.concat(present, ','))\n"
                + "log('libs:' .. type(string) .. type(table) .. type(math) .. type(utf8) .. type(bit32))"));
        assertEquals("0:present:    ", logged("present:"));
        assertEquals("0:libs:tabletabletabletabletable    ", logged("libs:"));
    }

    @Test
    public void binaryChunksAreRefused() throws Exception {
        // the exact bytes of string.dump, carried out of Lua as hex
        assertNull(run("local d = string.dump(function() return 1 end)\n"
                + "log('dump:' .. d:gsub('.', function(c) return string.format('%02x', c:byte()) end))"));
        String line = logged("dump:");
        assertNotNull(line);
        String hex = line.substring("0:dump:".length()).trim();
        hex = hex.substring(0, hex.indexOf(' ') < 0 ? hex.length() : hex.indexOf(' '));
        byte[] dump = new byte[hex.length() / 2];
        for (int i = 0; i < dump.length; i++) dump[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        assertEquals(0x1b, dump[0]);
        // the path runFile takes (bytes as stored in the game files)
        Method nRun = LuaJs.class.getDeclaredMethod("nRun", byte[].class, byte[].class, boolean.class);
        nRun.setAccessible(true);
        byte[] err = (byte[]) nRun.invoke(null, "bin".getBytes(StandardCharsets.UTF_8), dump, false);
        assertNotNull("binary chunk refused", err);
        String msg = new String(err, StandardCharsets.UTF_8);
        assertTrue(msg, msg.contains("attempt to load a binary chunk (mode is 't')"));
        // and through the String API
        String err2 = LuaJs.run("bin2", new String(dump, StandardCharsets.ISO_8859_1), false);
        assertNotNull(err2);
        assertTrue(err2, err2.contains("binary chunk"));
    }

    @Test
    public void instructionCapStopsARunawayCallback() {
        LuaJs.setCaps(200_000, 0);
        assertNull(run("script.on_internal_event(Defines.InternalEvents.ON_TICK, function() while true do end end)"));
        assertTrue("registration reached the engine", HsLua.hasInt[LuaApi.EV_ON_TICK]);
        assertEquals(1, LuaJs.has(0, LuaApi.EV_ON_TICK));
        long t0 = System.nanoTime();
        assertEquals(0, LuaJs.fire(LuaApi.EV_ON_TICK, 0, 0));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        String line = logged("instruction limit");
        assertNotNull("cap error logged: " + logs, line);
        assertTrue(line, line.startsWith("2:Failed to call the callback for InternalEvent ON_TICK!"));
        assertTrue(line, line.contains("instruction limit (200000) exceeded in t, callback InternalEvent ON_TICK"));
        assertTrue("stopped promptly (" + ms + " ms)", ms < 5000);
        // the state is still usable, and the profile counts the failure
        assertNull(run("log('still alive')"));
        assertNotNull(logged("still alive"));
        String profile = LuaJs.profile(false);
        assertTrue(profile, profile.contains("\"errors\":1"));
    }

    @Test
    public void instructionCapStopsARunawayScript() {
        LuaJs.setCaps(0, 1_000_000);
        String err = LuaJs.run("loop.lua", "while true do end", false);
        assertNotNull(err);
        assertTrue(err, err.contains("instruction limit (1000000) exceeded while running loop.lua"));
    }

    @Test
    public void finalizerLoopsStopAtTheCap() {
        assertNull(run("setmetatable({}, {__gc = function() while true do end end})"));
        LuaJs.fullGc();
        assertNotNull(logged("instruction limit"));
        assertNull(run("setmetatable({}, {__gc = function() while true do pcall(function() while true do end end) end end})"));
        LuaJs.fullGc();
        LuaJs.setCaps(0, 1_000_000);
        String err = LuaJs.run("gc-loop.lua", "setmetatable({}, {__gc = function() while true do end end}) for i = 1, 1e8 do local t = {i} end", false);
        assertNotNull(err);
        assertTrue(err, err.contains("instruction limit"));
        assertNull(LuaJs.run("after.lua", "return 1", false));
    }

    @Test
    public void utf8StringsCrossIntact() {
        String s = "h\u00E9llo w\u00F6rld \u2211 \uD83D\uDE00 \u0000 end";
        LuaJs.pushstring(s);
        assertEquals(1, LuaJs.gettop());
        assertEquals(4, LuaJs.type(-1));   // LUA_TSTRING
        assertTrue(LuaJs.isstring(-1));
        assertEquals(s, LuaJs.tostring(-1));
        LuaJs.pop(1);
        LuaJs.pushnumber(1.5);
        assertEquals("1.5", LuaJs.tostring(-1));
        LuaJs.pushinteger(42);
        assertTrue(LuaJs.isinteger(-1));
        assertEquals("42", LuaJs.tostring(-1));
        LuaJs.pushnil();
        assertNull(LuaJs.tostring(-1));
        LuaJs.pop(3);
        assertNull(run("log('\u00FCn\u00EF \uD83D\uDE00 ' .. #'\uD83D\uDE00' .. ' ' .. utf8.len('\u00FCn\u00EF'))"));
        assertEquals("0:\u00FCn\u00EF \uD83D\uDE00 4 3    ", logged("\u00FCn\u00EF"));
    }

    @Test
    public void engineObjectsCrossTheBridge() {
        // constructor (call), number get/set (getn/setn), string get/set (gets/sets) and SWIG's errors
        assertNull(run("local p = Hyperspace.Point(3, 4)\n"
                + "log('point:' .. p.x .. ',' .. p.y)\n"
                + "p.x = 7 ; log('moved:' .. p.x)\n"
                + "local t = Hyperspace.TextString()\n"
                + "t.data = 'd\u00E4t\u00E4 \uD83D\uDE00' ; log('text:' .. t.data .. ':' .. #t.data)\n"
                + "local ok, e = pcall(function() p.x = 'nope' end) ; log('seterr:' .. tostring(e))\n"
                + "log('type:' .. swig_type(p))"));
        assertEquals(logs.toString(), "0:point:3.0,4.0    ", logged("point:"));
        assertEquals("0:moved:7.0    ", logged("moved:"));
        assertEquals("0:text:d\u00E4t\u00E4 \uD83D\uDE00:11    ", logged("text:"));
        assertEquals("0:seterr:Error in Point::x (arg 2), expected 'int' got 'string'    ", logged("seterr:"));
        assertEquals("0:type:Point *    ", logged("type:"));
    }

    @Test
    public void forgedMetamethodCallsAreRefused() {
        // getmetatable hands scripts the C metamethods; a forged first argument would be misread natively
        assertNull(run("local function try(name, f, ...)\n"
                + "  local ok, e = pcall(f, ...)\n"
                + "  log('forged ' .. name .. ':' .. tostring(ok) .. ':' .. tostring(e))\n"
                + "end\n"
                + "local ns = getmetatable(Hyperspace)\n"
                + "try('nsget-table', ns.__index, {}, 'x')\n"
                + "try('nsget-fake', ns.__index, setmetatable({}, {['.get'] = 'AAAAAAAAAAAAAAAA', ['.fn'] = {}}), 'x')\n"
                + "try('nsset-string', ns.__newindex, 'abc', 'k', 'v')\n"
                + "try('nsset-number', ns.__newindex, 5, 'k', 'v')\n"
                + "local p = Hyperspace.Point(1, 2)\n"
                + "local pm = getmetatable(p)\n"
                + "try('index', pm.__index, {}, 'x')\n"
                + "try('newindex', pm.__newindex, 5, 'x', 1)\n"
                + "try('tostring', pm.__tostring, nil)\n"
                + "try('ctor', getmetatable(Hyperspace.Point).__call)\n"
                + "log('normal:' .. tostring(Hyperspace.Point(8, 9).y) .. ':' .. tostring(Hyperspace.ships ~= nil))"));
        for (String name : new String[]{"nsget-table", "nsget-fake", "nsset-string", "nsset-number", "index", "newindex", "tostring", "ctor"}) {
            String line = logged("forged " + name + ":");
            assertNotNull(name + ": " + logs, line);
            assertTrue(line, line.contains(":false:") && line.contains("bad argument #1 to a Hyperspace metamethod"));
        }
        assertEquals("0:normal:9.0:true    ", logged("normal:"));
    }

    @Test
    public void gcErrorsOutsideCallbacksDoNotCrossJni() {
        // finalizers that raise run inside GC steps of the engine's own pushes (no Lua frame around them)
        assertNull(run("keep = {} ; for i = 1, 2000 do keep[i] = setmetatable({}, {__gc = function() error('boom in gc') end}) end"));
        run("keep = nil");   // a finalizer may already fail inside this chunk; that one is an ordinary script error
        for (int i = 0; i < 200_000 && logged("Lua error outside a callback") == null; i++) {
            LuaJs.pushstring("garbage " + i);
            LuaJs.newtable();
            LuaJs.pop(2);
        }
        String line = logged("Lua error outside a callback");
        assertNotNull("finalizer error caught at the JNI boundary: " + logs, line);
        assertTrue(line, line.startsWith("2:Lua error outside a callback: error in __gc metamethod"));
        assertTrue(line, line.contains("boom in gc"));
        assertNull(run("log('after gc errors')"));
        assertNotNull(logged("after gc errors"));
    }

    @Test
    public void gcErrorsInsideEngineCallsBecomeLuaErrors() {
        // every allocation in the loop sits inside pcall: in Lua code or deferred from the engine's push of the result
        assertNull(run("keep = {} ; for i = 1, 2000 do keep[i] = setmetatable({}, {__gc = function() error('boom in gc') end}) end\n"
                + "keep = nil\n"
                + "local n, last = 0, nil\n"
                + "for i = 1, 100000 do\n"
                + "  local ok, e = pcall(Hyperspace.Point, i, i)\n"
                + "  if not ok then n = n + 1 ; last = e end\n"
                + "end\n"
                + "log('gcfail:' .. n .. ':' .. tostring(last))"));
        String line = logged("gcfail:");
        assertNotNull(line);
        assertFalse(line, line.startsWith("0:gcfail:0:"));
        assertTrue(line, line.contains("boom in gc"));
    }

    @Test
    public void javaExceptionsInHostCallsAreCleared() {
        throwOn = "THROW";
        assertNull(run("log('THROW') ; log('after the exception')"));
        assertNull(logged("THROW"));
        assertNotNull(logged("after the exception"));
    }

    @Test
    public void memoryIsCapped() {
        // 1.5 GiB in one request: refused before any of it is touched (uncapped, a 64-bit device would try to fill it)
        String err = run("local s = string.rep('x', 3 * 512 * 1024 * 1024)");
        assertNotNull(err);
        assertTrue(err, err.contains("not enough memory"));
        assertNull(run("log('after the cap')"));
        assertNotNull(logged("after the cap"));
    }

    @Test
    public void deepCRecursionStopsWithALuaError() throws Exception {
        // nested gsub keeps an 8 KiB luaL_Buffer per C level and Lua stops at 200 C levels (~2 MiB of native stack),
        // so the thread that runs Lua needs a stack of a few MiB; 16 MiB here.
        final String[] result = new String[1];
        Thread t = new Thread(null, () -> result[0] = run(
                "local function f(s) return (s:gsub('.', f)) end\nf('x')"), "deep-lua", 16L << 20);
        t.start();
        t.join();
        assertNotNull(result[0]);
        assertTrue(result[0], result[0].contains("stack overflow"));
    }

    @Test
    public void reinitDropsRegistrations() {
        assertNull(run("script.on_internal_event(Defines.InternalEvents.ON_TICK, function() log('tick') end)"));
        assertEquals(1, LuaJs.has(0, LuaApi.EV_ON_TICK));
        LuaJs.reinit();
        assertEquals(0, LuaJs.has(0, LuaApi.EV_ON_TICK));
        assertEquals(0, LuaJs.fire(LuaApi.EV_ON_TICK, 0, 0));
        assertNull(logged("tick"));
        assertTrue(LuaJs.gcKiB() > 0);
    }

    @Test
    public void rawStackOperations() {
        LuaJs.newtable();
        LuaJs.pushstring("v");
        LuaJs.setfield(-2, "k\u00E9");
        LuaJs.pushboolean(true);
        LuaJs.rawseti(-2, 1);
        assertEquals(1, LuaJs.gettop());
        assertEquals(5, LuaJs.type(-1));   // LUA_TTABLE
        LuaJs.pushvalue(-1);
        assertEquals(2, LuaJs.gettop());
        LuaJs.settop(4);
        assertEquals(0, LuaJs.type(-1));   // nil fill
        assertEquals(0, LuaJs.tohandle(-1));
        assertEquals(0, LuaJs.toobjtype(1));
        LuaJs.settop(0);
        assertEquals(-1, LuaJs.type(1));   // LUA_TNONE
    }
}
