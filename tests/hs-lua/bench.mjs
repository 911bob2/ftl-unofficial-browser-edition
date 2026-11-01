// Per-frame cost of an MV-sized Lua load through the real bridge (web/vendor/hs-lua.wasm) against a JS host that
// answers like the engine adapters do. Model (same as .build/hs/bench/bench.mjs, written against HS's API):
// 26 ON_TICK handlers reading Hyperspace.ships.player / App.world.bStartedGame and scanning 8 space drones,
// 19 SHIP_LOOP handlers x 2 ships, 2 CREW_LOOP handlers x 32 crew (read + write), 16 render handlers x 5 draws.
// Usage: node tests/hs-lua/bench.mjs [frames]
import {pathToFileURL} from 'node:url';
import {createRuntime} from './mock-host.mjs';

export const BENCH_LUA = `
local counter = 0
for i = 1, 26 do
  script.on_internal_event(Defines.InternalEvents.ON_TICK, function()
    local ship = Hyperspace.ships.player
    if Hyperspace.App.world.bStartedGame and ship then
      local drones = ship.spaceDrones
      for d = 0, drones:size() - 1 do
        local drone = drones[d]
        if drone.iShipId == 1 and drone.powered then counter = counter + 1 end
      end
    end
  end)
end
for i = 1, 19 do
  script.on_internal_event(Defines.InternalEvents.SHIP_LOOP, function(ship)
    if ship.ship.hullIntegrity.first < 5 or ship.iShipId > 10 then counter = counter + 1 end
  end)
end
for i = 1, 2 do
  script.on_internal_event(Defines.InternalEvents.CREW_LOOP, function(crew)
    local health = crew.health
    if health.first < health.second then health.first = health.first + 0.0001 end
  end)
end
local color = Graphics.GL_Color(1, 0.5, 0.25, 1)
for i = 1, 16 do
  script.on_render_event(Defines.RenderEvents.LAYER_FOREGROUND, function()
    for k = 1, 5 do Graphics.CSurface.GL_DrawRect(k, k, 10, 10, color) end
  end, function() end)
end
function bench_counter() return counter end
`;

/** Builds the world and adapters on a mock-host runtime; returns {frame(), stats()}. */
export function setupBench(r) {
  const {T, A, M, attrs, methods, arg, ret, e} = r;
  const mkShip = id => ({$t: T.ShipManager, iShipId: id, crew: [], drones: [], geom: {$t: T.Ship, hull: {$t: T.pair_int_int, first: 20 + id, second: 30}}});
  const ships = [mkShip(0), mkShip(1)];
  for (const s of ships) for (let i = 0; i < 16; i++) s.crew.push({$t: T.CrewMember, health: {$t: T.pair_float_float, first: 50 + i, second: 100}});
  for (let i = 0; i < 8; i++) ships[0].drones.push({$t: T.SpaceDrone, iShipId: i % 2, powered: i % 3 === 0});
  ships[0].dronesVec = {$t: T.vector_SpaceDrone, list: ships[0].drones};
  const world = {$t: T.WorldManager, bStartedGame: true}, app = {$t: T.CApp, world}, global = {$t: T.Global};
  let draws = 0;
  attrs[A['module.App']] = {get: () => app};
  attrs[A['CApp.world']] = {get: o => o.world};
  attrs[A['WorldManager.bStartedGame']] = {get: o => o.bStartedGame};
  attrs[A['ShipManager.spaceDrones']] = {get: o => o.dronesVec};
  attrs[A['ShipManager.ship']] = {get: o => o.geom};
  attrs[A['ShipManager.iShipId']] = {get: o => o.iShipId};
  attrs[A['Ship.hullIntegrity']] = {get: o => o.hull};
  attrs[A['pair_int_int.first']] = {get: o => o.first};
  attrs[A['pair_float_float.first']] = {get: o => o.first, set: (o, v) => { o.first = v; }};
  attrs[A['pair_float_float.second']] = {get: o => o.second};
  attrs[A['CrewMember.health']] = {get: o => o.health};
  attrs[A['Drone.iShipId']] = {get: o => o.iShipId};
  attrs[A['Drone.powered']] = {get: o => o.powered};
  methods[M.Global_GetInstance] = () => { ret.obj(global); return 1; };
  methods[M.Global_GetShipManager] = () => { ret.obj(ships[arg.num(2)] ?? null); return 1; };
  methods[M.vector_SpaceDrone_size] = () => { ret.num(arg.obj(1).list.length); return 1; };
  methods[M.vector_SpaceDrone___getitem] = () => { const v = arg.obj(1), i = arg.num(2); if (i >= v.list.length) { r.rt.setError('SWIG_IndexError:in vector::__getitem__()'); return -1; } ret.obj(v.list[i]); return 1; };
  const colors = [];
  methods[M.new_GL_Color__SWIG_0] = () => { const c = {$t: T.GL_Color, r: arg.num(1), g: arg.num(2), b: arg.num(3), a: arg.num(4)}; colors.push(c); ret.obj(c); return 1; };
  methods[M.CSurface_GL_DrawRect] = () => { const c = arg.obj(5); draws += arg.num(1) + arg.num(2) + arg.num(3) + arg.num(4) + c.r; return 0; };
  const err = r.rt.run('bench.lua', BENCH_LUA);
  if (err) throw Error(err);
  const ON_TICK = 1, SHIP_LOOP = 19, CREW_LOOP = 17, LAYER_FOREGROUND = 4;
  const {H} = r;
  const frame = () => {
    e.hs_fire(ON_TICK, 0, 0);
    for (const s of ships) {
      e.hs_pushobj(H.expose(s), T.ShipManager); e.hs_fire(SHIP_LOOP, 1, 0); e.hs_pop(1);
      for (const c of s.crew) { e.hs_pushobj(H.expose(c), T.CrewMember); e.hs_fire(CREW_LOOP, 1, 0); e.hs_pop(1); }
    }
    const idx = e.hs_render_pre(LAYER_FOREGROUND, 0); e.hs_render_post(LAYER_FOREGROUND, Math.abs(idx), 0);
  };
  return {frame, stats: () => ({draws, crew0: ships[0].crew[0].health.first})};
}

export function measure(label, frame, frames = 3000, warm = 300) {
  for (let i = 0; i < warm; i++) frame();
  const times = new Float64Array(frames);
  for (let i = 0; i < frames; i++) { const t = performance.now(); frame(); times[i] = performance.now() - t; }
  const sorted = Array.from(times).sort((a, b) => a - b);
  const mean = sorted.reduce((a, b) => a + b, 0) / frames;
  const res = {label, frames, mean, p50: sorted[frames >> 1], p99: sorted[Math.floor(frames * 0.99)], max: sorted[frames - 1]};
  console.log(`${label.padEnd(40)} mean ${mean.toFixed(4)} ms  p50 ${res.p50.toFixed(4)}  p99 ${res.p99.toFixed(4)}  max ${res.max.toFixed(3)}`);
  return res;
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  const frames = +(process.argv[2] || 3000);
  const r = await createRuntime();
  const b = setupBench(r);
  // count crossings in one frame
  let crossings = 0; const wrap = n => { const f = r.host[n]; r.host[n] = (...a) => { crossings++; return f(...a); }; };
  const out = {};
  out.withProfiling = measure('bridge, profiling on', b.frame, frames);
  r.e.hs_set_profiling(0);
  out.noProfiling = measure('bridge, profiling off', b.frame, frames);
  out.stats = b.stats();
  out.liveHandles = r.H.live();
  console.log(JSON.stringify(out));
}
