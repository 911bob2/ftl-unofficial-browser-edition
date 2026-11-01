// Background sync between the device store and the account (docs/SAVES.md), the browser twin of the Android app's
// SyncEngine. The game never waits for it: saves are on the device already; this uploads them when it can, takes and
// refreshes locks, fetches what the cloud has newer, and leaves the questions (conflicts, deletions elsewhere,
// overrides) to the page. Every rule comes from savecore (`core`); this module only carries the decisions out.
//
// createSync({core, store, call, instance, deviceId}): `store` is a device store (web/device-store.mjs), `call(path,
// {method, body, headers})` an API call that adds X-FTL-Instance/X-FTL-Device and throws errors with `status`, `code`
// and `detail` (the JSON body) for HTTP failures and without a status when the network failed.
export const offline = e => !e?.status;

/**
 * The override (its time) the player has been told about, per save, for this tab's game instance. The server repeats an
 * override (409 lock_overridden) until the instance holds the lock again; once told, the save is simply in use there.
 */
export function seenOverrides(instance) {
  const key = 'ftl-overrides:' + instance, memory = new Map();
  const read = () => { try { return JSON.parse(sessionStorage.getItem(key) ?? '{}') ?? {}; } catch { return {}; } };
  return {
    get: id => read()[id] ?? memory.get(id) ?? null,
    set(id, at) { memory.set(id, at); try { sessionStorage.setItem(key, JSON.stringify({...read(), [id]: at})); } catch { /* this page only */ } },
  };
}

/** The lock another tab of this browser holds (its game's Web Lock; web/game-session.mjs). */
export const anotherTab = deviceId => ({instance: 'another-tab', deviceId, device: 'another tab of this browser', platform: 'browser', since: '', lastSeen: ''});

