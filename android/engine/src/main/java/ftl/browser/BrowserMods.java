package ftl.browser;

import ftl.android.ModSession;

/** The mod session the launcher prepared before start, or nothing for vanilla play. Read once per engine start. */
public final class BrowserMods {
    private BrowserMods() {
    }

    public static boolean modded() {
        ModSession s = ModSession.current;
        return s != null && !s.mods.isEmpty();
    }

    public static boolean hyperspace() {
        ModSession s = ModSession.current;
        return s != null && s.hyperspace;
    }

    public static String hash() {
        ModSession s = ModSession.current;
        return s == null ? null : s.hash;
    }

    public static String hsVersion() {
        ModSession s = ModSession.current;
        return s == null ? null : s.hsVersion;
    }

    /** Lua script paths in load order, newline-separated ("" when none). */
    public static String scripts() {
        ModSession s = ModSession.current;
        return s == null ? "" : String.join("\n", s.scripts);
    }

    /** Display names of the loaded archives in order, newline-separated. */
    public static String names() {
        ModSession s = ModSession.current;
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (ModSession.Mod m : s.mods) {
            if (b.length() > 0) b.append('\n');
            b.append(m.name);
        }
        return b.toString();
    }
}
