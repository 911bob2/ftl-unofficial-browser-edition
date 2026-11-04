// Hyperspace Lua runtime (Lua 5.3.6 + bridge in web/vendor/hs-lua.wasm). Loaded only when the mod session lists Lua
// scripts (window.ftlModSession.scripts); vanilla play never imports this module. The engine (TeaVM) drives it through
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
  const h = host;
  const env = {
    log: (kind, p, len) => h.log(kind, dec.decode(view().subarray(p, p + len))),
    now: () => performance.now(),
    epoch: () => Date.now(),
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
  const wasi = {fd_read: () => 52, fd_write: () => 52, fd_close: () => 52, fd_seek: () => 52};
  const {instance} = wasm instanceof WebAssembly.Module
    ? {instance: await WebAssembly.instantiate(wasm, {env, wasi_snapshot_preview1: wasi})}
    : await WebAssembly.instantiate(wasm, {env, wasi_snapshot_preview1: wasi});
  e = instance.exports; mem = e.memory; u8 = new Uint8Array(mem.buffer);
  e._initialize();
  e.hs_init();
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
  return rt;
}
