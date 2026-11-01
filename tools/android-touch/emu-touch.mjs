// Real multi-touch for an Android emulator: calls the emulator's own gRPC sendTouch, which writes multi-touch protocol B
// to the guest's virtio touchscreen. The events then pass through the kernel, EventHub, InputReader and InputDispatcher
// exactly as a phone's do. No root is needed, which is what a Google Play image requires: it refuses adb root and
// its SELinux policy denies the shell writing to /dev/input (sendevent).
//
// Needs an emulator started with -grpc <port>. No dependencies: gRPC is HTTP/2 with a five byte frame, and the two
// messages are small enough to encode by hand (emulator/lib/emulator_controller.proto).
import http2 from 'node:http2';

function varint(n) {
    const out = [];
    let v = BigInt.asUintN(64, BigInt(n));
    while (v > 0x7fn) {
        out.push(Number(v & 0x7fn) | 0x80);
        v >>= 7n;
    }
    out.push(Number(v));
    return out;
}

const field = (no, n) => [...varint(no << 3), ...varint(n)];

function encodeTouch({ x, y, id, pressure }) {
    return [
        ...field(1, x),
        ...field(2, y),
        ...field(3, id),
        ...field(4, pressure),
        ...field(7, 1), // NEVER_EXPIRE: a held finger stays down until the script lifts it
    ];
}

export function encodeTouchEvent(touches) {
    const out = [];
    for (const t of touches) {
        const body = encodeTouch(t);
        out.push(...varint((1 << 3) | 2), ...varint(body.length), ...body);
    }
    return Buffer.from(out);
}

export class EmulatorTouch {
    constructor(port) {
        this.client = http2.connect(`http://127.0.0.1:${port}`);
        this.client.on('error', (e) => { this.error = e; });
    }

    /** Sends one report: every finger listed changes together, in one SYN_REPORT batch per sendTouch. */
    send(touches) {
        const msg = encodeTouchEvent(touches);
        const frame = Buffer.alloc(5 + msg.length);
        frame.writeUInt32BE(msg.length, 1);
        msg.copy(frame, 5);
        return new Promise((resolve, reject) => {
            if (this.error) return reject(this.error);
            const s = this.client.request({
                ':method': 'POST',
                ':path': '/android.emulation.control.EmulatorController/sendTouch',
                'content-type': 'application/grpc',
                te: 'trailers',
            });
            let status = null;
            s.on('response', (h) => { if (h['grpc-status'] !== undefined) status = h['grpc-status']; });
            s.on('trailers', (h) => { status = h['grpc-status']; });
            s.on('data', () => {});
            s.on('error', reject);
            s.on('close', () => {
                if (status === '0' || status === 0) resolve();
                else reject(new Error(`sendTouch: grpc-status ${status}`));
            });
            s.end(frame);
        });
    }

    close() {
        this.client.close();
    }
}
