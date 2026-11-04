package xyz.znix.xftl.game;

import org.teavm.jso.JSBody;

/** Browser facts for the language start ([LanguageSelectState]). */
final class LanguageJs {
    private LanguageJs() {}

    /**
     * navigator.webdriver: the page is driven by Playwright (the test and capture runs); window.ftlTestRuns: the website's
     * Tests page, whose temporary runs are those same fixtures in a player's own browser (no webdriver there).
     */
    @JSBody(script = "return !!(typeof navigator !== 'undefined' && navigator.webdriver) || !!(typeof window !== 'undefined' && window.ftlTestRuns);")
    static native boolean automated();
}
