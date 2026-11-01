// Unit group: our MEGA reader (web/mods/mega.mjs) against a file encrypted the way MEGA stores it (the F1 fixture with a
// fixed key, encrypted by Node's own AES), the API error codes it turns into next steps, and the resumable SHA-256.
// No network: fetch is injected. The live check against the official Multiverse Data link is
// `node tools/mods/fetch-mods.mjs <dir> --only multiverse-data-5.5.1` (developer, needs the internet).
import assert from 'node:assert/strict';
import {createHash, randomBytes} from 'node:crypto';
import {createDecryptor, decryptAttributes, fileKey, MegaError, megaFileInfo, megaRange, parseMegaLink} from '../web/mods/mega.mjs';
import {createSha256} from '../web/mods/sha256.mjs';
import {MEGA_HANDLE, MEGA_KEY, MEGA_STORAGE, megaAttributes, megaEncrypt, megaLink, testCatalogue} from './fixtures/mods/catalogue.mjs';

const pass = label => console.log('PASS ' + label);
const sha = b => createHash('sha256').update(b).digest('hex');
const {archives: {override}, catalogue} = testCatalogue(), entry = catalogue.files.find(f => f.id === 'f1-override');
const plain = override.bytes, cipher = megaEncrypt(plain);

// Links.
const link = parseMegaLink(megaLink);
assert.equal(link.handle, MEGA_HANDLE); assert.deepEqual(Buffer.from(link.key), MEGA_KEY);
assert.deepEqual(parseMegaLink(`https://mega.nz/#!${MEGA_HANDLE}!${megaLink.split('#')[1]}`).handle, MEGA_HANDLE);
for (const bad of ['https://mega.nz/file/short#x', 'http://mega.nz/file/F1fixtur#' + 'a'.repeat(43), 'https://evil.example/file/F1fixtur#' + 'a'.repeat(43), 'https://mega.nz/folder/F1fixtur#' + 'a'.repeat(22)])
  assert.throws(() => parseMegaLink(bad), e => e instanceof MegaError && e.kind === 'bad-link', bad);
pass('MEGA file links parse (new and legacy form); other hosts, folders and short keys are refused');

// Content decryption at any 16-byte offset, in ranges of several sizes, equals the plaintext.
const decrypt = await createDecryptor(fileKey(link.key));
for (const size of [16, 160, 256, 1024, plain.length + 16]) {
  const parts = [];
  for (let o = 0; o < cipher.length; o += size) parts.push(await decrypt(cipher.subarray(o, Math.min(cipher.length, o + size)), o));
  assert.equal(sha(Buffer.concat(parts)), entry.sha256, 'range size ' + size);
}
await assert.rejects(decrypt(cipher.subarray(5, 21), 5), e => e.kind === 'crypto');
// A larger random file crossing many counter blocks (and the 32-bit counter word boundary arithmetic).
const big = randomBytes(1 << 20), bigCipher = megaEncrypt(big);
assert.equal(sha(await decrypt(bigCipher, 0)), sha(big));
assert.equal(sha(await decrypt(bigCipher.subarray(65536), 65536)), sha(big.subarray(65536)));
pass(`AES-128-CTR decryption matches the plaintext for every range size (${plain.length} B fixture, 1 MiB random)`);

assert.deepEqual(await decryptAttributes(megaAttributes('f1-override.zip'), fileKey(link.key).aes), {n: 'f1-override.zip'});
assert.equal(await decryptAttributes(megaAttributes('x').slice(0, -2), fileKey(link.key).aes), null);
pass('MEGA attributes (AES-CBC, zero padding) decrypt to the file name; damaged attributes give null');

// The API call and the storage ranges with an injected fetch.
const json = body => new Response(JSON.stringify(body), {status: 200, headers: {'Content-Type': 'application/json'}});
const api = answer => async (url, init) => { assert.ok(url.startsWith('https://g.api.mega.co.nz/cs?id=')); assert.equal(init.method, 'POST'); assert.match(init.headers['Content-Type'], /^text\/plain/); assert.deepEqual(JSON.parse(init.body), [{a: 'g', g: 1, ssl: 2, p: MEGA_HANDLE}]); return answer(); };
const info = await megaFileInfo(megaLink, {fetch: api(() => json([{s: plain.length, at: megaAttributes('f1-override.zip'), g: MEGA_STORAGE}]))});
assert.equal(info.size, plain.length); assert.equal(info.name, 'f1-override.zip'); assert.equal(info.url, MEGA_STORAGE);
const storage = async url => { const m = /\/(\d+)-(\d+)$/.exec(url); return new Response(cipher.subarray(Number(m[1]), Number(m[2]) + 1)); };
assert.equal(sha(await decrypt(await megaRange(info.url, 0, plain.length - 1, {fetch: storage}), 0)), entry.sha256);
pass('megaFileInfo asks MEGA for the handle (simple CORS request) and megaRange + decrypt give the pinned file');

const kinds = [];
for (const [answer, kind] of [[() => json([-9]), 'removed'], [() => json([-16]), 'removed'], [() => json([-17]), 'quota'], [() => json([-4]), 'rate'], [() => json({err: -11}), 'denied'],
  [() => json([{s: 1, g: MEGA_STORAGE, tl: 600}]), 'quota'], [() => json([{s: 1, g: 'https://evil.example/dl/x'}]), 'api'],
  [() => new Response('', {status: 402, headers: {'X-Hashcash': '1:1:1:x'}}), 'busy'], [() => { throw new TypeError('Failed to fetch'); }, 'network'], [() => new Response('nope', {status: 403}), 'http']]) {
  const error = await megaFileInfo(megaLink, {fetch: api(answer), attempts: 1}).then(() => null, e => e);
  assert.ok(error instanceof MegaError, kind); assert.equal(error.kind, kind, JSON.stringify(error.message)); kinds.push(kind);
}
const quota = await megaFileInfo(megaLink, {fetch: api(() => json([{s: 1, g: MEGA_STORAGE, tl: 600}]))}).catch(e => e);
assert.equal(quota.retryAfter, 600);
let tries = 0;
const retried = await megaFileInfo(megaLink, {fetch: api(() => ++tries < 2 ? json([-3]) : json([{s: 5, g: MEGA_STORAGE}]))});
assert.equal(retried.size, 5); assert.equal(tries, 2);
for (const [response, kind] of [[new Response('', {status: 509}), 'quota'], [new Response('', {status: 404}), 'expired'], [new Response(new Uint8Array(3)), 'network']])
  assert.equal((await megaRange(MEGA_STORAGE, 0, 15, {fetch: async () => response}).catch(e => e)).kind, kind);
pass('MEGA errors map to next steps: ' + [...new Set(kinds)].join(', ') + '; EAGAIN is retried; storage 509/404/short reads');

// Resumable SHA-256 (downloads continue after a reload with the stored state).
for (const cut of [0, 1, 63, 64, 65, 1000, big.length - 1]) {
  const a = createSha256(); a.update(big.subarray(0, cut));
  const b = createSha256(structuredClone(a.state())); b.update(big.subarray(cut));
  assert.equal(b.hex(), sha(big), 'cut ' + cut);
}
pass('incremental SHA-256 resumes from a stored state at any byte');
