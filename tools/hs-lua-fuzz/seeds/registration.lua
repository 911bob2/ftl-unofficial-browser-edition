-- Callback lists changing while they run: every registration from inside every kind of callback.
local n = 0
local function again()
  n = n + 1
  if n > 60 then return end
  local s = 'event' .. n
  script.on_game_event(s, n % 2 == 0, again)
  script.on_game_event('start', false, again)
  script.on_load(again)
  script.on_init(again)
  script.on_internal_event(E.ON_TICK, again, n)
  script.on_internal_event(E.ON_KEY_DOWN, function(k) again() return C.CONTINUE end, -n)
  script.on_render_event(R.MOUSE_CONTROL, again, again, n)
end
script.on_game_event('start', false, again)
script.on_game_event('start', true, again)
script.on_load(again)
script.on_init(again)
script.on_internal_event(E.ON_TICK, again)
script.on_render_event(R.MOUSE_CONTROL, again, again, 1)
setmetatable({}, {__gc = again})
setmetatable({}, {__gc = function() for i = 1, 200 do script.on_load(function() end) end end})
for i = 1, 300 do setmetatable({}, {__gc = function() script.on_game_event('gc' .. i, false, again) end}) end
-- odd registrations
pcall(script.on_load, again, 1)
pcall(script.on_load, again, nil)
pcall(script.on_init, print, {}, {})
pcall(script.on_load, 'x')
pcall(script.on_game_event, 1, true, print)
pcall(script.on_game_event, 'a\0b', true, print)
pcall(script.on_game_event, ('n'):rep(5000), false, print)
pcall(script.on_internal_event, 0, print)
pcall(script.on_internal_event, 2^32 + 1, print)
pcall(script.on_internal_event, 81, print)
pcall(script.on_internal_event, E.ON_TICK, print)
pcall(script.on_internal_event, E.ON_TICK, function(...) end)
pcall(script.on_internal_event, E.ON_TICK, again, 2^40)
pcall(script.on_render_event, 0, print, print)
pcall(script.on_render_event, 2^32 + 1, print, print)
pcall(script.on_render_event, R.MOUSE_CONTROL, print, print, 1.5)
-- every event id, callback failing with odd error objects
for id = 1, 90 do
  pcall(script.on_internal_event, id, function(a, b, c) error({a, b, c}) end, id)
  pcall(script.on_internal_event, id, function(a, b) error(a) end)
  pcall(script.on_internal_event, id, function(a) return C.HALT, a end)
  pcall(script.on_internal_event, id, function() return C.PREEMPT, nil, 1, 'x', {} end)
  pcall(script.on_render_event, id, function() error(setmetatable({}, {__tostring = function() error('x') end})) end, function(a) return a end)
end
