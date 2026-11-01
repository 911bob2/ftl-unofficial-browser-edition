// ftlman Lua patch-script cases (our own content). Each case is [name, base document, script]: the script runs as
// `ftlman append base.xml blueprints.append.lua` would run it (chunk "@base.xml", no mod.vfs, no current_path).
// Results are written into the document by the prelude's helpers, so the expected output is the patched document:
//   R(label, ...)  one <r> row with a <v t="type">tostring(value)</v> per value (t: nil/boolean/integer/float/...)
//   E(label, f)    <e k=label ok="true|false"/> for pcall(f) (the message is not compared)
//   M(label, f)    like E, plus the error message as text (only where ours promises ftlman's wording)
// Expected outputs: lua-expected.json, recorded from the ftlman 0.7.4 release binary (tools/mods/ftlman-lua-cases.mjs).
const ftl = body => `<?xml version="1.0" encoding="utf-8"?>\n<FTL>\n${body}\n</FTL>\n`;
export const PRELUDE = `local __root = document.root
local __out = mod.xml.element("results")
__root:append(__out)
local function __t(v) if type(v) == "number" then return math.type(v) end return type(v) end
function R(label, ...)
  local n = select("#", ...)
  local row = mod.xml.element("r", {k = label})
  for i = 1, n do
    local v = select(i, ...)
    local cell = mod.xml.element("v", {t = __t(v)})
    if type(v) ~= "userdata" or true then cell:append(tostring(v)) end
    row:append(cell)
  end
  __out:append(row)
end
function E(label, f, ...)
  local ok = pcall(f, ...)
  __out:append(mod.xml.element("e", {k = label, ok = tostring(ok)}))
  return ok
end
function M(label, f, ...)
  local ok, err = pcall(f, ...)
  local row = mod.xml.element("e", {k = label, ok = tostring(ok)})
  if not ok then row:append(tostring(err)) end
  __out:append(row)
end
function names(it, fmt)
  local parts = {}
  for node in it do
    if node.type == "element" then parts[#parts + 1] = "<" .. node.name .. ">" else parts[#parts + 1] = "'" .. node.content .. "'" end
  end
  return table.concat(parts, ",")
end
`;
const sorted = `local function sortedKeys(t) local k = {} for key in pairs(t) do k[#k + 1] = tostring(key) end table.sort(k) return table.concat(k, " ") end\n`;

