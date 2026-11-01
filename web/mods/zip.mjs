// Minimal ZIP reader for mod archives (.ftl/.zip) that reads through random-access slices, so a 700 MB archive is
// never copied whole: `source` is {size, read(offset, length) -> Promise<Uint8Array>, readSync?(offset, length)}. Stored
// and deflated entries; ZIP64 sizes/offsets. Entry order is the central directory order (ftlman applies files in that
// order). readSync (Lua patch scripts read files synchronously) exists when the source has one: in a worker
// (FileReaderSync), for bytes and for Node files.
import {inflateSync} from '../vendor/fflate-0.8.3.mjs';

const utf8 = new TextDecoder('utf-8');
// CP437 high half, for names without the UTF-8 flag (as the zip crate decodes them).
const CP437 = 'ÇüéâäàåçêëèïîìÄÅÉæÆôöòûùÿÖÜ¢£¥₧ƒáíóúñÑªº¿⌐¬½¼¡«»░▒▓│┤╡╢╖╕╣║╗╝╜╛┐└┴┬├─┼╞╟╚╔╩╦╠═╬╧╨╤╥╙╘╒╓╫╪┘┌█▄▌▐▀αßΓπΣσµτΦΘΩδ∞φε∩≡±≥≤⌠⌡÷≈°∙·√ⁿ²■ ';
function cp437(bytes) { let s = ''; for (const b of bytes) s += b < 128 ? String.fromCharCode(b) : CP437[b - 128]; return s; }

export function blobSource(blob) {
  const source = {size: blob.size, read: async (offset, length) => new Uint8Array(await blob.slice(offset, offset + length).arrayBuffer())};
  if (typeof FileReaderSync === 'function') source.readSync = (offset, length) => new Uint8Array(new FileReaderSync().readAsArrayBuffer(blob.slice(offset, offset + length)));
  return source;
}
export function bytesSource(bytes) { return {size: bytes.byteLength, read: async (offset, length) => bytes.subarray(offset, offset + length), readSync: (offset, length) => bytes.subarray(offset, offset + length)}; }

/**
 * What an archive may unpack to, checked when a mod is added: a small archive of huge text files would run the merge
 * out of memory. Real mods stay far below this (Multiverse: 750 MiB in total, 20 MiB of text, at most 43:1).
 */
export const ZIP_LIMITS = {total: 4 * 1024 ** 3, text: 512 * 1024 ** 2, ratio: 300, ratioFrom: 1024 ** 2};
const isZipText = n => /\.(xml|append|txt|lua|rawclobber|rawappend)$/i.test(n);

/** Throws when the entries unpack to more than ZIP_LIMITS allows. */
export function checkZipLimits(entries) {
  let total = 0, text = 0;
  for (const e of entries) {
    total += e.size;
    if (isZipText(e.name)) text += e.size;
    if (e.size >= ZIP_LIMITS.ratioFrom && e.size / Math.max(e.compressed, 1) > ZIP_LIMITS.ratio) throw Error(`This archive is not a mod: ${e.name} unpacks to more than ${ZIP_LIMITS.ratio} times its stored size.`);
  }
  if (total > ZIP_LIMITS.total) throw Error('This archive unpacks to more than 4 GiB, too much for a mod.');
  if (text > ZIP_LIMITS.text) throw Error('This archive has more than 512 MiB of text files, too much for a mod.');
}

