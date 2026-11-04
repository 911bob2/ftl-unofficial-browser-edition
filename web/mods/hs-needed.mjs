// "Hyperspace mod without Hyperspace" (docs/MODS.md). The engine has Hyperspace's program built in and turns it on when
// the merged mods hold data/hyperspace.xml, which Multiverse ships too. Hyperspace.ftl is Hyperspace's own data (images,
// fonts, translations, sounds, default settings); native Hyperspace needs it, loaded first. A set that uses Hyperspace
// without it starts and plays with some of that data missing, so it stays allowed: the player is warned once per start
// and the mod picker shows a quiet hint. Loaded only for starts with mods and by the mod picker.
import {getArchive} from './store.mjs';
import {blobSource, openZip} from './zip.mjs';
import {choose, el} from '../dialogs.mjs';

/** Hyperspace's own releases, for when this server cannot give the file. */
export const HYPERSPACE_RELEASES = 'https://github.com/FTL-Hyperspace/FTL-Hyperspace/releases';
/** An entry that uses Hyperspace: its hyperspace.xml, or a patch of that file (append, raw append or clobber, Lua script). */
const MARKER = /^data\/hyperspace\.(xml(\.(append|rawappend|rawclobber))?|(append|rawappend|rawclobber)\.xml|append\.lua)$/i;
export const usesHyperspace = names => names.some(n => MARKER.test(String(n).replace(/\\/g, '/')));
/** The catalogue's Hyperspace file. */
export const hyperspaceEntry = cat => cat?.files?.find(f => f.mod === 'hyperspace') ?? null;
/**
 * Hyperspace.ftl itself: a catalogue file of mod "hyperspace" by SHA-256 (as the catalogue names files: Verified), or an
 * archive whose own metadata title is "Hyperspace" (another build of it, Custom). Never by file name.
 */
export const isHyperspace = (mod, cat) => !!cat?.files?.some(f => f.mod === 'hyperspace' && f.sha256 === mod.sha256) || /^hyperspace$/i.test(mod.title ?? '');
/** mods: [{sha256, title, marker, ...}] in load order. The first that uses Hyperspace when none is Hyperspace.ftl, else null. */
export const withoutHyperspace = (mods, cat) => mods.some(m => isHyperspace(m, cat)) ? null : mods.find(m => m.marker) ?? null;

const markers = new Map();
/** Whether a stored archive uses Hyperspace: data/hyperspace.xml (noted when it was added) or a patch of it (its file list). */
export async function markerOf(archive) {
  if (markers.has(archive.sha256)) return markers.get(archive.sha256);
  let marker = !!archive.hyperspace;
  if (!marker && archive.file) { try { marker = usesHyperspace((await openZip(blobSource(archive.file))).entries.map(e => e.name)); } catch { marker = false; } }
  markers.set(archive.sha256, marker);
  return marker;
}
/**
 * A save's or the enabled mods ([{name, version, sha256}] in order): the one that uses Hyperspace while Hyperspace.ftl is
 * not among them, or null. Archives not in this browser are not looked into.
 */
export async function hyperspaceMissing(mods, cat) {
  const seen = [];
  for (const m of mods) { const a = await getArchive(m.sha256).catch(() => null); seen.push({...m, title: a?.title ?? null, ownVersion: a?.version ?? null, marker: a ? await markerOf(a) : false}); }
  return withoutHyperspace(seen, cat);
}

/** As the picker names it: the catalogue's name and version (Verified), else the archive's own title. */
const modName = m => { const [name, version] = m.verified || !m.title ? [m.name, m.version] : [m.title, m.ownVersion]; return name + (version ? ' ' + version : ''); };
const mb = n => (n / 1048576).toFixed(1) + ' MB';
const link = (href, text) => el('a', {className: 'link', href, target: '_blank', rel: 'noopener noreferrer'}, text);
export const WARNING_TITLE = 'Hyperspace mod without Hyperspace';
export const warningText = m => `${modName(m)} needs Hyperspace.ftl, Hyperspace's own data, loaded first. Without it some Hyperspace images and text are missing.`;

/**
 * The warning before a start: Enable Hyperspace, Run anyway or Cancel. save: the name of the save that is starting, or null
 * for a new one. Resolves 'enable', 'run' or null.
 */
export function askHyperspace({mod, save = null}) {
  return choose({id: 'hs-missing-dialog', title: WARNING_TITLE, eyebrow: save ?? undefined,
    text: [warningText(mod), el('p', {className: 'muted text-sm'}, save ? 'This save\'s mods change only under Saves > Advanced: mods. Enable Hyperspace takes you there, or to a new save.'
      : 'Enable Hyperspace turns on Hyperspace.ftl, first in your mods, and carries on.')],
    actions: [{label: 'Cancel', value: null, kind: 'ghost', id: 'hs-missing-cancel'}, {label: 'Run anyway', value: 'run', id: 'hs-missing-run'}, {label: 'Enable Hyperspace', value: 'enable', kind: 'primary', id: 'hs-missing-enable'}]});
}

