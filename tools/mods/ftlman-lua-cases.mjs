#!/usr/bin/env node
// Ground truth for the ftlman Lua patch scripts (developer tool) from the real ftlman 0.7.4 binary:
//   node tools/mods/ftlman-lua-cases.mjs materialize <dir> [ftlman-src]
//     <dir>/cases/<case>/{base.xml, blueprints.append.lua}   tests/fixtures/mods/lua-cases.mjs (prelude + script)
//     <dir>/l1/{data/ftl.dat, mods/*}                        tests/fixtures/mods/l1.mjs
//     <dir>/ftlman-tests/{tests,files}                        ftlman's own lua/tests (copied from [ftlman-src]/lua)
//     <dir>/run.sh                                            what to run where the binary is (a container)
//   sh <dir>/run.sh <ftlman binary>   (inside the container; writes out/err/code next to each input)
//   node tools/mods/ftlman-lua-cases.mjs collect <dir>     -> tests/fixtures/mods/lua-expected.json
import {cpSync, existsSync, mkdirSync, readFileSync, rmSync, writeFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {join} from 'node:path';
import {PRELUDE, cases} from '../../tests/fixtures/mods/lua-cases.mjs';
import {l1Archives, l1Base} from '../../tests/fixtures/mods/l1.mjs';
import {readPkg, writePkg} from './pkg.mjs';

const RUN = `#!/bin/sh
# run.sh <ftlman>: every case with \`ftlman append\` (20 s each), L1 with \`ftlman patch\` + extract, ftlman's own Lua tests.
F=$1; D=$(cd "$(dirname "$0")" && pwd)
for c in "$D"/cases/*/; do
  (cd "$c" && timeout 20 "$F" append base.xml blueprints.append.lua > out 2> err; echo $? > code)
done
(cd "$D/l1" && "$F" patch -d data mods/l1-lua-a.zip mods/l1-lua-b.ftl > patch.log 2>&1; echo $? > code; "$F" extract files data/ftl.dat >> patch.log 2>&1)
if [ -d "$D/ftlman-tests/tests" ]; then
  for t in "$D"/ftlman-tests/tests/*.lua; do
    (cd "$D/ftlman-tests" && LC_ALL=C.UTF-8 timeout 20 "$F" lua-run --fs files ./files "tests/$(basename "$t")" > "$t.out" 2> "$t.err"; echo $? > "$t.code")
  done
fi
echo done
`;

const [command, dir, src] = process.argv.slice(2);
const sha = b => createHash('sha256').update(b).digest('hex');
if (command === 'materialize') {
  rmSync(dir, {recursive: true, force: true});
  for (const [name, base, script] of cases) {
    const d = join(dir, 'cases', name); mkdirSync(d, {recursive: true});
    writeFileSync(join(d, 'base.xml'), base); writeFileSync(join(d, 'blueprints.append.lua'), PRELUDE + script);
  }
  mkdirSync(join(dir, 'l1', 'data'), {recursive: true}); mkdirSync(join(dir, 'l1', 'mods'));
  writeFileSync(join(dir, 'l1', 'data', 'ftl.dat'), writePkg(l1Base()));
  for (const a of l1Archives()) writeFileSync(join(dir, 'l1', 'mods', a.name), a.bytes);
  if (src) cpSync(join(src, 'lua'), join(dir, 'ftlman-tests'), {recursive: true});
  writeFileSync(join(dir, 'run.sh'), RUN);
  console.log(`${cases.length} cases, L1${src ? ', ftlman tests' : ''} in ${dir}`);
} else if (command === 'collect') {
  const result = {ftlman: '0.7.4', cases: {}, l1: null};
  const clean = s => s.replace(/\x1b\[[0-9;]*m/g, '');
  for (const [name] of cases) {
    const d = join(dir, 'cases', name), code = Number(readFileSync(join(d, 'code'), 'utf8').trim());
    const out = readFileSync(join(d, 'out'), 'utf8'), err = clean(readFileSync(join(d, 'err'), 'utf8'));
    result.cases[name] = code === 0 ? {ok: true, output: out} : {ok: false, code, stdout: out, error: err.split('\n').filter(l => l.trim()).slice(0, 6).join('\n').slice(0, 600)};
  }
  const l1 = join(dir, 'l1'), code = Number(readFileSync(join(l1, 'code'), 'utf8').trim());
  if (code === 0) {
    const merged = readPkg(join(l1, 'data', 'ftl.dat')), base = l1Base(), files = {}, text = {};
    for (const [n, b] of [...merged].sort(([x], [y]) => x < y ? -1 : 1)) {
      files[n] = sha(b);
      const old = base.get(n);
      if (!/\.(png|bin)$/.test(n) && (!old || !Buffer.from(old).equals(b))) text[n] = b.toString('utf8');
    }
    result.l1 = {ok: true, files, text};
  } else result.l1 = {ok: false, log: readFileSync(join(l1, 'patch.log'), 'utf8').slice(-2000)};
  const t = join(dir, 'ftlman-tests', 'tests');
  if (existsSync(t)) {
    result.ftlmanTests = {};
    for (const f of ['pretty.lua', 'util.lua', 'vfs.lua', 'xml.lua']) {
      if (!existsSync(join(t, f + '.code'))) continue;
      result.ftlmanTests[f] = {code: Number(readFileSync(join(t, f + '.code'), 'utf8').trim()), stdout: readFileSync(join(t, f + '.out'), 'utf8'), stderr: clean(readFileSync(join(t, f + '.err'), 'utf8')).slice(0, 2000)};
    }
  }
  writeFileSync(new URL('../../tests/fixtures/mods/lua-expected.json', import.meta.url), JSON.stringify(result, null, 1) + '\n');
  console.log(`${Object.keys(result.cases).length} cases, L1 ${result.l1.ok ? Object.keys(result.l1.files).length + ' files' : 'failed'}`);
} else throw Error('Usage: ftlman-lua-cases.mjs materialize <dir> [ftlman-src] | collect <dir>');
