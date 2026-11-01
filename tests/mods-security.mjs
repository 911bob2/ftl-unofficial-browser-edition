// Security group: mod file downloads (role rules enforced by the server, not the page), the CSP for the MEGA reader,
// and what Lua patch scripts can reach at merge time (tests/lib/mods-lua-security.mjs).
// Cloud saves, modded or not, are tested in tests/saves-v2.mjs.
// Runs on the disposable security fixture, whose test catalogue (tests/fixtures/mods/catalogue.mjs) offers our F1
// archives with the Hyperspace rule (f1-content) and the Multiverse rule (f1-override).
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createHash, randomUUID} from 'node:crypto';
import {testCatalogue} from './fixtures/mods/catalogue.mjs';
import {luaSecurityChecks} from './lib/mods-lua-security.mjs';

const origin = process.env.TEST_ORIGIN ?? 'http://localhost:8081';
if (!['http://localhost:8081', 'http://localhost:8082'].includes(origin)) throw Error('This test mutates disposable accounts; use an isolated localhost:8081 or :8082 instance.');
const fixture = process.env.TEST_FIXTURE_DIR ?? (origin.endsWith(':8082') ? '.build/security-data' : '.build/test-data');
for (let i = 0; !await fetch(origin + '/health/ready').then(r => r.ok).catch(() => false); i++) { if (i > 80) throw Error('Fixture did not become ready'); await new Promise(r => setTimeout(r, 250)); }
const credentials = new Map((await readFile(fixture + '/bootstrap-credentials.txt', 'utf8')).split(/\r?\n/).filter(l => l.includes('\t')).map(l => l.split('\t')));
const pass = label => console.log('PASS ' + label);
const sha = b => createHash('sha256').update(b).digest('hex');
const {archives} = testCatalogue();

// Lua patch scripts (merge-time mod code): pure checks on the patch runtime, before the server ones.
await luaSecurityChecks(pass);

class Client {
  cookies = new Map(); csrf = null;
  async request(path, method = 'GET', body, extra = {}) {
    const r = await fetch(origin + path, {method, headers: {Cookie: [...this.cookies].map(([k, v]) => `${k}=${v}`).join('; '), ...(body === undefined ? {} : {'Content-Type': 'application/json'}), ...(method === 'GET' ? {} : {'X-CSRF-TOKEN': this.csrf}), Origin: origin, ...extra}, body: body === undefined ? undefined : JSON.stringify(body)});
    for (const c of r.headers.getSetCookie()) { const [pair] = c.split(';'); const [k, ...v] = pair.split('='); if (v.join('=')) this.cookies.set(k, v.join('=')); else this.cookies.delete(k); }
    return r;
  }
  async token() { this.csrf = (await (await this.request('/api/auth/csrf')).json()).token; }
  async login(user, password) { await this.token(); const r = await this.request('/api/auth/login', 'POST', {username: user, password}); assert.equal(r.status, 200, 'login ' + user); await this.token(); }
  async player(user) {
    const initial = credentials.get(user), password = 'mods-security-password-' + user;
    await this.token(); let r = await this.request('/api/auth/login', 'POST', {username: user, password});
    if (r.status === 401) { await this.login(user, initial); await this.json('/api/auth/password', 'POST', {currentPassword: initial, newPassword: password}); await this.login(user, password); } else { assert.equal(r.status, 200); await this.token(); }
    return this;
  }
  async json(path, method = 'GET', body, extra = {}) { const r = await this.request(path, method, body, extra); assert.ok(r.ok, `${path}: HTTP ${r.status} ${await r.clone().text()}`); return r.status === 204 ? null : r.json(); }
}

// Signed out: no catalogue with server rights, no files; the public catalogue offers nothing from the server.
const anonymous = new Client();
assert.equal((await anonymous.request('/api/mods/catalogue')).status, 401);
assert.equal((await anonymous.request('/api/mods/files/f1-content')).status, 401);
const publicCatalogue = await (await anonymous.request('/api/public/mods/catalogue')).json();
assert.deepEqual(publicCatalogue.files.map(f => [f.id, f.server.available, f.server.reason]), [['f1-content', false, 'sign-in'], ['f1-override', false, 'sign-in']]);
assert.equal('multiverseRedistribution' in publicCatalogue, false);
pass('Signed-out visitors get no mod files from the server, only the public catalogue');

const csp = (await anonymous.request('/')).headers.get('content-security-policy');
assert.match(csp, /(^|; )connect-src 'self' https:\/\/g\.api\.mega\.co\.nz https:\/\/\*\.userstorage\.mega\.co\.nz(;|$)/);
assert.match(csp, /default-src 'self'/); assert.match(csp, /frame-ancestors 'none'/);
pass("CSP connect-src adds only MEGA's API and storage hosts");

// A Normal user downloads the Hyperspace-rule file, whole or by range; the Multiverse-rule file is never served; unknown ids are 404.
const user = await new Client().player('user07');
const catalogue = await user.json('/api/mods/catalogue');
assert.deepEqual(catalogue.files.map(f => [f.id, f.server.reason]), [['f1-content', 'ready'], ['f1-override', 'not-permitted']]);
assert.equal((await user.request('/api/mods/files/f1-override')).status, 403);
const whole = await user.request('/api/mods/files/f1-content');
assert.equal(whole.status, 200); assert.equal(sha(Buffer.from(await whole.arrayBuffer())), sha(archives.content.bytes)); assert.match(whole.headers.get('cache-control'), /no-store/);
const part = await user.request('/api/mods/files/f1-content', 'GET', undefined, {Range: 'bytes=10-19'});
assert.equal(part.status, 206); assert.equal(part.headers.get('content-range'), `bytes 10-19/${archives.content.bytes.length}`);
assert.deepEqual(Buffer.from(await part.arrayBuffer()), Buffer.from(archives.content.bytes.subarray(10, 20)));
assert.equal((await user.request('/api/mods/files/does-not-exist')).status, 404);
assert.equal((await user.request('/api/mods/files/..%2F..%2Fdata%2Fftl.sqlite')).status, 404);
pass('Normal users download the Hyperspace-rule file whole or by range and get no Multiverse-rule file; unknown or path-like ids are 404');

// An External user (self-registered) gets the Hyperspace-rule file but not the Multiverse-rule one: refused by the server.
const external = new Client(); await external.token();
const name = 'modsext' + randomUUID().slice(0, 8), password = 'mods-security-external-' + randomUUID();
assert.equal((await external.request('/api/auth/register', 'POST', {username: name, password})).status, 200);
await external.token();
assert.equal((await external.json('/api/auth/me')).role, 'external-user');
assert.deepEqual((await external.json('/api/mods/catalogue')).files.map(f => [f.id, f.server.reason]), [['f1-content', 'ready'], ['f1-override', 'not-permitted']]);
const refused = await external.request('/api/mods/files/f1-override');
assert.equal(refused.status, 403); assert.equal((await refused.json()).code, 'mod_not_permitted');
assert.equal((await external.request('/api/mods/files/f1-override', 'GET', undefined, {Range: 'bytes=0-9'})).status, 403);
assert.equal((await external.request('/api/mods/files/f1-content')).status, 200);
pass('External users: Hyperspace-rule file allowed, Multiverse-rule file refused by the server');
