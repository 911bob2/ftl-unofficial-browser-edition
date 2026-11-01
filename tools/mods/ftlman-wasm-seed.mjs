// Prints the string hash seed of web/vendor/ftlman-lua.wasm (its `pairs` order follows from it), which the Android
// build copies (android/engine/src/main/jni/ftlman_lstate_jni.c): the word in the module's memory that gives the hash
// its interned library names carry. Usage: node tools/mods/ftlman-wasm-seed.mjs
import {readFileSync} from 'node:fs';

const module = await WebAssembly.compile(readFileSync(new URL('../../web/vendor/ftlman-lua.wasm', import.meta.url)));
const imports = {env: {log() {}, fs: () => -1, xml_parse: () => -1, xml_write: () => -1, xml_append: () => -1}};
for (const imp of WebAssembly.Module.imports(module)) {
  if (imp.kind === 'function' && !(imp.module === 'env' && imports.env[imp.name])) (imports[imp.module] ??= {})[imp.name] ??= () => 52;
}
const {exports: e} = await WebAssembly.instantiate(module, imports);
e._initialize?.();
if (e.mp_init(1e9, 512 * 1024 * 1024) < 0) throw Error('mp_init failed');
const memory = Buffer.from(e.memory.buffer), view = new DataView(e.memory.buffer);
const luaHash = (s, seed) => {
  let h = (seed ^ s.length) >>> 0;
  for (let l = s.length; l >= 1; l--) h = (h ^ (((h << 5) >>> 0) + (h >>> 2) + s.charCodeAt(l - 1))) >>> 0;
  return h;
};
// A short TString in wasm32: ..., shrlen byte, u32 hash, u32 hnext, the characters and a NUL.
const interned = ['gmatch', 'tostring', 'setmetatable', 'collectgarbage', 'reverse'].map(s => {
  for (let at = memory.indexOf(s + '\0'); at >= 0; at = memory.indexOf(s + '\0', at + 1)) {
    if (memory[at - 9] === s.length) return [s, view.getUint32(at - 8, true)];
  }
  throw Error('not found as an interned string: ' + s);
});
const seeds = new Set();
for (let i = 0; i + 4 <= memory.length; i += 4) {
  const w = view.getUint32(i, true);
  if (interned.every(([s, hash]) => luaHash(s, w) === hash)) seeds.add(w);
}
if (seeds.size !== 1) throw Error('expected one seed, found ' + seeds.size);
console.log('0x' + [...seeds][0].toString(16));
