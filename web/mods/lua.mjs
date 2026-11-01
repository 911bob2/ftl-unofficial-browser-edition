// ftlman 0.7.4 Lua patch scripts (`X.append.lua`) for the mod merge: the host side of web/vendor/ftlman-lua.wasm
// (hs-lua/ftlman.c holds the Lua API and the document tree). The host reads and writes XML text with xml.mjs
// (ftlman's speedy-xml) and answers mod.vfs calls; VirtualFileTree is ftlman's (src/apply/mod.rs). Interface:
// docs/MODS.md, "Lua patch scripts". Used by merge.mjs in the merge worker and in Node (tests, tools).
import {Writer, XmlError, parseAll, parseElement} from './xml.mjs';

export const LUA_LIMITS = {instructions: 1e9, memory: 512 * 1024 * 1024};
const enc = new TextEncoder(), dec = new TextDecoder();

let modulePromise = null;
/** The compiled patch runtime (fetched once per worker / process). */
export function loadPatchModule() {
  return modulePromise ??= (async () => {
    const url = new URL('../vendor/ftlman-lua.wasm', import.meta.url);
    let bytes;
    if (url.protocol === 'file:') bytes = await (await import('node:fs/promises')).readFile(url);
    else {
      const res = await fetch(url);
      if (!res.ok) throw Error('The Lua patch-script runtime could not be downloaded');
      bytes = await res.arrayBuffer();
    }
    return WebAssembly.compile(bytes);
  })().catch(e => { modulePromise = null; throw e; });
}

// ---- tree events (hs-lua/ftlman.c): 'S' prefix name attrs ... 'E', 'T' text, 'D' cdata, 'C' comment --------------
class Bytes {
  constructor(size = 1 << 16) { this.b = new Uint8Array(size); this.n = 0; }
  need(k) { if (this.n + k > this.b.length) { let c = this.b.length * 2; while (c < this.n + k) c *= 2; const nb = new Uint8Array(c); nb.set(this.b.subarray(0, this.n)); this.b = nb; } }
  byte(v) { this.need(1); this.b[this.n++] = v; }
  u32(v) { this.need(4); const b = this.b, n = this.n; b[n] = v & 255; b[n + 1] = (v >>> 8) & 255; b[n + 2] = (v >>> 16) & 255; b[n + 3] = v >>> 24; this.n += 4; }
  str(s) { this.need(s.length * 3 + 4); const r = enc.encodeInto(s, this.b.subarray(this.n + 4)); this.u32(r.written); this.n += r.written; }
  bytes() { return this.b.subarray(0, this.n); }
}
const S = 0x53, E = 0x45, T = 0x54, D = 0x44, C = 0x43;
function encodeNode(out, node) {
  if (node.name === undefined) {
    if (node.text !== undefined) { out.byte(T); out.str(node.text); }
    else if (node.cdata !== undefined) { out.byte(D); out.str(node.cdata); }
    else { out.byte(C); out.str(node.comment); }
    return;
  }
  out.byte(S);
  if (node.prefix == null) out.u32(0xffffffff); else out.str(node.prefix);
  out.str(node.name); out.u32(node.attrs.size);
  for (const [k, v] of node.attrs) { out.str(k); out.str(v); }
  for (const c of node.children) encodeNode(out, c);
  out.byte(E);
}
/** Events -> text through speedy-xml's writer (empty elements as `<a/>`). */
function writeEvents(bytes) {
  const w = new Writer(), stack = [], dv = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  let p = 0;
  const u32 = () => { const v = dv.getUint32(p, true); p += 4; return v; };
  const str = () => { const n = u32(), s = dec.decode(bytes.subarray(p, p + n)); p += n; return s; };
  while (p < bytes.length) {
    const t = bytes[p++];
    if (t === S) {
      const pl = dv.getUint32(p, true), prefix = pl === 0xffffffff ? (p += 4, null) : str(), name = str(), n = u32(), attrs = [];
      for (let i = 0; i < n; i++) attrs.push([str(), str()]);
      if (bytes[p] === E) { p++; w.emptyTag(prefix, name); for (const [k, v] of attrs) w.attribute(k, v); }
      else { w.start(prefix, name); for (const [k, v] of attrs) w.attribute(k, v); stack.push([prefix, name]); }
    } else if (t === E) { const [prefix, name] = stack.pop(); w.end(prefix, name); }
    else if (t === T) w.text(str());
    else if (t === D) w.cdata(str());
    else if (t === C) w.comment(str());
    else throw new XmlError('bad tree event');
  }
  return w.finish();
}

