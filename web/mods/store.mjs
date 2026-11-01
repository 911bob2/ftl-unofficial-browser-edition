// Browser-local mod storage (IndexedDB 'ftl-mods'). Nothing here talks to the server: archives, the ordered library and
// cached merges stay in this browser profile (custom archives never upload).
//   archives: sha256 -> {sha256, name, size, title, version, hyperspace, file (Blob), addedUtc, verified, catalogue}   (shared by accounts)
//   lists:    account -> [{sha256, enabled}] in load order
//   merges:   cache key -> {key, created, text: [[name, bytes]], refs: [[name, archive, entry]], removed, warnings}
//   saves, slots: the modded saves of the old save model, read once by web/old-saves.mjs (saves are now device saves)
//   downloads: sha256 -> {sha256, id, size, done, parts, hash (resumable SHA-256 state), source, updatedUtc}
//   parts:    sha256|index -> Blob of decrypted bytes [index * chunk, ...) of an unfinished download
const DB = 'ftl-mods', STORES = ['archives', 'lists', 'merges', 'saves', 'slots', 'downloads', 'parts'];
let dbPromise;

function database() {
  return dbPromise ??= new Promise((resolve, reject) => {
    if (!globalThis.indexedDB) return reject(Error('Browser storage is unavailable.'));
    const r = indexedDB.open(DB, 2);
    r.onupgradeneeded = () => { for (const s of STORES) if (!r.result.objectStoreNames.contains(s)) r.result.createObjectStore(s); };
    r.onsuccess = () => { r.result.onversionchange = () => { r.result.close(); dbPromise = null; }; resolve(r.result); };
    r.onerror = () => { dbPromise = null; reject(r.error ?? Error('Browser storage is unavailable.')); };
    r.onblocked = () => reject(Error('Close other FTL tabs to update browser storage.'));
  });
}

const failure = tx => tx.error?.name === 'QuotaExceededError' ? Object.assign(Error('There is not enough browser storage for this mod. Free up disk space or delete cached mod files, then try again. A private window has much less room: use a regular one.'), {name: 'QuotaExceededError', kind: 'storage'}) : tx.error ?? Error('Browser storage failed.');
async function run(store, mode, fn) {
  const db = await database();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(store, mode), s = tx.objectStore(store), r = fn(s);
    tx.oncomplete = () => resolve(r?.result);
    tx.onerror = tx.onabort = () => reject(failure(tx));
  });
}
/** One transaction over several stores; `fn(stores)` may return a request whose result is resolved. */
export async function transaction(names, mode, fn) {
  const db = await database();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(names, mode), stores = Object.fromEntries(names.map(n => [n, tx.objectStore(n)]));
    let out; try { out = fn(stores, tx); } catch (e) { tx.abort(); reject(e); return; }
    tx.oncomplete = () => resolve(out instanceof IDBRequest ? out.result : out);
    tx.onerror = tx.onabort = () => reject(out?.failure ?? failure(tx));
  });
}
const get = (store, key) => run(store, 'readonly', s => s.get(key));
const put = (store, key, value) => run(store, 'readwrite', s => s.put(value, key));
const del = (store, key) => run(store, 'readwrite', s => s.delete(key));
const keys = store => run(store, 'readonly', s => s.getAllKeys());

export const getArchive = sha => get('archives', sha);
export const putArchive = record => put('archives', record.sha256, record);
export const getList = async account => (await get('lists', account)) ?? [];
export const setList = (account, list) => put('lists', account, list.map(({sha256, enabled}) => ({sha256, enabled: !!enabled})));
export const getMerge = key => get('merges', key);
export const putMerge = (key, value) => put('merges', key, value);
export const listArchives = () => run('archives', 'readonly', s => s.getAll());
export const getDownload = sha => get('downloads', sha);
export const listDownloads = () => run('downloads', 'readonly', s => s.getAll());
/** Store one finished chunk and the download's new state together, so a reload resumes exactly after it. */
export const putDownloadPart = (state, index, blob) => transaction(['downloads', 'parts'], 'readwrite', s => { s.parts.put(blob, state.sha256 + '|' + index); s.downloads.put(state, state.sha256); });
export async function getDownloadParts(sha, count) {
  const out = await transaction(['parts'], 'readonly', s => { const list = []; for (let i = 0; i < count; i++) { const r = s.parts.get(sha + '|' + i); list.push(r); } return {list}; });
  return out.list.map(r => r.result);
}
export const clearDownload = sha => transaction(['downloads', 'parts'], 'readwrite', s => { s.downloads.delete(sha); s.parts.delete(IDBKeyRange.bound(sha + '|', sha + '|~')); }); // '~' sorts after the decimal part indexes

/** The accounts in this browser whose mod list includes the archive. */
export async function archiveUsers(sha) {
  const out = [];
  for (const k of await keys('lists')) if ((await get('lists', k) ?? []).some(e => e.sha256 === sha)) out.push(k);
  return out;
}
/**
 * Remove an archive from this account's list. Its bytes are deleted only when no other account in this browser lists
 * it; resolves {deleted, others} (others: the accounts that still list it).
 */
export async function removeArchive(account, sha) {
  await setList(account, (await getList(account)).filter(e => e.sha256 !== sha));
  const others = await archiveUsers(sha);
  if (others.length) return {deleted: false, others};
  await del('archives', sha);
  return {deleted: true, others};
}
/**
 * Delete an archive's bytes from this browser for every account: each list that names it drops it (the file is shared,
 * the lists are per account). Resolves the accounts whose list changed.
 */
export async function deleteArchive(sha) {
  const changed = [];
  for (const k of await keys('lists')) {
    const list = await get('lists', k) ?? [];
    if (list.some(e => e.sha256 === sha)) { await put('lists', k, list.filter(e => e.sha256 !== sha)); changed.push(k); }
  }
  await del('archives', sha);
  await clearDownload(sha).catch(() => {});
  return changed;
}

/** Keep only the newest `keep` cached merges. */
export async function pruneMerges(keep = 2) {
  const all = [];
  for (const k of await keys('merges')) { const m = await get('merges', k); all.push([k, m?.created ?? 0]); }
  all.sort((a, b) => b[1] - a[1]);
  for (const [k] of all.slice(keep)) await del('merges', k);
}
