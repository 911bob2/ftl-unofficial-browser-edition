// Loads Multiverse's scripts (hyperspace.xml <scripts> order) into the real runtime against the JS mock host and lists
// the Hyperspace members they touch while loading that the engine adapters (engine/.../hs/lua/*.kt) do not implement.
// Mod data is read from outside the repository and never copied:
//   node tests/hs-lua/mv-load.mjs <MV data dir>   (default .build/hs/extract/mv-5.5.1-data in the coordinator worktree)
// The mock answers every member leniently (objects are plain JS objects), so this is a guide for which adapters
// the load path needs, not a check of their behaviour; the browser run (docs/HS_LUA.md) is the real test.
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {createRuntime, attrName, methodName} from './mock-host.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const MV = process.argv[2];
const xml = fs.readFileSync(path.join(MV, 'data/hyperspace.xml'), 'utf8');
const block = xml.slice(xml.indexOf('<scripts>'), xml.indexOf('</scripts>'));
const scripts = [...block.matchAll(/<script>([^<]+)<\/script>/g)].map(m => m[1].trim());

// ids the Kotlin adapters register
const src = path.join(here, '../../engine/src/main/java/xyz/znix/xftl/hs/lua');
const implemented = new Set(), structTypes = new Set();
for (const f of fs.readdirSync(src).filter(f => f.endsWith('.kt') && f !== 'LuaApi.kt')) {
  const text = fs.readFileSync(path.join(src, f), 'utf8');
  for (const m of text.matchAll(/\bA\.([AM]_\w+)/g)) implemented.add(m[1]);
  // LuaStructs.create(T): every field of T and its bases has a generic accessor
  for (const m of text.matchAll(/LuaStructs\.create\(A\.T_(\w+)\)/g)) structTypes.add(m[1]);
}

// bindgen's catalog (written by tools/hs-bindgen/bindgen.mjs): declared result types of members
const cat = JSON.parse(fs.readFileSync(path.join(here, '../../.build/hsgen/catalog.json'), 'utf8'));
const typeId = Object.fromEntries(cat.types.map(t => [t.name, t.id]));
const byId = new Map(cat.methods.map(m => [m.id, m]));
const resolve = mid => byId.get(mid);
const stubs = new Map();
const touched = new Map();
const mark = (kind, name) => { const k = kind + ' ' + name; touched.set(k, (touched.get(k) ?? 0) + 1); };
let r = null;
r = await createRuntime({wrap(host) {
  for (const k of ['getn', 'gets', 'geto', 'setn', 'sets', 'seto']) {
    const f = host[k];
    host[k] = (h, a, ...rest) => { mark('A', attrName[a]); return f(h, a, ...rest); };
  }
  // members without a mock adapter answer typed stand-ins (an object of the declared type, 0, false, ""), so loading
  // continues past them
  const geto = host.geto;
  host.geto = (h, a) => {
    if (r.attrs[a]) return geto(h, a);
    const at = cat.attrs[a - 1];
    if (!at?.objType) return 0;
    const key = h + ':' + a;
    let o = stubs.get(key); if (!o) { o = {$t: at.objType}; stubs.set(key, o); }
    return r.packed(o, at.objType);
  };
  const call = host.call;
  host.call = (mid, n) => {
    mark('M', methodName[mid]);
    if (r.methods[mid]) return call(mid, n);
    const m = resolve(mid);
    const k = m?.getKind;
    if ((k === 'ptr' || k === 'val' || k === 'mem') && m.objType) { const t = typeId[m.objType]; r.ret.obj({$t: t}, t); }
    else if (k === 'num') r.e.hs_pushnumber(0);
    else if (k === 'bool') r.e.hs_pushboolean(0);
    else if (k === 'str') r.ret.str('');
    else if (/^new_/.test(methodName[mid]) || m?.name?.includes('::' + m?.name?.split('::')[0])) {
      const t = typeId[methodName[mid].replace(/^new_/, '').replace(/__SWIG_\d+$/, '')]; if (t) r.ret.obj({$t: t}, t); else return 0;
    } else return 0;
    return 1;
  };
}});
const files = new Map(scripts.map(s => [s, fs.readFileSync(path.join(MV, s))]));
let failed = 0;
for (const s of scripts) {
  const err = r.rt.run(s, files.get(s).toString('utf8'));
  if (err) { failed++; console.log('FAIL', s, '::', err.slice(0, 240)); }
}
const kotlinName = (kind, name) => kind === 'A'
  ? 'A_' + name.replace('.', '_').replace(/^module_?/, 'module_')
  : 'M_' + name.replace('.', '_').replace(/\(\)$/, '');
// struct types' bases and member struct types are generic too
for (let changed = true; changed;) {
  changed = false;
  for (const t of cat.types) if (structTypes.has(t.name)) {
    const more = [...(t.cls?.bases ?? []).map(b => b.replace(/^.*::/, '').replace(/\s*\*$/, '')),
      ...cat.attrs.filter(a => a.owner === t.id && (a.kind === 'mem' || a.kind === 'val') && a.objType).map(a => cat.types[a.objType - 1]?.name)];
    for (const b of more) if (b && !structTypes.has(b)) { structTypes.add(b); changed = true; }
  }
}
const missing = [...touched.keys()].filter(k => {
  const [kind, name] = k.split(' ');
  if (kind === 'A' && structTypes.has(name.split('.')[0])) return false;
  if (name.startsWith('module.')) return !implemented.has('A_module_' + name.slice(7));
  return !implemented.has(kotlinName(kind, name)) && ![...implemented].some(i => i.startsWith(kotlinName(kind, name) + '__SWIG'));
});
console.log(`scripts ${scripts.length}, failed ${failed}; members touched while loading ${touched.size}, without a Kotlin adapter ${missing.length}`);
for (const k of missing.sort()) console.log('  ' + k + ' x' + touched.get(k));
