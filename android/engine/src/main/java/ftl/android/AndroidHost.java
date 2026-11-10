package ftl.android;

/**
 * What the engine asks of the Android app around it: the counterpart of the page callbacks the browser build calls
 * (window.ftlOnSave, ftlDebugEvent, ftlSetCursor, ftlExit, checkpoints, the fullscreen toggle, Lua page logs).
 * The app installs its implementation before the engine starts; until then every call is a no-op.
 */
public interface AndroidHost {
    /** A save the engine just wrote: kind "run" or "profile", the engine's XML, or null when the engine removed it. Called on the game thread. */
    default void onSave(String kind, String xml) {}

    default void debugEvent(String category, String name, String payload, String severity) {}

    default void checkpointPut(String name, String xml) {}

    default String checkpointGet(String name) { return null; }

    default void checkpointExport(String xml) {}

    default void checkpointImport() {}

    /** The engine's cursor key (main[,overlay][#frame]); touch play shows none. */
    default void setCursor(String key) {}

    /** The engine quit (main menu Quit): the app returns to its launcher. */
    default void exit() {}

    default boolean isFullscreen() { return true; }

    default void toggleFullscreen() {}

    /** Haptic feedback of the touch HUD: count ticks of a TouchHaptics kind, in one gesture step. Called on the game thread. */
    default void haptic(int kind, int count) {}

    default void openUrl(String url) {}

    default void setWindowTitle(String title) {}

    /** Hyperspace Lua log lines: kind 0 debug, 1 info, 2 warning, 3 error. */
    default void luaLog(int kind, String text) {}

    /** A mod script called os.exit (the original closes FTL): source is the script's chunk name. Called on the game thread. */
    default void luaExit(String source) {}

    AndroidHost NONE = new AndroidHost() {};

    static AndroidHost get() { return Holder.host; }

    static void set(AndroidHost host) { Holder.host = host == null ? NONE : host; }

    final class Holder {
        private Holder() {}

        static volatile AndroidHost host = NONE;
    }
}
