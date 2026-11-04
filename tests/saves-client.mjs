// The website's sync glue (web/sync.mjs over web/savecore.mjs) with the real savecore.js, an in-memory device store and an
// in-memory account that answers like server/CloudSaves.cs (revisions, If-Match, locks with override and
// lock_overridden, tombstones, limits). Covers offline play then sync, fetch, both conflict choices, lock
// override with the overridden instance's notice, another tab of the same browser, deletion elsewhere and a save's profile syncing with its run.
// savecore.js: SAVECORE_JS, a local build (docker build --target savecore-js -o type=local,dest=.build/savecore-js .) or
// the app image's copy (/app/wwwroot/savecore).
import assert from 'node:assert/strict';
import {existsSync, readFileSync} from 'node:fs';
import {wrapSaveCore} from '../web/savecore.mjs';
import {saveSyncChecks} from '../web/self-tests-saves.mjs';

const file = relative => new URL(relative, import.meta.url).pathname.replace(/^\/(\w:)/, '$1');
const path = [process.env.SAVECORE_JS, file('../.build/savecore-js/savecore.js'), file('../.build/android/savecore-js/savecore.js'), file('../wwwroot/savecore/savecore.js')].find(p => p && existsSync(p));
assert.ok(path, 'savecore.js not built (see the header)');
const raw = {}; new Function('exports', readFileSync(path, 'utf8'))(raw);
console.log(await saveSyncChecks({core: wrapSaveCore(raw), assert, log: line => console.log(line)}));
