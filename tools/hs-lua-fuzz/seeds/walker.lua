-- Walks _G, the API namespaces and metatables; calls every function and reads and assigns every attribute under pcall
-- with hostile values and with userdata of other types as self. K and N (set by fuzz.py) select one shard.
local pcall, pairs, type, getmetatable, ipairs, tostring = pcall, pairs, type, getmetatable, ipairs, tostring
local K, N = K or 0, N or 1
local big = ('z'):rep(70000)
local vals = {0, 1, -1, 0.5, 0/0, 1/0, -1/0, 2^53, 2^63, math.mininteger, math.maxinteger, '', 'x', big, '\xff\xfe\0z', true, false,
  {}, {{}}, print, function() end, function(...) return ... end,
  setmetatable({}, {__index = function(t, k) return k end, __newindex = function() end, __call = function(s, ...) return ... end,
    __tostring = function() return 1 end, __len = function() return -1 end, __gc = function(o) keep = o end, __mode = 'kv'})}
local nv = #vals
local objs, seen, count = {}, {}, 0
local function keepobj(o)
  if type(o) == 'userdata' and #objs < 64 then objs[#objs + 1] = o end
end
local function try(f, ...)
  local ok, r, r2 = pcall(f, ...)
  keepobj(r) keepobj(r2)
  return r
end
local function callall(f)
  count = count + 1
  if count % N ~= K then return end
  try(f)
  try(f, nil)
  try(f, nil, nil)
  for i = 1, nv do
    try(f, vals[i])
    try(f, vals[i], vals[i % nv + 1])
    try(f, vals[i], vals[(i + 3) % nv + 1], vals[(i + 7) % nv + 1])
  end
  for i = 1, #objs, 3 do
    local o = objs[i]
    try(f, o)
    try(f, o, o)
    try(f, o, 1, 'x')
    try(f, o, 'x', 2)
    try(f, 1, o)
  end
end
local function index(o, k) return o[k] end
local function assign(o, k, v) o[k] = v end
local function members(names)
  for k in pairs(names) do
    count = count + 1
    if count % N == K then
      for i = 1, #objs, 5 do
        local o = objs[i]
        try(index, o, k)
        for j = 1, nv, 3 do try(assign, o, k, vals[j]) end
        try(assign, o, k, nil)
        try(assign, o, k, objs[(i + 1) % #objs + 1])
      end
    end
  end
end
local function walk(t, depth)
  if seen[t] or depth > 10 then return end
  seen[t] = true
  local names = {}
  for k, v in pairs(t) do names[#names + 1] = {k, v} end
  for _, kv in ipairs(names) do
    local k, v = kv[1], kv[2]
    if type(v) == 'function' then callall(v)
    elseif type(v) == 'table' then walk(v, depth + 1)
    elseif type(v) == 'userdata' then keepobj(v) end
    if k == '.get' or k == '.set' or k == '.fn' then members(v) end
  end
  local mt = getmetatable(t)
  if type(mt) == 'table' then walk(mt, depth + 1) end
end
for _, name in ipairs({'Hyperspace', 'Graphics', 'Defines', 'RapidXML', 'script'}) do walk(_G[name] or {}, 0) end
walk(_G, 0)
for i = 1, #objs do
  local mt = getmetatable(objs[i])
  if mt then walk(mt, 0) end
  try(tostring, objs[i])
  try(function(a, b) return a == b, a .. 'x', -a, #a, a + 1, a < b, a(b) end, objs[i], objs[i % #objs + 1])
end
