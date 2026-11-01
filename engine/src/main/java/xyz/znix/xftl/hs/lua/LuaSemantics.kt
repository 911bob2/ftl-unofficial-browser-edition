// Ported from FTL-Hyperspace (GPL-3.0-or-later) commit 1b05a07 (lua/ script API and .i members, src/ files named below),
// not from the 1.23.2 tag.
package xyz.znix.xftl.hs.lua

import xyz.znix.xftl.hs.lua.LuaApi as A

/**
 * Script API semantics checked against Hyperspace's LuaLibScript.cpp / InternalEvents.cpp / RenderEvents.cpp:
 * stable priority order (higher first, registration order within a priority), Chain CONTINUE/HALT/PREEMPT and
 * non-integer returns, argument rewriting by chain callbacks (non-nil only), first non-nil return of plain events,
 * errors logged and skipped, registration checks with HS's messages, render pre/post order with HALT/PREEMPT,
 * on_load stopping at the first error, the instruction cap. tests/hs-lua/semantics.mjs runs the same script
 * (between the SCRIPT markers) and driver steps in Node.
 */
object LuaSemantics {
    // SCRIPT BEGIN
    const val SCRIPT = """
trace = {}
local function rec(s) trace[#trace + 1] = s end
mode = {}
local E, C = Defines.InternalEvents, Defines.Chain
local function reg(name, prio)
  script.on_internal_event(E.ON_KEY_DOWN, function(key)
    rec(name .. key)
    local m = mode[name]
    if m == 'halt' then return C.HALT elseif m == 'preempt' then return C.PREEMPT
    elseif m == 'seven' then return 7 elseif m == 'string' then return 'x'
    elseif m == 'error' then error('boom ' .. name) end
  end, prio)
end
reg('a', 0) reg('b', 5) reg('c', 0) reg('d', 5) reg('e', -1)
-- argument rewriting: SET_BONUS_POWER(system, amount) return Chain, amount
script.on_internal_event(E.SET_BONUS_POWER, function(sys, amount) rec('p' .. tostring(amount)) return C.CONTINUE, amount + 5 end, 2)
script.on_internal_event(E.SET_BONUS_POWER, function(sys, amount) rec('q' .. tostring(amount)) return C.CONTINUE, nil end, 1)
script.on_internal_event(E.SET_BONUS_POWER, function(sys, amount) rec('r' .. tostring(amount)) end, 0)
-- plain event with a result: DANGEROUS_ENVIRONMENT(danger) return danger
script.on_internal_event(E.DANGEROUS_ENVIRONMENT, function(d) rec('x') return nil end)
script.on_internal_event(E.DANGEROUS_ENVIRONMENT, function(d) rec('y') return not d end)
script.on_internal_event(E.DANGEROUS_ENVIRONMENT, function(d) rec('z') return d end)
-- render pre/post
for _, p in ipairs({0, 10, 5}) do
  script.on_render_event(Defines.RenderEvents.MOUSE_CONTROL,
    function() rec('pre' .. p) if mode.render == p then return C.HALT elseif mode.renderP == p then return C.PREEMPT end end,
    function() rec('post' .. p) end, p)
end
-- registration checks (HS's own messages)
local ok, err = pcall(script.on_internal_event, E.ON_TICK, function(a, b) end)
regArgs = err
ok, err = pcall(script.on_internal_event, E.ON_TICK, function(...) end)
regVararg = err
ok, err = pcall(script.on_internal_event, E.ON_TICK, function() end, 1.5)
regPrio = err
ok, err = pcall(script.on_internal_event, 9999, function() end)
regUnknown = err
-- instruction cap: the looping callback fails, the next one still runs
script.on_internal_event(E.ON_KEY_UP, function(k) while true do end end, 1)
script.on_internal_event(E.ON_KEY_UP, function(k) rec('after' .. k) end, 0)
-- on_load stops at the first failing callback
script.on_load(function() rec('L1') end)
script.on_load(function() error('load failure') end)
script.on_load(function() rec('L3') end)
function take() local s = table.concat(trace, ',') trace = {} return s end
"""
    // SCRIPT END

