// The shared save rules (docs/SAVES.md): savecore.js is the Kotlin of savecore/ built by TeaVM, the same code the
// Android app runs, so the page and the app decide sync and locks alike. savecore.js speaks JSON text; this
// module turns it into plain objects. In the page it loads as a classic script (CSP: script-src 'self'); Node tests
// pass the functions of their own copy to wrapSaveCore.
let loading = null;

/** Loads /savecore/savecore.js once and resolves the wrapped functions. */
export function loadSaveCore(version = '') {
  return loading ??= new Promise((resolve, reject) => {
    // The UMD wrapper of savecore.js writes its functions into a global `exports` when there is one, which keeps them
    // off window; the engine (loaded later) has its own globals.
    const target = {}, previous = Object.getOwnPropertyDescriptor(globalThis, 'exports');
    globalThis.exports = target;
    const restore = () => { if (previous) Object.defineProperty(globalThis, 'exports', previous); else delete globalThis.exports; };
    const s = document.createElement('script');
    s.src = '/savecore/savecore.js' + version;
    s.onload = () => { s.remove(); restore(); if (typeof target.savecoreDecide !== 'function') reject(Error('The save rules did not load. Reload the page.')); else resolve(wrapSaveCore(target)); };
    s.onerror = () => { s.remove(); restore(); reject(Error('The save rules could not be downloaded. Reload the page.')); };
    document.head.append(s);
  }).catch(e => { loading = null; throw e; });
}

const text = value => value == null ? null : JSON.stringify(value);
const parse = value => value == null ? null : JSON.parse(value);
/** A LocalSave as savecore reads it: the run only when the rule needs it. */
const withoutData = save => save == null ? null : {...save, run: null, profile: null};

/** The savecore functions over plain objects (LocalSave, CloudSave, LockInfo as docs/SAVES.md names them). */
export function wrapSaveCore(raw) {
  return {
    /** {status, action, rename} for a device copy, the cloud's metadata and whether the cloud has a tombstone. */
    decide: (local, cloud, deleted = false) => {
      const d = parse(raw.savecoreDecide(text(withoutData(local)), text(cloud), !!deleted));
      return {status: d.status, action: d.action, rename: !!d.rename};
    },
    written: (local, run, meta) => parse(raw.savecoreWritten(text(local), run ?? null, text(meta ?? {}))),
    /** The save after the engine wrote its profile, or null when it did not change. */
    profileWritten: (local, xml) => { const next = raw.savecoreProfileWritten(text(local), xml); return next == null ? null : parse(next); },
    uploaded: (local, uploadedRun, uploadedProfile, revision) => parse(raw.savecoreUploaded(text(local), uploadedRun ?? null, uploadedProfile ?? null, revision)),
    fetched: (local, cloud, run, profile) => parse(raw.savecoreFetched(text(local), text(cloud), run ?? null, profile ?? null)),
    keepMine: (local, cloud) => parse(raw.savecoreKeepMine(text(local), text(cloud))),
    /** The run of a save moved to other mods (Saves > Advanced). */
    runForSetup: (run, setupKey) => raw.savecoreRunForSetup(run, setupKey),
    overridden: (local, lock, at) => parse(raw.savecoreOverridden(text(local), text(lock), at)),
    /** 'FREE', 'MINE' or 'OTHER'. */
    lockState: (lock, instance) => raw.savecoreLockState(text(lock), instance),
    /** One-time conversion of an old profile: {profile: without the settings, settings: their document or null}. */
    splitOldProfile: xml => parse(raw.savecoreSplitOldProfile(xml)),
    modSetHash: shas => raw.savecoreModSetHash(JSON.stringify(shas)),
    /** A local mod file as a save names it: {name, version, sha256, size, verified}. */
    classify: (catalogue, fileName, sha256, size) => parse(raw.savecoreClassify(typeof catalogue === 'string' ? catalogue : JSON.stringify(catalogue ?? {files: []}), fileName, sha256, size)),
    summary: (run, playSeconds = 0) => parse(raw.savecoreSummary(run ?? null, playSeconds)),
    cleanName: name => raw.savecoreCleanName(name ?? '') ?? null,
    nextName: names => raw.savecoreNextName(JSON.stringify(names)),
    sha256: value => raw.savecoreSha256(value),
  };
}

/** The setup of an ordered mod list ({name, version, sha256, size, verified}); `vanilla` when there are none. */
export function setupOf(core, mods) {
  const list = (mods ?? []).map(m => ({name: m.name, version: m.version ?? '', sha256: m.sha256, size: m.size ?? 0, verified: !!m.verified}));
  return list.length ? {key: core.modSetHash(list.map(m => m.sha256)), mods: list} : {key: 'vanilla', mods: []};
}
export const SAVES_LIMIT = 30, SNAPSHOTS_LIMIT = 10, ACCOUNT_BYTES = 256 * 1024 * 1024;
