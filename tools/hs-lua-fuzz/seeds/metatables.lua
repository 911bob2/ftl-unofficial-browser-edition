-- Metamethods called with wrong selves, metatables edited behind the runtime's back.
local o, p = U(), U(H.Point)
local mt = o and getmetatable(o) or {}
local mp = p and getmetatable(p) or {}
local function each(f) for _, v in ipairs(V) do pcall(f, v) pcall(f, v, v) pcall(f, o, v) pcall(f, v, o) pcall(f, o, v, v) end pcall(f) end
for k, f in pairs(mt) do if type(f) == 'function' then each(f) end end
for k, f in pairs(mp) do if type(f) == 'function' then each(f) end end
for _, ns in ipairs({H, G, D, X, H.Point, H.Global}) do
  local m = getmetatable(ns)
  if m then
    for k, f in pairs(m) do if type(f) == 'function' then each(f) end end
    if m['.get'] then for k, f in pairs(m['.get']) do each(f) end end
    if m['.set'] then for k, f in pairs(m['.set']) do each(f) end end
    if m['.instance'] then
      local i = m['.instance']
      for _, part in ipairs({'.get', '.set', '.fn'}) do for k, f in pairs(i[part] or {}) do each(f) end end
    end
    m['.get'] = 1
    pcall(function() return ns.x end)
    m['.fn'] = {}
    pcall(function() return ns.x end)
    m['.set'] = {x = print, y = string.rep}
    pcall(function() ns.x = 1 ns.y = 2 ns.z = 3 end)
    m['.get'] = {x = print, y = string.rep, z = error}
    m['.fn'] = {}
    pcall(function() return ns.x, ns.y, ns.z end)
  end
end
-- metatables of userdata poisoned
if o then
  pcall(setmetatable, o, {})
  mt['.hs'] = nil
  pcall(function() return o.x, tostring(o), swig_type(o), swig_equals(o, o) end)
  pcall(function() return H.Point.__add(o, o) end)
  mt['.hs'] = true
  mt.__index = function(t, k) return k end
  mt.__newindex = function() error('no') end
  mt.__gc = function(u) saved = u end
  mt.__tostring = function() return {} end
  mt.__eq = function() return true end
  pcall(tostring, o)
  pcall(print, o)
  pcall(log, o, 1, nil, {})
  mt.__gc = print
  mt.__index = string.rep
  pcall(function() return o.x end)
end
for i = 1, 300 do local u = U() end
-- tables that claim to be class objects
local fake = setmetatable({}, mt)
pcall(function() return fake.x, fake == fake, tostring(fake), swig_type(fake) end)
for k, f in pairs(mp) do if type(f) == 'function' then pcall(f, fake) pcall(f, fake, 'x') pcall(f, fake, 'x', 1) end end
fake = setmetatable({}, getmetatable(H))
pcall(function() return fake.x, rawlen(fake) end)
getmetatable('').__index = function(s, k) return k end
pcall(function() return ('x').y end)
