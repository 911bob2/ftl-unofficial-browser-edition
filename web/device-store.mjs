// The device copy of every save (docs/SAVES.md, "The device first"): IndexedDB `ftl-saves`, written before anything
// goes to the network. Every change of a save is one transaction over its metadata, run and profile, so a closed tab or
// a full disk leaves the previous or the new version, never half of one.
//   saves:        account|id       -> LocalSave without its run and profile (savecore's shape)
//   runs:         account|id       -> the run XML
//   saveProfiles: account|id       -> the save's profile XML (unlocks, scores, statistics, Hyperspace data)
//   profiles:     account|setupKey -> a setup's old shared profile, until mergeProfiles moved it into its saves
//   state:        key              -> small records (lock releases still owed, the migrations and the backup of old data)
// Accounts are kept apart; local-only play uses the account `local-device`. The settings (language, volumes, hotkeys)
// belong to the browser, not to an account or save: localStorage `ftl-settings`.
export const LOCAL_ACCOUNT = 'local-device';
const DB = 'ftl-saves', STORES = ['saves', 'runs', 'saveProfiles', 'profiles', 'state'], DATA = ['saves', 'runs', 'saveProfiles'];
const SETTINGS = 'ftl-settings';
let dbPromise = null;

function database() {
  return dbPromise ??= new Promise((resolve, reject) => {
    if (!globalThis.indexedDB) return reject(Error('Browser storage is unavailable.'));
    let r; try { r = indexedDB.open(DB, 2); } catch (e) { return reject(e); }
    r.onupgradeneeded = () => { for (const s of STORES) if (!r.result.objectStoreNames.contains(s)) r.result.createObjectStore(s); };
    r.onsuccess = () => { r.result.onversionchange = () => { r.result.close(); dbPromise = null; }; r.result.onclose = () => { dbPromise = null; }; resolve(r.result); };
    r.onerror = () => reject(r.error ?? Error('Browser storage is unavailable.'));
    r.onblocked = () => reject(Error('Close other FTL tabs to update browser storage.'));
  }).catch(e => { dbPromise = null; throw e; });
}

function storageError(tx, failure) {
  if (failure) return failure;
  const e = tx.error;
  if (e?.name === 'QuotaExceededError') return Object.assign(Error('This browser has no room left for the save. Free up disk space or download a backup, then save again.'), {name: 'QuotaExceededError', storage: true});
  return Object.assign(Error('This browser could not store the save' + (e?.message ? ': ' + e.message : '.')), {storage: true});
}
/** One transaction; `fn(stores)` runs inside it and may set `out.value` or throw to abort. */
async function transact(names, mode, fn) {
  const db = await database();
  return new Promise((resolve, reject) => {
    let tx; try { tx = db.transaction(names, mode); } catch (e) { reject(e); return; }
    const stores = Object.fromEntries(names.map(n => [n, tx.objectStore(n)])), out = {value: undefined, failure: null};
    out.abort = e => { out.failure = e; try { tx.abort(); } catch { /* already finished */ } };
    try { fn(stores, out); } catch (e) { out.abort(e); }
    tx.oncomplete = () => resolve(out.value);
    tx.onerror = tx.onabort = () => reject(storageError(tx, out.failure));
  });
}
const range = account => IDBKeyRange.bound(account + '|', account + '|￿');
const keyOf = (account, id) => account + '|' + id;
const playedAt = s => s.meta?.playedOn?.at || s.createdUtc || '';

let channel = null; try { channel = new BroadcastChannel('ftl-saves'); } catch { /* single tab */ }
const listeners = new Set();
/** Called with the account after any change to its saves, here or in another tab of this browser. */
export function onStoreChange(listener) { listeners.add(listener); return () => listeners.delete(listener); }
function changed(account, local = true) {
  for (const l of listeners) { try { l(account, local); } catch { /* a listener's problem */ } }
  if (local) try { channel?.postMessage({type: 'saves', account}); } catch { /* ignore */ }
}
if (channel) channel.onmessage = ({data}) => { if (data?.type === 'saves') changed(data.account, false); };

