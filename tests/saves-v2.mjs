// Security group: the save model 2 API (docs/SAVES.md "Server API"). Runs on the disposable security fixture, which lowers
// the account storage limit (FTL_SAVE_BYTES_LIMIT) so the limit can be reached; accounts user02..user06, rerunnable.
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {randomBytes, randomUUID} from 'node:crypto';

const origin = process.env.TEST_ORIGIN ?? 'http://localhost:8081';
if (!['http://localhost:8081', 'http://localhost:8082'].includes(origin)) throw Error('This test mutates disposable accounts; use an isolated localhost:8081 or :8082 instance.');
const fixture = process.env.TEST_FIXTURE_DIR ?? (origin.endsWith(':8082') ? '.build/security-data' : '.build/test-data');
for (let i = 0; !await fetch(origin + '/health/ready').then(r => r.ok).catch(() => false); i++) { if (i > 80) throw Error('Fixture did not become ready'); await new Promise(r => setTimeout(r, 250)); }
const credentials = new Map((await readFile(fixture + '/bootstrap-credentials.txt', 'utf8')).split(/\r?\n/).filter(l => l.includes('\t')).map(l => l.split('\t')));
let passed = 0; const pass = label => { passed++; console.log('PASS ' + label); };

class Client {
  cookies = new Map(); csrf = null;
  async request(path, method = 'GET', body, extra = {}, csrf = true) {
    const r = await fetch(origin + path, {method, headers: {Cookie: [...this.cookies].map(([k, v]) => `${k}=${v}`).join('; '), ...(body === undefined ? {} : {'Content-Type': 'application/json'}), ...(method === 'GET' || !csrf ? {} : {'X-CSRF-TOKEN': this.csrf}), Origin: origin, ...extra}, body: body === undefined ? undefined : JSON.stringify(body)});
    for (const c of r.headers.getSetCookie()) { const [pair] = c.split(';'); const [k, ...v] = pair.split('='); if (v.join('=')) this.cookies.set(k, v.join('=')); else this.cookies.delete(k); }
    return r;
  }
  async token() { this.csrf = (await (await this.request('/api/auth/csrf')).json()).token; }
  async login(user, password) { await this.token(); const r = await this.request('/api/auth/login', 'POST', {username: user, password}); assert.equal(r.status, 200, 'login ' + user); await this.token(); }
  async player(user) {
    const initial = credentials.get(user), password = 'saves-v2-password-' + user;
    await this.token(); const r = await this.request('/api/auth/login', 'POST', {username: user, password});
    if (r.status === 401) { await this.login(user, initial); await this.json('/api/auth/password', 'POST', {currentPassword: initial, newPassword: password}); await this.login(user, password); } else { assert.equal(r.status, 200); await this.token(); }
    return this;
  }
  async json(path, method = 'GET', body, extra = {}) { const r = await this.request(path, method, body, extra); assert.ok(r.ok, `${method} ${path}: HTTP ${r.status} ${await r.clone().text()}`); return r.status === 204 ? null : r.json(); }
  // Save API helpers: every write names the game instance and device.
  put(id, revision, body, device) { return this.request('/api/saves/' + id, 'PUT', body, {...device, 'If-Match': `"${revision}"`}); }
  lock(id, device, override = false, acknowledge = false) { return this.request(`/api/saves/${id}/lock`, 'POST', {override, acknowledge}, device); }
  unlock(id, device) { return this.request(`/api/saves/${id}/unlock`, 'POST', undefined, device); }
  remove(id, device, override = false) { return this.request('/api/saves/' + id + (override ? '?override=true' : ''), 'DELETE', undefined, device); }
  restore(id, revision, ifMatch, device) { return this.request(`/api/saves/${id}/snapshots/${revision}/restore`, 'POST', undefined, {...device, 'If-Match': `"${ifMatch}"`}); }
  async clear(device) { for (const s of (await this.json('/api/saves')).saves) assert.equal((await this.remove(s.id, device, true)).status, 204); }
}
async function expect(response, status, code) {
  const text = await response.text();
  assert.equal(response.status, status, `${response.url}: expected ${status}${code ? ' ' + code : ''}, got ${response.status} ${text}`);
  const json = text ? JSON.parse(text) : null;
  if (code) assert.equal(json?.code, code, text);
  return json;
}
const device = (instance, platform = 'browser', deviceId = 'dev-' + instance, name = 'Device ' + instance) => ({'X-FTL-Instance': instance, 'X-FTL-Device': `${platform};${deviceId};${name}`});
const vanilla = {key: 'vanilla', mods: []};
const save = (run, extra = {}) => ({name: 'Run', setup: vanilla, meta: {}, run, ...extra});
const runXml = tag => `<xftlSaveGame><playerShip shipId="PLAYER_SHIP_HARD" tag="${tag}"/></xftlSaveGame>`;
const pause = ms => new Promise(r => setTimeout(r, ms));

