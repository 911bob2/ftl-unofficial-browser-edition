package ftl.browser;

import org.teavm.jso.JSBody;

/** A monotonic millisecond clock, for slicing work across frames. */
public final class BrowserClock {
    private BrowserClock() {
    }

    @JSBody(script = "return performance.now();")
    public static native double now();
}