/** An existing save: its own mods (Advanced) or a new save. Resolves 'advanced', 'new' or null. */
export function askSaveHyperspace(save) {
  return choose({id: 'hs-save-dialog', title: 'Hyperspace for ' + save, eyebrow: WARNING_TITLE,
    text: ['A save is locked to its mods. Add Hyperspace.ftl to this save\'s mods under Advanced, where you confirm the change, or start a new save with Hyperspace and these mods.'],
    actions: [{label: 'Cancel', value: null, kind: 'ghost', id: 'hs-save-cancel'}, {label: 'New save with Hyperspace', value: 'new', id: 'hs-save-new'}, {label: 'Change this save\'s mods', value: 'advanced', kind: 'primary', id: 'hs-save-advanced'}]});
}

/**
 * Hyperspace.ftl in this browser, asking before anything downloads: its size and source come from our server's catalogue
 * (Hyperspace may be hosted; Multiverse never is). tools: {catalogue(fresh), have(sha256), download(entry) -> true/false,
 * error(entry) -> message, signIn(), openMods()}. Resolves the catalogue entry once the file is here, or null.
 */
export async function getHyperspace(tools) {
  let asked = false;
  for (let fresh = false; ; fresh = true) {
    let cat = null;
    try { cat = await tools.catalogue(fresh); } catch { cat = null; }
    const entry = hyperspaceEntry(cat);
    if (entry && await tools.have(entry.sha256)) return entry;
    const page = link(entry?.page ?? HYPERSPACE_RELEASES, 'official Hyperspace release page');
    const elsewhere = ['Or get Hyperspace.ftl from its ', page, ' and add it (or the release zip) under Mods.'];
    const cancel = {label: 'Cancel', value: null, kind: 'ghost', id: 'hs-get-cancel'};
    if (!entry) {
      const again = await choose({id: 'hs-get-dialog', title: 'Hyperspace is not in this browser', text: ['The mod catalogue cannot be reached right now (offline?).', el('p', {}, ...elsewhere)],
        actions: [cancel, {label: 'Try again', value: true, kind: 'primary', id: 'hs-get-retry'}]});
      if (!again) return null; continue;
    }
    const what = `${entry.title} ${entry.version} (${entry.file}, ${mb(entry.size)})`, reason = entry.server?.reason;
    if (reason === 'sign-in') {
      const go = await choose({id: 'hs-get-dialog', title: 'Sign in to download Hyperspace', text: [`${what} is not in this browser. Signed in, you can download it from this server.`, el('p', {}, ...elsewhere)],
        actions: [cancel, {label: 'Sign in', value: true, kind: 'primary', id: 'hs-get-signin'}]});
      if (go) tools.signIn(); return null;
    }
    if (reason !== 'ready') {
      const checking = reason === 'verifying';
      const again = await choose({id: 'hs-get-dialog', title: checking ? 'Hyperspace is not ready on this server' : 'This server does not offer Hyperspace',
        text: [checking ? `This server is still checking its copy of ${what}. Try again in a moment.` : `${what} is not in this browser, and this server does not offer it.`, el('p', {}, ...elsewhere)],
        actions: [cancel, checking ? {label: 'Try again', value: 'retry', kind: 'primary', id: 'hs-get-retry'} : {label: 'Open Mods', value: 'mods', kind: 'primary', id: 'hs-get-mods'}]});
      if (again === 'retry') continue;
      if (again === 'mods') tools.openMods();
      return null;
    }
    const yes = asked || await choose({id: 'hs-get-dialog', title: 'Download Hyperspace?',
      text: [`${what} is not in this browser. Download it from this server? It is the file our mod catalogue pins, from Hyperspace's own release (${entry.license ?? 'its licence'}).`, el('p', {className: 'muted text-sm'}, 'Source: ', link(entry.page ?? HYPERSPACE_RELEASES, entry.page ?? HYPERSPACE_RELEASES))],
      actions: [cancel, {label: 'Download ' + mb(entry.size), value: true, kind: 'primary', id: 'hs-get-download'}]});
    if (!yes) return null;
    asked = true;
    const progress = el('p', {className: 'muted text-sm', id: 'hs-get-progress', role: 'status'}, 'Downloading ' + what + '…');
    const busy = choose({id: 'hs-get-busy', title: 'Downloading Hyperspace', text: [progress], actions: [], required: true});
    const ok = await tools.download(entry).catch(() => false);
    document.getElementById('hs-get-busy')?.close(); document.getElementById('hs-get-busy')?.remove(); void busy;
    if (ok && await tools.have(entry.sha256)) return entry;
    const again = await choose({id: 'hs-get-dialog', title: 'Hyperspace did not download', text: [tools.error(entry) ?? 'The download failed.', el('p', {}, ...elsewhere)],
      actions: [cancel, {label: 'Try again', value: true, kind: 'primary', id: 'hs-get-retry'}]});
    if (!again) return null;
  }
}
