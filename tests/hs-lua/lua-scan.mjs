// The launcher's scan of mod scripts for io, os, debug and package (web/mods/lua-scan.mjs; Android's ftl.mods.LuaScan
// runs the same cases). Run: node tests/hs-lua/lua-scan.mjs
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {fileURLToPath} from 'node:url';
import {scanLua, promptText, usesText} from '../../web/mods/lua-scan.mjs';

const here = fileURLToPath(new URL('.', import.meta.url));
const cases = JSON.parse(fs.readFileSync(here + 'lua-scan-cases.json', 'utf8'));
for (const c of cases) assert.deepEqual(scanLua(c.src), c.uses, c.src);
assert.equal(usesText(['io.open', 'os.execute']), 'files (io.open) and system commands (os.execute)');
assert.deepEqual(promptText([{name: 'Test mod', uses: ['io.open', 'os.execute']}]),
  ["Test mod: this mod's scripts use files (io.open) and system commands (os.execute).", 'Here they run in a private folder: they cannot reach your files or run programs.']);
console.log(`PASS lua-scan: ${cases.length} cases`);
