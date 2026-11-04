// ftlman Lua patch scripts against ftlman 0.7.4 ground truth (our own fixture content only); the checks are
// web/self-tests-mods.mjs, which the Tests page runs too:
// 1. the API cases (tests/fixtures/mods/lua-cases.mjs) as `ftlman append` ran them (lua-expected.json `cases`);
// 2. L1: a merge with patch scripts (tests/fixtures/mods/l1.mjs) against `ftlman patch` (lua-expected.json `l1`).
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {l1Checks, luaCaseChecks} from '../../web/self-tests-mods.mjs';
export {normaliseErrors} from '../../web/self-tests-mods.mjs';

export const expected = JSON.parse(readFileSync(new URL('../fixtures/mods/lua-expected.json', import.meta.url), 'utf8'));
const load = name => name.endsWith('.json') ? expected : import('../fixtures/mods/' + name);

export const runLuaCases = () => luaCaseChecks({assert, load});
export const runL1 = () => l1Checks({assert, load});
