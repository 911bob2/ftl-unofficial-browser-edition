package ftl.saves

/**
 * Save files of the original game (docs/SAVES.md, "Saves from the original game"): the profile (`ae_prof.sav`, the
 * older `prof.sav`) and the run in progress (`continue.sav`), and the files Hyperspace writes in their place with its
 * save prefix (`hs_prof.sav`, `hs_continue.sav`; Multiverse `hs_mv_prof.sav`, `hs_mv_continue.sav`).
 *
 * The files are untrusted and are converted on the device only. This part needs no game data: it tells the files
 * apart, checks them to fixed limits and reads what the save list shows. The full conversion needs the ship layouts of
 * the game files or mods the save was made with, so the engine does it the first time the save is played
 * (`xyz.znix.xftl.vanillasave.NativeImport`). Until then the save holds the original bytes, wrapped in the engine's
 * own root elements so the save syncs like any other: [wrapRun] and [wrapProfile].
 */
object NativeSave {
    /** The largest run and profile taken: real runs are tens of kB, Multiverse profiles a few hundred kB. */
    const val RUN_BYTES = 4 * 1024 * 1024
    const val PROFILE_BYTES = 1024 * 1024

    /** The wrapper's child holding the original file as Base64, and the root attribute that marks a wrapped save. */
    const val ELEMENT = "nativeImport"
    const val VERSION = 1

    enum class Kind { RUN, PROFILE }

    /** What a file is. [prefix] is "" for the game without mods, else Hyperspace's save prefix ("hs", "hs_mv", ...). */
    data class Found(val fileName: String, val kind: Kind, val prefix: String, val format: Int, val bytes: ByteArray, val summary: Summary? = null) {
        override fun equals(other: Any?) = other is Found && other.fileName == fileName && other.kind == kind && other.prefix == prefix && other.bytes.contentEquals(bytes)
        override fun hashCode() = fileName.hashCode() * 31 + bytes.contentHashCode()
    }

    /** Why a file cannot be imported; the message is for the player. */
    class Invalid(message: String) : IllegalArgumentException(message)

    const val NOT_A_SAVE = "This is not a save file of FTL. Choose ae_prof.sav or continue.sav (with mods: hs_mv_prof.sav or hs_mv_continue.sav)."
    private const val DAMAGED_RUN = "This run file is damaged or from an unknown version of FTL, so it cannot be imported."
    private const val DAMAGED_PROFILE = "This profile file is damaged or from an unknown version of FTL, so it cannot be imported."

    private val NAME = Regex("^(?:(hs(?:_[A-Za-z0-9_]{1,40})?)_)?(continue|ae_prof|prof)(?: ?\\(\\d{1,3}\\))?\\.sav$", RegexOption.IGNORE_CASE)

    /** The kind and prefix a file name says, or null for another name (a renamed copy). */
    fun nameOf(fileName: String): Pair<Kind, String>? {
        val base = fileName.substringAfterLast('/').substringAfterLast('\\').trim()
        val m = NAME.matchEntire(base) ?: return null
        val prefix = m.groupValues[1].lowercase()
        val kind = if (m.groupValues[2].equals("continue", true)) Kind.RUN else Kind.PROFILE
        // Hyperspace never writes the profile without its prefix as prof.sav; "prof.sav" is the original before 1.5.
        return kind to prefix
    }

    /** Identifies one chosen file; throws [Invalid] with the reason. The name decides the prefix, the content the kind. */
    fun identify(fileName: String, bytes: ByteArray): Found {
        if (bytes.size < 8) throw Invalid(NOT_A_SAVE)
        val named = nameOf(fileName)
        val format = Reader(bytes).int()
        val kind = when (format) {
            RUN_FORMAT -> Kind.RUN
            9 -> Kind.PROFILE
            4 -> throw Invalid("This profile is from FTL before version 1.5 (prof.sav). Start FTL once so it writes ae_prof.sav, then import that file.")
            2, 7, 8 -> throw Invalid("This run was saved by an old version of FTL (before 1.6). Load it there, save it again, then import it.")
            else -> throw Invalid(NOT_A_SAVE)
        }
        if (named != null && named.first != kind) throw Invalid(NOT_A_SAVE)
        when (kind) {
            Kind.RUN -> if (bytes.size > RUN_BYTES) throw Invalid("This run file is too large to be a save of FTL.")
            Kind.PROFILE -> if (bytes.size > PROFILE_BYTES) throw Invalid("This profile file is too large to be a save of FTL.")
        }
        // A renamed copy: try the original's layout first, then Hyperspace's (its mods are then asked for).
        val prefixes = named?.let { listOf(it.second) } ?: listOf("", UNKNOWN_PREFIX)
        var failure: Invalid? = null
        for (prefix in prefixes) {
            try {
                return if (kind == Kind.PROFILE) {
                    checkProfile(bytes, prefix.isNotEmpty())
                    Found(fileName, kind, prefix, format, bytes)
                } else Found(fileName, kind, prefix, format, bytes, runSummary(bytes, prefix.isNotEmpty()))
            } catch (e: Invalid) {
                failure = failure ?: e
            }
        }
        throw failure!!
    }

