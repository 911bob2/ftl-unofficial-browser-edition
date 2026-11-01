package ftl.browser;

import ftl.android.AndroidHost;
import ftl.android.GameFiles;
import java.util.List;

/** Game files, the run/profile save and the settings the engine reads and writes (Android twin of the page's window.ftlFiles). */
public final class BrowserFiles {
    public static boolean enableAE = true;
    private static String run, profile, settings;
    private static boolean temporaryWrites;

    /** Tutorial actions can use the normal save paths without publishing progress. */
    public static void beginTemporaryWrites() {
        if (temporaryWrites) throw new IllegalStateException("A temporary session is already active");
        temporaryWrites = true;
    }

    public static void endTemporaryWrites() { temporaryWrites = false; }

    public static boolean isTemporaryWrites() { return temporaryWrites; }

    public static String getRun() { return run; }

    public static String getProfile() { return profile; }

    /** The device's settings (settings.ini in the original): language, volumes, hotkeys, options. Null until first written. */
    public static String getSettings() { return settings; }

    public static void setRun(String xml) {
        if (ignoreTemporaryWrite("run")) return;
        run = xml;
        AndroidHost.get().onSave("run", xml);
    }

    public static void setProfile(String xml) {
        if (ignoreTemporaryWrite("profile")) return;
        profile = xml;
        AndroidHost.get().onSave("profile", xml);
    }

    public static void setSettings(String xml) {
        if (ignoreTemporaryWrite("settings")) return;
        settings = xml;
        AndroidHost.get().onSave("settings", xml);
    }

    private static boolean ignoreTemporaryWrite(String kind) {
        if (!temporaryWrites) return false;
        BrowserDebug.record("persistence", "save.ignored", "{\"kind\":" + BrowserDebug.quote(kind) + ",\"reason\":\"tutorial\"}");
        return true;
    }

    public static void hydrate(String r, String p) { run = r; profile = p; }

    public static void hydrateSettings(String s) { settings = s; }

    public static List<String> names() { return GameFiles.current().names(); }

    public static boolean contains(String name) { return GameFiles.current().contains(name); }

    public static byte[] read(String name) {
        byte[] b = GameFiles.current().read(name);
        if (b == null) throw new IllegalArgumentException("Missing resource: " + name);
        return b;
    }
}
