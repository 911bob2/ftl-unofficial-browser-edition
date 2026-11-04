package ftl.saves

/**
 * A save as a file: the website's backup format (`{schema: 3, kind: "ftl-save", name, setup, meta, run, profile, savedUtc}`,
 * web/save-service.mjs), so a file moves between the browser and the app. It also reads what the website reads: schema 2
 * (no profile) and schema 1 (a save without mods).
 *
 * A file is untrusted: everything taken from it is checked here, to the limits the server applies to an upload, so a save
 * that imports can also sync. Nothing else in the file is kept: the metadata is rebuilt, the mod list's key and Verified
 * marks are worked out again, and settings left in an old profile are dropped, since they belong to this device.
 */
object SaveFile {
    const val PROFILE_CHARS = 4 * 1024 * 1024
    const val PROFILE_ELEMENTS = 100_000
    const val PROFILE_ATTRIBUTES = 256
    const val MAX_MODS = 64

    /** The largest run and profile, every character escaped once, and the rest of the file. */
    const val MAX_FILE_BYTES = 2L * (Limits.RUN_BYTES + PROFILE_CHARS) + 1024 * 1024

    private const val JSON_DEPTH = 16
    private const val SUMMARY_TEXT = 64

    const val NOT_A_SAVE = "This file is not a save file. Choose one exported from this app or the website."
    private const val RUN_DAMAGED = "The run in this file is damaged, so it cannot be imported. Try another copy of the file."
    const val TOO_LARGE = "This file is too large to be a save file."

    /** Why a file cannot be imported; the message is for the player. */
    class Invalid(message: String) : IllegalArgumentException(message)

    data class Parsed(val name: String?, val setup: Setup, val run: String?, val profile: String?, val engineBuild: String?, val gameData: String?, val playSeconds: Long = 0) {
        val summary: Summary get() = RunSummary.read(run, playSeconds).let { s -> s.copy(ship = s.ship?.take(SUMMARY_TEXT), shipId = s.shipId?.take(SUMMARY_TEXT)) }
    }

    /** The file's text. The save's device id stays out: it names this device's locks, and the file may go to anyone. */
    fun write(name: String, setup: Setup, meta: SaveMeta, run: String?, profile: String?, savedUtc: String): String =
        Json.write(obj("schema" to 3, "kind" to "ftl-save", "name" to name, "setup" to setup.toJson(), "meta" to meta.copy(playedOn = null).toJson(),
            "run" to run, "profile" to profile, "savedUtc" to savedUtc))

    /** "ftl-save-My-run.json", as the website names it. */
    fun fileName(name: String): String = "ftl-save-" + Regex("[^\\p{L}\\p{N}_-]+").replace(name, "-").trim('-').ifEmpty { "save" } + ".json"

    fun parse(text: String, catalogue: List<CatalogueFile> = emptyList()): Parsed {
        val clean = text.removePrefix("﻿")
        if (tooDeep(clean)) throw Invalid(NOT_A_SAVE)
        val value = try {
            Json.parse(clean)
        } catch (e: IllegalArgumentException) {
            throw Invalid(NOT_A_SAVE)
        } as? Json.Obj ?: throw Invalid(NOT_A_SAVE)
        val schema = (value["schema"] as? Json.Num)?.value
        val kind = value["kind"].str()
        if (kind == "ftl-saves") throw Invalid("This file holds a whole account's saves. Export one save on the website and import that.")
        val current = (schema == 3.0 || schema == 2.0) && kind == "ftl-save"
        if (!current && !(schema == 1.0 && value.fields.containsKey("run"))) throw Invalid(NOT_A_SAVE)

        val run = textOrNull(value["run"], "run")
        run?.let { (XmlShape.check(it, "xftlSaveGame", Limits.RUN_BYTES) ?: RunShape.check(it))?.let { throw Invalid(RUN_DAMAGED) } }
        val profile = textOrNull(value["profile"], "profile")?.let { OldProfile.split(it).profile }
        profile?.let {
            XmlShape.check(it, "xftl-profile", PROFILE_CHARS, PROFILE_ELEMENTS, PROFILE_ATTRIBUTES)?.let { throw Invalid("The profile in this file is damaged.") }
        }
        if (!current) return Parsed("Imported save", Setup.VANILLA, run, profile, shortText(value["engineBuild"]), shortText(value["assetVersion"]))
        val meta = value["meta"].objOrNull()
        return Parsed(SaveNames.clean(value["name"].str()), setupOf(value["setup"], catalogue), run, profile, shortText(meta?.get("engineBuild")), shortText(meta?.get("gameData")),
            ((meta?.get("summary").objOrNull()?.get("playSeconds") as? Json.Num)?.value ?: 0.0).takeIf { it >= 0 && it < 1E12 }?.toLong() ?: 0)
    }

