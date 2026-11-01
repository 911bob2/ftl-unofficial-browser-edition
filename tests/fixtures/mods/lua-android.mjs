// Cases for the native Lua patch runtime on Android (android/app/src/debug/.../LuaVectors.kt, our own content only).
// security: tests/lib/mods-lua-security.mjs as data. parity: scripts whose expected result is what web/mods/lua.mjs and
// merge.mjs produce (tools/mods/kotlin-vectors.mjs runs them on web/vendor/ftlman-lua.wasm when exporting); they cover
// what depends on the platform: table order, numbers, math.random, libc. native: what the wasm sandbox hides.
import {zipSync} from '../../../web/vendor/fflate-0.8.3.mjs';

const enc = new TextEncoder();
export const luaDoc = '<FTL>\n<a/>\n</FTL>\n';
export const luaReport = `local function R(v) local e = mod.xml.element("r") e:append(tostring(v)) document.root:append(e) end\n`;
const zip = entries => zipSync(Object.fromEntries(entries.map(([n, t]) => [n, typeof t === 'string' ? enc.encode(t) : t])), {mtime: new Date(Date.UTC(2026, 0, 1))});

// ---- tests/lib/mods-lua-security.mjs: `small` is 2e6 instructions and 64 MiB; results are the <r> rows in order --------
const absent = ['io', 'os', 'package', 'debug', 'require', 'dofile', 'loadfile', 'collectgarbage', 'rawset', 'coroutine', 'utf8', 'loadstring', 'module', 'arg'];
export const security = [
  {name: 'absent-libraries', code: absent.map(n => `R(type(${n}))`).join('\n'), results: absent.map(() => 'nil')},
  {name: 'locked-metatables', code: `R(type(mod.vfs)) R(getmetatable(document)) R(getmetatable(mod.xml.element("x"))) R(pcall(setmetatable, string, {}))`, results: ['nil', 'false', 'false', 'false']},
  {name: 'no-bytecode', code: `local f, e = load(string.dump(function() return 1 end)) R(f) R(e ~= nil)
    local g = load(string.dump(function() return 1 end), "x", "b") R(g)
    R(load("return 5", "x", "bt")())`, results: ['nil', 'true', 'nil', '5']},
  ...['while true do end', 'while true do pcall(function() while true do end end) end',
    'while true do xpcall(function() while true do end end, function(m) return m end) end',
    'local function f() return pcall(f) end while true do f() end', 'repeat local ok = pcall(error, "x") until false',
    'local e = mod.xml.element("d") for i = 1, 20000 do local c = mod.xml.element("d") e:append(c) e = c end',
  ].map((code, i) => ({name: 'instruction-limit-' + i, small: true, bare: true, code, error: 'instruction limit'})),
  {name: 'runs-after-instruction-limit', small: true, code: 'local n = 0 for i = 1, 1000 do n = n + i end R(n)', results: ['500500']},
  ...['local t = {} for i = 1, 1e9 do t[i] = string.rep("x", 100) .. i end', 'local s = string.rep("x", 200 * 1024 * 1024)',
    'local e = mod.xml.element("x", {a = string.rep("y", 1e6)}) for i = 1, 1e6 do document.root:append(e:clone()) end',
    'local t = {} while true do t[#t + 1] = pcall(string.rep, "x", 1e6) and string.rep("x", 1e6) end',
  ].map((code, i) => ({name: 'memory-limit-' + i, small: true, bare: true, code, error: 'memory limit|not enough memory|instruction limit'})),
  {name: 'runs-after-memory-limit', small: true, code: 'R(#string.rep("ab", 10))', results: ['20']},
  {name: 'sibling-loop', code: 'local a = document.root:firstElementChild() local b = mod.xml.element("b") a:append(b, mod.xml.element("c")) b:detach() a:append(b)', error: 'sibling links form a loop'},
  {name: 'deep-tree', code: `local top = mod.xml.element("d") local e = top
    for i = 1, 50000 do local c = mod.xml.element("d") e:append(c) e = c end
    e:append("leaf") document.root:append(top) R(#mod.xml.stringify(top:clone())) R(top.textContent)`, result1: 'leaf'},
  {name: 'vfs-bounds', fs: true, code: `R(mod.vfs.pkg:read("/data/a.txt")) R(pcall(mod.vfs.pkg.read, mod.vfs.pkg, "/../../etc/passwd"))
    R(pcall(mod.vfs.pkg.read, mod.vfs.pkg, "data/a.txt")) R(pcall(mod.vfs.mod.write, mod.vfs.mod, "/data/x", "y")) R(pcall(function() mod.vfs.evil = 1 end))
    R(pcall(mod.vfs.pkg.read, mod.vfs.pkg, "/data/../data/a.txt")) R(mod.vfs.mod:read("/data/x.txt")) mod.vfs.pkg:write("/data/new.txt", "N")
    load("kept = mod.vfs.pkg")()`, results: ['A', 'false', 'false', 'false', 'false', 'false', 'X'], written: ['data/new.txt']},
  {name: 'vfs-kept-stops', fs: true, code: 'R(pcall(kept.read, kept, "/data/a.txt")) R(type(mod.vfs))', results: ['false', 'table']},
];
export const securityMod = zip([['data/x.txt', 'X']]);
// A failing script in a merge; instructions 2e6. The base holds data/blueprints.xml = luaDoc; archive bad.zip.
export const securityMerges = [
  {name: 'merge-runtime-error', script: 'local x = nil; x.y = 1', error: 'data/blueprints\\.xml according to data/blueprints\\.append\\.lua from bad\\.zip: runtime error: data/blueprints\\.append\\.lua:1: attempt to index a nil value'},
  {name: 'merge-instruction-limit', script: 'while true do end', error: 'instruction limit'},
  {name: 'merge-syntax-error', script: 'local = 1', error: 'syntax error'},
];