    /** The prefix of a Hyperspace file whose name does not say it. */
    const val UNKNOWN_PREFIX = "hs_unknown"

    const val RUN_FORMAT = 11

    /**
     * The start of a run, as the list shows it: the ship's name and blueprint, the sector, scrap and hull. Everything up
     * to there needs no ship layout. A Hyperspace run has the next object id after the state variables and the removed
     * starting systems before the ship. Throws [Invalid] when the start does not read as a run.
     */
    fun runSummary(bytes: ByteArray, hyperspace: Boolean): Summary {
        val r = Reader(bytes)
        try {
            if (r.int() != RUN_FORMAT) throw Invalid(DAMAGED_RUN)
            r.int() // random_native
            r.bool() // advanced edition
            if (r.int() !in 0..2) throw Invalid(DAMAGED_RUN) // difficulty
            repeat(4) { r.count(1_000_000_000) } // ships defeated, beacons explored, scrap collected, crew hired
            val shipName = r.string()
            val blueprint = r.string()
            val sector = r.int()
            r.int()
            repeat(r.count(10_000)) { r.string(); r.int() }
            if (hyperspace) {
                r.int() // next space id
                repeat(r.count(64)) { r.int() }
            }
            if (r.string() != blueprint) throw Invalid(DAMAGED_RUN)
            r.string(); r.string() // ship name, graphics
            repeat(r.count(64)) { r.string(); r.string() }
            r.int(); r.int(); r.int(); r.int() // hostile, FTL charge, jumping, jump animation
            val hull = r.int()
            r.int(); r.int(); r.int() // fuel, drone parts, missiles
            val scrap = r.int()
            if (sector !in 1..99 || blueprint.isEmpty() || blueprint.any { it < ' ' }) throw Invalid(DAMAGED_RUN)
            return Summary(shipName.take(64).ifEmpty { blueprint.take(64) }, blueprint.take(64), sector, scrap.coerceAtLeast(0), hull, null)
        } catch (e: IndexOutOfBoundsException) {
            throw Invalid(DAMAGED_RUN)
        }
    }

    /** Walks a profile to its end: the original's version 9 layout, with Hyperspace's blocks when [hyperspace]. */
    fun checkProfile(bytes: ByteArray, hyperspace: Boolean) {
        val r = Reader(bytes)
        try {
            if (r.int() != 9) throw Invalid(DAMAGED_PROFILE)
            r.int() // the ship list tutorial
            repeat(r.count(10_000)) {
                val id = r.string()
                r.int()
                if (id.endsWith("_VICTORY")) repeat(3) { r.int() }
            }
            if (hyperspace) {
                repeat(r.count(100_000)) { r.string() }
                repeat(r.count(100_000)) { r.string(); r.int() }
                repeat(r.count(100_000)) { r.string(); r.int() }
                repeat(r.count(10_000)) { r.string(); repeat(r.count(100_000)) { r.string(); r.int() } }
                repeat(r.count(100_000)) { r.string(); r.int() }
                repeat(r.count(100_000)) { r.string(); repeat(r.count(1_000)) { r.string(); r.string(); repeat(5) { r.int() } } }
                repeat(r.count(1_000_000)) { r.string(); r.int() }
            }
            repeat(24) { r.int() } // layouts A and C of the twelve ship slots
            scores(r) // the top scores
            scores(r) // each ship's best
            repeat(10) { r.int() } // four most/total pairs, games played, victories
            repeat(5) { r.int(); r.string(); r.string(); r.int() }
        } catch (e: IndexOutOfBoundsException) {
            throw Invalid(DAMAGED_PROFILE)
        }
        if (r.remaining != 0 && !(hyperspace && r.remaining == 48)) throw Invalid(DAMAGED_PROFILE)
    }

