-- Weak tables, resurrecting finalizers and the handle cache.
weak = setmetatable({}, {__mode = 'v'})
keys = setmetatable({}, {__mode = 'k'})
saved = {}
local function new(i)
  local o = U()
  weak[i] = o
  if o then keys[o] = {o} end
  setmetatable({o}, {__gc = function(t) saved[#saved + 1] = t[1] end})
  return o
end
for i = 1, 200 do new(i) local x = {} end
for i = 1, 40 do
  setmetatable({}, {__gc = function()
    local o = U()
    saved[#saved + 1] = o
    if o then
      local mt = getmetatable(o)
      if mt then pcall(mt.__gc, o) pcall(mt.__gc, o) pcall(mt.__gc, 1) pcall(mt.__gc) end
      pcall(function() return o.table end)
      pcall(tostring, o)
    end
  end})
end
local s = ''
for i = 1, 2000 do s = s .. i end
script.on_internal_event(E.ON_TICK, function()
  for i = 1, #saved do
    local o = saved[i]
    pcall(tostring, o)
    pcall(function() return o.x, o.table, swig_type(o), swig_equals(o, saved[1]) end)
    pcall(function() o.x = 1 end)
  end
  for i = 1, 100 do weak[i] = U() end
end)
script.on_load(function() saved = {} for i = 1, 500 do local t = {U()} end end)
--@
-- Finalizers that use the engine, due while another engine answer is being turned into a Lua value or error.
local o, q = U(), U(H.Point)
local function read() return o.x, o.y, q.x, q.y, H.FPS, H.playerVariables end
for i = 1, 3000 do
  setmetatable({}, {__gc = function() pcall(read) pcall(function() o.x = 1 end) script.on_load(read) end})
  pcall(read)
  pcall(function() return ('s' .. i):rep(20) end)
end
script.on_internal_event(E.ON_TICK, function()
  for i = 1, 500 do
    setmetatable({}, {__gc = function() pcall(read) script.on_init(read) end})
    pcall(read)
  end
end)
--@
-- Finalizers registering callbacks while the profile is being written.
for i = 1, 300 do script.on_load(function() end) script.on_game_event('n' .. i, false, function() end) end
for i = 1, 3000 do
  setmetatable({}, {__gc = function() script.on_init(function() end) end})
end