const owner = await new Client().player('user03'), other = await new Client().player('user04');
const tabA = device('tab-a', 'browser', 'laptop-1', 'Laptop'), tabB = device('tab-b', 'android', 'pixel-1', encodeURIComponent('Renée’s Pixel 8'));
await owner.clear(tabA); await other.clear(tabA);

// Anonymous, cross-origin and CSRF-less requests are refused; save model 1 endpoints are gone.
{
  const anonymous = new Client(); const id = randomUUID();
  for (const path of ['/api/saves', '/api/saves/' + id, '/api/saves/' + id + '/snapshots', '/api/saves/current']) assert.equal((await anonymous.request(path)).status, 401, path);
  await expect(await owner.request('/api/saves/' + id, 'PUT', save(null), {...tabA, 'If-Match': '"0"'}, false), 400);
  await expect(await owner.request('/api/saves/' + id + '/lock', 'POST', {override: false}, tabA, false), 400);
  await expect(await owner.request('/api/saves/' + id, 'PUT', save(null), {...tabA, 'If-Match': '"0"', Origin: 'https://other.example'}), 403);
  assert.equal((await owner.request('/api/saves/' + id)).status, 404);
  pass('Saves need an account, the CSRF token and the same origin');
  for (const [path, method, body] of [['/api/saves/current', 'GET'], ['/api/saves/current', 'PUT', {state: {}}], ['/api/saves/slots', 'GET'], ['/api/saves/slots', 'POST', {}], ['/api/saves/slots/2/activate', 'POST', {}], ['/api/saves/snapshots', 'GET'], ['/api/saves/snapshots/3/restore', 'POST', {}], ['/api/play-lease/acquire', 'POST', {takeOver: true}], ['/api/play-lease/heartbeat', 'POST', {}]])
    assert.equal((await expect(await owner.request(path, method, body), 410, 'saves_v2')).error, 'Saves changed. Reload the page.');
  pass('Save model 1 endpoints answer 410 saves_v2');
}