    private fun scores(r: Reader) = repeat(r.count(1_000)) { r.string(); r.string(); repeat(5) { r.int() } }

    /** The run and profile that become one save. */
    data class Picked(val prefix: String, val run: Found?, val profile: Found?) {
        /** "vanilla", "hyperspace", "multiverse" or "other" ([setupFor]). */
        val setup get() = setupFor(prefix)
        val wrappedRun get() = run?.let { wrapRun(it.copy(prefix = prefix)) }
        val wrappedProfile get() = profile?.let { wrapProfile(it.copy(prefix = prefix)) }

        /** The new save on this device; sync creates it in the account like any new save. */
        fun newSave(id: String, name: String, setup: Setup, playedOn: PlayedOn): LocalSave {
            val run = wrappedRun
            val profile = wrappedProfile
            val meta = SaveMeta(playedOn, summary = this.run?.summary ?: Summary(),
                extra = mapOf("importedUtc" to Json.Str(playedOn.at), "importedFrom" to Json.Str(prefix.ifEmpty { "vanilla" })))
            return LocalSave(id, name, setup, playedOn.at, meta, base = 0, dirty = true, hasRun = run != null, run = run,
                hasProfile = profile != null, profile = profile)
        }
    }

    /** A preset of the website's catalogue (web/mods/catalogue.json `presets`) as a save setup. */
    data class Preset(val id: String, val title: String, val setup: Setup, val hyperspace: Boolean)

    /** The catalogue's presets whose files it lists; [] when the catalogue does not read. */
    fun presets(catalogueJson: String): List<Preset> = try {
        val root = Json.parse(catalogueJson).objOrNull()
        val files = root?.get("files").arr().mapNotNull { it.objOrNull() }.associateBy { it["id"].str() ?: "" }
        root?.get("presets").arr().mapNotNull { p ->
            val o = p.objOrNull() ?: return@mapNotNull null
            val list = o["files"].arr().map { files[it.str() ?: ""] ?: return@mapNotNull null }
            if (list.isEmpty()) return@mapNotNull null
            Preset(o["id"].str() ?: "", o["title"].str() ?: "", Setup.of(list.map {
                ModFile(it["title"].str() ?: "", it["version"].str() ?: "", it["sha256"].str() ?: "", it["size"].long() ?: 0L, true)
            }), list.any { it["mod"].str() == "hyperspace" })
        }
    } catch (e: IllegalArgumentException) {
        emptyList()
    }

    /** The preset a file's prefix asks for (Multiverse, Hyperspace alone), or null: no mods, or one the player picks. */
    fun presetFor(prefix: String, presets: List<Preset>): Preset? = when (setupFor(prefix)) {
        "hyperspace" -> presets.firstOrNull { it.id == "hyperspace" }
        "multiverse" -> presets.firstOrNull { it.id == "multiverse" }
        else -> null
    }

    /**
     * The chosen files as one save: at most one run and one profile, of one setup. A file of an unknown Hyperspace
     * prefix pairs with any Hyperspace file. Throws [Invalid] with the reason.
     */
    fun pick(files: List<Found>): Picked {
        if (files.isEmpty()) throw Invalid("Choose the profile, the run or both.")
        val runs = files.filter { it.kind == Kind.RUN }
        val profiles = files.filter { it.kind == Kind.PROFILE }
        if (runs.size > 1) throw Invalid("Choose one run (continue.sav) at a time.")
        if (profiles.size > 1) throw Invalid("Choose one profile (ae_prof.sav) at a time.")
        val prefixes = files.map { it.prefix }.toSet()
        val known = prefixes - UNKNOWN_PREFIX
        val prefix = when {
            prefixes.size == 1 -> prefixes.first()
            known.size == 1 && known.first().isNotEmpty() -> known.first()
            else -> throw Invalid("These files are from different games: " + files.joinToString(" and ") { describe(it.prefix) } + ". Choose the profile and run of one game.")
        }
        return Picked(prefix, runs.firstOrNull(), profiles.firstOrNull())
    }

