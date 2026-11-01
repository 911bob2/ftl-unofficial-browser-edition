// Mod session (PLAN I1): identity of an ordered mod set and the `window.ftlModSession` object the engine reads.
const encoder = new TextEncoder(), decoder = new TextDecoder('utf-8');

export async function sha256Hex(bytes) {
  return [...new Uint8Array(await crypto.subtle.digest('SHA-256', bytes))].map(b => b.toString(16).padStart(2, '0')).join('');
}

/** modSetHash = sha256 of the UTF-8 JSON {"loader":1,"mods":[<archive sha256 in order>]}. */
export function modSetHash(shas) { return sha256Hex(encoder.encode(JSON.stringify({loader: 1, mods: shas}))); }

/** Title and version from mod-appendix/metadata.xml (CDATA or text, trimmed), as ftlman shows them. */
export function parseMetadata(bytes) {
  if (!bytes) return {title: null, version: null};
  const text = decoder.decode(bytes).replace(/<!--[\s\S]*?-->/g, '');
  const field = tag => {
    const m = new RegExp('<' + tag + '(?:\\s[^>]*)?>\\s*(?:<!\\[CDATA\\[([\\s\\S]*?)\\]\\]>|([^<]*))\\s*</' + tag + '>').exec(text);
    const value = m ? (m[1] ?? m[2] ?? '').trim() : '';
    return value || null;
  };
  return {title: field('title'), version: field('version')};
}

/** Lua scripts HS runs, in order: every <script> child of every top-level <scripts> in data/hyperspace.xml. */
export function hyperspaceScripts(xmlBytes) {
  if (!xmlBytes) return [];
  const text = decoder.decode(xmlBytes).replace(/<!--[\s\S]*?-->/g, ''), out = [];
  for (const block of text.matchAll(/<scripts(?:\s[^>]*)?>([\s\S]*?)<\/scripts>/g))
    for (const s of block[1].matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/g)) out.push(s[1].trim());
  return out;
}

/**
 * mods: [{name, version, sha256, size, title}] in load order; files: the merged file map (name -> bytes).
 * `hyperspace` = merged files contain data/hyperspace.xml; hsVersion from the archive titled "Hyperspace".
 */
export async function buildSession(mods, files) {
  const hyperspace = files.has('data/hyperspace.xml');
  const hs = mods.find(m => /^hyperspace$/i.test(m.title ?? ''));
  return {
    hash: await modSetHash(mods.map(m => m.sha256)), loader: 1,
    mods: mods.map(m => ({name: m.name, version: m.version ?? null, sha256: m.sha256, size: m.size})),
    hyperspace, hsVersion: hyperspace ? hs?.version ?? null : null,
    scripts: hyperspace ? hyperspaceScripts(files.get('data/hyperspace.xml')) : [],
  };
}
