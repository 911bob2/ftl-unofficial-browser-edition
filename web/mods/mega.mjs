// Our own MEGA public-file reader (no MEGA library): link parsing, the "g" API call, AES-128-CTR content decryption
// and AES-128-CBC attribute decryption with WebCrypto. Runs in browsers and in Node (tools/mods/fetch-mods.mjs).
// The integrity check is the catalogue SHA-256 of the decrypted file, not MEGA's chunk MACs.
//   link  https://mega.nz/file/<handle>#<key>  (or the legacy https://mega.nz/#!<handle>!<key>)
//   key   32 bytes = 8 big-endian uint32 k0..k7; AES key = k0^k4, k1^k5, k2^k6, k3^k7; CTR nonce = k4, k5
export const MEGA_API = 'https://g.api.mega.co.nz/cs';
const subtle = () => { const s = globalThis.crypto?.subtle; if (!s) throw new MegaError('crypto', 'This browser cannot decrypt MEGA files here (Web Crypto needs HTTPS).'); return s; };

/** kind: network | removed | quota | rate | busy | denied | bad-link | http | crypto | api. */
export class MegaError extends Error {
  constructor(kind, message, extra = {}) { super(message); this.name = 'MegaError'; this.kind = kind; Object.assign(this, extra); }
}
const CODES = {
  '-1': ['api', 'MEGA reported an internal error.'], '-2': ['bad-link', 'MEGA rejected the link.'], '-3': ['rate', 'MEGA is busy. Try again in a moment.'],
  '-4': ['rate', 'MEGA is limiting requests from your connection. Wait a few minutes and retry.'], '-5': ['api', 'MEGA could not complete the request.'],
  '-6': ['rate', 'Too many requests to MEGA. Wait a few minutes and retry.'], '-8': ['removed', 'The MEGA link has expired.'],
  '-9': ['removed', 'The file is no longer on MEGA (deleted or the link changed).'], '-11': ['denied', 'MEGA refused access to the file.'],
  '-14': ['bad-link', 'The MEGA link key is wrong.'], '-16': ['removed', 'MEGA has taken this file down.'],
  '-17': ['quota', 'Your MEGA transfer quota is used up.'], '-18': ['busy', 'MEGA is temporarily unavailable. Try again later.'],
  '-19': ['rate', 'Too many connections to MEGA. Close other MEGA downloads and retry.'],
};
export function megaError(code, extra = {}) {
  const [kind, message] = CODES[String(code)] ?? ['api', `MEGA returned error ${code}.`];
  return new MegaError(kind, message, {code, ...extra});
}

