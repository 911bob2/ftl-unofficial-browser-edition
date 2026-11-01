package ftl.android;

import java.util.Collections;
import java.util.List;

/**
 * The mod session of one engine start (the browser's window.ftlModSession): the archives in patch order, the mod-set
 * hash, whether the merged files contain Hyperspace and the Lua scripts of its &lt;scripts&gt; blocks. Null for vanilla.
 */
public final class ModSession {
    public static volatile ModSession current;

    public final String hash;
    public final List<Mod> mods;
    public final boolean hyperspace;
    public final String hsVersion;
    public final List<String> scripts;

    public ModSession(String hash, List<Mod> mods, boolean hyperspace, String hsVersion, List<String> scripts) {
        this.hash = hash;
        this.mods = Collections.unmodifiableList(mods);
        this.hyperspace = hyperspace;
        this.hsVersion = hsVersion;
        this.scripts = Collections.unmodifiableList(scripts);
    }

    public static final class Mod {
        public final String name, version, sha256;
        public final long size;

        public Mod(String name, String version, String sha256, long size) {
            this.name = name;
            this.version = version;
            this.sha256 = sha256;
            this.size = size;
        }
    }
}