    /** The name the imported save takes: never one already on the list, so it cannot be mistaken for the original. */
    fun uniqueName(wanted: String?, existing: Collection<String>): String {
        val name = wanted ?: return SaveNames.next(existing)
        if (name !in existing) return name
        var n = 2
        while (true) {
            val suffix = " ($n)"
            val candidate = name.take(SaveNames.MAX - suffix.length).trimEnd() + suffix
            if (candidate !in existing) return candidate
            n++
        }
    }

    /** The new save a file becomes: its own id, nothing uploaded yet, so sync creates it as a new save. */
    fun newSave(parsed: Parsed, id: String, name: String, playedOn: PlayedOn): LocalSave = LocalSave(
        id, name, parsed.setup, playedOn.at,
        SaveMeta(playedOn, parsed.engineBuild, parsed.gameData, parsed.summary, mapOf("importedUtc" to Json.Str(playedOn.at))),
        base = 0, dirty = true, hasRun = parsed.run != null, run = parsed.run, hasProfile = parsed.profile != null, profile = parsed.profile,
    )

    private fun textOrNull(j: Json?, what: String): String? = when (j) {
        null, Json.Null -> null
        is Json.Str -> j.value
        else -> throw Invalid("The $what in this file is damaged.")
    }

    private fun shortText(j: Json?): String? = j.str()?.takeIf { s -> s.length <= 128 && s.none { it < ' ' } }

    private fun setupOf(j: Json?, catalogue: List<CatalogueFile>): Setup {
        val list = j.objOrNull()?.get("mods") ?: return Setup.VANILLA
        val mods = (list as? Json.Arr)?.items ?: throw Invalid("The mod list in this file is damaged.")
        if (mods.size > MAX_MODS) throw Invalid("The mod list in this file is damaged.")
        return Setup.of(mods.map { m ->
            val o = m.objOrNull() ?: throw Invalid("The mod list in this file is damaged.")
            val sha = o["sha256"].str()?.lowercase()?.takeIf { isSha256(it) } ?: throw Invalid("The mod list in this file is damaged.")
            val size = (o["size"] as? Json.Num)?.value ?: 0.0
            val name = o["name"].str()
            val version = o["version"].str() ?: ""
            if (name == null || name.isEmpty() || name.length > 100 || name.any { it < ' ' || it == '\u007f' } || version.length > 40 || version.any { it < ' ' || it == '\u007f' } ||
                size.isNaN() || size < 0 || size > 9.007199254740992E15) throw Invalid("The mod list in this file is damaged.")
            // Verified is this device's own judgement of the hash, never the file's word.
            val known = catalogue.firstOrNull { it.sha256 == sha }
            ModFile(known?.title ?: name, known?.version ?: version, sha, size.toLong(), known != null)
        })
    }

    /** The JSON parser recurses; a file of nothing but brackets must not reach it. */
    private fun tooDeep(s: String): Boolean {
        var depth = 0
        var inString = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (inString) {
                if (c == '\\') i++ else if (c == '"') inString = false
            } else when (c) {
                '"' -> inString = true
                '{', '[' -> if (++depth > JSON_DEPTH) return true
                '}', ']' -> depth--
            }
            i++
        }
        return false
    }
}

