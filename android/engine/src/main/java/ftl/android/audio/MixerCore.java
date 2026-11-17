package ftl.android.audio;

import java.nio.ShortBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The mixer's sources, sounds and rules, with nothing of Android's in it (AudioMixer gives it the decoder, the threads
 * and the AudioTrack; MixerCoreTest gives it fakes): the same source and buffer model as the browser's Web Audio host
 * (web/audio.mjs).
 *
 * <p>A sound keeps its id for the whole game, because the engine asks for it once and remembers it
 * (RealSoundManager.loadRawSound). So nothing here may make an id unplayable for good: a sound freed to save memory, or
 * one whose decode failed, is decoded again when it is next played. Only a file the game does not have stays silent.
 */
public final class MixerCore {
    public static final int AL_PITCH = 0x1003, AL_LOOPING = 0x1007, AL_BUFFER = 0x1009, AL_GAIN = 0x100A,
        AL_SOURCE_STATE = 0x1010, AL_INITIAL = 0x1011, AL_PLAYING = 0x1012, AL_PAUSED = 0x1013, AL_STOPPED = 0x1014,
        AL_SEC_OFFSET = 0x1024;
    public static final int RATE = 44100, BLOCK = 512;
    /**
     * Blocks an effect waits for its sound before it is given up, about two seconds: it would come too late to mean
     * anything, and a decode that never ends must not hold one of the engine's voices (SoundStore has 61 for effects).
     * Music and loops wait as long as it takes.
     */
    public static final int STALE_BLOCKS = 2 * RATE / BLOCK;
    /** A failed decode is tried again when the sound is next wanted, not before this long, doubling up to a minute. */
    private static final long RETRY_NANOS = 1_000_000_000L, RETRY_MAX_NANOS = 60_000_000_000L;
    /** Blocks the output may take nothing before it is opened anew (about a second with the caller's wait). */
    private static final int BAD_WRITES = 50;

    /** A sound file the game does not have: it never plays and is not asked for again. */
    public static final class MissingAudio extends Exception {
        public MissingAudio(String path) { super("Missing audio " + path); }
    }

    /** One game's sound files, decoded: null when [stopped] said so midway. */
    public interface Loader {
        Pcm load(String path, BooleanSupplier stopped) throws Exception;
    }

    public interface Log {
        void debug(String message);

        void error(String message, Throwable e);
    }

    /** Where the mixed blocks go. */
    public interface Output {
        /** Takes [length] samples, waiting for room; less than that, or a negative number, when it cannot. */
        int write(short[] data, int length);

        void reopen() throws Exception;
    }

    private static final class Buffer {
        final String path;
        final Loader loader;
        final boolean music;
        Pcm pcm;
        boolean pending, missing, deleted, freed;
        int failures;
        long retryAt, lastUsed;

        Buffer(String path, Loader loader) {
            this.path = path;
            this.loader = loader;
            music = path.startsWith("audio/music/");
        }
    }

    private static final class Source {
        Buffer buffer;
        boolean loop, music;
        float gain = 1, pitch = 1;
        int state = AL_INITIAL;
        /** Position in seconds while not playing; while playing, cursor holds the position in buffer frames. */
        double offset;
        double cursor;
        boolean started;
        /** Blocks spent waiting for the sound to be decoded. */
        int waited;
    }

    private final Map<Integer, Buffer> buffers = new HashMap<>();
    private final Map<String, Integer> byPath = new HashMap<>();
    private final Map<Integer, Source> sources = new HashMap<>();
    /** The sources as the mixing thread walks them: it allocates nothing, so a full heap cannot end it. */
    private Source[] mixing = new Source[0];
    private final Executor decoder, musicDecoder;
    private final Supplier<Loader> loader;
    private final LongSupplier nanos;
    private final Log log;
    private volatile long cacheBytes;
    private int next = 1;
    /** Counts ended games. */
    private volatile int generation;
    private long decodedBytes;
    private volatile boolean paused, muted;
    private float masterGain = 1;
    private int decodedCount, failedCount, unknownSourceCalls, freedCount, reloadedCount, staleCount, mixErrors, reopens;
    private long audibleBlocks;
    private float peak;
    private final short[] out = new short[BLOCK * 2];
    private final float[] mix = new float[BLOCK * 2];
    private int badWrites;
    private boolean outputTakes = true;

