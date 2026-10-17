package ftl.browser;
import org.teavm.jso.*;
public final class BrowserAudio {
    @JSBody(params={"path"},script="return window.ftlAudio.buffer(path);") public static native int buffer(String path);
    @JSBody(params={"op","a","b","c"},script="return window.ftlAudio.call(op,a,b,c);") public static native double call(String op,int a,int b,double c);
    @JSBody(params={"source","path"},script="window.ftlAudio.music(source,path);") public static native void music(int source,String path);
}
