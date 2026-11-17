package ftl.android.audio;

import java.nio.ShortBuffer;

/** Decoded 16-bit PCM, interleaved, in a direct buffer. */
public final class Pcm {
    final ShortBuffer data;
    final int channels, rate, frames;

    public Pcm(ShortBuffer data, int channels, int rate) {
        this.data = data;
        this.channels = channels;
        this.rate = rate;
        this.frames = data.capacity() / channels;
    }

    long bytes() {
        return (long) data.capacity() * 2;
    }
}
