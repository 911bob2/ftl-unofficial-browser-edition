// The save screens (docs/SAVES.md): one plain list of the account's saves (Saves tab, and Play's "Change" filtered to the
// enabled mods), New save, "No save for ...", and the questions sync leaves to the player (lock held elsewhere, changed
// in two places, taken over, deleted elsewhere), all in the page's own dialogs.
// ctx (from app.mjs): {service, enabled(), label(setup), blocked(), playing(), play(id), missing(setup), libraryMods(),
// message(text), download(value, name), showAll()}.
import {choose, confirmAction, askText, notice, el} from './dialogs.mjs';
import {SAVES_LIMIT} from './savecore.mjs';
import {words} from './save-words.mjs';
export {words};

const fill = (node, ...children) => node.replaceChildren(...children.flat().filter(c => c != null && c !== false));
/** "14:05" today, else "29 Oct 14:05". */
export function clock(iso) {
  const d = new Date(iso); if (Number.isNaN(d.getTime())) return '';
  const t = d.toLocaleTimeString([], {hour: '2-digit', minute: '2-digit'});
  return d.toDateString() === new Date().toDateString() ? t : d.toLocaleDateString([], {day: 'numeric', month: 'short'}) + ' ' + t;
}
/** "just now", "5 min ago", "2 h ago", "3 d ago", then the date. */
export function ago(iso, now = Date.now()) {
  const t = Date.parse(iso); if (!Number.isFinite(t)) return '';
  const s = Math.max(0, Math.round((now - t) / 1000));
  if (s < 60) return 'just now';
  if (s < 3600) return Math.round(s / 60) + ' min ago';
  if (s < 86400) return Math.round(s / 3600) + ' h ago';
  if (s < 86400 * 14) return Math.round(s / 86400) + ' d ago';
  try { return new Date(t).toLocaleDateString(); } catch { return ''; }
}
/** "1 h 5 min", "12 min", "40 s". */
export function duration(seconds) {
  const s = Math.max(0, Math.round(seconds)); if (s < 60) return s + ' s';
  const m = Math.floor(s / 60), h = Math.floor(m / 60);
  return h ? h + ' h ' + (m % 60) + ' min' : m + ' min';
}
const modName = m => m.name + (m.version ? ' ' + m.version : '');
/** Tooltip text of a setup: "Hyperspace 1.23.2, Multiverse Assets 5.5"; "No mods" for none. */
export const modsLabel = mods => mods.length ? mods.map(modName).join(', ') : 'No mods';
/** Stock ships by blueprint id, as the Android launcher names them (ui/Theme.kt shipLabel; SaveWordsTest compares). */
const STOCK_SHIPS = {
  PLAYER_SHIP_HARD: 'The Kestrel', PLAYER_SHIP_STEALTH: 'Stealth Cruiser', PLAYER_SHIP_MANTIS: 'Mantis Cruiser',
  PLAYER_SHIP_CIRCLE: 'Engi Cruiser', PLAYER_SHIP_FED: 'Federation Cruiser', PLAYER_SHIP_JELLY: 'Slug Cruiser',
  PLAYER_SHIP_ROCK: 'Rock Cruiser', PLAYER_SHIP_ENERGY: 'Zoltan Cruiser', PLAYER_SHIP_CRYSTAL: 'Crystal Cruiser',
  PLAYER_SHIP_ANAEROBIC: 'Lanius Cruiser',
};
/** The list's ship: a save names a stock ship by its id unless the player renamed it; a mod's ship keeps what it has. */
export const shipLabel = ship => ship == null ? null : STOCK_SHIPS[String(ship).replace(/_[23]$/, '')] ?? ship;
/** The muted line of a save: "Kestrel A · sector 3 · 120 scrap · hull 25/30 · 1 h 5 min · 2 h ago", or "Empty". */
export function summaryLine(row) {
  if (!row.hasRun) return 'Empty';
  const s = row.meta?.summary ?? {};
  const parts = [shipLabel(s.ship ?? s.shipId), s.sector ? 'sector ' + s.sector : null, s.scrap != null ? s.scrap + ' scrap' : null,
    s.hull != null ? 'hull ' + s.hull + (s.maxHull ? '/' + s.maxHull : '') : null, s.playSeconds > 0 ? duration(s.playSeconds) : null, row.playedAt ? ago(row.playedAt) : null].filter(Boolean);
  return parts.join(' · ') || 'Run in progress';
}
/** The status badge of a save, as the Android launcher shows it. */
export function statusBadge(row, {local}) {
  const status = row.decision?.status;
  if (local || !row.cloud && (row.local?.base ?? 0) === 0) return ['local', 'This browser'];
  if (status === 'CONFLICT') return ['conflict', 'Changed in two places'];
  if (status === 'OVERRIDDEN') return ['overridden', 'Taken over'];
  if (status === 'DELETED') return ['deleted', 'Deleted elsewhere'];
  if (status === 'PENDING' || row.local?.dirty) return ['pending', 'Not synced'];
  if (!row.local || !row.local.hasRun && row.cloud?.hasRun) return ['cloud', 'Cloud'];
  return null;
}
/** "In use on Pixel 8 · last seen 14:05" (another tab of this browser says so). */
export function lockLine(lock, sameDevice) {
  const device = sameDevice ? 'another tab of this browser' : lock.device || 'another device';
  return lock.lastSeen ? words('lockLine', {device, time: clock(lock.lastSeen)}) : words('lockTitle', {device});
}

