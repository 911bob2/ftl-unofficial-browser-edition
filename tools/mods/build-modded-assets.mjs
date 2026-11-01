#!/usr/bin/env node
// Test/developer tool (never part of the website): builds a modded server-asset directory for the packaged test runner.
//
//   node tools/mods/build-modded-assets.mjs --base <vanilla asset dir> --out <dir> --mods <a.ftl> [<b.zip> ...]
//        [--dat <ftl.dat patched by ftlman>]
//
// --base is a prepared vanilla asset directory (manifest.json, boot.pack, audio/; tools/PrepareAssets.java).
// Content comes from our merge (web/mods/merge.mjs) of the archives over the base files, or, with --dat, from the
// ftl.dat ftlman wrote (ground truth). The archives always define the session identity.
// Output: manifest.json, boot.pack(.gz), audio/ (same layout as the base, vanilla assetVersion/engineBuild kept so
// fixture saves stay compatible), mod-session.json (window.ftlModSession, PLAN I1) and overlay.json (changed/added).
// Mod archives and outputs are user data: keep them in ignored .build/ directories only.
import {createReadStream, createWriteStream, copyFileSync, linkSync, mkdirSync, openSync, readFileSync, readSync, closeSync, fstatSync, writeFileSync, writeSync, existsSync, rmSync, renameSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {createGzip} from 'node:zlib';
import {pipeline} from 'node:stream/promises';
import {basename, join, resolve} from 'node:path';
import {readPkg} from './pkg.mjs';
import {openZip} from '../../web/mods/zip.mjs';
import {buildSession, parseMetadata} from '../../web/mods/session.mjs';

const argv = process.argv.slice(2), opts = {mods: []};
for (let i = 0; i < argv.length; i++) {
  const a = argv[i];
  if (a === '--base') opts.base = argv[++i]; else if (a === '--out') opts.out = argv[++i]; else if (a === '--dat') opts.dat = argv[++i];
  else if (a === '--mods') { while (argv[i + 1] && !argv[i + 1].startsWith('--')) opts.mods.push(argv[++i]); }
  else throw Error('Unknown argument ' + a);
}
if (!opts.base || !opts.out || !opts.mods.length) throw Error('Usage: build-modded-assets.mjs --base <dir> --out <dir> --mods <archive>... [--dat <ftl.dat>]');
const started = Date.now(), sha = bytes => createHash('sha256').update(bytes).digest('hex');
const log = (...a) => console.log(`[${((Date.now() - started) / 1000).toFixed(1)}s]`, ...a);

// Archives are read through file-descriptor slices, as the browser reads Blob slices.
function fileSource(path) {
  const fd = openSync(path, 'r'), size = fstatSync(fd).size;
  const readAt = (offset, length) => { const b = Buffer.allocUnsafe(length); let n = 0; while (n < length) { const r = readSync(fd, b, n, length - n, offset + n); if (!r) throw Error('Short read'); n += r; } return new Uint8Array(b.buffer, b.byteOffset, length); };
  return {size, fd, read: async (offset, length) => readAt(offset, length), readSync: readAt};
}
async function fileSha(path) { const h = createHash('sha256'); await pipeline(createReadStream(path), h); return h.digest('hex'); }

// Base files: boot.pack entries plus audio by name.
const manifest = JSON.parse(readFileSync(join(opts.base, 'manifest.json'), 'utf8'));
const pack = readFileSync(join(opts.base, 'boot.pack')), pv = new DataView(pack.buffer, pack.byteOffset, pack.byteLength);
if (pv.getUint32(0) !== 0x46544c42) throw Error('Invalid base boot.pack');
const jsonLength = pv.getUint32(4), bootEntries = JSON.parse(pack.subarray(8, 8 + jsonLength).toString('utf8')), packBase = 8 + jsonLength;
const base = new Map();
for (const e of bootEntries) base.set(e.name, {kind: 'boot', hash: e.hash, length: e.length, bytes: () => pack.subarray(packBase + e.offset, packBase + e.offset + e.length)});
for (const a of manifest.audio) base.set(a.name, {kind: 'audio', hash: a.hash, length: a.length, extension: a.extension, bytes: () => readFileSync(join(opts.base, 'audio', a.hash + a.extension))});
log(`base: ${bootEntries.length} boot entries, ${manifest.audio.length} audio`);

// Session identity from the archives.
const mods = [];
for (const path of opts.mods) {
  const src = fileSource(path), zip = await openZip(src), meta = zip.get('mod-appendix/metadata.xml');
  const {title, version} = parseMetadata(meta ? await zip.read(meta) : null);
  mods.push({path, name: title ?? basename(path), title, version, size: src.size, sha256: await fileSha(path), zip, src});
}
log('mods: ' + mods.map(m => `${m.name} ${m.version ?? ''} ${m.sha256.slice(0, 12)}`).join(' | '));

// Merged content: changed or added files relative to the base.
const overlay = new Map();
if (opts.dat) {
  const merged = readPkg(opts.dat);
  for (const [name, bytes] of merged) { const b = base.get(name); if (!b || b.hash !== sha(bytes)) overlay.set(name, bytes); }
  const missing = [...base.keys()].filter(n => !n.startsWith('engine/') && !merged.has(n));
  if (missing.length) throw Error('Merged ftl.dat lacks base files: ' + missing.slice(0, 5).join(', '));
  log(`ftlman dat: ${merged.size} files, ${overlay.size} changed/added`);
} else {
  const {mergeMods} = await import('../../web/mods/merge.mjs');
  const result = await mergeMods({baseNames: [...base.keys()].filter(n => !n.startsWith('engine/')), readBase: name => base.get(name).bytes(), baseSize: name => base.get(name).length,
    archives: mods.map(m => ({name: m.name, filename: basename(m.path), zip: m.zip})), onWarning: w => console.warn('WARN ' + w)});
  for (const [name, bytes] of result.files) { const b = base.get(name); if (!b || b.hash !== sha(bytes)) overlay.set(name, bytes); }
  for (const name of result.removed) { bootEntries.splice(bootEntries.findIndex(e => e.name === name), 1); manifest.audio = manifest.audio.filter(a => a.name !== name); }
  log(`our merge: ${overlay.size} changed/added, ${result.removed.size} removed, ${result.warnings.length} warnings`);
}
for (const m of mods) closeSync(m.src.fd);

// Write the asset directory.
rmSync(opts.out, {recursive: true, force: true}); mkdirSync(join(opts.out, 'audio'), {recursive: true});
const isAudio = n => /\.(ogg|wav)$/.test(n);
const order = [...bootEntries.map(e => e.name), ...[...overlay.keys()].filter(n => !base.has(n) && !isAudio(n))];
const outEntries = [], parts = []; let offset = 0;
for (const name of order) {
  const bytes = overlay.get(name) ?? base.get(name).bytes();
  outEntries.push({name, hash: overlay.has(name) ? sha(bytes) : base.get(name).hash, offset, length: bytes.length}); parts.push(bytes); offset += bytes.length;
}
const json = Buffer.from(JSON.stringify(outEntries), 'utf8'), head = Buffer.alloc(8); head.writeUInt32BE(0x46544c42, 0); head.writeUInt32BE(json.length, 4);
const fd = openSync(join(opts.out, 'boot.pack'), 'w'); writeSync(fd, head); writeSync(fd, json); for (const p of parts) writeSync(fd, p); closeSync(fd);
log(`boot.pack: ${outEntries.length} entries, ${(offset / 1048576).toFixed(1)} MiB`);
await pipeline(createReadStream(join(opts.out, 'boot.pack')), createGzip({level: 1}), createWriteStream(join(opts.out, 'boot.pack.gz')));
log('boot.pack.gz written');

const audio = [];
for (const a of manifest.audio) if (!overlay.has(a.name)) {
  const target = join(opts.out, 'audio', a.hash + a.extension);
  if (!existsSync(target)) { try { linkSync(join(opts.base, 'audio', a.hash + a.extension), target); } catch { copyFileSync(join(opts.base, 'audio', a.hash + a.extension), target); } }
  audio.push(a);
}
for (const [name, bytes] of overlay) if (isAudio(name)) {
  const hash = sha(bytes), extension = name.endsWith('.ogg') ? '.ogg' : '.wav';
  writeFileSync(join(opts.out, 'audio', hash + extension), bytes); audio.push({name, hash, length: bytes.length, extension});
}
const bootBytes = 8 + json.length + offset;
writeFileSync(join(opts.out, 'manifest.json'), JSON.stringify({...manifest, bootBytes, entries: outEntries.length, audio}));
const files = new Map([...overlay]); if (!files.has('data/hyperspace.xml') && base.has('data/hyperspace.xml')) files.set('data/hyperspace.xml', base.get('data/hyperspace.xml').bytes());
const session = await buildSession(mods, files);
writeFileSync(join(opts.out, 'mod-session.json'), JSON.stringify(session, null, 1));
writeFileSync(join(opts.out, 'overlay.json'), JSON.stringify({source: opts.dat ? 'ftlman' : 'merge', changed: [...overlay.keys()].filter(n => base.has(n)), added: [...overlay.keys()].filter(n => !base.has(n))}, null, 1));
log(`done: ${audio.length} audio, session ${session.hash.slice(0, 16)} hyperspace=${session.hyperspace} scripts=${session.scripts.length} -> ${resolve(opts.out)}`);
