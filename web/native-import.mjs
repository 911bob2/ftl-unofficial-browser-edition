// Saves > Import from FTL (docs/SAVES.md, "Saves from the original game"): the profile and run of the original game, or of
// Hyperspace and Multiverse, become one new save. The files are read and checked in this browser by savecore
// (NativeSave); the engine converts them the first time the save is played, with the save's game files and mods. Nothing
// is sent to the server except the new save, which syncs like any other.
import {el} from './dialogs.mjs';
import {SAVES_LIMIT} from './savecore.mjs';

const MAX_FILE = 4 * 1024 * 1024;

/** Where the original game keeps its saves, per system (the guide in the dialog). */
export const FOLDERS = [
  ['Windows', 'Documents\\My Games\\FasterThanLight', 'In the file window, paste %USERPROFILE%\\Documents\\My Games\\FasterThanLight into the address bar.'],
  ['macOS', '~/Library/Application Support/FasterThanLight', 'In the file window, press Cmd+Shift+G and paste the folder.'],
  ['Linux', '~/.local/share/FasterThanLight', 'The folder is hidden: press Ctrl+H in the file window. With Proton or Wine it is the Windows folder inside the prefix.'],
];

async function base64(file) {
  const bytes = new Uint8Array(await file.arrayBuffer());
  let text = '';
  for (let i = 0; i < bytes.length; i += 0x8000) text += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(text);
}

/** The setups the files can go on with: [{value, label, setup}], the first is the suggestion. */
async function setupChoices(ctx, picked) {
  if (picked.setup === 'vanilla') return [{value: 'vanilla', label: 'No mods', setup: {key: 'vanilla', mods: []}}];
  const presets = await ctx.presets(true), out = [];
  const add = (value, label, setup) => { if (setup && !out.some(o => o.setup.key === setup.key)) out.push({value, label, setup}); };
  const enabled = await ctx.enabled().catch(() => null);
  const preset = id => presets.find(p => p.id === id);
  if (picked.setup === 'multiverse') add('multiverse', preset('multiverse')?.title, preset('multiverse')?.setup);
  if (picked.setup === 'hyperspace') add('hyperspace', preset('hyperspace')?.title, preset('hyperspace')?.setup);
  if (enabled?.hyperspace) add('enabled', 'The enabled mods (' + await ctx.label(enabled) + ')', enabled);
  for (const p of presets) if (p.hyperspace) add(p.id, p.title, p.setup);
  return out;
}