// ⋯ menus close on an outside click or Escape.
let menusWired = false;
function wireMenus() {
  if (menusWired) return; menusWired = true;
  document.addEventListener('click', e => { for (const d of document.querySelectorAll('details.row-menu[open]')) if (!d.contains(e.target)) d.open = false; });
  document.addEventListener('keydown', e => { if (e.key === 'Escape') for (const d of document.querySelectorAll('details.row-menu[open]')) d.open = false; });
}

const mounted = new Map(); // root -> render arguments, redrawn by refreshSaves()
let refreshTimer = null;
/** Redraws every visible list soon (after a change here, in another tab or by the syncer). */
export function refreshSaves() {
  clearTimeout(refreshTimer);
  refreshTimer = setTimeout(() => { for (const [root, args] of mounted) { if (!root.isConnected) mounted.delete(root); else if (root.getClientRects().length) renderSaves(args); } }, 60);
}

/** The list. only: null (every save) or 'enabled' (the setup of the enabled mods). onPlay runs before a row's Play. */
export async function renderSaves({root, ctx, only = null, onPlay = null}) {
  wireMenus();
  mounted.set(root, {root, ctx, only, onPlay});
  if (!root._pressWatched) {
    root._pressWatched = true;
    root.addEventListener('pointerdown', () => { root._pressed = true; });
    for (const type of ['pointerup', 'pointercancel']) window.addEventListener(type, () => {
      if (!root._pressed) return;
      root._pressed = false;
      // After the click this press may have started.
      if (root._dirty) setTimeout(refreshSaves);
    });
  }
  const seq = root._seq = (root._seq ?? 0) + 1, {service} = ctx;
  let rows, enabled, current;
  try {
    [rows, enabled] = await Promise.all([service.rows(), ctx.enabled()]);
    current = await service.currentFor(enabled.key, rows);
  } catch (e) { if (seq === root._seq) fill(root, el('p', {className: 'text-sm text-warning', role: 'status'}, 'Saves are unavailable: ' + e.message)); return; }
  const shown = rows.filter(r => only !== 'enabled' || r.setup.key === enabled.key)
    .sort((a, b) => Number(b.setup.key === enabled.key) - Number(a.setup.key === enabled.key) || Number(b.id === current?.id) - Number(a.id === current?.id));
  const labels = new Map(), missing = new Map();
  for (const r of shown) {
    if (!labels.has(r.setup.key)) labels.set(r.setup.key, await ctx.label(r.setup).catch(() => r.setup.mods.length + ' mods'));
    if (r.setup.mods.length && !missing.has(r.setup.key)) missing.set(r.setup.key, await ctx.missing(r.setup).catch(() => []));
  }
  if (seq !== root._seq) return;
  // Do not close a menu under the player's hand, or replace a button between its press and release (the click would be lost).
  if (root.querySelector('details.row-menu[open]') || root._pressed) { root._dirty = true; return; }
  root._dirty = false;
  const blocked = ctx.blocked(), playing = ctx.playing();
  const status = el('p', {className: 'text-sm text-warning slot-note', role: 'status'});
  const fail = e => { status.textContent = e.message; };
  const items = shown.map(r => {
    const [badgeKind, badgeText] = statusBadge(r, {local: service.local}) ?? [];
    const isCurrent = r.setup.key === enabled.key && r.id === current?.id;
    const play = el('button', {className: 'btn btn-primary btn-sm', type: 'button', disabled: !!blocked, title: blocked ?? undefined, ariaLabel: (r.hasRun ? 'Play ' : 'Start ') + r.name, dataset: {action: 'play'}}, r.hasRun ? 'Play' : 'Start');
    play.onclick = async () => { try { onPlay?.(); await ctx.play(r.id); } catch (e) { fail(e); } };
    const item = (label, action, handler, extra = {}) => { const b = el('button', {className: 'menu-item', type: 'button', dataset: {action}, ...extra}, label); b.onclick = async () => { menu.open = false; try { await handler(); } catch (e) { fail(e); } }; return b; };
    const busy = r.id === playing ? 'This save is running: use Save and close first' : null;
    const menu = el('details', {className: 'row-menu'}, el('summary', {className: 'btn btn-ghost btn-sm', ariaLabel: 'More for ' + r.name, title: 'More'}, '⋯'), el('div', {className: 'menu-pop'},
      item('Rename', 'rename', () => renameSave(ctx, r)),
      item('Download backup', 'download', async () => ctx.download(await service.backup(r.id), 'ftl-save-' + r.name.replace(/[^\w-]+/g, '-') + '.json')),
      service.sync.cloudEnabled && r.cloud ? item('Recovery snapshots', 'snapshots', () => snapshotsDialog(ctx, r)) : null,
      item('Advanced: mods', 'mods', () => modsDialog(ctx, r), {disabled: !!busy, title: busy ?? undefined}),
      item('Delete', 'delete', () => deleteSave(ctx, r), {disabled: !!busy, title: busy ?? undefined})));
    menu.addEventListener('toggle', () => { if (!menu.open && root._dirty) renderSaves({root, ctx, only, onPlay}); });
    const label = labels.get(r.setup.key);
    const badges = [el('span', {className: 'badge badge-sm setup-badge', title: r.setup.mods.length ? modsLabel(r.setup.mods) : undefined, dataset: {badge: 'setup', setup: r.setup.key === 'vanilla' ? 'none' : 'mods'}}, label)];
    if (isCurrent) badges.push(el('span', {className: 'badge badge-sm badge-primary', dataset: {badge: 'current'}}, 'Current'));
    if (badgeKind) badges.push(el('span', {className: 'badge badge-sm ' + (['conflict', 'overridden', 'deleted'].includes(badgeKind) ? 'badge-error' : 'badge-ghost'), dataset: {badge: badgeKind}}, badgeText));
    const played = r.meta?.playedOn;
    const lines = [el('div', {className: 'muted text-sm save-line'}, summaryLine(r))];
    if (played?.device) lines.push(el('div', {className: 'muted text-sm save-played'}, 'Last played on ' + played.device + (played.platform ? ' (' + played.platform + ')' : '')));
    if (r.lock) lines.push(el('div', {className: 'text-sm text-warning save-lock'}, lockLine(r.lock, service.sync.sameDevice(r.lock))));
    for (const m of missing.get(r.setup.key) ?? []) lines.push(el('div', {className: 'text-sm text-warning save-missing', dataset: {sha: m.sha256}}, m.inCatalogue ? 'Downloads when played: ' + modName(m) : 'missing mod: ' + m.name + ', ' + m.sha256));
    if (r.error) lines.push(el('div', {className: 'text-sm text-warning save-error'}, words('notSynced', {reason: r.error})));
    return el('li', {className: 'save-row', dataset: {id: r.id, setup: r.setup.key, status: r.decision?.status ?? '', current: String(isCurrent)}},
      el('div', {className: 'save-main'}, el('div', {className: 'save-title'}, el('strong', {}, r.name), ...badges), ...lines),
      el('div', {className: 'actions'}, play, menu));
  });
  fill(root, blocked ? el('p', {className: 'text-sm text-warning'}, blocked) : null,
    items.length ? el('ol', {className: 'save-list'}, ...items) : el('p', {className: 'muted'}, only === 'enabled' ? 'No saves for these mods yet.' : 'No saves yet.'), status);
}

