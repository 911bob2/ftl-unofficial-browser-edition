// What a mod set's Lua scripts use of io, os, debug and package/require (sweep M1b, docs/HS_LUA.md "Files, programs and
// the sandbox"), found before the game starts so the player can say Run or Don't run. The same rules as
// android/mods/src/main/kotlin/ftl/mods/LuaScan.kt (cases: tests/hs-lua/lua-scan-cases.json).
// The scan reads names as Lua does (comments and strings skipped): `io.open`, `io["open"]`, `os.execute`, `require`,
// a library kept in a variable (`local o = os`), `_G.io`, `_G["io"]`. It cannot see names built at run time
// (`_G["i" .. "o"]`, `getmetatable("").__index`, a library handed over by another script); those calls still run in the
// sandbox. os.clock, os.time, os.date, os.difftime, io.write, io.read and io.type are not asked about (no files, no
// programs: the standard streams are the game's log here).
const LIBS = new Set(['io', 'os', 'debug', 'package']);
const QUIET = new Set(['os.clock', 'os.time', 'os.date', 'os.difftime', 'os.setlocale', 'io.write', 'io.read', 'io.type', 'io.stdout', 'io.stderr', 'io.stdin']);

/** Lua tokens: {t: 'name' | 'str' | 'op', v}; comments dropped, long brackets and escapes as Lua reads them. */
export function tokens(src) {
  const out = []; let i = 0; const n = src.length;
  const longOpen = j => { if (src[j] !== '[') return -1; let k = j + 1, eq = 0; while (src[k] === '=') { eq++; k++; } return src[k] === '[' ? eq : -1; };
  const longEnd = (j, eq) => { const close = ']' + '='.repeat(eq) + ']'; const e = src.indexOf(close, j); return e < 0 ? n : e + close.length; };
  while (i < n) {
    const c = src[i];
    if (c === '-' && src[i + 1] === '-') {
      const eq = longOpen(i + 2);
      if (eq >= 0) i = longEnd(i + 4 + eq, eq);
      else { const e = src.indexOf('\n', i); i = e < 0 ? n : e + 1; }
      continue;
    }
    if (/\s/.test(c)) { i++; continue; }
    if (/[A-Za-z_]/.test(c)) { let j = i + 1; while (j < n && /[A-Za-z0-9_]/.test(src[j])) j++; out.push({t: 'name', v: src.slice(i, j)}); i = j; continue; }
    if (c === '"' || c === "'") {
      let j = i + 1, v = '';
      while (j < n && src[j] !== c && src[j] !== '\n') { if (src[j] === '\\') { v += src[j + 1] ?? ''; j += 2; } else v += src[j++]; }
      out.push({t: 'str', v}); i = j + 1; continue;
    }
    if (c === '[') { const eq = longOpen(i); if (eq >= 0) { const s = i + 2 + eq, e = longEnd(s, eq); out.push({t: 'str', v: src.slice(s, e - 2 - eq)}); i = e; continue; } }
    if (/[0-9]/.test(c)) { let j = i + 1; while (j < n && /[0-9A-Za-z_.]/.test(src[j])) j++; out.push({t: 'num', v: src.slice(i, j)}); i = j; continue; }
    if (c === '.' && src[i + 1] === '.') { out.push({t: 'op', v: '..'}); i += src[i + 2] === '.' ? 3 : 2; continue; }
    out.push({t: 'op', v: c}); i++;
  }
  return out;
}

/** The library names a script uses: a sorted list such as ['io.open', 'os.execute', 'require']; 'io' alone is the library kept in a variable. */
export function scanLua(src) {
  const t = tokens(src), uses = new Set();
  for (let i = 0; i < t.length; i++) {
    let lib = null, at = i;
    const a = t[i];
    if (a.t !== 'name') continue;
    const prevDot = i > 0 && t[i - 1].t === 'op' && (t[i - 1].v === '.' || t[i - 1].v === ':');
    if (prevDot && !(i > 1 && t[i - 2].t === 'name' && (t[i - 2].v === '_G' || t[i - 2].v === '_ENV'))) continue;   // a field, not the global
    if (a.v === 'require') { uses.add('require'); continue; }
    if (a.v === '_G' || a.v === '_ENV') {
      if (t[i + 1]?.v === '[' && t[i + 2]?.t === 'str' && t[i + 3]?.v === ']') {
        const g = t[i + 2].v;
        if (g === 'require') uses.add('require');
        if (LIBS.has(g)) { lib = g; at = i + 3; }
      }
      if (!lib) continue;
    } else if (LIBS.has(a.v)) lib = a.v;
    else continue;
    const nx = t[at + 1], nn = t[at + 2];
    if (nx?.t === 'op' && (nx.v === '.' || nx.v === ':') && nn?.t === 'name') uses.add(lib + '.' + nn.v);
    else if (nx?.t === 'op' && nx.v === '[' && nn?.t === 'str' && t[at + 3]?.v === ']') uses.add(lib + '.' + nn.v);
    else if (nx?.t === 'op' && nx.v === '=' && t[at + 2]?.v !== '=') { /* the name is assigned, not read */ }
    else uses.add(lib);
  }
  return [...uses].filter(u => !QUIET.has(u)).sort();
}

const GROUPS = [
  ['files', 'files', u => /^io(\.|$)/.test(u) && u !== 'io.popen' || ['os.remove', 'os.rename', 'os.tmpname'].includes(u)],
  ['programs', 'system commands', u => u === 'os.execute' || u === 'io.popen'],
  ['os', 'system functions', u => u === 'os' || /^os\./.test(u) && !['os.remove', 'os.rename', 'os.tmpname', 'os.execute', 'os.getenv', 'os.exit'].includes(u)],
  ['environment', 'environment variables', u => u === 'os.getenv'],
  ['exit', 'closing the game', u => u === 'os.exit'],
  ['debug', 'the debug library', u => /^debug(\.|$)/.test(u)],
  ['modules', 'loading code from files', u => u === 'require' || /^package(\.|$)/.test(u)],
];
/** Plain words for a list of uses: [{key, words, names}], e.g. {key: 'programs', words: 'system commands', names: ['os.execute']}. */
export function describeUses(uses) {
  const out = [];
  for (const [key, words, test] of GROUPS) { const names = uses.filter(test); if (names.length) out.push({key, words, names}); }
  return out;
}
const list = items => items.length < 2 ? items.join('') : items.slice(0, -1).join(', ') + ' and ' + items.at(-1);
/** "files (io.open) and system commands (os.execute)" */
export function usesText(uses) { return list(describeUses(uses).map(g => `${g.words} (${g.names.slice(0, 3).join(', ')}${g.names.length > 3 ? ', …' : ''})`)); }

/**
 * The prompt's text. mods: [{name, uses}]. real: "Disable Lua sandboxing" is on (Android only; the browser always
 * sandboxes).
 */
export function promptText(mods, real = false) {
  const one = mods.length === 1;
  const lines = mods.map(m => `${one ? "This mod's" : m.name + "'s"} scripts use ${usesText(m.uses)}.`);
  if (one) lines[0] = `${mods[0].name}: ${lines[0].replace(/^This mod's/, "this mod's")}`;
  const exit = mods.some(m => m.uses.includes('os.exit'));
  if (real) lines.push('Lua sandboxing is off: these scripts can read, change and delete the files of this app, including your saves and your account sign-in, run programs on this device as this app, and send what they read anywhere.');
  else lines.push('Here they run in a private folder: they cannot reach your files or run programs.' + (exit ? ' Closing the game ends this game and returns to the launcher.' : ''));
  return lines;
}