function b64(s) {
  const t = s.replace(/-/g, '+').replace(/_/g, '/').replace(/,/g, '');
  const bin = atob(t + '='.repeat((4 - t.length % 4) % 4)), out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

/** {handle, key: Uint8Array(32)} from a public file link; throws MegaError('bad-link'). */
export function parseMegaLink(link) {
  const m = /^https:\/\/mega\.(?:nz|co\.nz)\/(?:file\/([\w-]{8})#([\w-]{43})|#!([\w-]{8})!([\w-]{43}))$/.exec(String(link).trim());
  if (!m) throw new MegaError('bad-link', 'This is not a MEGA file link.');
  const key = b64(m[2] ?? m[4]);
  if (key.length !== 32) throw new MegaError('bad-link', 'The MEGA link key is incomplete.');
  return {handle: m[1] ?? m[3], key};
}

/** AES key and CTR nonce of a 32-byte file key. */
export function fileKey(key) {
  const v = new DataView(key.buffer, key.byteOffset, 32), aes = new Uint8Array(16), nonce = new Uint8Array(8), out = new DataView(aes.buffer);
  for (let i = 0; i < 4; i++) out.setUint32(i * 4, (v.getUint32(i * 4) ^ v.getUint32(16 + i * 4)) >>> 0);
  nonce.set(key.subarray(16, 24));
  return {aes, nonce};
}

/** Decrypted attributes ({n: file name, ...}) of the API's `at` field; null when they do not decode. */
export async function decryptAttributes(at, aes) {
  const data = b64(at);
  if (!data.length || data.length % 16) return null;
  const k = await subtle().importKey('raw', aes, 'AES-CBC', false, ['encrypt', 'decrypt']);
  // MEGA pads with zero bytes, WebCrypto expects PKCS#7: append the block that encrypts a full padding block, so the
  // decryption strips exactly that block and returns MEGA's plaintext.
  const pad = new Uint8Array(await subtle().encrypt({name: 'AES-CBC', iv: data.subarray(data.length - 16)}, k, new Uint8Array(0)));
  const joined = new Uint8Array(data.length + 16); joined.set(data); joined.set(pad, data.length);
  const text = new TextDecoder().decode(await subtle().decrypt({name: 'AES-CBC', iv: new Uint8Array(16)}, k, joined)).replace(/\0+$/, '');
  if (!text.startsWith('MEGA{')) return null;
  try { return JSON.parse(text.slice(4)); } catch { return null; }
}

/** AES-128-CTR decryptor for a byte range starting at `offset` (a multiple of 16). */
export async function createDecryptor({aes, nonce}) {
  const k = await subtle().importKey('raw', aes, 'AES-CTR', false, ['decrypt']);
  return async (bytes, offset) => {
    if (offset % 16) throw new MegaError('crypto', 'MEGA ranges must start on a 16-byte boundary.');
    const counter = new Uint8Array(16), v = new DataView(counter.buffer), block = offset / 16;
    counter.set(nonce); v.setUint32(8, Math.floor(block / 0x100000000)); v.setUint32(12, block >>> 0);
    return new Uint8Array(await subtle().decrypt({name: 'AES-CTR', counter, length: 64}, k, bytes));
  };
}

// ---- MEGA's proof of work (HTTP 402 with X-Hashcash), as MEGA's own public clients answer it: the web client
// (meganz/webclient js/utils/api.js, MEGAPIRequest.fetch and gencash) and the SDK (meganz/sdk src/hashcash.cpp,
// src/posix/net.cpp). The API asks for it when it is under load; its CORS rules let any page send the answer
// (Access-Control-Allow-Headers and -Expose-Headers name X-Hashcash). The challenge "1:<easiness>:<time>:<token>"
// (token: 64 base64url characters, 48 bytes) is answered by a 4-byte prefix whose SHA-256 over prefix + the token
// 262144 times (12 MiB) starts with a big-endian 32-bit number at or below the easiness's threshold; the request is
// sent again with "X-Hashcash: 1:<token>:<base64url prefix>".
export const HASHCASH_REPEAT = 262144;
/** The SDK gives up on a challenge after this long (its token lives 300 s; the SDK's own budget is 100-150 s). */
export const HASHCASH_BUDGET_MS = 270000;
const HASHCASH_SOLVES = 3;

function b64url(bytes) {
  let bin = '';
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

/** The challenge of an X-Hashcash answer header ({token, bytes, easiness}), or null when it is not one (the SDK's checks). */
export function hashcashChallenge(header) {
  const p = String(header ?? '').trim().split(':');
  if (p.length !== 4 || Number(p[0]) !== 1) return null;
  const easiness = Number(p[1]);
  if (!Number.isInteger(easiness) || easiness < 0 || easiness > 255 || p[3].length !== 64 || !/^[\w-]+$/.test(p[3])) return null;
  const bytes = b64(p[3]);
  return bytes.length === 48 ? {token: p[3], bytes, easiness} : null;
}

/** The largest acceptable first word of the hash: ((e & 63) * 2 + 1) << ((e >> 6) * 7 + 3), as both clients compute it. */
export const hashcashThreshold = easiness => ((((easiness & 63) << 1) + 1) << ((easiness >> 6) * 7 + 3)) >>> 0;

/** prefix + token x 262144, the buffer every attempt hashes. */
function hashcashBuffer(bytes) {
  const buffer = new Uint8Array(4 + HASHCASH_REPEAT * 48);
  buffer.set(bytes, 4);
  for (let filled = 48; filled < HASHCASH_REPEAT * 48; filled *= 2) buffer.copyWithin(4 + filled, 4, 4 + Math.min(filled, HASHCASH_REPEAT * 48 - filled));
  return buffer;
}

/** Whether `solution` (base64url of 4 bytes) answers the challenge (the SDK's validateHashcash). */
export async function verifyHashcash(challenge, solution) {
  const prefix = b64(solution);
  if (prefix.length !== 4) return false;
  const buffer = hashcashBuffer(challenge.bytes); buffer.set(prefix, 0);
  return new DataView(await subtle().digest('SHA-256', buffer)).getUint32(0) <= hashcashThreshold(challenge.easiness);
}

/**
 * Finds the prefix: `lanes` digests in flight at once (WebCrypto hashes off the page's thread), lane w trying the
 * nonces w, w + lanes, ... (big-endian, as the SDK writes them). Throws MegaError('busy') after `budget` ms.
 */
export async function solveHashcash(challenge, {signal, lanes = Math.max(1, Math.min(4, globalThis.navigator?.hardwareConcurrency || 2)), budget = HASHCASH_BUDGET_MS, now = () => Date.now()} = {}) {
  const threshold = hashcashThreshold(challenge.easiness), end = now() + budget;
  let found = null, done;
  const finished = new Promise(resolve => { done = resolve; });
  const lane = async w => {
    const buffer = hashcashBuffer(challenge.bytes), view = new DataView(buffer.buffer);
    for (let n = w; found == null && n <= 0xffffffff; n += lanes) {
      if (signal?.aborted) throw new DOMException('Aborted', 'AbortError');
      if (now() > end) throw new MegaError('busy', 'MEGA asks this connection for more proof-of-work than this device managed in time (it is under load). Try again later.');
      view.setUint32(0, n);
      if (new DataView(await subtle().digest('SHA-256', buffer)).getUint32(0) <= threshold) { found ??= buffer.slice(0, 4); done(); }
    }
  };
  const runs = Array.from({length: lanes}, (_, w) => lane(w));
  runs.forEach(r => r.catch(() => {}));
  // the first answer ends every lane at its next step; a lane's error (budget, abort) ends the search
  await Promise.race([finished, Promise.all(runs)]);
  if (!found) throw new MegaError('busy', 'MEGA\'s proof-of-work challenge has no answer.');
  return b64url(found);
}

let sequence = Math.floor(Math.random() * 1e9);
/**
 * Ask MEGA for a temporary download URL. Returns {handle, size, url, name, key: {aes, nonce}}. `fetch` may be
 * injected (tests). Errors are MegaError with a kind the UI turns into a next step.
 */
export async function megaFileInfo(link, {fetch = globalThis.fetch, signal, attempts = 3, onProof = () => {}, solve = solveHashcash} = {}) {
  const {handle, key} = parseMegaLink(link), keys = fileKey(key);
  /** One request; a 402 challenge is answered and the request sent again with the answer (at most three times). */
  const post = async () => {
    const url = MEGA_API + '?id=' + (sequence++);
    for (let solved = 0, cash = null; ; solved++) {
      // text/plain keeps the first try a CORS "simple" request (MEGA reads the JSON body either way); the answer's
      // X-Hashcash header is one MEGA's CORS rules allow
      const headers = {'Content-Type': 'text/plain;charset=UTF-8', ...(cash ? {'X-Hashcash': cash} : {})};
      let response;
      try { response = await fetch(url, {method: 'POST', headers, body: JSON.stringify([{a: 'g', g: 1, ssl: 2, p: handle}]), signal, cache: 'no-store', credentials: 'omit'}); }
      catch (e) { if (e?.name === 'AbortError') throw e; throw new MegaError('network', 'Could not connect to mega.nz.'); }
      if (response.status !== 402) return response;
      const challenge = hashcashChallenge(response.headers.get('X-Hashcash'));
      if (!challenge) throw new MegaError('busy', 'MEGA answered 402 without a proof-of-work challenge it can be answered with. Try again later.');
      if (solved >= HASHCASH_SOLVES) throw new MegaError('busy', 'MEGA keeps asking this connection for proof-of-work (it is under load). Try again later.');
      onProof({easiness: challenge.easiness, threshold: hashcashThreshold(challenge.easiness)});
      cash = '1:' + challenge.token + ':' + await solve(challenge, {signal});
    }
  };
  let last;
  for (let attempt = 0; attempt < attempts; attempt++) {
    if (attempt) await new Promise(r => setTimeout(r, 1000 * 2 ** attempt));
    const response = await post();
    if (!response.ok) { last = new MegaError(response.status === 429 ? 'rate' : 'http', `MEGA answered HTTP ${response.status}.`, {status: response.status}); if (response.status >= 500 || response.status === 429) continue; throw last; }
    let body;
    try { body = await response.json(); } catch { last = new MegaError('api', 'MEGA sent an unreadable answer.'); continue; }
    const r = Array.isArray(body) ? body[0] : body;
    const code = typeof r === 'number' ? r : typeof body === 'number' ? body : r?.err ?? r?.e;
    if (typeof code === 'number' && code < 0) { last = megaError(code); if (code === -3) continue; throw last; }
    if (!r || typeof r.s !== 'number' || typeof r.g !== 'string') { last = new MegaError('api', 'MEGA did not return a download address.'); continue; }
    if (r.tl > 0) throw megaError(-17, {retryAfter: r.tl});
    if (!/^https:\/\/[\w.-]+\.userstorage\.mega\.co\.nz\//.test(r.g)) throw new MegaError('api', 'MEGA returned an unexpected download host.');
    const attributes = r.at ? await decryptAttributes(r.at, keys.aes).catch(() => null) : null;
    return {handle, size: r.s, url: r.g, name: attributes?.n ?? null, key: keys};
  }
  throw last;
}

/** Fetch encrypted bytes [start, end] (inclusive) from a MEGA storage URL. */
export async function megaRange(url, start, end, {fetch = globalThis.fetch, signal} = {}) {
  let response;
  try { response = await fetch(`${url}/${start}-${end}`, {signal, cache: 'no-store', credentials: 'omit'}); }
  catch (e) { if (e?.name === 'AbortError') throw e; throw new MegaError('network', 'The connection to mega.nz was interrupted.'); }
  if (response.status === 509) throw megaError(-17, {retryAfter: Number(response.headers.get('Retry-After')) || null});
  if (response.status === 403 || response.status === 404) throw new MegaError('expired', 'The MEGA download address expired.', {status: response.status});
  if (!response.ok) throw new MegaError('http', `mega.nz answered HTTP ${response.status}.`, {status: response.status});
  let bytes;
  try { bytes = new Uint8Array(await response.arrayBuffer()); }
  catch (e) { if (e?.name === 'AbortError') throw e; throw new MegaError('network', 'The connection to mega.nz was interrupted.'); }
  if (bytes.length !== end - start + 1) throw new MegaError('network', 'mega.nz sent an incomplete block.');
  return bytes;
}
