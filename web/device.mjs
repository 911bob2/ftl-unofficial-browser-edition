// Who this browser and this tab are to the save locks (docs/SAVES.md, "Locks"). The device (deviceId and a name such
// as "Firefox on Windows") lives in localStorage and is shared by the tabs of this browser profile; the instance is one
// running game, one per tab, kept in sessionStorage so a reload keeps it. A duplicated tab copies sessionStorage, so a
// tab that finds another live tab with its instance takes a new one.
const DEVICE_KEY = 'ftl-device', INSTANCE_KEY = 'ftl-instance', ID = /^[A-Za-z0-9-]{1,64}$/;
const newId = () => (globalThis.crypto?.randomUUID?.() ?? Array.from({length: 4}, () => Math.random().toString(16).slice(2, 10)).join('-'));

/** "Firefox on Windows", "Chrome on Android", "Safari on iPhone" ... from the user agent, or "Browser". */
export function browserName(ua = globalThis.navigator?.userAgent ?? '') {
  const browser = /Edg\//.test(ua) ? 'Edge' : /OPR\//.test(ua) ? 'Opera' : /Firefox\//.test(ua) ? 'Firefox' : /Chrome\/|Chromium\//.test(ua) ? 'Chrome' : /Safari\//.test(ua) ? 'Safari' : 'Browser';
  const os = /Android/.test(ua) ? 'Android' : /iPhone/.test(ua) ? 'iPhone' : /iPad/.test(ua) ? 'iPad' : /Windows/.test(ua) ? 'Windows' : /Mac OS X|Macintosh/.test(ua) ? 'macOS' : /CrOS/.test(ua) ? 'ChromeOS' : /Linux/.test(ua) ? 'Linux' : null;
  return os ? browser + ' on ' + os : browser;
}

/** {id, name} of this browser profile. */
export function device() {
  try {
    const stored = JSON.parse(localStorage.getItem(DEVICE_KEY) ?? 'null');
    if (stored && ID.test(stored.id) && typeof stored.name === 'string' && stored.name.trim()) return {id: stored.id, name: stored.name.trim().slice(0, 60)};
  } catch { /* a new identity below */ }
  const fresh = {id: newId(), name: browserName()};
  try { localStorage.setItem(DEVICE_KEY, JSON.stringify(fresh)); } catch { /* this page keeps it in memory */ }
  return fresh;
}

let instance = null;
/** This tab's instance id (stable across reloads of the tab). */
export function instanceId() {
  if (instance) return instance;
  try { const stored = sessionStorage.getItem(INSTANCE_KEY); if (stored && ID.test(stored)) return instance = stored; } catch { /* below */ }
  instance = newId();
  try { sessionStorage.setItem(INSTANCE_KEY, instance); } catch { /* memory only */ }
  return instance;
}

/**
 * Asks the other tabs whether one of them already runs with this tab's instance id (a duplicated tab); if so this tab
 * takes a new one. Resolves the instance id after a short wait. Every tab answers for the rest of its life.
 */
export async function claimInstance(wait = 250) {
  let channel; try { channel = new BroadcastChannel('ftl-instances'); } catch { return instanceId(); }
  const mine = instanceId(), born = performance.timeOrigin + performance.now();
  let taken = false;
  channel.onmessage = ({data}) => {
    if (data?.type === 'who' && data.id === instance && data.born > born) channel.postMessage({type: 'mine', id: instance, to: data.born});
    else if (data?.type === 'mine' && data.id === mine && data.to === born) taken = true;
  };
  channel.postMessage({type: 'who', id: mine, born});
  await new Promise(r => setTimeout(r, wait));
  if (taken && instance === mine) {
    instance = newId();
    try { sessionStorage.setItem(INSTANCE_KEY, instance); } catch { /* memory only */ }
  }
  return instance;
}

/** The headers every save call carries (docs/SAVES.md, "Wire details"). */
export function saveHeaders() {
  const d = device();
  return {'X-FTL-Instance': instanceId(), 'X-FTL-Device': 'browser;' + d.id + ';' + encodeURIComponent(d.name)};
}
