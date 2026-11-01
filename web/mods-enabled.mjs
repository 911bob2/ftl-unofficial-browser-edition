// Which mods the Play page's one Launch button starts with, read without loading mod code. The Mods panel mirrors each
// account's enabled archives (sha256 in load order) into localStorage on every change; a library from before the
// mirror existed is read once from IndexedDB, only when that database already exists (opening it would create it).
// No mirror and no database: no mods, and Launch is the vanilla launch.
const key = account => 'ftl-mods-enabled:' + account;

export function readEnabled(account) {
  try { const v = JSON.parse(localStorage.getItem(key(account)) ?? 'null'); return Array.isArray(v) ? v.filter(s => typeof s === 'string') : null; } catch { return null; }
}
export function writeEnabled(account, shas) { try { localStorage.setItem(key(account), JSON.stringify(shas)); } catch { /* label falls back to the database */ } }

/** The enabled archives (sha256, in order) for `account`: the mirror, or the stored list when there is no mirror yet. */
export async function enabledMods(account) {
  const mirror = readEnabled(account);
  if (mirror) return mirror;
  let names = null;
  try { names = (await indexedDB.databases()).map(d => d.name); } catch { return []; }
  if (!names.includes('ftl-mods')) return [];
  const db = await new Promise(resolve => { try { const r = indexedDB.open('ftl-mods'); r.onsuccess = () => resolve(r.result); r.onerror = () => resolve(null); } catch { resolve(null); } });
  if (!db) return [];
  try {
    if (!db.objectStoreNames.contains('lists')) return [];
    const list = await new Promise(resolve => { const tx = db.transaction('lists', 'readonly'), r = tx.objectStore('lists').get(account); tx.oncomplete = () => resolve(r.result ?? []); tx.onerror = tx.onabort = () => resolve([]); });
    const shas = list.filter(e => e.enabled).map(e => e.sha256);
    writeEnabled(account, shas);
    return shas;
  } finally { db.close(); }
}