// ---- filesystems -------------------------------------------------------------------------------------------------
const ioError = message => Object.assign(new Error(message), {io: true});
// Rust's String order (UTF-8 bytes = code points).
const byCodePoint = (a, b) => {
  for (let i = 0, j = 0; i < a.length && j < b.length;) {
    const x = a.codePointAt(i), y = b.codePointAt(j);
    if (x !== y) return x < y ? -1 : 1;
    i += x > 0xffff ? 2 : 1; j += y > 0xffff ? 2 : 1;
  }
  return a.length - b.length;
};
/** ftlman's VirtualFileTree: directories from path components (split on / and \), lookups split on /. */
export class VirtualFileTree {
  constructor(paths, what = 'pkg archive') {
    this.root = new Map();
    for (const path of paths) {
      const parts = path.split(/[/\\]/), last = parts.pop();
      let cur = this.root;
      for (const c of parts) {
        let next = cur.get(c);
        if (next === undefined) cur.set(c, next = new Map());
        else if (next === true) throw Error(`Invalid file tree in ${what}: file overlaps directory`);
        cur = next;
      }
      if (cur.has(last)) throw Error(`Invalid file tree in ${what}: duplicate file or directory overlaps file`);
      cur.set(last, true);
    }
  }
  traverse(path) {
    const parts = path.split('/'), last = parts.pop();
    let cur = this.root;
    for (const c of parts) { const n = cur.get(c); if (!(n instanceof Map)) return undefined; cur = n; }
    return cur.get(last);
  }
  stat(path, onFile) { const n = this.traverse(path); return n instanceof Map ? {dir: true} : n === true ? {length: onFile()} : null; }
  ls(path) {
    const n = this.traverse(path);
    if (n instanceof Map) return [...n.keys()].sort(byCodePoint).map(name => ({name, dir: n.get(name) instanceof Map}));
    throw ioError(n === true ? 'not a directory' : 'entity not found');
  }
  read(path, onFile) { const n = this.traverse(path); if (n instanceof Map) throw ioError('is a directory'); if (n === true) return onFile(); throw ioError('entity not found'); }
  write(path, onFile) {
    const parts = path.split('/'), last = parts.pop();
    let cur = this.root;
    for (const c of parts) {
      let next = cur.get(c);
      if (next === undefined) cur.set(c, next = new Map());
      else if (next === true) throw ioError('not a directory');
      cur = next;
    }
    if (cur.get(last) instanceof Map) throw ioError('is a directory');
    onFile(); cur.set(last, true);
  }
}
/** mod.vfs.pkg (LuaPkgFS): the game files as the merge has them now. size/read/write are synchronous callbacks. */
export function pkgFilesystem(paths, {size, read, write}) {
  const tree = new VirtualFileTree(paths);
  return {
    stat: path => tree.stat(path, () => size(path)),
    ls: path => tree.ls(path),
    read: path => tree.read(path, () => read(path)),
    write: (path, data) => tree.write(path, () => write(path, data)),
  };
}
/** mod.vfs.mod (LuaZipFS): the archive's entries by exact name, read-only. */
export function zipFilesystem(zip) {
  const names = [...new Set(zip.entries.map(e => e.name))], tree = new VirtualFileTree(names, 'zip file');
  const entry = path => { const e = zip.get(path); if (!e) throw ioError('specified file not found in archive'); return e; };
  return {
    stat: path => tree.stat(path, () => entry(path).size),
    ls: path => tree.ls(path),
    read: path => tree.read(path, () => zip.readSync(entry(path))),
    write: () => { throw ioError('read-only filesystem or storage medium'); },
  };
}

// ---- runtime -----------------------------------------------------------------------------------------------------
const WRAPPER = /(<[?]xml [^>]*?[?]>\n*)|(<\/?FTL>)/g;
const unwrap = text => text.replace(WRAPPER, '');
const hadFtlRoot = text => { for (const m of text.matchAll(WRAPPER)) if (m[2] !== undefined) return true; return false; };

/**
 * One Lua runtime for a whole merge (ftlman's ModLuaRuntime: scripts share the global table, each gets its own
 * environment). `append(lower, upper, kind)` is merge.mjs's applyOneXml (mod.xml.append); onLog(kind, text) gets
 * print (0) and mod.debug.pretty_print (1) output and Lua warnings (2).
 */