// ---- parity: one runtime, in this order, as in the wasm ----------------------------------------------------------------
const put = `local function P(...) local t = table.pack(...) for i = 1, t.n do t[i] = tostring(t[i]) end document.root:append(mod.xml.element("r", {v = table.concat(t, "|")})) end\nlocal function G(x) return string.format("%.17g", x) end\n`;
const parityScripts = {
  'pairs-strings': `local t = {} for i = 1, 3000 do t["key" .. (i * 7919 % 10007)] = i end
    local parts = {} for k, v in pairs(t) do parts[#parts + 1] = k .. "=" .. v end P(table.concat(parts, ","))
    for i = 1, 3000, 2 do t["key" .. (i * 7919 % 10007)] = nil end
    t.extra, t[1], t[2.5] = 1, 2, 3
    parts = {} for k, v in pairs(t) do parts[#parts + 1] = tostring(k) .. "=" .. v end P(table.concat(parts, ","))`,
  'pairs-mixed': `local t = {[1] = 1, [2] = 2, [4] = 4, [-1] = 5, [0] = 6, [2^53] = 7, [1.5] = 8, [-0.25] = 9, [1e300] = 10, [true] = 11, [false] = 12, [1 << 40] = 13, [math.mininteger] = 14,
      [string.rep("long", 20)] = 15, [string.rep("LONG", 20)] = 16, a = 17, b = 18, c = 19, [""] = 20}
    local parts = {} for k, v in pairs(t) do parts[#parts + 1] = tostring(k) .. "=" .. v end P(table.concat(parts, ","))`,
  'pairs-libraries': `for _, lib in ipairs({string, table, math, mod, mod.xml, mod.debug, mod.iter, mod.table, mod.util}) do
      local parts = {} for k in pairs(lib) do parts[#parts + 1] = k end P(table.concat(parts, ","))
    end`,
  'random': `P(G(math.random()), math.random(100), math.random(5, 1000000))
    math.randomseed(42) P(G(math.random()), math.random(1000000), math.random(-5, 5))
    math.randomseed(2147483653) P(G(math.random()), math.random(1000000))
    math.randomseed(0) P(G(math.random()))`,
  'numbers': `P(1, -1, 0.1, 1/3, 1e15, 1e16, 1e100, -1e-100, 2^53, 2^63, 100 // 1.0, -0.0, math.huge, -math.huge, 3.0, 1e15 + 0.5)
    P(math.maxinteger, math.mininteger, math.maxinteger + 1, math.mininteger - 1, 7 // 2, -7 // 2, 7 % 3, -7 % 3, 7 % -3, 7.5 % -2, -7.5 % 2, 5 // 0.0, -5 // 0.0, 5 % math.huge, -5 % math.huge)
    P(1 << 62, 1 << 63, 1 << 64, -1 >> 1, ~0, 5 & 3, 5 | 3, 5 ~ 3, 2^0.5, 10 / 2)
    P("10" + 1, "0x10" + 0, "1e1" + 0, "3" * "4", 10 .. 20, "5" // 2, "7" % "3", -"2", "2" ^ 2)
    P(math.tointeger(3.0), math.tointeger(3.5), math.type(1), math.type(1.0), math.type("1"), tonumber("0x1p4"), tonumber("  10  "), tonumber("10", 2), tonumber("zz", 36), tonumber("1e"), tonumber(""), tonumber("0x"), tostring(1e15), tostring(123456789012345678))
    P(string.format("%d %5d %-5d| %05d %x %X %o %c %e %E %f %g %G", 42, 42, 42, 42, 255, 255, 8, 65, 1.5, 1.5, 1.5, 1.5, 1e20))
    P(string.format("%a %A %.2a %5.1f %.0f %.3g %#x %+d % d %%", 1.5, 0.1, 3.14, 2.25, 2.5, 1234.5, 255, 5, 5))
    P(string.format("%s %10s %-10s| %.2s %q %q %q %q", "a", "b", "c", "defg", "x\\n\\0y\\"", 1, 1.5, math.mininteger))
    P(string.format("%.14g %.17g %g %g", 0.1, 0.1, 100000, 1000000), string.format("%i", 3.0), pcall(string.format, "%d", 3.5))
    P(pcall(string.format, "%y", 1), pcall(string.format, "%", 1), pcall(string.rep))`,
  'math': `P(math.floor(2.5), math.ceil(2.5), math.floor(-2.5), math.ceil(-2.5), math.abs(-3), math.max(1, 2.5), math.min(1, 2.5), math.ult(1, -1), math.pi)
    P(G(math.sin(1)), G(math.cos(1)), G(math.tan(1)), G(math.asin(0.5)), G(math.acos(0.5)), G(math.atan(1)), G(math.atan(1, 2)), G(math.exp(1)), G(math.log(10)), G(math.log(8, 2)), G(math.log(1000, 10)), G(math.log(7, 3)), G(math.sqrt(2)))
    P(G(math.fmod(7, 3)), G(math.fmod(-7, 3)), G(math.fmod(7.5, 2)), math.modf(3.7), math.modf(-3.7), math.modf(math.huge), pcall(math.fmod, 1, 0))
    P(G(math.pow(2, 0.5)), G(math.cosh(1)), G(math.sinh(1)), G(math.tanh(1)), G(math.log10(1000)), math.frexp(8), G(math.ldexp(1.5, 4)))
    P(G(2^0.5), G(10^-2), G(1.1^100), G(3.3 * 4.4 + 5.5), G(1e308 * 10), G(0.1 + 0.2))`,
  'strings': `local s = "H\\xc3\\xa9llo W\\xffrld\\0end"
    P(#s, s:upper(), s:lower(), s:reverse(), s:byte(1, 3), s:sub(2, 4), s:sub(-3), ("x"):rep(3, ","), ("abc"):find("b", 1, true))
    P("\\xff" < "a", "a\\0b" < "a\\0c", "a" < "a\\0", "" < "a", "Z" < "a", "\\xc3\\xa9" < "\\xc3\\xa8")
    P(("hello world"):find("o w"), ("hello"):match("(h)(.)"), ("key=val"):match("(%w+)=(%w+)"), ("abc"):gsub("%w", "%0%0"), ("abc"):gsub("", "-"), ("hello"):gsub("l", {l = "L"}), ("hello"):gsub("(l)(l)", function(a, b) return a:upper() .. b end))
    P(("[[x]]"):match("%b[]"), ("THE (quick) fox"):find("%((%a+)%)"), ("hello"):match("()ll()"), ("  trim  "):match("^%s*(.-)%s*$"), ("a,b,,c"):gsub(",", ";", 2), ("\\xe9\\xff"):match("%a*"), ("\\xe9"):match("%w"), ("\\xa0"):match("%s"), ("x\\xff"):upper())
    P(("%f"):rep(2), ("THE (quick) fox"):gsub("%f[%a]%a+", string.lower), pcall(string.gsub, "a", "(", "b"), pcall(string.find, "a", "[a"), pcall(string.rep, "x", -1))
    local parts = {} for w, n in ("a=1, b=2"):gmatch("(%w+)=(%w+)") do parts[#parts + 1] = w .. n end P(table.concat(parts, ";"))
    P(string.pack("<i4", 100):byte(1, -1)) P(string.pack(">I2", 258):byte(1, -1)) P(string.pack("z", "ab"):byte(1, -1)) P(string.pack("s1", "ab"):byte(1, -1)) P(string.pack("<d", 1.5):byte(1, -1)) P(string.pack("<f", 1.5):byte(1, -1)) P(string.pack("<j", -2):byte(1, -1))
    P(string.unpack("<i4", string.pack("<i4", -7))) P(string.packsize("i4 i8 d")) P(string.char(72, 105), pcall(string.char, 256), tostring(nil), tostring(true), select("#"), select("#", nil, nil), select(-1, 1, 2))`,
  'sort-and-table': `local t, x = {}, 12345
    for i = 1, 500 do x = (x * 1103515245 + 12345) % 2147483648 t[i] = x end
    local tagged = {} for i, v in ipairs(t) do tagged[i] = {v % 10, i} end
    table.sort(tagged, function(a, b) return a[1] < b[1] end)
    local parts = {} for i, v in ipairs(tagged) do parts[i] = v[2] end P(table.concat(parts, ","))
    table.sort(t) P(t[1], t[250], t[500]) table.sort(t, function(a, b) return a > b end) P(t[1], t[250], t[500])
    P(pcall(table.sort, {3, 1, 2, 5, 4, 7, 6, 9, 8, 10}, function() return true end))
    local q = {1, 2, 3} table.insert(q, 4) table.insert(q, 1, 0) P(table.concat(q, ","), table.remove(q), table.remove(q, 1), #q, table.unpack(q)) P(table.pack(1, nil, 3).n, table.concat(table.move({1, 2, 3}, 1, 3, 2), ","))
    P(pcall(table.insert, q, 9, 9), pcall(table.concat, {{}}), rawlen({1, 2}), rawlen("abc"), rawequal(q, q), rawget(q, 1), next({}), type(next))`,
  'xml-parse': `local function show(s, o)
      local r = table.pack(pcall(mod.xml.parse, s, o))
      if not r[1] then P("error", r[2]) return end
      local parts = {} for i = 2, r.n do parts[#parts + 1] = tostring(r[i]) .. ":" .. mod.xml.stringify(r[i]) end P(r.n - 1, table.concat(parts, ";"))
    end
    show('<a x="1" b="&lt;&amp;&quot;" a="2"  c = \\'q\\'><b/>text &amp; more<![CDATA[<raw>]]><!-- c > d --><p:q r="1"/></a>')
    show('\\xef\\xbb\\xbf<a/>') show('<?xml version="1.0"?>\\n<FTL>\\n<a/>\\n</FTL>') show('<!DOCTYPE x [ <!ELEMENT a> ]><a/>') show('<a/><b/>') show('')
    show('text') show('text<a/>more', {allow_top_level_text = true}) show('<a>', {}) show('</a>') show('<a></b>') show('<a b=1/>') show('<a b="1"') show('<a\\0/>') show('<!-- x') show('<![CDATA[ x') show('<?pi')
    show('<a>&#65;&#x42;&#xd800;&bad;&amp;</a>') show('<a b="&#65;"/>') show('<\\xc3\\xa9 \\xc3\\xa8="\\xc3\\xa7">\\xf0\\x9f\\x98\\x80</\\xc3\\xa9>') show('<a>\\xef\\xbb\\xbfb</a>') show('\\xff') show('<a b:c="1"/>')
    P(pcall(mod.xml.parse, "<a/>", {nope = true})) P(pcall(mod.xml.parse, "<a/>", {allow_top_level_text = 1})) P(pcall(mod.xml.parse, {}))`,
  'xml-tree': `local e = mod.xml.element("a", {z = 1, y = 2.5, x = true, w = "s<&\\"", v = -0.0, u = 1e100, t = 1 << 40})
    local c = e:append("text", mod.xml.element("b"), mod.xml.element("p", "c")) P(mod.xml.stringify(e)) P(tostring(e))
    for k, v in e:attrs() do P(k, v, type(v)) end
    for k, v in e:rawattrs() do P(k, v) end
    P(e.attrs.z, e.rawattrs.z, e.attrs.y, e.attrs.nope, e.attrs.x, e.prefix, e.name, e.type)
    local kid = e:firstElementChild() P(kid.name, kid.type, tostring(e:lastElementChild()), tostring(e:firstChild()), tostring(kid.parent), tostring(kid.previousSibling), tostring(kid.nextSibling))
    kid:after("after") kid:before("before") e:prepend("first") P(mod.xml.stringify(e)) P(e.textContent) e.textContent = "<reset>" P(mod.xml.stringify(e), tostring(e:firstChild()))
    P(pcall(function() e.attrs["bad name"] = 1 end)) P(pcall(function() e.attrs.q = {} end)) P(pcall(function() e.rawattrs.q = 1 end)) P(pcall(function() e.name = "1x" end)) P(pcall(function() e.nope = 1 end)) P(pcall(function() e.name = "\\xff" end))
    P(pcall(mod.xml.element)) P(pcall(mod.xml.element, "a b")) P(pcall(mod.xml.element, "a", {["b c"] = 1})) P(pcall(e.append, e, e)) P(pcall(e.before, e, "x")) P(pcall(e.clone, e, "nope"))
    P(tostring(mod.xml.parse("x", {allow_top_level_text = true}))) P(mod.xml.stringify("a<b", 1, 2.5))
    P(mod.xml.append("<a><b/></a>", "<mod:findName name=\\"x\\"><mod:setValue>y</mod:setValue></mod:findName><c/>"))
    P(mod.xml.append("<FTL><a/></FTL>", "<c/>", {type = "xml.rawappend"})) P(pcall(mod.xml.append, "<a/>", "<c/>", {type = "nope"})) P(pcall(mod.xml.append, "<a/>", "<mod:bad/>"))
    P(pcall(mod.xml.append, "<a/>", "<mod:" .. string.rep("\\xc3\\xa9", 3000) .. "/>"))
    local d = mod.xml.parse(string.rep("<a>", 2000) .. "deep" .. string.rep("</a>", 2000)) local n = 0 while d do n = n + 1 d = d:firstElementChild() end P(n)
    document.root:append(e, "tail text", "\\xef\\xbb\\xbfbom text")`,
  'debug': `P(mod.debug.pretty_string({1, 2.5, "s\\n\\"", true, {a = {b = 1e100}}, [100] = 0.1}))
    P(mod.debug.pretty_string({x = 1}, {indent = "  "})) P(mod.debug.pretty_string({x = {y = 1}}, {recursive = false}) ~= nil) P(mod.debug.pretty_string(1.5), mod.debug.pretty_string("\\xff\\xc3\\xa9\\0\\t"), mod.debug.pretty_string(nil))
    mod.debug.pretty_print({1, {2}}) P(mod.debug._compare({1, {2}}, {1, {2}}), mod.debug._compare({1}, {2}), mod.debug._compare(1, 1.0))
    P(pcall(mod.debug.assert_equal, 1, 2)) P(pcall(mod.debug.pretty_string, 1, {nope = 1})) P(tostring(mod.xml.element("x", {a = "\\xc3\\xa9\\n"})))
    local t = {} for i = 1, 300 do t = {t} end P(pcall(mod.debug.pretty_string, t)) P(pcall(mod.debug._compare, t, t))`,
  'eval-and-meta': `P(mod.meta.current_path, mod.util.eval("1 + 1", {env = {}}), mod.util.eval("return x", {env = {x = 5}}), mod.util.eval("x = 7 return x", {env = {}}))
    P(pcall(mod.util.eval, "error('e')", {env = {}, name = "named"})) P(pcall(mod.util.eval, "1 +", {env = {}})) P(pcall(mod.util.eval, "1")) P(mod.util.eval("mod.meta.current_path", {env = {mod = mod}, path = "/other"}), mod.meta.current_path)
    local ro = mod.util.readonly({a = 1}) P(ro.a, pcall(function() ro.b = 1 end))
    P(mod.iter.count(mod.iter.map(mod.table.iter_array({1, 2, 3}), function(x) return x * 2 end)), table.concat(mod.iter.collect(mod.table.iter_array({"a", "b"})), ","), mod.table.compare_arrays({1, 2}, {1, 3}), mod.table.compare_arrays({1}, {1, 2}))
    P(type(mod.vfs), type(document), document.root.name, _VERSION, type(_G), _G == _ENV, pcall(function() mod.xml.x = 1 end), pcall(function() string.x = 1 end), getmetatable("").__name, type(getmetatable("").__index))
    load("shared = 11")() P(shared) sharedEnv = 1`,
  'globals-across-scripts': `P(shared, sharedEnv)`,
  'errors-print': `print("line", 1, 2.5, nil, true) print("\\xef\\xbb\\xbfbom") print("bad \\xff\\xc3 bytes") print("two\\nlines")`,
  'error-value': `local x = nil; x.y = 1`,
  'error-bytes': `error("\\xff\\xfe bad \\xc3 bytes")`,
  'error-long': `error(string.rep("\\xc3\\xa9", 5000))`,
  'error-table': `error({})`,
  'error-tostring': `error(setmetatable({}, {__tostring = function() return "custom" end}))`,
  'error-level': `local function f() error("level2", 2) end f()`,
  'error-nested-pcall': `P(pcall(error)) P(pcall(error, nil)) P(pcall(error, 1)) P(select(2, xpcall(function() error("x") end, function(m) return "handled " .. m end))) P(pcall(pcall)) P(xpcall(function() return 1, 2 end, print))`,
  'error-syntax': `local = 1`,
  'gc-finite': `setmetatable({}, {__gc = function() print("finalized") end}) P("before")`,
  'after-gc': `P("after")`,
};
export const parityCases = Object.entries(parityScripts).map(([name, code]) => ({name, code: put + code}));

