// Mod merge with ftlman 0.7.4 semantics (src/apply/mod.rs apply_ftl): archives in order, entries in central-directory
// order, each file replacing, adding to or patching what earlier mods (and the base ftl.dat) left. Pure JS; runs in a
// Web Worker in the browser and in Node for tests/tools.
import {Reader, Writer, XmlError, attributes, parseElement, writeChildren, writeElement} from './xml.mjs';
import {parseScript, patch} from './append.mjs';
import {createPatchRuntime, pkgFilesystem, zipFilesystem} from './lua.mjs';

export const FTLMAN_VERSION = '0.7.4';
const WRAPPER = /(<[?]xml [^>]*?[?]>\n*)|(<\/?FTL>)/g;
const IGNORED = /[.]DS_Store$|(^|\/)thumbs[.]db$|(^|\/)[.]dropbox$|^~|~$|(^|\/)#.+#$/;
const KNOWN_DIRS = /^(audio|data|fonts|img)\//;
const SUFFIXES = [['.xml.append', 'append'], ['.append.xml', 'append'], ['.rawappend.xml', 'rawappend'], ['.xml.rawappend', 'rawappend'], ['.append.lua', 'lua']];
export class MergeError extends Error {}

const utf8 = new TextDecoder('utf-8', {fatal: true, ignoreBOM: true}), encoder = new TextEncoder();
function strictUtf8(bytes, what) { try { return utf8.decode(bytes); } catch { throw new MergeError(what + ' is not valid UTF-8'); } }
function utf16(bytes, le, what) {
  if (bytes.length % 2) throw new MergeError(what + ': UTF-16 decoding failed: partial bytepair');
  let s = '';
  for (let i = 0; i < bytes.length; i += 2) s += String.fromCharCode(le ? bytes[i] | bytes[i + 1] << 8 : bytes[i] << 8 | bytes[i + 1]);
  if (/[\ud800-\udbff](?![\udc00-\udfff])|(?<![\ud800-\udbff])[\udc00-\udfff]/.test(s)) throw new MergeError(what + ': invalid UTF-16');
  return s;
}

/** read_encoded_text: UTF-16 by BOM, UTF-8 BOM stripped. Like ftlman, the first two bytes are validated as UTF-8 on
 *  their own, so a file whose second byte starts a multi-byte character fails. */
export function readEncodedText(bytes, what = 'file') {
  if (bytes.length < 2) return strictUtf8(bytes, what);
  const a = bytes[0], b = bytes[1];
  if (a === 0xff && b === 0xfe) return utf16(bytes.subarray(2), true, what);
  if (a === 0xfe && b === 0xff) return utf16(bytes.subarray(2), false, what);
  if (a === 0xef && b === 0xbb) {
    if (bytes.length < 3) throw new MergeError(what + ' is not valid UTF-8');
    if (bytes[2] === 0xbf) return strictUtf8(bytes.subarray(3), what);
    return strictUtf8(bytes.subarray(0, 3), what) + strictUtf8(bytes.subarray(3), what);
  }
  return strictUtf8(bytes.subarray(0, 2), what) + strictUtf8(bytes.subarray(2), what);
}

export const unwrapXmlText = text => text.replace(WRAPPER, '');
const hadFtlRoot = text => { for (const m of text.matchAll(WRAPPER)) if (m[2] !== undefined) return true; return false; };
export const lfToCrlf = s => s.replace(/(?<!\r)\n/g, '\r\n');

/** apply_one_xml: patch `document` (the current file text) with an .append or .rawappend script. */
export function applyOneXml(document, script, kind) {
  if (kind === 'rawappend') {
    const had = hadFtlRoot(document);
    return '<?xml version="1.0" encoding="utf-8"?>\n' + (had ? '<FTL>\n' : '') + unwrapXmlText(document) +
      `\n\n<!-- Appended by ftlman ${FTLMAN_VERSION} -->\n\n` + unwrapXmlText(script) + '\n' + (had ? '</FTL>\n' : '');
  }
  const had = hadFtlRoot(document);
  const root = parseElement('<FTL>' + unwrapXmlText(document) + '</FTL>');
  if (!root) throw new MergeError('XML document does not contain a root element');
  patch(root, parseScript(unwrapXmlText(script)));
  const w = new Writer();
  if (had) writeElement(w, root); else writeChildren(w, root);
  return w.finish();
}

