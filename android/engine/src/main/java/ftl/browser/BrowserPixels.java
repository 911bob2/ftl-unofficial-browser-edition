package ftl.browser;

import android.opengl.GLES30;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Reads back drawn pixels, for the checks that compare what the engine draws with the picture it should be. */
public final class BrowserPixels {
    private BrowserPixels() {
    }

    /** The RGBA pixels of a rectangle in game coordinates (top-left origin), rows top to bottom. */
    public static byte[] read(int x, int y, int w, int h) {
        xyz.znix.xftl.rendering.DrawQueue.drain();
        int[] viewport = new int[4];
        GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, viewport, 0);
        ByteBuffer rows = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
        GLES30.glReadPixels(x, viewport[3] - y - h, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, rows);
        byte[] out = new byte[w * h * 4];
        for (int r = 0; r < h; r++) {
            rows.position((h - 1 - r) * w * 4);
            rows.get(out, r * w * 4, w * 4);
        }
        return out;
    }
}
