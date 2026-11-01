-- The object API as mods use it: constructors, vectors, attributes, per-object tables, Lua-side errors from the engine.
local objs = {}
for name, cls in pairs(H) do
  if type(cls) == 'table' then
    local ok, o = pcall(cls)
    if ok and type(o) == 'userdata' then objs[#objs + 1] = o end
    ok, o = pcall(cls, 1, 2)
    if ok and type(o) == 'userdata' then objs[#objs + 1] = o end
  end
  if #objs > 40 then break end
end
for i, o in ipairs(objs) do
  pcall(function()
    local t = o.table
    if type(t) == 'table' then t.x = i t.self = o end
  end)
  pcall(function() return o.size and o:size(), o[0], o[1], #o, o.first, o.second, o.x, o.y end)
  pcall(function() o.x = i o.y = 'str' o.first = o o.nope = 1 o[1] = 2 end)
  pcall(function() return o + o, o - 1, o == objs[1], o < o, o .. 'x', o(), tostring(o), swig_type(o) end)
  pcall(function() o:__disown() end)
end
local g = H.Global
pcall(function() local i = g.GetInstance() return i:GetShipManager(0), i:GetCApp(), i:GetCApp().gui end)
pcall(function() return H.ships.player, H.ships.enemy, H.ships(0), H.ships(2), H.ships.none end)
pcall(function() H.ships.x = 1 end)
pcall(function() return H.FPS, H.playerVariables, H.metaVariables, H.Settings, H.App, H.CustomEventsParser end)
pcall(function() H.playerVariables.a = 1 return H.playerVariables.a, H.playerVariables['\xff'] end)
pcall(function() Graphics.CSurface.GL_RenderPrimitive(nil) end)
pcall(function() return RapidXML.xml_document('<a/>') end)
script.on_internal_event(E.CREW_LOOP, function(c) pcall(function() return c.table, c.x, c:GetName() end) end)
script.on_internal_event(E.SHIP_LOOP, function(s) pcall(function() return s.table, s.ship.vRoomList[0] end) end)
script.on_internal_event(E.ON_KEY_DOWN, function(k) return C.CONTINUE, k end)
script.on_internal_event(E.SET_BONUS_POWER, function(s, a) return C.CONTINUE, a end)
script.on_internal_event(E.DANGEROUS_ENVIRONMENT, function(d) return not d end)
script.on_render_event(R.MOUSE_CONTROL, function() return C.HALT end, function() return C.PREEMPT end, 5)
script.on_game_event('A', false, function() log('A', 1, nil, true, {}, print) end)
script.on_load(function() print('load') end)
script.on_init(function(n) print(n, math.random(), math.random(3), math.random(1, 2)) math.randomseed(1) end)
pcall(math.random, 1, 0)
pcall(math.random, math.mininteger, math.maxinteger)
pcall(math.randomseed, 0/0)
pcall(log)
pcall(print, setmetatable({}, {__tostring = function() return 1 end}))
tostring = function(x) return 1 end
pcall(print, 1, 2)
tostring = function(x) return {} end
pcall(print, 1, 2)
tostring = nil
pcall(print, 1, 2)
pcall(log)
setmetatable(_G, {__index = function(t, k) error('no global ' .. k) end, __newindex = function(t, k, v) error(k) end})
pcall(print, 1)
