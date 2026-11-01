package ftl.android.app.log

/**
 * What must never reach the debug log. The app itself logs no credentials; this is the second line, for an exception text or a
 * mod script's print that happens to carry one. Applied to every line before it is written.
 */
object Redact {
    private const val GONE = "[removed]"

    private val RULES: List<Pair<Regex, String>> = listOf(
        // Header lines, whole value: a cookie header holds many name=value pairs.
        Regex("(?i)\\b(set-cookie|cookie|authorization|proxy-authorization|x-csrf-token|x-xsrf-token|x-ftl-account|x-ftl-device|x-ftl-instance)\\b\"?\\s*[:=]\\s*.*") to "$1: $GONE",
        // The website's own cookies, by name.
        Regex("(?i)\\b(__Host-[\\w-]+|ftl-(?:csrf-|consent-|mode-)?local-\\d+)=\\S+") to "$1=$GONE",
        // Any key that names a secret, in JSON, form or plain text.
        Regex("(?i)(\"?[\\w.-]*(?:password|passwd|pwd|passphrase|secret|token|csrf|xsrf|session|sessionid|api[_-]?key|auth|credential)[\\w.-]*\"?\\s*[:=]\\s*)(\"(?:[^\"\\\\]|\\\\.)*\"|'[^']*'|[^\\s,;&}\\]]+)") to "$1$GONE",
        Regex("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9+/._~-]{8,}=*") to "$1 $GONE",
        // A web address: nothing after the path (a MEGA link's key is its fragment), and no user:password@.
        Regex("(?i)\\b([a-z][a-z0-9+.-]*://)[^\\s/@\"']+@") to "$1$GONE@",
        Regex("(?i)\\b(https?://[^\\s?#\"']*)[?#][^\\s\"']*") to "$1?$GONE",
        Regex("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+") to "[email]",
        // A long opaque value is a key or a token whatever it is called. A hash (64 hex, a mod's id) is not secret and helps.
        Regex("(?<![0-9a-fA-F])(?![0-9a-fA-F]{64}(?![0-9a-fA-F]))[A-Za-z0-9+/_-]{40,}={0,2}") to GONE,
    )

    fun line(text: String): String {
        var out = text
        for ((pattern, replacement) in RULES) out = pattern.replace(out, replacement)
        return out
    }

    fun text(text: String): String = text.split('\n').joinToString("\n") { line(it) }
}
