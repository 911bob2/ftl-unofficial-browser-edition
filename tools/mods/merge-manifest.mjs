#!/usr/bin/env node
// Developer check input: merge mod archives over a vanilla ftl.dat with web/mods/merge.mjs and write the SHA-256 of
// every resulting file, for the Android merge's opt-in comparison on real mod sets (android/mods RealModsTest, run
// with FTL_MODS_REAL=<folder of these manifests>). The website's merge is byte-identical to ftlman 0.7.4 on the sets
// listed in docs/MODS.md (tools/mods/compare-ftlman.mjs).
//   node tools/mods/merge-manifest.mjs --vanilla <ftl.dat> --out <manifest.json> --mods <a> <b> ...
import {createHash} from 'node:crypto';
import {openSync, readSync, fstatSync, closeSync, writeFileSync} from 'node:fs';
import {basename, resolve} from 'node:path';
import {readPkg} from './pkg.mjs';
import {openZip} from '../../web/mods/zip.mjs';
import {mergeMods} from '../../web/mods/merge.mjs';

const argv = process.argv.slice(2), o = {mods: []};
for (let i = 0; i < argv.length; i++) {
  const a = argv[i];
  if (a === '--vanilla') o.vanilla = argv[++i]; else if (a === '--out') o.out = argv[++i];
  else if (a === '--mods') { while (argv[i + 1] && !argv[i + 1].startsWith('--')) o.mods.push(argv[++i]); }
  else throw Error('Unknown argument ' + a);
}
if (!o.vanilla || !o.out || !o.mods.length) throw Error('Usage: --vanilla <ftl.dat> --out <json> --mods <archives...>');
const vanilla = readPkg(o.vanilla), fds = [], archives = [];
for (const path of o.mods) {
  const fd = openSync(path, 'r'), size = fstatSync(fd).size; fds.push(fd);
  const readAt = (off, len) => { const b = Buffer.allocUnsafe(len); let n = 0; while (n < len) { const r = readSync(fd, b, n, len - n, off + n); if (!r) throw Error('short read'); n += r; } return new Uint8Array(b.buffer, b.byteOffset, len); };
  archives.push({name: basename(path), zip: await openZip({size, read: async (off, len) => readAt(off, len), readSync: readAt})});
}
const result = await mergeMods({baseNames: [...vanilla.keys()], readBase: n => vanilla.get(n), archives});
for (const fd of fds) closeSync(fd);
const sha = b => createHash('sha256').update(b).digest('hex'), files = {};
for (const [n, b] of vanilla) if (!result.removed.has(n) && !result.files.has(n)) files[n] = sha(b);
for (const [n, b] of result.files) files[n] = sha(b);
writeFileSync(o.out, JSON.stringify({vanilla: resolve(o.vanilla), mods: o.mods.map(m => resolve(m)), warnings: result.warnings.length, files}));
console.log(`${o.out}: ${Object.keys(files).length} files, ${result.warnings.length} warnings`);
