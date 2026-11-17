// A performance gate: prints `PERF {"name","value","lo","hi","margin","ok"}` (the runner's performance phase reads these
// lines) and returns whether value lies in [lo, hi]. The caller asserts on the result, so the gate itself is unchanged.
// {open:true}: the bounds are exclusive (lo < value < hi), for gates written with > and <; the line then has "open":true.
export function perf(name, value, lo = -Infinity, hi = Infinity, unit = '', {open = false} = {}) {
  const ok = open ? value > lo && value < hi : value >= lo && value <= hi;
  const margin = Math.min(value - lo, hi - value);
  console.log('PERF ' + JSON.stringify({name, value, lo: Number.isFinite(lo) ? lo : null, hi: Number.isFinite(hi) ? hi : null, unit, margin: Number.isFinite(margin) ? margin : null, ok, ...(open ? {open: true} : {})}));
  return ok;
}

// gate(): the same row and condition as perf(), but a miss does not stop the script: it is kept, the script goes on to
// measure its other gates, and gatesChecked() (called once at the end) fails with every miss at once. So one slow
// measurement no longer hides the rest of a job's gates.
const missed = [];
export function gate(name, value, lo = -Infinity, hi = Infinity, unit = '', opts = {}, detail = '') {
  const ok = perf(name, value, lo, hi, unit, opts);
  if (!ok) missed.push(name + ': ' + value + ' ' + unit + ' outside ' + (opts.open ? '(' : '[') + lo + ', ' + hi + (opts.open ? ')' : ']') + (detail ? ' ' + detail : ''));
  return ok;
}
export const gateMisses = () => missed.length;
export function gatesChecked() {
  if (missed.length) throw new Error(missed.length + ' performance gate(s) missed: ' + missed.join('; '));
}
