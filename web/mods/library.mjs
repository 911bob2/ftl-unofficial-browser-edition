// Mod library panel on Play: the catalogue of pinned mod files (download from this server or official MEGA, or
// choose a file you have), presets, and your archives in patch order with enable boxes. The page's one Launch FTL button
// starts with the enabled mods (it then reads "Launch FTL with mods"); with none enabled it is the vanilla launch.
// Loaded the first time the player opens the Mods panel (or launches with mods enabled).
// Nothing downloads by itself: every download starts from a button. Downloads stay pinned to the catalogue; a local file
// is accepted whatever it is and badged Verified (its SHA-256 is a catalogue file) or Custom (savecore's rule).
import {archiveUsers, deleteArchive, getArchive, getList, listArchives, putArchive, removeArchive, setList} from './store.mjs';
import {inspectArchive} from './boot.mjs';
import {modSetHash} from './session.mjs';
import {checkFileSpace, createMeter, discardDownload, downloadFile, importLocalFile, mb, pendingDownload} from './download.mjs';
import {formatProgress, progressBar} from '../progress.mjs';
import {readEnabled, writeEnabled} from '../mods-enabled.mjs';
import {confirmAction} from '../dialogs.mjs';
import {isHyperspace, markerOf} from './hs-needed.mjs';
import {readConsent, setConsent} from './lua-consent.mjs';
import {usesText} from './lua-scan.mjs';

const MiB = n => (n / 1048576).toFixed(1) + ' MiB';
function el(tag, props = {}, ...children) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(props)) {
    if (v === undefined) continue;
    if (k === 'dataset') Object.assign(e.dataset, v);
    else if (k === 'role' || k.startsWith('aria')) e.setAttribute(k === 'role' ? k : 'aria-' + k.slice(4).toLowerCase(), v);
    else e[k] = v;
  }
  for (const c of children) if (c != null && c !== false) e.append(c);
  return e;
}
/**
 * A button whose width fits the longest of its labels, so it keeps its size when its state changes ("Enable" /
 * "Enabled"): the label is a span, the other labels sit invisibly in the same grid cell (styles.css .btn-fixed).
 */
export function fixedButton(props, label, labels) {
  const b = el('button', {type: 'button', ...props, className: (props.className ?? 'btn') + ' btn-fixed'}, el('span', {}, label));
  const [a = '', c = ''] = labels.filter(l => l !== label);
  b.dataset.alt1 = a; b.dataset.alt2 = c;
  return b;
}
const SOURCE_LABEL = {server: 'this server', mega: 'mega.nz', local: 'a local file', import: 'a local file'};

/**
 * Ordering hints (not enforced): Hyperspace mods need Hyperspace.ftl first (hs-needed.mjs names both); MV Data needs MV
 * Assets before it. item.marker: the archive uses Hyperspace (else its stored `hyperspace`, data/hyperspace.xml).
 */
export function orderHints(items, cat = null) {
  const on = items.filter(i => i.enabled), hints = [];
  const at = test => on.findIndex(test), hs = at(i => isHyperspace(i, cat));
  const mvData = at(i => /multiverse/i.test(i.title ?? i.name) && /data/i.test(i.title ?? i.name));
  const mvAssets = at(i => /multiverse/i.test(i.title ?? i.name) && /assets/i.test(i.title ?? i.name));
  on.forEach((item, index) => {
    if (index === hs || !(item.marker ?? item.hyperspace)) return;
    if (hs < 0) hints.push(`${item.title ?? item.name} uses Hyperspace, but Hyperspace.ftl is not enabled: some Hyperspace images and text will be missing. Enable Hyperspace.ftl above it.`);
    else if (hs > index) hints.push(`${item.title ?? item.name} uses Hyperspace: enable Hyperspace.ftl above it.`);
  });
  if (mvData >= 0 && (mvAssets < 0 || mvAssets > mvData)) hints.push('Multiverse Data needs Multiverse Assets above it (order: Hyperspace, Assets, Data).');
  return hints;
}

/** Where a catalogue file can come from for this account: [server?, mega?]; a local file always works. */
export function sourcesFor(entry) { return ['server', 'mega'].filter(s => (s === 'server' ? entry.server?.available : s === 'mega' && !!entry.mega) && (entry.sources ?? ['server', 'mega', 'local']).includes(s)); }

/** What the error means for the player and which next steps fit (labels for buttons). */
export function errorSteps(error, entry) {
  const steps = [], other = sourcesFor(entry).filter(s => s !== error.source);
  switch (error.kind) {
    case 'interrupted': steps.push('resume', 'restart'); break;
    case 'network': steps.push('retry'); break;
    case 'checksum': steps.push(error.local ? null : 'retry'); break;
    case 'custom': break;
    case 'quota': case 'rate': case 'busy': steps.push('retry'); break;
    case 'storage': steps.push('manage', 'resume'); break;
    case 'cancelled': steps.push('resume', 'restart'); break;
    case 'auth': steps.push('signin'); break;
    default: steps.push('retry');
  }
  for (const s of other) steps.push('use-' + s);
  steps.push('local');
  if (entry.page) steps.push('page');
  return steps.filter(Boolean);
}
function describe(error) {
  const extra = error.kind === 'network' && error.mega ? (navigator.onLine === false ? ' You are offline.' : ' A firewall, proxy, browser extension or network filter may block mega.nz.')
    : error.kind === 'quota' && error.retryAfter ? ` MEGA allows more in about ${Math.ceil(error.retryAfter / 60)} min.` : '';
  return error.message + extra;
}

