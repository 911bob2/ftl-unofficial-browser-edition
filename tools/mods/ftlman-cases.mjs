#!/usr/bin/env node
// Ground truth for tests/fixtures/mods/append-cases.mjs from the real ftlman 0.7.4 binary (developer tool).
//   node tools/mods/ftlman-cases.mjs materialize <dir>      writes <dir>/<case>/{base.xml, patch.xml.append|.rawappend}
//   (run `ftlman append base.xml patch.*` in each case dir, saving stdout to out, stderr to err, exit code to code)
//   node tools/mods/ftlman-cases.mjs collect <dir> <json>   writes {case: {ok, output}} (output only when ok)
import {mkdirSync, readFileSync, writeFileSync, rmSync} from 'node:fs';
import {join} from 'node:path';
import {cases} from '../../tests/fixtures/mods/append-cases.mjs';

const [command, dir, out] = process.argv.slice(2);
if (command === 'materialize') {
  rmSync(dir, {recursive: true, force: true});
  for (const [name, base, script, kind = 'append'] of cases) {
    const d = join(dir, name); mkdirSync(d, {recursive: true});
    writeFileSync(join(d, 'base.xml'), base); writeFileSync(join(d, 'patch.xml.' + kind), script);
  }
  console.log(`${cases.length} cases in ${dir}`);
} else if (command === 'collect') {
  const result = {};
  for (const [name] of cases) {
    const code = Number(readFileSync(join(dir, name, 'code'), 'utf8').trim());
    result[name] = code === 0 ? {ok: true, output: readFileSync(join(dir, name, 'out'), 'utf8')} : {ok: false, error: readFileSync(join(dir, name, 'err'), 'utf8').split('\n').filter(l => /error|Error/.test(l)).slice(0, 2).join(' | ').replace(/\x1b\[[0-9;]*m/g, '').slice(0, 300)};
  }
  writeFileSync(out, JSON.stringify(result, null, 1) + '\n');
  console.log(`${Object.keys(result).length} results -> ${out}`);
} else throw Error('Usage: ftlman-cases.mjs materialize <dir> | collect <dir> <json>');
