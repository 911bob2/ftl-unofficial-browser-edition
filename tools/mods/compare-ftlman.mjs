#!/usr/bin/env node
// Developer check: merge mod archives over a vanilla ftl.dat with web/mods/merge.mjs and compare every resulting file
// with the ftl.dat that ftlman 0.7.4 patched from the same inputs.
//   node tools/mods/compare-ftlman.mjs --vanilla <ftl.dat> --ftlman <patched ftl.dat> [--report <json>] --mods <a> <b> ...
// Prints counts and every difference (first differing offset with context); exit code 1 when anything differs.
import {openSync, readSync, fstatSync, closeSync, writeFileSync} from 'node:fs';
import {basename} from 'node:path';
import {readPkg} from './pkg.mjs';
import {openZip} from '../../web/mods/zip.mjs';
import {mergeMods} from '../../web/mods/merge.mjs';

const argv = process.argv.slice(2), o = {mods: []};
for (let i = 0; i < argv.length; i++) {
  const a = argv[i];
  if (a === '--vanilla') o.vanilla = argv[++i]; else if (a === '--ftlman') o.ftlman = argv[++i]; else if (a === '--report') o.report = argv[++i];
  else if (a === '--mods') { while (argv[i + 1] && !argv[i + 1].startsWith('--')) o.mods.push(argv[++i]); }
  else throw Error('Unknown argument ' + a);
}
const t0 = Date.now(), vanilla = readPkg(o.vanilla), expected = readPkg(o.ftlman);
const fds = [], archives = [];
for (const path of o.mods) {
  const fd = openSync(path, 'r'), size = fstatSync(fd).size; fds.push(fd);
  const readAt = (off, len) => { const b = Buffer.allocUnsafe(len); let n = 0; while (n < len) { const r = readSync(fd, b, n, len - n, off + n); if (!r) throw Error('short read'); n += r; } return new Uint8Array(b.buffer, b.byteOffset, len); };
  const source = {size, read: async (off, len) => readAt(off, len), readSync: readAt};
  archives.push({name: basename(path), zip: await openZip(source)});
}
const t1 = Date.now();
const result = await mergeMods({baseNames: [...vanilla.keys()], readBase: n => vanilla.get(n), archives});
const mergeMs = Date.now() - t1;
for (const fd of fds) closeSync(fd);

const ours = new Map(); for (const [n, b] of vanilla) if (!result.removed.has(n)) ours.set(n, b); for (const [n, b] of result.files) ours.set(n, b);
const same = (a, b) => a.length === b.length && Buffer.from(a.buffer, a.byteOffset, a.length).equals(Buffer.from(b.buffer, b.byteOffset, b.length));
const diffs = [], onlyOurs = [], onlyFtlman = []; let equal = 0, changedEqual = 0;
for (const [name, exp] of expected) {
  const got = ours.get(name);
  if (!got) { onlyFtlman.push(name); continue; }
  if (same(got, exp)) { equal++; if (result.files.has(name) || !vanilla.has(name) || !same(vanilla.get(name), exp)) changedEqual++; continue; }
  let i = 0; while (i < got.length && i < exp.length && got[i] === exp[i]) i++;
  const ctx = b => Buffer.from(b.subarray(Math.max(0, i - 60), i + 60)).toString('utf8');
  diffs.push({name, ours: got.length, ftlman: exp.length, offset: i, oursContext: ctx(got), ftlmanContext: ctx(exp)});
}
for (const name of ours.keys()) if (!expected.has(name)) onlyOurs.push(name);
const summary = {mods: o.mods.map(m => basename(m)), files: expected.size, equal, modifiedByMods: changedEqual, different: diffs.length, onlyFtlman: onlyFtlman.length, onlyOurs: onlyOurs.length, warnings: result.warnings.length, mergeMs, totalMs: Date.now() - t0};
console.log('COMPARE ' + JSON.stringify(summary));
for (const d of diffs.slice(0, 20)) console.log('DIFF ' + JSON.stringify(d));
if (onlyFtlman.length) console.log('ONLY FTLMAN ' + onlyFtlman.slice(0, 20).join(' '));
if (onlyOurs.length) console.log('ONLY OURS ' + onlyOurs.slice(0, 20).join(' '));
for (const w of result.warnings.slice(0, 10)) console.log('WARN ' + w);
if (o.report) writeFileSync(o.report, JSON.stringify({summary, diffs, onlyFtlman, onlyOurs, warnings: result.warnings}, null, 1));
process.exitCode = diffs.length || onlyFtlman.length || onlyOurs.length ? 1 : 0;
