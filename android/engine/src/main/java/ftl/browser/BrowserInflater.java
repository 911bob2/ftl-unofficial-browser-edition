package ftl.browser;

import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** PNG decoder's streaming zlib bridge; on Android the platform Inflater does the work. */
public final class BrowserInflater {
    private final Inflater inflater = new Inflater();

    public void setInput(byte[] bytes, int offset, int length) {
        // A copy: the caller reuses the array while the inflater still holds the input.
        inflater.setInput(java.util.Arrays.copyOfRange(bytes, offset, offset + length), 0, length);
    }

    public int inflate(byte[] bytes, int offset, int length) throws DataFormatException {
        return inflater.inflate(bytes, offset, length);
    }

    public boolean needsInput() {
        return inflater.needsInput();
    }

    public boolean finished() {
        return inflater.finished();
    }

    public void end() {
        inflater.end();
    }
}