    /** "the game without mods", "Hyperspace", "Multiverse", "a Hyperspace mod (save prefix x)". */
    fun describe(prefix: String): String = when (setupFor(prefix)) {
        "vanilla" -> "the game without mods"
        "hyperspace" -> "Hyperspace"
        "multiverse" -> "Multiverse"
        else -> if (prefix == UNKNOWN_PREFIX) "a Hyperspace mod" else "a Hyperspace mod (save prefix ${prefix.removePrefix("hs_")})"
    }

    /** The run as the save stores it until the engine converts it. */
    fun wrapRun(found: Found): String {
        require(found.kind == Kind.RUN)
        val s = found.summary
        val attrs = listOfNotNull("difficulty" to "NORMAL", ELEMENT to VERSION.toString(), "prefix" to found.prefix,
            s?.ship?.let { "ship" to it }, s?.shipId?.let { "shipId" to it }, s?.sector?.let { "sector" to it.toString() },
            s?.scrap?.let { "scrap" to it.toString() }, s?.hull?.let { "hull" to it.toString() })
        return wrap("xftlSaveGame", attrs, found)
    }

    /** The profile as the save stores it until the engine converts it. */
    fun wrapProfile(found: Found): String {
        require(found.kind == Kind.PROFILE)
        return wrap("xftl-profile", listOf(ELEMENT to VERSION.toString(), "prefix" to found.prefix), found)
    }

    private fun wrap(root: String, attrs: List<Pair<String, String>>, found: Found): String {
        val head = attrs.joinToString("") { (k, v) -> " $k=\"${escape(v)}\"" }
        val file = found.fileName.substringAfterLast('/').substringAfterLast('\\').take(80)
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<$root$head><$ELEMENT kind=\"${found.kind.name.lowercase()}\" file=\"${escape(file)}\" " +
            "bytes=\"${found.bytes.size}\" sha256=\"${Sha256.hex(found.bytes)}\">${java.util.Base64.getEncoder().encodeToString(found.bytes)}</$ELEMENT></$root>"
    }

    /** Whether a run or profile still holds an original file that the engine has not converted yet. */
    fun isWrapped(xml: String?): Boolean {
        if (xml == null) return false
        var wrapped = false
        try {
            XmlScanner(xml.take(4096)).forEachTag { _, attrs, closing, _ -> if (!closing && !wrapped && attrs[ELEMENT] != null) wrapped = true }
        } catch (e: IllegalArgumentException) {
            // The first 4 kB end inside a tag: what was read decides.
        }
        return wrapped
    }

    /** The summary of a wrapped run (RunSummary), or null when the run is not wrapped. */
    internal fun wrappedSummary(attrs: Map<String, String>, playSeconds: Long): Summary? {
        if (attrs[ELEMENT] == null) return null
        return Summary(attrs["ship"], attrs["shipId"], attrs["sector"]?.toIntOrNull(), attrs["scrap"]?.toIntOrNull(), attrs["hull"]?.toIntOrNull(), null, playSeconds)
    }

    /** Which save setup a prefix asks for: "vanilla", "hyperspace" (Hyperspace alone), "multiverse", or "other". */
    fun setupFor(prefix: String): String = when (prefix) {
        "" -> "vanilla"
        "hs" -> "hyperspace"
        "hs_mv" -> "multiverse"
        else -> "other"
    }

    private fun escape(v: String) = v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        .filter { it >= ' ' }

    /** Little-endian int32 and length-prefixed UTF-8 strings, every read bounded by the file. */
    class Reader(private val data: ByteArray) {
        var pos = 0
            private set
        val remaining get() = data.size - pos

        fun int(): Int {
            if (pos + 4 > data.size) throw IndexOutOfBoundsException("end of file")
            val v = (data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8) or
                ((data[pos + 2].toInt() and 0xFF) shl 16) or (data[pos + 3].toInt() shl 24)
            pos += 4
            return v
        }

        fun bool() = int() != 0

        /** A count of at most [max] entries. */
        fun count(max: Int): Int {
            val n = int()
            if (n < 0 || n > max) throw IndexOutOfBoundsException("count $n")
            return n
        }

        fun string(): String {
            val n = int()
            if (n < 0 || n > 65536 || n > remaining) throw IndexOutOfBoundsException("string $n")
            val s = data.decodeToString(pos, pos + n)
            pos += n
            return s
        }
    }
}