async function renameSave(ctx, r) {
  const name = await askText({id: 'save-rename-dialog', title: 'Rename', label: 'Name (1 to 40 characters)', value: r.name, okLabel: 'Rename', check: t => ctx.service.core.cleanName(t) ? null : 'Use a name of 1 to 40 characters.'});
  if (!name || name === r.name) return;
  await ctx.service.sync.rename(r.id, ctx.service.core.cleanName(name));
  ctx.service.sync.requestSync(); refreshSaves();
}

async function deleteSave(ctx, r) {
  const {service} = ctx, inCloud = !!r.cloud;
  const yes = await confirmAction({id: 'save-delete-dialog', title: words('deleteTitle', {name: r.name}), danger: true, yes: 'Delete',
    text: words(inCloud ? 'deleteCloud' : 'deleteLocal')});
  if (!yes) return;
  try { await service.sync.remove(r.id); }
  catch (e) {
    if (e.code !== 'locked') throw e;
    const lock = e.detail?.lock;
    const force = await confirmAction({id: 'save-delete-locked-dialog', title: words('lockTitle', {device: lock?.device || 'another device'}), danger: true, yes: words('deleteAnyway'),
      text: lock ? lockLine(lock, service.sync.sameDevice(lock)) + '.' : 'Another game has this save open.', warning: words('deleteLockedWarning')});
    if (!force) return;
    await service.sync.remove(r.id, {override: true});
  }
  refreshSaves();
}

