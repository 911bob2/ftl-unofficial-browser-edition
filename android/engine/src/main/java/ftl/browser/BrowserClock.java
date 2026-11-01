package ftl.browser;

/** A monotonic millisecond clock, for slicing work across frames (Android twin of the browser's performance.now()). */
public final class BrowserClock {
    private BrowserClock() {
    }

    public static double now() {
        return System.nanoTime() / 1_000_000.0;
    }
}
