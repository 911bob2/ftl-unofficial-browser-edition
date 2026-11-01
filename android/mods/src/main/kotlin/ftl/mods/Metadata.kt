package ftl.mods

// What the launcher reads from mod archives and merged files: the Kotlin twin of the helpers in web/mods/session.mjs.

// JavaScript's \s (WhiteSpace and LineTerminator), so these patterns read files exactly as the website does.
private const val WS = "[\\t\\n\\u000b\\f\\r \\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]"
private val COMMENTS = Regex("<!--[\\s\\S]*?-->")

private val TRIM = Regex("^$WS+|$WS+$")
private fun jsTrim(s: String) = s.replace(TRIM, "")

private fun decode(bytes: ByteArray): String = bytes.decodeToString().removePrefix("\ufeff").replace(COMMENTS, "")

class ModMetadata(val title: String?, val version: String?)

/** Title and version from mod-appendix/metadata.xml (CDATA or text, trimmed), as ftlman shows them. */
fun parseMetadata(bytes: ByteArray?): ModMetadata {
    if (bytes == null) return ModMetadata(null, null)
    val text = decode(bytes)
    fun field(tag: String): String? {
        val m = Regex("<$tag(?:$WS[^>]*)?>$WS*(?:<!\\[CDATA\\[([\\s\\S]*?)\\]\\]>|([^<]*))$WS*</$tag>").find(text) ?: return null
        val value = jsTrim(m.groups[1]?.value ?: m.groups[2]?.value ?: "")
        return value.ifEmpty { null }
    }
    return ModMetadata(field("title"), field("version"))
}

/** Lua scripts HS runs, in order: every <script> child of every top-level <scripts> in data/hyperspace.xml. */
fun hyperspaceScripts(xmlBytes: ByteArray?): List<String> {
    if (xmlBytes == null) return emptyList()
    val text = decode(xmlBytes)
    val out = ArrayList<String>()
    for (block in Regex("<scripts(?:$WS[^>]*)?>([\\s\\S]*?)</scripts>").findAll(text))
        for (s in Regex("<script(?:$WS[^>]*)?>([\\s\\S]*?)</script>").findAll(block.groupValues[1])) out.add(jsTrim(s.groupValues[1]))
    return out
}
