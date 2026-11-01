// Catalogue file downloads (Mods panel): our server or official MEGA, in 16 MiB ranges that are decrypted (MEGA),
// hashed incrementally in a worker and stored as parts in IndexedDB together with the resumable hash state, so a
// 712 MB file is never held twice in memory and an interrupted download resumes after its last stored part. The
// finished file must match the catalogue SHA-256; only then does it become an archive in the library.
// A local file ("choose a file") is that entry when it has the same SHA-256, whatever its name; any other file is
// accepted as a Custom mod (docs/SAVES.md: local mod files are not pinned).
import {clearDownload, getArchive, getDownload, getDownloadParts, putArchive, putDownloadPart} from './store.mjs';
import {createDecryptor, MegaError, megaFileInfo, megaRange} from './mega.mjs';
import {inspectArchive} from './boot.mjs';
import {createRate} from '../progress.mjs';

export const CHUNK = 16 << 20;
// Tests shrink the range size (a multiple of 16) to exercise several ranges with small fixture files.
const chunkSize = () => { const n = globalThis.ftlModDownloadChunk; return Number.isSafeInteger(n) && n >= 16 && n % 16 === 0 ? n : CHUNK; };
/** The last download events (id, phase, done, total), read by tests and diagnostics. */
const log = (entry, p) => { const l = globalThis.ftlModDownloadLog ??= []; l.push({id: entry.id, phase: p.phase, done: p.done, total: p.total, source: p.source}); if (l.length > 200) l.shift(); };
/** kind: network | interrupted | removed | quota | rate | busy | denied | absent | auth | checksum | storage | changed | http | api | cancelled */
export class DownloadError extends Error {
  constructor(kind, message, extra = {}) { super(message); this.name = 'DownloadError'; this.kind = kind; Object.assign(this, extra); }
}

/** Bytes/s (smoothed) and seconds left for progress lines. */
export const createMeter = createRate;

function hashWorker() {
  const worker = new Worker(new URL('./hash-worker.mjs', import.meta.url), {type: 'module'});
  let id = 0; const waiting = new Map();
  worker.onmessage = ({data}) => { const w = waiting.get(data.id); waiting.delete(data.id); if (data.error) w.reject(Error(data.error)); else w.resolve(data); };
  worker.onerror = () => { for (const w of waiting.values()) w.reject(Error('The checksum worker failed.')); waiting.clear(); };
  const call = (message, transfer = []) => new Promise((resolve, reject) => { const n = ++id; waiting.set(n, {resolve, reject}); worker.postMessage({...message, id: n}, transfer); });
  return {init: state => call({op: 'init', state}), update: bytes => call({op: 'update', bytes}, [bytes.buffer]), hex: async () => (await call({op: 'hex'})).hex, close: () => worker.terminate()};
}

// Range requests at a time across all downloads: this server allows two per account (the "mod-download" limit).
function limiter(n) { let active = 0; const queue = []; return async fn => { while (active >= n) await new Promise(r => queue.push(r)); active++; try { return await fn(); } finally { active--; queue.shift()?.(); } }; }
const slots = {server: limiter(2), mega: limiter(4)};

async function checkSpace(needed) {
  try {
    const {quota, usage} = await navigator.storage.estimate();
    if (quota != null && usage != null && quota - usage < needed)
      throw new DownloadError('storage', `Not enough browser storage: this needs about ${mb(needed)}, ${mb(Math.max(0, quota - usage))} is available.`, {needed, available: quota - usage});
  } catch (e) { if (e instanceof DownloadError) throw e; }
}
/**
 * Refuse a local file up front when the browser says it cannot hold it, before the hashing of a large file. The browser
 * can still refuse the write later (wrap() and store.mjs's failure() give the same advice); a browser that gives no
 * estimate is simply tried.
 */
export async function checkFileSpace(size, name) {
  let free;
  try { const {quota, usage} = await navigator.storage.estimate(); if (quota != null && usage != null) free = quota - usage; } catch { return; }
  if (free != null && free < size) throw new DownloadError('storage', `There is not enough browser storage for ${name}: it needs ${mb(size)}, ${mb(Math.max(0, free))} is available. Delete cached mod files or free disk space, then try again.`, {needed: size, available: free});
}
export const mb = n => (n / 1048576).toFixed(n < 10 * 1048576 ? 1 : 0) + ' MB';

/**
 * A ranged reader for one catalogue file. `server`: our /api/mods/files/{id} with HTTP ranges (fetchServer is supplied
 * by the page so it carries the account header). `mega`: the catalogue's MEGA link, decrypted per range.
 */
