package ftl.browser;

import org.teavm.jso.JSBody;

/** JS heap size in bytes (Chromium's performance.memory, 0 elsewhere), for the full-run diagnostics. */
public final class BrowserHeap {
    private BrowserHeap() {
    }

    @JSBody(script = "return (performance && performance.memory) ? performance.memory.usedJSHeapSize : 0;")
    public static native double used();

    /** Live heap: collects first when the browser exposes gc (--js-flags=--expose-gc), for the heap measurements. */
    @JSBody(script = "if (typeof gc === 'function') gc(); return (performance && performance.memory) ? performance.memory.usedJSHeapSize : 0;")
    public static native double usedAfterGc();
}