/** A plain .xml file copied from a mod: re-emitted through speedy-xml's event writer (declarations/PIs dropped,
 *  tags normalised, end tags renamed to match their start tags, unclosed elements closed). */
export function rewriteXml(text) {
  const r = new Reader(text, {topLevelText: true, unclosed: true}), w = new Writer(), stack = [];
  for (let e; (e = r.next()) !== null;) {
    if (e.type === 'start' || e.type === 'empty') {
      if (e.type === 'start') { stack.push([e.prefix, e.name]); w.start(e.prefix, e.name); } else w.emptyTag(e.prefix, e.name);
      for (const a of attributes(e.attrs)) w.rawAttribute(a);
    } else if (e.type === 'end') { const top = stack.pop(); if (top) w.end(top[0], top[1]); }
    else w.raw(e.raw);
  }
  while (stack.length) { const [p, n] = stack.pop(); w.end(p, n); }
  return w.finish();
}

function appendType(name) { for (const [suffix, kind] of SUFFIXES) if (name.endsWith(suffix)) return [name.slice(0, -suffix.length), kind]; return null; }

/**
 * baseNames: names in the base ftl.dat (in order); readBase(name) -> Uint8Array|Promise;
 * archives: [{name, zip}] in load order (zip from openZip). Returns {files: Map(name -> Uint8Array, or {archive, entry}
 * for copied binaries with lazyBinary) of changed/added files in first-write order, removed: Set, warnings: [],
 * lua: {scripts, instructions, maxInstructions, memoryPeak}}. Unsupported or failing input throws MergeError/XmlError
 * where ftlman's patch would fail.
 * Lua patch scripts read files synchronously: readBase must then return bytes (not a Promise) for the files a script
 * reads; baseSize(name) (optional) gives a base file's size without reading it; zips need readSync for their entries.
 * luaLimits: {instructions, memory} per script / for the runtime; onLuaLog(kind, text): print and pretty_print output.
 */
