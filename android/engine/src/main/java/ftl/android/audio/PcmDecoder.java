package ftl.android.audio;

import android.media.MediaCodec;
import android.media.MediaDataSource;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import org.newdawn.slick.openal.OggInputStream;

/**
 * Sound files to PCM. Ogg Vorbis goes through the engine's Java decoder: the platform's codec gives back one packet per
 * round trip, which took a four minute track about 90 s on the emulator, so music started that late. Plain PCM in a
 * WAV file is read here too: the platform's codec took 260 to 550 ms to set up for a click of 30 ms (emulator), so a
 * button's sound came that late the first time, and again each time it had been freed. Anything else, or a file those
 * two refuse, goes through the platform's.
 */
final class PcmDecoder {
    /** The platform's codec may go this long without taking or giving anything before the decode is given up. */
    private static final long STALL_NANOS = 10_000_000_000L;

    private PcmDecoder() {
    }

    private static final class Bytes extends MediaDataSource {
        private final byte[] bytes;

        Bytes(byte[] bytes) { this.bytes = bytes; }

        @Override public int readAt(long position, byte[] buffer, int offset, int size) {
            if (position >= bytes.length) return -1;
            int n = (int) Math.min(size, bytes.length - position);
            System.arraycopy(bytes, (int) position, buffer, offset, n);
            return n;
        }

        @Override public long getSize() { return bytes.length; }

        @Override public void close() {}
    }

    /** Returns null when stopped says so midway. */
    static Pcm decode(byte[] file, java.util.function.BooleanSupplier stopped) throws IOException {
        if (file.length > 4 && file[0] == 'O' && file[1] == 'g' && file[2] == 'g' && file[3] == 'S') {
            try {
                return decodeOgg(file, stopped);
            } catch (IOException | RuntimeException e) {
                android.util.Log.w("FTL-Audio", "Java Ogg decoder refused a file: " + e);
            }
        }
        Pcm wav = decodeWav(file);
        return wav != null ? wav : decodePlatform(file, stopped);
    }

    private static boolean tag(byte[] f, int at, String name) {
        return f[at] == name.charAt(0) && f[at + 1] == name.charAt(1) && f[at + 2] == name.charAt(2) && f[at + 3] == name.charAt(3);
    }

    private static int le16(byte[] f, int at) {
        return (f[at] & 0xff) | (f[at + 1] & 0xff) << 8;
    }

    private static int le32(byte[] f, int at) {
        return le16(f, at) | le16(f, at + 2) << 16;
    }