// Merges through merge.mjs (lazyBinary as the app runs them).
const vfsProbe = `local pkg, modfs = mod.vfs.pkg, mod.vfs.mod
local function P(...) local t = table.pack(...) for i = 1, t.n do t[i] = tostring(t[i]) end document.root:append(mod.xml.element("r", {v = table.concat(t, "|")})) end
local function e(f, ...) local r = table.pack(pcall(f, ...)) if r[1] then return "ok" end return tostring(r[2]) end
local function ls(fs, p) local out = {} for _, x in ipairs(fs:ls(p)) do out[#out + 1] = x.filename .. ":" .. x.type end return table.concat(out, ",") end
local function st(fs, p) local s = fs:stat(p) if not s then return "nil" end return s.type .. ":" .. tostring(s.length) end
P(mod.meta.current_path, ls(pkg, "/data"), ls(modfs, "/data"), ls(modfs, "/extra"), ls(pkg, "/img"))
P(st(pkg, "/data/a.xml"), st(pkg, "/img/base.png"), st(pkg, "/img/added.bin"), st(pkg, "/nope"), st(pkg, "/data/"), st(pkg, "/"), st(modfs, "/data/a.append.lua"), st(modfs, "/data/back.txt"), st(modfs, "/data"), st(modfs, "/nope"))
P(e(pkg.ls, pkg, "/"), e(pkg.ls, pkg, "/nope"), e(pkg.ls, pkg, "/data/a.xml"), e(pkg.read, pkg, "data/a.xml"), e(pkg.read, pkg, "/data"), e(pkg.read, pkg, "/nope"), e(pkg.write, pkg, "/data", "x"), e(pkg.write, pkg, "/data/a.xml/sub", "x"), e(pkg.write, pkg, "nope", "x"))
P(e(modfs.read, modfs, "/nope"), e(modfs.read, modfs, "/data"), e(modfs.read, modfs, "/data/back.txt"), e(modfs.stat, modfs, "/data/back.txt"), e(modfs.write, modfs, "/x", "y"), e(modfs.ls, modfs, "/nope"), e(modfs.ls, modfs, "/data/a.append.lua"))
P(#pkg:read("/img/base.png"), #pkg:read("/img/added.bin"), pkg:read("/img/added.bin"), modfs:read("/extra/\\xc3\\xa9.txt"), pkg:read("/data/a.xml"))
pkg:write("/data/written.txt", "w\\xff\\0x") pkg:write("/new/dir/file.bin", "n") pkg:write("/img/added.bin", "replaced")
P(st(pkg, "/data/written.txt"), ls(pkg, "/new/dir"), pkg:read("/img/added.bin"))
`;
export const parityBase = {'data/a.xml': '<FTL>\n<!-- c --><base x="1"/>\n</FTL>\n', 'data/noroot.xml': '<a/>\n<b/>\n', 'data/Case.xml': '<FTL><c/></FTL>', 'img/base.png': new Uint8Array([1, 2, 3, 255]), 'data/text.txt': 'text\r\n'};
export const parityMerges = [
  {name: 'vfs', archives: [['p1.zip', [['img/added.bin', new Uint8Array([97, 98, 0, 255])], ['data\\back.txt', 'b'], ['extra/é.txt', 'accent'], ['extra/😀.txt', 'x'], ['extra/￯.txt', 'y'], ['extra/z.txt', 'z'], ['data/a.append.lua', vfsProbe]]]]},
  {name: 'order-and-case', archives: [['p1.zip', [['data/case.append.lua', `document.root:append(mod.xml.element("d", {s = mod.meta.current_path}))`], ['data/noroot.append.lua', `document.root:append("t") shared = 5`], ['data/missing.append.lua', `error("never")`], ['data/a.xml.append', `<x/>`]]],
    ['p2.ftl', [['data/a.append.lua', `local r = mod.vfs.pkg:read("/data/a.xml") document.root:append(mod.xml.element("seen", {n = #r, g = load("return shared")()}))`], ['data/noroot.xml', `<replaced  a='1'/>`], ['data/noroot.append.lua', `document.root:append(mod.xml.element("again"))`], ['skipme/x.txt', 'x']]]]},
  {name: 'script-writes-xml-then-append', archives: [['p1.zip', [['data/a.append.lua', `mod.vfs.pkg:write("/data/made.xml", "<made  z='1'  a='2'/>") mod.vfs.pkg:write("/data/Case.xml", "<replaced/>")`], ['data/made.append.lua', `document.root:append(mod.xml.element("x"))`], ['data/made.xml.append', `<y/>`]]]]},
  {name: 'filesystem-overlap', archives: [['p1.zip', [['data/a.append.lua', `error("never")`], ['data/z', 'file'], ['data/z/y', 'under file']]]]},
  {name: 'filesystem-duplicate-names', archives: [['p1.zip', [['data/q', 'file'], ['data\\q', 'file'], ['data/a.append.lua', `error("never")`]]]]},
  {name: 'error-runtime', archives: [['bad.zip', [['data/a.append.lua', `local x = nil; x.y = 1`]]]]},
  {name: 'error-syntax', archives: [['bad.zip', [['data/a.append.lua', `local = 1`]]]]},
  {name: 'error-script-encoding', archives: [['bad.zip', [['data/a.append.lua', new Uint8Array([0x61, 0xff])]]]]},
  {name: 'error-document', archives: [['bad.zip', [['data/a.xml', '<a><b></a>'], ['data/a.append.lua', `document.root:append("x")`]]]]},
  {name: 'no-root-document', archives: [['bad.zip', [['data/a.xml.rawclobber', 'just text'], ['data/a.append.lua', `document.root:append("x")`]]]]},
];
export const parityZip = entries => zip(entries);