    /**
     * @param cacheBytes   decoded PCM kept beyond the sounds that are playing
     * @param decoder      runs effect decodes; music has its own, since a track takes far longer than an effect
     * @param loader       the files of the game that is asking now
     */
    public MixerCore(long cacheBytes, Executor decoder, Executor musicDecoder, Supplier<Loader> loader, LongSupplier nanos, Log log) {
        this.cacheBytes = cacheBytes;
        this.decoder = decoder;
        this.musicDecoder = musicDecoder;
        this.loader = loader;
        this.nanos = nanos;
        this.log = log;
    }

    /** Silence with every source held where it is: the app is in the background and the game stands still. */
    public void setPaused(boolean value) { paused = value; }

    /**
     * Silence while the game goes on (another app has the audio focus): the sources play on unheard, so the effects of
     * that time end as they would, instead of holding every voice and all sounding at once afterwards.
     */
    public void setMuted(boolean value) { muted = value; }

    public void setCacheBytes(long bytes) { cacheBytes = bytes; }

    /**
     * Ends a game (engine shutdown): every sound is forgotten, and sounds still queued for decoding are dropped, because
     * the next game may have different files under the same paths. The sources stay and go silent: the engine's
     * SoundStore creates them once per process and uses the same ones in every game.
     */
    public synchronized void reset() {
        for (Source s : sources.values()) {
            stopNode(s);
            s.buffer = null;
            s.loop = false;
            s.music = false;
            s.gain = 1;
            s.pitch = 1;
            s.offset = 0;
            s.state = AL_INITIAL;
        }
        generation++;
        buffers.clear();
        byPath.clear();
        decodedBytes = 0;
        decodedCount = failedCount = unknownSourceCalls = freedCount = reloadedCount = staleCount = mixErrors = reopens = 0;
        audibleBlocks = 0;
        peak = 0;
    }

    // ---- the browser host's API ----

    public synchronized int buffer(String path) {
        Integer known = byPath.get(path);
        if (known != null) return known;
        int id = next++;
        byPath.put(path, id);
        Buffer b = new Buffer(path, loader.get());
        buffers.put(id, b);
        request(b);
        return id;
    }

    /** Queues the sound's decode unless it is there, on its way, missing, or failed too recently. True while it is on its way. */
    private boolean request(Buffer b) {
        if (b.pending) return true;
        if (b.pcm != null || b.missing || b.deleted || b.path.isEmpty()) return false;
        if (b.failures > 0 && nanos.getAsLong() - b.retryAt < 0) return false;
        b.pending = true;
        int forGame = generation;
        try {
            (b.music ? musicDecoder : decoder).execute(() -> decode(b, forGame));
        } catch (RuntimeException e) {
            failed(b, e);
            return false;
        }
        return true;
    }

    /** On a decoder thread, from the files of the game that asked; a job of an ended game stops where it is. */
    private void decode(Buffer b, int forGame) {
        if (forGame != generation) return;
        BooleanSupplier stopped = () -> forGame != generation;
        long began = nanos.getAsLong();
        Pcm pcm;
        try {
            try {
                pcm = b.loader.load(b.path, stopped);
            } catch (OutOfMemoryError e) {
                // The heap is full. Every decoded sound that is not playing goes, then this one is tried once more.
                synchronized (this) {
                    if (forGame != generation) return;
                    free(0, b);
                }
                log.error("Out of memory decoding " + b.path + "; the sound cache was emptied, trying again", null);
                pcm = b.loader.load(b.path, stopped);
            }
            if (pcm == null && !stopped.getAsBoolean()) throw new IllegalStateException("Nothing decoded");
        } catch (MissingAudio e) {
            synchronized (this) {
                if (forGame != generation) return;
                b.pending = false;
                b.missing = true;
                failedCount++;
            }
            log.error("Audio decode failed: " + b.path + ": the game files have no such sound", null);
            return;
        } catch (Throwable e) {
            if (forGame != generation) return;
            failed(b, e);
            return;
        }
        if (pcm == null) return;
        synchronized (this) {
            if (forGame != generation) return;
            b.pending = false;
            b.failures = 0;
            if (b.deleted) return;
            b.pcm = pcm;
            b.lastUsed = nanos.getAsLong();
            decodedBytes += pcm.bytes();
            decodedCount++;
            if (b.freed) reloadedCount++;
            free(cacheBytes, b);
        }
        log.debug("decoded " + b.path + " in " + (nanos.getAsLong() - began) / 1000000 + " ms, frames=" + pcm.frames + " rate=" + pcm.rate + " channels=" + pcm.channels);
    }