async function snapshotsDialog(ctx, r) {
  const {service} = ctx, list = el('div', {className: 'snapshot-list', id: 'save-snapshots'}, el('p', {className: 'muted text-sm'}, 'Loading…'));
  const kinds = {auto: 'auto', manual: 'manual', import: 'import', restore: 'restore'};
  const shown = choose({id: 'save-snapshots-dialog', title: 'Recovery snapshots', eyebrow: r.name, className: 'slot-dialog',
    text: words('snapshotsText'), content: [list],
    actions: [{label: 'Close', value: null, kind: 'ghost', id: 'save-snapshots-close'}]});
  try {
    const snapshots = await service.sync.snapshots(r.id), current = snapshots[0]?.revision;
    fill(list, snapshots.length ? snapshots.map(s => {
      const restore = el('button', {className: 'btn btn-sm', type: 'button'}, 'Restore');
      restore.onclick = async () => {
        document.getElementById('save-snapshots-dialog')?.close();
        const yes = await confirmAction({id: 'save-restore-dialog', title: words('restoreTitle', {time: clock(s.createdUtc)}), yes: 'Restore', danger: true,
          text: words('restoreText'), warning: r.local?.dirty ? words('restoreUnsynced') : null});
        if (!yes) return;
        try { await service.sync.restore(r.id, s.revision); ctx.message(words('restored', {name: r.name, time: clock(s.createdUtc)})); }
        catch (e) { ctx.message(e.message); }
        refreshSaves();
      };
      const get = el('button', {className: 'btn btn-ghost btn-sm', type: 'button'}, 'Download');
      get.onclick = async () => { try { const p = await service.sync.snapshot(r.id, s.revision); ctx.download(snapshotBackup(p), 'ftl-snapshot-' + s.revision + '.json'); } catch (e) { ctx.message(e.message); } };
      return el('div', {className: 'snapshot-row', dataset: {revision: String(s.revision)}},
        el('span', {}, [clock(s.createdUtc), kinds[s.kind] ?? s.kind, 'revision ' + s.revision, s.revision === current ? 'current' : null].filter(Boolean).join(' · ')),
        el('div', {className: 'actions'}, s.revision === current || ctx.playing() === r.id ? null : restore, get));
    }) : el('p', {className: 'muted text-sm'}, 'No snapshots yet.'));
  } catch (e) { fill(list, el('p', {className: 'text-sm text-warning'}, e.message)); }
  await shown;
}