/**
 * Whether a text is well-formed XML of the shape a save may have: the server's rules (server/SaveValidation.cs: root element
 * without a namespace, no DTD, nesting up to 64) and what its reader refuses (unknown entities, duplicate attributes,
 * undeclared prefixes, characters XML does not allow), in a form that compiles everywhere the save core does. Linear in
 * the text and not recursive.
 */
object RunShape {
    private val DIFFICULTIES = setOf("EASY", "NORMAL", "HARD")
    private val PARTS = setOf("playerShip", "gameMap", "currentSector", "visitedSectors")

    /** Null when the run has what any run the game can load must have (the root's difficulty and these parts), else what it lacks. */
    fun check(xml: String): String? {
        var depth = 0
        var difficulty: String? = null
        var wrapped = false
        val found = HashSet<String>()
        try {
            XmlScanner(xml).forEachTag { name, attrs, closing, selfClosing ->
                if (closing) depth--
                else {
                    if (depth == 0) { difficulty = attrs["difficulty"]; wrapped = attrs[NativeSave.ELEMENT] != null }
                    if (depth == 1 && (name in PARTS || wrapped && name == NativeSave.ELEMENT)) found.add(name)
                    if (!selfClosing) depth++
                }
            }
        } catch (e: IllegalArgumentException) {
            return "unreadable"
        }
        if (difficulty !in DIFFICULTIES) return "no difficulty"
        // A run imported from the original game that the engine has not converted yet (NativeSave.wrapRun).
        if (wrapped && found == setOf(NativeSave.ELEMENT)) return null
        return (PARTS - found).firstOrNull()?.let { "no $it" }
    }
}

object XmlShape {
    private const val DEPTH = 64

    /** Null when fine, else what is wrong. */
    fun check(xml: String, root: String, maxChars: Int, maxElements: Int = Int.MAX_VALUE, maxAttributes: Int = Int.MAX_VALUE): String? {
        if (xml.length > maxChars) return "too long"
        return Scan(xml, root, maxElements, maxAttributes).run()
    }

    private class Scan(val s: String, val root: String, val maxElements: Int, val maxAttributes: Int) {
        var i = 0
        val open = ArrayList<String>()
        var elements = 0
        var rootSeen = false

        fun run(): String? {
            if (s.startsWith("<?xml") && s.length > 5 && space(s[5])) {
                i = s.indexOf("?>", 5).let { if (it < 0) return "unterminated declaration" else it + 2 }
            }
            while (i < s.length) {
                val problem = if (s[i] == '<') markup() else text()
                if (problem != null) return problem
            }
            if (!rootSeen) return "no root element"
            return if (open.isNotEmpty()) "unclosed element" else null
        }

        fun space(c: Char) = c == ' ' || c == '\n' || c == '\r' || c == '\t'

        fun allowed(c: Char) = c >= ' ' && c != '￾' && c != '￿' || c == '\n' || c == '\r' || c == '\t'

        fun text(): String? {
            val start = i
            while (i < s.length && s[i] != '<') {
                val c = s[i]
                if (!allowed(c)) return "bad character"
                if (open.isEmpty() && !space(c)) return "text outside the root"
                if (c == '&') {
                    if (open.isEmpty()) return "text outside the root"
                    reference()?.let { return it }
                    continue
                }
                if (c == ']' && s.startsWith("]]>", i)) return "stray section end"
                i++
            }
            return if (start == i) "bad markup" else null
        }

