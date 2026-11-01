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
 * round trip, which took a four minute track about 90 s on the emulator, so music started that late. Anything else (WAV),
 * or an Ogg file that decoder refuses, goes through the platform's.
 */
final class PcmDecoder {
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
        return decodePlatform(file, stopped);
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

    private static Pcm decodePlatform(byte[] file, java.util.function.BooleanSupplier stopped) throws IOException {
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
            while (!outputDone) {
                if (stopped.getAsBoolean()) return null;
                if (!inputDone) {
                    int in = codec.dequeueInputBuffer(1_000);
                    if (in >= 0) {
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