// Headers and bodies are validated before anything is stored.
{
  const id = randomUUID();
  for (const headers of [{}, {'X-FTL-Instance': 'tab-a'}, device('tab a'), device('x'.repeat(65)), device('tab-a', 'ios'), device('tab-a', 'browser', 'bad id'), device('tab-a', 'browser', 'dev', ''), device('tab-a', 'browser', 'dev', 'x'.repeat(61)), device('tab-a', 'browser', 'dev', 'tab%09name')])
    await expect(await owner.request('/api/saves/' + id, 'PUT', save(null), {...headers, 'If-Match': '"0"'}), 400, 'bad_device');
  for (const ifMatch of [undefined, '0', '"abc"', '"-1"', 'W/"0"']) await expect(await owner.request('/api/saves/' + id, 'PUT', save(null), {...tabA, ...(ifMatch === undefined ? {} : {'If-Match': ifMatch})}), 400, 'bad_if_match');
  for (const name of ['', '  ', 'x'.repeat(41), 'tab\tname', null]) await expect(await owner.put(id, 0, save(null, {name}), tabA), 400, 'bad_name');
  const mod = {name: 'Hyperspace', version: '1.23.2', sha256: 'a'.repeat(64), size: 10, verified: true};
  for (const setup of [null, {key: 'vanilla', mods: [mod]}, {key: 'b'.repeat(64), mods: []}, {key: 'B'.repeat(64), mods: [mod]}, {key: 'b'.repeat(64), mods: [{...mod, sha256: 'z'.repeat(64)}]}, {key: 'b'.repeat(64), mods: [{...mod, size: -1}]}, {key: 'b'.repeat(64), mods: [{...mod, verified: 'yes'}]}, {key: 'b'.repeat(64), mods: [{...mod, name: ''}]}, {key: 'b'.repeat(64), mods: [{...mod, version: 'v'.repeat(41)}]}, {key: 'b'.repeat(64), mods: Array(65).fill(mod)}])
    await expect(await owner.put(id, 0, save(null, {setup}), tabA), 400, 'bad_setup');
  for (const meta of [[], 'text', 5, {pad: 'x'.repeat(8200)}]) await expect(await owner.put(id, 0, save(null, {meta}), tabA), 400, 'bad_meta');
  for (const run of ['<other/>', '<!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><xftlSaveGame>&e;</xftlSaveGame>', '<xftlSaveGame>' + '<a>'.repeat(70) + '</a>'.repeat(70) + '</xftlSaveGame>', '<xftlSaveGame>'])
    await expect(await owner.put(id, 0, save(run), tabA), 400, 'bad_run');
  assert.equal((await owner.request('/api/saves/' + id)).status, 404);
  pass('Instance, device, If-Match, name, setup, meta and run XML are validated');
}

