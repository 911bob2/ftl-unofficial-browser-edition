package ftl.browser;

/** Java heap in use, in bytes, for the full-run diagnostics (the browser reports the JS heap). */
public final class BrowserHeap {
    private BrowserHeap() {
    }

    public static double used() {
        Runtime r = Runtime.getRuntime();
        return r.totalMemory() - r.freeMemory();
    }
}
