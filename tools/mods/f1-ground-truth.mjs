#!/usr/bin/env node
// F1 ground truth (developer tool): writes the synthetic base as a PKG ftl.dat plus the F1 archives, and after
// `ftlman patch` has run on them, records every resulting file's sha256 in tests/fixtures/mods/f1-expected.json.
//   node tools/mods/f1-ground-truth.mjs write <dir>        -> <dir>/data/ftl.dat, <dir>/mods/<archives>
//   (ftlman patch -d <dir>/data <dir>/mods/f1-content.ftl <dir>/mods/f1-override.zip)
//   node tools/mods/f1-ground-truth.mjs collect <dir>      -> tests/fixtures/mods/f1-expected.json
import {mkdirSync, rmSync, writeFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {join} from 'node:path';
import {f1Archives, f1Base} from '../../tests/fixtures/mods/f1.mjs';
import {readPkg, writePkg} from './pkg.mjs';

const [command, dir] = process.argv.slice(2);
if (command === 'write') {
  rmSync(dir, {recursive: true, force: true}); mkdirSync(join(dir, 'data'), {recursive: true}); mkdirSync(join(dir, 'mods'));
  writeFileSync(join(dir, 'data', 'ftl.dat'), writePkg(f1Base()));
  for (const a of f1Archives()) writeFileSync(join(dir, 'mods', a.name), a.bytes);
  console.log('F1 inputs in ' + dir);
} else if (command === 'collect') {
  const merged = readPkg(join(dir, 'data', 'ftl.dat')), base = f1Base(), files = {}, text = {};
  for (const [n, b] of [...merged].sort(([a], [c]) => a < c ? -1 : 1)) {
    files[n] = createHash('sha256').update(b).digest('hex');
    const old = base.get(n);
    if (!/\.(png|ogg|font)$/.test(n) && (!old || !Buffer.from(old).equals(b))) text[n] = b.toString('utf8');
  }
  writeFileSync(new URL('../../tests/fixtures/mods/f1-expected.json', import.meta.url), JSON.stringify({ftlman: '0.7.4', files, text}, null, 1) + '\n');
  console.log(`${Object.keys(files).length} files recorded`);
} else throw Error('Usage: f1-ground-truth.mjs write|collect <dir>');
