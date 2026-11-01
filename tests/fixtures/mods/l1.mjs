// L1: our own small mods with ftlman Lua patch scripts (no game content) over a synthetic base "ftl.dat": the
// script environment of a real merge (mod.vfs.pkg / mod.vfs.mod, mod.meta.current_path), what pkg sees of earlier
// files and earlier mods, writes through the pkg filesystem, case-insensitive targets, a missing target, globals
// shared by the scripts of one merge, documents with and without an <FTL> root, and an XML append after a script.
// Ground truth: lua-expected.json (`l1`), from `ftlman patch` 0.7.4 (tools/mods/ftlman-lua-cases.mjs).
import {zipSync} from '../../../web/vendor/fflate-0.8.3.mjs';

const enc = new TextEncoder();
const ftl = body => `<?xml version="1.0" encoding="utf-8"?>\n<FTL>\n${body}\n</FTL>\n`;

export function l1Base() {
  const files = {
    'data/blueprints.xml': ftl(`<!-- ships -->
<shipBlueprint name="PLAYER_A" layout="kestral" img="kestral">
\t<name>Alpha</name>
\t<systemList>
\t\t<pilot power="1" room="0" start="true"/>
\t\t<shields power="2" room="1" start="true"><slot><number>1</number></slot></shields>
\t</systemList>
</shipBlueprint>
<shipBlueprint name="ENEMY_A" layout="enemy">
\t<systemList><pilot power="1" room="0"/></systemList>
</shipBlueprint>
<shipBlueprint name="PLAYER_MISSING" layout="nolayout"><name>Missing</name><systemList/></shipBlueprint>
<weaponBlueprint name="W"><type>LASER</type></weaponBlueprint>`),
    'data/kestral.txt': 'X_OFFSET\r\n0\r\nROOM\r\n0\r\n1\r\n1\r\n2\r\n2\r\nROOM\r\n1\r\n3\r\n1\r\n2\r\n2\r\nROOM\r\n2\r\n5\r\n1\r\n1\r\n1\r\nDOOR\r\n3\r\n1\r\n0\r\n1\r\n1\r\n',
    'data/enemy.txt': 'ROOM\r\n0\r\n0\r\n0\r\n1\r\n1\r\n',
    'data/events.xml': `<event name="E1"><text>One</text></event>\n<event name="E2"/>\n`,
    'data/Text_Lua.xml': ftl('<text name="t1">T</text>'),
    'data/notes.txt': 'base notes\r\n',
    'img/base.png': new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0, 1, 255]),
    // silpkg zeroes moved data when the file is shorter than its grown index (see f1.mjs); keep the base large.
    'img/zz_filler.bin': new Uint8Array(1 << 20),
  };
  return new Map(Object.entries(files).map(([k, v]) => [k, typeof v === 'string' ? enc.encode(v) : v]));
}

const metadata = (title, version) => `<?xml version="1.0" encoding="UTF-8"?>\n<metadata>\n<title><![CDATA[ ${title} ]]></title>\n<version><![CDATA[ ${version} ]]></version>\n</metadata>\n`;
const zip = entries => zipSync(Object.fromEntries(entries.map(([name, data, level = 6]) => [name, [typeof data === 'string' ? enc.encode(data) : data, {level}]])), {mtime: new Date(Date.UTC(2026, 0, 1))});

// Shared helpers at the top of each script: rec(k, ...) appends <r k=k>v1;v2;...</r> to a probe element.
const helpers = name => `local probe = mod.xml.element("luaProbe", {script = "${name}"})
document.root:append(probe)
local function str(v) if type(v) == "number" then return math.type(v) .. ":" .. tostring(v) end return type(v) .. ":" .. tostring(v) end
local function rec(k, ...)
  local row = mod.xml.element("r", {k = k})
  local parts = {}
  for i = 1, select("#", ...) do parts[i] = str((select(i, ...))) end
  row:append(table.concat(parts, ";"))
  probe:append(row)
end
local function try(k, f, ...) local ok = pcall(f, ...) rec(k, ok) end
local function ls(fs, path)
  local parts = {}
  for _, e in ipairs(fs:ls(path)) do parts[#parts + 1] = e.filename .. ":" .. e.type end
  return table.concat(parts, ",")
end
local function stat(fs, path)
  local s = fs:stat(path)
  if s == nil then return "nil" end
  return s.type .. ":" .. tostring(s.length)
end
`;

