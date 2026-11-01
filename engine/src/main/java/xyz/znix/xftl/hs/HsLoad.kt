package xyz.znix.xftl.hs

import ftl.browser.BrowserHsScan
import ftl.browser.BrowserXml
import org.jdom2.Document

/**
 * Loading mod data the vanilla loaders would reject. Vanilla play takes exactly the old path (one [HS.modded] read);
 * in a modded session a bad item is dropped and recorded (`HS.unsupported("parse", file, ...)`) instead of stopping the
 * engine, as FTL+HS skip or tolerate what they cannot use.
 */
object HsLoad {
    /**
     * FTL's rapidxml accepts several top-level elements (MV ship layouts, boss files, ...); our strict DOM parser
     * needs one root. In modded sessions such a file (found up front by [BrowserHsScan.needsRoot], so no strict parse
     * fails and Chrome logs no CSP errors for a parsererror document) is parsed with its content wrapped in one
     * `<FTL>` root, as Slipstream's patcher does before it re-wraps; every such MV 5.5.1 file parses that way. A file
     * the check passes but the strict parser still rejects is retried wrapped too (counted in [wrappedLate]).
     */
    @JvmStatic
    fun parseXml(name: String, text: String): Document {
        if (!HS.modded) return BrowserXml.parse(text)
        if (BrowserHsScan.needsRoot(text)) {
            wrapped++
            return BrowserXml.parse(wrap(text))
        }
        return try {
            BrowserXml.parse(text)
        } catch (e: Throwable) {
            wrappedLate++
            BrowserXml.parse(wrap(text))
        }
    }

    // (No static Regex: this object is touched in vanilla play, which must run no HS initialisers with work.)
    private fun wrap(text: String) = "<FTL>" + Regex("<\\?xml[^>]*\\?>|</?FTL\\s*>").replace(text, "") + "</FTL>"

    /** Files parsed wrapped (modded sessions), for the capability report. */
    @JvmField
    var wrapped = 0

    /** Files the up-front check missed: their strict parse failed first (and logged CSP errors). Should stay 0. */
    @JvmField
    var wrappedLate = 0

    /** Vanilla: run [block]; modded: return null and record the failure if it throws. */
    inline fun <T> item(file: String, what: () -> String, block: () -> T): T? {
        if (!HS.modded) return block()
        return try {
            block()
        } catch (e: Throwable) {
            HS.unsupported("parse", file, "${what()}: ${e.message ?: e.toString()}")
            null
        }
    }

    /**
     * An element a vanilla loader rejects by name. Vanilla: false (the caller fails as before); modded: true after
     * recording it, and the caller skips it (FTL's parsers ignore names they do not read).
     */
    @JvmStatic
    fun skipUnknown(file: String, name: String): Boolean {
        if (!HS.modded) return false
        HsCapability.ignore("$file|$name", 1)
        return true
    }
}
