package ftl.saves

/**
 * The summary the save list shows, read from the engine's run XML (`xftlSaveGame`): the player ship's name (else its
 * blueprint), hull and scrap, and the sector number (visitedSectors/sectorInfo, as web/save-summary.mjs counts it).
 * Play time is not in the run; the device adds it to the metadata while the game runs.
 */
object RunSummary {
    fun read(run: String?, playSeconds: Long = 0): Summary {
        if (run.isNullOrEmpty()) return Summary(playSeconds = playSeconds)
        return try {
            scan(run, playSeconds)
        } catch (e: IllegalArgumentException) {
            Summary(playSeconds = playSeconds)
        }
    }

    private fun scan(xml: String, playSeconds: Long): Summary {
        var ship: String? = null
        var shipId: String? = null
        var hull: Int? = null
        var maxHull: Int? = null
        var scrap: Int? = null
        var sectors: Int? = null
        val path = ArrayList<String>()
        XmlScanner(xml).forEachTag { name, attrs, closing, selfClosing ->
            if (closing) {
                if (path.isNotEmpty()) path.removeAt(path.size - 1)
                return@forEachTag
            }
            val depth = path.size
            when {
                depth == 1 && name == "playerShip" -> {
                    ship = attrs["displayName"]
                    shipId = attrs["shipId"]
                    hull = attrs["health"]?.toIntOrNull()
                    maxHull = attrs["browserMaxHealth"]?.toIntOrNull()
                }
                depth == 2 && name == "resources" && path[1] == "playerShip" -> scrap = attrs["SCRAP"]?.toIntOrNull() ?: 0
                depth == 1 && name == "visitedSectors" -> sectors = 0
                depth == 2 && name == "sectorInfo" && path[1] == "visitedSectors" -> sectors = (sectors ?: 0) + 1
            }
            if (!selfClosing) path.add(name)
        }
        return Summary(ship ?: shipId, shipId, sectors?.let { maxOf(1, it) }, scrap, hull, maxHull, playSeconds)
    }
}

/** Enough of XML to walk start/end tags with their attributes: comments, CDATA, PIs and DTDs are skipped. */
internal class XmlScanner(private val s: String) {
    fun forEachTag(visit: (name: String, attrs: Map<String, String>, closing: Boolean, selfClosing: Boolean) -> Unit) {
        var i = 0
        while (true) {
            val lt = s.indexOf('<', i)
            if (lt < 0) return
            when {
                s.startsWith("<!--", lt) -> { i = end(s.indexOf("-->", lt + 4), 3) }
                s.startsWith("<![CDATA[", lt) -> { i = end(s.indexOf("]]>", lt + 9), 3) }
                s.startsWith("<?", lt) -> { i = end(s.indexOf("?>", lt + 2), 2) }
                s.startsWith("<!", lt) -> { i = end(s.indexOf('>', lt + 2), 1) }
                else -> i = tag(lt, visit)
            }
        }
    }

    private fun end(at: Int, length: Int): Int {
        if (at < 0) throw IllegalArgumentException("unterminated markup")
        return at + length
    }

    private fun tag(lt: Int, visit: (String, Map<String, String>, Boolean, Boolean) -> Unit): Int {
        var i = lt + 1
        val closing = i < s.length && s[i] == '/'
        if (closing) i++
        val nameStart = i
        while (i < s.length && !s[i].isWhitespace() && s[i] != '>' && s[i] != '/') i++
        val name = s.substring(nameStart, i)
        if (name.isEmpty()) throw IllegalArgumentException("bad tag")
        val attrs = LinkedHashMap<String, String>()
        while (true) {
            while (i < s.length && s[i].isWhitespace()) i++
            if (i >= s.length) throw IllegalArgumentException("unterminated tag")
            if (s[i] == '>') {
                visit(name, attrs, closing, false)
                return i + 1
            }
            if (s[i] == '/' && i + 1 < s.length && s[i + 1] == '>') {
                visit(name, attrs, false, true)
                return i + 2
            }
            val keyStart = i
            while (i < s.length && s[i] != '=' && !s[i].isWhitespace() && s[i] != '>') i++
            val key = s.substring(keyStart, i)
            while (i < s.length && s[i].isWhitespace()) i++
            if (i >= s.length || s[i] != '=') throw IllegalArgumentException("attribute without value")
            i++
            while (i < s.length && s[i].isWhitespace()) i++
            if (i >= s.length || (s[i] != '"' && s[i] != '\'')) throw IllegalArgumentException("unquoted attribute")
            val quote = s[i]
            val close = s.indexOf(quote, i + 1)
            if (close < 0) throw IllegalArgumentException("unterminated attribute")
            attrs[key] = unescape(s.substring(i + 1, close))
            i = close + 1
        }
    }

    private fun unescape(v: String): String {
        if (v.indexOf('&') < 0) return v
        val out = StringBuilder()
        var i = 0
        while (i < v.length) {
            val c = v[i]
            if (c != '&') { out.append(c); i++; continue }
            val semi = v.indexOf(';', i)
            if (semi < 0) { out.append(c); i++; continue }
            val ref = v.substring(i + 1, semi)
            val decoded = when {
                ref == "amp" -> "&"; ref == "lt" -> "<"; ref == "gt" -> ">"; ref == "quot" -> "\""; ref == "apos" -> "'"
                ref.startsWith("#x") -> ref.substring(2).toIntOrNull(16)?.let { codePoint(it) }
                ref.startsWith("#") -> ref.substring(1).toIntOrNull()?.let { codePoint(it) }
                else -> null
            }
            if (decoded == null) { out.append(c); i++; continue }
            out.append(decoded)
            i = semi + 1
        }
        return out.toString()
    }

    private fun codePoint(cp: Int): String = if (cp < 0x10000) cp.toChar().toString()
    else String(charArrayOf((0xD800 + ((cp - 0x10000) shr 10)).toChar(), (0xDC00 + ((cp - 0x10000) and 0x3FF)).toChar()))
}
