package ftl.browser;

import ftl.android.AndroidHost;

/** Named debug checkpoints (the console's checkpoint commands); stored by the Android host. */
public final class BrowserCheckpoints {
    private BrowserCheckpoints() {
    }

    public static void put(String name, String xml) {
        if (!name.matches("[\\w-]{1,64}"))
            throw new IllegalArgumentException("Use a checkpoint name of 1 to 64 letters, digits, hyphens or underscores");
        AndroidHost.get().checkpointPut(name, xml);
    }

    public static String get(String name) {
        return AndroidHost.get().checkpointGet(name);
    }

    public static void download(String xml) {
        AndroidHost.get().checkpointExport(xml);
    }

    public static void importFile() {
        AndroidHost.get().checkpointImport();
    }
}
