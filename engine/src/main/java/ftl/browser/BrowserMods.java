package ftl.browser;
import org.teavm.jso.JSBody;
/**
 * The mod session the page prepared before {@code start} (see .build/hs/PLAN.md I1): {@code window.ftlModSession},
 * or nothing for vanilla play. Read once per engine start; vanilla never reaches any mod code.
 */
public final class BrowserMods {
    private BrowserMods() {}
    @JSBody(script="var s=window.ftlModSession;return !!(s&&s.mods&&s.mods.length);")
    public static native boolean modded();
    @JSBody(script="var s=window.ftlModSession;return !!(s&&s.hyperspace);")
    public static native boolean hyperspace();
    @JSBody(script="var s=window.ftlModSession;return s&&s.hash?String(s.hash):null;")
    public static native String hash();
    @JSBody(script="var s=window.ftlModSession;return s&&s.hsVersion?String(s.hsVersion):null;")
    public static native String hsVersion();
    /** Lua script paths in load order, newline-separated ("" when none). */
    @JSBody(script="var s=window.ftlModSession;return s&&s.scripts?s.scripts.join('\\n'):'';")
    public static native String scripts();
    /** Display names of the loaded archives in order, newline-separated. */
    @JSBody(script="var s=window.ftlModSession;return s&&s.mods?s.mods.map(function(m){return String(m.name);}).join('\\n'):'';")
    public static native String names();
}
