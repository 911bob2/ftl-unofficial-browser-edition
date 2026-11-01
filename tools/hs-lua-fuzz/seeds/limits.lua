-- Lua stack at its limit while calling the engine: big frames most of the way (the collector walks the stack, so few
-- levels are much faster), then small ones that each call the engine.
local Point = H.Point
local function small(n) pcall(Point, 1, 2) return 1 + small(n + 1) end
local function small2(a, b, c, d) local x = U() local ok = x and x.x return 1 + small2(a, b, c, d) end
local function direct(n) Point(n, 1) return 1 + direct(n + 1) end
local function big(n, f)
  local a0,a1,a2,a3,a4,a5,a6,a7,a8,a9,a10,a11,a12,a13,a14,a15,a16,a17,a18,a19,a20,a21,a22,a23,a24,a25,a26,a27,a28,a29,a30,a31,a32,a33,a34,a35,a36,a37,a38,a39,a40,a41,a42,a43,a44,a45,a46,a47,a48,a49,a50,a51,a52,a53,a54,a55,a56,a57,a58,a59,a60,a61,a62,a63,a64,a65,a66,a67,a68,a69,a70,a71,a72,a73,a74,a75,a76,a77,a78,a79,a80,a81,a82,a83,a84,a85,a86,a87,a88,a89,a90,a91,a92,a93,a94,a95,a96,a97,a98,a99,a100,a101,a102,a103,a104,a105,a106,a107,a108,a109,a110,a111,a112,a113,a114,a115,a116,a117,a118,a119,a120,a121,a122,a123,a124,a125,a126,a127,a128,a129,a130,a131,a132,a133,a134,a135,a136,a137,a138,a139,a140,a141,a142,a143,a144,a145,a146,a147,a148,a149,a150,a151,a152,a153,a154,a155,a156,a157,a158,a159,a160,a161,a162,a163,a164,a165,a166,a167,a168,a169,a170,a171,a172,a173,a174,a175,a176,a177,a178,a179,a180,a181,a182,a183,a184,a185,a186,a187,a188,a189
  if n > 0 then a0 = big(n - 1, f) else a0 = f(1, 2, 3, 4) end
  return a0
end
print(pcall(big, 5100, small))
print(pcall(big, 5100, small2))
print(pcall(big, 5100, direct))
--@
local t, Point = {}, H.Point
for i = 1, 200 do t[i] = i end
local function manyargs(...) return Point(...) end
print(pcall(manyargs, table.unpack(t)))
print(pcall(Point, table.unpack(t)))
print(pcall(string.rep, 'x', 2^40))
--@
-- C recursion through metamethods, pcall, gsub, sort, tostring
local m = setmetatable({}, {})
getmetatable(m).__index = function(s, k) return s[k .. 'x'] end
print(pcall(function() return m.a end))
getmetatable(m).__tostring = function(s) return tostring(s) end
print(pcall(tostring, m))
local function pc(n) return pcall(pc, n + 1) end
print(pc(1))
local function gs(s) return (s:gsub('.', gs)) end
print(pcall(gs, 'abc'))
local function srt(a, b) table.sort({3, 2, 1}, srt) return a < b end
print(pcall(table.sort, {3, 2, 1}, srt))
print(pcall(table.sort, {3, 2, 1, 5, 4, 7, 6, 9, 8}, function(a, b) return true end))
--@
local m = setmetatable({}, {})
print(pcall(string.format, ('%5.2f %s %q %d %x %c'), 1, m, 'q\0', 2, 3, 4))
print(pcall(string.format, ('%99.99f'), 1e308))
print(pcall(string.pack, 'i17', 1))
print(pcall(string.unpack, '<!16i16', ('\0'):rep(32)))
print(pcall(string.pack, 's1', ('x'):rep(300)))
print(pcall(table.concat, {1, {}, 3}))
print(pcall(table.move, {1, 2, 3}, 1, math.maxinteger, 2))
print(pcall(table.insert, {}, 2^53, 1))
print(pcall(utf8.char, -1, 2^31, 65))
print(pcall(utf8.codepoint, '\xff\xff', 1, 2))
print(pcall(function() for p, c in utf8.codes('ab\xffc') do end end))
print(pcall(utf8.offset, 'abc', 5, 10))
print(pcall(string.dump, print))
print(#string.dump(function() return 1 end))
print(load, loadstring, dofile, collectgarbage, rawget, rawset, require, os, io, debug, coroutine, package)
--@
-- fill the heap, then allocate in the bridge
local Point = H.Point
local hog = {}
print(pcall(function() while true do hog[#hog + 1] = ('h'):rep(1 << 20) .. #hog end end))
print(pcall(Point, 1, 2))
print(pcall(script.on_game_event, 'hog', false, print))
print(pcall(function() return U() end))
for i = 1, 100 do local u = U() end
hog = nil
--@
-- the instruction cap with pcall and finalizers
local Point = H.Point
print(pcall(function() while true do pcall(function() while true do end end) end end))
setmetatable({}, {__gc = function() while true do pcall(function() while true do end end) end end})
script.on_internal_event(E.ON_TICK, function() while true do pcall(Point, 1, 2) end end)
script.on_internal_event(E.ON_KEY_DOWN, function() local function f() return pcall(f) end while true do f() end end)