/** The device's settings the engine wrote (the original's settings.ini), or null. */
export function readSettings() { try { return localStorage.getItem(SETTINGS); } catch { return null; } }
export function writeSettings(xml) { try { localStorage.setItem(SETTINGS, xml); } catch { /* the next start uses the defaults */ } }

/** The store of one account. */
export function deviceStore(account) {
  const write = (s, save) => {
    const key = keyOf(account, save.id);
    s.saves.put({...save, run: null, profile: null}, key);
    if (save.run != null) s.runs.put(save.run, key); else s.runs.delete(key);
    if (save.profile != null) s.saveProfiles.put(save.profile, key); else s.saveProfiles.delete(key);
  };
  const read = (s, id, done) => {
    const a = s.saves.get(keyOf(account, id)), b = s.runs.get(keyOf(account, id)), c = s.saveProfiles.get(keyOf(account, id));
    c.onsuccess = () => done(a.result ? withData(a.result, b.result, c.result) : null);
  };
  const store = {
    account,
    /** Every save of the account without its run and profile, last played first. */
    async list() {
      const rows = await transact(['saves'], 'readonly', (s, out) => { const r = s.saves.getAll(range(account)); r.onsuccess = () => { out.value = r.result; }; });
      return (rows ?? []).sort((a, b) => playedAt(b).localeCompare(playedAt(a)));
    },
    /** The save with its run and profile, or null. */
    async get(id) {
      return transact(DATA, 'readonly', (s, out) => read(s, id, save => { out.value = save; }));
    },
    async put(save) { await transact(DATA, 'readwrite', s => write(s, save)); changed(account); },
    /**
     * Reads, changes and writes one save in one transaction, so the game and the syncer never overwrite each other.
     * `change(save)` is synchronous and returns the new save or null (no change). Resolves the stored save or null.
     */
    async update(id, change) {
      let wrote = false;
      const result = await transact(DATA, 'readwrite', (s, out) => read(s, id, current => {
        if (!current) { out.value = null; return; }
        let next; try { next = change(current); } catch (e) { out.abort(e); return; }
        if (!next) { out.value = current; return; }
        write(s, next); wrote = true; out.value = next;
      }));
      if (wrote) changed(account);
      return result;
    },
    async remove(id) { await transact(DATA, 'readwrite', s => { for (const n of DATA) s[n].delete(keyOf(account, id)); }); changed(account); },
    /** Saves whose lock this browser still has to give back (closed with Save and close while offline). */
    async releases() { return new Set(await getState('releases|' + account) ?? []); },
    async setRelease(id, pending) {
      await transact(['state'], 'readwrite', s => {
        const r = s.state.get('releases|' + account);
        r.onsuccess = () => { const set = new Set(r.result ?? []); if (pending) set.add(id); else set.delete(id); s.state.put([...set], 'releases|' + account); };
      });
    },
    /** Everything of this account in this browser goes (account deletion). */
    async clear() {
      await transact(STORES, 'readwrite', s => { for (const n of [...DATA, 'profiles']) s[n].delete(range(account)); s.state.delete('releases|' + account); });
      changed(account);
    },
  };
  return store;
}
const withData = (save, run, profile) => ({...save, run: save.hasRun && typeof run === 'string' ? run : null, hasRun: !!save.hasRun && typeof run === 'string',
  profile: save.hasProfile && typeof profile === 'string' ? profile : null, hasProfile: !!save.hasProfile && typeof profile === 'string'});

/**
 * Before a save held its profile each setup had one: every save gets a copy, unsynced when that profile was. A vanilla
 * profile with no save (and no account copy of it) becomes a save of its own. The settings it held become the browser's.
 * Once per account.
 */
