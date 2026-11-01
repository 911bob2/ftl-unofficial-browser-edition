package ftl.browser;

/** Diagnostics: the browser adds the JS stack of a Java exception; on Android the Java stack trace is all there is. */
final class BrowserJsStack {
    private BrowserJsStack() {
    }

    static String of(Throwable e) {
        return "";
    }
}
