// The page's saves (docs/SAVES.md): the device store, the syncer and what the Saves and Play pages show, for one
// account (or local-only play). Everything the engine writes goes to the device store first (web/game-session.mjs); the
// syncer (web/sync.mjs) carries it to the account in the background with savecore's rules (web/savecore.mjs).
import {loadSaveCore, setupOf, SAVES_LIMIT} from './savecore.mjs';
import {deviceStore, keepOldSettings, LOCAL_ACCOUNT, moveSaves, onStoreChange} from './device-store.mjs';
import {createLocalSync, createSync} from './sync.mjs';
import {claimInstance, device, saveHeaders} from './device.mjs';
import {words} from './save-words.mjs';

const CURRENT = 'ftl-current-save:';
const newId = () => crypto.randomUUID();

/**
 * api(path, options): the page's API call (throws errors with status, code and detail). local: local-only play.
 * Resolves the service; `ready` is already settled.
 */
export async function createSaveService({account, local, api, version = ''}) {
  const [core] = await Promise.all([loadSaveCore(version), claimInstance()]);
  const me = device(), store = deviceStore(local ? LOCAL_ACCOUNT : account), storeAccount = store.account;
  const call = (path, {method = 'GET', body, headers = {}} = {}) => api(path, {method, body, headers: {...saveHeaders(), ...headers}, saveCall: true});
  const sync = local ? createLocalSync({core, store, account: storeAccount, deviceId: me.id}) : createSync({core, store, call, instance: saveHeaders()['X-FTL-Instance'], deviceId: me.id});
  const listeners = new Set();
  const notify = () => { for (const l of listeners) { try { l(); } catch { /* listener */ } } };
  sync.on(notify);
  onStoreChange(a => { if (a === storeAccount) { sync.refresh?.().catch(() => {}); notify(); } });

  /** The saves the page lists: the device copy, the cloud's, or both (as the Android launcher's Row). */
  async function rows() {
    const locals = await store.list(), cloud = sync.state.cloud, out = [];
    for (const id of new Set([...locals.map(s => s.id), ...cloud.keys()])) {
      const l = locals.find(s => s.id === id) ?? null, c = cloud.get(id) ?? null;
      if (!l && sync.state.deleted.has(id)) continue;
      const meta = c && (c.revision > (l?.base ?? 0)) && !l?.dirty ? c.meta : l?.meta ?? c?.meta ?? {};
      const decision = sync.state.decisions.get(id) ?? sync.decide(l, c, sync.state.deleted.has(id));
      const lock = c?.lock ?? null, lockState = sync.lockState(lock);
      out.push({id, local: l, cloud: c, name: l?.name ?? c.name, setup: l?.setup ?? c.setup, meta, decision, error: sync.state.errors.get(id) ?? null,
        hasRun: !!(l?.hasRun || c?.hasRun), playedAt: meta?.playedOn?.at || l?.createdUtc || c?.createdUtc || '',
        lock: lockState === 'OTHER' ? lock : null, lockMine: lockState === 'MINE'});
    }
    return out.sort((a, b) => b.playedAt.localeCompare(a.playedAt));
  }

  function currentChoices() { try { return JSON.parse(localStorage.getItem(CURRENT + storeAccount) ?? '{}') ?? {}; } catch { return {}; } }
  /** The save Launch FTL plays for a setup: the one last chosen, else the one played last. */
  async function currentFor(setupKey, list = null) {
    list ??= await rows();
    const mine = list.filter(r => r.setup.key === setupKey), chosen = currentChoices()[setupKey];
    return mine.find(r => r.id === chosen) ?? mine[0] ?? null;
  }
  function choose(setupKey, id) {
    const all = currentChoices(); all[setupKey] = id;
    try { localStorage.setItem(CURRENT + storeAccount, JSON.stringify(all)); } catch { /* the last played one stays current */ }
  }

  /** A new empty save (or one with `run` and `profile`) on this device; the syncer uploads it. */
  async function create(setup, name, {run = null, profile = null, meta = {}} = {}) {
    const list = await rows();
    if (list.length >= SAVES_LIMIT) throw Error(words('savesFull', {count: SAVES_LIMIT}));
    const clean = core.cleanName(name ?? '') ?? core.nextName(list.map(r => r.name));
    const now = new Date().toISOString();
    const save = {id: newId(), name: clean, setup, createdUtc: now, meta: {...meta, summary: core.summary(run, 0), playedOn: {platform: 'browser', device: me.name, deviceId: me.id, at: now}},
      base: 0, dirty: true, hasRun: run != null, run, hasProfile: profile != null, profile, nameDirty: false, overridden: null};
    await store.put(save);
    choose(setup.key, save.id);
    sync.requestSync();
    return save;
  }

  /** The run and profile of a save, from this device or (downloaded) from the cloud. */
  async function dataOf(id) {
    const l = await store.get(id), c = sync.state.cloud.get(id);
    if (!sync.cloudEnabled || l && (l.hasRun || !c?.hasRun) && (l.hasProfile || !c?.hasProfile)) return {run: l?.run ?? null, profile: l?.profile ?? null};
    const {run, profile} = await sync.fetchCloud(id);
    return {run, profile};
  }

  /** A backup of one save ({schema: 3, ...}); what this device lacks comes from the cloud. */
  async function backup(id) {
    const l = await store.get(id), c = sync.state.cloud.get(id) ?? null, s = l ?? c;
    return {schema: 3, kind: 'ftl-save', name: s.name, setup: s.setup, meta: l?.meta ?? c?.meta ?? {}, ...await dataOf(id), savedUtc: new Date().toISOString()};
  }
  /** Every save of the account in one file (account deletion offers it). */
  async function backupAll() {
    const list = await rows(), saves = [];
    for (const r of list) saves.push(await backup(r.id).catch(() => ({schema: 3, kind: 'ftl-save', name: r.name, setup: r.setup, meta: r.meta, run: null, profile: null})));
    return {schema: 3, kind: 'ftl-saves', savedUtc: new Date().toISOString(), saves};
  }
  /**
   * Imports a backup as a new save: this page's own backups (schema 3, and 2 without a profile) and the backups of the
   * old save model (schema 1, {run, profile, engineBuild, assetVersion}: a save without mods).
   */
  async function importBackup(text) {
    let value; try { value = JSON.parse(text); } catch { throw Error('This file is not a save backup.'); }
    const run = typeof value?.run === 'string' ? value.run : null;
    if (run != null && !/^\s*(<\?xml[^>]*>\s*)?<xftlSaveGame[\s>]/.test(run)) throw Error('This file is not a save backup.');
    let profile = typeof value?.profile === 'string' ? value.profile : null;
    if (profile != null && !/^\s*(<\?xml[^>]*>\s*)?<xftl-profile[\s/>]/.test(profile)) throw Error('This file is not a save backup.');
    // A backup from before the settings left the profile.
    if (profile != null) { const split = core.splitOldProfile(profile); profile = split.profile; if (split.settings) keepOldSettings([split.settings]); }
    if ((value?.schema === 3 || value?.schema === 2) && value.kind === 'ftl-save') {
      const setup = value.setup?.mods?.length ? setupOf(core, value.setup.mods) : {key: 'vanilla', mods: []};
      return create(setup, value.name, {run, profile, meta: {engineBuild: value.meta?.engineBuild ?? null, gameData: value.meta?.gameData ?? null, importedUtc: new Date().toISOString()}});
    }
    if (value?.schema === 1 && 'run' in value) return create({key: 'vanilla', mods: []}, 'Imported save', {run, profile, meta: {engineBuild: value.engineBuild ?? null, gameData: value.assetVersion ?? null, importedUtc: new Date().toISOString()}});
    throw Error('This file is not a save backup.');
  }

  /** The saves of local-only play in this browser, moved into this account (Enable cloud saves); they upload next. */
  async function adoptLocalSaves() {
    if (local) return 0;
    const saves = await deviceStore(LOCAL_ACCOUNT).list();
    const moved = await moveSaves(LOCAL_ACCOUNT, storeAccount, saves.map(s => s.id), s => ({...s, base: 0, dirty: true, overridden: null}));
    await sync.syncAll();
    return moved.length;
  }
  const localSaveCount = async () => local ? 0 : (await deviceStore(LOCAL_ACCOUNT).list()).length;

  return {core, store, sync, device: me, account: storeAccount, local, rows, currentFor, choose, create, dataOf, backup, backupAll, importBackup, adoptLocalSaves, localSaveCount,
    on(listener) { listeners.add(listener); return () => listeners.delete(listener); }, notify, setupOf: mods => setupOf(core, mods), runForSetup: (run, key) => core.runForSetup(run, key)};
}