/**
 * Saves > a save > Advanced: its mod files (only here, with a warning: a save is locked to its mods). first: a mod put at
 * the top of the list for the player to confirm (Hyperspace.ftl, from the "Hyperspace mod without Hyperspace" warning).
 * Resolves true when the save's mods changed.
 */
export async function modsDialog(ctx, r, {first = null} = {}) {
  const {service} = ctx, core = service.core;
  let mods = r.setup.mods.map(m => ({...m}));
  if (first && !mods.some(m => m.sha256 === first.sha256)) mods.unshift({...first});
  const library = await ctx.libraryMods().catch(() => []);
  const listBox = el('ol', {className: 'mod-list', id: 'save-mods-list'}), add = el('select', {className: 'select select-sm', id: 'save-mods-add', ariaLabel: 'Mod file to add'}), addButton = el('button', {className: 'btn btn-sm', type: 'button', id: 'save-mods-add-button'}, 'Add');
  const draw = () => {
    fill(listBox, mods.length ? mods.map((m, i) => {
      const up = el('button', {className: 'btn btn-sm', type: 'button', disabled: i === 0, ariaLabel: 'Move up ' + m.name}, '↑'); up.onclick = () => { mods.splice(i - 1, 0, ...mods.splice(i, 1)); draw(); };
      const down = el('button', {className: 'btn btn-sm', type: 'button', disabled: i === mods.length - 1, ariaLabel: 'Move down ' + m.name}, '↓'); down.onclick = () => { mods.splice(i + 1, 0, ...mods.splice(i, 1)); draw(); };
      const remove = el('button', {className: 'btn btn-ghost btn-sm', type: 'button', ariaLabel: 'Remove ' + m.name}, 'Remove'); remove.onclick = () => { mods.splice(i, 1); draw(); };
      return el('li', {className: 'mod-row', dataset: {sha: m.sha256}}, el('span', {className: 'mod-name'}, el('strong', {}, m.name), m.version ? ' ' + m.version : '', ' ',
        el('span', {className: 'badge badge-sm ' + (m.verified ? 'badge-primary' : 'badge-ghost')}, m.verified ? 'Verified' : 'Custom'), el('br'), el('span', {className: 'muted text-sm'}, m.sha256)), el('span', {className: 'actions'}, up, down, remove));
    }) : el('li', {className: 'muted'}, 'No mods.'));
    const available = library.filter(l => !mods.some(m => m.sha256 === l.sha256));
    fill(add, available.map(l => el('option', {value: l.sha256}, modName(l) + (l.verified ? '' : ' (Custom)'))));
    add.disabled = addButton.disabled = !available.length;
  };
  addButton.onclick = () => { const l = library.find(x => x.sha256 === add.value); if (l) { mods.push({name: l.name, version: l.version ?? '', sha256: l.sha256, size: l.size, verified: !!l.verified}); draw(); } };
  draw();
  const answer = await choose({id: 'save-mods-dialog', title: 'Mods of ' + r.name, eyebrow: 'Advanced', className: 'slot-dialog',
    text: ['A save is locked to its mods, in this order. Other mod files in this browser can be added here.',
      first ? el('p', {className: 'text-sm', id: 'save-mods-added'}, first.name + ' is added first. Change the mods to keep it.') : null],
    content: [listBox, el('div', {className: 'actions'}, add, addButton)],
    warning: 'Changing the mods can break this run: what a removed mod added is dropped when the save loads (the game lists it), and Hyperspace data of the old set is not read. The save keeps its unlocks.',
    actions: [{label: 'Cancel', value: false, kind: 'ghost', id: 'save-mods-cancel'}, {label: 'Change the mods', value: true, kind: 'danger', id: 'save-mods-apply'}], cancel: false});
  if (!answer) return false;
  const setup = service.setupOf(mods);
  if (setup.key === r.setup.key && JSON.stringify(setup.mods) === JSON.stringify(r.setup.mods)) return false;
  if (!r.local) await service.sync.prepare(r.id);
  const changed = await service.store.update(r.id, cur => ({...cur, setup, dirty: true, run: cur.run == null ? cur.run : service.runForSetup(cur.run, setup.key)}));
  if (!changed) throw Error('Download this save first: connect and open it once.');
  service.choose(setup.key, r.id);
  service.sync.requestSync(); refreshSaves();
  if (!first) ctx.message('The mods of ' + r.name + ' changed.'); // from the Hyperspace warning the save starts next
  return true;
}