    private void failed(Buffer b, Throwable e) {
        int tries;
        synchronized (this) {
            b.pending = false;
            tries = ++b.failures;
            b.retryAt = nanos.getAsLong() + Math.min(RETRY_MAX_NANOS, RETRY_NANOS << Math.min(tries - 1, 6));
            failedCount++;
        }
        try {
            log.error("Audio decode failed: " + b.path + " (try " + tries + ", tried again when it is next played)", e);
        } catch (Throwable ignored) {
            // Out of memory again while saying so.
        }
    }

    /**
     * Frees decoded sounds that no source is playing while more than [limit] bytes are held: music first (a track is
     * hundreds of effects' worth and its return is covered by its fade), then effects, each least recently heard first.
     * The sound keeps its id and is decoded again when it is next played.
     */
    private void free(long limit, Buffer keep) {
        while (decodedBytes > limit) {
            Buffer oldest = null;
            for (Buffer b : buffers.values()) {
                if (b.pcm == null || b == keep || inUse(b)) continue;
                if (oldest == null || (b.music != oldest.music ? b.music : b.lastUsed - oldest.lastUsed < 0)) oldest = b;
            }
            if (oldest == null) return;
            decodedBytes -= oldest.pcm.bytes();
            oldest.pcm = null;
            oldest.freed = true;
            freedCount++;
        }
    }

    /** Playing, waiting to play, or paused on a source. A source that has finished holds nothing. */
    private boolean inUse(Buffer b) {
        for (Source s : mixing) if (s.buffer == b && (s.state == AL_PLAYING || s.state == AL_PAUSED)) return true;
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
        s.buffer = buffers.get(buffer(path));
        s.music = true;
        play(s);
    }

