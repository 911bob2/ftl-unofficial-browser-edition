// Test catalogue for the mod browser and mod download tests: our F1 fixture archives stand in for the pinned files
// (no real mod content in Git or the image). "f1-content" has the Hyperspace rule (every signed-in role may download
// it from the server), "f1-override" the Multiverse rule plus a MEGA link whose bytes the tests serve through a
// fake MEGA (encrypted here with a fixed key, exactly as MEGA stores files).
import {createCipheriv, createHash} from 'node:crypto';
import {mkdir, writeFile} from 'node:fs/promises';
import {join} from 'node:path';
import {f1Archives} from './f1.mjs';

export const MEGA_HANDLE = 'F1fixtur';
export const MEGA_KEY = Buffer.from(Array.from({length: 32}, (_, i) => (i * 37 + 11) & 255));
export const MEGA_STORAGE = 'https://gfs1n1.userstorage.mega.co.nz/dl/f1-fixture';
const b64url = b => Buffer.from(b).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
export const megaLink = `https://mega.nz/file/${MEGA_HANDLE}#${b64url(MEGA_KEY)}`;
const sha = b => createHash('sha256').update(b).digest('hex');

/** AES key and CTR nonce of the fixture key (the MEGA scheme, independently of web/mods/mega.mjs). */
function keys() {
  const aes = Buffer.alloc(16); for (let i = 0; i < 4; i++) aes.writeUInt32BE((MEGA_KEY.readUInt32BE(i * 4) ^ MEGA_KEY.readUInt32BE(16 + i * 4)) >>> 0, i * 4);
  const iv = Buffer.alloc(16); MEGA_KEY.copy(iv, 0, 16, 24); return {aes, iv};
}
/** The file as MEGA serves it: AES-128-CTR over the plaintext. */
export function megaEncrypt(plain) { const {aes, iv} = keys(), c = createCipheriv('aes-128-ctr', aes, iv); return Buffer.concat([c.update(plain), c.final()]); }
/** MEGA's `at` attribute field: "MEGA" + JSON, zero-padded, AES-128-CBC with a zero IV. */
export function megaAttributes(name) {
  const text = Buffer.from('MEGA' + JSON.stringify({n: name})), padded = Buffer.alloc(Math.ceil(text.length / 16) * 16); text.copy(padded);
  const {aes} = keys(), c = createCipheriv('aes-128-cbc', aes, Buffer.alloc(16)); c.setAutoPadding(false);
  return b64url(Buffer.concat([c.update(padded), c.final()]));
}

export function testCatalogue() {
  const [content, override] = f1Archives();
  const entry = (a, extra) => ({file: a.name, size: a.bytes.length, sha256: sha(a.bytes), page: 'https://example.invalid/' + a.name, ...extra});
  return {
    archives: {content, override},
    catalogue: {schema: 1, files: [
      entry(content, {id: 'f1-content', mod: 'hyperspace', title: 'F1 Content', version: '1', policy: 'hyperspace', license: 'GPL-3.0-or-later', licenseNotice: 'GPL-3.0.txt', credits: 'Test fixture', sources: ['server', 'local']}),
      entry(override, {id: 'f1-override', mod: 'multiverse', title: 'F1 Override', version: '1', policy: 'multiverse', mega: megaLink, credits: 'Test fixture', sources: ['mega', 'local']}),
    ], presets: [{id: 'f1', title: 'F1 pair', description: 'F1 Content and F1 Override, in that order.', files: ['f1-content', 'f1-override']}]},
  };
}
/** Writes <dir>/files/<archives> and <dir>/catalogue.json; returns {dir, files, catalogue} for FTL_MODS_DIR / FTL_MODS_CATALOGUE. */
export async function writeModsFixture(dir) {
  const {archives, catalogue} = testCatalogue(), files = join(dir, 'files');
  await mkdir(files, {recursive: true});
  for (const a of Object.values(archives)) await writeFile(join(files, a.name), a.bytes);
  await writeFile(join(dir, 'catalogue.json'), JSON.stringify(catalogue, null, 1));
  return {dir, files, catalogue: join(dir, 'catalogue.json')};
}
