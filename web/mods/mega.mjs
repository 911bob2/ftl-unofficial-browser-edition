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

let sequence = Math.floor(Math.random() * 1e9);
/**
 * Ask MEGA for a temporary download URL. Returns {handle, size, url, name, key: {aes, nonce}}. `fetch` may be
 * injected (tests). Errors are MegaError with a kind the UI turns into a next step.
 */
export async function megaFileInfo(link, {fetch = globalThis.fetch, signal, attempts = 3} = {}) {
  const {handle, key} = parseMegaLink(link), keys = fileKey(key);
  let last;
  for (let attempt = 0; attempt < attempts; attempt++) {
    if (attempt) await new Promise(r => setTimeout(r, 1000 * 2 ** attempt));
    let response;
    // text/plain keeps this a CORS "simple" request (MEGA reads the JSON body either way).
    try { response = await fetch(MEGA_API + '?id=' + (sequence++), {method: 'POST', headers: {'Content-Type': 'text/plain;charset=UTF-8'}, body: JSON.stringify([{a: 'g', g: 1, ssl: 2, p: handle}]), signal, cache: 'no-store', credentials: 'omit'}); }
    catch (e) { if (e?.name === 'AbortError') throw e; throw new MegaError('network', 'Could not connect to mega.nz.'); }
    if (response.status === 402 && response.headers.get('X-Hashcash')) throw new MegaError('busy', 'MEGA asks this connection for a proof-of-work challenge (it is under load). Try again later.');
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
