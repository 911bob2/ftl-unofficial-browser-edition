package ftl.android.audio;

import java.nio.ShortBuffer;

/** Decoded 16-bit PCM, interleaved, in a direct buffer. */
final class Pcm {
    final ShortBuffer data;
    final int channels, rate, frames;

    Pcm(ShortBuffer data, int channels, int rate) {
        this.data = data;
        this.channels = channels;
        this.rate = rate;
        this.frames = data.capacity() / channels;
    }

    long bytes() {
        return (long) data.capacity() * 2;
    }
}
