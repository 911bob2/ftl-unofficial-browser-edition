package ftl.android;

import java.util.Collections;
import java.util.List;

/**
 * How one game's mod scripts reach files and programs (docs/HS_LUA.md, "Files, programs and the sandbox"), set by the
 * app before the engine starts, read by LuaJs.reinit. folder: the account's private folder of mod files (one per
 * account on this device, kept in the app's storage). real: "Disable Lua sandboxing" is on; then the scripts in
 * sources (chunk names of the mods the player approved with that setting on) get real files under root (the app's own
 * storage) and /system/bin/sh. Everything else stays in the private folder.
 */
public final class LuaLibs {
    public static volatile LuaLibs current;

    public final String folder;
    public final boolean real;
    public final String root;
    public final List<String> sources;

    public LuaLibs(String folder, boolean real, String root, List<String> sources) {
        this.folder = folder;
        this.real = real;
        this.root = root;
        this.sources = Collections.unmodifiableList(sources);
    }
}