// Create, list, read, update and rename.
const first = randomUUID(), modded = randomUUID();
{
  const created = await expect(await owner.put(first, 0, save(runXml(1), {name: '  First run  ', meta: {summary: {ship: 'Kestrel', sector: 3}, note: 'ünïcødé'}, kind: 'manual'}), tabA), 200);
  assert.equal(created.revision, 1); assert.ok(Date.parse(created.updatedUtc) > 0 && created.updatedUtc.endsWith('Z'));
  const mods = [{name: 'Hyperspace', version: '1.23.2', sha256: 'C'.repeat(64), size: 1234567, verified: true, extra: 'dropped'}, {name: 'Custom ship', version: '', sha256: 'd'.repeat(64), size: 0, verified: false}];
  await expect(await owner.put(modded, 0, save(null, {name: 'Modded', setup: {key: 'e'.repeat(64), mods}}), tabB), 200);
  const list = await owner.json('/api/saves');
  assert.equal(list.limits.saves, 30); assert.equal(list.limits.snapshots, 10); assert.ok(list.limits.bytes > 0); assert.ok(list.usedBytes > 0); assert.ok(Array.isArray(list.deleted));
  const a = list.saves.find(s => s.id === first), b = list.saves.find(s => s.id === modded);
  assert.deepEqual(Object.keys(a).sort(), ['createdUtc', 'hasProfile', 'hasRun', 'id', 'lock', 'meta', 'name', 'profileBytes', 'revision', 'runBytes', 'setup', 'updatedUtc']);
  assert.equal(a.name, 'First run'); assert.deepEqual(a.setup, vanilla); assert.equal(a.revision, 1); assert.equal(a.hasRun, true); assert.equal(a.runBytes, Buffer.byteLength(runXml(1)));
  assert.deepEqual(a.meta, {summary: {ship: 'Kestrel', sector: 3}, note: 'ünïcødé'}); assert.equal(a.lock, null); assert.equal(a.createdUtc, a.updatedUtc);
  assert.deepEqual(b.setup, {key: 'e'.repeat(64), mods: [{name: 'Hyperspace', version: '1.23.2', sha256: 'c'.repeat(64), size: 1234567, verified: true}, {name: 'Custom ship', version: '', sha256: 'd'.repeat(64), size: 0, verified: false}]});
  assert.equal(b.hasRun, false); assert.equal(b.runBytes, 0); assert.deepEqual(b.meta, {});
  const got = await owner.request('/api/saves/' + first); assert.equal(got.headers.get('etag'), '"1"');
  const full = await got.json(); assert.equal(full.run, runXml(1)); assert.equal(full.revision, 1); assert.equal(full.name, 'First run');
  pass('A save is created with If-Match "0", listed with its metadata and read with its run and ETag');

  const updated = await expect(await owner.put(first, 1, save(runXml(2), {name: 'First run'}), tabA), 200);
  assert.equal(updated.revision, 2);
  assert.equal((await expect(await owner.put(first, 1, save(runXml(3)), tabA), 409, 'revision_conflict')).revision, 2);
  assert.equal((await expect(await owner.put(first, 0, save(runXml(3)), tabA), 409, 'revision_conflict')).revision, 2);
  await expect(await owner.put(randomUUID(), 4, save(runXml(3)), tabA), 404, 'not_found');
  assert.equal((await owner.json('/api/saves/' + first)).run, runXml(2));
  pass('An update needs the current revision (If-Match); a stale or repeated create is a revision conflict');

  const before = await owner.json('/api/saves/' + first); await pause(20);
  for (const name of ['', 'x'.repeat(41)]) await expect(await owner.request('/api/saves/' + first, 'PATCH', {name}), 400, 'bad_name');
  assert.equal((await owner.request('/api/saves/' + first, 'PATCH', {name: ' Renamed '})).status, 204);
  await expect(await owner.request('/api/saves/' + randomUUID(), 'PATCH', {name: 'x'}), 404, 'not_found');
  const renamed = await owner.json('/api/saves/' + first);
  assert.equal(renamed.name, 'Renamed'); assert.equal(renamed.revision, before.revision); assert.ok(Date.parse(renamed.updatedUtc) > Date.parse(before.updatedUtc)); assert.equal(renamed.run, before.run);
  pass('PATCH renames a save without a new revision');

  // Deltas: only what changed after `since` (exclusive), tombstones included.
  const at = (await owner.json('/api/saves')).saves.find(s => s.id === first).updatedUtc; await pause(20);
  assert.equal((await owner.request('/api/saves/' + modded, 'PATCH', {name: 'Modded run'})).status, 204);
  const delta = await owner.json('/api/saves?since=' + encodeURIComponent(at));
  assert.deepEqual(delta.saves.map(s => [s.id, s.name]), [[modded, 'Modded run']]); assert.deepEqual(delta.deleted, []);
  assert.ok(Date.parse(delta.nextSince) < Date.now());
  await expect(await owner.request('/api/saves?since=yesterday'), 400, 'bad_since');
  pass('GET /api/saves?since= lists only saves changed after it');
}