/**
 * New save: Name (prefilled "Save N") and the enabled mods, greyed out: a save starts with those. Other mods are chosen in
 * the mod picker (Change mods), the one place that enables them. Creating an empty save makes it the one Launch FTL plays
 * for that setup; nothing downloads. onLeave closes whatever opened this before the picker shows. Resolves the save or null.
 */
export async function newSave({ctx, onLeave = null}) {
  const {service} = ctx;
  const [rows, firstEnabled] = await Promise.all([service.rows(), ctx.enabled()]);
  let enabled = firstEnabled, hsAsked = false;
  return new Promise(resolve => {
    document.getElementById('new-save-dialog')?.remove();
    const dialog = el('dialog', {className: 'user-dialog save-dialog', id: 'new-save-dialog', ariaLabelledby: 'new-save-title'});
    document.body.append(dialog);
    const mods = el('ul', {className: 'new-save-mods', id: 'new-save-mods', ariaLabel: 'Enabled mods'});
    const drawMods = () => fill(mods, enabled.mods.length
      ? enabled.mods.map(m => el('li', {}, el('input', {type: 'checkbox', className: 'checkbox checkbox-sm', checked: true, disabled: true, tabIndex: -1, ariaHidden: 'true'}), modName(m)))
      : [el('li', {}, 'No mods: the game as it ships.')]);
    drawMods();
    const name = el('input', {className: 'input w-full', id: 'new-save-name', maxLength: 40, autocomplete: 'off', value: service.core.nextName(rows.map(r => r.name))});
    const note = el('p', {className: 'text-sm text-warning', id: 'new-save-note', role: 'status'});
    const create = el('button', {className: 'btn btn-primary', type: 'button', id: 'new-save-create'}, 'Create');
    const cancel = el('button', {className: 'btn btn-ghost', type: 'button', id: 'new-save-cancel'}, 'Cancel');
    const change = el('button', {className: 'btn btn-sm', type: 'button', id: 'new-save-change-mods'}, 'Change mods');
    let finished = false;
    const done = value => { if (finished) return; finished = true; if (dialog.open) dialog.close(); dialog.remove(); resolve(value); };
    const full = rows.length >= SAVES_LIMIT, blockedNow = ctx.blocked();
    note.textContent = blockedNow ?? (full ? words('savesFull', {count: SAVES_LIMIT}) : '');
    create.disabled = full || !!blockedNow;
    change.disabled = !!blockedNow;
    cancel.onclick = () => done(null);
    change.onclick = () => { done(null); onLeave?.(); ctx.openMods(); };
    dialog.addEventListener('cancel', e => { e.preventDefault(); done(null); });
    create.onclick = async () => {
      if (!service.core.cleanName(name.value)) { note.textContent = 'Use a name of 1 to 40 characters.'; return; }
      create.disabled = true; note.className = 'muted text-sm'; note.textContent = 'Creating…';
      try {
        // A mod that uses Hyperspace without Hyperspace.ftl: asked once (app.mjs, mods/hs-needed.mjs).
        if (!hsAsked && enabled.mods.length && ctx.hyperspace) {
          const step = await ctx.hyperspace(enabled);
          if (step === 'stop') { note.textContent = ''; create.disabled = false; return; }
          hsAsked = true;
          if (step === 'again') {
            enabled = await ctx.enabled(); drawMods();
            note.className = 'text-sm'; note.textContent = 'Hyperspace.ftl is enabled now, first in your mods. Create to go on.'; create.disabled = false; return;
          }
        }
        const made = await service.create(enabled, name.value); refreshSaves(); done(made);
      }
      catch (e) { note.className = 'text-sm text-warning'; note.textContent = e.message; create.disabled = false; }
    };
    name.addEventListener('keydown', e => { if (e.key === 'Enter' && !create.disabled) { e.preventDefault(); create.click(); } });
    fill(dialog, el('h2', {id: 'new-save-title'}, 'New save'),
      el('p', {className: 'muted text-sm'}, 'A new save starts like a fresh install: only the default ships are unlocked.'),
      el('label', {className: 'field'}, 'Name', name),
      el('div', {className: 'field'}, el('span', {}, 'Mods'), mods,
        el('p', {className: 'muted text-sm'}, enabled.mods.length ? 'This save starts with the enabled mods. For other mods, change them first.' : 'To start with mods, enable them first.'), el('div', {}, change)),
      note, el('div', {className: 'actions'}, create, cancel));
    dialog.showModal(); name.focus(); name.select();
  });
}

