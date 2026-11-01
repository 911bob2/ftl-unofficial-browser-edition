package ftl.browser;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSObject;

/** Diagnostics: the JS stack of a Java exception thrown in the browser engine (TeaVM keeps it as $jsException). */
final class BrowserJsStack {
    private BrowserJsStack() {
    }

    @JSBody(params = "e", script = "var j = e && e.$jsException; return j && j.stack ? String(j.stack) : '';")
    private static native String stackOf(JSObject e);

    @JSBody(script = "var l = Error.stackTraceLimit; Error.stackTraceLimit = 32; var s = String(new Error().stack); Error.stackTraceLimit = l; return s;")
    static native String here();

    static String of(Throwable e) {
        try {
            return stackOf((JSObject) (Object) e);
        } catch (Throwable ignored) {
            return "";
        }
    }
}
