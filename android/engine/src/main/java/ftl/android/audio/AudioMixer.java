package ftl.android.audio;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import ftl.android.GameFiles;
import java.nio.ShortBuffer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The engine's OpenAL calls (org.lwjgl.openal.AL10 through ftl.browser.BrowserAudio) on Android: the same source and
 * buffer model as the browser's Web Audio host (web/audio.mjs), mixed in software into one AudioTrack.
 *
 * <p>Sounds are decoded once on a background thread (MediaCodec) into native memory. A source started before its
 * buffer is decoded begins when the buffer is ready, as in the browser. Positions are seconds, pitch scales the rate.
 */
public final class AudioMixer {
    public static final int AL_PITCH = 0x1003, AL_LOOPING = 0x1007, AL_BUFFER = 0x1009, AL_GAIN = 0x100A,
        AL_SOURCE_STATE = 0x1010, AL_INITIAL = 0x1011, AL_PLAYING = 0x1012, AL_PAUSED = 0x1013, AL_STOPPED = 0x1014,
        AL_SEC_OFFSET = 0x1024;
    private static final int RATE = 44100, BLOCK = 512;
    /** Decoded PCM kept beyond the sounds in use, in bytes: a share of the Java heap, where a direct buffer is an array. */
    private static final long CACHE_BYTES = Math.min(192L << 20, Runtime.getRuntime().maxMemory() / 4);
    /** "adb shell setprop log.tag.FTL-Audio DEBUG" logs each sound as it decodes; [stats] prints the totals. */
    private static final String TAG = "FTL-Audio";

    private static AudioMixer instance;

    public static synchronized AudioMixer get() {
        if (instance == null) instance = new AudioMixer();
        return instance;
    }

    private static final class Buffer {
        final String path;
        volatile Pcm pcm;
        volatile boolean failed;
        long lastUsed;

        Buffer(String path) { this.path = path; }
    }

    private static final class Source {
        int buffer;
        boolean loop;
        float gain = 1, pitch = 1;
        int state = AL_INITIAL;
        /** Position in seconds while not playing; while playing, cursor holds the position in buffer frames. */
        double offset;
        double cursor;
        boolean started;
    }

    private final Map<Integer, Buffer> buffers = new HashMap<>();
    private final Map<String, Integer> byPath = new HashMap<>();
    private final Map<Integer, Source> sources = new HashMap<>();
    // Music takes far longer to decode than an effect, so an effect never queues behind a track.
    private final ExecutorService decoder = decodeThread("ftl-audio-decode"), musicDecoder = decodeThread("ftl-music-decode");
    private int next = 1;
    /** Counts ended games. */
    private volatile int generation;
    private long decodedBytes;
    private AudioTrack track;
    private Thread thread;
    private volatile boolean running, paused;
    private float masterGain = 1;
    private int decodedCount, failedCount, unknownSourceCalls;
    private long audibleBlocks;
    private float peak;

    private AudioMixer() {
    }

    private static ExecutorService decodeThread(String name) {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
    }

    // ---- lifecycle (activity) ----