    /**
     * Uncompressed PCM, 8 or 16 bit, mono or stereo, in a RIFF file; null for anything else (the platform's decoder gets
     * it). 16 bit samples as they are, 8 bit ones (unsigned) centred and widened. A data chunk that says more than the
     * file holds gives what is there (some of the game's files have that, or bytes after it).
     */
    static Pcm decodeWav(byte[] f) {
        if (f.length < 44 || !tag(f, 0, "RIFF") || !tag(f, 8, "WAVE")) return null;
        int format = 0, channels = 0, rate = 0, bits = 0;
        int at = 12;
        while (at + 8 <= f.length) {
            int length = le32(f, at + 4), body = at + 8;
            if (length < 0) return null;
            if (tag(f, at, "fmt ")) {
                if (length < 16 || body + 16 > f.length) return null;
                format = le16(f, body);
                channels = le16(f, body + 2);
                rate = le32(f, body + 4);
                bits = le16(f, body + 14);
            } else if (tag(f, at, "data")) {
                if (format != 1 || (bits != 8 && bits != 16) || channels < 1 || channels > 2 || rate <= 0) return null;
                int width = bits / 8;
                int bytes = Math.min(length, f.length - body);
                int samples = (bytes - bytes % (width * channels)) / width;
                if (samples == 0) return null;
                ShortBuffer out = ByteBuffer.allocateDirect(samples * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
                if (bits == 16) for (int i = 0; i < samples; i++) out.put(i, (short) le16(f, body + 2 * i));
                else for (int i = 0; i < samples; i++) out.put(i, (short) (((f[body + i] & 0xff) - 128) << 8));
                return new Pcm(out, channels, rate);
            }
            if (length > f.length - body) return null;
            at = body + length + (length & 1);
        }
        return null;
    }

    /**
     * Writes straight into the final buffer. A direct buffer is an ordinary array in ART's Java heap, so a track decoded
     * through a growing byte array and copied at the end needed three to four times its size at the peak, on top of
     * the sounds already cached.
     */
    private static Pcm decodeOgg(byte[] file, java.util.function.BooleanSupplier stopped) throws IOException {
        OggInputStream in = new OggInputStream(new ByteArrayInputStream(file));
        try {
            byte[] chunk = new byte[1 << 16];
            ByteBuffer out = null;
            int n;
            while ((n = in.read(chunk)) > 0) {
                if (stopped.getAsBoolean()) return null;
                if (out == null) out = pcmBuffer(oggPcmBytes(file, in.getChannels(), n));
                if (out.remaining() < n) {
                    ByteBuffer bigger = pcmBuffer(out.capacity() + Math.max(n, out.capacity() / 2));
                    out.flip();
                    bigger.put(out);
                    out = bigger;
                }
                out.put(chunk, 0, n);
            }
            if (out == null) throw new IOException("No sound in the file");
            out.limit(out.position() - out.position() % (2 * in.getChannels()));
            out.position(0);
            return new Pcm(out.asShortBuffer(), in.getChannels(), in.getRate());
        } finally {
            in.close();
        }
    }

    private static ByteBuffer pcmBuffer(int bytes) {
        return ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** The decoded size an Ogg file announces: the sample position on its last page. At least [atLeast] when it does not say. */
    private static int oggPcmBytes(byte[] file, int channels, int atLeast) {
        for (int i = file.length - 27; i >= 0 && i >= file.length - (1 << 17); i--) {
            if (file[i] != 'O' || file[i + 1] != 'g' || file[i + 2] != 'g' || file[i + 3] != 'S') continue;
            long samples = 0;
            for (int b = 7; b >= 0; b--) samples = samples << 8 | (file[i + 6 + b] & 0xff);
            long bytes = samples * channels * 2;
            return bytes > 0 && bytes <= Integer.MAX_VALUE - 8 ? (int) Math.max(bytes, atLeast) : atLeast;
        }
        return atLeast;
    }

    private static Pcm pcm(byte[] pcm, int channels, int rate) {
        ByteBuffer direct = ByteBuffer.allocateDirect(pcm.length - pcm.length % (2 * channels)).order(ByteOrder.nativeOrder());
        ShortBuffer source = ByteBuffer.wrap(pcm, 0, direct.capacity()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        ShortBuffer target = direct.asShortBuffer();
        target.put(source);
        target.flip();
        return new Pcm(target, channels, rate);
    }

    static Pcm decodePlatform(byte[] file, java.util.function.BooleanSupplier stopped) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(new Bytes(file));
            int track = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    format = f;
                    break;
                }
            }
            if (track < 0) throw new IOException("No audio track");
            extractor.selectTrack(track);
            int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            int rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format, null, null, 0);
            codec.start();
            ByteArrayOutputStream out = new ByteArrayOutputStream(file.length * 8);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false, outputDone = false;
            byte[] chunk = new byte[0];
            // A codec that neither takes input nor gives output would hold the one decoder thread for ever, and with it
            // every sound not decoded yet. The failed decode is tried again when the sound is next played (MixerCore).
            long progress = System.nanoTime();
            while (!outputDone) {
                if (stopped.getAsBoolean()) return null;
                if (System.nanoTime() - progress > STALL_NANOS) throw new IOException("The decoder stalled");
                if (!inputDone) {
                    int in = codec.dequeueInputBuffer(1_000);
                    if (in >= 0) {
                        progress = System.nanoTime();
                        ByteBuffer buffer = codec.getInputBuffer(in);
                        int size = extractor.readSampleData(buffer, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(in, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                // While there is input to give, a wait here would cost its full length for every packet.
                int outIndex = codec.dequeueOutputBuffer(info, inputDone ? 10_000 : 0);
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat f = codec.getOutputFormat();
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                } else if (outIndex >= 0) {
                    progress = System.nanoTime();
                    ByteBuffer buffer = codec.getOutputBuffer(outIndex);
                    if (info.size > 0 && buffer != null) {
                        if (chunk.length < info.size) chunk = new byte[info.size];
                        buffer.position(info.offset);
                        buffer.get(chunk, 0, info.size);
                        out.write(chunk, 0, info.size);
                    }
                    codec.releaseOutputBuffer(outIndex, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                }
            }
            return pcm(out.toByteArray(), channels, rate);
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (IllegalStateException ignored) {}
                codec.release();
            }
            extractor.release();
        }
    }
}
