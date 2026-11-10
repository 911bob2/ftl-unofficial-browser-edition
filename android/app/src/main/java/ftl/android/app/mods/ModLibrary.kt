package ftl.android.app.mods

import android.content.ContentResolver
import android.content.res.AssetManager
import android.net.Uri
import android.provider.OpenableColumns
import ftl.android.app.data.GameData
import ftl.android.app.net.Api
import ftl.mods.Mega
import ftl.mods.MegaError
import ftl.mods.Zip
import ftl.mods.parseMetadata
import ftl.saves.Catalogue
import ftl.saves.CatalogueFile
import ftl.saves.Json
import ftl.saves.ModFile
import ftl.saves.arr
import ftl.saves.bool
import ftl.saves.long
import ftl.saves.obj
import ftl.saves.objOrNull
import ftl.saves.str
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * The mod archives on this device (docs/ANDROID.md, "Mods"). Any local file is accepted and badged Verified (its
 * SHA-256 is in the website's catalogue) or Custom; server and MEGA downloads must match the catalogue. Archives are
 * stored by SHA-256 and never uploaded; a save only names them.
 */
class ModLibrary(root: File, assets: AssetManager) {
    private val dir = File(root, "mods").apply { mkdirs() }
    private val indexFile = File(dir, "library.json")

    /** The website's pinned catalogue (web/mods/catalogue.json, bundled). */
    private val catalogueJson = runCatching { assets.open("site/catalogue.json").use { it.readBytes().decodeToString() } }.getOrNull()
    val catalogue: List<CatalogueFile> = runCatching { Catalogue.parse(catalogueJson!!) }.getOrDefault(emptyList())
    /** The catalogue's presets as setups (Multiverse, Hyperspace alone). */
    val presets: List<ftl.saves.NativeSave.Preset> = catalogueJson?.let { ftl.saves.NativeSave.presets(it) } ?: emptyList()

    /** The catalogue's official MEGA links, by catalogue id (Multiverse). */
    val megaLinks: Map<String, String> = runCatching {
        Json.parse(catalogueJson!!).objOrNull()?.get("files").arr().mapNotNull { f ->
            val o = f.objOrNull() ?: return@mapNotNull null
            val id = o["id"].str() ?: return@mapNotNull null
            o["mega"].str()?.let { id to it }
        }.toMap()
    }.getOrDefault(emptyMap())

    /** The catalogue's Hyperspace file (mod "hyperspace") and its official release page. */
    private val hyperspaceIds: Map<String, String?> = runCatching {
        Json.parse(catalogueJson!!).objOrNull()?.get("files").arr().mapNotNull { f ->
            val o = f.objOrNull() ?: return@mapNotNull null
            if (o["mod"].str() == "hyperspace") (o["id"].str() ?: return@mapNotNull null) to o["page"].str() else null
        }.toMap()
    }.getOrDefault(emptyMap())
    val hyperspace: CatalogueFile? = catalogue.firstOrNull { it.id in hyperspaceIds }
    val hyperspacePage: String = hyperspace?.let { hyperspaceIds[it.id] } ?: "https://github.com/FTL-Hyperspace/FTL-Hyperspace/releases"
    private val hyperspaceShas = catalogue.filter { it.id in hyperspaceIds }.map { it.sha256 }.toSet()
    private val markers = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** Whether an archive uses Hyperspace: data/hyperspace.xml (noted when it was added) or a patch of it (its file list). */
    fun usesHyperspace(mod: Mod): Boolean = mod.hyperspace || markers.getOrPut(mod.sha256) {
        runCatching { Zip.open(file(mod.sha256)).use { z -> HyperspaceRule.usesHyperspace(z.entries.map { it.name }) } }.getOrDefault(false)
    }

    fun isHyperspace(mod: Mod): Boolean = HyperspaceRule.isHyperspace(HyperspaceRule.Mod(mod.sha256, mod.title, false), hyperspaceShas)

    /** Of these files (load order), the one that uses Hyperspace while Hyperspace.ftl is not among them; files not here are not looked into. */
    fun withoutHyperspace(shas: List<String>): Mod? {
        val all = list()
        val mods = shas.mapNotNull { s -> all.firstOrNull { it.sha256 == s } }
        val hit = HyperspaceRule.without(mods.map { HyperspaceRule.Mod(it.sha256, it.title, usesHyperspace(it)) }, hyperspaceShas) ?: return null
        return mods.first { it.sha256 == hit.sha256 }
    }

    class Mod(val file: ModFile, val title: String?, val hyperspace: Boolean, val source: String) {
        val sha256 get() = file.sha256
        /** The catalogue's name for a Verified file, else the title in the archive's metadata. */
        val label get() = if (file.verified) file.name else title ?: file.name

        fun toJson(): Json.Obj = obj("file" to file.toJson(), "title" to title, "hyperspace" to hyperspace, "source" to source)

        companion object {
            fun fromJson(j: Json?): Mod? {
                val o = j.objOrNull() ?: return null
                return Mod(ModFile.fromJson(o["file"]), o["title"].str(), o["hyperspace"].bool() ?: false, o["source"].str() ?: "local")
            }
        }
    }

    class ModError(message: String) : Exception(message)

    @Synchronized
    fun list(): List<Mod> = runCatching { Json.parse(indexFile.readText()).arr().mapNotNull { Mod.fromJson(it) } }.getOrDefault(emptyList())
        .filter { file(it.sha256).exists() }

    fun get(sha256: String): Mod? = list().firstOrNull { it.sha256 == sha256 }

    fun file(sha256: String) = File(dir, "$sha256.zip")

    @Synchronized
    private fun put(mod: Mod) {
        val all = list().filter { it.sha256 != mod.sha256 } + mod
        val tmp = File(dir, "library.json.tmp")
        tmp.writeText(Json.write(Json.Arr(all.map { it.toJson() })))
        if (!tmp.renameTo(indexFile)) { indexFile.delete(); tmp.renameTo(indexFile) }
    }

    @Synchronized
    fun remove(sha256: String) {
        val all = list().filter { it.sha256 != sha256 }
        indexFile.writeText(Json.write(Json.Arr(all.map { it.toJson() })))
        file(sha256).delete()
    }

    /** A mod file the player chose: copied while hashing, opened as a ZIP, then classified. */
    fun import(resolver: ContentResolver, uri: Uri, progress: (Long, Long) -> Unit): Mod {
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "mod.zip"
        val total = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        if (total > MAX_ARCHIVE) throw ModError("Mod files are limited to ${MAX_ARCHIVE shr 20} MiB.")
        val input = resolver.openInputStream(uri) ?: throw ModError("The file could not be opened.")
        return store(input.use { copy(it, total, progress) }, name, "local", null)
    }

    /** A catalogue file from this server, which offers only Hyperspace; it must match the catalogue's SHA-256. */
    fun download(api: Api, entry: CatalogueFile, progress: (Long, Long) -> Unit): Mod {
        val tmp = File(dir, "download.part")
        api.download("/api/mods/files/${entry.id}", tmp) { progress(it, entry.size) }
        val sha = sha256(tmp)
        if (sha != entry.sha256) { tmp.delete(); throw ModError("The downloaded ${entry.title} does not match the catalogue.") }
        return store(tmp to sha, entry.file, "server", entry)
    }

    /**
     * A catalogue file from its official MEGA link, in 16 MiB ranges decrypted on the device. An interrupted download
     * resumes after its last whole range; the result must match the catalogue's SHA-256.
     */
    fun downloadMega(entry: CatalogueFile, progress: (Long, Long) -> Unit): Mod {
        val link = megaLinks[entry.id] ?: throw ModError("${entry.title} has no MEGA link.")
        try {
            var info = Mega.fileInfo(link)
            if (info.size != entry.size) throw ModError("The file behind the MEGA link changed (${info.size shr 20} MiB instead of " +
                "${entry.size shr 20} MiB); it is not the pinned ${entry.title} ${entry.version}.")
            val part = File(dir, "${entry.sha256}.mega.part")
            val digest = MessageDigest.getInstance("SHA-256")
            RandomAccessFile(part, "rw").use { out ->
                var done = (out.length() / MEGA_RANGE * MEGA_RANGE).coerceAtMost(entry.size)
                out.setLength(done)
                val buf = ByteArray(1 shl 20)
                out.seek(0)
                var read = 0L
                while (read < done) {
                    val n = out.read(buf, 0, minOf(buf.size.toLong(), done - read).toInt())
                    if (n < 0) break
                    digest.update(buf, 0, n)
                    read += n
                }
                progress(done, entry.size)
                while (done < entry.size) {
                    val end = minOf(entry.size, done + MEGA_RANGE) - 1
                    val encrypted = try {
                        Mega.range(info.url, done, end)
                    } catch (e: MegaError) {
                        if (e.kind != "expired") throw e
                        info = Mega.fileInfo(link)
                        Mega.range(info.url, done, end)
                    }
                    val plain = Mega.decrypt(info.keys, encrypted, done)
                    out.write(plain)
                    digest.update(plain)
                    done = end + 1
                    progress(done, entry.size)
                }
            }
            val sha = GameData.hex(digest.digest())
            if (sha != entry.sha256) { part.delete(); throw ModError("The downloaded ${entry.title} does not match the catalogue.") }
            return store(part to sha, entry.file, "mega", entry)
        } catch (e: MegaError) {
            val wait = e.retryAfter?.let { " (MEGA says to wait about ${(it + 59) / 60} min)" } ?: ""
            throw ModError(e.message + wait)
        }
    }

    private fun copy(input: java.io.InputStream, total: Long, progress: (Long, Long) -> Unit): Pair<File, String> {
        val tmp = File(dir, "import.part")
        val digest = MessageDigest.getInstance("SHA-256")
        var done = 0L
        tmp.outputStream().use { out ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                done += n
                if (done > MAX_ARCHIVE) { tmp.delete(); throw ModError("Mod files are limited to ${MAX_ARCHIVE shr 20} MiB.") }
                digest.update(buf, 0, n)
                out.write(buf, 0, n)
                progress(done, total)
            }
        }
        return tmp to GameData.hex(digest.digest())
    }

    private fun store(part: Pair<File, String>, name: String, source: String, entry: CatalogueFile?): Mod {
        val (tmp, sha) = part
        try {
            val meta = Zip.open(tmp).use { zip ->
                if (zip.entries.size > MAX_ENTRIES) throw ModError("This archive has too many files to be a mod.")
                if (zip.entries.any { it.size > MAX_ENTRY }) throw ModError("A file in this archive is larger than ${MAX_ENTRY shr 20} MiB.")
                zip.limitProblem()?.let { throw ModError(it) }
                val hyperspace = zip.get("data/hyperspace.xml") != null
                val m = zip.get("mod-appendix/metadata.xml")?.let { parseMetadata(zip.read(it)) }
                Triple(m?.title, m?.version, hyperspace)
            }
            val classified = if (entry != null) ModFile(entry.title, entry.version, sha, tmp.length(), true)
            else Catalogue.classify(catalogue, name, sha, tmp.length()).let { if (it.name.length > 100) it.copy(name = it.name.take(100)) else it }
            val target = file(sha)
            target.delete()
            if (!tmp.renameTo(target)) throw ModError("The mod file could not be stored.")
            return Mod(classified, meta.first, meta.third, source).also { put(it) }
        } catch (e: ModError) {
            tmp.delete()
            throw e
        } catch (e: Exception) {
            tmp.delete()
            throw ModError("This is not a mod archive (.ftl or .zip): ${e.message}")
        }
    }

    companion object {
        const val MAX_ARCHIVE = 1L shl 30
        const val MAX_ENTRY = 256L shl 20
        const val MAX_ENTRIES = 200_000
        const val MEGA_RANGE = 16L shl 20

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(1 shl 20)
                while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n) }
            }
            return GameData.hex(digest.digest())
        }
    }
}