    /** Expected values (identical in tests/hs-lua/semantics.mjs). */
    // CHECK BEGIN
    const val CHECK = """
local function eq(name, got, want) if got ~= want then error(name .. ': got [' .. tostring(got) .. '] want [' .. tostring(want) .. ']', 0) end end
eq('order', R1, 'b1,d1,a1,c1,e1')
eq('halt', R2, 'b2')
eq('preempt', R3, 'b3')
eq('int7', R4, 'b4')
eq('string', R5, 'b5,d5,a5,c5,e5')
eq('error', R6, 'b6,d6,a6,c6,e6')
eq('rewrite', R7, 'p10,q15,r15')
eq('plain', R8, 'x,y')
eq('render', R9, 'pre10,pre5,pre0,post0,post5,post10')
eq('renderHalt', R10, 'pre10,pre5,post5,post10')
eq('renderPreempt', R11, 'pre10,post10')
eq('cap', R12, 'after3')
eq('load', R13, 'L1')
eq('regArgs', regArgs, 'Error: Callback function for InternalEvent ON_TICK has the wrong number of arguments! Expected 0, got 2\nExpected function of the form: function on_tick()\n')
eq('regVararg', regVararg, 'Error: Provided function for InternalEvent ON_TICK Should not be a variable argument function!\nExpected function of the form: function on_tick()\n')
eq('regPrio', regPrio, "bad argument #3 to 'script.on_internal_event' (integer expected!)")
eq('regUnknown', regUnknown, "bad argument #1 to 'script.on_internal_event' (Unknown InternalEvent!)")
"""
    // CHECK END

    private fun key(ev: Int, k: Int): Boolean { LuaJs.pushinteger(k.toDouble()); val p = LuaJs.fireChain(ev, 1, 0); LuaJs.pop(1); return p }

    fun run(): String {
        val base = LuaJs.gettop()
        var err = HsLua.runString("semantics.lua", SCRIPT)
        if (err != null) return "FAIL semantics script: $err"
        val results = ArrayList<String>()
        fun step(name: String, setup: String, action: () -> Any?, expectReturn: Any? = null) {
            LuaJs.run("setup", "trace = {} mode = {} $setup", false)
            val r = action()
            if (expectReturn != null && r != expectReturn) results.add("$name returned $r, want $expectReturn")
            LuaJs.run("take", "$name = take()", false)
        }
        step("R1", "", { key(A.EV_ON_KEY_DOWN, 1) }, false)
        step("R2", "mode.b = 'halt'", { key(A.EV_ON_KEY_DOWN, 2) }, false)
        step("R3", "mode.b = 'preempt'", { key(A.EV_ON_KEY_DOWN, 3) }, true)
        step("R4", "mode.b = 'seven'", { key(A.EV_ON_KEY_DOWN, 4) }, false)
        step("R5", "mode.b = 'string'", { key(A.EV_ON_KEY_DOWN, 5) }, false)
        step("R6", "mode.d = 'error'", { key(A.EV_ON_KEY_DOWN, 6) }, false)
        step("R7", "", {
            LuaJs.pushnil(); LuaJs.pushinteger(10.0)
            LuaJs.fireChain(A.EV_SET_BONUS_POWER, 2, 1)
            val amount = LuaJs.tonumber(-1); LuaJs.pop(2)
            if (amount != 15.0) results.add("rewrite: final amount $amount, want 15")
            null
        })
        step("R8", "", {
            LuaJs.pushboolean(false)
            val n = LuaJs.fire(A.EV_DANGEROUS_ENVIRONMENT, 1, 1)
            val v = if (n == 1) LuaJs.toboolean(-1) else null
            LuaJs.pop(if (n == 1) 2 else 1)
            if (n != 1 || v != true) results.add("plain: returned $n/$v, want 1/true")
            null
        })
        step("R9", "", { val i = LuaJs.renderPre(A.RE_MOUSE_CONTROL, 0); LuaJs.renderPost(A.RE_MOUSE_CONTROL, Math.abs(i), 0); i }, 3)
        step("R10", "mode.render = 5", { val i = LuaJs.renderPre(A.RE_MOUSE_CONTROL, 0); LuaJs.renderPost(A.RE_MOUSE_CONTROL, Math.abs(i), 0); i }, 2)
        step("R11", "mode.renderP = 10", { val i = LuaJs.renderPre(A.RE_MOUSE_CONTROL, 0); LuaJs.renderPost(A.RE_MOUSE_CONTROL, Math.abs(i), 0); i }, -1)
        LuaJs.setCaps(100000, 0)
        step("R12", "", { key(A.EV_ON_KEY_UP, 3) }, false)
        LuaJs.setCaps(50000000, 0)
        step("R13", "", { LuaJs.onLoad(); null })
        err = LuaJs.run("check.lua", CHECK, false)
        if (err != null) results.add(err)
        if (LuaJs.gettop() != base) results.add("Lua stack not balanced: ${LuaJs.gettop()} vs $base")
        return if (results.isEmpty()) "PASS script API semantics: priority order, HALT/PREEMPT, rewriting, plain results, errors, registration checks, render pre/post, instruction cap, on_load"
        else "FAIL script API semantics: " + results.joinToString("; ")
    }
}
