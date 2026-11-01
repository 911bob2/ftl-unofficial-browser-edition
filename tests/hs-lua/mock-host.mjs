// A plain-JS stand-in for the engine side of the Hyperspace Lua bridge (what the Kotlin package xyz.znix.xftl.hs.lua
// does in the browser): a handle table with generations, attribute accessors by id and method adapters by id.
// Used by the Node tests and the bridge benchmark; the objects are JS objects, so only the bridge is exercised.
import fs from 'node:fs';
import {fileURLToPath} from 'node:url';
import {createHsLua} from '../../web/hs-lua.mjs';

const here = fileURLToPath(new URL('.', import.meta.url));
export const ids = JSON.parse(fs.readFileSync(here + '../../hs-lua/gen/hs_ids.json', 'utf8'));
export const attrName = []; for (const [k, v] of Object.entries(ids.attrs)) attrName[v] = k;
export const methodName = []; for (const [k, v] of Object.entries(ids.methods)) methodName[v] = k;

export async function createRuntime({rand = () => 12345, logs = [], counter = null, wrap = null} = {}) {
  const objs = [null], gens = [0], types = [0], free = [], idOf = new Map();
  let tables = 0; const tableOf = new Map();
  const H = {
    expose(o, type) {
      if (o == null) return 0;
      let h = idOf.get(o); if (h) return h;
      let i = free.pop(); if (i === undefined) { i = objs.length; objs.push(null); gens.push(0); types.push(0); }
      gens[i] = (gens[i] + 1) & 0x7ff || 1; objs[i] = o;
      h = (gens[i] << 20) | i; idOf.set(o, h); return h;
    },
    get(h) { const i = h & 0xfffff; return gens[i] === (h >>> 20) ? objs[i] : undefined; },
    release(h) { const i = h & 0xfffff; if (gens[i] !== (h >>> 20)) return; idOf.delete(objs[i]); objs[i] = null; gens[i] = (gens[i] + 1) & 0x7ff || 1; free.push(i); },
    live() { return idOf.size; },
  };
  const packed = (o, t) => o == null ? 0 : t * 4294967296 + (H.expose(o) >>> 0);
  // attribute accessors: attrs[id] = {get(obj), set(obj, v)}; objects carry their Lua type in o.$t
  const attrs = [], methods = [];
  const typeOf = o => o.$t;
  let rt;
  const host = {
    log: (kind, text) => logs.push([kind, text]),
    rand32: () => rand(), srand32: () => {},
    getn: (h, a) => { const f = attrs[a]; const o = h ? H.get(h) : null; return f ? +f.get(o) : (o ? +o[attrName[a].split('.')[1]] || 0 : 0); },
    gets: (h, a) => { const f = attrs[a]; const o = h ? H.get(h) : null; const v = f ? f.get(o) : o?.[attrName[a].split('.')[1]]; return v == null ? null : String(v); },
    geto: (h, a) => { const f = attrs[a]; const o = h ? H.get(h) : null; const v = f ? f.get(o) : o?.[attrName[a].split('.')[1]]; return v == null ? 0 : packed(v, typeOf(v)); },
    setn: (h, a, v) => { const o = H.get(h); const f = attrs[a]; if (f?.set) f.set(o, v); else o[attrName[a].split('.')[1]] = v; return null; },
    sets: (h, a, s) => { const o = H.get(h); const f = attrs[a]; if (f?.set) f.set(o, s); else o[attrName[a].split('.')[1]] = s; return null; },
    seto: (h, a, h2) => { const o = H.get(h); o[attrName[a].split('.')[1]] = h2 ? H.get(h2) : null; return null; },
    call: (mid, n) => { const f = methods[mid]; if (!f) { rt.setError('not implemented: ' + methodName[mid]); return -1; } return f(n); },
    release: h => H.release(h),
    tableid: h => { const o = H.get(h); if (!o) return 0; let t = tableOf.get(o); if (!t) { t = ++tables; tableOf.set(o, t); } return t; },
    registered: () => {},
  };
  if (counter) for (const k of Object.keys(host)) { const f = host[k]; host[k] = (...a) => { counter.n++; counter[k] = (counter[k] || 0) + 1; return f(...a); }; }
  if (wrap) wrap(host);
  rt = await createHsLua(fs.readFileSync(here + '../../web/vendor/hs-lua.wasm'), host);
  const e = rt.e;
  // helpers for adapters: arguments and results on the Lua stack
  const arg = {
    obj: i => H.get(e.hs_tohandle(i)), num: i => e.hs_tonumber(i), bool: i => !!e.hs_toboolean(i), str: i => rt.toString(i),
  };
  const ret = {
    num: v => e.hs_pushnumber(v), int: v => e.hs_pushinteger(v), bool: v => e.hs_pushboolean(v ? 1 : 0),
    str: v => rt.pushString(v), obj: (o, t) => { if (o == null) e.hs_pushnil(); else e.hs_pushobj(H.expose(o), t ?? o.$t); },
  };
  return {rt, e, H, host, attrs, methods, arg, ret, logs, packed, T: ids.types, A: ids.attrs, M: ids.methods};
}