// Locks: per save, held by one game instance, never expiring; an override is recorded and reported to the old holder.
{
  const id = first, tabC = device('tab-c', 'browser', 'laptop-1', 'Laptop');
  await expect(await owner.lock(randomUUID(), tabA), 404, 'not_found');
  const held = await expect(await owner.lock(id, tabA), 200);
  assert.deepEqual(Object.keys(held).sort(), ['device', 'deviceId', 'instance', 'lastSeen', 'platform', 'since']);
  assert.deepEqual([held.instance, held.deviceId, held.device, held.platform], ['tab-a', 'laptop-1', 'Laptop', 'browser']); assert.equal(held.since, held.lastSeen);
  await pause(20); const again = await expect(await owner.lock(id, tabA), 200);
  assert.equal(again.since, held.since); assert.ok(Date.parse(again.lastSeen) > Date.parse(held.lastSeen));
  assert.deepEqual((await owner.json('/api/saves/' + id)).lock, {...again, instance: 'other'});
  pass('Play takes the lock; the holder refreshes it (since unchanged, lastSeen now)');

  assert.deepEqual((await expect(await owner.lock(id, tabC), 409, 'locked')).lock, {...again, instance: 'other'});
  await expect(await owner.put(id, 2, save(runXml(9)), tabC), 409, 'locked');
  await expect(await owner.restore(id, 1, 2, tabC), 409, 'locked');
  await expect(await owner.unlock(id, tabC), 409, 'not_holder');
  await expect(await owner.remove(id, tabC), 409, 'locked');
  const saved = await expect(await owner.put(id, 2, save(runXml(3), {name: 'Renamed'}), tabA), 200);
  assert.equal(saved.revision, 3); assert.equal((await owner.json('/api/saves/' + id)).lock.lastSeen, saved.updatedUtc);
  pass('Another instance (even on the same device) gets 409 locked for lock, PUT, restore, unlock and delete; the holder saves');

  const taken = await expect(await owner.lock(id, tabB, true), 200);
  assert.deepEqual([taken.instance, taken.deviceId, taken.device, taken.platform], ['tab-b', 'pixel-1', 'Renée’s Pixel 8', 'android']); assert.ok(Date.parse(taken.since) > Date.parse(again.since));
  const told = await expect(await owner.lock(id, tabA), 409, 'lock_overridden');
  assert.deepEqual(told.lock, {...taken, instance: 'other'}); assert.ok(Date.parse(told.overriddenUtc) > 0); assert.ok(told.error.includes('Renée’s Pixel 8'));
  assert.equal((await expect(await owner.put(id, 3, save(runXml(4)), tabA), 409, 'lock_overridden')).overriddenUtc, told.overriddenUtc);
  await expect(await owner.restore(id, 1, 3, tabA), 409, 'lock_overridden');
  await expect(await owner.unlock(id, tabA), 409, 'not_holder');
  await expect(await owner.lock(id, tabC), 409, 'locked');
  pass('Override moves the lock at once; the overridden instance learns it (409 lock_overridden) on its next lock refresh or upload');
  assert.equal((await owner.json('/api/saves/' + id)).lock.instance, 'other');
  assert.equal((await (await owner.request('/api/saves/' + id, 'GET', undefined, tabB)).json()).lock.instance, 'tab-b');
  pass('A lock shows the holder\'s instance id only to the holder');

  const back = await expect(await owner.lock(id, tabA, true), 200);
  assert.equal(back.instance, 'tab-a');
  await expect(await owner.lock(id, tabB), 409, 'lock_overridden');
  assert.equal((await expect(await owner.lock(id, tabA), 200)).since, back.since);
  assert.equal((await owner.unlock(id, tabA)).status, 204); assert.equal((await owner.json('/api/saves/' + id)).lock, null);
  assert.equal((await owner.unlock(id, tabA)).status, 204);
  // Free again, but tab B was overridden and has not held it since: it is told first, and its upload is refused.
  assert.equal((await expect(await owner.lock(id, tabB), 409, 'lock_overridden')).lock, null);
  await expect(await owner.put(id, 3, save(runXml(6)), tabB), 409, 'lock_overridden');
  assert.equal((await expect(await owner.lock(id, tabB, false, true), 200)).instance, 'tab-b');
  assert.equal((await owner.unlock(id, tabB)).status, 204);
  assert.equal((await expect(await owner.lock(id, tabB), 200)).instance, 'tab-b');
  assert.equal((await owner.unlock(id, tabB)).status, 204);
  await expect(await owner.lock(id, tabC), 200); assert.equal((await owner.unlock(id, tabC)).status, 204);
  pass('An overridden instance can take the lock back; once the lock is free it is still told until it acknowledges; unlock is for the holder only; a free lock goes to anyone else');

  // Without a lock anyone may save; an overridden instance still learns it while another holds the lock.
  assert.equal((await expect(await owner.put(id, 3, save(runXml(5), {name: 'Renamed'}), tabC), 200)).revision, 4);
  pass('A save without a lock accepts uploads from any instance');
}

