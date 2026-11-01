#!/usr/bin/env node
// Operator/developer tool: put the pinned catalogue files (web/mods/catalogue.json) into a server's mods directory
// (FTL_MODS_DIR, outside Git and the image), from their official sources, and verify every file's size and SHA-256.
//   node tools/mods/fetch-mods.mjs <mods-dir> [--only id,id] [--from <dir>] [--catalogue <file>] [--check]
// Hyperspace: the release zip from GitHub (its own SHA-256 checked), Hyperspace.ftl extracted from it.
// Multiverse: the official MEGA links, decrypted with web/mods/mega.mjs. --from copies matching files (any name) from a
// local folder instead of downloading; --check only verifies what is there. Exit code 1 when a file is missing or wrong.
import {createHash} from 'node:crypto';
import {createReadStream} from 'node:fs';
import {mkdir, open, readdir, readFile, rename, rm, stat, writeFile, copyFile} from 'node:fs/promises';
import {join, dirname} from 'node:path';
import {fileURLToPath} from 'node:url';
import {createDecryptor, megaFileInfo, megaRange} from '../../web/mods/mega.mjs';
import {bytesSource, openZip} from '../../web/mods/zip.mjs';

const args = process.argv.slice(2), flag = name => { const i = args.indexOf(name); return i < 0 ? null : args.splice(i, 2)[1]; };
const only = flag('--only')?.split(','), from = flag('--from'), check = args.includes('--check') && args.splice(args.indexOf('--check'), 1);
const catalogueFile = flag('--catalogue') ?? join(dirname(fileURLToPath(import.meta.url)), '../../web/mods/catalogue.json');
const dir = args[0];
if (!dir) { console.error('Usage: node tools/mods/fetch-mods.mjs <mods-dir> [--only id,id] [--from <dir>] [--catalogue <file>] [--check]'); process.exit(2); }
const catalogue = JSON.parse(await readFile(catalogueFile, 'utf8')), entries = catalogue.files.filter(f => !only || only.includes(f.id));
await mkdir(dir, {recursive: true});

const sha = async path => { const h = createHash('sha256'); for await (const c of createReadStream(path)) h.update(c); return h.digest('hex'); };
const size = async path => (await stat(path).catch(() => null))?.size ?? null;
const mb = n => (n / 1048576).toFixed(1) + ' MB';
async function verified(path, entry) { return await size(path) === entry.size && await sha(path) === entry.sha256; }

async function fromGithub(entry, target) {
  const u = entry.upstream; console.log(`  downloading ${u.url}`);
  const r = await fetch(u.url); if (!r.ok) throw Error(`GitHub answered HTTP ${r.status}`);
  const zip = new Uint8Array(await r.arrayBuffer());
  if (zip.length !== u.size || createHash('sha256').update(zip).digest('hex') !== u.sha256) throw Error(`${u.archive} does not match its pinned SHA-256`);
  const z = await openZip(bytesSource(zip)), e = z.get(u.entry); if (!e) throw Error(`${u.archive} has no ${u.entry}`);
  await writeFile(target, await z.read(e));
}
async function fromMega(entry, target) {
  const info = await megaFileInfo(entry.mega);
  if (info.size !== entry.size) throw Error(`the MEGA file has ${info.size} bytes, the pinned one ${entry.size}`);
  console.log(`  MEGA: ${info.name} (${mb(info.size)}) from ${new URL(info.url).host}`);
  const decrypt = await createDecryptor(info.key), out = await open(target, 'w'), chunk = 16 << 20, started = Date.now();
  try {
    for (let o = 0; o < info.size; o += chunk) {
      const end = Math.min(info.size, o + chunk) - 1;
      let bytes;
      for (let attempt = 0; ; attempt++) { try { bytes = await decrypt(await megaRange(info.url, o, end), o); break; } catch (e) { if (attempt >= 3) throw e; await new Promise(r => setTimeout(r, 2000 * (attempt + 1))); } }
      await out.write(bytes, 0, bytes.length, o);
      const s = (Date.now() - started) / 1000; process.stdout.write(`\r  ${mb(end + 1)} of ${mb(info.size)} · ${mb((end + 1) / Math.max(s, .001))}/s   `);
    }
    process.stdout.write('\n');
  } finally { await out.close(); }
}
async function fromFolder(entry, target) {
  for (const name of await readdir(from)) {
    const path = join(from, name);
    if (await size(path) === entry.size && await sha(path) === entry.sha256) { await copyFile(path, target); console.log(`  copied from ${path}`); return true; }
  }
  return false;
}

let failed = 0;
for (const entry of entries) {
  const target = join(dir, entry.file), part = target + '.part';
  console.log(`${entry.title} ${entry.version} -> ${target}`);
  if (await verified(target, entry)) { console.log('  ok (already there, SHA-256 matches)'); continue; }
  if (check) { console.log(await size(target) == null ? '  missing' : '  WRONG (size or SHA-256 differs)'); failed++; continue; }
  try {
    await rm(part, {force: true});
    if (from && await fromFolder(entry, part)) {}
    else if (entry.mega) await fromMega(entry, part);
    else if (entry.upstream) await fromGithub(entry, part);
    else throw Error('no official source in the catalogue');
    if (!await verified(part, entry)) throw Error('the result does not match the pinned size and SHA-256');
    await rename(part, target); console.log('  ok (verified)');
  } catch (e) { await rm(part, {force: true}); console.log('  FAILED: ' + e.message); failed++; }
}
process.exitCode = failed ? 1 : 0;
