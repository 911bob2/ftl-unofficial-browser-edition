// Mod worker: archive inspection on import and the merge at launch, off the page's main thread.
//   inspect {file, hash} -> {sha256 (null when hash is false), size, title, version, hyperspace, entries}
//   merge   {archives: [{name, file}], baseNames, baseText: [[name, bytes]], baseSizes: [[name, size]], cached} -> {text,
//            binary, audio, removed, warnings, cache, ms}; `cached` (a stored merge) skips the merge itself.
// Lua patch scripts (`.append.lua`) read game files synchronously: base text files from baseText, archive entries
// through FileReaderSync; other base files (images, audio, fonts) are not readable by scripts here.
import {blobSource, checkZipLimits, openZip} from './zip.mjs';
import {parseMetadata} from './session.mjs';
import {blobSha256} from './sha256.mjs';
import {mergeMods} from './merge.mjs';

const isAudio = n => /\.(ogg|wav)$/.test(n);
self.onmessage = async ({data}) => {
  const {id, action} = data, progress = value => self.postMessage({id, progress: value});
  try {
    if (action === 'inspect') {
      const {file} = data, zip = await openZip(blobSource(file)), meta = zip.get('mod-appendix/metadata.xml');
      checkZipLimits(zip.entries);
      const {title, version} = parseMetadata(meta ? await zip.read(meta) : null);
      // A finished download was hashed while it arrived (`hash: false`): only its metadata is read here.
      const sha256 = data.hash === false ? null : await blobSha256(file, (done, total) => progress({phase: 'hash', done, total}));
      self.postMessage({id, result: {sha256, size: file.size, title, version, entries: zip.entries.length, hyperspace: !!zip.get('data/hyperspace.xml')}});
    } else if (action === 'merge') {
      const started = performance.now(), zips = [];
      for (const a of data.archives) zips.push(await openZip(blobSource(a.file)));
      let cache = data.cached;
      if (!cache) {
        const base = new Map(data.baseText), sizes = new Map(data.baseSizes ?? []);
        const result = await mergeMods({baseNames: data.baseNames, readBase: n => base.get(n) ?? (/\.(xml|txt)$/.test(n) ? new Uint8Array(0) : null),
          baseSize: n => sizes.get(n) ?? base.get(n)?.length ?? 0, lazyBinary: true,
          archives: data.archives.map((a, i) => ({name: a.name, zip: zips[i]})), onProgress: p => progress({phase: 'merge', ...p}),
          onLuaLog: (kind, text) => console.info('[mod script]', text)});
        const text = [], refs = [];
        for (const [name, v] of result.files) { if (v instanceof Uint8Array) text.push([name, v]); else refs.push([name, v.archive, v.entry.name]); }
        cache = {text, refs, removed: [...result.removed], warnings: result.warnings};
      }
      const merged = performance.now();
      // Images and other binaries are read now (the engine needs them in its file map); audio stays lazy.
      const binary = [], audio = [], transfer = [];
      const wanted = zips.map(() => new Map());
      for (const [name, archive, entry] of cache.refs) {
        if (isAudio(name)) { audio.push([name, archive, entry]); continue; }
        const e = zips[archive].get(entry);
        if (!e) throw Error(`${entry} is missing from ${data.archives[archive].name}`);
        if (!wanted[archive].has(e)) wanted[archive].set(e, []);
        wanted[archive].get(e).push(name);
      }
      let done = 0;
      for (const [archive, map] of wanted.entries()) {
        for await (const [e, bytes] of zips[archive].readMany(map.keys())) {
          for (const [k, name] of map.get(e).entries()) { const b = k ? bytes.slice() : bytes; binary.push([name, b]); transfer.push(b.buffer); }
          if (++done % 512 === 0) progress({phase: 'read', done, total: cache.refs.length});
        }
      }
      // Text results are copied (they also go to the cache); binaries are transferred.
      self.postMessage({id, result: {cache, binary, audio, mergeMs: merged - started, readMs: performance.now() - merged}}, transfer);
    } else throw Error('Unknown mod operation.');
  } catch (e) { self.postMessage({id, error: e.message}); }
};