export async function createPatchRuntime({append, onLog = () => {}, instructions = LUA_LIMITS.instructions, memory = LUA_LIMITS.memory} = {}) {
  const module = await loadPatchModule();
  let e = null, mem = null, u8 = null, filesystems = null;
  const view = () => (u8.buffer === mem.buffer ? u8 : (u8 = new Uint8Array(mem.buffer)));
  const bytesAt = (len, offset = 0) => view().subarray(e.mp_buf() + offset, e.mp_buf() + offset + len);
  const put = (bytes, offset = 0) => {
    if (offset + bytes.length > e.mp_buf_cap() && !e.mp_buf_grow(offset + bytes.length)) throw ioError('not enough memory');
    view().set(bytes, e.mp_buf() + offset);
    return bytes.length;
  };
  const putError = (err, base) => { const b = enc.encode(String(err?.message ?? err)).subarray(0, 4096); view().set(b, e.mp_buf()); return base - b.length; };
  const env = {
    log: (kind, p, len) => { try { onLog(kind, dec.decode(view().subarray(p, p + len))); } catch { /* the script goes on */ } },
    fs: (fs, op, pathLen, dataLen) => {
      try {
        const target = filesystems?.[fs];
        if (!target) throw ioError('no such filesystem');
        const path = dec.decode(bytesAt(pathLen));
        if (op === 0) { const s = target.stat(path); return s === null ? -1 : put(enc.encode(s.dir ? 'd' : 'f' + s.length)); }
        if (op === 1) {
          const out = new Bytes();
          for (const {name, dir} of target.ls(path)) { out.byte(dir ? 0x64 : 0x66); out.str(name); }
          return put(out.bytes());
        }
        if (op === 2) return put(target.read(path));
        target.write(path, bytesAt(dataLen, pathLen).slice());
        return 0;
      } catch (err) { return putError(err, -2); }
    },
    xml_parse: (len, topLevelText) => {
      try {
        const nodes = parseAll(unwrap(dec.decode(bytesAt(len))), {topLevelText: !!topLevelText}), out = new Bytes();
        for (const n of nodes) encodeNode(out, n);
        return put(out.bytes());
      } catch (err) { return putError(err, -1); }
    },
    xml_write: len => { try { return put(enc.encode(writeEvents(bytesAt(len).slice()))); } catch (err) { return putError(err, -1); } },
    xml_append: (lowerLen, upperLen, kind) => {
      try { return put(enc.encode(append(dec.decode(bytesAt(lowerLen)), dec.decode(bytesAt(upperLen, lowerLen)), kind ? 'rawappend' : 'append'))); }
      catch (err) { return putError(err, -1); }
    },
  };
  const imports = {env};
  for (const imp of WebAssembly.Module.imports(module)) {
    if (imp.kind !== 'function' || (imp.module === 'env' && env[imp.name])) continue;
    (imports[imp.module] ??= {})[imp.name] ??= imp.name === 'emscripten_notify_memory_growth' ? () => { u8 = new Uint8Array(mem.buffer); } : () => 52;
  }
  const instance = await WebAssembly.instantiate(module, imports);
  e = instance.exports; mem = e.memory; u8 = new Uint8Array(mem.buffer);
  e._initialize?.();
  const r0 = e.mp_init(instructions, memory);
  if (r0 < 0) throw Error('Lua runtime: ' + dec.decode(bytesAt(-1 - r0)));
  const run = (code, document, {chunkName, metaPath = null, fs = null}) => {
    let had = false, doc = null;
    if (document != null) {
      had = hadFtlRoot(document);
      let root;
      try { root = parseElement('<FTL>' + unwrap(document) + '</FTL>'); }
      catch (err) { throw Error('Could not parse XML document: ' + err.message); }
      if (!root) throw Error('XML document does not contain a root element');
      doc = new Bytes(); encodeNode(doc, root);
    }
    const names = new Bytes(), list = fs ? Object.entries(fs) : [];
    for (const [k] of list) names.str(k);
    const c = enc.encode(code), n = enc.encode(chunkName), m = metaPath == null ? null : enc.encode(metaPath);
    let off = put(c); off += put(n, off); if (m) off += put(m, off); if (doc) off += put(doc.bytes(), off); put(names.bytes(), off);
    filesystems = list.map(([, v]) => v);
    let r;
    try { r = e.mp_run(c.length, n.length, m ? m.length : -1, doc ? doc.n : 0, names.n, (had ? 0 : 1) | (doc ? 0 : 4)); }
    finally { filesystems = null; }
    if (r < 0) throw Error(dec.decode(bytesAt(-1 - r)));
    return doc ? writeEvents(bytesAt(r).slice()) : null;
  };
  return {
    /** apply_one_lua: runs `code` on `document` (the current file text); returns the patched text or throws.
     *  fs: {name: filesystem} for mod.vfs (a merge passes {pkg, mod}); chunkName "@data/x.append.lua". */
    apply: (document, code, options) => run(code, document, options),
    /** A script without a document (ftlman's `lua-run`). */
    run: (code, options) => { run(code, null, options); },
    stats: () => ({instructions: e.mp_instructions(), memory: e.mp_memory(), memoryPeak: e.mp_memory_peak(), nodes: e.mp_nodes()}),
  };
}
