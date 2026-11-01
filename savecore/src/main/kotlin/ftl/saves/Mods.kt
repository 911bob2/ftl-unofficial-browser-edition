package ftl.saves

/** One mod archive of a save's setup. `verified`: its SHA-256 is a catalogue entry (else a player's own "Custom" file). */
data class ModFile(val name: String, val version: String, val sha256: String, val size: Long, val verified: Boolean) {
    fun toJson(): Json.Obj = obj("name" to name, "version" to version, "sha256" to sha256, "size" to size, "verified" to verified)

    companion object {
        fun fromJson(j: Json?): ModFile {
            val o = j.objOrNull() ?: throw IllegalArgumentException("mod is not an object")
            return ModFile(o["name"].str() ?: "", o["version"].str() ?: "", o["sha256"].str() ?: "", o["size"].long() ?: 0L,
                o["verified"].bool() ?: false)
        }
    }
}

/** The mod set a save is locked to: `vanilla`, or the mod-set hash of its archives in patch order. */
data class Setup(val key: String, val mods: List<ModFile>) {
    val vanilla: Boolean get() = mods.isEmpty()

    fun toJson(): Json.Obj = obj("key" to key, "mods" to mods.map { it.toJson() })

    companion object {
        const val VANILLA_KEY = "vanilla"
        val VANILLA = Setup(VANILLA_KEY, emptyList())

        fun of(mods: List<ModFile>): Setup = if (mods.isEmpty()) VANILLA else Setup(modSetHash(mods.map { it.sha256 }), mods)

        fun fromJson(j: Json?): Setup {
            val o = j.objOrNull() ?: return VANILLA
            val mods = o["mods"].arr().map { ModFile.fromJson(it) }
            // The key names a file on the device, so only a well-formed one is taken from outside.
            return Setup(o["key"].str()?.takeIf { validKey(it) } ?: if (mods.isEmpty()) VANILLA_KEY else modSetHash(mods.map { it.sha256 }), mods)
        }

        fun validKey(key: String): Boolean = key == VANILLA_KEY || isSha256(key)
    }
}

fun isSha256(s: String): Boolean = s.length == 64 && s.all { it in '0'..'9' || it in 'a'..'f' }

/**
 * modSetHash = sha256 of the UTF-8 JSON {"loader":1,"mods":[archive sha256 in order]}, exactly the text the website's
 * JSON.stringify produces, so a mod set has one id everywhere.
 */
fun modSetHash(sha256s: List<String>): String {
    val text = StringBuilder("{\"loader\":1,\"mods\":[")
    sha256s.forEachIndexed { i, s -> if (i > 0) text.append(','); Json.quote(text, s) }
    return Sha256.hex(text.append("]}").toString())
}

/** A catalogue file as the website's web/mods/catalogue.json lists it (only what the save core needs). */
data class CatalogueFile(val id: String, val title: String, val version: String, val file: String, val size: Long, val sha256: String)

object Catalogue {
    fun parse(json: String): List<CatalogueFile> = Json.parse(json).objOrNull()?.get("files").arr().map {
        val o = it.objOrNull() ?: throw IllegalArgumentException("catalogue file is not an object")
        CatalogueFile(o["id"].str() ?: "", o["title"].str() ?: "", o["version"].str() ?: "", o["file"].str() ?: "",
            o["size"].long() ?: 0L, o["sha256"].str() ?: "")
    }

    /**
     * A local file is accepted whatever it is (unpinned local mods): Verified when its SHA-256 is a catalogue entry,
     * otherwise Custom with the name the player's file has. Server and MEGA downloads stay pinned elsewhere.
     */
    fun classify(catalogue: List<CatalogueFile>, fileName: String, sha256: String, size: Long): ModFile {
        val entry = catalogue.firstOrNull { it.sha256 == sha256 }
        return if (entry != null) ModFile(entry.title, entry.version, sha256, size, true)
        else ModFile(fileName.substringAfterLast('/').substringAfterLast('\\'), "", sha256, size, false)
    }
}

/** What a device has of a save's mod list: the files it lacks are shown as "missing mod: name, sha256". */
fun missingMods(setup: Setup, available: Set<String>): List<ModFile> = setup.mods.filter { it.sha256 !in available }