export function createSync({core, store, call, instance, deviceId, now = () => new Date().toISOString(), delay = 3000, seen = seenOverrides(instance)}) {
  const listeners = new Set(), overrideListeners = new Set();
  const state = {online: true, syncing: false, listed: false, cloud: new Map(), deleted: new Map(), decisions: new Map(), errors: new Map(), error: null, lastSync: null, limits: null, usedBytes: null};
  let queue = Promise.resolve(), playing = null, timer = null, pendingId = null, scheduled = null;
  const emit = () => { for (const l of listeners) { try { l(state); } catch { /* listener */ } } };
  /** Passes and the calls the page makes run one at a time, as on Android (one mutex). */
  const exclusive = fn => { const run = queue.then(fn, fn); queue = run.catch(() => {}); return run; };
  const headers = revision => revision == null ? {} : {'If-Match': `"${revision}"`};
  const path = id => '/saves/' + encodeURIComponent(id);
  const sameDevice = lock => !!lock && lock.deviceId === deviceId;

  function wentOffline(e) {
    state.online = false;
    state.error = e?.status === 401 ? 'Signed out: saves stay in this browser and sync after you sign in again.' : 'Offline: saves stay in this browser and sync later.';
  }

  async function list() {
    const answer = await call('/saves');
    state.cloud = new Map(answer.saves.map(s => [s.id, s]));
    state.deleted = new Map((answer.deleted ?? []).map(d => [d.id, d.deletedUtc]));
    state.limits = answer.limits ?? null; state.usedBytes = answer.usedBytes ?? null;
    state.listed = true;
    return answer;
  }
  /**
   * savecore's decision, once the account's list has been read. Before that (offline since the page opened) the cloud
   * is unknown, not empty: a save here is only new, unsynced or as last synced.
   */
  function decide(local, cloud, deleted) {
    if (state.listed || !local) return core.decide(local, cloud, deleted);
    if (local.overridden) return {status: 'OVERRIDDEN', action: 'NOTIFY_OVERRIDDEN', rename: false};
    if (!local.base) return {status: 'LOCAL', action: 'NONE', rename: false};
    return {status: local.dirty ? 'PENDING' : 'SYNCED', action: 'NONE', rename: false};
  }
  async function recompute() {
    const locals = new Map((await store.list()).map(s => [s.id, s])), decisions = new Map();
    for (const id of new Set([...locals.keys(), ...state.cloud.keys()])) decisions.set(id, decide(locals.get(id) ?? null, state.cloud.get(id) ?? null, state.deleted.has(id)));
    state.decisions = decisions;
  }

  function body(save, kind) { return {name: save.name, setup: save.setup, meta: save.meta ?? {}, run: save.run ?? null, profile: save.profile ?? null, kind}; }

  const lockCall = (id, body) => call(path(id) + '/lock', {method: 'POST', body: {override: false, acknowledge: false, ...body}});

  /** Uploads the device copy over cloud revision `revision`; the decision left afterwards. */
  async function upload(local, revision, kind = 'auto', retried = false) {
    try {
      const r = await call(path(local.id), {method: 'PUT', body: body(local, kind), headers: headers(revision)});
      await store.update(local.id, cur => core.uploaded(cur, local.run, local.profile, r.revision));
      state.errors.delete(local.id);
      return {status: 'SYNCED', action: 'NONE', rename: false};
    } catch (e) {
      if (offline(e)) throw e;
      const lock = e.detail?.lock ?? null;
      switch (e.code) {
        case 'lock_overridden': {
          const at = e.detail?.overriddenUtc ?? null;
          // Another tab of this browser plays it now: that tab uploads (the device store is shared).
          if (lock && sameDevice(lock)) { for (const l of overrideListeners) l(local.id, lock, at); return {status: 'PENDING', action: 'NONE', rename: false, lock}; }
          if (at != null && seen.get(local.id) === at) {
            // Told already. Held elsewhere: in use there, as below. Free: progress made here since (a game started
            // offline) uploads once the notice is acknowledged to the account.
            if (lock || retried) return {status: 'CONFLICT', action: 'ASK_CONFLICT', rename: false, lock};
            await lockCall(local.id, {acknowledge: true});
            const d = await upload(local, revision, kind, true);
            if (playing !== local.id) await releaseNow(local.id).catch(() => {});
            return d;
          }
          for (const l of overrideListeners) l(local.id, lock, at);
          await store.update(local.id, cur => cur.overridden ? null : core.overridden(cur, lock, at ?? now()));
          return {status: 'OVERRIDDEN', action: 'NOTIFY_OVERRIDDEN', rename: false, lock, at};
        }
        // Another tab of this browser plays it: that tab uploads. Another device holds it: the player chooses.
        case 'locked': return sameDevice(lock) ? {status: 'PENDING', action: 'NONE', rename: false, lock} : {status: 'CONFLICT', action: 'ASK_CONFLICT', rename: false, lock};
        case 'revision_conflict': return {status: 'CONFLICT', action: 'ASK_CONFLICT', rename: false};
        case 'deleted': return {status: 'DELETED', action: local.dirty ? 'ASK_DELETED' : 'REMOVE_LOCAL', rename: false};
        default:
          // saves_full, storage_full, bad_*: the save stays here, unsynced, and the row says why.
          state.errors.set(local.id, e.message);
          return {status: revision ? 'PENDING' : 'LOCAL', action: 'NONE', rename: false, error: e.message};
      }
    }
  }

  async function fetchCloud(id) {
    const c = await call(path(id));
    const {run = null, profile = null, ...info} = c;
    return {cloud: info, run, profile};
  }

  /** Carries out what needs no question; returns the decision left for the player. */
  async function act(id, d) {
    const local = await store.get(id), cloud = state.cloud.get(id) ?? null;
    if (d.rename && local && cloud) {
      await call(path(id), {method: 'PATCH', body: {name: local.name}});
      await store.update(id, cur => cur.name === local.name ? {...cur, nameDirty: false} : null);
    }
    if (id === playing && (d.action === 'FETCH' || d.action === 'REMOVE_LOCAL')) return d; // never under the running game
    switch (d.action) {
      case 'CREATE': return upload(local, 0);
      case 'UPLOAD': return upload(local, local.base);
      case 'FETCH': {
        const {cloud: c, run, profile} = await fetchCloud(id);
        await store.update(id, cur => cur.dirty ? null : core.fetched(cur, c, run, profile));
        return {status: 'SYNCED', action: 'NONE', rename: false};
      }
      case 'REMOVE_LOCAL': await store.remove(id); return {status: 'DELETED', action: 'NONE', rename: false};
      default: return d;
    }
  }

  async function releaseNow(id) {
    try { await call(path(id) + '/unlock', {method: 'POST'}); await store.setRelease(id, false); }
    catch (e) { if (offline(e)) throw e; if (e.code === 'not_holder' || e.status === 404 || e.status === 410) await store.setRelease(id, false); else throw e; }
  }

  /** The full pass over every save. Safe to call any time; passes do not overlap. */
  const syncAll = () => exclusive(async () => {
    state.syncing = true; emit();
    try {
      await list();
      const locals = new Map((await store.list()).map(s => [s.id, s]));
      for (const id of new Set([...locals.keys(), ...state.cloud.keys()])) {
        const d = core.decide(locals.get(id) ?? null, state.cloud.get(id) ?? null, state.deleted.has(id));
        try { await act(id, d); }
        catch (e) { if (offline(e)) throw e; state.errors.set(id, e.message); }
      }
      for (const id of await store.releases()) if (id !== playing) await releaseNow(id).catch(e => { if (offline(e)) throw e; });
      state.error = null;
      await list(); await recompute();
      state.online = true; state.lastSync = now();
    } catch (e) {
      if (offline(e) || e.status === 401) wentOffline(e); else state.error = e.message;
      await recompute().catch(() => {});
    } finally { state.syncing = false; emit(); }
  });

  /** A pass soon (several requests in a row make one pass). */
  function requestSync(wait = 300) {
    return scheduled ??= new Promise(resolve => setTimeout(() => { scheduled = null; syncAll().then(resolve, resolve); }, wait));
  }

  /**
   * Takes the save's lock before play (override: from whoever has it). Results: granted, offline, held {lock},
   * overridden {lock, at} (another game took it from this tab: tell the player once), refused, deleted.
   */
  const lock = (id, override = false) => exclusive(async () => {
    // Another tab of this browser plays it (even one that started offline): the same rule as another device.
    if (!override && await playedInAnotherTab(store.account, id)) return {result: 'held', lock: anotherTab(deviceId)};
    return lockNow(id, override);
  });
  async function lockNow(id, override) {
    try { await lockCall(id, {override: !!override}); state.online = true; return {result: 'granted'}; }
    catch (e) {
      if (offline(e)) { wentOffline(e); return {result: 'offline'}; }
      const held = e.detail?.lock ?? null;
      if (e.code === 'locked' && held) return {result: 'held', lock: held};
      if (e.code === 'lock_overridden') {
        // The account repeats an override until this tab's game holds the lock again (also once the save is free).
        const at = e.detail?.overriddenUtc ?? null;
        // Taken by another tab of this browser: that tab stopped this one's game at once (the Web Lock) and its progress
        // is in the shared device store; nothing to announce.
        if (held && sameDevice(held)) { if (at) seen.set(id, at); return {result: 'held', lock: held}; }
        // The player was told: in use there (with Override), or free and taken with the acknowledgement.
        if (at != null && seen.get(id) === at) {
          if (held) return {result: 'held', lock: held};
          await lockCall(id, {acknowledge: true});
          return {result: 'granted'};
        }
        await store.update(id, cur => cur.dirty && !cur.overridden ? core.overridden(cur, held, at ?? now()) : null);
        return {result: 'overridden', lock: held, at};
      }
      if (e.status === 404) {
        // Not in the cloud yet: create it, then lock it.
        const local = await store.get(id);
        if (!local) throw e;
        const d = await upload(local, 0);
        if (d.status !== 'SYNCED') return {result: 'refused', decision: d};
        await lockCall(id, {override: !!override});
        return {result: 'granted'};
      }
      if (e.status === 410) return {result: 'deleted'};
      throw e;
    }
  }

  /** The save the page is about to play: the cloud copy when it is newer and nothing here is unsynced. Null: offline. */
  const prepare = id => exclusive(async () => {
    const local = await store.get(id);
    let cloud = null, run = null, profile = null, deleted = false;
    try { ({cloud, run, profile} = await fetchCloud(id)); }
    catch (e) {
      if (offline(e)) { wentOffline(e); return null; }
      if (e.status === 410) deleted = true; else if (e.status !== 404) throw e;
    }
    state.online = true;
    if (cloud) state.cloud.set(id, cloud);
    const d = core.decide(local, cloud, deleted);
    switch (d.action) {
      case 'FETCH': case 'LIST_ONLY': {
        const fetched = core.fetched(local, cloud, run, profile);
        if (local) await store.update(id, cur => core.fetched(cur, cloud, run, profile)); else await store.put(fetched);
        return {status: 'SYNCED', action: 'NONE', rename: false};
      }
      case 'CREATE': return upload(local, 0);
      case 'REMOVE_LOCAL': await store.remove(id); return {status: 'DELETED', action: 'NONE', rename: false, removed: true};
      default: return d;
    }
  });

  /**
   * "Use the cloud copy" (keepMine false; needs no lock) or "Keep this device's copy" (true) after a conflict. Keeping
   * this device's copy replaces the account's, so it needs this tab to hold the lock: Play takes it first, as for any
   * game, and a lock held elsewhere is never overridden here (the upload then answers `locked`: still a conflict).
   */
  const resolveConflict = (id, keepMine) => exclusive(async () => {
    const {cloud, run, profile} = await fetchCloud(id);
    let d;
    if (!keepMine) { await store.update(id, cur => core.fetched(cur, cloud, run, profile)); d = {status: 'SYNCED', action: 'NONE', rename: false}; }
    else {
      const local = await store.update(id, cur => core.keepMine(cur, cloud));
      d = local ? await upload(local, cloud.revision, 'manual') : {status: 'DELETED', action: 'NONE', rename: false};
    }
    state.decisions.set(id, d); emit();
    return d;
  });

  /** After the override notice (`at`: the override's time, when known): this device's copy becomes the cloud's. */
  const acknowledgeOverride = (id, at = null) => exclusive(async () => {
    if (at) seen.set(id, at);
    const {cloud, run, profile} = await fetchCloud(id);
    await store.update(id, cur => core.fetched({...cur, overridden: null}, cloud, run, profile));
  });

  /**
   * This tab's game was taken by another tab of this browser (its Web Lock). The account's lock goes back if this tab
   * still holds it (the other tab started offline); otherwise the override's time is noted, so it is never announced.
   */
  const leave = id => exclusive(async () => {
    if (playing === id) playing = null;
    try { await call(path(id) + '/unlock', {method: 'POST'}); return; }
    catch (e) { if (offline(e) || e.code !== 'not_holder') return; }
    try { await lockCall(id, {}); await call(path(id) + '/unlock', {method: 'POST'}).catch(() => {}); }
    catch (e) { if (e.code === 'lock_overridden' && e.detail?.overriddenUtc) seen.set(id, e.detail.overriddenUtc); }
  });

  /** A save deleted elsewhere with unsynced progress here: upload it again as a new save, or delete it here too. */
  const resolveDeleted = (id, uploadAgain, newId) => exclusive(async () => {
    const local = await store.get(id);
    if (!local) return null;
    await store.remove(id);
    if (!uploadAgain) return null;
    const copy = {...local, id: newId, base: 0, dirty: true, overridden: null, nameDirty: false};
    await store.put(copy);
    return upload(copy, 0).catch(e => { if (offline(e)) return null; throw e; });
  });

  /** Uploads the save now if it has unsynced progress. False when it could not. */
  const uploadNow = (id, kind = 'auto') => exclusive(async () => {
    try {
      const local = await store.get(id);
      if (!local) return true;
      let d = {status: 'SYNCED'};
      if (local.dirty && !local.overridden) d = await upload(local, local.base, kind);
      state.online = true;
      if (d.status !== 'SYNCED') state.decisions.set(id, d);
      emit();
      return d.status === 'SYNCED';
    } catch (e) { if (offline(e)) { wentOffline(e); emit(); return false; } throw e; }
  });

  /** The game wrote a save: upload it a few seconds later (writes in quick succession upload once). */
  function written(id) {
    if (pendingId !== id) clearTimeout(timer);
    pendingId = id; clearTimeout(timer);
    timer = setTimeout(() => { pendingId = null; uploadNow(id).catch(() => {}); }, delay);
  }

  /** Save and close: upload, then give the lock back (later, if offline now). */
  async function finish(id) {
    clearTimeout(timer); pendingId = null;
    await store.setRelease(id, true);
    if (playing === id) playing = null;
    if (!await uploadNow(id).catch(() => false)) return false;
    return exclusive(() => releaseNow(id).then(() => true, () => false));
  }

  /** Deletes the save here and, when it is in the cloud, there (override: even if another game holds its lock). */
  const remove = (id, {override = false} = {}) => exclusive(async () => {
    const local = await store.get(id);
    if (state.cloud.has(id) || (local?.base ?? 0) > 0) {
      try { await call(path(id) + (override ? '?override=true' : ''), {method: 'DELETE'}); }
      catch (e) {
        if (offline(e)) { wentOffline(e); throw Object.assign(Error('Connect to delete this save from your account.'), {offline: true}); }
        if (e.status !== 404) throw e;
      }
      state.cloud.delete(id);
    }
    await store.remove(id);
    state.decisions.delete(id); emit();
  });

  /** A rename: the device copy carries it to the cloud on the next pass; a save only in the cloud is renamed there. */
  const rename = (id, name) => exclusive(async () => {
    const local = await store.update(id, cur => ({...cur, name, nameDirty: true}));
    if (!local) await call(path(id), {method: 'PATCH', body: {name}});
  });

  const snapshots = id => call(path(id) + '/snapshots');
  const snapshot = (id, revision) => call(path(id) + '/snapshots/' + revision);
  /** Restores a recovery snapshot (needs the lock) and replaces this device's copy with the result. */
  const restore = (id, revision) => exclusive(async () => {
    const current = await fetchCloud(id);
    const taken = await lockNow(id, false);
    if (taken.result === 'held' || taken.result === 'overridden') throw Object.assign(Error('This save is in use on ' + (taken.lock?.device || 'another device') + '.'), {code: 'locked', lock: taken.lock});
    if (taken.result !== 'granted') throw Error('Connect to restore a recovery snapshot.');
    await call(path(id) + '/snapshots/' + revision + '/restore', {method: 'POST', headers: headers(current.cloud.revision)});
    const {cloud, run, profile} = await fetchCloud(id);
    const local = await store.get(id);
    if (local) await store.update(id, cur => core.fetched({...cur, overridden: null, dirty: false}, cloud, run, profile)); else await store.put(core.fetched(null, cloud, run, profile));
    if (playing !== id) await releaseNow(id).catch(() => {});
  });

  return {
    state, syncAll, requestSync, lock, prepare, resolveConflict, acknowledgeOverride, leave, resolveDeleted, uploadNow, written, finish, remove, rename,
    snapshots, snapshot, restore, fetchCloud: id => exclusive(() => fetchCloud(id)),
    /** The lock heartbeat while playing. */
    heartbeat: id => exclusive(() => lockNow(id, false)),
    release: id => exclusive(() => releaseNow(id)),
    /** The save this tab plays: the syncer never fetches over it or removes it. */
    setPlaying(id) { playing = id; },
    on(listener) { listeners.add(listener); return () => listeners.delete(listener); },
    /** Called with (id, lock, at) when an upload learns that another game took the save. */
    onOverridden(listener) { overrideListeners.add(listener); return () => overrideListeners.delete(listener); },
    lockState: (lock) => core.lockState(lock ?? null, instance),
    decide,
    sameDevice,
    setOnline(value) { state.online = value; if (value) requestSync(); emit(); },
    refresh: () => exclusive(async () => { await recompute(); emit(); }),
    cloudEnabled: true,
  };
}