async function openSource(entry, source, {fetchServer, fetch = globalThis.fetch, signal}) {
  if (source === 'server') {
    return {concurrency: 2, async read(start, end) {
      let r;
      try { r = await slots.server(() => fetchServer(entry.id, start, end, signal).then(async x => ({status: x.status, ok: x.ok, buffer: x.ok ? await x.arrayBuffer() : null}))); }
      catch (e) { if (e?.name === 'AbortError') throw e; throw new DownloadError('network', 'Could not reach this server.'); }
      if (r.status === 401) throw new DownloadError('auth', 'Sign in again to download from this server.');
      if (r.status === 403) throw new DownloadError('denied', 'Your account may not download this file from this server.');
      if (r.status === 404) throw new DownloadError('absent', 'This server does not offer this file.');
      if (r.status === 429) throw new DownloadError('rate', 'This server is busy with your other downloads. Retry in a moment.');
      if (r.status !== 206 && !(r.status === 200 && start === 0 && end === entry.size - 1)) throw new DownloadError('http', `The server answered HTTP ${r.status}.`);
      let bytes;
      bytes = new Uint8Array(r.buffer);
      if (bytes.length !== end - start + 1) throw new DownloadError('network', 'The server sent an incomplete block.');
      return bytes;
    }};
  }
  if (source === 'mega') {
    let info = await megaFileInfo(entry.mega, {fetch, signal});
    if (info.size !== entry.size) throw new DownloadError('changed', `The file behind the MEGA link changed (${mb(info.size)} instead of ${mb(entry.size)}); it is not the pinned ${entry.title} ${entry.version}.`);
    let decrypt = await createDecryptor(info.key), refreshed = false;
    return {concurrency: 3, name: info.name, async read(start, end) {
      try { return await decrypt(await slots.mega(() => megaRange(info.url, start, end, {fetch, signal})), start); }
      catch (e) {
        // The storage address is temporary: ask the API for a fresh one once per download.
        if (e instanceof MegaError && e.kind === 'expired' && !refreshed) { refreshed = true; info = await megaFileInfo(entry.mega, {fetch, signal}); decrypt = await createDecryptor(info.key); return decrypt(await megaRange(info.url, start, end, {fetch, signal}), start); }
        throw e;
      }
    }};
  }
  throw new DownloadError('api', 'Unknown download source.');
}

function wrap(e) {
  if (e instanceof DownloadError) return e;
  if (e?.name === 'AbortError') return new DownloadError('cancelled', 'Download paused.');
  if (e instanceof MegaError) return new DownloadError(e.kind === 'expired' ? 'network' : e.kind, e.message, {retryAfter: e.retryAfter ?? null, mega: true});
  if (e?.kind === 'storage' || e?.name === 'QuotaExceededError') return new DownloadError('storage', 'There is not enough browser storage for this file. Delete cached mod files or free disk space, then resume.');
  return new DownloadError('api', e?.message ?? String(e));
}

/** Status of an unfinished download of `entry` ({done, size, source}) or null. */
export async function pendingDownload(entry) { try { return (await getDownload(entry.sha256)) ?? null; } catch { return null; } }
export const discardDownload = entry => clearDownload(entry.sha256);

/**
 * Download `entry` from `source` ('server' | 'mega'), resuming a stored partial download (from either source: the
 * decrypted bytes are the same). onProgress({phase, done, total, rate, eta}). Resolves with the archive record.
 */
