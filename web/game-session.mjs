// One running game (docs/SAVES.md, "The device first"): the save it plays, run and profile. Everything the engine
// writes goes to the device store at once, in order; the syncer uploads it a few seconds later. The network never
// pauses the game. While it runs, the lock heartbeat tells this tab when another game took the save over: then nothing
// it still writes is kept, and the page stops it.
import {anotherTab, playLockName} from './sync.mjs';
import {keepOldSettings, writeSettings} from './device-store.mjs';

const FLUSH = 'ftl-save-flush';

export function createGameSession({service, save, manifest, onStatus = () => {}, onOverridden = () => {}, heartbeatMs = 30000}) {
  const {core, store, sync, device} = service, id = save.id;
  let queue = Promise.resolve(), pending = 0, lastRun = save.run ?? null, lastProfile = save.profile ?? null;
  let frozen = false, closed = false, heartbeat = null, unqueued = null;
  // Play time counts while the page is visible.
  let playSeconds = save.meta?.summary?.playSeconds ?? 0, resumed = document.hidden ? null : performance.now();
  const seconds = () => Math.floor(playSeconds + (resumed == null ? 0 : (performance.now() - resumed) / 1000));
  const visibility = () => { if (document.hidden) { if (resumed != null) playSeconds += (performance.now() - resumed) / 1000; resumed = null; } else resumed ??= performance.now(); };
  document.addEventListener('visibilitychange', visibility);

  // The newest write of each kind that failed ({write, latest, error}); a later write of that kind replaces it, and
  // flush() tries it once more (the engine does not save an unchanged run or profile again).
  const failed = {run: null, profile: null};
  const failureNow = () => failed.run?.error ?? failed.profile?.error ?? null;
  const enqueue = (write, latest, kind) => {
    pending++; unqueued = {...unqueued, ...latest};
    queue = queue.then(write).then(() => { failed[kind] = null; }, error => {
      failed[kind] = {write, latest, error};
      onStatus('Not saved: ' + error.message, true); console.error('Save failed', error);
    }).finally(() => { if (--pending === 0) unqueued = null; });
    return queue;
  };

  /** The engine's save callback: kind "run" (xml null when the run ended), "profile", or "settings" (the browser's, not the save's). */
  function onSave(kind, xml) {
    if (frozen || closed) return;
    if (kind === 'settings') { if (xml != null) writeSettings(xml); return; }
    if (kind === 'run') {
      if (xml === lastRun) return;
      lastRun = xml ?? null;
      const at = new Date().toISOString(), meta = cur => ({...cur.meta, playedOn: {platform: 'browser', device: device.name, deviceId: device.id, at},
        engineBuild: manifest.engineBuild ?? null, gameData: manifest.assetVersion ?? null, summary: core.summary(xml ?? null, seconds())});
      enqueue(async () => {
        if (frozen) return;
        await store.update(id, cur => core.written(cur, xml ?? null, meta(cur)));
        onStatus('Saved on this device · ' + new Date().toLocaleTimeString());
        sync.written(id);
      }, {run: xml ?? null, at}, 'run');
    } else if (kind === 'profile' && xml != null) {
      if (xml === lastProfile) return;
      lastProfile = xml;
      enqueue(async () => {
        if (frozen) return;
        await store.update(id, cur => core.profileWritten(cur, xml));
        sync.written(id);
      }, {profile: xml, at: new Date().toISOString()}, 'profile');
    }
  }

  /** Waits until every save written so far is in the device store (a failed one is tried again); throws when one is not. */
  async function flush() {
    await queue;
    for (const kind of ['run', 'profile']) { const f = failed[kind]; if (f && !frozen) await enqueue(f.write, f.latest, kind); }
    const error = failureNow(); if (error) throw error;
  }

  // The game's Web Lock: another tab of this browser that plays this save takes it (after the player chose Override
  // there), and this tab stops at once.
  let releaseTab = null;
  navigator.locks?.request(playLockName(service.account, id), {steal: true}, () => new Promise(resolve => { releaseTab = resolve; }))
    .catch(e => { if (e?.name === 'AbortError' && !closed) stop(anotherTab(device.id), null); });

  /** Another game took the save (lock: who, at: the server's time of the override, when known). */
  function stop(lock, at = null) {
    if (frozen) return;
    frozen = true; clearInterval(heartbeat); releaseTab?.();
    onOverridden(lock ?? null, at);
  }
  const unlisten = sync.onOverridden((which, lock, at) => { if (which === id && !closed) stop(lock, at); });
  if (heartbeatMs > 0) heartbeat = setInterval(async () => {
    if (frozen || closed) return;
    try {
      const r = await sync.heartbeat(id);
      // held: this game started offline and another holds the save now; it plays on and the list asks afterwards.
      if (r.result === 'overridden') stop(r.lock, r.at);
    } catch { /* the next beat tries again */ }
  }, heartbeatMs);

  // A tab closed while a write is still queued keeps it in localStorage (the only storage a closing page can write at
  // once); the next start of the page puts it into the device store.
  function keepUnwritten() {
    if (!unqueued || frozen || closed) return;
    try { const text = JSON.stringify({account: service.account, id, ...unqueued, meta: {engineBuild: manifest.engineBuild ?? null, gameData: manifest.assetVersion ?? null}, playSeconds: seconds()}); if (text.length < 8 * 1024 * 1024) localStorage.setItem(FLUSH, text); } catch { /* storage full */ }
  }
  addEventListener('pagehide', keepUnwritten);

  sync.setPlaying(id);
  return {
    id, run: save.run ?? null, profileXml: save.profile ?? null, onSave, flush,
    get frozen() { return frozen; }, get pending() { return pending > 0; }, get failure() { return failureNow(); },
    /** Ends the session (Save and close, or the page stops the game): nothing written afterwards is kept. */
    close() { closed = true; clearInterval(heartbeat); releaseTab?.(); unlisten(); removeEventListener('pagehide', keepUnwritten); document.removeEventListener('visibilitychange', visibility); },
    stop,
  };
}

/** Puts a save that a closing tab left in localStorage into the device store (when it is newer than the stored one). */
export async function applyUnwritten(service) {
  let kept; try { kept = JSON.parse(localStorage.getItem(FLUSH) ?? 'null'); } catch { kept = null; }
  if (!kept || kept.account !== service.account) return false;
  const {core, store, device} = service;
  if ('run' in kept) {
    await store.update(kept.id, cur => (cur.meta?.playedOn?.at ?? '') >= kept.at ? null : core.written(cur, kept.run ?? null, {...cur.meta,
      playedOn: {platform: 'browser', device: device.name, deviceId: device.id, at: kept.at}, engineBuild: kept.meta?.engineBuild ?? null, gameData: kept.meta?.gameData ?? null,
      summary: core.summary(kept.run ?? null, kept.playSeconds ?? 0)}));
  }
  if (typeof kept.profile === 'string') {
    // Left by a tab of the version before the settings left the profile.
    const split = core.splitOldProfile(kept.profile);
    if (split.settings) keepOldSettings([split.settings]);
    await store.update(kept.id, cur => core.profileWritten(cur, split.profile));
  }
  try { localStorage.removeItem(FLUSH); } catch { /* ignore */ }
  return true;
}
