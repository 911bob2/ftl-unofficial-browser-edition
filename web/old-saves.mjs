// The browser half of the save model 2 migration (docs/SAVES.md, "Migration"): once per browser profile and account,
// the saves of the old stores become device saves. Old stores: the local-only save with its parked slots
// (IndexedDB `ftl-local-saves`, localStorage `ftl-local-save:v1`), the modded saves (`ftl-mods` stores `saves` and
// `slots`, listed in localStorage `ftl-mod-save-index:<account>`) and the pending recovery copies (`ftl-pending`).
// A copy of all of it is stored first (state `old-saves-backup`), and the old stores are left untouched until the player
// removes them from the Saves page.
import {deviceStore, getState, keepOldSettings, setState, LOCAL_ACCOUNT} from './device-store.mjs';

const BACKUP = 'old-saves-backup', MIRROR = 'ftl-local-save:v1', INDEX = 'ftl-mod-save-index:';
const OLD_DATABASES = ['ftl-local-saves', 'ftl-pending'];

/** Opens an existing database without creating it; null when it does not exist. */
async function openExisting(name) {
  try { if (indexedDB.databases && !(await indexedDB.databases()).some(d => d.name === name)) return null; } catch { /* below */ }
  return new Promise(resolve => {
    let r; try { r = indexedDB.open(name); } catch { resolve(null); return; }
    // A database that does not exist would be created by open(): abort that upgrade, so nothing is created.
    r.onupgradeneeded = () => r.transaction.abort();
    r.onsuccess = () => resolve(r.result);
    r.onerror = () => resolve(null);
    r.onblocked = () => resolve(null);
  });
}
async function readAll(db, store) {
  if (!db?.objectStoreNames.contains(store)) return [];
  return new Promise(resolve => {
    const tx = db.transaction(store, 'readonly'), s = tx.objectStore(store), keys = s.getAllKeys(), values = s.getAll();
    tx.oncomplete = () => resolve(keys.result.map((key, i) => ({key, value: values.result[i]})));
    tx.onerror = tx.onabort = () => resolve([]);
  });
}
function localStorageEntries(test) {
  const out = {};
  try { for (let i = 0; i < localStorage.length; i++) { const k = localStorage.key(i); if (test(k)) out[k] = localStorage.getItem(k); } } catch { /* none */ }
  return out;
}

/** Everything of the old save model in this browser, or null when there is none. */
export async function readOldData() {
  const local = await openExisting('ftl-local-saves'), mods = await openExisting('ftl-mods'), pending = await openExisting('ftl-pending');
  try {
    const data = {
      local: {current: await readAll(local, 'current'), slots: await readAll(local, 'slots'), history: await readAll(local, 'history')},
      mods: {saves: await readAll(mods, 'saves'), slots: await readAll(mods, 'slots')},
      pending: await readAll(pending, 'pending'),
      localStorage: localStorageEntries(k => k === MIRROR || k?.startsWith(INDEX)),
    };
    const any = data.local.current.length || data.local.slots.length || data.mods.saves.length || data.mods.slots.length || data.pending.length || Object.keys(data.localStorage).length;
    return any ? data : null;
  } finally { local?.close(); mods?.close(); pending?.close(); }
}

/** The stored copy of the old data ({at, data}) or null. */
export const oldBackup = () => getState(BACKUP).catch(() => null);

/** Removes the old stores and the stored copy (the Saves page asks first). */
export async function removeOldData() {
  for (const name of OLD_DATABASES) await new Promise(resolve => { try { const r = indexedDB.deleteDatabase(name); r.onsuccess = r.onerror = r.onblocked = () => resolve(); } catch { resolve(); } });
  const mods = await openExisting('ftl-mods');
  if (mods) {
    const stores = ['saves', 'slots'].filter(s => mods.objectStoreNames.contains(s));
    if (stores.length) await new Promise(resolve => { const tx = mods.transaction(stores, 'readwrite'); for (const s of stores) tx.objectStore(s).clear(); tx.oncomplete = tx.onerror = tx.onabort = () => resolve(); });
    mods.close();
  }
  for (const k of Object.keys(localStorageEntries(k => k === MIRROR || k?.startsWith(INDEX)))) try { localStorage.removeItem(k); } catch { /* ignore */ }
  await setState(BACKUP, undefined);
}

