package ftl.browser;

import ftl.android.AndroidHost;
import xyz.znix.xftl.game.MainGame;

/** Engine diagnostics events; the Android host receives them instead of the page's debug log. */
public final class BrowserDebug {
    public static boolean enabled;
    /** Parity captures only: skip the random space backdrop so HUD pixels compare exactly. */
    public static boolean parityNoBackground;
    /** Parity imports only: arrive at the save's own beacon index instead of the sector start. */
    public static boolean paritySavedBeacon;
    public static long tick;

    private static void sink(String category, String name, String payload, String severity) {
        AndroidHost.get().debugEvent(category, name, payload, severity);
    }

    public static void mark() {
    }

    public static String quote(String s) {
        if (s == null) return "null";
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default: if (c < 32) out.append(' '); else out.append(c);
            }
        }
        return out.append('"').toString();
    }

    public static void event(String category, String name, String key, String value) {
        if (enabled) sink(category, name, "{" + quote(key) + ":" + quote(value) + "}", "info");
    }

    public static void record(String category, String name, String payload) {
        if (enabled) sink(category, name, payload, "info");
    }

    public static void warning(String message) {
        if (enabled) sink("engine", "warning", "{\"message\":" + quote(message) + "}", "warn");
    }

    public static String snapshot(MainGame game) {
        return BrowserDebugState.INSTANCE.snapshot(game);
    }

    /** The browser mirrors stdout into its debug log; Android's stdout already goes to logcat. */
    public static void installOutputBridge() {
    }
}
