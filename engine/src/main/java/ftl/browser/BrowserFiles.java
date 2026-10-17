package ftl.browser;
import java.util.*;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSByRef;
import org.teavm.jso.typedarrays.Uint8Array;
public final class BrowserFiles {
    public static boolean enableAE = true;
    private static String run, profile;
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
    public static void setRun(String xml) {if(ignoreTemporaryWrite("run"))return; run=xml; changed("run",xml);}
    public static void setProfile(String xml) {if(ignoreTemporaryWrite("profile"))return; profile=xml; changed("profile",xml);}
    private static boolean ignoreTemporaryWrite(String kind) {
        if (!temporaryWrites) return false;
        BrowserDebug.record("persistence", "save.ignored", "{\"kind\":"+BrowserDebug.quote(kind)+",\"reason\":\"tutorial\"}");
        return true;
    }
    public static void hydrate(String r,String p) {run=r;profile=p;}
    @JSBody(params={"kind","xml"},script="if(window.ftlOnSave)window.ftlOnSave(kind,xml);")
    private static native void changed(String kind,String xml);
    /** One bulk copy: a call per byte through JSO is slow for the megabytes of an image. */
    @JSBody(params={"a","dst"},script="dst.set(a);")
    private static native void copyOut(Uint8Array a,@JSByRef byte[] dst);
    @JSBody(params="name",script="return window.ftlFiles.get(name) || null;")
    private static native Uint8Array bytes(String name);
    @JSBody(script="return Array.from(window.ftlFiles.keys()).join('\\n');")
    private static native String list();
    public static List<String> names() {return Arrays.asList(list().split("\n"));}
    public static boolean contains(String name) {return bytes(name)!=null;}
    public static byte[] read(String name) {
        Uint8Array a=bytes(name);if(a==null)throw new IllegalArgumentException("Missing resource: "+name);
        byte[] b=new byte[a.getLength()];copyOut(a,b);return b;
    }
}
