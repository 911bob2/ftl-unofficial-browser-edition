// Modded launch: merge the enabled archives over this deployment's game files (cached by mod set), build the overlay
// for window.ftlFiles, the audio list and window.ftlModSession (PLAN I1). Saves of a mod set are ordinary saves locked to
// it (docs/SAVES.md). Loaded only when the player launches with mods; vanilla launches never import this module.
import {getArchive, getMerge, putMerge, pruneMerges} from './store.mjs';
import {buildSession} from './session.mjs';
import {FTLMAN_VERSION} from './merge.mjs';
import {blobSource, openZip} from './zip.mjs';

export const MERGE_VERSION = 1;
const isAudio = n => /\.(ogg|wav)$/.test(n), decoder = new TextDecoder();

function task(action, payload, onProgress, transfer = []) {
  return new Promise((resolve, reject) => {
    const worker = new Worker(new URL('./worker.mjs', import.meta.url), {type: 'module'});
    worker.onmessage = ({data}) => { if (data.progress) { onProgress(data.progress); return; } worker.terminate(); if (data.error) reject(Error(data.error)); else resolve(data.result); };
    worker.onerror = e => { worker.terminate(); reject(Error('The mod worker failed' + (e.message ? ': ' + e.message : '.'))); };
    worker.postMessage({id: 1, action, ...payload}, transfer);
  });
}
export const inspectArchive = (file, onProgress = () => {}, {hash = true} = {}) => task('inspect', {file, hash}, onProgress);

function unpack(packed) {
  const view = new DataView(packed);
  if (view.getUint32(0) !== 0x46544c42) throw Error('Invalid asset bundle');
  const n = view.getUint32(4), entries = JSON.parse(decoder.decode(new Uint8Array(packed, 8, n)));
  return {entries, base: 8 + n};
}

/**
 * The setup of enabled archives (sha256 in load order) as a save names it, before any merge: {key, mods: [{name, version,
 * sha256, size, verified}], hyperspace}. Each file is Verified or Custom by savecore's rule (`core.classify` against the
 * catalogue; without it the classification stored when the file was added).
 */
export async function describeSetup(shas, {core, catalogue = null}) {
  const mods = []; let hyperspace = false;
  for (const sha256 of shas) {
    const a = await getArchive(sha256);
    if (!a) throw Error('A mod archive is missing from browser storage. Import it again.');
    hyperspace ||= !!a.hyperspace;
    const known = catalogue ? core.classify(catalogue, a.name, sha256, a.size) : a.verified ? {name: a.title ?? a.name, version: a.version ?? '', verified: true} : core.classify({files: []}, a.name, sha256, a.size);
    mods.push({name: known.name, version: known.version ?? '', sha256, size: a.size, verified: !!known.verified});
  }
  return {key: core.modSetHash(shas), mods, hyperspace};
}
/**
 * list: [{sha256}] enabled archives in load order. provider: the vanilla asset provider (server or local DAT).
 * Returns {session, manifest, fetchBoot, fetchAudio, overlay: {files, removed, session}, stats}.
 */