    public synchronized void start() {
        if (running) return;
        int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        track = new AudioTrack.Builder()
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(Math.max(min, BLOCK * 4 * 4))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM).build();
        track.play();
        running = true;
        thread = new Thread(this::loop, "ftl-audio-mix");
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
    }

    public void stop() {
        Thread t;
        synchronized (this) {
            running = false;
            t = thread;
            thread = null;
        }
        if (t != null) {
            try { t.join(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        synchronized (this) {
            if (track != null) {
                track.stop();
                track.release();
                track = null;
            }
        }
    }

    /** Silence while the app is in the background; sources keep their state. */
    public void setPaused(boolean value) { paused = value; }

    /**
     * Ends a game (engine shutdown): every sound is forgotten, and sounds still queued for decoding are dropped, because
     * the next game may have different files under the same paths. The sources stay and go silent: the engine's
     * SoundStore creates them once per process and uses the same ones in every game.
     */
    public synchronized void reset() {
        for (Source s : sources.values()) {
            stopNode(s);
            s.buffer = 0;
            s.loop = false;
            s.gain = 1;
            s.pitch = 1;
            s.offset = 0;
            s.state = AL_INITIAL;
        }
        generation++;
        buffers.clear();
        byPath.clear();
        decodedBytes = 0;
        decodedCount = failedCount = unknownSourceCalls = 0;
        audibleBlocks = 0;
        peak = 0;
    }

    // ---- the browser host's API ----

    public synchronized int buffer(String path) {
        Integer known = byPath.get(path);
        if (known != null) return known;
        int id = next++;
        byPath.put(path, id);
        Buffer b = new Buffer(path);
        buffers.put(id, b);
        decode(b, GameFiles.current(), generation);
        return id;
    }

    /** On the decoder thread, from the files of the game that asked; a job of an ended game stops where it is. */
    private void decode(Buffer b, GameFiles files, int forGame) {
        (b.path.startsWith("audio/music/") ? musicDecoder : decoder).execute(() -> {
            try {
                if (forGame != generation) return;
                long began = System.nanoTime();
                byte[] bytes = files.audio(b.path);
                if (bytes == null) throw new IllegalStateException("Missing audio " + b.path);
                Pcm pcm = PcmDecoder.decode(bytes, () -> forGame != generation);
                if (pcm == null) return;
                synchronized (AudioMixer.this) {
                    if (forGame != generation) return;
                    b.pcm = pcm;
                    b.lastUsed = System.nanoTime();
                    decodedBytes += pcm.bytes();
                    decodedCount++;
                    evict();
                }
                if (android.util.Log.isLoggable(TAG, android.util.Log.DEBUG))
                    android.util.Log.d(TAG, "decoded " + b.path + " in " + (System.nanoTime() - began) / 1000000 + " ms, frames=" + pcm.frames + " rate=" + pcm.rate + " channels=" + pcm.channels);
            } catch (Throwable e) {
                if (forGame != generation) return;
                synchronized (AudioMixer.this) { failedCount++; }
                b.failed = true;
                android.util.Log.e("FTL", "Audio decode failed: " + b.path, e);
            }
        });
    }

    /** Frees decoded sounds no source uses, oldest first, while the cache is over budget. */
    private void evict() {
        while (decodedBytes > CACHE_BYTES) {
            Buffer oldest = null;
            int oldestId = 0;
            for (Map.Entry<Integer, Buffer> e : buffers.entrySet()) {
                Buffer b = e.getValue();
                if (b.pcm == null || inUse(e.getKey())) continue;
                if (oldest == null || b.lastUsed < oldest.lastUsed) {
                    oldest = b;
                    oldestId = e.getKey();
                }
            }
            if (oldest == null) return;
            decodedBytes -= oldest.pcm.bytes();
            oldest.pcm = null;
            buffers.remove(oldestId);
            byPath.remove(oldest.path);
        }
    }

    private boolean inUse(int buffer) {
        for (Source s : sources.values()) if (s.buffer == buffer) return true;
        return false;
    }

    public synchronized void music(int id, String path) {
        Source s = sources.get(id);
        if (s == null) {
            unknownSourceCalls++;
            return;
        }
        stopNode(s);
        s.offset = 0;
        s.buffer = buffer(path);
        play(s);
    }

    public synchronized double call(String op, int a, int b, double c) {
        Source s = sources.get(a);
        if (s == null && a != 0 && !op.endsWith("Buffer")) unknownSourceCalls++;
        switch (op) {
            case "buffer": {
                int id = next++;
                buffers.put(id, new Buffer(""));
                return id;
            }
            case "source": {
                int id = next++;
                sources.put(id, new Source());
                return id;
            }
            case "deleteBuffer":
                buffers.remove(a);
                return 0;
            case "deleteSource":
                if (s != null) sources.remove(a);
                return 0;
            case "sourcei":
                if (s == null) return 0;
                if (b == AL_BUFFER) {
                    stopNode(s);
                    s.buffer = (int) c;
                    s.offset = 0;
                    s.state = AL_INITIAL;
                }
                if (b == AL_LOOPING) s.loop = c != 0;
                return 0;
            case "sourcef":
                if (s == null) return 0;
                if (b == AL_GAIN) s.gain = (float) c;
                if (b == AL_PITCH) s.pitch = (float) c;
                if (b == AL_SEC_OFFSET) {
                    boolean playing = s.state == AL_PLAYING;
                    stopNode(s);
                    s.offset = c;
                    if (playing) play(s);
                }
                return 0;
            case "play":
                if (s != null) {
                    stopNode(s);
                    play(s);
                }
                return 0;
            case "pause":
                if (s != null) {
                    s.offset = position(s);
                    stopNode(s);
                    s.state = AL_PAUSED;
                }
                return 0;
            case "stop":
                if (s != null) {
                    stopNode(s);
                    s.offset = 0;
                    s.state = AL_STOPPED;
                }
                return 0;
            case "getSource":
                if (s == null) return 0;
                if (b == AL_SOURCE_STATE) return s.state;
                if (b == AL_SEC_OFFSET) return position(s);
                if (b == AL_GAIN) return s.gain;
                if (b == AL_PITCH) return s.pitch;
                return 0;
            case "getBuffer": {
                Buffer buf = buffers.get(a);
                Pcm pcm = buf == null ? null : buf.pcm;
                return pcm == null ? 0 : pcm.frames;
            }
            default:
                return 0;
        }
    }

    // ---- source state ----

    private void play(Source s) {
        s.state = AL_PLAYING;
        Buffer b = buffers.get(s.buffer);
        if (b == null || b.failed) {
            s.state = AL_STOPPED;
            return;
        }
        s.started = false;
    }

    /** Ends the current playback; like the browser's node stop, the source's offset stays where it started. */
    private void stopNode(Source s) {
        s.started = false;
    }

    private double position(Source s) {
        if (s.state != AL_PLAYING || !s.started) return s.offset;
        Buffer b = buffers.get(s.buffer);
        Pcm pcm = b == null ? null : b.pcm;
        return pcm == null ? s.offset : s.cursor / pcm.rate;
    }

    /** The mixer's totals since this process started or the last reset, for the debug console. */
    public synchronized String stats() {
        int playing = 0, waiting = 0, known = 0;
        for (Source s : sources.values()) {
            if (s.state != AL_PLAYING) continue;
            Buffer b = buffers.get(s.buffer);
            if (b != null && b.pcm != null) playing++;
            else waiting++;
        }
        for (Buffer b : buffers.values()) if (!b.path.isEmpty()) known++;
        return "running=" + running + " paused=" + paused + " sources=" + sources.size() + " playing=" + playing + " waitingForDecode=" + waiting
            + " sounds=" + known + " decoded=" + decodedCount + " failed=" + failedCount + " unknownSourceCalls=" + unknownSourceCalls
            + " audibleBlocks=" + audibleBlocks + " peak=" + peak;
    }

    // ---- mixing ----

    private void loop() {
        short[] out = new short[BLOCK * 2];
        float[] mix = new float[BLOCK * 2];
        while (running) {
            java.util.Arrays.fill(mix, 0);
            if (!paused) {
                synchronized (this) { mixInto(mix); }
            }
            float blockPeak = 0;
            for (int i = 0; i < mix.length; i++) {
                float v = mix[i] * masterGain;
                blockPeak = Math.max(blockPeak, Math.abs(v));
                out[i] = (short) Math.max(-32768, Math.min(32767, Math.round(v * 32767f)));
            }
            if (blockPeak > 0) {
                audibleBlocks++;
                peak = Math.max(peak, blockPeak);
            }
            AudioTrack t = track;
            if (t == null) break;
            t.write(out, 0, out.length);
        }
    }

    private void mixInto(float[] mix) {
        for (Iterator<Source> it = sources.values().iterator(); it.hasNext(); ) {
            Source s = it.next();
            if (s.state != AL_PLAYING) continue;
            Buffer b = buffers.get(s.buffer);
            if (b == null) {
                s.state = AL_STOPPED;
                continue;
            }
            if (b.failed) {
                s.state = AL_STOPPED;
                continue;
            }
            Pcm pcm = b.pcm;
            if (pcm == null) continue;
            b.lastUsed = System.nanoTime();
            double duration = pcm.frames / (double) pcm.rate;
            if (!s.started) {
                double start = Math.max(0, s.offset);
                if (duration > 0) start %= duration;
                s.cursor = start * pcm.rate;
                s.started = true;
            }
            double step = s.pitch * pcm.rate / (double) RATE;
            ShortBuffer data = pcm.data;
            int ch = pcm.channels;
            float gain = s.gain / 32768f;
            for (int i = 0; i < BLOCK; i++) {
                if (s.cursor >= pcm.frames) {
                    if (s.loop && pcm.frames > 0) s.cursor %= pcm.frames;
                    else {
                        s.state = AL_STOPPED;
                        s.offset = 0;
                        s.started = false;
                        break;
                    }
                }
                int f = (int) s.cursor;
                double frac = s.cursor - f;
                int g = f + 1 < pcm.frames ? f + 1 : (s.loop ? 0 : f);
                float l0 = data.get(f * ch), l1 = data.get(g * ch);
                float l = (float) (l0 + (l1 - l0) * frac);
                float r = l;
                if (ch > 1) {
                    float r0 = data.get(f * ch + 1), r1 = data.get(g * ch + 1);
                    r = (float) (r0 + (r1 - r0) * frac);
                }
                mix[i * 2] += l * gain;
                mix[i * 2 + 1] += r * gain;
                s.cursor += step;
            }
        }
    }
}