/** The dialog; resolves the new save or null. */
export function importFromGame(ctx) {
  const {service} = ctx;
  return new Promise(resolve => {
    document.getElementById('native-import-dialog')?.remove();
    const dialog = el('dialog', {className: 'user-dialog save-dialog', id: 'native-import-dialog', ariaLabelledby: 'native-import-title'});
    document.body.append(dialog);
    let finished = false, picked = null, choices = [];
    const done = value => { if (finished) return; finished = true; if (dialog.open) dialog.close(); dialog.remove(); resolve(value); };
    const input = el('input', {type: 'file', id: 'native-import-files', multiple: true, accept: '.sav'});
    const choose = el('label', {className: 'btn file-button'}, 'Choose files', input);
    const found = el('div', {id: 'native-import-found', className: 'native-found', role: 'status'});
    const name = el('input', {className: 'input w-full', id: 'native-import-name', maxLength: 40, autocomplete: 'off'});
    const mods = el('select', {className: 'select select-sm', id: 'native-import-mods', ariaLabel: 'Mods'});
    const details = el('div', {id: 'native-import-details', hidden: true}, el('label', {className: 'field'}, 'Name', name), el('label', {className: 'field'}, 'Mods', mods));
    const note = el('p', {className: 'text-sm text-warning', id: 'native-import-note', role: 'status'});
    const create = el('button', {className: 'btn btn-primary', type: 'button', id: 'native-import-create', disabled: true}, 'Import');
    const cancel = el('button', {className: 'btn btn-ghost', type: 'button', id: 'native-import-cancel'}, 'Cancel');
    cancel.onclick = () => done(null);
    dialog.addEventListener('cancel', e => { e.preventDefault(); done(null); });
    const blocked = ctx.blocked();
    service.rows().then(rows => { if (rows.length >= SAVES_LIMIT && !finished) { note.textContent = `An account has at most ${SAVES_LIMIT} saves. Delete one first.`; } }).catch(() => {});
    if (blocked) { note.textContent = blocked; choose.classList.add('btn-disabled'); input.disabled = true; }

    input.addEventListener('change', async () => {
      const files = [...input.files];
      input.value = '';
      picked = null; create.disabled = true; details.hidden = true; note.textContent = ''; found.replaceChildren();
      if (!files.length) return;
      try {
        if (files.length > 2) throw Error('Choose at most two files: the profile and the run.');
        for (const f of files) if (f.size > MAX_FILE) throw Error(f.name + ' is too large to be a save of FTL.');
        const result = service.core.nativePick(await Promise.all(files.map(async f => ({name: f.name, base64: await base64(f)}))));
        if (result.error) throw Error(result.error);
        picked = result;
        choices = await setupChoices(ctx, picked);
        if (!choices.length) throw Error('These files come from ' + picked.from + '. Add Hyperspace in Mods first, then import them.');
        const s = picked.run?.summary;
        found.replaceChildren(...[el('p', {}, 'From ' + picked.from + ':'), el('ul', {className: 'plain-list'},
          picked.profile ? el('li', {dataset: {kind: 'profile'}}, 'Profile (' + picked.profile.file + '): unlocked ships, achievements and scores') : null,
          picked.run ? el('li', {dataset: {kind: 'run'}}, 'Run (' + picked.run.file + '): ' + [s?.ship, s?.sector ? 'sector ' + s.sector : null, s?.scrap != null ? s.scrap + ' scrap' : null, s?.hull != null ? 'hull ' + s.hull : null].filter(Boolean).join(' · ')) : null),
          !picked.profile ? el('p', {className: 'muted text-sm'}, 'Without the profile the save starts with the default unlocks.') : null,
          !picked.run ? el('p', {className: 'muted text-sm'}, 'Without a run the save starts in the hangar.') : null].filter(Boolean));
        mods.replaceChildren(...choices.map((c, i) => el('option', {value: String(i)}, c.label)));
        mods.disabled = choices.length < 2;
        const rows = await service.rows();
        name.value = service.core.cleanName(picked.run?.summary?.ship ? 'FTL: ' + picked.run.summary.ship : 'FTL profile') ?? service.core.nextName(rows.map(r => r.name));
        details.hidden = false; create.disabled = !!blocked;
        if (picked.setup === 'other') note.textContent = 'This save prefix is not one of the mod presets. Choose the mods it was played with.';
      } catch (e) { note.textContent = e.message; }
    });

    create.onclick = async () => {
      if (!picked) return;
      if (!service.core.cleanName(name.value)) { note.textContent = 'Use a name of 1 to 40 characters.'; return; }
      create.disabled = true; note.className = 'muted text-sm'; note.textContent = 'Importing…';
      try {
        const setup = choices[Number(mods.value) || 0].setup;
        const made = await service.create({key: setup.key, mods: setup.mods}, name.value, {run: picked.wrappedRun ?? null, profile: picked.wrappedProfile ?? null,
          meta: {importedUtc: new Date().toISOString(), importedFrom: picked.prefix || 'vanilla'}});
        done(made);
      } catch (e) { note.className = 'text-sm text-warning'; note.textContent = e.message; create.disabled = false; }
    };

    dialog.append(el('h2', {id: 'native-import-title'}, 'Import from FTL'),
      el('p', {className: 'muted text-sm'}, 'Bring your profile and your run from FTL on Steam, GOG or another store. The files are read in this browser and become a new save.'),
      el('div', {className: 'native-guide', id: 'native-import-guide'},
        el('p', {}, 'Choose one or both files:'),
        el('ul', {className: 'plain-list'},
          el('li', {}, el('strong', {}, 'ae_prof.sav'), ': your profile (unlocked ships, achievements, scores).'),
          el('li', {}, el('strong', {}, 'continue.sav'), ': the run you left with Save and Quit.'),
          el('li', {}, 'With Hyperspace: hs_prof.sav and hs_continue.sav. With Multiverse: hs_mv_prof.sav and hs_mv_continue.sav.')),
        el('p', {}, 'They are in this folder:'),
        el('ul', {className: 'plain-list'}, ...FOLDERS.map(([os, folder, tip]) => el('li', {dataset: {os}}, el('strong', {}, os + ': '), el('code', {}, folder), el('br'), el('span', {className: 'muted text-sm'}, tip)))),
        el('p', {className: 'muted text-sm'}, 'With Steam Cloud, start FTL once on that computer so Steam puts the latest files there. Quit FTL before you copy them.')),
      el('div', {className: 'actions'}, choose), found, details, note, el('div', {className: 'actions'}, create, cancel));
    dialog.showModal();
  });
}
