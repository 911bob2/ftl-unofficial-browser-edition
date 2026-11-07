// Hyperspace's start-up error box (docs/HYPERSPACE.md, "Errors"): HS's ErrorMessage is SDL_ShowSimpleMessageBox with the
// title "Error" and one OK button. The original shows it before the main menu over a black window and waits: OK, Enter
// or Escape closes it and the start goes on. Measured from the original (.build/hs/reports/ps-m2b): a 414 px wide light
// box with the red error icon, the text and a full-width OK button; its height follows the text.
import {el} from './dialogs.mjs';

const ICON = 'data:image/svg+xml,' + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32">' +
  '<defs><radialGradient id="g" cx="40%" cy="30%" r="75%"><stop offset="0" stop-color="#ff7b70"/><stop offset=".55" stop-color="#e2231a"/>' +
  '<stop offset="1" stop-color="#a40d08"/></radialGradient></defs><circle cx="16" cy="16" r="14.5" fill="url(#g)" stroke="#8a0a05"/>' +
  '<path d="M10.5 10.5l11 11m0-11l-11 11" stroke="#fff" stroke-width="3.6" stroke-linecap="round"/></svg>');

/** Shows one box centred on the game canvas; resolves when it is dismissed. */
export function showHsErrorBox(canvas, text) {
  return new Promise(resolve => {
    const ok = el('button', {type: 'button', className: 'hs-error-ok', id: 'hs-error-box-ok'}, 'OK');
    const dialog = el('dialog', {className: 'hs-error-box', id: 'hs-error-box', ariaLabel: 'Error', role: 'alertdialog'},
      el('div', {className: 'hs-error-body'}, el('img', {src: ICON, alt: '', width: 32, height: 32}), el('p', {className: 'hs-error-text'}, text)), ok);
    let finished = false;
    const done = () => { if (finished) return; finished = true; if (dialog.open) dialog.close(); dialog.remove(); resolve(); };
    ok.onclick = done;
    // Escape is the box's cancel key; Enter presses the focused OK.
    dialog.addEventListener('cancel', e => { e.preventDefault(); done(); });
    const r = canvas.getBoundingClientRect();
    if (r.width > 0) { dialog.style.setProperty('--hs-box-x', Math.round(r.left + r.width / 2) + 'px'); dialog.style.setProperty('--hs-box-y', Math.round(r.top + r.height / 2) + 'px'); }
    (document.fullscreenElement ?? document.body).append(dialog);
    dialog.showModal();
    ok.focus();
  });
}
