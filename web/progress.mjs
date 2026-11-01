// Progress lines for long downloads and reads (game files, ftl.dat checks, mod files): bytes done of total, speed and
// time left. `progress` = {done, total, rate (bytes/s) | 0, eta (s) | null}; any field may be missing.
export const formatBytes = n => n == null ? '?' : (n / 1048576).toFixed(n >= 1048576 * 100 ? 0 : 1) + ' MB';
export function formatEta(s) {
  if (s == null || !Number.isFinite(s)) return '';
  if (s < 60) return Math.max(1, Math.round(s)) + ' s left';
  if (s < 3600) return 'about ' + Math.round(s / 60) + ' min left';
  return 'about ' + (s / 3600).toFixed(1) + ' h left';
}
export function formatProgress({done, total, rate, eta} = {}) {
  const parts = [total ? `${formatBytes(done ?? 0)} of ${formatBytes(total)}` : done != null ? formatBytes(done) : ''];
  if (rate > 0) parts.push(formatBytes(rate) + '/s');
  const left = formatEta(eta); if (left) parts.push(left);
  return parts.filter(Boolean).join(' · ');
}
/** Speed and time left from successive byte counts (smoothed over the last few seconds). */
export function createRate(total, done = 0, now = () => performance.now()) {
  const samples = [[now(), done]];
  return value => {
    const t = now(); samples.push([t, value]); while (samples.length > 2 && t - samples[0][0] > 6000) samples.shift();
    const [t0, d0] = samples[0], rate = t - t0 > 250 ? (value - d0) / ((t - t0) / 1000) : 0;
    return {done: value, total, rate, eta: rate > 0 && total ? (total - value) / rate : null};
  };
}
/** A <progress> with its text line; `element.update(progress)` changes it in place. */
export function progressBar(progress, {label = 'Progress', id} = {}) {
  const wrap = document.createElement('div'), bar = document.createElement('progress'), text = document.createElement('span');
  wrap.className = 'progress-line'; bar.className = 'progress-bar'; text.className = 'progress-text text-sm';
  bar.setAttribute('aria-label', label); if (id) wrap.dataset.progress = id;
  wrap.append(bar, text);
  wrap.update = p => {
    if (p?.total) { bar.max = p.total; bar.value = Math.min(p.total, p.done ?? 0); } else bar.removeAttribute('value');
    text.textContent = formatProgress(p ?? {});
  };
  wrap.update(progress);
  return wrap;
}
