// Hang detection for the functional test phase (FTL_HANG_S seconds, set by the runner).
//
// A functional check must give the same result at any machine speed. Its waits wait for an event; how long the event
// takes is no part of the check. With FTL_HANG_S set, this module (loaded with `node --import`, see
// tools/RunLauncherTests.mjs) gives every positive Playwright wait at least FTL_HANG_S seconds (default timeouts and
// explicit `timeout` options; `timeout: 0` stays "no limit") and turns a wait that still runs out into
// "HANG after <s> s at <test file:line>": a hang, never a failed check. waitForTimeout (a fixed pause) is not touched.
// Wall-clock deadlines written in the tests themselves use hangMs(). Without FTL_HANG_S nothing changes.
import {createRequire} from 'node:module';

const S = Number(process.env.FTL_HANG_S || 0);
const MS = S > 0 ? S * 1000 : 0;

/** A test's own wall-clock deadline: at least FTL_HANG_S seconds in the functional phase, else as written. */
export const hangMs = ms => MS ? Math.max(ms, MS) : ms;
/** The error a test's own deadline loop throws when it runs out. */
export const hangError = (step, ms) => new Error((MS ? 'HANG after ' + Math.round(hangMs(ms) / 1000) + ' s at ' : 'Timed out waiting for ') + step);

function site() {
  const lines = String(new Error().stack).split('\n').slice(2);
  const own = lines.find(l => /\/tests\//.test(l) && !/\/tests\/lib\/hang\.mjs/.test(l));
  return (own || lines[0] || '').trim().replace(/^at\s+/, '');
}

const SKIP = new Set(['constructor', 'evaluate', 'evaluateHandle', '$eval', '$$eval', 'waitForTimeout', 'exposeFunction', 'exposeBinding',
  'addInitScript', 'route', 'unroute', 'unrouteAll', 'routeFromHAR', 'on', 'once', 'off', 'addListener', 'removeListener', 'prependListener',
  'removeAllListeners', 'emit', 'setDefaultTimeout', 'setDefaultNavigationTimeout', 'locator', 'getByRole', 'getByText', 'getByLabel',
  'getByPlaceholder', 'getByAltText', 'getByTitle', 'getByTestId', 'frameLocator', 'first', 'last', 'nth', 'filter', 'and', 'or',
  'mainFrame', 'frames', 'context', 'url', 'isClosed', 'video', 'keyboard', 'mouse', 'touchscreen', 'request', 'clock']);

function longer(a) {
  return a && typeof a === 'object' && !Array.isArray(a) && typeof a.timeout === 'number' && a.timeout > 0 && a.timeout < MS ? {...a, timeout: MS} : a;
}

function patch(proto) {
  if (!proto || proto.__ftlHang) return;
  Object.defineProperty(proto, '__ftlHang', {value: true});
  for (const name of Object.getOwnPropertyNames(proto)) {
    if (SKIP.has(name) || name.startsWith('_')) continue;
    const d = Object.getOwnPropertyDescriptor(proto, name);
    if (!d || typeof d.value !== 'function') continue;
    const orig = d.value;
    proto[name] = function (...args) {
      const at = site();
      const r = orig.apply(this, name === 'waitForFunction' ? [args[0], args[1], longer(args[2]), ...args.slice(3)] : args.map(longer));
      if (!r || typeof r.then !== 'function') return r;
      return r.catch(e => {
        if (e && e.name === 'TimeoutError') { const h = new Error('HANG after ' + S + ' s at ' + at + ' (' + name + '): ' + String(e.message).split('\n')[0]); h.name = 'Hang'; throw h; }
        throw e;
      });
    };
  }
  for (const name of ['setDefaultTimeout', 'setDefaultNavigationTimeout']) {
    const orig = proto[name];
    if (typeof orig === 'function') proto[name] = function (v) { return orig.call(this, v > 0 && v < MS ? MS : v); };
  }
}

function patchContext(context) {
  patch(Object.getPrototypeOf(context));
  context.setDefaultTimeout(MS); context.setDefaultNavigationTimeout(MS);
  const watch = page => {
    patch(Object.getPrototypeOf(page)); patch(Object.getPrototypeOf(page.mainFrame())); patch(Object.getPrototypeOf(page.locator('html')));
    page.setDefaultTimeout(MS); page.setDefaultNavigationTimeout(MS);
  };
  context.pages().forEach(watch); context.on('page', watch);
  return context;
}

if (MS) {
  const pw = createRequire(import.meta.url)('playwright');
  for (const type of [pw.chromium, pw.firefox, pw.webkit]) {
    const launch = type.launch.bind(type), persistent = type.launchPersistentContext.bind(type);
    type.launch = async (...args) => {
      const browser = await launch(...args), newContext = browser.newContext.bind(browser), newPage = browser.newPage.bind(browser);
      browser.newContext = async (...p) => patchContext(await newContext(...p));
      browser.newPage = async (...p) => { const page = await newPage(...p); patchContext(page.context()); return page; };
      return browser;
    };
    type.launchPersistentContext = async (...args) => patchContext(await persistent(...args));
  }
  console.log('HANG detection: positive waits at least ' + S + ' s');
}