export async function mergeProfiles(account, core) {
  const flag = 'profiles-merged|' + account;
  let moved = false; const found = [];
  await transact(STORES, 'readwrite', s => {
    const done = s.state.get(flag), saves = s.saves.getAll(range(account)), old = s.profiles.getAll(range(account));
    old.onsuccess = () => {
      if (done.result) return;
      const profiles = new Map(old.result.map(p => [p.setupKey, p]));
      const copyOf = key => {
        const p = profiles.get(key); if (!p) return null;
        const dirty = p.current != null && p.current !== p.engineBase, xml = dirty ? p.current : p.cloud ?? p.current;
        if (xml == null) return null;
        const split = core.splitOldProfile(xml);
        if (split.settings) found.push(split.settings);
        return {xml: split.profile, dirty};
      };
      for (const save of saves.result) {
        const copy = copyOf(save.setup.key);
        if (!copy || save.hasProfile) continue;
        s.saves.put({...save, hasProfile: true, dirty: !!save.dirty || copy.dirty}, keyOf(account, save.id));
        s.saveProfiles.put(copy.xml, keyOf(account, save.id)); moved = true;
      }
      const vanilla = copyOf('vanilla'), keys = new Set(saves.result.map(save => save.setup.key));
      if (vanilla && !keys.has('vanilla') && saves.result.length < 30 && (vanilla.dirty || account === LOCAL_ACCOUNT)) {
        const id = crypto.randomUUID();
        s.saves.put({id, name: 'Save 1', setup: {key: 'vanilla', mods: []}, createdUtc: new Date().toISOString(), meta: {}, base: 0, dirty: true, hasRun: false, run: null,
          hasProfile: true, profile: null, nameDirty: false, overridden: null}, keyOf(account, id));
        s.saveProfiles.put(vanilla.xml, keyOf(account, id)); moved = true; keys.add('vanilla');
      }
      for (const key of profiles.keys()) copyOf(key);
      for (const key of keys) s.profiles.delete(keyOf(account, key));
      s.state.put(new Date().toISOString(), flag);
    };
  });
  if (found.length) keepOldSettings(found);
  if (moved) changed(account);
}
/** The settings out of old profiles, in a browser that has none yet: one with a language first. */
export function keepOldSettings(found) { if (!readSettings()) { const pick = found.find(s => s.includes(' language="')) ?? found[0]; if (pick) writeSettings(pick); } }

/** Moves saves (with their runs and profiles) from one account's store to another's, in one transaction; resolves the moved ids. */
export async function moveSaves(from, to, ids, change = s => s) {
  const moved = [];
  await transact(DATA, 'readwrite', s => {
    for (const id of ids) {
      const a = s.saves.get(keyOf(from, id)), b = s.runs.get(keyOf(from, id)), c = s.saveProfiles.get(keyOf(from, id));
      c.onsuccess = () => {
        if (!a.result) return;
        const next = change(withData(a.result, b.result, c.result));
        s.saves.put({...next, run: null, profile: null}, keyOf(to, next.id));
        if (next.run != null) s.runs.put(next.run, keyOf(to, next.id));
        if (next.profile != null) s.saveProfiles.put(next.profile, keyOf(to, next.id));
        for (const n of DATA) s[n].delete(keyOf(from, id));
        moved.push(next.id);
      };
    }
  });
  changed(from); changed(to);
  return moved;
}

export async function getState(key) {
  return transact(['state'], 'readonly', (s, out) => { const r = s.state.get(key); r.onsuccess = () => { out.value = r.result; }; });
}
export async function setState(key, value) {
  await transact(['state'], 'readwrite', s => { if (value === undefined) s.state.delete(key); else s.state.put(value, key); });
}

export async function requestPersistence() { try { return !!await navigator.storage?.persist?.(); } catch { return false; } }
export async function storageStatus() {
  const status = {supported: typeof navigator.storage?.persist === 'function', persisted: null, usage: null};
  try { status.persisted = await navigator.storage.persisted(); } catch { /* unknown */ }
  try { status.usage = (await navigator.storage.estimate()).usage ?? null; } catch { /* unknown */ }
  return status;
}
// estimate().quota is the most this site may use (Chrome: a share of the whole disk), not free space, so only usage is shown.
export function describeStorage(status) {
  const lead = !status.supported ? 'This browser cannot keep site data persistent. Download backups.'
    : status.persisted ? 'Persistent storage granted.'
    : 'Persistent storage not granted: the browser may clear this site’s data when the disk is low. Download backups.';
  return lead + (status.usage == null ? '' : ' This site uses ' + (status.usage / 1024 / 1024).toFixed(1) + ' MiB.');
}
