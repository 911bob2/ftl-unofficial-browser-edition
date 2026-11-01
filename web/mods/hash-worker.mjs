// Incremental SHA-256 off the page's thread for downloads: {op: 'init', state} then {op: 'update', bytes} (each
// answered with the resumable state) and {op: 'hex'}.
import {createSha256} from './sha256.mjs';

let sha = createSha256();
self.onmessage = ({data}) => {
  try {
    if (data.op === 'init') sha = createSha256(data.state ?? null);
    else if (data.op === 'update') sha.update(data.bytes);
    self.postMessage({id: data.id, state: data.op === 'hex' ? null : sha.state(), hex: data.op === 'hex' ? sha.hex() : null});
  } catch (e) { self.postMessage({id: data.id, error: e.message}); }
};
