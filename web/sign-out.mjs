// Sign out always asks first, wherever the header has the button (the play page, web/app.mjs, and the other pages,
// web/shell.mjs), with the Android launcher's words (android/app/.../SignOut.kt; SignOutTest keeps them the same):
// Cancel or Sign out, and a stronger line when a save of this account has progress the account does not have yet.
import {confirmAction} from './dialogs.mjs';
import {deviceStore} from './device-store.mjs';

export const SIGN_OUT = {
  title: 'Sign out?',
  text: "Sign in again to play this account's saves here.",
  unsynced: 'Your latest progress is saved on this device but not in your account yet. It syncs the next time you sign in here.',
  yes: 'Sign out',
  no: 'Cancel',
};

/** True when a save of `account` in this browser has progress or a new name the account does not have yet. */
export async function unsyncedSaves(account) {
  if (!account) return false;
  try { return (await deviceStore(account).list()).some(s => s.dirty || s.nameDirty); } catch { return false; }
}

/** Resolves true for Sign out. */
export function confirmSignOut(unsynced) {
  return confirmAction({id: 'logout-dialog', title: SIGN_OUT.title, text: SIGN_OUT.text, warning: unsynced ? SIGN_OUT.unsynced : null, yes: SIGN_OUT.yes, no: SIGN_OUT.no});
}