const scriptA = helpers('a/blueprints') + `
local pkg, modfs = mod.vfs.pkg, mod.vfs.mod
rec("path", mod.meta.current_path)
rec("vfs", type(mod.vfs), type(pkg), type(modfs), pkg == mod.vfs.pkg)
rec("stat", stat(pkg, "/data/kestral.txt"), stat(pkg, "/data"), stat(pkg, "/missing"), stat(pkg, "/data/"), stat(pkg, "/"), stat(pkg, "/img/base.png"))
rec("ls-data", ls(pkg, "/data"))
rec("ls-img", ls(pkg, "/img"))
try("ls-root", function() return pkg:ls("/") end)
try("ls-missing", function() return pkg:ls("/missing") end)
try("ls-file", function() return pkg:ls("/data/kestral.txt") end)
try("no-slash", function() return pkg:read("data/kestral.txt") end)
try("read-dir", function() return pkg:read("/data") end)
try("read-missing", function() return pkg:read("/data/nothing.txt") end)
local layout = pkg:read("/data/kestral.txt")
rec("rooms", mod.iter.count(string.gmatch(layout, "ROOM%s+(%d+)")), #layout)
rec("new-layout", pkg:read("/data/new_layout.txt"))
rec("back", pkg:read("/data/back.txt"))
local png = pkg:read("/img/base.png")
rec("binary", #png, png:byte(1), png:byte(7))
rec("own-doc", #pkg:read("/data/blueprints.xml"))
rec("mod-ls", ls(modfs, "/data"))
rec("mod-ls-sub", ls(modfs, "/data/sub"))
rec("mod-stat", stat(modfs, "/data/extra.txt"), stat(modfs, "/data/sub"), stat(modfs, "/nothing"))
rec("mod-read", modfs:read("/data/extra.txt"))
try("mod-read-back", function() return modfs:read("/data/back.txt") end)
try("mod-stat-back", function() return modfs:stat("/data/back.txt") end)
try("mod-write", function() return modfs:write("/data/x.txt", "x") end)
try("mod-read-dir", function() return modfs:read("/data") end)
pkg:write("/data/written.txt", "written by lua\\n")
rec("written", stat(pkg, "/data/written.txt"), pkg:read("/data/written.txt"))
pkg:write("/data/written.xml", "<raw   a = '1'>kept as is</raw>")
pkg:write("/newdir/deeper/file.txt", "z")
rec("newdir", ls(pkg, "/newdir"), ls(pkg, "/newdir/deeper"))
try("write-dir", function() return pkg:write("/data", "x") end)
try("write-under-file", function() return pkg:write("/data/kestral.txt/sub", "x") end)
try("write-no-slash", function() return pkg:write("data/y.txt", "y") end)
pkg:write("/data/blueprints.xml", "overwritten")
rec("overwritten", pkg:read("/data/blueprints.xml"))
pkg:write("/data/notes.txt", "notes from lua")
load("leaked = 'from a'")()
plain = "env only"

local root = document.root
local marker = mod.xml.element("usedFTLman", {})
root:append(marker)
for blueprint in root:children() do
  if blueprint.name == "shipBlueprint" then
    local text = nil
    pcall(function() text = pkg:read("/data/" .. blueprint.attrs.layout .. ".txt") end)
    local isEnemy, systems = true, nil
    for child in blueprint:children() do
      if child.name == "name" then isEnemy = false elseif child.name == "systemList" then systems = child end
    end
    rec("ship", blueprint.attrs.name, text ~= nil, isEnemy)
    if text and not isEnemy then
      local rooms, taken = {}, {}
      for idx, x, y, w, h in string.gmatch(text, "ROOM%s+(%d+)%s+(%d+)%s+(%d+)%s+(%d+)%s+(%d+)") do
        rooms[idx + 1] = {x = tonumber(x), y = tonumber(y), w = tonumber(w), h = tonumber(h)}
      end
      for system in systems:children() do
        for name, value in system:attrs() do if name == "room" then taken[value] = system.name end end
      end
      for r, room in ipairs(rooms) do
        if not taken[r - 1] then
          local sys = mod.xml.element("og_turret", {power = 1, start = "false"})
          sys.attrs.room = r - 1
          sys.attrs.size = room.w * room.h
          local slot = mod.xml.element("slot", {})
          local number = mod.xml.element("number", {})
          number:append(tostring(room.w))
          slot:append(number)
          sys:append(slot)
          systems:append(sys)
        end
      end
    end
  end
end
`;

const scriptText = helpers('a/text_lua') + `
rec("path", mod.meta.current_path)
rec("globals", leaked, plain)
rec("target", document.root:firstElementChild().attrs.name)
rec("notes", mod.vfs.pkg:read("/data/notes.txt"))
document.root:append(mod.xml.element("text", {name = "t2"}))
`;

const scriptMissing = helpers('a/missing') + `rec("never", 1)`;

const scriptB = helpers('b/blueprints') + `
local pkg, modfs = mod.vfs.pkg, mod.vfs.mod
rec("path", mod.meta.current_path)
rec("globals", leaked, plain)
rec("written", pkg:read("/data/written.txt"), pkg:read("/data/written.xml"))
rec("stat", stat(pkg, "/data/blueprints.xml"), stat(pkg, "/data/after.xml"), stat(pkg, "/newdir/deeper/file.txt"))
rec("after", pkg:read("/data/after.xml"))
rec("mod-ls", ls(modfs, "/data"))
local probes = 0
for e in document.root:children() do if e.name == "luaProbe" then probes = probes + 1 end end
rec("probes", probes)
for e in document.root:children() do
  if e.name == "shipBlueprint" and e.attrs.name == "PLAYER_A" then e.attrs.patchedBy = "b" end
end
`;

const scriptEvents = helpers('b/events') + `
rec("root", document.root.name)
for e in document.root:children() do rec("event", e.attrs.name) end
document.root:append(mod.xml.element("event", {name = "E3"}))
`;

export function l1Archives() {
  const a = zip([
    ['mod-appendix/metadata.xml', metadata('L1 Lua A', '1.0')],
    ['data/', new Uint8Array(0), 0],
    ['data/extra.txt', 'mod extra\n'],
    ['data/new_layout.txt', 'ROOM\n0\n0\n0\n1\n1\n'],
    ['data\\back.txt', 'backslash\n'],
    ['data/sub/deep.txt', 'deep'],
    ['data/blueprints.append.lua', scriptA],
    ['data/text_lua.append.lua', scriptText],
    ['data/missing.append.lua', scriptMissing],
    ['data/after.xml', '<after  a="1"/>'],
  ]);
  const b = zip([
    ['mod-appendix/metadata.xml', metadata('L1 Lua B', '2')],
    ['data/blueprints.append.lua', scriptB],
    ['data/blueprints.xml.append', `<mod:findName name="ENEMY_A"><mod:setAttributes appended="yes"/></mod:findName><appended/>`],
    ['data/events.append.lua', scriptEvents],
  ]);
  return [{name: 'l1-lua-a.zip', bytes: a}, {name: 'l1-lua-b.ftl', bytes: b}];
}