// Snapshots: the last ten of every save, labelled by what made them; restore makes a new revision and keeps the newer ones.
{
  const id = randomUUID();
  let revision = 0;
  const kinds = ['auto', 'manual', 'import', 'restore', null, 'other'];
  for (let i = 1; i <= 12; i++) revision = (await expect(await owner.put(id, revision, save(runXml('s' + i), {name: 'Snap ' + i, meta: {i}, kind: kinds[i % kinds.length]}), tabA), 200)).revision;
  let list = await owner.json(`/api/saves/${id}/snapshots`);
  assert.deepEqual(list.map(s => s.revision), [12, 11, 10, 9, 8, 7, 6, 5, 4, 3]);
  assert.deepEqual(list.slice(0, 6).map(s => s.kind), ['auto', null, null, null, 'import', 'manual']);
  assert.ok(list.every(s => s.createdUtc.endsWith('Z')));
  const payload = await owner.json(`/api/saves/${id}/snapshots/5`);
  assert.deepEqual(payload, {name: 'Snap 5', setup: vanilla, meta: {i: 5}, run: runXml('s5'), revision: 5});
  await expect(await owner.request(`/api/saves/${id}/snapshots/2`), 404, 'not_found');
  pass('Each upload makes a snapshot; a save keeps its last ten; a client cannot label one a restore');

  await owner.json(`/api/saves/${id}/lock`, 'POST', {override: false}, tabA);
  await expect(await owner.restore(id, 5, 11, tabA), 409, 'revision_conflict');
  await expect(await owner.restore(id, 12, 12, tabA), 400, 'already_current');
  await expect(await owner.restore(id, 2, 12, tabA), 404, 'not_found');
  await expect(await owner.restore(id, 5, 12, device('tab-a')), 200).then(r => assert.equal(r.revision, 13));
  const restored = await owner.json('/api/saves/' + id);
  assert.equal(restored.revision, 13); assert.equal(restored.run, runXml('s5')); assert.equal(restored.name, 'Snap 5'); assert.deepEqual(restored.meta, {i: 5});
  list = await owner.json(`/api/saves/${id}/snapshots`);
  assert.deepEqual(list.map(s => [s.revision, s.kind]).slice(0, 3), [[13, 'restore'], [12, 'auto'], [11, null]]);
  assert.deepEqual(list.map(s => s.revision), [13, 12, 11, 10, 9, 8, 7, 6, 5, 4]);
  assert.equal((await owner.json(`/api/saves/${id}/snapshots/13`)).revision, 13);
  // The restore can itself be undone: the snapshot from before it is still there.
  assert.equal((await expect(await owner.restore(id, 12, 13, tabA), 200)).revision, 14);
  assert.equal((await owner.json('/api/saves/' + id)).run, runXml('s12'));
  assert.equal((await owner.unlock(id, tabA)).status, 204);
  pass('Restore needs the current revision and the lock, makes a new revision and keeps the newer snapshots');
}

// Delete leaves a tombstone for 90 days: 410 on read, no re-creation, listed under deleted.
{
  const id = randomUUID();
  const since = (await expect(await owner.put(id, 0, save(runXml('gone')), tabA), 200)).updatedUtc;
  await owner.json(`/api/saves/${id}/lock`, 'POST', {override: false}, tabB);
  await expect(await owner.remove(id, tabA), 409, 'locked');
  await expect(await owner.remove(randomUUID(), tabA), 404, 'not_found');
  assert.equal((await owner.remove(id, tabA, true)).status, 204);
  const gone = await expect(await owner.request('/api/saves/' + id), 410, 'deleted'); assert.ok(gone.deletedUtc.endsWith('Z'));
  await expect(await owner.request(`/api/saves/${id}/snapshots`), 410, 'deleted');
  await expect(await owner.lock(id, tabB), 410, 'deleted');
  assert.equal((await owner.request(`/api/saves/${id}/snapshots/1`)).status, 404);
  assert.equal((await expect(await owner.put(id, 0, save(runXml('again')), tabA), 409, 'deleted')).deletedUtc, gone.deletedUtc);
  await expect(await owner.put(id, 1, save(runXml('again')), tabA), 409, 'deleted');
  assert.equal((await owner.remove(id, tabA)).status, 204);
  const list = await owner.json('/api/saves');
  assert.ok(!list.saves.some(s => s.id === id)); assert.deepEqual(list.deleted.find(d => d.id === id), {id, deletedUtc: gone.deletedUtc});
  assert.deepEqual((await owner.json('/api/saves?since=' + encodeURIComponent(since))).deleted.map(d => d.id), [id]);
  pass('Delete (override needed while another instance holds the lock) leaves a tombstone: 410, no re-creation, listed as deleted');
}