/**
 * "No save for <mods>": launching with mods never creates a save by itself. withoutMods: the saves without mods that have a
 * run, [{id, name}] (only where a run may be copied: mods without Hyperspace), or null.
 * Resolves {action: 'create'} | {action: 'copy', id} | null.
 */
export function askNoSave({label, withoutMods = null}) {
  return new Promise(resolve => {
    document.getElementById('no-save-dialog')?.remove();
    const dialog = el('dialog', {className: 'user-dialog save-dialog', id: 'no-save-dialog', ariaLabelledby: 'no-save-title'});
    document.body.append(dialog);
    let finished = false;
    const done = value => { if (finished) return; finished = true; if (dialog.open) dialog.close(); dialog.remove(); resolve(value); };
    const create = el('button', {className: 'btn btn-primary', type: 'button', id: 'no-save-create'}, 'Create save'); create.onclick = () => done({action: 'create'});
    const cancel = el('button', {className: 'btn btn-ghost', type: 'button', id: 'no-save-cancel'}, 'Cancel'); cancel.onclick = () => done(null);
    dialog.addEventListener('cancel', e => { e.preventDefault(); done(null); });
    const pick = withoutMods?.length > 1 ? el('select', {className: 'select select-sm', id: 'no-save-copy-from', ariaLabel: 'Save without mods'}, ...withoutMods.map(s => el('option', {value: s.id}, s.name))) : null;
    const copy = withoutMods?.length ? el('button', {className: 'btn', type: 'button', id: 'no-save-copy'}, 'Use a save without mods') : null;
    if (copy) copy.onclick = () => done({action: 'copy', id: pick ? pick.value : withoutMods[0].id});
    fill(dialog, el('h2', {id: 'no-save-title'}, 'No save for ' + label), el('p', {}, 'Create a save for these mods?'),
      el('div', {className: 'actions'}, create, pick, copy, cancel),
      copy ? el('p', {className: 'muted text-sm', id: 'no-save-warning'}, 'It may not work with these mods.') : null);
    dialog.showModal(); create.focus();
  });
}

