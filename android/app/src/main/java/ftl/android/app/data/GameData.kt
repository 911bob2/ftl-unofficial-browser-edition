package ftl.android.app.data

import android.content.ContentResolver
import android.content.res.AssetManager
import android.net.Uri
import ftl.android.GameFiles
import ftl.android.app.net.Api
import ftl.saves.Json
import ftl.saves.long
import ftl.saves.obj
import ftl.saves.str
import java.io.File
import java.security.MessageDigest

/**
 * The game data on this device: the player's own `ftl.dat`, imported once and never uploaded (External users and
 * offline play), or the server's prepared assets (Admin and User accounts, as the website serves them). `version` is
 * the SHA-256 of the `ftl.dat` (the website's `assetVersion`), which saves record as `gameData`.
 *
 * The server's files belong to the account that downloaded them (docs/WEBSITE.md): `serverOwner` names the signed-in
 * account allowed server assets, and reconcile() deletes them for anyone else. Without them, only an imported file plays.
 */
class GameData(private val root: File, private val assets: AssetManager, private val serverOwner: () -> String?) {
    private val dir = File(root, "data").apply { mkdirs() }
    private val datFile = File(dir, "ftl.dat")
    private val serverDir = File(dir, "server")
    private val infoFile = File(dir, "data.json")
    private val ownerFile = File(serverDir, "owner.json")

    data class Info(val version: String, val size: Long, val source: String)

    val info: Info? get() = serverInfo() ?: localInfo()

    private fun localInfo(): Info? = runCatching {
        val o = Json.parse(infoFile.readText()) as Json.Obj
        Info(o["version"].str()!!, o["size"].long()!!, "local")
    }.getOrNull()?.takeIf { datFile.exists() && datFile.length() == it.size }

    private fun serverInfo(): Info? {
        val owner = serverOwner() ?: return null
        return runCatching {
            val o = Json.parse(ownerFile.readText()) as Json.Obj
            if (o["owner"].str() != owner) return null
            Info(o["version"].str()!!, o["size"].long()!!, "server")
        }.getOrNull()?.takeIf { File(serverDir, "boot.pack").exists() }
    }

    /** Deletes the server's game files unless the current account may use them. */
    @Synchronized
    fun reconcile() {
        close()
        if (serverDir.exists() && serverInfo() == null) serverDir.deleteRecursively()
    }

