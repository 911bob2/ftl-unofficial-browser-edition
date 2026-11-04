// The emulator and the running debug app, for the multi-touch tests: adb, the debug console, frames, and fingers placed
// in the game's canvas units.
import { spawnSync } from 'node:child_process';
import { writeFileSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { EmulatorTouch } from './emu-touch.mjs';

export const PKG = 'dev.ftl911bob.unofficial';
export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

export class Device {
    constructor({ serial, grpcPort, adbPath, outDir }) {
        this.serial = serial;
        this.adbPath = adbPath;
        this.outDir = outDir;
        this.touch = new EmulatorTouch(grpcPort);
        this.fingers = new Map();
        this.tagCount = 0;
        this.nextFingerId = 1;
        mkdirSync(outDir, { recursive: true });
        this.size = this.screen();
    }

    adb(...args) {
        const r = spawnSync(this.adbPath, ['-s', this.serial, ...args], { encoding: 'buffer', maxBuffer: 1 << 28, env: { ...process.env, MSYS_NO_PATHCONV: '1' } });
        if (r.error) throw r.error;
        return r.stdout;
    }

    shell(cmd) {
        return this.adb('shell', cmd).toString('utf8');
    }

    screen() {
        const m = /Physical size: (\d+)x(\d+)/.exec(this.shell('wm size'));
        return { panelW: Number(m[1]), panelH: Number(m[2]) };
    }

    rotation() {
        const m = /mCurrentRotation=ROTATION_(\d+)/.exec(this.shell('dumpsys window displays'));
        return Number(m[1]) / 90;
    }

    console(cmd) {
        this.adb('shell', 'am', 'broadcast', '-n', `${PKG}/ftl.android.app.debug.ConsoleReceiver`, '--es', 'cmd', `'${cmd}'`);
    }

    /** One JSON line from the game thread (TouchProbe). */
    async state() {
        const tag = `s${Date.now()}_${this.tagCount++}`;
        this.console(`touchstate ${tag}`);
        for (let i = 0; i < 100; i++) {
            const log = this.adb('logcat', '-d', '-s', 'FTL-Touch:I', '-t', '200').toString('utf8');
            const lines = log.split('\n').map((l) => l.replace(/\r$/, ''));
            const line = lines.find((l) => l.includes(`"tag":"${tag}"`) && !l.includes(`part ${tag} `));
            if (line) return JSON.parse(line.slice(line.indexOf('{')));
            // A long answer comes in parts, "part <tag> i/n <text>" (logcat cuts a line at about 4000 bytes).
            const parts = [];
            let total = 0;
            for (const l of lines) {
                const at = l.indexOf(`part ${tag} `);
                if (at < 0) continue;
                const m = /^(\d+)\/(\d+) /.exec(l.slice(at + tag.length + 6));
                if (!m) continue;
                parts[Number(m[1]) - 1] = l.slice(at + tag.length + 6 + m[0].length);
                total = Number(m[2]);
            }
            if (total && parts.filter((x) => x !== undefined).length === total) return JSON.parse(parts.join(''));
            await sleep(50);
        }
        throw new Error('no answer from the running game (is it open on a debug build?)');
    }

    /** The MotionEvents the view received since [clearTrace]. */
    trace() {
        return this.adb('logcat', '-d', '-s', 'FTL-Touch:I').toString('utf8').split('\n')
            .map((l) => { const i = l.indexOf(' me '); return i < 0 ? null : l.slice(i + 4).trim(); }).filter(Boolean);
    }

    clearTrace() {
        this.adb('logcat', '-c');
    }

    frame(name) {
        const png = this.adb('exec-out', 'screencap', '-p');
        const path = join(this.outDir, `${name}.png`);
        writeFileSync(path, png);
        return path;
    }

    // ---- fingers: canvas units (the game's 768 tall frame), on the default 16:9 picture centred on the screen ----
    canvasToPanel(cx, cy, canvasW = 1365, rot = this.rot ?? 1) {
        const { panelW, panelH } = this.size;
        const scale = panelW / 768;
        const sx = (panelH - canvasW * scale) / 2 + cx * scale;
        const sy = cy * scale;
        return this.screenToPanel(sx, sy, rot);
    }

    /** From landscape screen pixels to the panel's own coordinates, which is what the touchscreen reports. */
    screenToPanel(sx, sy, rot = this.rot ?? 1) {
        const { panelW, panelH } = this.size;
        if (rot === 3) return { x: Math.round(sy), y: Math.round(panelH - sx) };
        return { x: Math.round(panelW - sy), y: Math.round(sx) };
    }

    /** Puts a finger down; returns its handle. All fingers listed in one call change in one touch report. */
    async down(cx, cy) {
        const f = { id: this.nextFingerId++, cx, cy };
        const p = this.canvasToPanel(cx, cy);
        this.fingers.set(f.id, f);
        await this.touch.send([{ ...p, id: f.id, pressure: 100 }]);
        return f;
    }

    async downScreen(sx, sy) {
        const f = { id: this.nextFingerId++, screen: true, sx, sy };
        this.fingers.set(f.id, f);
        await this.touch.send([{ ...this.screenToPanel(sx, sy), id: f.id, pressure: 100 }]);
        return f;
    }

    async moveTo(f, cx, cy, { steps = 8, ms = 16 } = {}) {
        const x0 = f.cx;
        const y0 = f.cy;
        for (let i = 1; i <= steps; i++) {
            f.cx = x0 + (cx - x0) * i / steps;
            f.cy = y0 + (cy - y0) * i / steps;
            await this.touch.send([{ ...this.canvasToPanel(f.cx, f.cy), id: f.id, pressure: 100 }]);
            await sleep(ms);
        }
    }

    /** Lifts these fingers together, in one report. */
    async up(...fs) {
        await this.touch.send(fs.map((f) => ({
            ...(f.screen ? this.screenToPanel(f.sx, f.sy) : this.canvasToPanel(f.cx, f.cy)),
            id: f.id, pressure: 0,
        })));
        for (const f of fs) this.fingers.delete(f.id);
    }

    async tap(cx, cy, holdMs = 60) {
        const f = await this.down(cx, cy);
        await sleep(holdMs);
        await this.up(f);
        await sleep(150);
    }

    /** Waits for the game to have seen everything sent, then for a frame or two. */
    async settle(ms = 250) {
        await sleep(ms);
    }

    close() {
        this.touch.close();
    }
}