export async function prepareModdedLaunch({account, list, provider, onProgress = () => {}}) {
  const t0 = performance.now(), archives = [];
  for (const {sha256} of list) {
    const a = await getArchive(sha256);
    if (!a) throw Error('A mod archive is missing from browser storage. Import it again.');
    archives.push(a);
  }
  onProgress('Loading game resources…', {stage: 'files'});
  const packed = await provider.fetchBoot(), {entries, base} = unpack(packed), t1 = performance.now();
  // The worker gets the base text files (XML for appends; .txt too, which Lua patch scripts read, e.g. ship
  // layouts) and every base file's size (mod.vfs.pkg:stat).
  const manifest = provider.manifest, baseNames = [], baseText = [], baseSizes = [];
  for (const e of entries) {
    if (e.name.startsWith('engine/')) continue;
    baseNames.push(e.name); baseSizes.push([e.name, e.length]);
    if (e.name.endsWith('.xml') || e.name.endsWith('.txt')) baseText.push([e.name, new Uint8Array(packed, base + e.offset, e.length).slice()]);
  }
  for (const a of manifest.audio) { baseNames.push(a.name); baseSizes.push([a.name, a.length]); }
  const mods = archives.map(a => ({name: a.title ?? a.name, title: a.title, version: a.version, sha256: a.sha256, size: a.size}));
  const key = ['merge', MERGE_VERSION, FTLMAN_VERSION, manifest.assetVersion, ...archives.map(a => a.sha256)].join('|');
  let cached = null;
  try { cached = await getMerge(key); } catch { cached = null; }
  onProgress(cached ? 'Loading cached mod merge…' : 'Merging mods…', {stage: 'mods'});
  const result = await task('merge', {archives: archives.map(a => ({name: a.title ?? a.name, file: a.file})), baseNames, baseText, baseSizes, cached: cached ? {text: cached.text, refs: cached.refs, removed: cached.removed, warnings: cached.warnings} : null},
    p => onProgress(p.phase === 'merge' ? `Merging ${p.name}…` : p.phase === 'read' ? 'Reading mod files…' : 'Merging mods…',
      {stage: 'mods', unit: 'files', done: p.phase === 'merge' ? p.file : p.done, total: p.phase === 'merge' ? p.files : p.total}));
  if (!cached) { try { await putMerge(key, {key, created: Date.now(), ...result.cache}); await pruneMerges(2); } catch { /* the merge still runs; it is only not cached */ } }
  const t2 = performance.now();

  const files = new Map(), removed = new Set(result.cache.removed);
  for (const [name, bytes] of result.cache.text) if (!isAudio(name)) files.set(name, bytes);
  for (const [name, bytes] of result.binary) files.set(name, bytes);
  // The base game has no data/hyperspace.xml, so the overlay alone decides `hyperspace` and the script list.
  const session = await buildSession(mods, files);

  // Audio: base entries unless replaced/removed, plus the archives' audio read lazily from their Blobs.
  const modAudio = new Map();
  for (const [name, bytes] of result.cache.text) if (isAudio(name)) modAudio.set(name, {bytes});
  for (const [name, archive, entry] of result.audio) modAudio.set(name, {archive, entry});
  const audio = manifest.audio.filter(a => !removed.has(a.name) && !modAudio.has(a.name));
  for (const [name, ref] of modAudio) audio.push({name, hash: 'mod:' + name, extension: name.endsWith('.ogg') ? '.ogg' : '.wav', mod: ref});
  const zips = new Map();
  const zipOf = i => { if (!zips.has(i)) zips.set(i, openZip(blobSource(archives[i].file))); return zips.get(i); };
  async function fetchAudio(asset) {
    if (!asset.mod) return provider.fetchAudio(asset);
    if (asset.mod.bytes) return asset.mod.bytes.slice().buffer;
    const zip = await zipOf(asset.mod.archive), e = zip.get(asset.mod.entry);
    if (!e) throw Error('Mod audio is missing: ' + asset.name);
    const bytes = await zip.read(e);
    return bytes.byteOffset === 0 && bytes.byteLength === bytes.buffer.byteLength ? bytes.buffer : bytes.slice().buffer;
  }
  const stats = {archives: archives.length, cached: !!cached, files: files.size, removed: removed.size, audio: modAudio.size, warnings: result.cache.warnings.length,
    bootMs: Math.round(t1 - t0), mergeMs: Math.round(result.mergeMs), readMs: Math.round(result.readMs), totalMs: Math.round(t2 - t0)};
  globalThis.ftlModLaunchStats = stats; // read by tests and shown in diagnostics
  // Which archive each script comes from (the last one that has the file): the launcher asks per mod before scripts
  // that use io, os, debug or package run (mods/lua-consent.mjs).
  async function scriptOwners() {
    const owners = new Map(), left = new Set(session.scripts);
    for (let i = archives.length - 1; i >= 0 && left.size; i--) {
      const zip = await zipOf(i);
      for (const s of [...left]) if (zip.get(s)) { owners.set(s, i); left.delete(s); }
    }
    return owners;
  }
  return {session, stats, warnings: result.cache.warnings, manifest: {...manifest, audio}, fetchBoot: async () => packed, fetchAudio,
    overlay: {files, removed, session}, mods, scriptOwners};
}