    public synchronized double call(String op, int a, int b, double c) {
        Source s = sources.get(a);
        if (s == null && a != 0 && !op.endsWith("Buffer")) unknownSourceCalls++;
        switch (op) {
            case "buffer": {
                int id = next++;
                buffers.put(id, new Buffer("", null));
                return id;
            }
            case "source": {
                int id = next++;
                sources.put(id, new Source());
                mixing = sources.values().toArray(new Source[0]);
                return id;
            }
            case "deleteBuffer": {
                Buffer gone = buffers.remove(a);
                if (gone != null) {
                    gone.deleted = true;
                    if (gone.pcm != null) decodedBytes -= gone.pcm.bytes();
                    gone.pcm = null;
                    if (!gone.path.isEmpty()) byPath.remove(gone.path);
                }
                return 0;
            }
            case "deleteSource":
                if (s != null) {
                    sources.remove(a);
                    mixing = sources.values().toArray(new Source[0]);
                }
                return 0;
            case "sourcei":
                if (s == null) return 0;
                if (b == AL_BUFFER) {
                    stopNode(s);
                    s.buffer = buffers.get((int) c);
                    s.music = false;
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
        s.started = false;
        s.waited = 0;
        Buffer b = s.buffer;
        if (b == null || b.deleted || b.path.isEmpty()) {
            s.state = AL_STOPPED;
            return;
        }
        // A file the game does not have never plays: AL_INITIAL rather than AL_STOPPED, so a missing music track is not
        // taken for one that ended (web/audio.mjs does the same).
        if (b.missing) {
            s.state = AL_INITIAL;
            return;
        }
        s.state = AL_PLAYING;
        // Freed since it was last heard, or its decode failed: asked for again. The source begins when it is there.
        if (b.pcm == null) request(b);
    }

    /** Ends the current playback; like the browser's node stop, the source's offset stays where it started. */
    private void stopNode(Source s) {
        s.started = false;
    }

    private double position(Source s) {
        if (s.state != AL_PLAYING || !s.started) return s.offset;
        Buffer b = s.buffer;
        Pcm pcm = b == null ? null : b.pcm;
        return pcm == null ? s.offset : s.cursor / pcm.rate;
    }

    /** One line about a sound, for the debug console: unknown, ready, waiting, freed, failed or missing. */
    public synchronized String describe(String path) {
        Integer id = byPath.get(path);
        Buffer b = id == null ? null : buffers.get(id);
        if (b == null) return path + " unknown";
        String state = b.pcm != null ? "ready" : b.pending ? "decoding" : b.missing ? "missing" : b.failures > 0 ? "failed" : b.freed ? "freed" : "new";
        return path + " id=" + id + " " + state + " failures=" + b.failures + " inUse=" + inUse(b);
    }

    /** The mixer's totals since this process started or the last reset, for the debug console. */
    public synchronized String stats() {
        int playing = 0, waiting = 0, known = 0, held = 0;
        for (Source s : sources.values()) {
            if (s.state != AL_PLAYING) continue;
            if (s.buffer != null && s.buffer.pcm != null) playing++;
            else waiting++;
        }
        for (Buffer b : buffers.values()) {
            if (!b.path.isEmpty()) known++;
            if (b.pcm != null) held++;
        }
        return "paused=" + paused + " muted=" + muted + " sources=" + sources.size() + " playing=" + playing + " waitingForDecode=" + waiting
            + " sounds=" + known + " held=" + held + " heldMB=" + (decodedBytes >> 20) + " cacheMB=" + (cacheBytes >> 20)
            + " decoded=" + decodedCount + " failed=" + failedCount + " freed=" + freedCount + " decodedAgain=" + reloadedCount
            + " givenUpWaiting=" + staleCount + " mixErrors=" + mixErrors + " outputReopened=" + reopens
            + " unknownSourceCalls=" + unknownSourceCalls + " audibleBlocks=" + audibleBlocks + " peak=" + peak;
    }

    // ---- mixing ----

    /**
     * Mixes one block and hands it to the output; the mixing thread calls this for as long as it runs. False when the
     * output did not take the block: the caller waits a moment before the next one. An output that reports an error (a
     * dead AudioTrack, after the audio device or the audio server changed), or takes nothing for about a second, is
     * opened anew, so sound comes back by itself. Nothing is mixed for an output that took nothing: the sources would run
     * to their ends unheard.
     */
    public boolean pump(Output output) {
        java.util.Arrays.fill(mix, 0);
        if (!paused && outputTakes) {
            synchronized (this) { mixInto(mix); }
        }
        boolean silent = paused || muted;
        float blockPeak = 0;
        for (int i = 0; i < mix.length; i++) {
            float v = silent ? 0 : mix[i] * masterGain;
            blockPeak = Math.max(blockPeak, Math.abs(v));
            out[i] = (short) Math.max(-32768, Math.min(32767, Math.round(v * 32767f)));
        }
        if (blockPeak > 0) {
            audibleBlocks++;
            peak = Math.max(peak, blockPeak);
        }
        int n = output.write(out, out.length);
        if (n >= out.length) {
            badWrites = 0;
            outputTakes = true;
            return true;
        }
        outputTakes = false;
        // An error: at once, then once in every BAD_WRITES blocks. Nothing taken: after BAD_WRITES blocks.
        boolean reopen = n < 0 ? badWrites % BAD_WRITES == 0 : badWrites % BAD_WRITES == BAD_WRITES - 1;
        badWrites++;
        if (reopen) {
            reopens++;
            try {
                output.reopen();
            } catch (Exception e) {
                log.error("The audio output could not be opened again (write gave " + n + ")", e);
            }
        }
        return false;
    }

    private void mixInto(float[] mix) {
        long now = nanos.getAsLong();
        Source[] all = mixing;
        for (int k = 0; k < all.length; k++) {
            Source s = all[k];
            if (s.state != AL_PLAYING) continue;
            Buffer b = s.buffer;
            if (b == null || b.deleted) {
                s.state = AL_STOPPED;
                continue;
            }
            Pcm pcm = b.pcm;
            if (pcm == null) {
                if (b.missing) {
                    s.state = AL_INITIAL;
                    continue;
                }
                // Not decoded yet. A failed decode's next try starts here once it is due.
                if (!b.pending) request(b);
                if (!s.loop && !s.music && ++s.waited > STALE_BLOCKS) {
                    s.state = AL_STOPPED;
                    s.offset = 0;
                    staleCount++;
                    log.debug("gave up waiting for " + b.path);
                }
                continue;
            }
            b.lastUsed = now;
            try {
                mixSource(s, pcm, mix);
            } catch (RuntimeException e) {
                // A sound whose data does not fit what it says: that source ends, the others and the thread go on.
                s.state = AL_STOPPED;
                s.offset = 0;
                s.started = false;
                mixErrors++;
            }
        }
    }

    private static void mixSource(Source s, Pcm pcm, float[] mix) {
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
            // "not below" rather than "at or past": a position that is not a number ends the sound instead of holding it.
            if (!(s.cursor < pcm.frames)) {
                if (s.loop && pcm.frames > 0 && s.cursor >= pcm.frames) s.cursor %= pcm.frames;
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