export async function mergeMods({baseNames, readBase, baseSize = null, archives, lazyBinary = false, onWarning = () => {}, onProgress = () => {}, luaLimits = {}, onLuaLog = () => {}}) {
  const base = new Set(baseNames), files = new Map(), removed = new Set(), warnings = [];
  const warn = text => { warnings.push(text); onWarning(text); };
  const lower = new Map(); for (const n of baseNames) lower.set(n.toLowerCase(), n);
  const contains = n => files.has(n) || (base.has(n) && !removed.has(n));
  const read = async n => files.get(n) ?? await readBase(n);
  const put = (n, bytes) => { removed.delete(n); files.set(n, bytes); };
  let lua = null; const luaStats = {scripts: 0, instructions: 0, maxInstructions: 0, memoryPeak: 0};
  // mod.vfs.pkg: what ftl.dat holds at this point of the merge, read synchronously.
  const readSync = n => {
    const v = files.get(n);
    if (v instanceof Uint8Array) return v;
    if (v) return archives[v.archive].zip.readSync(v.entry);
    const b = readBase(n);
    if (!(b instanceof Uint8Array)) throw Error(n + ' is not available to patch scripts here');
    return b;
  };
  const sizeOf = n => { const v = files.get(n); if (v instanceof Uint8Array) return v.length; if (v) return v.entry.size; return baseSize ? baseSize(n) : readSync(n).length; };
  const pkgPaths = () => { const out = []; for (const n of baseNames) if (!removed.has(n) && !files.has(n)) out.push(n); for (const n of files.keys()) out.push(n); return out; };
  for (const [index, archive] of archives.entries()) {
    const skippedDirs = new Set(), entries = archive.zip.entries.filter(e => !e.name.endsWith('/') && !e.name.endsWith('\\'));
    for (const [j, entry] of entries.entries()) {
      const name = entry.name.replace(/\\/g, '/');
      if (name.startsWith('/') || name.split('/').includes('..')) throw new MergeError(`Unsafe path ${entry.name} in ${archive.name}`);
      if (name.startsWith('mod-appendix/')) continue;
      // silpkg (ftl.dat) paths are ASCII-only; ftlman fails on any other inserted name.
      const ascii = !/[^\x00-\x7f]/.test(name);
      if (!KNOWN_DIRS.test(name)) {
        const sep = name.indexOf('/');
        if (sep >= 0) { const dir = name.slice(0, sep); if (!skippedDirs.has(dir)) { skippedDirs.add(dir); warn(`${archive.name}: skipping unrecognized top-level directory ${dir}`); } continue; }
      }
      if (j % 64 === 0) onProgress({archive: index, file: j, files: entries.length, name: archive.name});
      if (!ascii && (KNOWN_DIRS.test(name) || !name.includes('/'))) throw new MergeError(`${name} from ${archive.name}: non-ASCII file names cannot be stored in ftl.dat`);
      const what = `${name} from ${archive.name}`, text = async () => readEncodedText(await archive.zip.read(entry), what);
      const append = appendType(name);
      if (append) {
        const [stem, kind] = append;
        let target = stem + '.xml';
        if (!contains(target)) { const c = lower.get(target.toLowerCase()); if (c !== undefined) { warn(`${archive.name}: ${target} not found, using case-insensitive match ${c}`); target = c; } }
        if (!contains(target)) { warn(`${archive.name}: ignoring ${name} with non-existent base file`); continue; }
        const original = strictUtf8(await read(target), target), script = await text();
        let result;
        if (kind === 'lua') {
          // ftlman's apply_one_lua with mod.vfs.pkg/mod; one runtime for the whole merge, as ftlman.
          lua ??= await createPatchRuntime({append: applyOneXml, onLog: onLuaLog, ...luaLimits});
          let fs;
          try { fs = {pkg: pkgFilesystem(pkgPaths(), {size: sizeOf, read: readSync, write: (n, bytes) => put(n, bytes)}), mod: zipFilesystem(archive.zip)}; }
          catch (e) { throw new MergeError(`Failed to create archive filesystem for ${what}: ${e.message}`); }
          try { result = lua.apply(original, script, {chunkName: '@' + name, metaPath: '/' + name, fs}); }
          catch (e) { throw new MergeError(`Could not patch XML file ${target} according to ${what}: ${e.message}`); }
          const st = lua.stats(); luaStats.scripts++; luaStats.instructions += st.instructions;
          luaStats.maxInstructions = Math.max(luaStats.maxInstructions, st.instructions); luaStats.memoryPeak = st.memoryPeak;
        } else {
          try { result = applyOneXml(original, script, kind); }
          catch (e) { throw new MergeError(`Could not patch XML file ${target} according to ${what}: ${e.message}`); }
        }
        put(target, encoder.encode(result));
      } else if (name.endsWith('.xml.rawclobber') || name.endsWith('.rawclobber.xml')) {
        const target = name.endsWith('.rawclobber.xml') ? name.slice(0, -'.rawclobber.xml'.length) + '.xml' : name.slice(0, -'.rawclobber'.length);
        put(target, encoder.encode(await text())); lower.set(target.toLowerCase(), target);
      } else if (name.endsWith('.xml')) {
        let out;
        try { out = rewriteXml(await text()); } catch (e) { if (e instanceof XmlError) throw new MergeError(`XML parse error in ${what}: ${e.message}`); throw e; }
        put(name, encoder.encode(out)); lower.set(name.toLowerCase(), name);
      } else if (!IGNORED.test(name)) {
        if (name.endsWith('.txt')) { put(name, encoder.encode(lfToCrlf(await text()))); lower.set(name.toLowerCase(), name); }
        // lazyBinary: keep a reference {archive, entry} instead of the bytes (the browser reads images/audio later).
        else put(name, lazyBinary ? {archive: index, entry} : await archive.zip.read(entry));
      } else if (contains(name)) {
        // ftlman removes the existing entry before noticing the file is ignored, so it is deleted.
        files.delete(name); if (base.has(name)) removed.add(name);
      }
    }
  }
  return {files, removed, warnings, lua: luaStats};
}