export function mountModLibrary({root, account, role = 'user', local = false, message = () => {}, onChange = () => {}, catalogue = async () => null, fetchServer, core}) {
  let items = [], busy = false, cat = null, catLoaded = false, archives = new Map(), highlight = null; // highlight: the row enableFirst points at
  const jobs = new Map(); // catalogue id -> {controller, progress, error, source}
  const prefKey = 'ftl-mod-source:' + account;
  const preference = () => { try { return localStorage.getItem(prefKey); } catch { return null; } };
  const status = el('p', {className: 'muted', role: 'status'}), list = el('ol', {className: 'mod-list', ariaLabel: 'Mod load order'}), hints = el('ul', {className: 'mod-hints'});
  const input = el('input', {type: 'file', accept: '.ftl,.zip,application/zip', multiple: true, ariaLabel: 'Choose mod archives'});
  const drop = el('label', {className: 'file-drop'}, el('span', {className: 'file-drop-icon', ariaHidden: 'true'}, '↥'), el('strong', {}, 'Add mods (.ftl or .zip)'), el('span', {className: 'muted'}, 'or drop archives here'), input);
  const setLabel = el('p', {className: 'muted text-sm', id: 'mods-set'});
  const luaBox = el('div', {className: 'mod-lua', id: 'mods-lua'});
  const credits = el('details', {className: 'mod-credits-box', hidden: true});
  const catalogueBox = el('div', {className: 'mod-catalogue', id: 'mods-catalogue'}), presetsBox = el('div', {className: 'mod-presets', id: 'mods-presets'});
  const sourceBox = el('div', {className: 'mod-source', id: 'mods-source'});
  const dialog = el('dialog', {className: 'user-dialog mod-dialog', id: 'mods-preset-dialog', ariaLabelledby: 'mods-preset-title'});
  document.body.append(dialog); // outside the panel: the download dialog also opens from the Saves page
  root.replaceChildren(
    el('h3', {className: 'mod-heading'}, 'Presets'), presetsBox,
    el('h3', {className: 'mod-heading'}, 'Mod files'), sourceBox, catalogueBox, credits,
    el('h3', {className: 'mod-heading'}, 'Your mods'), drop, status, list, hints, setLabel,
    el('h3', {className: 'mod-heading'}, 'Mod scripts'), luaBox);

  // A redraw (a refresh on focus, the catalogue arriving, a download's next phase) waits until the pointer is up: replacing
  // a button between its press and release would lose the click.
  let pressed = false, redrawAfterPress = false;
  for (const part of [root, dialog]) part.addEventListener('pointerdown', () => { pressed = true; });
  for (const type of ['pointerup', 'pointercancel']) window.addEventListener(type, () => {
    if (!pressed) return;
    pressed = false;
    // After the click this press may have started.
    if (redrawAfterPress) { redrawAfterPress = false; setTimeout(() => { render(); renderDialog(); }); }
  });
  const held = () => { if (pressed) redrawAfterPress = true; return pressed; };

  /** Store this account's list and mirror the enabled archives for the Launch button (web/mods-enabled.mjs). */
  const enabledShas = () => items.filter(i => i.enabled).map(i => i.sha256);
  // Writes run one after another, and a re-read (load) never replaces the list when a change was made while it read, so a
  // refresh caused by focus or another tab cannot undo a toggle that is being saved.
  let saving = Promise.resolve(), revision = 0;
  const save = () => { revision++; return saving = saving.catch(() => {}).then(async () => { await setList(account, items); writeEnabled(account, enabledShas()); onChange(enabledShas()); }); };
  const entryOf = id => cat?.files.find(f => f.id === id);
  const catalogueIndex = sha => cat?.files.findIndex(f => f.sha256 === sha) ?? -1;
  /** Verified: the file's SHA-256 is a catalogue file's (else Custom). */
  const verified = item => cat ? cat.files.some(f => f.sha256 === item.sha256) : !!item.verified;
  const badge = item => el('span', {className: 'badge badge-sm ' + (verified(item) ? 'badge-primary' : 'badge-ghost'), dataset: {badge: verified(item) ? 'verified' : 'custom'}}, verified(item) ? 'Verified' : 'Custom');
  /** A local file as an archive record: Verified files carry their catalogue entry. */
  const classified = (sha256, name, size) => { const c = core && cat ? core.classify(cat, name, sha256, size) : null; return {verified: !!c?.verified, catalogue: c?.verified ? cat.files.find(f => f.sha256 === sha256)?.id : undefined}; };
  const defaultSource = entry => { const s = sourcesFor(entry); const p = preference(); return s.includes(p) ? p : s[0] ?? null; };

  async function load() {
    await saving.catch(() => {});
    // A catalogue that could not be fetched (offline) is asked for again: it names Verified files and Hyperspace.
    if (catLoaded && !cat) { try { cat = await catalogue(); } catch { cat = null; } }
    const seen = revision, out = [];
    for (const {sha256, enabled} of await getList(account)) { const a = await getArchive(sha256); if (a) out.push({...a, file: undefined, enabled}); }
    const known = new Map((await listArchives().catch(() => [])).map(a => [a.sha256, {...a, file: undefined}]));
    if (seen !== revision) return; // changed while reading: keep the newer list in memory
    items = out; archives = known;
    if (JSON.stringify(readEnabled(account)) !== JSON.stringify(enabledShas())) { writeEnabled(account, enabledShas()); onChange(enabledShas()); }
    render();
  }
  function line(entry) {
    const job = jobs.get(entry.id), have = archives.get(entry.sha256);
    if (have) return `In this browser · ${MiB(have.size)} · from ${SOURCE_LABEL[have.source] ?? 'a local file'}`;
    if (job?.progress && !job.error) return job.progress.phase === 'hash' ? 'Checking your file…' : job.progress.phase === 'verify' ? 'Verifying checksum…' : job.progress.phase === 'store' ? 'Saving in this browser…' : job.progress.phase === 'connect' ? 'Connecting to ' + SOURCE_LABEL[job.source] + '…' : 'Downloading from ' + SOURCE_LABEL[job.source] + '…';
    if (job?.pending) return `Paused at ${mb(job.pending.done)} of ${mb(entry.size)}`;
    return 'Not in this browser · ' + mb(entry.size);
  }
  function stepButton(step, entry) {
    const job = jobs.get(entry.id), labels = {retry: 'Retry', resume: 'Resume', restart: 'Restart', 'use-server': 'Use this server', 'use-mega': 'Use mega.nz', local: 'Choose a local file', page: 'Official page', manage: 'Manage cached files', signin: 'Sign in again'};
    if (step === 'page') return el('a', {className: 'btn btn-sm', href: entry.page, target: '_blank', rel: 'noopener noreferrer'}, labels.page);
    const b = el('button', {className: 'btn btn-sm', type: 'button'}, labels[step]);
    b.onclick = () => {
      if (step === 'local') return pickLocal([entry]);
      if (step === 'manage') return list.scrollIntoView({block: 'nearest'});
      if (step === 'signin') return location.assign('/?view=signin');
      const source = step.startsWith('use-') ? step.slice(4) : job?.source ?? defaultSource(entry);
      run(entry, source, {restart: step === 'restart'});
    };
    return b;
  }
  function renderCatalogue() {
    if (held()) return;
    if (!cat) { credits.hidden = true; catalogueBox.replaceChildren(el('p', {className: 'muted'}, catLoaded ? 'The mod catalogue is unavailable right now.' : 'Loading the mod catalogue…')); presetsBox.replaceChildren(); sourceBox.replaceChildren(); return; }
    const choosable = cat.files.some(f => sourcesFor(f).length > 1) && !local;
    sourceBox.replaceChildren();
    if (choosable) {
      const select = el('select', {className: 'select select-sm', id: 'mods-source-select', ariaLabel: 'Download source'},
        el('option', {value: 'server'}, 'This server'), el('option', {value: 'mega'}, 'mega.nz'));
      select.value = preference() === 'mega' ? 'mega' : 'server';
      select.onchange = () => { try { localStorage.setItem(prefKey, select.value); } catch {} renderCatalogue(); };
      sourceBox.append(el('label', {className: 'flex items-center gap-2 text-sm'}, 'Download Multiverse from', select));
    
    }
    catalogueBox.replaceChildren(el('ol', {className: 'mod-list catalogue-list'}, ...cat.files.map(entry => {
      const job = jobs.get(entry.id), have = archives.get(entry.sha256), running = !!job && !job.error && (!!job.controller || job.source === 'local');
      const source = job?.source ?? defaultSource(entry);
      const actions = el('span', {className: 'actions'});
      if (!have && !running) {
        if (source) { const via = source === 'mega' ? ' from mega.nz' : ''; const b = fixedButton({className: 'btn btn-sm btn-primary', ariaLabel: `Download ${entry.title} ${entry.version}`}, (job?.pending ? 'Resume' : 'Download') + via, ['Download' + via, 'Resume' + via]); b.onclick = () => run(entry, source); actions.append(b); }
        const pick = el('button', {className: 'btn btn-sm', type: 'button', ariaLabel: `Choose a local file for ${entry.title} ${entry.version}`}, 'Choose file'); pick.onclick = () => pickLocal([entry]); actions.append(pick);
        if (job?.pending) { const x = el('button', {className: 'btn btn-ghost btn-sm', type: 'button'}, 'Discard'); x.onclick = async () => { await discardDownload(entry); jobs.delete(entry.id); render(); }; actions.append(x); }
      }
      if (running && job.controller) { const p = el('button', {className: 'btn btn-sm', type: 'button', ariaLabel: `Pause ${entry.title}`}, 'Pause'); p.onclick = () => job.controller.abort(); actions.append(p); }
      if (have) { const d = el('button', {className: 'btn btn-ghost btn-sm', type: 'button', ariaLabel: `Delete ${entry.title} ${entry.version} from this browser`}, 'Delete'); d.onclick = () => deleteFile(have); actions.append(d); }
      const notes = [el('a', {className: 'link', href: entry.page, target: '_blank', rel: 'noopener noreferrer'}, 'official page')];
      if (entry.licenseNotice) notes.push(' · ', el('a', {className: 'link', href: '/Licenses#' + entry.licenseNotice}, entry.license));
      if (!sourcesFor(entry).length && !have) notes.push(' · ', el('span', {}, entry.mod === 'hyperspace' ? (local ? 'sign in to download it from this server, or ' : 'this server does not offer it; ') + 'get it from the official page and choose Hyperspace.ftl or the release zip' : 'choose a file you have'));
      if (source === 'mega' && !have) notes.push(' · ', el('span', {}, 'connects to mega.nz'));
      const row = el('li', {className: 'mod-row catalogue-row', dataset: {id: entry.id, state: have ? 'cached' : running ? 'running' : job?.error ? 'error' : job?.pending ? 'paused' : 'missing'}},
        el('span', {className: 'mod-name'}, el('strong', {}, entry.title), ' ' + entry.version, el('span', {className: 'muted text-sm'}, ` · ${entry.file}`), el('br'), el('span', {className: 'text-sm', role: 'status'}, line(entry)), el('br'), el('span', {className: 'muted text-sm'}, ...notes)),
        actions);
      if (running && job.progress) row.append(progressBar(job.progress, {label: entry.title + ' download', id: entry.id}));
      if (job?.error) row.append(el('div', {className: 'mod-error', role: 'alert'}, el('p', {className: 'text-sm text-warning'}, describe(job.error)), el('div', {className: 'actions'}, ...errorSteps(job.error, entry).map(s => stepButton(s, entry)))));
      return row;
    })));
    const credited = cat.files.filter(f => f.credits);
    credits.replaceChildren(el('summary', {}, 'Credits'), ...credited.map(f => el('p', {className: 'muted text-sm'}, el('strong', {}, `${f.title} ${f.version}`), ' by ' + f.credits)));
    credits.hidden = !credited.length;
    presetsBox.replaceChildren(...(cat.presets ?? []).map(preset => {
      const files = preset.files.map(entryOf).filter(Boolean), missing = files.filter(f => !archives.has(f.sha256));
      const enabled = items.filter(i => i.enabled).map(i => i.sha256).join() === files.map(f => f.sha256).join();
      const b = fixedButton({className: 'btn btn-sm' + (enabled ? '' : ' btn-primary'), disabled: enabled, ariaLabel: 'Enable preset ' + preset.title}, enabled ? 'Enabled' : 'Enable', ['Enable', 'Enabled']);
      b.onclick = () => enablePreset(preset);
      return el('div', {className: 'mod-row preset-row', dataset: {preset: preset.id}},
        el('span', {className: 'mod-name'}, el('strong', {}, preset.title), el('br'), el('span', {className: 'muted text-sm'}, missing.length ? `${missing.length} of ${files.length} files to get · ${mb(missing.reduce((n, f) => n + f.size, 0))}` : 'All files here')),
        el('span', {className: 'actions'}, b));
    }));
  }
  // Mod scripts (sweep M1b): the private folder their io and os write to (this browser, this account), the mods allowed
  // to run scripts that use io, os, debug or package, and the sandbox setting the browser cannot offer.
  async function renderLua() {
    const {luaFilesUsage, clearLuaFiles} = await import('../lua-files.mjs');
    const used = await luaFilesUsage(account);
    const clear = el('button', {className: 'btn btn-sm', type: 'button', id: 'mods-lua-clear', disabled: used === 0}, 'Clear mod files');
    clear.onclick = async () => {
      if (!await confirmAction({id: 'mods-lua-clear-dialog', title: 'Clear mod files?', yes: 'Clear', danger: true, text: 'Delete the files mod scripts wrote in this browser for this account? Mods that keep settings or progress in files start over.'})) return;
      try { await clearLuaFiles(account); } catch (e) { message(e.message); }
      renderLua();
    };
    const allowed = Object.entries(readConsent(account));
    const rows = allowed.map(([sha, entry]) => {
      const name = items.find(i => i.sha256 === sha) ?? archives.get(sha);
      const again = el('button', {className: 'btn btn-ghost btn-sm', type: 'button', ariaLabel: 'Ask again for ' + (name?.title ?? name?.name ?? sha.slice(0, 12))}, 'Ask again');
      again.onclick = () => { setConsent(account, sha, null); renderLua(); };
      return el('li', {className: 'mod-row', dataset: {luaConsent: sha}}, el('span', {className: 'mod-name'}, el('strong', {}, name?.title ?? name?.name ?? sha.slice(0, 12)),
        el('span', {className: 'muted text-sm'}, ' · allowed to run scripts that use ' + usesText(entry.uses ?? []))), el('span', {className: 'actions'}, again));
    });
    luaBox.replaceChildren(
      el('p', {className: 'text-sm', id: 'mods-lua-files'}, `Files mod scripts wrote in this browser: ${used ? MiB(used) : 'none'} (at most 50 MiB, not synced). `, clear),
      rows.length ? el('ul', {className: 'mod-list', id: 'mods-lua-allowed'}, ...rows) : el('p', {className: 'muted text-sm', id: 'mods-lua-allowed'}, 'No mod has been allowed to run scripts that use files or system commands. The game asks before it starts one.'),
      el('label', {className: 'flex items-center gap-2 text-sm', title: 'A web page cannot give scripts your files or programs.'},
        el('input', {type: 'checkbox', className: 'checkbox checkbox-sm', id: 'mods-lua-unsandboxed', disabled: true}), 'Disable Lua sandboxing (dangerous)'),
      el('p', {className: 'muted text-sm'}, 'Not available in the browser: a web page cannot give scripts your files or programs, so mod scripts always run in their private folder here. The Android app has this setting.'));
  }
  let renderSeq = 0;
  async function render() {
    if (held()) return;
    const seq = ++renderSeq;
    renderCatalogue();
    list.replaceChildren(...items.map((item, index) => {
      const toggle = el('input', {type: 'checkbox', className: 'checkbox checkbox-sm', checked: item.enabled, ariaLabel: 'Enable ' + (item.title ?? item.name)});
      // By file, not by this draw's objects or place: a refresh may have re-read the list since this row was drawn.
      const current = () => items.findIndex(i => i.sha256 === item.sha256);
      toggle.onchange = async () => { highlight = null; const at = current(); if (at >= 0) items[at].enabled = toggle.checked; await save(); render(); };
      const up = el('button', {className: 'btn btn-sm', type: 'button', disabled: index === 0, ariaLabel: 'Move up ' + (item.title ?? item.name)}, '↑');
      const down = el('button', {className: 'btn btn-sm', type: 'button', disabled: index === items.length - 1, ariaLabel: 'Move down ' + (item.title ?? item.name)}, '↓');
      const remove = el('button', {className: 'btn btn-ghost btn-sm', type: 'button', ariaLabel: 'Remove ' + (item.title ?? item.name)}, 'Remove');
      const move = async d => { highlight = null; const at = current(); if (at >= 0 && at + d >= 0 && at + d < items.length) items.splice(at + d, 0, ...items.splice(at, 1)); await save(); render(); };
      up.onclick = () => move(-1); down.onclick = () => move(1);
      remove.onclick = () => removeFromList(item);
      return el('li', {className: 'mod-row', dataset: {enabled: String(item.enabled), sha: item.sha256, ...item.sha256 === highlight ? {highlight: 'true'} : {}}},
        el('label', {className: 'flex items-center gap-2'}, toggle, el('span', {className: 'mod-name'}, el('strong', {}, item.title ?? item.name), item.version ? ' ' + item.version : '', ' ', badge(item), el('span', {className: 'muted text-sm'}, ` · ${item.name} · ${MiB(item.size)} · from ${SOURCE_LABEL[archives.get(item.sha256)?.source ?? item.source] ?? 'a local file'}`))),
        el('span', {className: 'actions'}, up, down, remove));
    }));
    for (const item of items) if (item.enabled && item.marker === undefined) item.marker = await markerOf((await getArchive(item.sha256).catch(() => null)) ?? item);
    if (seq !== renderSeq) return;
    const h = orderHints(items, cat);
    hints.replaceChildren(...h.map(t => el('li', {className: 'text-warning text-sm', dataset: {hint: /not enabled/.test(t) ? 'hyperspace-missing' : 'order'}}, t)));
    const enabled = items.filter(i => i.enabled);
    if (!busy) status.textContent = items.length ? `${enabled.length} of ${items.length} enabled` : 'No mods yet.';
    const id = enabled.length ? 'Id ' + (await modSetHash(enabled.map(i => i.sha256))).slice(0, 12) : '';
    if (seq === renderSeq) setLabel.textContent = id; // an older draw finishing late does not put back its old set
    if (seq === renderSeq) renderLua().catch(() => {});
  }
  // The files are shared by the accounts of this browser; each account has its own list. Remove takes the archive off
  // this account's list (its bytes go only when no other account lists it); Delete removes the file for everyone here,
  // after saying that another account still uses it.
  const nameOf = item => item.title ?? item.name;
  async function removeFromList(item) {
    try {
      const others = (await archiveUsers(item.sha256)).filter(a => a !== account);
      if (!await confirmAction({id: 'mods-remove-dialog', title: `Remove ${nameOf(item)}?`, yes: 'Remove', text: others.length ? `Remove ${nameOf(item)} from your mods? Another account in this browser also uses this file, so it stays in this browser.` : `Remove ${nameOf(item)} from your mods? The file is deleted from this browser; you can download or choose it again later.`})) return;
      const {deleted} = await removeArchive(account, item.sha256); await load(); onChange(enabledShas());
      status.textContent = deleted ? `${nameOf(item)} removed and deleted from this browser.` : `${nameOf(item)} removed from your mods; the file stays for the other account.`;
    } catch (e) { message(e.message); }
  }
  async function deleteFile(item) {
    try {
      const others = (await archiveUsers(item.sha256)).filter(a => a !== account);
      if (!await confirmAction({id: 'mods-delete-dialog', title: `Delete ${nameOf(item)}?`, yes: 'Delete', danger: true, text: [`Delete ${nameOf(item)} from this browser? You can download or choose it again later.`, others.length ? `Another account in this browser also has it in its mods (${others.length === 1 ? 'one account' : others.length + ' accounts'}); it is removed from their mods too.` : null]})) return;
      for (const other of await deleteArchive(item.sha256)) if (other !== account) { const e = readEnabled(other); if (e) writeEnabled(other, e.filter(s => s !== item.sha256)); }
      await load(); onChange(enabledShas()); status.textContent = `${nameOf(item)} deleted from this browser.`;
    } catch (e) { message(e.message); }
  }
  /** Add an archive to this account's list (enabled), catalogue files in catalogue order. */
  async function addToList(record, enabled = true) {
    if (items.some(i => i.sha256 === record.sha256)) return;
    const k = catalogueIndex(record.sha256), item = {...record, file: undefined, enabled};
    const at = k < 0 ? items.length : items.findIndex(i => catalogueIndex(i.sha256) > k);
    items.splice(at < 0 ? items.length : at, 0, item); await save();
  }
  /** A new phase redraws the row; byte counts only move its progress bars (so buttons keep working while it runs). */
  function progress(entry, job, p) {
    const changed = job.progress?.phase !== p.phase; job.progress = p;
    const bars = [...root.querySelectorAll(`[data-progress="${entry.id}"]`), ...dialog.querySelectorAll(`[data-progress="${entry.id}"]`)];
    if (changed || !bars.length) { renderCatalogue(); renderDialog(); } else for (const bar of bars) bar.update(p);
  }
  /** Start (or resume) one catalogue download; resolves true when the file is in this browser. */
  async function run(entry, source, {restart = false} = {}) {
    const old = jobs.get(entry.id);
    if (old?.controller && !old.error) return old.promise;
    const job = {controller: new AbortController(), progress: null, error: null, source, pending: null};
    jobs.set(entry.id, job);
    job.promise = downloadFile({entry, source, fetchServer, signal: job.controller.signal, restart, onProgress: p => progress(entry, job, p)})
      .then(async record => { jobs.delete(entry.id); archives.set(record.sha256, record); await addToList(record); render(); renderDialog(); return true; },
        async error => { error.source = source; job.error = error; job.controller = null; job.pending = await pendingDownload(entry); render(); renderDialog(); return false; });
    render(); return job.promise;
  }
  const pickerInput = el('input', {type: 'file', accept: '.ftl,.zip,application/zip', multiple: true, hidden: true, id: 'mods-local-file', ariaLabel: 'Choose mod files you have'});
  document.body.append(pickerInput); // outside the panel: its own file input stays the panel's only one
  let pickFor = [];
  function pickLocal(entries) { pickFor = entries; pickerInput.value = ''; pickerInput.click(); }
  /** Match chosen files to catalogue entries by size (then SHA-256), whatever their names; a release zip of Hyperspace also works. */
  async function importFiles(files, wanted) {
    let ok = 0;
    for (const file of files) {
      const entry = wanted.find(e => e.size === file.size || e.upstream?.size === file.size) ?? (wanted.length === 1 ? wanted[0] : null);
      if (!entry) { message(`${file.name} is none of the files this needs (no size matches).`); continue; }
      const job = {controller: null, progress: {phase: 'hash', done: 0, total: file.size}, error: null, source: 'local'};
      jobs.set(entry.id, job); render(); renderDialog();
      try {
        const source = entry.upstream && file.size === entry.upstream.size ? await extractUpstream(entry, file) : file;
        const record = await importLocalFile({entry, file: source, onProgress: p => progress(entry, job, p)});
        jobs.delete(entry.id); archives.set(record.sha256, record); await addToList(record); ok++;
      } catch (e) {
        // Not the catalogue's file: it is still accepted, as a Custom mod of its own.
        if (e.kind === 'custom' && e.record) { archives.set(e.record.sha256, e.record); await addToList(e.record); }
        e.source = 'local'; e.local = true; job.error = e.kind ? e : Object.assign(e, {kind: 'api'});
      }
      render(); renderDialog();
    }
    return ok;
  }
  async function extractUpstream(entry, file) {
    const {openZip, blobSource} = await import('./zip.mjs'), zip = await openZip(blobSource(file)), e = zip.get(entry.upstream.entry);
    if (!e) throw Object.assign(Error(`${file.name} does not contain ${entry.upstream.entry}.`), {kind: 'checksum'});
    return new File([await zip.read(e)], entry.file, {type: 'application/zip'});
  }
  pickerInput.onchange = async () => { const files = [...pickerInput.files], wanted = pickFor; pickFor = []; if (files.length) await importFiles(files, wanted); if (pendingPreset && presetReady(pendingPreset)) finishPreset(); };

  // Presets: enabling one turns on exactly its files in its order (other mods stay listed, disabled).
  let pendingPreset = null;
  const presetFiles = preset => preset.files.map(entryOf).filter(Boolean);
  const presetReady = preset => presetFiles(preset).every(f => archives.has(f.sha256));
  async function applyPreset(preset) {
    const files = presetFiles(preset);
    for (const f of files) if (!items.some(i => i.sha256 === f.sha256)) items.push({...archives.get(f.sha256), enabled: true});
    const chosen = files.map(f => items.find(i => i.sha256 === f.sha256)), rest = items.filter(i => !chosen.includes(i));
    items = [...chosen.map(i => ({...i, enabled: true})), ...rest.map(i => ({...i, enabled: false}))];
    await save(); await render(); status.textContent = preset.title + ' enabled.';
  }
  async function finishPreset() {
    const p = pendingPreset; if (!p) return; pendingPreset = null; if (dialog.open) dialog.close();
    if (p.shas) { await useSet(p.shas); p.resolve?.(true); } else await applyPreset(p);
  }
  /** Put this exact archive list (sha256 in order) enabled first, the rest disabled. */
  async function useSet(shas) {
    const chosen = shas.map(s => items.find(i => i.sha256 === s) ?? {...archives.get(s), enabled: true});
    items = [...chosen.map(i => ({...i, enabled: true})), ...items.filter(i => !shas.includes(i.sha256)).map(i => ({...i, enabled: false}))];
    await save(); await render();
  }
  async function enablePreset(preset) {
    if (presetReady(preset)) return applyPreset(preset);
    pendingPreset = preset; renderDialog(); if (!dialog.open) dialog.showModal();
  }
  function renderDialog() {
    if (!pendingPreset || held()) return;
    const preset = pendingPreset, missing = presetFiles(preset).filter(f => !archives.has(f.sha256)), total = missing.reduce((n, f) => n + f.size, 0);
    if (!missing.length && dialog.open) { finishPreset(); return; }
    const anyRunning = missing.some(f => jobs.get(f.id)?.controller && !jobs.get(f.id)?.error);
    const mega = missing.some(f => defaultSource(f) === 'mega');
    const rows = missing.map(f => {
      const job = jobs.get(f.id), source = job?.source ?? defaultSource(f);
      return el('li', {className: 'mod-row', dataset: {id: f.id}},
        el('span', {className: 'mod-name'}, el('strong', {}, `${f.title} ${f.version}`), el('span', {className: 'muted text-sm'}, ` · ${mb(f.size)} · ` + (source === 'mega' ? 'from mega.nz' : source === 'server' ? 'from this server' : 'choose a file you have')),
          job?.error ? el('span', {className: 'text-sm text-warning', role: 'alert'}, el('br'), describe(job.error)) : null),
        job?.progress && !job.error ? progressBar(job.progress, {label: f.title, id: f.id}) : null);
    });
    const downloadLabel = `Download ${mb(missing.filter(f => defaultSource(f)).reduce((n, f) => n + f.size, 0))}`;
    const download = fixedButton({className: 'btn btn-primary', id: 'mods-preset-download', disabled: anyRunning || !missing.some(f => defaultSource(f))}, anyRunning ? 'Downloading…' : downloadLabel, [downloadLabel, 'Downloading…']);
    download.onclick = async () => { const results = await Promise.all(missing.filter(f => defaultSource(f)).map(f => run(f, jobs.get(f.id)?.error ? jobs.get(f.id).source : defaultSource(f)))); if (results.every(Boolean) && presetReady(preset)) finishPreset(); };
    const choose = el('button', {className: 'btn', type: 'button', id: 'mods-preset-choose'}, 'Choose files…'); choose.onclick = () => pickLocal(missing);
    const cancel = fixedButton({className: 'btn btn-ghost', id: 'mods-preset-cancel'}, anyRunning ? 'Pause and close' : 'Cancel', ['Cancel', 'Pause and close']);
    cancel.onclick = () => { for (const f of missing) jobs.get(f.id)?.controller?.abort(); preset.resolve?.(false); pendingPreset = null; dialog.close(); };
    const background = anyRunning ? el('button', {className: 'btn', type: 'button'}, 'Keep downloading in the background') : null;
    if (background) background.onclick = () => dialog.close();
    dialog.replaceChildren(...[
      el('div', {className: 'section-heading'}, el('div', {}, el('span', {className: 'eyebrow'}, 'Preset'), el('h2', {id: 'mods-preset-title'}, 'Enable ' + preset.title))),
      el('p', {}, `${missing.length} of ${presetFiles(preset).length} files to download · ${mb(total)}`),
      el('ol', {className: 'mod-list'}, ...rows),
      mega ? el('p', {className: 'muted text-sm'}, 'This connects your browser to mega.nz (MEGA sees your address).') : null,
      el('div', {className: 'actions'}, download, choose, background, cancel)].filter(Boolean));
  }
  dialog.addEventListener('close', () => { if (pendingPreset && !presetFiles(pendingPreset).some(f => jobs.get(f.id)?.controller)) { pendingPreset.resolve?.(false); pendingPreset = null; } });

  async function add(files) {
    if (busy || !files.length) return;
    busy = true; drop.setAttribute('aria-busy', 'true');
    try {
      await ready.catch(() => {}); // the first load must not replace the list while files are being added
      for (const file of files) {
        // A file of this size already here may be this one, which needs no more room; the hash below decides.
        if (!(await listArchives().catch(() => [])).some(a => a.size === file.size)) await checkFileSpace(file.size, file.name);
        const meter = createMeter(file.size);
        status.replaceChildren(`Checking ${file.name}…`);
        const info = await inspectArchive(file, p => { status.replaceChildren(`Checking ${file.name}… `, formatProgress(meter(p.done))); });
        if (!await getArchive(info.sha256)) {
          status.textContent = `Storing ${file.name} in this browser…`;
          await putArchive({sha256: info.sha256, name: file.name, size: info.size, title: info.title, version: info.version, hyperspace: info.hyperspace, file, addedUtc: new Date().toISOString(), source: 'import', ...classified(info.sha256, file.name, info.size)});
        }
        if (!items.some(i => i.sha256 === info.sha256)) { items.push({sha256: info.sha256, name: file.name, size: info.size, title: info.title, version: info.version, hyperspace: info.hyperspace, enabled: true, ...classified(info.sha256, file.name, info.size)}); await save(); }
      }
      navigator.storage?.persist?.().catch(() => {});
      archives = new Map((await listArchives().catch(() => [])).map(a => [a.sha256, {...a, file: undefined}]));
    } catch (e) { message(e.message); }
    finally { busy = false; drop.removeAttribute('aria-busy'); input.value = ''; render(); }
  }
  input.onchange = () => add([...input.files]);
  drop.ondragover = e => { e.preventDefault(); drop.classList.add('dragging'); };
  drop.ondragleave = () => drop.classList.remove('dragging');
  drop.ondrop = e => { e.preventDefault(); drop.classList.remove('dragging'); add([...e.dataTransfer.files]); };
  status.textContent = 'Loading your mod library…';
  const ready = (async () => {
    // Your mods first (local, fast); the catalogue (a server request) fills in after.
    await load();
    try { cat = await catalogue(); } catch { cat = null; }
    catLoaded = true;
    if (cat) for (const f of cat.files) { const p = await pendingDownload(f); if (p && !archives.has(f.sha256)) jobs.set(f.id, {controller: null, progress: null, error: null, source: p.source, pending: p}); }
    await render();
  })().catch(e => { status.textContent = 'Browser storage is unavailable: ' + e.message; });
  return {refresh: load, ready,
    /** The enabled archives now (the stored list, not the mirror). */
    enabled: async () => { await ready; await load(); return enabledShas(); },
    /** Turn every mod off (to play without mods). */
    disableAll: async () => { await ready; items = items.map(i => ({...i, enabled: false})); await save(); await render(); },
    enablePreset: async id => { await ready; const p = cat?.presets.find(x => x.id === id); return p && enablePreset(p); },
    /**
     * Enable exactly a save's mods ([{name, version, sha256}] in order). Missing catalogue files open the download dialog;
     * resolves true once all are here and enabled, false when cancelled or when a missing file is not in the catalogue
     * (a Custom file: "missing mod: name, sha256").
     */
    async requireSet(mods) {
      await ready; await load();
      const shas = mods.map(m => m.sha256), missing = shas.filter(s => !archives.has(s));
      if (!missing.length) { await useSet(shas); return true; }
      const unknown = missing.filter(s => !cat?.files.some(f => f.sha256 === s));
      if (unknown.length) { message('This save needs mod files this browser does not have: ' + unknown.map(s => 'missing mod: ' + (mods.find(x => x.sha256 === s)?.name ?? s.slice(0, 12)) + ', ' + s).join('; ') + '. Add them under Mods.'); return false; }
      return new Promise(resolve => {
        pendingPreset = {id: 'save', title: 'these mods', files: missing.map(s => cat.files.find(f => f.sha256 === s).id), shas, resolve};
        renderDialog(); if (!dialog.open) dialog.showModal();
      });
    },
    catalogueFor: sha => cat?.files.find(f => f.sha256 === sha) ?? null, download: (entry, source) => run(entry, source ?? defaultSource(entry)),
    /** Why the last download of a catalogue file failed, or null. */
    downloadError: entry => jobs.get(entry.id)?.error ? describe(jobs.get(entry.id).error) : null,
    /** Enable an archive of this browser first in the load order (Hyperspace.ftl, hs-needed.mjs) and point at its row. */
    async enableFirst(sha256) {
      await ready; await load();
      const at = items.findIndex(i => i.sha256 === sha256), item = at >= 0 ? items.splice(at, 1)[0] : {...archives.get(sha256), file: undefined};
      items.unshift({...item, enabled: true}); highlight = sha256; await save(); await render();
      status.textContent = `${nameOf(item)} is enabled, first in your mods.`;
    }};
}