/** The Web Lock a game holds on its save in this browser (web/game-session.mjs); another tab that plays it steals it. */
export const playLockName = (account, id) => 'ftl-play:' + account + ':' + id;
/** Whether another tab of this browser plays the save now. */
export async function playedInAnotherTab(account, id) {
  try { return !!(await navigator.locks?.query())?.held?.some(l => l.name === playLockName(account, id)); } catch { return false; }
}

/**
 * Local-only play (no account): nothing syncs, every save is `local`. The tabs of this browser still follow the lock rule
 * through the game's Web Lock: a second tab sees the save in use and may override, and the overridden tab learns it at
 * once (web/game-session.mjs).
 */
export function createLocalSync({core, store, account, deviceId}) {
  const listeners = new Set(), overrideListeners = new Set();
  const state = {online: false, syncing: false, cloud: new Map(), deleted: new Map(), decisions: new Map(), errors: new Map(), error: null, lastSync: null, limits: null, usedBytes: null};
  const emit = () => { for (const l of listeners) { try { l(state); } catch { /* listener */ } } };
  const other = anotherTab(deviceId);
  async function refresh() {
    state.decisions = new Map((await store.list()).map(s => [s.id, core.decide(s, null, false)]));
    emit();
  }
  const done = () => Promise.resolve();
  return {
    state, syncAll: refresh, requestSync: refresh,
    lock: async (id, override = false) => !override && await playedInAnotherTab(account, id) ? {result: 'held', lock: other} : {result: 'granted'},
    heartbeat: async () => ({result: 'granted'}),
    prepare: async () => ({status: 'LOCAL', action: 'NONE', rename: false}), resolveConflict: done, acknowledgeOverride: done, leave: done, resolveDeleted: done,
    uploadNow: async () => false, written() {}, finish: async () => true,
    remove: async id => { await store.remove(id); await refresh(); }, rename: async (id, name) => { await store.update(id, cur => ({...cur, name})); },
    snapshots: async () => [], snapshot: done, restore: done, fetchCloud: done, release: done,
    setPlaying() {}, on(listener) { listeners.add(listener); return () => listeners.delete(listener); },
    onOverridden(listener) { overrideListeners.add(listener); return () => overrideListeners.delete(listener); },
    lockState: () => 'FREE', decide: local => core.decide(local, null, false), sameDevice: () => true, setOnline() {}, refresh, cloudEnabled: false,
  };
}
