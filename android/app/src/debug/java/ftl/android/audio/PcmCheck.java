package ftl.android.audio;

import android.util.Log;
import ftl.android.GameFiles;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import org.newdawn.slick.openal.OggInputStream;

/** Debug builds only: the decoder's samples against a plain read of the same Ogg stream. */
public final class PcmCheck {
    private PcmCheck() {
    }

    public static void run(String path) throws Exception {
        byte[] file = GameFiles.current().audio(path);
        if (path.endsWith(".wav")) {
            // A WAV file: our reader against the platform's decoder, sample for sample.
            Pcm ours = PcmDecoder.decodeWav(file);
            Pcm theirs = PcmDecoder.decodePlatform(file, () -> false);
            boolean equal = ours != null && ours.channels == theirs.channels && ours.rate == theirs.rate && ours.data.capacity() == theirs.data.capacity();
            for (int i = 0; equal && i < ours.data.capacity(); i++) equal = ours.data.get(i) == theirs.data.get(i);
            Log.i("FTL-Mem", "pcmcheck " + path + " wav ours=" + (ours == null ? "refused" : ours.data.capacity() + " shorts " + ours.channels + " ch " + ours.rate + " Hz")
                + " platform=" + theirs.data.capacity() + " shorts " + theirs.channels + " ch " + theirs.rate + " Hz same=" + equal);
            return;
        }
        Pcm pcm = PcmDecoder.decode(file, () -> false);
        ByteArrayOutputStream plain = new ByteArrayOutputStream();
        try (OggInputStream in = new OggInputStream(new ByteArrayInputStream(file))) {
            byte[] chunk = new byte[1 << 16];
            int n;
            while ((n = in.read(chunk)) > 0) plain.write(chunk, 0, n);
        }
        byte[] bytes = plain.toByteArray();
        ShortBuffer want = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        int size = want.remaining() - want.remaining() % pcm.channels;
        boolean same = pcm.data.capacity() == size;
        for (int i = 0; same && i < size; i++) same = pcm.data.get(i) == want.get(i);
        Log.i("FTL-Mem", "pcmcheck " + path + " shorts=" + pcm.data.capacity() + " want=" + size + " same=" + same);
    }
}
