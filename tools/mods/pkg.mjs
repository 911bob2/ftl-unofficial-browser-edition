// Reader and writer for FTL's PKG archive (ftl.dat) as silpkg writes it: empty slots (data offset 0) are skipped and
// DEFLATED entries (flag bit 24) are inflated. Test/diagnostic tooling only (reads ftlman's patched ftl.dat).
import {readFileSync} from 'node:fs';
import {inflateSync} from 'node:zlib';

export function readPkg(path) {
  const data = readFileSync(path), view = new DataView(data.buffer, data.byteOffset, data.byteLength);
  if (view.getUint32(0) !== 0x504b470a || view.getUint16(4) !== 16 || view.getUint16(6) !== 20) throw Error('Not a PKG archive: ' + path);
  const count = view.getUint32(8), nameSize = view.getUint32(12), names = data.subarray(16 + count * 20, 16 + count * 20 + nameSize);
  const files = new Map();
  for (let i = 0; i < count; i++) {
    const pos = 16 + i * 20, flagName = view.getUint32(pos + 4), offset = view.getUint32(pos + 8), size = view.getUint32(pos + 12), unpacked = view.getUint32(pos + 16);
    if (offset === 0) continue;
    const start = flagName & 0xffffff; let end = start; while (names[end] !== 0) end++;
    const name = names.subarray(start, end).toString('latin1');
    let bytes = data.subarray(offset, offset + size);
    if (flagName & 0x01000000) bytes = inflateSync(bytes);
    else if (flagName >>> 24) throw Error('Unknown PKG entry flags in ' + name);
    if (bytes.length !== unpacked) throw Error('PKG size mismatch for ' + name);
    if (files.has(name)) throw Error('Duplicate PKG entry ' + name);
    files.set(name, bytes);
  }
  return files;
}

// silpkg's pkg_path_hash.
const pathHash = p => { let h = 0; for (const ch of p.toLowerCase()) { h = ((h << 27) | (h >>> 5)) >>> 0; h = (h ^ ch.charCodeAt(0)) >>> 0; } return h; };
/** A PKG archive of `files` (Map name -> bytes), uncompressed, in map order (synthetic test bases). */
export function writePkg(files) {
  const names = [...files.keys()], nameBytes = [], nameOffsets = []; let nameSize = 0;
  for (const n of names) { const b = Buffer.from(n + '\0', 'ascii'); nameOffsets.push(nameSize); nameBytes.push(b); nameSize += b.length; }
  const header = 16 + names.length * 20 + nameSize, out = [Buffer.alloc(16 + names.length * 20)], dv = out[0];
  dv.writeUInt32BE(0x504b470a, 0); dv.writeUInt16BE(16, 4); dv.writeUInt16BE(20, 6); dv.writeUInt32BE(names.length, 8); dv.writeUInt32BE(nameSize, 12);
  let offset = header;
  names.forEach((n, i) => { const len = files.get(n).length, p = 16 + i * 20; dv.writeUInt32BE(pathHash(n), p); dv.writeUInt32BE(nameOffsets[i], p + 4); dv.writeUInt32BE(offset, p + 8); dv.writeUInt32BE(len, p + 12); dv.writeUInt32BE(len, p + 16); offset += len; });
  out.push(...nameBytes, ...names.map(n => Buffer.from(files.get(n))));
  return Buffer.concat(out);
}