// At most 30 saves per account.
{
  const filler = await new Client().player('user05'); await filler.clear(tabA);
  const ids = Array.from({length: 30}, () => randomUUID());
  for (const id of ids) await expect(await filler.put(id, 0, save(null, {name: 'Slot'}), tabA), 200);
  await expect(await filler.put(randomUUID(), 0, save(null), tabA), 409, 'saves_full');
  assert.equal((await filler.json('/api/saves')).saves.length, 30);
  assert.equal((await filler.remove(ids[0], tabA)).status, 204);
  await expect(await filler.put(randomUUID(), 0, save(null), tabA), 200);
  await expect(await filler.put(ids[0], 0, save(null), tabA), 409, 'deleted');
  pass('An account has at most 30 saves');
  await filler.clear(tabA);
}

// A save holds its profile: one upload, one revision, one snapshot.
{
  const id = randomUUID(), profile = n => `<xftl-profile listTutorial="1"><ach id="A${n}" diff="EASY"/></xftl-profile>`;
  const created = await expect(await owner.put(id, 0, {...save(runXml('p1')), profile: profile(1)}, tabA), 200);
  assert.equal(created.revision, 1);
  const one = await owner.json('/api/saves/' + id);
  assert.equal(one.profile, profile(1)); assert.equal(one.run, runXml('p1')); assert.equal(one.hasProfile, true); assert.equal(one.profileBytes, profile(1).length);
  const listed = (await owner.json('/api/saves')).saves.find(s => s.id === id);
  assert.equal(listed.hasProfile, true); assert.equal(listed.profileBytes, profile(1).length); assert.equal(listed.profile, undefined);
  // No profile sent: the stored one stays. The run can end and the unlocks stay.
  await expect(await owner.put(id, 1, save(runXml('p2')), tabA), 200);
  assert.equal((await owner.json('/api/saves/' + id)).profile, profile(1));
  await expect(await owner.put(id, 2, {...save(null), profile: profile(2)}, tabA), 200);
  const ended = await owner.json('/api/saves/' + id);
  assert.equal(ended.hasRun, false); assert.equal(ended.run, null); assert.equal(ended.profile, profile(2));
  // A snapshot holds both, and a restore brings both back.
  const snap = await owner.json(`/api/saves/${id}/snapshots/1`);
  assert.equal(snap.profile, profile(1)); assert.equal(snap.run, runXml('p1'));
  assert.equal((await owner.json(`/api/saves/${id}/snapshots/2`)).profile, profile(1), 'an upload without a profile snapshots the stored one');
  await expect(await owner.lock(id, tabA), 200);
  await expect(await owner.restore(id, 1, 3, tabA), 200);
  const back = await owner.json('/api/saves/' + id);
  assert.equal(back.profile, profile(1)); assert.equal(back.run, runXml('p1')); assert.equal(back.revision, 4);
  await expect(await owner.unlock(id, tabA), 204);
  // The profile's rules: its root, no DTD, its size and shape.
  for (const bad of ['<other/>', '<!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><xftl-profile>&e;</xftl-profile>', 'not xml', `<xftl-profile pad="${'x'.repeat(4 * 1024 * 1024)}"/>`, `<xftl-profile>${'<a/>'.repeat(100000)}</xftl-profile>`])
    await expect(await owner.put(id, 4, {...save(null), profile: bad}, tabA), 400, 'bad_profile');
  assert.equal((await owner.json('/api/saves/' + id)).revision, 4);
  // Saves share nothing.
  const second = randomUUID();
  await expect(await owner.put(second, 0, save(null), tabA), 200);
  assert.equal((await owner.json('/api/saves/' + second)).profile, null);
  await expect(await owner.remove(second, tabA), 204); await expect(await owner.remove(id, tabA), 204);
  // The shared profiles are gone: an old page learns it.
  for (const [path, method] of [['/api/profiles/vanilla', 'GET'], ['/api/profiles/vanilla', 'PUT']]) await expect(await owner.request(path, method, method === 'PUT' ? {base: null, mine: '<xftl-profile/>', baseRevision: 0} : undefined), 410, 'saves_v2');
  pass('A save holds its profile: uploaded, snapshotted and restored with its run; invalid ones refused; /api/profiles answers 410');
}