        /** At '&': a predefined entity or a character XML allows; the server allows no others. */
        fun reference(): String? {
            val semi = s.indexOf(';', i)
            if (semi < 0 || semi - i > 12) return "bad entity"
            val name = s.substring(i + 1, semi)
            val ok = when {
                name == "amp" || name == "lt" || name == "gt" || name == "quot" || name == "apos" -> true
                name.startsWith("#x") -> codePointOk(name.substring(2).takeIf { n -> n.isNotEmpty() && n.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' } }?.toIntOrNull(16))
                name.startsWith("#") -> codePointOk(name.substring(1).takeIf { n -> n.isNotEmpty() && n.all { it.isDigit() } }?.toIntOrNull())
                else -> false
            }
            if (!ok) return "bad entity"
            i = semi + 1
            return null
        }

        fun codePointOk(c: Int?) = c != null && (c == 9 || c == 10 || c == 13 || c in 0x20..0xD7FF || c in 0xE000..0xFFFD || c in 0x10000..0x10FFFF)

        fun markup(): String? = when {
            s.startsWith("<!--", i) -> {
                val end = s.indexOf("--", i + 4)
                if (end < 0 || !s.startsWith("-->", end)) "bad comment" else { i = end + 3; null }
            }
            s.startsWith("<![CDATA[", i) -> {
                val end = s.indexOf("]]>", i + 9)
                if (end < 0 || open.isEmpty()) "bad section" else { i = end + 3; null }
            }
            s.startsWith("<?", i) -> {
                val end = s.indexOf("?>", i + 2)
                // A declaration anywhere but the very start is not XML.
                if (end < 0 || s.regionMatches(i + 2, "xml", 0, 3, ignoreCase = true) && (i + 5 == end || space(s[i + 5]))) "bad instruction" else { i = end + 2; null }
            }
            s.startsWith("<!", i) -> "DTD"
            s.startsWith("</", i) -> endTag()
            else -> startTag()
        }

        fun nameStart(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c == '_' || c >= 'À' && c != '×' && c != '÷' && c !in ' '..'⁯' && c !in '\uD800'..'\uDBFF' && c !in '\uDC00'..'\uDFFF' && c != '￾' && c != '￿'

        fun nameChar(c: Char) = nameStart(c) || c in '0'..'9' || c == '-' || c == '.' || c == '·'

        /** A name without a prefix: nothing in a save declares a namespace. */
        fun name(): String? {
            val start = i
            if (i >= s.length || !nameStart(s[i])) return null
            while (i < s.length && nameChar(s[i])) i++
            return s.substring(start, i)
        }

        fun endTag(): String? {
            i += 2
            val n = name() ?: return "bad end tag"
            while (i < s.length && space(s[i])) i++
            if (i >= s.length || s[i] != '>') return "bad end tag"
            i++
            if (open.isEmpty() || open.removeAt(open.size - 1) != n) return "mismatched end tag"
            return null
        }

        fun startTag(): String? {
            i++
            val n = name() ?: return "bad tag"
            if (open.isEmpty()) {
                if (rootSeen) return "second root"
                if (n != root) return "unexpected root"
                rootSeen = true
            }
            if (++elements > maxElements) return "too many elements"
            val names = HashSet<String>()
            while (true) {
                val before = i
                while (i < s.length && space(s[i])) i++
                if (i >= s.length) return "unterminated tag"
                if (s[i] == '>') {
                    i++
                    if (open.size >= DEPTH) return "too deep"
                    open.add(n)
                    return null
                }
                if (s.startsWith("/>", i)) {
                    i += 2
                    return if (open.size >= DEPTH) "too deep" else null
                }
                if (before == i) return "bad tag"
                val attr = name() ?: return "bad attribute"
                if (attr == "xmlns" || !names.add(attr)) return "bad attribute"
                if (names.size > maxAttributes) return "too many attributes"
                while (i < s.length && space(s[i])) i++
                if (i >= s.length || s[i] != '=') return "bad attribute"
                i++
                while (i < s.length && space(s[i])) i++
                if (i >= s.length || (s[i] != '"' && s[i] != '\'')) return "bad attribute"
                val quote = s[i++]
                while (true) {
                    if (i >= s.length) return "unterminated attribute"
                    val c = s[i]
                    if (c == quote) { i++; break }
                    if (c == '<' || !allowed(c)) return "bad attribute value"
                    if (c == '&') { reference()?.let { return it } } else i++
                }
            }
        }
    }
}
