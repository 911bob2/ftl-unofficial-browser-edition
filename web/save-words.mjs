// The words of the save questions (docs/SAVES.md), the same on the website and in the Android launcher
// (android/app/.../SaveWords.kt; SaveWordsTest keeps them equal). {name}, {device}, {time} and {reason} are filled in by
// `words`. The website's own cases (another tab of this browser) stay in web/saves-ui.mjs.
export const SAVE_WORDS = {
  lockTitle: 'In use on {device}',
  lockLine: 'In use on {device} · last seen {time}',
  overrideWarning: '{device} may have progress and unlocks that have not synced yet. Overriding discards them; nothing is kept.',
  override: 'Override lock',
  conflictTitle: '{name} changed in two places',
  conflictText: "The account has a newer version than the one this device's unsynced progress started from.",
  conflictChoices: "Use the cloud copy: the progress and unlocks on this device that have not synced are discarded. Keep this device's copy: it replaces the version in your account, unlocks too (that version stays a recovery snapshot).",
  useCloud: 'Use the cloud copy',
  keepMine: "Keep this device's copy",
  overriddenTitle: 'Save taken over',
  overridden: 'This save was taken over on {device}. Progress and unlocks since your last sync were discarded.',
  overriddenAt: 'This save was taken over on {device} at {time}. Progress and unlocks since your last sync were discarded.',
  deletedTitle: '{name} was deleted on another device',
  deletedText: 'This device has progress of it that never synced.',
  deleteHereToo: 'Delete here too',
  uploadAgain: 'Upload again',
  deletedRemoved: '{name} was deleted on another device.',
  deleteTitle: 'Delete {name}?',
  deleteCloud: 'The save is deleted on this device and in your account. Its recovery snapshots are deleted with it.',
  deleteLocal: 'The save is deleted from this device.',
  deleteLockedWarning: 'That game may have progress and unlocks that have not synced yet. Deleting discards them.',
  deleteAnyway: 'Delete anyway',
  deleteOffline: 'Connect to delete this save from your account.',
  snapshotsText: 'The account keeps the last 10 versions of every save. Restoring makes a snapshot the newest version; the newer ones stay.',
  restoreTitle: 'Restore the save from {time}?',
  restoreText: 'The save becomes this snapshot as its newest version; the newer snapshots stay, so this can be undone.',
  restoreUnsynced: 'Progress on this device that has not synced yet is discarded.',
  restoreHeld: 'This save is in use on {device}.',
  restored: 'Restored {name} from {time}.',
  notSynced: 'Not synced: {reason}',
  keepOffline: "Connect to keep this device's copy of {name}: the account has a newer version.",
  savesFull: 'An account has at most {count} saves. Delete one first.',
};

/** One of SAVE_WORDS with its {fields} filled in. */
export const words = (key, fields = {}) => SAVE_WORDS[key].replace(/\{(\w+)\}/g, (m, f) => f in fields ? String(fields[f]) : m);