// Another account sees none of this, and its own save of the same id is its own.
{
  const id = first;
  assert.deepEqual((await other.json('/api/saves')).saves, []);
  await expect(await other.request('/api/saves/' + id), 404, 'not_found');
  await expect(await other.request(`/api/saves/${id}/snapshots`), 404, 'not_found');
  await expect(await other.request(`/api/saves/${id}/snapshots/1`), 404, 'not_found');
  await expect(await other.put(id, 4, save(runXml('mine')), tabA), 404, 'not_found');
  await expect(await other.request('/api/saves/' + id, 'PATCH', {name: 'mine now'}), 404, 'not_found');
  await expect(await other.lock(id, tabA, true), 404, 'not_found');
  await expect(await other.unlock(id, tabA), 404, 'not_found');
  await expect(await other.remove(id, tabA, true), 404, 'not_found');
  await expect(await other.restore(id, 1, 4, tabA), 404, 'not_found');
  await expect(await other.put(id, 0, save(runXml('mine')), tabA), 200);
  assert.equal((await other.json('/api/saves/' + id)).run, runXml('mine'));
  const own = await owner.json('/api/saves/' + id); assert.equal(own.name, 'Renamed'); assert.equal(own.run, runXml(5)); assert.equal(own.revision, 4);
  await expect(await other.remove(id, tabA), 204); await expect(await owner.request('/api/saves/' + id), 200);
  pass("Another account can neither read, write, lock nor delete an account's saves or snapshots (404)");
}

// The account storage limit counts runs, profiles, meta and snapshots.
{
  const full = await new Client().player('user06'); await full.clear(tabA);
  const {limits, usedBytes} = await full.json('/api/saves');
  assert.ok(limits.bytes <= 8 * 1024 * 1024, 'start the fixture with a small FTL_SAVE_BYTES_LIMIT (Development only)');
  const run = n => `<xftlSaveGame><pad v="${'x'.repeat(n)}"/></xftlSaveGame>`, size = Math.floor((limits.bytes - usedBytes) * 0.3);
  const id = randomUUID();
  await expect(await full.put(id, 0, save(run(size)), tabA), 200);
  await expect(await full.put(id, 1, save(run(size)), tabA), 200);
  const before = await full.json('/api/saves');
  assert.ok(before.usedBytes > 3 * size && before.usedBytes <= limits.bytes);
  await expect(await full.put(id, 2, save(run(size)), tabA), 409, 'storage_full');
  assert.equal((await full.json('/api/saves/' + id)).revision, 2); assert.equal((await full.json(`/api/saves/${id}/snapshots`)).length, 2);
  assert.equal((await full.json('/api/saves')).usedBytes, before.usedBytes);
  await expect(await full.put(randomUUID(), 0, save(run(size)), tabA), 409, 'storage_full');
  await expect(await full.put(id, 2, {...save(null), profile: `<xftl-profile pad="${'x'.repeat(Math.min(size * 2, 4000000))}"/>`}, tabA), 409, 'storage_full');
  await expect(await full.put(id, 2, save(null), tabA), 200);
  await full.clear(tabA);
  assert.ok((await full.json('/api/saves')).usedBytes < before.usedBytes / 4);
  pass('Writes that would exceed the account storage limit are refused (409 storage_full) and change nothing');
}

console.log(`${passed} save model 3 checks passed`);