    /**
     * Copies a chosen `ftl.dat` into app storage while hashing it, then checks its index. `expected`, when known
     * (the server's assetVersion for External users), must match. Progress is bytes copied out of the total.
     */
    fun import(resolver: ContentResolver, uri: Uri, expected: String?, progress: (Long, Long) -> Unit): Info {
        val total = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        if (total > 1024L * 1024 * 1024) throw InvalidDat("This file is too large to be an ftl.dat.")
        val tmp = File(dir, "ftl.dat.tmp")
        val digest = MessageDigest.getInstance("SHA-256")
        var done = 0L
        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "The file could not be opened." }
            tmp.outputStream().use { out ->
                val buf = ByteArray(1 shl 20)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                    out.write(buf, 0, n)
                    done += n
                    progress(done, total)
                }
            }
        }
        val hash = hex(digest.digest())
        try {
            if (expected != null && hash != expected)
                throw InvalidDat("This ftl.dat does not match this server's game files. Choose the same unmodified FTL 1.6.14 data file.")
            DatFile.index(tmp)
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
        close()
        datFile.delete()
        if (!tmp.renameTo(datFile)) throw IllegalStateException("The game file could not be stored.")
        infoFile.writeText(Json.write(obj("version" to hash, "size" to datFile.length())))
        return Info(hash, datFile.length(), "local")
    }

    /**
     * Admin and User accounts: the server's prepared game files, downloaded once (boot pack and every sound), so play
     * never needs the network. Progress is (bytes so far, total bytes).
     */
    fun downloadFromServer(api: Api, progress: (Long, Long) -> Unit): Info {
        val manifest = api.request("GET", "/api/assets/manifest").json as Json.Obj
        val version = manifest["assetVersion"].str() ?: throw InvalidDat("The server has no game files.")
        val audio = (manifest["audio"] as? Json.Arr)?.items.orEmpty().map { it as Json.Obj }
        val bootBytes = manifest["bootBytes"].long() ?: 0
        val total = bootBytes + audio.sumOf { it["length"].long() ?: 0 }
        val staging = File(dir, "server.part").apply { deleteRecursively(); mkdirs() }
        val audioDir = File(staging, "audio").apply { mkdirs() }
        var done = 0L
        api.download("/api/assets/boot", File(staging, "boot.pack")) { progress(done + it, total) }
        done += File(staging, "boot.pack").length()
        PackFile(File(staging, "boot.pack")).close()
        for (a in audio) {
            val hash = a["hash"].str() ?: continue
            if (!ftl.saves.isSha256(hash)) continue
            val target = File(audioDir, hash + audioExtension(a))
            api.download("/api/assets/audio/$hash", target) { progress(done + it, total) }
            done += target.length()
        }
        File(staging, "manifest.json").writeText(Json.write(manifest))
        val owner = serverOwner() ?: throw IllegalStateException("This account may not use the server's game files.")
        File(staging, "owner.json").writeText(Json.write(obj("owner" to owner, "version" to version, "size" to total)))
        close()
        serverDir.deleteRecursively()
        if (!staging.renameTo(serverDir)) throw IllegalStateException("The game files could not be stored.")
        return Info(version, total, "server")
    }

    fun remove() {
        close()
        datFile.delete()
        serverDir.deleteRecursively()
        infoFile.delete()
    }

    /** The open game data: non-audio names, audio names, file bytes and sound bytes. */
    private interface Source : AutoCloseable {
        val names: Collection<String>
        val audioNames: Collection<String>
        fun read(name: String): ByteArray?
        fun audio(path: String): ByteArray?
        fun size(name: String): Long?
    }

    private var open: Source? = null

    @Synchronized
    private fun source(): Source = open ?: (if (info?.source == "server") server() else dat()).also { open = it }

    private fun dat(): Source {
        val dat = DatFile(datFile)
        return object : Source {
            override val names = dat.entries.keys.filter { !isAudio(it) }
            override val audioNames = dat.entries.keys.filter { isAudio(it) }
            override fun read(name: String) = dat.read(name)
            override fun audio(path: String) = dat.read(path)
            override fun size(name: String) = dat.entries[name]?.length?.toLong()
            override fun close() = dat.close()
        }
    }

    private fun server(): Source {
        val pack = PackFile(File(serverDir, "boot.pack"))
        val manifest = Json.parse(File(serverDir, "manifest.json").readText()) as Json.Obj
        val sounds = (manifest["audio"] as? Json.Arr)?.items.orEmpty().mapNotNull { it as? Json.Obj }
            .associate { (it["name"].str() ?: "") to File(File(serverDir, "audio"), (it["hash"].str() ?: "") + audioExtension(it)) }
        return object : Source {
            // The server pack carries its engine resources too; the app uses its own (the engine it was built with).
            override val names = pack.entries.keys.filter { !it.startsWith("engine/") && !isAudio(it) }
            override val audioNames = sounds.keys
            override fun read(name: String) = pack.read(name)
            override fun audio(path: String) = sounds[path]?.takeIf { it.exists() }?.readBytes()
            override fun size(name: String) = pack.entries[name]?.length?.toLong() ?: sounds[name]?.takeIf { it.exists() }?.length()
            override fun close() = pack.close()
        }
    }

    @Synchronized
    fun close() {
        open?.close()
        open = null
    }

    /** Every game data file name, sounds included (what a mod merge patches over). */
    fun baseNames(): List<String> = source().let { it.names + it.audioNames }

    /** A game data file's bytes (not a sound). */
    fun readBase(name: String): ByteArray? = source().read(name)

    /** A sound's bytes (patch scripts may read it). */
    fun readBaseAudio(name: String): ByteArray? = source().audio(name)

    /** A game data file's size without reading it (0 for a sound not on the device). */
    fun baseSize(name: String): Long = source().size(name) ?: 0

    /**
     * The files the engine reads: the game data, the engine's own resources (APK assets) and an optional mod overlay
     * (merged files, read when the engine asks for them; sounds in modAudio).
     */
    fun files(overlay: Map<String, () -> ByteArray?> = emptyMap(), removed: Set<String> = emptySet(), modAudio: Map<String, () -> ByteArray?> = emptyMap()): GameFiles {
        val data = source()
        val engine = ArrayList<String>().also { listAssets("engine", it) }
        val names = LinkedHashSet<String>()
        for (n in data.names) if (n !in removed) names.add(n)
        for (n in overlay.keys) if (!isAudio(n)) names.add(n)
        names.addAll(engine)
        val engineSet = engine.toHashSet()
        val list = names.toList()
        return object : GameFiles {
            override fun names(): List<String> = list
            override fun contains(name: String) = name in names
            override fun read(name: String): ByteArray? = when {
                name in engineSet -> assets.open(name).use { it.readBytes() }
                isAudio(name) -> null
                overlay.containsKey(name) -> overlay[name]?.invoke()
                name in removed -> null
                else -> data.read(name)
            }
            override fun audio(path: String): ByteArray? = modAudio[path]?.invoke() ?: if (path in removed) null else data.audio(path)
        }
    }

    private fun audioExtension(entry: Json.Obj) = entry["extension"].str()?.takeIf { it == ".ogg" || it == ".wav" } ?: ".ogg"

    private fun listAssets(path: String, out: MutableList<String>) {
        val children = assets.list(path).orEmpty()
        if (children.isEmpty()) out.add(path)
        else for (c in children) listAssets("$path/$c", out)
    }

    companion object {
        /** FTL 1.6.14 with Advanced Edition (Steam, GOG and Humble ship the same file). */
        const val FTL_1_6_14 = "d7c4744f91f96303c0342b30c2dcb63843050e0f489426200c683d02fb8abc7d"

        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    }
}
