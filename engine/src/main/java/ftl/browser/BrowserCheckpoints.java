package ftl.browser;
import org.teavm.jso.JSBody;
public final class BrowserCheckpoints {
    @JSBody(params={"name","xml"},script="window.ftlCheckpointPut(name,xml);") public static native void put(String name,String xml);
    @JSBody(params="name",script="return window.ftlCheckpointGet(name);") public static native String get(String name);
    @JSBody(params="xml",script="window.ftlCheckpointDownload(xml);") public static native void download(String xml);
    @JSBody(script="window.ftlCheckpointImport();") public static native void importFile();
}
