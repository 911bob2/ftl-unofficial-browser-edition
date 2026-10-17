// Only drawing is throttled. Input and simulation continue on browser callbacks.
export function createFramePacer() {
  let limit, deadline;
  return {
    reset() { limit = deadline = undefined; },
    shouldRender(now, requestedLimit) {
      if (limit !== requestedLimit || deadline === undefined) {
        limit = requestedLimit;
        deadline = now;
      }
      if (limit === 0) return true;
      const interval = 1000 / limit;
      // Allow sub-millisecond animation timestamp rounding at exact boundaries.
      if (now + .1 < deadline) return false;
      const elapsed = Math.max(0, now - deadline);
      deadline += (Math.floor(elapsed / interval) + 1) * interval;
      return true;
    }
  };
}
