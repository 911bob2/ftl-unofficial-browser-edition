package ftl.android.audio;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import ftl.android.GameFiles;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The engine's OpenAL calls (org.lwjgl.openal.AL10 through ftl.browser.BrowserAudio) on Android: the same source and
 * buffer model as the browser's Web Audio host (web/audio.mjs), mixed in software into one AudioTrack.
 *
 * <p>Sounds are decoded on a background thread into buffers the Java heap holds. A source started before its buffer
 * is decoded begins when the buffer is ready, as in the browser. Positions are seconds, pitch scales the rate. The
 * sources, the sounds and every rule about them are MixerCore's; this class gives it the decoder, the threads and the
 * AudioTrack.
 */
public final class AudioMixer {
    private static final int RATE = MixerCore.RATE, BLOCK = MixerCore.BLOCK;
    /** Decoded PCM kept beyond the sounds that are playing, in bytes: a share of the Java heap, where a direct buffer is an array. */
    private static final long CACHE_BYTES = Math.min(192L << 20, Runtime.getRuntime().maxMemory() / 4);
    /** "adb shell setprop log.tag.FTL-Audio DEBUG" logs each sound as it decodes; [stats] prints the totals. */
    private static final String TAG = "FTL-Audio";

    private static AudioMixer instance;

    public static synchronized AudioMixer get() {
        if (instance == null) instance = new AudioMixer();
        return instance;
    }

    // Music takes far longer to decode than an effect, so an effect never queues behind a track.
    private final ExecutorService decoder = decodeThread("ftl-audio-decode"), musicDecoder = decodeThread("ftl-music-decode");
    private final MixerCore core = new MixerCore(CACHE_BYTES, decoder, musicDecoder, AudioMixer::gameFiles, System::nanoTime, new MixerCore.Log() {
        @Override public void debug(String message) {
            if (android.util.Log.isLoggable(TAG, android.util.Log.DEBUG)) android.util.Log.d(TAG, message);
        }

        @Override public void error(String message, Throwable e) {
            android.util.Log.e("FTL", message, e);
        }
    });
    private AudioTrack track;
    private Thread thread;
    private volatile boolean running;
    private int loopErrors;

    private final MixerCore.Output output = new MixerCore.Output() {
        @Override public int write(short[] data, int length) {
            AudioTrack t = track;
            try {
                return t == null ? 0 : t.write(data, 0, length);
            } catch (IllegalStateException e) {
                // A track that never initialised or was released under us: an error like any other.
                return AudioTrack.ERROR;
            }
        }

        @Override public void reopen() {
            synchronized (AudioMixer.this) {
                if (!running) return;
                AudioTrack old = track;
                track = null;
                if (old != null) {
                    try { old.release(); } catch (RuntimeException ignored) {}
                }
                track = open();
                android.util.Log.w(TAG, "The audio output stopped taking sound and was opened again");
            }
        }
    };

    private AudioMixer() {
    }

    private static ExecutorService decodeThread(String name) {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
    }

    /** The files of the game that is running now, decoded. */
    private static MixerCore.Loader gameFiles() {
        GameFiles files = GameFiles.current();
        return (path, stopped) -> {
            byte[] bytes = files.audio(path);
            if (bytes == null) throw new MixerCore.MissingAudio(path);
            return PcmDecoder.decode(bytes, stopped);
        };
    }

    // ---- lifecycle (activity) ----

    public synchronized void start() {
        if (running) return;
        track = open();
        running = true;
        thread = new Thread(this::loop, "ftl-audio-mix");
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
    }

    private static AudioTrack open() {
        int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack t = new AudioTrack.Builder()
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(Math.max(min, BLOCK * 4 * 4))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM).build();
        t.play();
        return t;
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
                try { track.stop(); } catch (IllegalStateException ignored) {}
                track.release();
                track = null;
            }
        }
    }

    /** Silence while the app is in the background; sources keep their state. */
    public void setPaused(boolean value) { core.setPaused(value); }

    /** Silence while another app has the audio focus and the game goes on: the sources play on unheard. */
    public void setMuted(boolean value) { core.setMuted(value); }

    /** Ends a game (engine shutdown), see MixerCore.reset. */
    public void reset() { core.reset(); }

    // ---- the browser host's API ----

    public int buffer(String path) { return core.buffer(path); }

    public void music(int id, String path) { core.music(id, path); }

    public double call(String op, int a, int b, double c) { return core.call(op, a, b, c); }

    /** The mixer's totals since this process started or the last reset, for the debug console. */
    public String stats() { return "running=" + running + " " + core.stats(); }

    /** Debug console: one sound's state. */
    public String describe(String path) { return core.describe(path); }

    /** Debug console: another cache size, to see sounds freed and decoded again without a small phone. */
    public void setCacheBytes(long bytes) { core.setCacheBytes(bytes); }

    // ---- mixing ----

    /**
     * Nothing may end this thread while the game runs: sound would be gone until the game is started again. So an
     * output that refuses a block is waited for (MixerCore opens it anew), and whatever is thrown is logged and the
     * loop goes on.
     */
    private void loop() {
        while (running) {
            try {
                if (!core.pump(output)) Thread.sleep(20);
            } catch (InterruptedException e) {
                // stop() does not interrupt; go on until it says so.
            } catch (Throwable e) {
                try {
                    if (loopErrors++ < 5) android.util.Log.e("FTL", "Audio mixer", e);
                    Thread.sleep(50);
                } catch (Throwable ignored) {
                    // Out of memory again while saying so, or interrupted.
                }
            }
        }
    }
}