// ---- native: bombs and misuse that must end in an error or a result, never a crash or a hang --------------------------
const bomb = n => `string.rep("<a>", ${n}) .. string.rep("</a>", ${n})`;
export const native = [
  // hooks are off inside __gc in stock Lua
  {name: 'gc-loop-after-script', small: true, code: `setmetatable({}, {__gc = function() while true do end end}) R("done")`, results: ['done']},
  {name: 'gc-runs-next-script', small: true, code: 'R("next")', results: ['next']},
  {name: 'gc-loop-during-script', small: true, code: `setmetatable({}, {__gc = function() while true do end end}) for i = 1, 1e8 do local t = {i} end`, error: 'instruction limit'},
  {name: 'gc-loop-pcall-after-script', small: true, code: `setmetatable({}, {__gc = function() while true do pcall(function() while true do end end) end end}) R("done")`, results: ['done']},
  {name: 'gc-many-finalizers', small: true, code: `for i = 1, 1000 do setmetatable({}, {__gc = function() while true do end end}) end R("done")`, anyEnd: true},
  {name: 'gc-recreating-finalizer', small: true, code: `local function f() setmetatable({}, {__gc = f}) end f() for i = 1, 1000 do local t = {i} end R("done")`, results: ['done']},
  {name: 'gc-loop-left-for-close', small: true, code: `load("kept_for_close = setmetatable({}, {__gc = function() while true do end end})")() R("done")`, results: ['done']},
  // a userdata resurrected by a __gc handler, used after its own finalizer and after the sweep
  {name: 'resurrect-setup', code: `local e = mod.xml.element("x", {a = "1"}) e:append("t", mod.xml.element("y"))
    local keep = load("return function(t) saved = t end")()
    setmetatable({attrs = e.attrs, text = e:childNodes()(), kids = e:children(), nodes = e:childNodes(), el = e, raw = e.rawattrs, it = e:attrs()}, {__gc = keep}) R("set")`, results: ['set']},
  {name: 'resurrect-use', code: `local s = load("return saved")()
    R(type(s)) R((pcall(function() return s.attrs.a end))) R((pcall(function() s.attrs.a = 1 end))) R((pcall(s.attrs, s.el))) R((pcall(function() return s.text.content end))) R((pcall(function() s.text.content = "z" end))) R((pcall(tostring, s.text)))
    R((pcall(s.kids))) R((pcall(s.nodes))) R((pcall(function() return s.el.name end))) R((pcall(tostring, s.el))) R((pcall(function() return s.raw.a end))) R((pcall(s.it))) R((pcall(mod.xml.stringify, s.el))) R((pcall(function() document.root:append(s.el) end)))`,
    results: ['table', 'false', 'false', 'false', 'false', 'false', 'false', 'false', 'false', 'false', 'false', 'false', 'true', 'false', 'false']},
  // the C stack: every recursion a script can drive
  {name: 'recursion-gsub', code: `local function f(s) return (s:gsub(".", f)) end R((pcall(f, "abc")))`, results: ['false']},
  {name: 'recursion-index', code: `local t = setmetatable({}, {__index = function(t, k) return t[k] end}) R((pcall(function() return t.x end)))`, results: ['false']},
  {name: 'recursion-tostring', code: `local t = setmetatable({}, {__tostring = function(t) return tostring(t) end}) R((pcall(tostring, t)))`, results: ['false']},
  {name: 'recursion-sort', code: `local function f(a, b) table.sort({3, 2, 1}, f) return a < b end R((pcall(table.sort, {3, 2, 1}, f)))`, results: ['false']},
  {name: 'recursion-lua', code: `local function f() return 1 + f() end R((pcall(f)))`, results: ['false']},
  {name: 'recursion-load', code: `local function f() return load("return 1")() + f() end R((pcall(f)))`, results: ['false']},
  {name: 'recursion-eval', code: `local function f() return mod.util.eval("f()", {env = {f = f}}) end R((pcall(f)))`, results: ['false']},
  {name: 'recursion-xpcall-handler', code: `local function h(m) error(m) end R((xpcall(error, h, "x")))`, results: ['false']},
  {name: 'recursion-compare', code: `local t = {} t[1] = t R((pcall(mod.debug._compare, t, t)))`, results: ['false']},
  {name: 'parser-parens', code: `R(load("return " .. string.rep("(", 1000000)))`, results: ['nil']},
  {name: 'parser-tables', code: `R(load("return " .. string.rep("{", 1000000)))`, results: ['nil']},
  {name: 'parser-blocks', code: `R(load(string.rep("do ", 1000000)))`, results: ['nil']},
  {name: 'parser-functions', code: `R(load(string.rep("function f() ", 100000)))`, results: ['nil']},
  {name: 'parser-concat', code: `R(load("return " .. string.rep("a..", 1000000) .. "a"))`, results: ['nil']},
  {name: 'parser-unary', code: `R(load("return " .. string.rep("- ", 1000000) .. "1"))`, results: ['nil']},
  {name: 'pattern-depth', code: `R((pcall(string.find, string.rep("a", 1000000), string.rep("a?", 100000))))`, results: ['false']},
  {name: 'pattern-captures', code: `R((pcall(string.find, "a", string.rep("(", 100))))`, results: ['false']},
  {name: 'pattern-gsub-long', code: `R(#string.gsub(string.rep("a", 1000000), "a", "bb"))`, results: ['2000000']},
  {name: 'unpack-too-many', code: `R((pcall(table.unpack, {}, 1, 1e7)))`, results: ['false']},
  {name: 'concat-huge', code: `R((pcall(table.concat, {}, "", 1, 1e9)))`, results: ['false']},
  {name: 'rep-huge', code: `R((pcall(string.rep, "abc", math.maxinteger)))`, results: ['false']},
  {name: 'format-width', code: `R((pcall(string.format, "%1000d", 1)))`, results: ['false']},
  {name: 'pack-huge', code: `R((pcall(string.pack, "i17", 1))) R((pcall(string.rep, "x", 1 << 62)))`, results: ['false', 'false']},
  // XML nesting through the host's parser, which recurses
  {name: 'xml-bomb-parse', code: `R((pcall(mod.xml.parse, ${bomb(3000000)})))`, results: ['false']},
  {name: 'xml-bomb-append', code: `R((pcall(mod.xml.append, ${bomb(3000000)}, "<x/>")))`, results: ['false']},
  {name: 'xml-bomb-append-script', code: `R((pcall(mod.xml.append, "<x/>", ${bomb(3000000)})))`, results: ['false']},
  {name: 'xml-deep-ok', code: `local d = mod.xml.parse(${bomb(20000)}) local n = 0 while d do n = n + 1 d = d:firstElementChild() end R(n)`, results: ['20000']},
  {name: 'xml-deep-document-out', code: `local e = document.root for i = 1, 20000 do local c = mod.xml.element("d") e:append(c) e = c end R("done")`, contains: 'done'},
  // host callbacks and the plain malloc buffers
  {name: 'fs-big-file', fs: true, code: `local s = string.rep("x", 30000000) mod.vfs.pkg:write("/data/big.bin", s) R(#s)`, results: ['30000000']},
  {name: 'fs-host-out-of-memory', fs: true, code: `local s = string.rep("x", 250000000) R((pcall(mod.vfs.pkg.write, mod.vfs.pkg, "/data/huge.bin", s)))`, anyEnd: true},
  {name: 'fs-host-throws', fs: true, throwing: true, code: `R((pcall(mod.vfs.bad.read, mod.vfs.bad, "/oom"))) R((pcall(mod.vfs.bad.read, mod.vfs.bad, "/overflow"))) R((pcall(mod.vfs.bad.read, mod.vfs.bad, "/runtime"))) R(select(2, pcall(mod.vfs.bad.read, mod.vfs.bad, "/overflow")))`, results: ['false', 'false', 'false', 'Maximum call stack size exceeded']},
  {name: 'print-flood', code: `for i = 1, 300000 do print("x") end R("done")`, results: ['done']},
  {name: 'raw-copy-bomb', small: true, code: `local s = string.rep("x", 30000000) local t = {} for i = 1, 100 do t["k" .. i] = s end R((pcall(mod.xml.element, "a", t)))`, results: ['false']},
  {name: 'raw-pretty-bomb', small: true, code: `local s = string.rep("\\1", 20000000) R((pcall(mod.debug.pretty_string, {s, s, s, s, s, s, s, s})))`, results: ['false']},
  {name: 'raw-buffer-bomb', small: true, code: `local s = string.rep("x", 20000000) R((pcall(mod.xml.stringify, s, s, s, s, s, s)))`, results: ['false']},
  {name: 'runs-after-bombs', small: true, code: 'R(#string.rep("ab", 10))', results: ['20']},
  // a state left full by a script's globals: later scripts may fail, the runtime must not take the process down
  {name: 'memory-left-full', small: true, bare: true, code: `load("keep = {} local n = 1 << 26 while n >= 8 do local ok = pcall(function() keep[#keep + 1] = string.rep('x', n) end) if not ok then n = n // 2 end end")()`, anyEnd: true},
  {name: 'memory-still-full', small: true, code: 'R(1)', anyEnd: true},
  {name: 'memory-still-full-again', small: true, code: 'load("keep = nil")() R(2)', anyEnd: true},
  // an error in a finalizer after the script ends that finalizer
  {name: 'gc-error-after-script', small: true, code: `setmetatable({}, {__gc = function() error("x") end}) R("done")`, results: ['done']},
  {name: 'gc-error-recreating', small: true, code: `local function f() setmetatable({}, {__gc = f}) error("x") end f = f setmetatable({}, {__gc = f}) R("done")`, results: ['done']},
  {name: 'gc-then-next-script', small: true, code: 'R("next")', results: ['next']},
];