/** Play's "Change": the saves of the enabled mods with New save, and a link to every save. Resolves when closed. */
export function manageSaves({ctx, enabled, label}) {
  return new Promise(resolve => {
    document.getElementById('save-manager')?.remove();
    const dialog = el('dialog', {className: 'user-dialog slot-dialog', id: 'save-manager', ariaLabelledby: 'save-manager-title'});
    document.body.append(dialog);
    const list = el('div', {id: 'save-manager-list'});
    const add = el('button', {className: 'btn btn-sm', type: 'button', id: 'save-manager-new'}, 'New save');
    add.onclick = () => { newSave({ctx, onLeave: () => dialog.close()}); };
    const all = el('button', {className: 'text-action link-button', type: 'button', id: 'save-manager-all'}, 'Show all saves');
    let showAll = false;
    all.onclick = () => { showAll = true; dialog.close(); };
    const closeButton = el('button', {className: 'btn btn-primary', type: 'button', id: 'save-manager-done'}, 'Done'); closeButton.onclick = () => dialog.close();
    dialog.addEventListener('close', () => { mounted.delete(list); dialog.remove(); if (showAll) ctx.showAll(); resolve(); });
    fill(dialog, el('div', {className: 'section-heading'}, el('div', {}, el('span', {className: 'eyebrow'}, label), el('h2', {id: 'save-manager-title'}, 'Saves'))),
      list, el('div', {className: 'actions'}, add), el('div', {className: 'actions dialog-foot'}, all, closeButton));
    renderSaves({root: list, ctx, only: 'enabled', onPlay: () => dialog.close()}).then(() => dialog.showModal());
  });
}

// ---- The questions sync leaves to the player (docs/SAVES.md) ----

/** A save open elsewhere: "Override lock" after a warning, or Cancel. Resolves true to override. */
export function askOverride(lock, sameDevice) {
  const where = sameDevice ? 'another tab of this browser' : lock?.device || 'another device';
  return confirmAction({id: 'save-lock-dialog', title: words('lockTitle', {device: where}), yes: words('override'), danger: true,
    text: lockLine(lock ?? {}, sameDevice) + '.',
    warning: sameDevice ? 'That tab stops at once; what it played since its last save is lost.' : words('overrideWarning', {device: where})});
}
/** Changed in two places: resolves 'cloud', 'mine' or null. */
export function askConflict(name) {
  return choose({id: 'save-conflict-dialog', title: words('conflictTitle', {name}), cancel: null,
    text: [words('conflictText'), el('p', {className: 'muted text-sm'}, words('conflictChoices'))],
    actions: [{label: 'Cancel', value: null, kind: 'ghost', id: 'save-conflict-cancel'}, {label: words('useCloud'), value: 'cloud', id: 'save-conflict-cloud'}, {label: words('keepMine'), value: 'mine', kind: 'primary', id: 'save-conflict-mine'}]});
}
/** The save was taken over: the notice, once. */
export function showOverridden(by, at, anotherTab = false) {
  return notice({id: 'save-overridden-dialog', title: words('overriddenTitle'), required: true,
    text: anotherTab ? 'This save was taken over in another tab of this browser. It goes on there from its last save.'
      : words(at ? 'overriddenAt' : 'overridden', {device: by || 'another device', time: at ? clock(at) : ''})});
}
/** Deleted on another device with unsynced progress here: resolves 'upload', 'delete' or null. */
export function askDeleted(name) {
  return choose({id: 'save-deleted-dialog', title: words('deletedTitle', {name}), cancel: null,
    text: words('deletedText'),
    actions: [{label: 'Cancel', value: null, kind: 'ghost', id: 'save-deleted-cancel'}, {label: words('deleteHereToo'), value: 'delete', kind: 'danger', id: 'save-deleted-delete'}, {label: words('uploadAgain'), value: 'upload', kind: 'primary', id: 'save-deleted-upload'}]});
}
/**
 * A recovery snapshot as a file: the backup format of Download backup (save-service.mjs, schema 3) with the snapshot's
 * profile, so an import keeps its unlocks. A snapshot from before save model 3 has no profile (null, as schema 2 had).
 */
export function snapshotBackup(p) {
  return {schema: 3, kind: 'ftl-save', name: p.name, setup: p.setup, meta: p.meta ?? {}, run: p.run ?? null, profile: p.profile ?? null, savedUtc: new Date().toISOString()};
}
/** What a loaded save lost because the game files or mods lack it. */
export function showMissingContent(names) {
  return notice({id: 'missing-content-dialog', title: 'Some content is missing',
    text: ['This save names content the current game files or mods lack. It was removed from the run:', el('ul', {className: 'plain-list', id: 'missing-content-list'}, ...names.map(n => el('li', {}, n))), 'The next save keeps the run without it.']});
}