export async function openZip(source) {
  const tail = Math.min(source.size, 65557), end = await source.read(source.size - tail, tail);
  const dv = new DataView(end.buffer, end.byteOffset, end.byteLength);
  let eocd = -1;
  for (let i = end.length - 22; i >= 0; i--) if (dv.getUint32(i, true) === 0x06054b50) { eocd = i; break; }
  if (eocd < 0) throw Error('Not a ZIP archive');
  let count = dv.getUint16(eocd + 10, true), cdSize = dv.getUint32(eocd + 12, true), cdOffset = dv.getUint32(eocd + 16, true);
  if (count === 0xffff || cdSize === 0xffffffff || cdOffset === 0xffffffff) {
    const loc = eocd - 20;
    if (loc < 0 || dv.getUint32(loc, true) !== 0x07064b50) throw Error('Invalid ZIP64 archive');
    const z64 = Number(dv.getBigUint64(loc + 8, true)), rec = await source.read(z64, 56), rv = new DataView(rec.buffer, rec.byteOffset, 56);
    if (rv.getUint32(0, true) !== 0x06064b50) throw Error('Invalid ZIP64 directory');
    count = Number(rv.getBigUint64(32, true)); cdSize = Number(rv.getBigUint64(40, true)); cdOffset = Number(rv.getBigUint64(48, true));
  }
  if (cdOffset + cdSize > source.size) throw Error('Truncated ZIP archive');
  const cd = await source.read(cdOffset, cdSize), cv = new DataView(cd.buffer, cd.byteOffset, cd.byteLength), entries = [];
  for (let p = 0, i = 0; i < count; i++) {
    if (cv.getUint32(p, true) !== 0x02014b50) throw Error('Invalid ZIP central directory');
    const flags = cv.getUint16(p + 8, true), method = cv.getUint16(p + 10, true);
    let compressed = cv.getUint32(p + 20, true), size = cv.getUint32(p + 24, true);
    const nameLen = cv.getUint16(p + 28, true), extraLen = cv.getUint16(p + 30, true), commentLen = cv.getUint16(p + 32, true);
    let offset = cv.getUint32(p + 42, true);
    const nameBytes = cd.subarray(p + 46, p + 46 + nameLen), name = flags & 0x800 ? utf8.decode(nameBytes) : cp437(nameBytes);
    for (let e = p + 46 + nameLen, stop = e + extraLen; e + 4 <= stop;) {
      const id = cv.getUint16(e, true), len = cv.getUint16(e + 2, true);
      if (id === 1) { let q = e + 4; if (size === 0xffffffff) { size = Number(cv.getBigUint64(q, true)); q += 8; } if (compressed === 0xffffffff) { compressed = Number(cv.getBigUint64(q, true)); q += 8; } if (offset === 0xffffffff) offset = Number(cv.getBigUint64(q, true)); }
      e += 4 + len;
    }
    entries.push({name, method, compressed, size, offset, encrypted: !!(flags & 1)});
    p += 46 + nameLen + extraLen + commentLen;
  }
  const dataStart = (entry, head) => {
    if (entry.encrypted) throw Error('Encrypted ZIP entries are unsupported: ' + entry.name);
    const hv = new DataView(head.buffer, head.byteOffset, 30);
    if (hv.getUint32(0, true) !== 0x04034b50) throw Error('Invalid ZIP entry header: ' + entry.name);
    return entry.offset + 30 + hv.getUint16(26, true) + hv.getUint16(28, true);
  };
  const unpack = (entry, raw) => {
    let out;
    if (entry.method === 0) out = raw;
    else if (entry.method === 8) out = inflateSync(raw, {out: new Uint8Array(entry.size)});
    else throw Error('Unsupported ZIP compression method ' + entry.method + ': ' + entry.name);
    if (out.byteLength !== entry.size) throw Error('ZIP entry size mismatch: ' + entry.name);
    return out;
  };
  async function read(entry) {
    if (entry.encrypted) throw Error('Encrypted ZIP entries are unsupported: ' + entry.name);
    const start = dataStart(entry, await source.read(entry.offset, 30));
    return unpack(entry, await source.read(start, entry.compressed));
  }
  function readSync(entry) {
    if (!source.readSync) throw Error('This archive cannot be read synchronously: ' + entry.name);
    const start = dataStart(entry, source.readSync(entry.offset, 30));
    return unpack(entry, source.readSync(start, entry.compressed));
  }
  // Many entries at once: reads the archive in large windows (one slice per window instead of two per entry).
  // Every result has its own buffer; yields [entry, bytes] in archive offset order.
  async function* readMany(list, windowBytes = 32 << 20) {
    const sorted = [...list].sort((a, b) => a.offset - b.offset);
    for (let i = 0; i < sorted.length;) {
      const start = sorted[i].offset; let j = i, end = start;
      // Local headers may carry a different extra field than the central directory: allow 64 KiB slack per entry.
      while (j < sorted.length && (j === i || sorted[j].offset + sorted[j].compressed + 65600 - start <= windowBytes)) { end = Math.max(end, sorted[j].offset + 30 + 65600 + sorted[j].compressed); j++; }
      const win = await source.read(start, Math.min(end, source.size) - start), wv = new DataView(win.buffer, win.byteOffset, win.byteLength);
      for (const entry of sorted.slice(i, j)) {
        const o = entry.offset - start;
        if (wv.getUint32(o, true) !== 0x04034b50) throw Error('Invalid ZIP entry header: ' + entry.name);
        const data = o + 30 + wv.getUint16(o + 26, true) + wv.getUint16(o + 28, true);
        if (entry.encrypted) throw Error('Encrypted ZIP entries are unsupported: ' + entry.name);
        if (data + entry.compressed > win.length) { yield [entry, await read(entry)]; continue; }
        const raw = win.subarray(data, data + entry.compressed);
        let out;
        if (entry.method === 0) out = raw.slice(); // own buffer: the window can be freed and the bytes transferred
        else if (entry.method === 8) out = inflateSync(raw, {out: new Uint8Array(entry.size)});
        else throw Error('Unsupported ZIP compression method ' + entry.method + ': ' + entry.name);
        if (out.byteLength !== entry.size) throw Error('ZIP entry size mismatch: ' + entry.name);
        yield [entry, out];
      }
      i = j;
    }
  }
  const byName = new Map(entries.map(e => [e.name, e]));
  return {entries, read, readSync, readMany, get: name => byName.get(name)};
}
