// "Ask before running" (sweep M1b): before a mod set whose scripts use io, os, debug or package first runs, the player
// says Run or Don't run. The answer "Run" is kept per mod version (archive SHA-256) and account in this browser and can
// be taken back under Mods. In the browser every script runs in the sandbox (docs/HS_LUA.md); the page has no way to
// give a script real files or programs, and no script can reach this module (it runs in the page, the scripts in wasm).
import {scanLua, promptText} from './lua-scan.mjs';
import {choose} from '../dialogs.mjs';

const KEY = account => 'ftl-lua-consent:' + account;
/** sha256 -> {at, uses} of the mods this account let run. */
export function readConsent(account) {
  try { const v = JSON.parse(localStorage.getItem(KEY(account)) ?? '{}'); return v && typeof v === 'object' ? v : {}; } catch { return {}; }
}
export function setConsent(account, sha256, entry) {
  const all = readConsent(account);
  if (entry) all[sha256] = entry; else delete all[sha256];
  try { localStorage.setItem(KEY(account), JSON.stringify(all)); } catch { /* this visit only */ }
}

/**
 * The mods of a launch whose scripts use the libraries: [{sha256, name, uses, scripts}] in load order.
 * mods: [{name, sha256}] in load order; scripts: the session's script paths; files: merged name -> bytes;
 * owners: script path -> index into mods (the last archive that has the file).
 */
export function scanLaunch({mods, scripts, files, owners}) {
  const dec = new TextDecoder(), per = new Map();
  for (const path of scripts) {
    const bytes = files.get(path);
    if (!bytes) continue;
    const uses = scanLua(dec.decode(bytes));
    if (!uses.length) continue;
    const i = owners.get(path) ?? mods.length - 1;
    const m = per.get(i) ?? {sha256: mods[i].sha256, name: mods[i].name, uses: new Set(), scripts: []};
    uses.forEach(u => m.uses.add(u)); m.scripts.push(path); per.set(i, m);
  }
  return [...per.keys()].sort((a, b) => a - b).map(i => ({...per.get(i), uses: [...per.get(i).uses].sort()}));
}

/** Resolves true to start, false for Don't run. Asks only about mods without a stored Run. */
export async function askLuaConsent({account, found}) {
  const stored = readConsent(account);
  const asking = found.filter(m => !stored[m.sha256]);
  if (!asking.length) return true;
  const yes = await choose({id: 'lua-consent-dialog', title: asking.length === 1 ? "Run this mod's scripts?" : "Run these mods' scripts?",
    eyebrow: 'Mod scripts', text: promptText(asking, false), cancel: false, required: false,
    actions: [{label: "Don't run", value: false, kind: 'ghost', id: 'lua-consent-no'}, {label: 'Run', value: true, kind: 'primary', id: 'lua-consent-yes'}]});
  if (yes) for (const m of asking) setConsent(account, m.sha256, {at: new Date().toISOString(), uses: m.uses});
  return !!yes;
}
