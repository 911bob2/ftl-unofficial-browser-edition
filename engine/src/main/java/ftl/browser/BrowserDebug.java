package ftl.browser;
import org.teavm.jso.JSBody;
import xyz.znix.xftl.game.MainGame;
public final class BrowserDebug {
    public static boolean enabled;
    /** Parity captures only: skip the random space backdrop so HUD pixels compare exactly. */
    public static boolean parityNoBackground;
    /** Parity imports only: arrive at the save's own beacon index instead of the sector start. */
    public static boolean paritySavedBeacon;
    public static long tick;
    @JSBody(params={"category","name","payload","severity"},script="if(window.ftlDebugEvent)window.ftlDebugEvent(category,name,payload,severity);")
    private static native void sink(String category,String name,String payload,String severity);
    @JSBody(script="if(window.ftlDebugMark)window.ftlDebugMark();") public static native void mark();
    public static String quote(String s) {
        if(s==null)return "null";
        StringBuilder out=new StringBuilder("\"");
        for(int i=0;i<s.length();i++){char c=s.charAt(i);switch(c){case '"':out.append("\\\"");break;case '\\':out.append("\\\\");break;case '\n':out.append("\\n");break;case '\r':out.append("\\r");break;case '\t':out.append("\\t");break;default:if(c<32)out.append(' ');else out.append(c);}}
        return out.append('"').toString();
    }
    public static void event(String category,String name,String key,String value){if(enabled)sink(category,name,"{"+quote(key)+":"+quote(value)+"}","info");}
    public static void record(String category,String name,String payload){if(enabled)sink(category,name,payload,"info");}
    public static void warning(String message){if(enabled)sink("engine","warning","{\"message\":"+quote(message)+"}","warn");}
    public static String snapshot(MainGame game){return BrowserDebugState.INSTANCE.snapshot(game);}
    private static boolean installed;
    public static void installOutputBridge() {
        if(installed)return; installed=true;
        System.setOut(new java.io.PrintStream(new DiagnosticOutput(System.out),true));
        System.setErr(new java.io.PrintStream(new DiagnosticOutput(System.err),true));
    }
    private static final class DiagnosticOutput extends java.io.OutputStream {
        private final java.io.PrintStream original;
        private final java.io.ByteArrayOutputStream line=new java.io.ByteArrayOutputStream();
        DiagnosticOutput(java.io.PrintStream original){this.original=original;}
        public void write(int b) {
            original.write(b);
            if(!enabled){line.reset();return;}
            if(b=='\n'){warning(line.toString(java.nio.charset.StandardCharsets.UTF_8));line.reset();}
            else if(line.size()<16384)line.write(b);
        }
        public void flush(){original.flush();}
    }
}
