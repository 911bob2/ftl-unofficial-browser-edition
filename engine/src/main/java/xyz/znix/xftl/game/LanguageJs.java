package xyz.znix.xftl.game;

import org.teavm.jso.JSBody;

/** Browser facts for the language start ([LanguageSelectState]). */
final class LanguageJs {
    private LanguageJs() {}

    /** navigator.webdriver: the page is driven by Playwright (the test and capture runs). */
    @JSBody(script = "return !!(typeof navigator !== 'undefined' && navigator.webdriver);")
    static native boolean automated();
}
