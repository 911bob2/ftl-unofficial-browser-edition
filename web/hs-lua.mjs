// Hyperspace Lua runtime (Lua 5.3.6 + bridge in web/vendor/hs-lua.wasm). Loaded when the mod session is Hyperspace or
// lists Lua scripts (window.ftlModSession.hyperspace / .scripts); vanilla play never imports this module. The engine (TeaVM) drives it through
// window.ftlHsLua and answers the wasm imports through its exported hsLua* functions (BrowserEngine).
const HOST_NAMES = ['log', 'rand32', 'srand32', 'getn', 'gets', 'geto', 'setn', 'sets', 'seto', 'call', 'release', 'tableid', 'registered'];

/** Instantiates the runtime. `host` supplies the engine side: {log(kind, text), rand32(), srand32(seed), getn(h, a),
 *  gets(h, a) -> string|null, geto(h, a) -> packed, setn/sets/seto(...) -> null|error, call(mid, n) -> int,
 *  release(h), tableid(h), registered(kind, id)}. Returns the object the engine uses as window.ftlHsLua. */
export async function createHsLua(wasm, host, files = null) {
  let mem, u8;
  const enc = new TextEncoder(), dec = new TextDecoder();
  const view = () => { if (u8.buffer !== mem.buffer) u8 = new Uint8Array(mem.buffer); return u8; };
  let e = null;
  // string into the shared buffer; returns its byte length
  const put = (s, offset = 0) => {
    for (;;) {
      const ptr = e.hs_sbuf(), cap = e.hs_sbuf_cap();
      const r = enc.encodeInto(s, view().subarray(ptr + offset, ptr + cap));
      if (r.read === s.length) return r.written;
      e.hs_sbuf_grow(offset + s.length * 3 + 16);
    }
  };
  const get = (len, offset = 0) => { const ptr = e.hs_sbuf(); return dec.decode(view().subarray(ptr + offset, ptr + offset + len)); };
  let error = '';
  const errorResult = msg => put(String(msg)) + 1;
  // os.date's zone (msvcrt names it with %z and %Z): the standard and summer names, from the Intl long form
  const zoneNames = [null, null];
  const zoneName = isdst => {
    if (zoneNames[isdst] == null) {
      const y = new Date().getFullYear(), jan = new Date(y, 0, 1), jul = new Date(y, 6, 1);
      const std = jan.getTimezoneOffset() >= jul.getTimezoneOffset() ? jan : jul, dst = std === jan ? jul : jan;
      try {
        const name = d => new Intl.DateTimeFormat('en-US', {timeZoneName: 'long'}).formatToParts(d).find(p => p.type === 'timeZoneName')?.value ?? '';
        zoneNames[0] = name(std); zoneNames[1] = std.getTimezoneOffset() === dst.getTimezoneOffset() ? zoneNames[0] : name(dst);
      } catch { zoneNames[0] = zoneNames[1] = ''; }
    }
    return zoneNames[isdst];
  };
  const h = host;
  const env = {
    log: (kind, p, len) => h.log(kind, dec.decode(view().subarray(p, p + len))),
    now: () => performance.now(),
    epoch: () => Date.now(),
    tzoffset: t => -new Date(t * 1000).getTimezoneOffset(),
    tzname: (isdst, ptr, cap) => enc.encodeInto(zoneName(isdst ? 1 : 0), view().subarray(ptr, ptr + cap)).written,
    rand32: () => h.rand32(),
    srand32: seed => h.srand32(seed),
    getn: h.getn,
    gets: (o, a) => { const s = h.gets(o, a); return s == null ? -1 : put(s); },
    geto: h.geto,
    setn: (o, a, v) => { const r = h.setn(o, a, v); return r == null ? 0 : errorResult(r); },
    sets: (o, a, len) => { const r = h.sets(o, a, get(len)); return r == null ? 0 : errorResult(r); },
    seto: (o, a, o2, t2) => { const r = h.seto(o, a, o2, t2); return r == null ? 0 : errorResult(r); },
    call: (mid, n) => { const r = h.call(mid, n); return r >= 0 ? r : -put(error) - 1; },
    release: h.release,
    tableid: h.tableid,
    registered: h.registered,
    emscripten_notify_memory_growth: () => { u8 = new Uint8Array(mem.buffer); },
    __syscall_dup3: () => -52,
  };
  // No process environment (os.getenv is nil; loadlib.c reads LUA_PATH at start), no file descriptors (hslibs.c's files are its own).
  const wasi = {fd_read: () => 52, fd_write: () => 52, fd_close: () => 52, fd_seek: () => 52,
    environ_sizes_get: (count, size) => { const d = new DataView(mem.buffer); d.setUint32(count, 0, true); d.setUint32(size, 0, true); return 0; },
    environ_get: () => 0};
  const {instance} = wasm instanceof WebAssembly.Module
    ? {instance: await WebAssembly.instantiate(wasm, {env, wasi_snapshot_preview1: wasi})}
    : await WebAssembly.instantiate(wasm, {env, wasi_snapshot_preview1: wasi});
  e = instance.exports; mem = e.memory; u8 = new Uint8Array(mem.buffer);
  e._initialize();
  e.hs_init();
  let filesAccount = null, filesStore = null, syncing = Promise.resolve();
  /** The changes the scripts made to the private folder since the last call: [{kind: 1 write | 2 remove, name, data}]. */
  const takeChanges = () => {
    const n = e.hs_vfs_changes(), out = [];
    for (let i = 0; i < n; i++) {
      const p = e.hs_vfs_change(i);
      if (!p) break;
      const d = new DataView(mem.buffer, p, 20), kind = d.getInt32(0, true), name = d.getUint32(4, true), nameLen = d.getInt32(8, true);
      const data = d.getUint32(12, true), len = d.getInt32(16, true);
      out.push({kind, name: dec.decode(view().subarray(name, name + nameLen)), data: kind === 1 ? view().slice(data, data + len) : null});
    }
    if (n) e.hs_vfs_settle();
    return out;
  };
  return {
    e,
    /** Error text for the next negative return of host.call. */
    setError(msg) { error = String(msg); },
    /** A getter failed: the error is raised in Lua when the getter returns. */
    raise(msg) { e.hs_raise(put(String(msg))); },
    pushString(s) { e.hs_pushstring(put(String(s))); },
    toString(i) { const n = e.hs_tostring(i); return n < 0 ? null : get(n); },
    setField(t, key) { e.hs_setfield(t, put(String(key))); },
    /** Runs a chunk as runLuaFileFromDat; returns null or the error message. */
    run(name, code, console = false) {
      const n = put(name); const c = put(code, n);
      return e.hs_run(n, c, console ? 1 : 0) ? get(e.hs_errlen()) : null;
    },
    /** Runs a file of the merged game files (window.ftlFiles) as HS's runLuaFileFromDat; null or the error. */
    runFile(name) {
      const bytes = (files ?? globalThis.window?.ftlFiles)?.get(name);
      if (!bytes) { const msg = 'cannot open ' + name; h.log(2, "Failed to load lua code from file '" + name + "'. Error: '" + msg + "'"); return msg; }
      const n = put(name), ptr = e.hs_sbuf_grow(n + bytes.length + 1);
      view().set(bytes, ptr + n);
      return e.hs_run(n, bytes.length, 0) ? get(e.hs_errlen()) : null;
    },
    /** A fresh lua_State with no registrations (LuaScriptInit). */
    reinit() { e.hs_init(); },
    gameEvent(name, loading) { e.hs_game_event(put(name), loading ? 1 : 0); },
    profile(reset = false) { const n = e.hs_profile(reset ? 1 : 0); return n < 0 ? '{}' : get(n); },
    /** Loads the account's private folder (web/lua-files.mjs or `store`: {read(account), write(account, changes)}) into the runtime. */
    async mountFiles(account, store = null) {
      filesStore = store ?? await import('./lua-files.mjs' + new URL(import.meta.url).search).then(m => ({read: m.readLuaFiles, write: m.writeLuaFiles}));
      const list = await filesStore.read(account);
      e.hs_vfs_reset();
      for (const f of list) {
        const n = put(f.name), ptr = e.hs_sbuf_grow(n + f.data.byteLength + 1);
        view().set(f.data, ptr + n);
        e.hs_vfs_put(n, f.data.byteLength);
      }
      filesAccount = account;
      return list.length;
    },
    /** Writes what the scripts changed to the account's folder (the engine calls it every few seconds and at the end). */
    syncFiles() {
      if (filesAccount == null) return syncing;
      const changes = takeChanges();
      if (changes.length) syncing = syncing.then(() => filesStore.write(filesAccount, changes)).catch(err => h.log(3, 'Lua files could not be saved: ' + (err?.message ?? err)));
      return syncing;
    },
    filesUsed() { return e.hs_vfs_used(); },
    /** os.exit: {code, source} once a script called it, else null. */
    exitState() { return e.hs_exit_state() ? {code: e.hs_exit_code(), source: get(e.hs_exit_source())} : null; },
  };
}

/** Browser: fetch and start the runtime against the engine's exports (engine.js must be loaded). */
export async function loadHsLua(version = '') {
  if (window.ftlHsLua) return window.ftlHsLua;
  const url = new URL('./vendor/hs-lua.wasm' + version, import.meta.url);
  const res = await fetch(url);
  if (!res.ok) throw Error('Hyperspace Lua runtime could not be downloaded');
  const host = {};
  for (const n of HOST_NAMES) {
    const f = window['hsLua' + n[0].toUpperCase() + n.slice(1)];
    if (typeof f !== 'function') throw Error('Engine has no Hyperspace Lua bridge (' + n + ')');
    host[n] = f;
  }
  const rt = await createHsLua(await res.arrayBuffer(), host);
  window.ftlHsLua = rt;
  window.addEventListener('pagehide', () => { rt.syncFiles(); });
  return rt;
}
