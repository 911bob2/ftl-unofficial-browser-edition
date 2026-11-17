// Real input for tests that drive the game through the page (keys and clicks on #game), with waits for their effect.
//
// web/game.mjs queues every key and pointer event and hands the queue to the engine at the start of its next animation
// frame (each event, then a zero step); Hyperspace's Lua ticks and the game's own time move only in that frame's real
// step. A state read right after a press (page.evaluate) can therefore still see the state from before the press, and a
// fixed pause only makes that unlikely: when the page is slow, a pause ends before the frame came. These helpers wait
// for frames and for stepped game time instead, so a slow page takes longer but does the same thing.

/** Resolves after two animation frames: the game's frame has handled every input the page had queued before the call. */
export const flush = page => page.evaluate(() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r))));

/**
 * Add to the context before the page loads: `window.__ftlSteppedMs`, the time the page has stepped the game by, as
 * web/game.mjs computes its step (each animation frame's interval, at most 50 ms; nothing while the page is hidden).
 * At full speed it runs with the wall clock; on a slow page it runs slower, as the game does.
 */
export const steppedClock = context => context.addInitScript(() => {
  let last = null; window.__ftlSteppedMs = 0;
  const frame = now => { if (last !== null && !document.hidden) window.__ftlSteppedMs += Math.min(Math.max(0, now - last), 50); last = now; requestAnimationFrame(frame); };
  requestAnimationFrame(frame);
});

/**
 * A pause of [seconds] of stepped game time (needs steppedClock): the scenario's "wait N s" (the oracle session waited N
 * seconds while the original ran at full speed), the same amount of game on a slow page.
 */
export const gameWait = async (page, seconds) => {
  const from = await page.evaluate(() => window.__ftlSteppedMs);
  if (typeof from !== 'number') throw Error('gameWait needs steppedClock(context) before the page loads');
  await page.waitForFunction(([from, ms]) => window.__ftlSteppedMs - from >= ms, [from, seconds * 1000], {timeout: Math.max(120000, seconds * 20000), polling: 50});
};

/**
 * Opens a <details> panel the way a player does, by a click on its summary, and clicks again while it is still
 * closed. The launcher's Play page still lays out after sessionReady (its save line comes in and moves the panels below
 * down by a line), so a click aimed just before that lands above the summary and opens nothing.
 */
export const openDetails = async (page, selector) => {
  for (let i = 0; !await page.locator(selector).evaluate(d => d.open); i++) {
    if (i === 5) throw Error(selector + ' does not open on a click on its summary');
    await page.locator(selector + ' > summary').click(); await flush(page);
  }
};