const validEnvelope = r => !!r && typeof r === 'object' && (r.run == null || typeof r.run === 'string') && (r.profile == null || typeof r.profile === 'string');
const uuid = () => crypto.randomUUID();

/**
 * Moves the old saves of `account` (or of local-only play) into its device store, once. `catalogue` (optional) marks
 * catalogue mods Verified; `archiveSize(sha)` gives the size of a mod file in this browser; `cloud` (signed in only)
 * = {saves(): SaveInfo[], run(id)} to attach pending progress to the save the server migrated.
 * Resolves {moved, recovered, skipped} or null when nothing was to do.
 */
export async function migrateAccount({account, core, device, catalogue = null, archiveSize = async () => 0, archiveLists = async () => [], presets = [], cloud = null}) {
  const flag = 'migrated|' + account;
  if (await getState(flag)) return null;
  const data = await readOldData();
  if (!data) { await setState(flag, new Date().toISOString()); return null; }
  // What the cloud has for the pending copy is read first: offline, nothing is written and the next start tries again.
  const pendingCloud = account !== LOCAL_ACCOUNT ? data.pending.find(p => p.key === account)?.value : null;
  const recover = validEnvelope(pendingCloud) && !!(pendingCloud.run || pendingCloud.profile) && !!cloud;
  let origin = null, originRun = null;
  if (recover) {
    origin = (await cloud.saves()).find(s => s.meta?.migratedFrom === 'slot ' + (pendingCloud.slot ?? 1)) ?? null;
    if (origin) originRun = await cloud.run(origin.id);
  }
  if (!await getState(BACKUP)) await setState(BACKUP, {at: new Date().toISOString(), data});
  const store = deviceStore(account), result = {moved: 0, recovered: 0, skipped: []};
  const meta = (envelope, at, extra = {}) => ({
    playedOn: {platform: 'browser', device: device.name, deviceId: device.id, at: at ?? new Date().toISOString()},
    engineBuild: envelope?.engineBuild ?? null, gameData: envelope?.assetVersion ?? null,
    summary: core.summary(envelope?.run ?? null, 0), migratedFrom: 'browser', ...extra});
  // The settings an old profile held become the browser's.
  const found = [];
  const withoutSettings = xml => { if (xml == null) return null; const split = core.splitOldProfile(xml); if (split.settings) found.push(split.settings); return split.profile; };
  // A save model 1 profile was shared by its slots: every save of them gets a copy.
  const save = (name, setup, run, envelope, at, created, profile = null) => ({
    id: uuid(), name: core.cleanName(name) ?? 'Save', setup, createdUtc: created ?? at ?? new Date().toISOString(), meta: meta({...envelope, run}, at),
    base: 0, dirty: true, hasRun: run != null, run: run ?? null, hasProfile: profile != null, profile, nameDirty: false, overridden: null});
  const pendingOf = key => data.pending.find(p => p.key === key)?.value;
  const newer = (stored, pending) => validEnvelope(pending) && pending.run !== stored?.run ? pending : null;

  // Local-only play: the active slot's run (IndexedDB, or the localStorage copy when it is newer) and the parked slots.
  if (account === LOCAL_ACCOUNT) {
    let current = data.local.current.find(e => e.key === 'save')?.value ?? null;
    try { const mirror = JSON.parse(data.localStorage[MIRROR] ?? 'null'); if (validEnvelope(mirror) && (!validEnvelope(current) || mirror.revision > current.revision)) current = mirror; } catch { /* no copy */ }
    const pending = newer(current, pendingOf(LOCAL_ACCOUNT));
    const active = current?.slot ?? 1, rows = data.local.slots.map(e => e.value).filter(Boolean), profile = withoutSettings(pending?.profile ?? current?.profile ?? null);
    if (validEnvelope(current) && (current.run || pending?.run || profile || rows.some(r => r.slot === active))) {
      const row = rows.find(r => r.slot === active), run = pending?.run ?? current.run ?? null;
      await store.put(save(row?.name ?? 'Save ' + active, {key: 'vanilla', mods: []}, run, current, current.savedUtc, row?.createdUtc, profile)); result.moved++;
    }
    for (const r of rows) if (r.slot !== active) { await store.put(save(r.name ?? 'Save ' + r.slot, {key: 'vanilla', mods: []}, r.run ?? null, r, r.playedUtc, r.createdUtc, profile)); result.moved++; }
  }

  // Modded saves of this account, one setup per mod set.
  let index = [];
  try { index = JSON.parse(data.localStorage[INDEX + account] ?? '[]'); } catch { index = []; }
  const lists = await archiveLists(account).catch(() => []);
  async function setupFor(hash, names) {
    let mods = index.find(e => e.hash === hash)?.mods ?? null;
    if (!mods && lists.length) { const shas = lists.filter(e => e.enabled).map(e => e.sha256); if (shas.length && core.modSetHash(shas) === hash) mods = shas.map((sha256, i) => ({name: names?.[i] ?? sha256.slice(0, 12), sha256})); }
    if (!mods) mods = presets.find(p => p.hash === hash)?.mods ?? null;
    if (!mods?.length) return null;
    const out = [];
    for (const m of mods) {
      const size = await archiveSize(m.sha256).catch(() => 0) || 0;
      const known = catalogue ? core.classify(catalogue, m.name ?? m.sha256, m.sha256, size) : null;
      out.push({name: known?.verified ? known.name : m.name ?? m.sha256.slice(0, 12), version: known?.verified ? known.version : m.version ?? '', sha256: m.sha256, size, verified: !!known?.verified});
    }
    const setup = {key: core.modSetHash(out.map(m => m.sha256)), mods: out};
    return setup.key === hash ? setup : null;
  }
  const prefix = account + '|';
  for (const {key, value} of data.mods.saves) {
    if (typeof key !== 'string' || !key.startsWith(prefix) || key.split('|').length !== 2 || !validEnvelope(value)) continue;
    const hash = key.slice(prefix.length), setup = await setupFor(hash, value.mods);
    const rows = data.mods.slots.filter(e => typeof e.key === 'string' && e.key.startsWith(key + '|')).map(e => e.value).filter(Boolean);
    if (!setup) { result.skipped.push(hash); continue; }
    const pending = newer(value, pendingOf(account + '|mod|' + hash)), active = value.slot ?? 1, row = rows.find(r => r.slot === active), profile = withoutSettings(pending?.profile ?? value.profile ?? null);
    await store.put(save(row?.name ?? 'Save ' + active, setup, pending?.run ?? value.run ?? null, value, value.updatedUtc, row?.createdUtc, profile)); result.moved++;
    for (const r of rows) if (r.slot !== active) { await store.put(save(r.name ?? 'Save ' + r.slot, setup, r.run ?? null, r, r.playedUtc, r.createdUtc, profile)); result.moved++; }
  }

  // The cloud save's pending recovery copy: the unsynced progress of the save the server migrated from that slot.
  // Progress newer than the save the server migrated from that slot becomes that save's unsynced progress here (it
  // uploads as its next revision; the older one stays a recovery snapshot). No such save: a save of its own.
  if (recover) {
    const pendingProfile = withoutSettings(pendingCloud.profile);
    const newRun = pendingCloud.run && originRun?.run !== pendingCloud.run, newProfile = pendingProfile && originRun?.profile !== pendingProfile;
    if (origin && (newRun || newProfile)) {
      let kept = core.fetched(null, originRun.cloud, originRun.run, originRun.profile);
      if (newRun) kept = core.written(kept, pendingCloud.run, meta(pendingCloud, new Date().toISOString()));
      if (newProfile) kept = core.profileWritten(kept, pendingProfile) ?? kept;
      await store.put(kept); result.recovered++;
    } else if (!origin) { await store.put(save('Recovered progress', {key: 'vanilla', mods: []}, pendingCloud.run ?? null, pendingCloud, new Date().toISOString(), undefined, pendingProfile)); result.recovered++; }
  }
  keepOldSettings(found);
  await setState(flag, new Date().toISOString());
  return result;
}
