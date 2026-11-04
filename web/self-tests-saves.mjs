// The save sync checks shared by tests/saves-client.mjs (Node, the image's savecore.js) and the Tests page (the page's own
// savecore.js): web/sync.mjs over web/savecore.mjs with an in-memory device store and an in-memory account that answers
// like server/CloudSaves.cs (revisions, If-Match, locks with override and lock_overridden, tombstones, limits). Covers
// offline play then sync, fetch, both conflict choices, lock override with the overridden instance's notice, another tab of
// the same browser, deletion elsewhere and a save's profile syncing with its run. Nothing here touches the browser's own
// saves, account or storage: every store, account and override notice is in memory.
import {setupOf} from './savecore.mjs';
import {createSync} from './sync.mjs';

/** core: wrapSaveCore(savecore.js); assert: node:assert/strict or the page's (equal, deepEqual, ok, match, notEqual); log: a line per check. */
export async function saveSyncChecks({core, assert, log = () => {}}) {
  let passed = 0; const pass = label => { passed++; log('PASS ' + label); };
  // The overridden-instance notices stay in memory (the page's default keeps them in sessionStorage).
  const memorySeen = () => { const m = new Map(); return {get: id => m.get(id) ?? null, set: (id, at) => { m.set(id, at); }}; };
  const syncOf = options => createSync({seen: memorySeen(), ...options});

  /** The device store's interface (web/device-store.mjs) in memory. */
  function memoryStore() {
    const saves = new Map(), releases = new Set();
    const copy = v => v == null ? v : structuredClone(v);
    return {
      account: 'a', saves,
      async list() { return [...saves.values()].map(s => ({...copy(s), run: null, profile: null})); },
      async get(id) { return copy(saves.get(id) ?? null); },
      async put(s) { saves.set(s.id, copy(s)); },
      async update(id, change) { const cur = saves.get(id); if (!cur) return null; const next = change(copy(cur)); if (!next) return copy(cur); saves.set(id, copy(next)); return copy(next); },
      async remove(id) { saves.delete(id); },
      async releases() { return new Set(releases); },
      async setRelease(id, on) { if (on) releases.add(id); else releases.delete(id); },
    };
  }

  /** The account: CloudSaves.cs rules that the client relies on. */
  function memoryServer() {
    const saves = new Map(), tombstones = new Map(), overrides = [];
    let full = false;
    const fail = (status, code, detail = {}) => Object.assign(Error(detail.error ?? code), {status, code, detail: {code, error: code, ...detail}});
    const lockOf = s => s.lock ? {...s.lock} : null;
    const info = s => ({id: s.id, name: s.name, setup: s.setup, createdUtc: s.createdUtc, updatedUtc: s.updatedUtc, revision: s.revision, meta: s.meta, hasRun: s.run != null, runBytes: s.run?.length ?? 0, hasProfile: s.profile != null, profileBytes: s.profile?.length ?? 0, lock: lockOf(s)});
    // An overridden instance hears lock_overridden until it takes the lock again, also once the save is free.
    function conflict(s, caller) {
      if (s.lock?.instance === caller.instance) return null;
      const o = overrides.findLast(o => o.id === s.id && o.instance === caller.instance && !o.ack);
      if (o) return fail(409, 'lock_overridden', {lock: lockOf(s), overriddenUtc: o.at});
      return s.lock ? fail(409, 'locked', {lock: lockOf(s)}) : null;
    }
    function handle(caller, path, {method = 'GET', body, headers = {}}) {
      const m = /^\/saves\/([^/?]+)(\/lock|\/unlock)?(\?override=true)?$/.exec(path), now = new Date().toISOString();
      if (path === '/saves' && method === 'GET') return {saves: [...saves.values()].map(info), deleted: [...tombstones].map(([id, deletedUtc]) => ({id, deletedUtc})), limits: {saves: 30, snapshots: 10, bytes: 1 << 28}, usedBytes: 0, nextSince: now};
      if (!m) throw fail(404, 'not_found');
      const id = decodeURIComponent(m[1]), s = saves.get(id);
      if (!m[2] && method === 'GET') { if (!s) throw tombstones.has(id) ? fail(410, 'deleted') : fail(404, 'not_found'); return {...info(s), run: s.run, profile: s.profile ?? null}; }
      if (!m[2] && method === 'PUT') {
        const expected = Number(/"(\d+)"/.exec(headers['If-Match'] ?? '')?.[1]);
        if (!s) {
          if (tombstones.has(id)) throw fail(409, 'deleted');
          if (expected !== 0) throw fail(404, 'not_found');
          if (full) throw fail(409, 'saves_full', {error: 'An account has at most 30 saves. Delete one first.'});
          saves.set(id, {id, name: body.name, setup: body.setup, meta: body.meta, run: body.run, profile: body.profile ?? null, revision: 1, createdUtc: now, updatedUtc: now, lock: null});
          return {revision: 1};
        }
        const c = conflict(s, caller); if (c) throw c;
        if (s.revision !== expected) throw fail(409, 'revision_conflict', {revision: s.revision});
        Object.assign(s, {name: body.name, setup: body.setup, meta: body.meta, run: body.run, profile: body.profile ?? s.profile, revision: expected + 1, updatedUtc: now});
        if (s.lock) s.lock.lastSeen = now;
        return {revision: s.revision};
      }
      if (!m[2] && method === 'PATCH') { if (!s) throw fail(404, 'not_found'); s.name = body.name; return null; }
      if (!m[2] && method === 'DELETE') {
        if (!s) return null;
        if (s.lock && s.lock.instance !== caller.instance && !m[3]) throw fail(409, 'locked', {lock: lockOf(s)});
        saves.delete(id); tombstones.set(id, now); return null;
      }
      if (m[2] === '/lock') {
        if (!s) throw tombstones.has(id) ? fail(410, 'deleted') : fail(404, 'not_found');
        if (s.lock && s.lock.instance !== caller.instance) {
          if (!body?.override) throw conflict(s, caller);
          overrides.push({id, instance: s.lock.instance, by: caller.name, at: now}); s.lock = null;
        } else if (!s.lock && !body?.override && !body?.acknowledge) { const c = conflict(s, caller); if (c) throw c; }
        s.lock = {instance: caller.instance, deviceId: caller.deviceId, device: caller.name, platform: 'browser', since: s.lock?.since ?? now, lastSeen: now};
        for (const o of overrides) if (o.id === id && o.instance === caller.instance) o.ack = true;
        return lockOf(s);
      }
      if (m[2] === '/unlock') {
        if (!s?.lock) return null;
        if (s.lock.instance !== caller.instance) throw fail(409, 'not_holder');
        s.lock = null; return null;
      }
      throw fail(404, 'not_found');
    }
    return {saves, tombstones, setFull(v) { full = v; },
      /** A client of one game instance: call(path, options) as web/save-service.mjs passes it. */
      client(caller) {
        const state = {offline: false};
        const call = async (p, options = {}) => { if (state.offline) throw new TypeError('Failed to fetch'); return structuredClone(handle(caller, p, options) ?? null); };
        return {call, state};
      }};
  }

  const server = memoryServer();
  const device = (name, deviceId, instance) => {
    const store = memoryStore(), caller = {instance, deviceId, name}, client = server.client(caller);
    const sync = syncOf({core, store, call: client.call, instance, deviceId, delay: 5});
    return {store, sync, client, caller};
  };
  const meta = (name, deviceId, run) => ({playedOn: {platform: 'browser', device: name, deviceId, at: new Date().toISOString()}, engineBuild: 'test', gameData: 'test', summary: core.summary(run, 0)});
  const RUN = n => `<xftlSaveGame><playerShip shipId="PLAYER_SHIP_HARD" displayName="Kestrel ${n}" health="25" browserMaxHealth="30"><resources SCRAP="${n}"/></playerShip></xftlSaveGame>`;

  // savecore through the wrapper: setups, names, summaries.
  assert.equal(setupOf(core, []).key, 'vanilla');
  const mods = [{name: 'A.ftl', version: '', sha256: 'a'.repeat(64), size: 1, verified: false}];
  assert.equal(setupOf(core, mods).key, core.modSetHash(['a'.repeat(64)]));
  assert.deepEqual(core.summary(RUN(7), 60), {ship: 'Kestrel 7', shipId: 'PLAYER_SHIP_HARD', sector: null, scrap: 7, hull: 25, maxHull: 30, playSeconds: 60});
  assert.deepEqual(core.classify({files: [{id: 'x', title: 'Hyperspace', version: '1.0', file: 'Hyperspace.ftl', size: 1, sha256: 'b'.repeat(64)}]}, 'renamed.zip', 'b'.repeat(64), 1), {name: 'Hyperspace', version: '1.0', sha256: 'b'.repeat(64), size: 1, verified: true});
  assert.equal(core.classify({files: []}, 'C:\\mods\\mine.zip', 'c'.repeat(64), 2).name, 'mine.zip');
  pass('savecore through web/savecore.mjs: setups, summary, Verified and Custom classification');

  // Offline play, then sync: the save stays on the device and uploads when the account answers again.
  const pc = device('Firefox on Windows', 'dev-pc', 'tab-pc');
  pc.client.state.offline = true;
  const id = crypto.randomUUID(), now = new Date().toISOString();
  await pc.store.put({id, name: 'Save 1', setup: {key: 'vanilla', mods: []}, createdUtc: now, meta: {}, base: 0, dirty: true, hasRun: false, run: null, nameDirty: false, overridden: null});
  await pc.store.update(id, cur => core.written(cur, RUN(1), meta('Firefox on Windows', 'dev-pc', RUN(1))));
  await pc.sync.syncAll();
  assert.equal(pc.sync.state.online, false); assert.match(pc.sync.state.error, /Offline/);
  assert.equal(pc.sync.state.decisions.get(id).status, 'LOCAL'); assert.equal(server.saves.size, 0);
  assert.equal((await pc.sync.lock(id)).result, 'offline', 'offline play needs no lock');
  pc.client.state.offline = false;
  await pc.sync.syncAll();
  assert.equal(server.saves.get(id).run, RUN(1)); assert.equal(server.saves.get(id).revision, 1);
  assert.deepEqual([(await pc.store.get(id)).base, (await pc.store.get(id)).dirty], [1, false]);
  assert.equal(pc.sync.state.decisions.get(id).status, 'SYNCED'); assert.equal(pc.sync.state.online, true);
  pass('offline: the save stays on the device (LOCAL, no lock needed); online again it uploads as revision 1');

  // Play with the lock; written saves upload a few moments later; Save and close releases the lock.
  assert.equal((await pc.sync.lock(id)).result, 'granted');
  pc.sync.setPlaying(id);
  await pc.store.update(id, cur => core.written(cur, RUN(2), meta('Firefox on Windows', 'dev-pc', RUN(2))));
  pc.sync.written(id); await new Promise(r => setTimeout(r, 40));
  assert.equal(server.saves.get(id).run, RUN(2)); assert.equal(server.saves.get(id).revision, 2);
  assert.equal(await pc.sync.finish(id), true); assert.equal(server.saves.get(id).lock, null); assert.deepEqual([...await pc.store.releases()], []);
  pass('a written save uploads after the delay; Save and close uploads and releases the lock');

  // Another device: the cloud copy is newer and nothing is unsynced here -> fetched automatically.
  const phone = device('Pixel 8', 'dev-phone', 'phone');
  await phone.sync.syncAll();
  assert.equal(phone.sync.state.decisions.get(id).status, 'CLOUD', 'listed, not downloaded yet');
  assert.equal((await phone.sync.prepare(id)).status, 'SYNCED'); assert.equal((await phone.store.get(id)).run, RUN(2));
  await phone.store.update(id, cur => core.written(cur, RUN(3), meta('Pixel 8', 'dev-phone', RUN(3))));
  await phone.sync.uploadNow(id);
  assert.equal(server.saves.get(id).revision, 3);
  await pc.sync.syncAll();
  assert.equal((await pc.store.get(id)).run, RUN(3)); assert.equal((await pc.store.get(id)).base, 3);
  pass('a newer cloud copy with nothing unsynced here is fetched by the next pass');

  // Changed in two places: both choices.
  await pc.store.update(id, cur => core.written(cur, RUN(40), meta('Firefox on Windows', 'dev-pc', RUN(40))));
  await phone.store.update(id, cur => core.written(cur, RUN(41), meta('Pixel 8', 'dev-phone', RUN(41))));
  await phone.sync.uploadNow(id);
  await pc.sync.syncAll();
  assert.equal(pc.sync.state.decisions.get(id).action, 'ASK_CONFLICT');
  assert.equal((await pc.sync.prepare(id)).action, 'ASK_CONFLICT');
  await pc.sync.resolveConflict(id, false);
  assert.equal((await pc.store.get(id)).run, RUN(41), 'Use the cloud copy'); assert.equal((await pc.store.get(id)).dirty, false);
  await pc.store.update(id, cur => core.written(cur, RUN(50), meta('Firefox on Windows', 'dev-pc', RUN(50))));
  assert.equal((await phone.sync.lock(id)).result, 'granted', 'the phone plays the save');
  await phone.store.update(id, cur => core.written(cur, RUN(51), meta('Pixel 8', 'dev-phone', RUN(51))));
  await phone.sync.uploadNow(id);
  await pc.sync.syncAll();
  assert.equal(pc.sync.state.decisions.get(id).status, 'CONFLICT');
  // "Keep this device's copy" needs the lock: Play asks for it without override and the phone holds it.
  const playing = await pc.sync.lock(id);
  assert.deepEqual([playing.result, playing.lock.device], ['held', 'Pixel 8'], 'keepMine never overrides a lock silently');
  assert.equal((await pc.sync.resolveConflict(id, true)).status, 'CONFLICT', 'without the lock the account refuses the copy');
  assert.equal(server.saves.get(id).run, RUN(51)); assert.equal(server.saves.get(id).lock.device, 'Pixel 8');
  // Override lock (after the dialog) takes it; then this device's copy replaces the account's.
  assert.equal((await pc.sync.lock(id, true)).result, 'granted');
  assert.equal((await pc.sync.resolveConflict(id, true)).status, 'SYNCED');
  assert.equal(server.saves.get(id).run, RUN(50), 'Keep this device\'s copy overwrites the cloud');
  await pc.sync.release(id);
  const told50 = await phone.sync.lock(id);
  assert.equal(told50.result, 'overridden'); await phone.sync.acknowledgeOverride(id, told50.at);
  pass('changed in two places: "Use the cloud copy" takes the cloud run; "Keep this device\'s copy" needs the lock (held elsewhere: asked, never overridden silently) and then overwrites the cloud');

  // Lock held elsewhere, override, and the overridden instance learns it on its next contact.
  await phone.sync.syncAll();
  assert.equal((await phone.sync.lock(id)).result, 'granted');
  await phone.store.update(id, cur => core.written(cur, RUN(60), meta('Pixel 8', 'dev-phone', RUN(60))));
  const held = await pc.sync.lock(id);
  assert.equal(held.result, 'held'); assert.equal(held.lock.device, 'Pixel 8');
  assert.equal(pc.sync.lockState(held.lock), 'OTHER');
  assert.equal((await pc.sync.lock(id, true)).result, 'granted');
  let told = null; phone.sync.onOverridden((which, lock, at) => { told = {which, lock, at}; });
  assert.equal((await phone.sync.uploadNow(id)), false);
  assert.equal(told.which, id); assert.equal(told.lock.device, 'Firefox on Windows'); assert.ok(told.at, 'the override names its time');
  const marked = await phone.store.get(id);
  assert.equal(marked.overridden.device, 'Firefox on Windows'); assert.equal(marked.overridden.at, told.at);
  assert.equal(core.decide(marked, null, false).action, 'NOTIFY_OVERRIDDEN');
  assert.equal(server.saves.get(id).run, RUN(50), 'nothing the overridden device wrote reached the cloud');
  const beat = await phone.sync.heartbeat(id);
  assert.deepEqual([beat.result, beat.at], ['overridden', told.at], 'until the player has seen the notice, every contact says so');
  await phone.sync.acknowledgeOverride(id, told.at);
  assert.deepEqual([(await phone.store.get(id)).run, (await phone.store.get(id)).overridden, (await phone.store.get(id)).dirty], [RUN(50), null, false]);
  const again = await phone.sync.lock(id);
  assert.equal(again.result, 'held', 'the server still answers lock_overridden; once seen, the save is simply in use there, with Override');
  assert.equal(again.lock.device, 'Firefox on Windows');
  await phone.store.update(id, cur => core.written(cur, RUN(61), meta('Pixel 8', 'dev-phone', RUN(61))));
  told = null;
  assert.equal((await phone.sync.uploadNow(id)), false); assert.equal(told, null, 'a seen override does not stop a game again');
  assert.equal((await phone.store.get(id)).overridden, null);
  await phone.sync.resolveConflict(id, false);
  assert.equal((await phone.sync.lock(id, true)).result, 'granted', 'Override takes it back');
  const back = await pc.sync.lock(id);
  assert.equal(back.result, 'overridden', 'a new override is a new notice'); assert.notEqual(back.at, undefined);
  await pc.sync.acknowledgeOverride(id, back.at);
  assert.equal((await pc.sync.lock(id)).result, 'held');
  assert.equal((await pc.sync.lock(id, true)).result, 'granted');
  pass('a held lock names its device; Override takes it; the overridden game hears lock_overridden once, keeps nothing and takes the cloud copy; then the save shows as in use');

  // Another tab of the same browser: a different instance with the same device; its record is not marked overridden.
  const tabB = syncOf({core, store: pc.store, call: server.client({instance: 'tab-pc-2', deviceId: 'dev-pc', name: 'Firefox on Windows'}).call, instance: 'tab-pc-2', deviceId: 'dev-pc'});
  const fromTab = await tabB.lock(id);
  assert.equal(fromTab.result, 'held'); assert.equal(tabB.sameDevice(fromTab.lock), true);
  assert.equal((await tabB.lock(id, true)).result, 'granted');
  let tabTold = null; pc.sync.onOverridden((which, lock) => { tabTold = lock; });
  await pc.store.update(id, cur => core.written(cur, RUN(70), meta('Firefox on Windows', 'dev-pc', RUN(70))));
  await pc.sync.uploadNow(id);
  assert.equal(tabTold.instance, 'tab-pc-2'); assert.equal((await pc.store.get(id)).overridden, null, 'the shared device copy is not marked: the other tab owns it now');
  assert.equal((await pc.sync.lock(id)).result, 'held', 'taken by another tab: in use there, no notice (that tab stopped this one already)');
  assert.equal((await tabB.uploadNow(id)), true); assert.equal(server.saves.get(id).run, RUN(70));
  await tabB.release(id);
  assert.equal((await pc.sync.lock(id)).result, 'granted', 'free again: taken with the acknowledgement, no notice');
  await pc.sync.release(id);
  pass('two tabs of one browser: the second overrides the first, the first stops, the shared device copy stays with the second');

  // The override wins completely: the overridden device is told also once the save is free, and takes it after the notice.
  const free = await phone.sync.lock(id);
  assert.deepEqual([free.result, free.lock], ['overridden', null]);
  await phone.store.update(id, cur => core.written(cur, RUN(75), meta('Pixel 8', 'dev-phone', RUN(75))));
  assert.equal(await phone.sync.uploadNow(id), false, 'its stale progress never lands on the newer save');
  assert.equal(server.saves.get(id).run, RUN(70));
  await phone.sync.acknowledgeOverride(id, free.at);
  assert.equal((await phone.store.get(id)).run, RUN(70));
  assert.equal((await phone.sync.lock(id)).result, 'granted', 'after the notice the free save is taken with {acknowledge: true}');
  await phone.sync.release(id);
  pass('an override wins completely: the overridden device hears it while the save is free, keeps nothing, then takes the lock after the notice');

  // Deleted on another device: clean copies go, unsynced ones ask; "Upload again" makes a new save.
  await phone.sync.syncAll();
  await pc.sync.remove(id);
  assert.equal(server.saves.has(id), false); assert.ok(server.tombstones.has(id));
  await phone.store.update(id, cur => core.written(cur, RUN(80), meta('Pixel 8', 'dev-phone', RUN(80))));
  await phone.sync.syncAll();
  assert.equal(phone.sync.state.decisions.get(id).action, 'ASK_DELETED');
  const fresh = crypto.randomUUID(); await phone.sync.resolveDeleted(id, true, fresh);
  assert.equal(server.saves.get(fresh).run, RUN(80)); assert.equal(await phone.store.get(id), null);
  await pc.sync.syncAll();
  assert.equal((await pc.store.get(fresh)), null, 'the other device lists it without downloading');
  assert.equal(pc.sync.state.decisions.get(fresh).status, 'CLOUD');
  pass('deleted elsewhere: unsynced progress asks; "Upload again" uploads it as a new save the other device lists');

  // Limits and refusals: the save stays here, unsynced, with the server's message.
  server.setFull(true);
  const extra = crypto.randomUUID();
  await pc.store.put({id: extra, name: 'Save 31', setup: {key: 'vanilla', mods: []}, createdUtc: now, meta: {}, base: 0, dirty: true, hasRun: false, run: null, nameDirty: false, overridden: null});
  await pc.sync.syncAll();
  assert.equal(pc.sync.state.errors.get(extra), 'An account has at most 30 saves. Delete one first.');
  assert.equal(pc.sync.state.decisions.get(extra).status, 'LOCAL'); assert.equal(server.saves.has(extra), false);
  server.setFull(false); await pc.sync.syncAll();
  assert.equal(pc.sync.state.errors.has(extra), false); assert.ok(server.saves.has(extra));
  pass('a refused upload (saves_full) keeps the save here with the server\'s message and uploads once there is room');

  // A page that opens offline: the account's list is unknown, not empty, so nothing looks deleted elsewhere.
  const offlineTab = server.client({instance: 'tab-late', deviceId: 'dev-pc', name: 'Firefox on Windows'}); offlineTab.state.offline = true;
  const late = syncOf({core, store: pc.store, call: offlineTab.call, instance: 'tab-late', deviceId: 'dev-pc'});
  await pc.store.update(extra, cur => core.written(cur, RUN(90), meta('Firefox on Windows', 'dev-pc', RUN(90))));
  await late.syncAll();
  assert.deepEqual([late.state.decisions.get(extra).status, late.state.decisions.get(extra).action], ['PENDING', 'NONE']);
  assert.ok(await pc.store.get(extra), 'kept');
  offlineTab.state.offline = false; await late.syncAll();
  assert.equal(server.saves.get(extra).run, RUN(90));
  pass('a page opened offline shows unsynced saves as "Not synced" (never deleted) and uploads them later');

  // A save holds its profile: one upload, one revision, and whichever copy wins keeps its unlocks.
  const profile = n => `<xftl-profile><ach id="A${n}" diff="EASY"/></xftl-profile>`;
  const own = crypto.randomUUID(), own2 = crypto.randomUUID();
  const newSave = (i, name) => ({id: i, name, setup: {key: 'vanilla', mods: []}, createdUtc: new Date().toISOString(), meta: {}, base: 0, dirty: true, hasRun: false, run: null, hasProfile: false, profile: null, nameDirty: false, overridden: null});
  await pc.store.put(newSave(own, 'Own unlocks')); await pc.store.put(newSave(own2, 'Other unlocks'));
  await pc.sync.syncAll();
  await pc.store.update(own, cur => core.profileWritten(cur, profile(1)));
  assert.equal((await pc.store.get(own)).dirty, true, 'an unlock alone leaves the save to upload');
  assert.equal(await pc.store.update(own, cur => core.profileWritten(cur, profile(1))).then(x => x.profile), profile(1));
  const revisionBefore = server.saves.get(own).revision;
  await pc.sync.uploadNow(own);
  assert.equal(server.saves.get(own).profile, profile(1));
  assert.equal(server.saves.get(own).revision, revisionBefore + 1);
  assert.equal((await pc.store.get(own)).dirty, false);
  assert.equal(server.saves.get(own2).profile, null, 'another save shares none of it');
  pass('a save uploads its profile with its run, under one revision; other saves share nothing');
  // The other device gets the whole save, and a run that ended keeps the unlocks.
  await phone.sync.syncAll();
  await phone.sync.prepare(own);
  assert.equal((await phone.store.get(own)).profile, profile(1));
  await pc.store.update(own, cur => core.written(cur, RUN(70), meta('Firefox on Windows', 'dev-pc', RUN(70))));
  await pc.store.update(own, cur => core.written(cur, null, meta('Firefox on Windows', 'dev-pc', null)));
  await pc.store.update(own, cur => core.profileWritten(cur, profile(2)));
  await pc.sync.uploadNow(own);
  assert.deepEqual([server.saves.get(own).run, server.saves.get(own).profile], [null, profile(2)]);
  await phone.sync.syncAll();
  const fetchedHere = await phone.store.get(own);
  assert.deepEqual([fetchedHere.hasRun, fetchedHere.profile, fetchedHere.dirty], [false, profile(2), false]);
  pass('fetching brings run and profile together; when a run ends the next one starts in the same save, with its unlocks');
  // Changed in two places: one whole copy is kept, so the other device's new unlocks go with it.
  await pc.store.update(own, cur => core.profileWritten(cur, profile(3)));
  await phone.store.update(own, cur => core.profileWritten(cur, profile(4)));
  await pc.sync.uploadNow(own);
  await phone.sync.syncAll();
  assert.equal(phone.sync.state.decisions.get(own).status, 'CONFLICT');
  await phone.sync.resolveConflict(own, false);
  assert.equal((await phone.store.get(own)).profile, profile(3), 'the unlocks only the phone had are gone: no merge');
  await phone.store.update(own, cur => core.profileWritten(cur, profile(5)));
  await pc.store.update(own, cur => core.profileWritten(cur, profile(6)));
  await pc.sync.uploadNow(own);
  await phone.sync.syncAll();
  await phone.sync.resolveConflict(own, true);
  assert.equal(server.saves.get(own).profile, profile(5), 'keeping this device\'s copy replaces the account\'s unlocks too');
  pass('a conflict keeps one whole copy of the save: its unlocks are not merged');
  // An upload that names no profile (a save not started yet here, an old page) leaves the stored one.
  await pc.sync.syncAll();
  await pc.client.call('/saves/' + own, {method: 'PUT', body: {name: 'x', setup: {key: 'vanilla', mods: []}, meta: {}, run: null, profile: null}, headers: {'If-Match': `"${server.saves.get(own).revision}"`}});
  assert.equal(server.saves.get(own).profile, profile(5));
  pass('an upload without a profile keeps the stored one');

  return `PASS ${passed} save sync checks: offline play, conflicts, locks and overrides, tabs, deletions, limits and profiles`;
}
