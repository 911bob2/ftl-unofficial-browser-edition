package ftl.browser;
import org.teavm.jso.JSBody;
/** The game canvas as a PNG data URL, read right after a frame is drawn (diagnostics that compare frames). */
public final class BrowserCanvasShot {
    private BrowserCanvasShot() {}
    @JSBody(script="return window.ftlGL.gl.canvas.toDataURL('image/png');")
    public static native String png();
}
