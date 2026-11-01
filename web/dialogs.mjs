// The page's own dialogs (in place of the browser's prompt/confirm/alert): modal <dialog> elements in the site's frame
// (styles.css .user-dialog). Each resolves the value of the button pressed; Escape is the cancel value unless the dialog
// must be answered.
export function el(tag, props = {}, ...children) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(props)) {
    if (v === undefined || v === null || v === false && k !== 'checked' && k !== 'disabled') continue;
    if (k === 'dataset') Object.assign(e.dataset, v);
    else if (k === 'role' || k === 'for' || k.startsWith('aria')) e.setAttribute(k === 'role' || k === 'for' ? k : 'aria-' + k.slice(4).toLowerCase(), v);
    else e[k] = v;
  }
  for (const c of children.flat()) if (c != null && c !== false) e.append(c);
  return e;
}
const KINDS = {primary: 'btn btn-primary', danger: 'btn btn-error', ghost: 'btn btn-ghost'};
const paragraphs = text => (Array.isArray(text) ? text : [text]).filter(Boolean).map(t => typeof t === 'string' ? el('p', {}, t) : t);

/**
 * A modal dialog. actions: [{label, value, kind ('primary' | 'danger' | 'ghost'), id}]. Resolves the pressed action's
 * value; Escape resolves `cancel` (or is ignored when `required`).
 */
export function choose({id, title, eyebrow, text, warning, content = [], actions, cancel = null, required = false, className = 'confirm-dialog'}) {
  return new Promise(resolve => {
    document.getElementById(id)?.remove();
    const dialog = el('dialog', {className: 'user-dialog ' + className, id, ariaLabelledby: id + '-title'});
    let finished = false;
    const done = value => { if (finished) return; finished = true; if (dialog.open) dialog.close(); dialog.remove(); resolve(value); };
    const buttons = actions.map(a => { const b = el('button', {className: KINDS[a.kind] ?? 'btn', type: 'button', id: a.id, dataset: {value: String(a.value)}}, a.label); b.onclick = () => done(a.value); return b; });
    dialog.addEventListener('cancel', e => { e.preventDefault(); if (!required) done(cancel); });
    dialog.append(...[
      el('div', {className: 'section-heading'}, el('div', {}, eyebrow ? el('span', {className: 'eyebrow'}, eyebrow) : null, el('h2', {id: id + '-title'}, title))),
      ...paragraphs(text), ...content,
      warning ? el('p', {className: 'text-sm text-warning dialog-warning', id: id + '-warning'}, warning) : null,
      el('div', {className: 'actions'}, ...buttons)].filter(Boolean));
    document.body.append(dialog); dialog.showModal();
    (buttons.find((b, i) => actions[i].kind === 'primary') ?? buttons[0])?.focus();
  });
}

/** A short message with OK. */
export function notice({id, title, text, okLabel = 'OK', required = false}) {
  return choose({id, title, text, actions: [{label: okLabel, value: true, kind: 'primary', id: id + '-ok'}], cancel: true, required});
}

/** A yes/no question; resolves true for the confirming button. */
export function confirmAction({id, title, text, warning, yes, no = 'Cancel', danger = false}) {
  return choose({id, title, text, warning, cancel: false, actions: [{label: no, value: false, kind: 'ghost', id: id + '-no'}, {label: yes, value: true, kind: danger ? 'danger' : 'primary', id: id + '-yes'}]});
}

/** One text field; resolves the trimmed text or null. `check(text)` returns an error message or null. */
export function askText({id, title, label, value = '', maxLength = 40, okLabel = 'OK', check = () => null}) {
  return new Promise(resolve => {
    document.getElementById(id)?.remove();
    const dialog = el('dialog', {className: 'user-dialog save-dialog', id, ariaLabelledby: id + '-title'});
    const input = el('input', {className: 'input w-full', id: id + '-input', maxLength, autocomplete: 'off', value});
    const note = el('p', {className: 'text-sm text-warning', id: id + '-note', role: 'status'});
    const ok = el('button', {className: 'btn btn-primary', type: 'button', id: id + '-ok'}, okLabel), cancel = el('button', {className: 'btn btn-ghost', type: 'button', id: id + '-cancel'}, 'Cancel');
    let finished = false;
    const done = v => { if (finished) return; finished = true; if (dialog.open) dialog.close(); dialog.remove(); resolve(v); };
    const validate = () => { const problem = check(input.value.trim()); note.textContent = problem ?? ''; ok.disabled = !!problem; return !problem; };
    input.addEventListener('input', validate);
    input.addEventListener('keydown', e => { if (e.key === 'Enter') { e.preventDefault(); if (validate()) done(input.value.trim()); } });
    ok.onclick = () => { if (validate()) done(input.value.trim()); };
    cancel.onclick = () => done(null);
    dialog.addEventListener('cancel', e => { e.preventDefault(); done(null); });
    dialog.append(el('h2', {id: id + '-title'}, title), el('label', {className: 'field'}, label, input), note, el('div', {className: 'actions'}, ok, cancel));
    document.body.append(dialog); dialog.showModal(); validate(); input.focus(); input.select();
  });
}