export const cases = [
  ['globals', ftl('<a/>'), `
for _, n in ipairs({"io","os","require","load","loadstring","dofile","loadfile","collectgarbage","rawset","rawget","rawequal","rawlen",
  "utf8","coroutine","debug","bit32","unpack","next","pairs","ipairs","select","setmetatable","getmetatable","pcall","xpcall",
  "error","assert","tostring","tonumber","type","print","_G","package","string","table","math","mod","document","arg"}) do
  R(n, type(_ENV[n]))
end
R("_G is _ENV", _G == _ENV)
R("string.dump", type(string.dump))
R("math.pow", type(math.pow), type(math.ult), type(math.tointeger))
R("table.unpack", type(table.unpack), type(table.move), type(table.pack))
R("string.pack", type(string.pack), type(string.unpack))
`],
  ['library-keys', ftl('<a/>'), sorted + `
R("mod", sortedKeys(mod))
R("mod.xml", sortedKeys(mod.xml))
R("mod.iter", sortedKeys(mod.iter))
R("mod.table", sortedKeys(mod.table))
R("mod.util", sortedKeys(mod.util))
R("mod.debug", sortedKeys(mod.debug))
R("mod.meta", sortedKeys(mod.meta))
R("mod.vfs", type(mod.vfs))
R("current_path", mod.meta.current_path)
`],
  ['protected-tables', ftl('<a/>'), `
E("string.new", function() string.newkey = 1 end)
E("string.sub overwrite", function() local o = string.sub; string.sub = o end)
R("mt(string)", getmetatable(string))
R("mt(table)", getmetatable(table))
R("mt(math)", getmetatable(math))
R("mt(mod)", getmetatable(mod))
R("mt(mod.xml)", getmetatable(mod.xml))
E("setmetatable(string)", function() setmetatable(string, {}) end)
R("mt(_ENV)", getmetatable(_ENV))
E("setmetatable(_ENV)", function() setmetatable(_ENV, {}) end)
R("strmt", type(getmetatable("")))
E("mod.new", function() mod.newkey = 1 end)
E("mod.xml.new", function() mod.xml.newkey = 1 end)
E("mod.iter.new", function() mod.iter.newkey = 1 end)
E("mod.meta.new", function() mod.meta.newkey = 1 end)
R("missing", mod.nothing, mod.xml.nothing, string.nothing)
x_global = 5
R("global", x_global, rawget(_ENV, "x_global"))
`],
  ['element-create', ftl('<a/>'), `
local function show(label, f)
  local ok, e = pcall(f)
  if ok then R(label, mod.xml.stringify(e), e.prefix) else R(label, "error") end
end
show("name", function() return mod.xml.element("abc") end)
show("name-attrs", function() return mod.xml.element("abc", {z = "1", a = "2"}) end)
show("prefix", function() return mod.xml.element("p", "abc") end)
show("prefix-attrs", function() return mod.xml.element("p", "abc", {k = "v"}) end)
show("nil-prefix", function() return mod.xml.element(nil, "abc", {k = "v"}) end)
show("number-attr", function() return mod.xml.element("abc", {i = 1, f = 1.5, g = 2.0, h = -0.0}) end)
show("bool-attr", function() return mod.xml.element("abc", {b = true}) end)
show("number-name", function() return mod.xml.element(12) end)
show("number-name2", function() return mod.xml.element("a", 12) end)
show("int-key", function() return mod.xml.element("abc", {"x"}) end)
show("empty-name", function() return mod.xml.element("") end)
show("dash", function() return mod.xml.element("-a_b-9") end)
show("bad-start", function() return mod.xml.element("1a") end)
show("bad-char", function() return mod.xml.element("a.b") end)
show("bad-colon", function() return mod.xml.element("a:b") end)
show("bad-unicode", function() return mod.xml.element("aé") end)
show("bad-attr-name", function() return mod.xml.element("a", {["b c"] = "1"}) end)
show("no-args", function() return mod.xml.element() end)
show("table-name", function() return mod.xml.element({}) end)
show("extra-args", function() return mod.xml.element("p", "n", {k = "v"}, "extra", 5) end)
show("attrs-not-table", function() return mod.xml.element("a", "b", "c") end)
M("msg-start", function() return mod.xml.element("1a") end)
M("msg-char", function() return mod.xml.element("a.b") end)
M("msg-space", function() return mod.xml.element("a b") end)
M("msg-quote", function() return mod.xml.element("a'b") end)
M("msg-newline", function() return mod.xml.element("a\\nb") end)
`],
  ['attr-typed', ftl('<a/>'), `
local values = {"1", "-0", "+5", "-12", "007", "1.0", "4.0", "1.5", ".5", "5.", "1e5", "1E5", "1e+5", "1e-5", "-1.25e-3", "inf", "-inf", "+inf",
  "infinity", "Infinity", "INF", "nan", "NaN", "-nan", "0x10", " 1", "1 ", "", "1_000", "true", "false", "True", "TRUE", "1e400", "-1e400", "1e-400",
  "9223372036854775807", "9223372036854775808", "-9223372036854775808", "-9223372036854775809", "123456789012345678901234567890",
  "0.1", "1.", ".", "e5", "1e", "1e+", "--1", "+-1", "1.2.3", "١", "0.30000000000000004", "2.5e-324", "1.7976931348623157e308", "abc"}
local e = mod.xml.element("x")
for _, s in ipairs(values) do
  e.rawattrs.v = s
  local v = e.attrs.v
  R("[" .. s .. "]", v)
end
R("missing", e.attrs.nothing, e.rawattrs.nothing)
R("number-key", e.attrs[1])
`],
  ['attr-format', ftl('<a/>'), `
local values = {0.1, 1/3, 2/3, 1e15, 1e16, 1e17, 1e21, 1e22, 1e23, 123456789012345680, 1e-5, 1e-4, 1e-6, 1e-7, 0.001234, 100.0, 1.0, -1.0,
  -0.0, 0.0, 1/0, -1/0, 2^53, 2^53 + 1, 2^63, 2^64, 5e-324, 1.7976931348623157e308, 9.999999999999999e22, 0.3, 2.5, 123e-20, 1.5e300,
  2^-1022, 2^-1023, 2^-1074, 2^60, 2^100, 2^-100, 2^1023, 2^-10, 8.0, 0.5, 1234567.0, 12345678901234567.0, 1234567890123456.0,
  3.0e-310, 4.9406564584124654e-324, 2.2250738585072014e-308, 2.2250738585072009e-308, 6.02214076e23, 1.602176634e-19,
  0.000001, 0.0000012345, 99999999999999990000.0, 1e300 * 10, 3.14159265358979, 2^24, 2^-24, 16777217.0, 1e9, 1e10, 4.35, 0.07, 8.41}
local e = mod.xml.element("x")
for i, v in ipairs(values) do
  e.attrs.v = v
  R(tostring(i), e.rawattrs.v)
end
e.attrs.n = 0/0
R("nan", e.rawattrs.n)
for _, v in ipairs({0, 1, -1, 42, math.maxinteger, math.mininteger, 1 << 40}) do e.attrs.v = v; R("int", e.rawattrs.v) end
e.attrs.b = true; R("true", e.rawattrs.b)
e.attrs.b = false; R("false", e.rawattrs.b)
e.attrs.s = "1"; R("str", e.rawattrs.s, e.attrs.s)
e.attrs.s = nil; R("removed", e.rawattrs.s)
E("table", function() e.attrs.t = {} end)
E("func", function() e.attrs.t = print end)
E("raw-number", function() e.rawattrs.t = 5 end)
E("raw-bool", function() e.rawattrs.t = true end)
E("bad-name", function() e.attrs["1x"] = "a" end)
E("number-name", function() e.attrs[1] = "a" end)
e.rawattrs.r = "x"; R("raw-str", e.attrs.r)
M("msg-table", function() e.attrs.t = {} end)
M("msg-raw", function() e.rawattrs.t = 5 end)
M("msg-raw-float", function() e.rawattrs.t = 5.5 end)
M("msg-raw-bool", function() e.rawattrs.t = false end)
R("doc", mod.xml.stringify(e))
`],
  ['attr-iterate', ftl('<a z="26" b="2" a="true" m="x" c="1.5"/>'), `
local a = document.root:firstElementChild()
local parts = {}
for k, v in a:attrs() do parts[#parts + 1] = k .. "=" .. tostring(v) .. ":" .. (math.type(v) or type(v)) end
R("typed", table.concat(parts, " "))
parts = {}
for k, v in a:rawattrs() do parts[#parts + 1] = k .. "=" .. v .. ":" .. type(v) end
R("raw", table.concat(parts, " "))
parts = {}
for k, v in a:attrs() do
  parts[#parts + 1] = k
  if k == "b" then a.attrs.bb = "new"; a.attrs.aa = "skipped"; a.attrs.c = nil end
end
R("mutating", table.concat(parts, " "))
E("attrs()", function() return a.attrs() end)
E("attrs(other)", function() return a.attrs(mod.xml.element("q")) end)
E("rawattrs()", function() return a.rawattrs() end)
local empty = mod.xml.element("q")
local n = 0
for k in empty:attrs() do n = n + 1 end
R("empty", n)
local it = a:attrs()
R("type", type(it))
local k1 = it()
R("first", k1)
`],
  ['dom-basic', ftl('<a x="1"><b/>text<!-- c --><c>inner</c><![CDATA[cd]]><d/></a>'), `
local a = document.root:firstElementChild()
R("name", a.name, a.prefix, a.type)
R("children", names(a:children()))
R("childNodes", names(a:childNodes()))
R("firstElementChild", a:firstElementChild().name)
R("lastElementChild", a:lastElementChild().name)
local fc = a:firstChild()
R("firstChild", fc.type, fc.name or fc.content)
R("lastChild", pcall(function() return a.lastChild end))
R("textContent", a.textContent)
R("root", document.root.name, document.root.parent)
R("parent", a:firstElementChild().parent.name)
local b = a:firstElementChild()
R("b.next", b.nextSibling.type, b.nextSibling.content)
local t = b.nextSibling
R("t.next", t.nextSibling)
R("t.prev", t.previousSibling.name)
local c = a:children(); c(); local cc = c()
R("c.prev", cc.previousSibling)
R("c.next", cc.nextSibling)
R("d.prev", a:lastElementChild().previousSibling)
R("tostring", tostring(a), tostring(b), tostring(t), tostring(cc))
R("tostring-attrs", tostring(mod.xml.element("q", {b = "x\\"y", a = "1"})))
R("as", a:as("element").name, a:as("text"), t:as("text").content, t:as("element"))
E("as-bad", function() return a:as("comment") end)
R("doc.root-eq", document.root == document.root, rawequal(document.root, document.root))
R("unknown-field", pcall(function() return a.nothing end))
E("set-unknown", function() a.nothing = 1 end)
E("set-type", function() a.type = "x" end)
R("text-fields", t.type, t.content, pcall(function() return t.name end))
`],
  ['dom-firstchild', ftl('<a><b/><c/>tail</a><e>only</e><f/><g><h/></g>'), `
local r = document.root
local a = r:firstElementChild()
local x = a:firstChild()
R("a.firstChild", x.type, x.name or x.content)
local e = a.nextSibling
R("e", e.name)
R("e.firstChild", e:firstChild().content)
R("f.firstChild", e.nextSibling:firstChild())
local g = e.nextSibling.nextSibling
R("g.firstChild", g:firstChild().name)
R("g.lastElementChild", g:lastElementChild().name)
R("g.firstElementChild", g:firstElementChild().name)
`],
  ['dom-insert', ftl('<a/>'), `
local root = mod.xml.element("root")
local first = mod.xml.element("first")
local second = mod.xml.element("second", {a2 = "10", pi = "3.14"})
local fourth = mod.xml.element("fourth", {active = "false"})
root:prepend(first, second)
root:append("third", fourth)
R("1", names(root:childNodes()))
local hi = mod.xml.element("hi")
local guys = mod.xml.element("guys")
second:after("greeting:", hi)
fourth.previousSibling:before(guys, "!")
R("2", names(root:childNodes()))
root:prepend("p1", 42, 1.5)
root:append(mod.xml.element("z"), 7)
R("3", names(root:childNodes()))
R("lastElementChild", root:lastElementChild().name, root:firstElementChild().name)
E("append-self", function() root:append(root) end)
E("append-parented", function() root:append(first) end)
local inner = mod.xml.element("inner")
first:append(inner)
E("append-ancestor", function() inner:append(root) end)
E("append-bool", function() root:append(true) end)
E("append-table", function() root:append({}) end)
E("before-orphan", function() mod.xml.element("o"):before("x") end)
E("after-orphan", function() mod.xml.element("o"):after("x") end)
local partial = mod.xml.element("partial")
E("append-partial", function() partial:append("a", mod.xml.element("b"), first, "c") end)
R("partial", names(partial:childNodes()))
local partial2 = mod.xml.element("partial2")
E("append-badtype", function() partial2:append("a", {}, "c") end)
R("partial2", names(partial2:childNodes()))
R("doc", mod.xml.stringify(root))
M("msg-self", function() root:append(root) end)
M("msg-parented", function() root:append(first) end)
M("msg-ancestor", function() inner:append(root) end)
M("msg-orphan", function() mod.xml.element("o"):before("x") end)
M("msg-orphan-after", function() mod.xml.element("o"):after("x") end)
M("msg-before-self", function() first:before(first) end)
M("msg-prepend-self", function() first:prepend(first) end)
`],
  ['dom-detach', ftl('<a><b/><c/><d/></a><s/>'), `
local a = document.root:firstElementChild()
local b, c, d
for e in a:children() do if e.name == "b" then b = e elseif e.name == "c" then c = e else d = e end end
c:detach()
R("after-detach", names(a:childNodes()), c.parent, c.previousSibling and c.previousSibling.name, c.nextSibling and c.nextSibling.name)
local p = mod.xml.element("p")
p:append(c)
R("p", names(p:childNodes()), mod.xml.stringify(p))
b:detach()
R("first-detached", names(a:childNodes()), a:firstElementChild().name, a:lastElementChild().name)
d:detach()
R("all-detached", names(a:childNodes()), a:firstElementChild(), a:lastElementChild())
a:append("x", mod.xml.element("y"))
R("reused", names(a:childNodes()))
local s
for e in document.root:children() do if e.name == "s" then s = e end end
s:detach()
s:detach()
R("twice", s.parent)
local only = mod.xml.element("only")
local child = mod.xml.element("child")
only:append(child)
child:detach()
R("only", names(only:childNodes()), only:firstElementChild())
only:append(mod.xml.element("n1"), mod.xml.element("n2"))
R("only-reused", names(only:childNodes()))
`],
  ['dom-clone', ftl('<a k="v">t<b x="1"><c/></b><!-- note --></a>'), `
local a = document.root:firstElementChild()
local deep = a:clone()
local deep2 = a:clone("deep")
local shallow = a:clone("shallow")
R("deep", mod.xml.stringify(deep), deep.parent)
R("deep2", mod.xml.stringify(deep2))
R("shallow", mod.xml.stringify(shallow))
local t = a:firstChild()
local tc = t:clone()
R("text", tc.type, tc.content, tc.parent)
E("bad-mode", function() return a:clone("wide") end)
E("number-mode", function() return a:clone(1) end)
deep.attrs.k = "changed"
deep:firstElementChild().attrs.x = "2"
R("independent", mod.xml.stringify(a))
document.root:append(deep, shallow)
`],
  ['dom-text', ftl('<a> x <b>y<c>z</c></b><![CDATA[q]]>w</a>'), `
local a = document.root:firstElementChild()
R("textContent", a.textContent)
a:firstElementChild().textContent = "new <&> text"
R("after", a.textContent, mod.xml.stringify(a))
a.textContent = 12
R("number", a.textContent, names(a:childNodes()))
local t = a:firstChild()
t.content = "changed & <more>"
R("content", t.content, mod.xml.stringify(a))
t.content = 3.5
R("content-num", t.content)
E("content-table", function() t.content = {} end)
a.name = "renamed"
a.prefix = "pfx"
R("renamed", mod.xml.stringify(a), a.prefix)
a.prefix = nil
R("unprefixed", mod.xml.stringify(a), a.prefix)
E("bad-name", function() a.name = "1x" end)
E("bad-prefix", function() a.prefix = "a b" end)
E("name-number", function() a.name = 5 end)
local empty = mod.xml.element("e")
R("empty-text", empty.textContent)
empty.textContent = ""
R("empty-set", mod.xml.stringify(empty), names(empty:childNodes()))
`],
  ['dom-iter-live', ftl('<a><b/><c/></a>'), `
local a = document.root:firstElementChild()
local seen = {}
for e in a:children() do
  seen[#seen + 1] = e.name
  if e.name == "b" then a:append(mod.xml.element("added")) end
end
R("live", table.concat(seen, ","))
local it = a:childNodes()
R("call", it().name, it().name, it().name, it())
R("after-end", it())
local n = 0
for e in mod.xml.element("z"):children() do n = n + 1 end
R("empty", n)
`],
  ['document-output', `<?xml version="1.0" encoding="utf-8"?>\n<FTL>\n<!-- head &amp; > comment -->\n<a k="1 &amp; 2" j='q"t'>R&amp;D &lt;1&gt; &#65;&#x42; &apos;&quot;</a>\n<![CDATA[raw <cdata> & stuff]]>\n<b>\n  <c/>\n</b>\n</FTL>\n`, `
document.root:append(mod.xml.element("new", {k = "a\\"b<c>&d"}))
document.root:append("tail & <text>")
`],
  ['document-no-ftl-root', `<a/>\n<b>text</b>\n`, `
R("root", document.root.name)
document.root:append(mod.xml.element("added"))
`],
  ['document-empty', ``, `
R("root", document.root.name, names(document.root:childNodes()))
`],
  ['document-only-ftl', `<FTL></FTL>`, `
R("x", 1)
`],
  ['document-ftl-empty', `<FTL></FTL>`, ``],
  ['document-unclosed', ftl('<a><b></a>'), `R("x", 1)`],
  ['document-ftl-midway', `<a/><FTL><b/></FTL><c/>`, `R("names", names(document.root:children()))`],
  ['document-doctype-pi', ftl('<?pi some data?><!DOCTYPE thing [<!ELEMENT a ANY>]><a k="1" k="2" j="3"/><!--c--><b/>'), `
R("names", names(document.root:childNodes()))
`],
  ['document-replace-root-children', ftl('<a/><b/>'), `
for e in document.root:children() do e:detach() end
R("left", names(document.root:childNodes()))
`],
  ['parse', ftl('<a/>'), `
local function show(label, ...)
  local n = select("#", ...)
  local parts = {}
  for i = 1, n do local v = select(i, ...); parts[i] = v.type .. ":" .. mod.xml.stringify(v) end
  R(label, n, table.concat(parts, " | "))
end
show("one", mod.xml.parse("<x a='1'>y</x>"))
show("many", mod.xml.parse("<x/><y/> <z>t</z>"))
show("comment", mod.xml.parse("<!-- c --><x/><![CDATA[d]]>"))
show("wrapped", mod.xml.parse('<?xml version="1.0"?>\\n<FTL><x/></FTL>'))
show("ftl-inner", mod.xml.parse('<a><FTL>t</FTL></a>'))
show("toplevel", mod.xml.parse("text <x/> more", {allow_top_level_text = true}))
E("toplevel-forbidden", function() return mod.xml.parse("text <x/>") end)
E("bad-option", function() return mod.xml.parse("<x/>", {nope = true}) end)
E("bad-option-value", function() return mod.xml.parse("<x/>", {allow_top_level_text = 1}) end)
E("unclosed", function() return mod.xml.parse("<x>") end)
E("mismatched", function() return mod.xml.parse("<x></y>") end)
E("bad-xml", function() return mod.xml.parse("<x a=1/>") end)
E("bad-utf8", function() return mod.xml.parse("<x>\\xff</x>") end)
show("empty", mod.xml.parse(""))
show("number", mod.xml.parse(12, {allow_top_level_text = true}))
show("entities", mod.xml.parse("<x a='&lt;&amp;'>&gt;&#33;</x>"))
E("unmatched-end", function() return mod.xml.parse("<x/></y>") end)
local p = mod.xml.parse("<x/>")
document.root:append(p)
R("parent", p.parent.name)
M("msg-option", function() return mod.xml.parse("<x/>", {nope = true}) end)
M("msg-option-value", function() return mod.xml.parse("<x/>", {allow_top_level_text = 1}) end)
`],
  ['stringify', ftl('<a/>'), `
local e = mod.xml.element("x", {b = "2", a = "1&<>\\"'"})
e:append("t<&>", mod.xml.element("y"), "u")
R("element", mod.xml.stringify(e))
R("many", mod.xml.stringify(e, "text", 5, 1.5, mod.xml.element("z")))
R("none", mod.xml.stringify())
R("text", mod.xml.stringify(e:firstChild()))
R("prefixed", mod.xml.stringify(mod.xml.element("p", "q", {r = "s"})))
R("empty-name", mod.xml.stringify(mod.xml.element("")))
E("table", function() return mod.xml.stringify({}) end)
E("bool", function() return mod.xml.stringify(true) end)
local root = document.root
R("root", #mod.xml.stringify(root) > 0)
`],
  ['xml-append', ftl('<a/>'), `
local doc = '<?xml version="1.0" encoding="utf-8"?>\\n<FTL>\\n<e name="x"/>\\n</FTL>\\n'
R("append", mod.xml.append(doc, '<mod:findName name="x"><mod:setAttributes v="1"/></mod:findName>'))
R("append-typed", mod.xml.append(doc, '<new/>', {type = "xml.append"}))
R("rawappend", mod.xml.append(doc, '<raw/>', {type = "xml.rawappend"}))
R("no-root", mod.xml.append('<e/>', '<f/>'))
E("bad-type", function() return mod.xml.append(doc, '<x/>', {type = "lua"}) end)
E("bad-option", function() return mod.xml.append(doc, '<x/>', {kind = "x"}) end)
E("bad-script", function() return mod.xml.append(doc, '<mod:findName name="x" panic="true"><mod:bogus/></mod:findName>') end)
E("panic", function() return mod.xml.append(doc, '<mod:findName name="none" panic="true"/>') end)
E("bad-doc", function() return mod.xml.append('<a', '<x/>') end)
M("msg-type", function() return mod.xml.append(doc, '<x/>', {type = "lua"}) end)
`],
  ['iter-table-util', ftl('<a/>'), `
local a = {"a", "b", "c"}
local b = {"b", "d", 7.5, "e"}
local it = mod.iter.enumerate(mod.iter.zip(mod.table.iter_array(a), mod.table.iter_array(b)), 3)
R("e1", it())
R("e2", it())
R("e3", it())
R("e4", it())
R("count", mod.iter.count(mod.table.iter_array(b)), mod.iter.count(string.gmatch("a1 b2 c3", "%a%d")))
R("collect", table.concat(mod.iter.collect(mod.table.iter_array(a)), ","))
R("map", table.concat(mod.iter.collect(mod.iter.map(mod.table.iter_array(a), string.upper)), ","))
R("enumerate-default", mod.iter.enumerate(mod.table.iter_array(a))())
local packed = mod.iter._collectpack(mod.iter.zip(mod.table.iter_array(a), mod.table.iter_array(b)))
R("collectpack", #packed, packed[1][1], packed[1][2])
R("compare", mod.table.compare_arrays({1, 2, 3}, {1, 2, 3}), mod.table.compare_arrays({1, 2}, {1, 3}), mod.table.compare_arrays({1, 2, 3}, {1, 2}), mod.table.compare_arrays({1}, {1, 2}), mod.table.compare_arrays({5}, {1, 2}))
local ro = mod.util.readonly({x = 1})
R("readonly", ro.x)
E("readonly-set", function() ro.y = 2 end)
M("readonly-msg", function() ro.y = 2 end)
E("assert-equal", function() mod.debug.assert_equal({1, {2, "x"}, k = true}, {1, {2, "x"}, k = true}) end)
E("assert-int-float", function() mod.debug.assert_equal(1, 1.0) end)
E("assert-type", function() mod.debug.assert_equal(1, "1") end)
E("assert-table-size", function() mod.debug.assert_equal({1, 2}, {1}) end)
E("assert-table-size2", function() mod.debug.assert_equal({1}, {1, 2}) end)
E("assert-func", function() mod.debug.assert_equal(print, print) end)
E("assert-nan", function() mod.debug.assert_equal(0/0, 0/0) end)
M("assert-msg", function() mod.debug.assert_equal(1, 2) end)
M("assert-type-msg", function() mod.debug.assert_equal(1, "x") end)
E("throws", function() mod.debug._assert_throws(function() error("x") end) end)
E("throws-not", function() mod.debug._assert_throws(function() end) end)
R("compare-fn", mod.debug._compare({a = 1}, {a = 1}), mod.debug._compare({a = 1}, {a = 2}), mod.debug._compare(1, 1.0), mod.debug._compare("a", "a"))
`],
  ['pretty', ftl('<a/>'), `
local P = mod.debug.pretty_string
R("nil", P(nil)); R("true", P(true)); R("int", P(42), P(-7), P(math.mininteger))
R("float", P(1.5), P(4.0), P(-0.0), P(1e21), P(1e-7), P(0.1), P(1/3), P(1/0), P(-1/0), P(1e300), P(123456789.125), P(2^63))
R("string", P("plain"), P('q"uote'), P("back\\\\slash"), P("nl\\ntab\\tcr\\r"), P("nul\\0x"), P("ctl\\1\\27\\127"), P("é ü"), P("e\\204\\129"), P("soft\\194\\173"), P("zw\\226\\128\\139"), P("apos'"))
R("table", P({}), P({1, 2, 3}), P({x = 1}), P({{}, {1}}))
R("indent", P({1, {2, 3}}, {indent = "  "}), P({}, {indent = "\\t"}))
R("element", P(mod.xml.element("a", {k = "v"})), P({mod.xml.element("b")}))
E("bad-option", function() return P(1, {nope = 1}) end)
E("bad-colors", function() return P(1, {colors = "rgb"}) end)
R("ansi", P({1, "s", true}, {colors = "ansi"}))
R("recursive-false-scalar", P("s", {recursive = false}))
local shared = {9}
R("seen-twice", (P({shared, shared}):gsub("0x%x+", "PTR")))
local cyc = {}; cyc[1] = cyc
R("cyclic", (P(cyc):gsub("0x%x+", "PTR")))
R("func", (P(print):gsub("0x%x+", "PTR")))
local proxied = setmetatable({}, {__index = {inherited = 1}})
R("index-table", P(proxied))
mod.debug.pretty_print({1})
R("pp-return", mod.debug.pretty_print("x"))
`],
  ['eval', ftl('<a/>'), `
R("arith", mod.util.eval("hello * world", {env = {hello = 29, world = 32}}))
R("multi", mod.util.eval("return 1, 'two', nil, 3", {env = {}}))
R("fun", mod.util.eval("function fun(h) return h .. world end return fun(fun('a'))", {env = {world = " b"}}))
a = 3; b = 4
R("env", mod.util.eval("a * b", {env = _ENV}))
R("assign", mod.util.eval("abc = 12", {env = _ENV}), abc)
local env1 = {}
mod.util.eval("abc = 13", {env = env1})
R("env1", env1.abc, abc)
E("missing-env", function() return mod.util.eval("1") end)
E("bad-option", function() return mod.util.eval("1", {env = {}, other = 1}) end)
E("syntax", function() return mod.util.eval("1 +", {env = {}}) end)
M("error-location", function() return mod.util.eval("error('boom')", {env = {error = error}}) end)
M("named", function() return mod.util.eval("error('boom')", {env = {error = error}, name = "mine"}) end)
E("long-name", function() return mod.util.eval("1", {env = {}, name = string.rep("n", 200)}) end)
R("path", mod.util.eval("return mod.meta.current_path", {env = {mod = mod}, path = "/x/y.lua"}), mod.meta.current_path)
R("statement", mod.util.eval("local x = 1", {env = {}}))
M("missing-env-msg", function() return mod.util.eval("1") end)
`],
  ['errors-caught', ftl('<a/>'), `
M("runtime", function() local x = nil; return x.y end)
M("error-string", function() error("plain") end)
M("error-level0", function() error("plain", 0) end)
E("error-table", function() error({}) end)
E("arith", function() return {} + 1 end)
M("concat", function() return "a" .. {} end)
R("tostring-nil", tostring(nil))
R("select", select("#", 1, nil, nil))
R("integer-div", 7 // 2, 7.0 // 2, 7 % -3, -7 % 3, 2^2, 7 / 2)
R("bitwise", 6 & 3, 6 | 3, 6 ~ 3, ~0, 1 << 62, 256 >> 4)
R("tointeger", math.tointeger(3.0), math.tointeger(3.5), math.tointeger("8"))
R("tonumber", tonumber("10"), tonumber("10.0"), tonumber("0x1F"), tonumber("z", 36), tonumber("  5  "), tonumber("5x"))
R("string-format", string.format("%d %5.2f %s %q", 3, 1.5, "x", "a\\nb"))
R("floor", math.floor(3.7), math.ceil(3.2), math.max(1, 2.5), math.min(3, 2))
R("tostring-float", tostring(1e15), tostring(1e16), tostring(2^63), tostring(-0.0), tostring(1/0), tostring(3.0))
R("goto", (function() local i = 0 ::top:: i = i + 1 if i < 3 then goto top end return i end)())
R("gsub", string.gsub("hello world", "o", "0"))
R("rep", string.rep("ab", 3, ","))
R("sort", (function() local t = {5, 2, 8, 1} table.sort(t) return table.concat(t, " ") end)())
R("strarith-int", "10" * 1 == 10)
R("load-text", load("return 1 + 1")())
R("load-env", load("return x", "chunk", "t", {x = 5})())
R("xpcall", xpcall(function() error("e") end, function(m) return "handled" end))
`],
  ['error-runtime', ftl('<a/>'), `
document.root:append(mod.xml.element("before"))
local t = nil
t.x = 1
`],
  ['error-syntax', ftl('<a/>'), `local x = = 1`],
  ['error-explicit', ftl('<a/>'), `error("stop here")`],
  ['error-api', ftl('<a/>'), `document.root:append({})`],
  ['return-value', ftl('<a/>'), `return 1, 2`],
  ['empty-script', ftl('<a k="v">x</a>'), ``],
  ['shebang', ftl('<a/>'), `#!/usr/bin/lua\nR("x", 1)`],
  ['string-arith', ftl('<a/>'), `
R("arith", "10" + 1, "10" * "2", "3.0" + 1, "0x10" + 0, -"2", "7" // 2, "7" % 3, "2" ^ 2, "7" / 2, 10 - "4", " 5 " + 0, "1e2" + 0)
R("mixed", "10" + 1.5, 2^53 .. "", 1 .. "", "9007199254740993" + 0, "-0" + 0, "1" + "1")
M("bad-left", function() return "abc" + 1 end)
M("bad-right", function() return 1 + "abc" end)
M("bad-both", function() return "x" * "y" end)
M("bad-table-left", function() return {} + "1" end)
M("bad-table-right", function() return "1" + {} end)
M("bad-unm", function() return -"x" end)
M("idiv-zero", function() return "1" // 0 end)
M("mod-zero", function() return "1" % "0" end)
R("table-mt", "1" + setmetatable({}, {__add = function(a, b) return "mt" end}), setmetatable({}, {__sub = function() return "mt2" end}) - "2")
E("bitwise-string", function() return "3" & 1 end)
E("compare", function() return "10" < 10 end)
E("for-string", function() local n = 0 for i = "1", 2 do n = n + 1 end return n end)
R("for-limit", (function() local n = 0 for i = 1, "3" do n = n + i end return n end)())
R("mt", type(getmetatable("").__add), type(getmetatable("").__unm), type(getmetatable("").__band), type(getmetatable("").__concat))
local w, h = string.match("ROOM 0 2 3", "ROOM %d+ (%d+) (%d+)")
local e = mod.xml.element("room", {size = w * h, half = w / 2, idx = w + 1, neg = -h})
document.root:append(e)
`],
  ['blueprints-like', ftl(`<shipBlueprint name="S1" layout="s1" img="s1"><name>Ship</name><systemList><pilot power="1" room="0" start="true"/><shields power="2" room="1" start="true"><slot><number>2</number></slot></shields></systemList></shipBlueprint>\n<shipBlueprint name="E1" layout="e1"><systemList><pilot power="1" room="0"/></systemList></shipBlueprint>\n<weaponBlueprint name="W"/>`), `
local root = document.root
local marker = mod.xml.element("usedFTLman", {})
root:append(marker)
for blueprint in root:children() do
  if blueprint.name == "shipBlueprint" then
    local isEnemy = true
    local systems = nil
    for child in blueprint:children() do
      if child.name == "name" then isEnemy = false elseif child.name == "systemList" then systems = child end
    end
    if not isEnemy then
      local taken = {}
      for system in systems:children() do
        local room = nil
        for name, value in system:attrs() do if name == "room" then room = value end end
        taken[room] = system.name
        for slot in system:children() do
          for node in slot:children() do if node.name == "number" then R("slot", node.textContent) end end
        end
      end
      local roomCount = 3
      for r = 0, roomCount - 1 do
        if not taken[r] then
          local sys = mod.xml.element("og_turret", {power = 1, start = "false", turret = "T1"})
          sys.attrs.room = r
          local slot = mod.xml.element("slot", {})
          local direction = mod.xml.element("direction", {})
          local number = mod.xml.element("number", {})
          direction:append("right")
          number:append(tostring(r + 1))
          slot:append(direction)
          slot:append(number)
          sys:append(slot)
          systems:append(sys)
          break
        end
      end
    end
  end
end
R("masks", tonumber("1010", 2) & tonumber("0110", 2), ("2" * "3") == 6)
`],
  ['cycle-hangs', ftl('<a><b/><c/></a>'), `
local a = document.root:firstElementChild()
local b = a:firstElementChild()
b:detach()
a:append(b)
`],
];