export async function downloadFile({entry, source, fetchServer, onProgress = () => {}, signal, fetch, restart = false}) {
  const report = onProgress; onProgress = p => { log(entry, p); report(p); };
  const existing = await getArchive(entry.sha256).catch(() => null);
  if (existing) return existing;
  let state = restart ? null : await pendingDownload(entry);
  if (restart) await clearDownload(entry.sha256).catch(() => {});
  const CHUNK = chunkSize(), chunks = Math.ceil(entry.size / CHUNK);
  if (state && (state.size !== entry.size || (state.chunk ?? 16 << 20) !== CHUNK)) { await clearDownload(entry.sha256).catch(() => {}); state = null; }
  if (!state) state = {sha256: entry.sha256, id: entry.id, size: entry.size, done: 0, parts: 0, hash: null, source, chunk: CHUNK, updatedUtc: new Date().toISOString()};
  const hasher = hashWorker();
  // Requests still in flight when the download stops (error or pause) are cancelled with it.
  const stop = new AbortController(), outer = signal;
  outer?.addEventListener('abort', () => stop.abort(), {once: true});
  signal = stop.signal;
  try {
    await checkSpace((entry.size - state.done) * 2 + 32 * 1048576);
    navigator.storage?.persist?.().catch(() => {});
    onProgress({phase: 'connect', done: state.done, total: entry.size, rate: 0, eta: null, source});
    const reader = await openSource(entry, source, {fetchServer, fetch, signal}).catch(e => { throw wrap(e); });
    await hasher.init(state.hash);
    const meter = createMeter(entry.size, state.done), inflight = new Map();
    let launched = state.parts;
    const range = i => [i * CHUNK, Math.min(entry.size, (i + 1) * CHUNK) - 1];
    const fetchPart = async i => {
      const [s, e] = range(i);
      for (let attempt = 0; ; attempt++) {
        try { return await reader.read(s, e); }
        catch (x) {
          // A dropped connection is retried twice with a pause; anything else (and a third failure) stops the download.
          const kind = x?.kind; if (x?.name === 'AbortError' || attempt >= 2 || !['network', 'http', 'busy', ...(source === 'server' ? ['rate'] : [])].includes(kind)) throw x;
          await new Promise(r => setTimeout(r, 1500 * (attempt + 1)));
          if (signal?.aborted) throw new DOMException('Aborted', 'AbortError');
        }
      }
    };
    for (let i = state.parts; i < chunks; i++) {
      while (launched < chunks && inflight.size < reader.concurrency) { const p = fetchPart(launched); p.catch(() => {}); inflight.set(launched++, p); }
      let bytes;
      try { bytes = await inflight.get(i); }
      catch (e) { const w = wrap(e); if (w.kind === 'network' && state.done > 0) throw new DownloadError('interrupted', `The download stopped at ${mb(state.done)} of ${mb(entry.size)}. ${w.message}`, {done: state.done}); throw w; }
      finally { inflight.delete(i); }
      const blob = new Blob([bytes]), length = bytes.length;
      const {state: hash} = await hasher.update(bytes);
      state = {...state, done: state.done + length, parts: i + 1, hash, source, updatedUtc: new Date().toISOString()};
      try { await putDownloadPart(state, i, blob); } catch (e) { throw wrap(e); }
      onProgress({phase: 'download', ...meter(state.done), source});
      if (outer?.aborted) throw new DownloadError('cancelled', 'Download paused.');
    }
    onProgress({phase: 'verify', done: entry.size, total: entry.size, rate: 0, eta: null, source});
    const hex = await hasher.hex();
    if (hex !== entry.sha256) {
      await clearDownload(entry.sha256).catch(() => {});
      throw new DownloadError('checksum', `The downloaded file does not match the pinned checksum of ${entry.title} ${entry.version}, so it was discarded.`);
    }
    const parts = await getDownloadParts(entry.sha256, chunks);
    if (parts.some(p => !(p instanceof Blob))) { await clearDownload(entry.sha256).catch(() => {}); throw new DownloadError('storage', 'Parts of the download were removed from browser storage. Download it again.'); }
    const file = new Blob(parts, {type: 'application/zip'});
    onProgress({phase: 'store', done: entry.size, total: entry.size, rate: 0, eta: null, source});
    const record = await store(entry, file, source);
    await clearDownload(entry.sha256).catch(() => {});
    return record;
  } catch (e) { throw wrap(e); }
  finally { hasher.close(); stop.abort(); }
}

async function store(entry, file, source) {
  const info = await inspectArchive(file, () => {}, {hash: false});
  const record = {sha256: entry.sha256, name: entry.file, size: file.size, title: info.title, version: info.version, hyperspace: info.hyperspace, file, addedUtc: new Date().toISOString(), source, catalogue: entry.id};
  try { await putArchive(record); } catch (e) { throw wrap(e); }
  return {...record, file: undefined};
}

/**
 * A file the player already has (any name), chosen for a catalogue entry: it is that entry (Verified) when its SHA-256 is
 * the catalogue's. Any other file is still accepted, as a Custom mod of its own: the error (kind `custom`) carries its
 * stored record and says it is not the catalogue's file.
 */
export async function importLocalFile({entry, file, onProgress = () => {}}) {
  if (file.size === entry.size) { const existing = await getArchive(entry.sha256).catch(() => null); if (existing) return existing; }
  await checkFileSpace(file.size, file.name);
  const meter = createMeter(file.size);
  const info = await inspectArchive(file, p => onProgress({phase: 'hash', ...meter(p.done)}));
  const matches = info.sha256 === entry.sha256;
  const record = matches ? {sha256: info.sha256, name: entry.file, size: file.size, title: info.title, version: info.version, hyperspace: info.hyperspace, file, addedUtc: new Date().toISOString(), source: 'local', catalogue: entry.id, verified: true}
    : {sha256: info.sha256, name: file.name, size: file.size, title: info.title, version: info.version, hyperspace: info.hyperspace, file, addedUtc: new Date().toISOString(), source: 'local', verified: false};
  if (matches || !await getArchive(info.sha256).catch(() => null)) { try { await putArchive(record); } catch (e) { throw wrap(e); } }
  if (matches) return {...record, file: undefined};
  const why = file.size !== entry.size ? `it has ${mb(file.size)}, the pinned file has ${mb(entry.size)}` : 'its checksum differs from the pinned file';
  throw new DownloadError('custom', `${file.name} is not ${entry.title} ${entry.version}: ${why}. It was added to your mods as a Custom file.`, {record: {...record, file: undefined}});
}
