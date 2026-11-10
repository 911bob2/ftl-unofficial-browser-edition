// The private folder of Hyperspace mod scripts (docs/HS_LUA.md, "Files, programs and the sandbox"): what io and os write
// stays in this browser, one folder per account, shared by every mod like the original's game folder, never synced.
// IndexedDB 'ftl-lua-files', store 'files': [account, name] -> {account, name, data (Uint8Array)}.
const DB = 'ftl-lua-files', STORE = 'files';
export const LUA_FILES_CAP = 50 * 1048576;   // the runtime's cap (hs-lua/hslibs.c VFS_CAP)
let dbPromise = null;

function database() {
  return dbPromise ??= new Promise((resolve, reject) => {
    if (!globalThis.indexedDB) return reject(Error('Browser storage is unavailable.'));
    const r = indexedDB.open(DB, 1);
    r.onupgradeneeded = () => { if (!r.result.objectStoreNames.contains(STORE)) r.result.createObjectStore(STORE); };
    r.onsuccess = () => { r.result.onversionchange = () => { r.result.close(); dbPromise = null; }; resolve(r.result); };
    r.onerror = () => { dbPromise = null; reject(r.error ?? Error('Browser storage is unavailable.')); };
    r.onblocked = () => reject(Error('Close other FTL tabs to update browser storage.'));
  });
}
const range = account => IDBKeyRange.bound([account, ''], [account, '￿']);
function tx(mode, fn) {
  return database().then(db => new Promise((resolve, reject) => {
    const t = db.transaction(STORE, mode), s = t.objectStore(STORE), out = fn(s);
    t.oncomplete = () => resolve(out instanceof IDBRequest ? out.result : out);
    t.onerror = t.onabort = () => reject(t.error ?? Error('Browser storage failed.'));
  }));
}
/** Every file of the account's folder: [{name, data}]. */
export const readLuaFiles = account => tx('readonly', s => s.getAll(range(String(account))));
/** Bytes the account's folder uses. */
export async function luaFilesUsage(account) {
  try { return (await readLuaFiles(account)).reduce((n, f) => n + (f.data?.byteLength ?? 0), 0); } catch { return 0; }
}
/** Deletes the account's folder. */
export const clearLuaFiles = account => tx('readwrite', s => s.delete(range(String(account))));
/** changes: [{kind: 1 write | 2 remove, name, data}] in order (removals first, as the runtime lists them). */
export function writeLuaFiles(account, changes) {
  account = String(account);
  return tx('readwrite', s => {
    for (const c of changes) {
      if (typeof c.name !== 'string' || !c.name || c.name.length > 255) continue;
      if (c.kind === 2) s.delete([account, c.name]);
      else if (c.data && c.data.byteLength <= LUA_FILES_CAP) s.put({account, name: c.name, data: c.data}, [account, c.name]);
    }
  });
}
