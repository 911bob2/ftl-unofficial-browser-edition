package ftl.android.app.mods

import android.util.Log
import ftl.android.GameFiles
import ftl.android.ModSession
import ftl.android.app.data.GameData
import ftl.android.app.data.isAudio
import ftl.mods.FTLMAN_VERSION
import ftl.mods.MergeArchive
import ftl.mods.ArchiveRef
import ftl.mods.Zip
import ftl.mods.hyperspaceScripts
import ftl.mods.mergeMods
import ftl.saves.Json
import ftl.saves.Setup
import ftl.saves.Sha256
import ftl.saves.arr
import ftl.saves.int
import ftl.saves.missingMods
import ftl.saves.obj
import ftl.saves.str
import java.io.File

/**
 * A modded start, as the website's web/mods/boot.mjs: the save's archives merged over the game data with ftlman's
 * rules (cached per mod set and game data), read lazily from the archives while the engine runs, and the mod session
 * the engine reads (Hyperspace, its version and Lua scripts).
 */
class ModLaunch private constructor(
    val files: GameFiles,
    val session: ModSession,
    val warnings: List<String>,
    private val zips: List<Zip>,
    /** The mods in load order and, per script of the session, the index of the last one with that file. */
    val mods: List<ModLibrary.Mod>,
    val scriptOwners: Map<String, Int>,
) : AutoCloseable {
    override fun close() = zips.forEach { runCatching { it.close() } }

    class MissingMods(val mods: List<ftl.saves.ModFile>) :
        Exception("This save needs mod files this device does not have: " + mods.joinToString { "${it.name} (${it.sha256.take(12)}…)" })

    companion object {
        private const val MERGE_VERSION = 1

        // What a patch script (and an append's target) reads from the game data, as the website's merge worker.
        private fun readBase(gameData: GameData, name: String): ByteArray =
            gameData.readBase(name) ?: gameData.readBaseAudio(name)
                ?: if (name.endsWith(".xml") || name.endsWith(".txt")) ByteArray(0)
                else throw IllegalStateException("$name is not available to patch scripts here")

        fun prepare(root: File, gameData: GameData, library: ModLibrary, setup: Setup, assets: android.content.res.AssetManager? = null,
                    progress: (String) -> Unit): ModLaunch {
            val missing = missingMods(setup, library.list().map { it.sha256 }.toSet())
            if (missing.isNotEmpty()) throw MissingMods(missing)
            val mods = setup.mods.map { library.get(it.sha256)!! }
            val zips = ArrayList<Zip>()
            try {
                for (m in mods) zips.add(Zip.open(library.file(m.sha256)))
                // Our touch wording over the mods it was written for, merged after them (TouchPatches).
                val patches = if (assets != null) TouchPatches.forMods(mods.map { it.sha256 }) else emptyList()
                val patchArchives = patches.map { TouchPatches.archive(assets!!, it) }
                for (a in patchArchives) zips.add(a.zip)
                val version = gameData.info?.version ?: throw IllegalStateException("No game files")
                val key = Sha256.hex((listOf("merge", MERGE_VERSION, FTLMAN_VERSION, version, *mods.map { it.sha256 }.toTypedArray()) +
                    (if (patches.isEmpty()) emptyList() else listOf("touch", TouchPatches.key(patches)))).joinToString("|"))
                val cache = File(root, "merged/$key")
                val index = File(cache, "index.json")
                val text = LinkedHashMap<String, File>()
                val refs = LinkedHashMap<String, Pair<Int, String>>()
                val removed = LinkedHashSet<String>()
                val warnings = ArrayList<String>()
                if (index.exists()) {
                    progress("Loading the mod merge…")
                    val o = Json.parse(index.readText()) as Json.Obj
                    for (t in o["text"].arr()) { val a = t.arr(); text[a[0].str()!!] = File(cache, "text/" + a[1].str()!!) }
                    for (r in o["refs"].arr()) { val a = r.arr(); refs[a[0].str()!!] = a[1].int()!! to a[2].str()!! }
                    o["removed"].arr().mapTo(removed) { it.str()!! }
                    o["warnings"].arr().mapTo(warnings) { it.str()!! }
                } else {
                    progress("Merging mods…")
                    val result = mergeMods(gameData.baseNames(), { readBase(gameData, it) },
                        mods.mapIndexed { i, m -> MergeArchive(m.label, zips[i]) } + patchArchives, lazyBinary = true,
                        onProgress = { progress("Merging ${it.name}… ${it.file * 100 / maxOf(1, it.files)}%") },
                        baseSize = { gameData.baseSize(it) }, patchNative = FtlmanLua,
                        onLuaLog = { _, text -> Log.i("FTL", "[mod script] $text") })
                    File(root, "merged").listFiles()?.forEach { it.deleteRecursively() }
                    val textDir = File(cache, "text").apply { mkdirs() }
                    val textIndex = ArrayList<Json>()
                    val refIndex = ArrayList<Json>()
                    for ((name, v) in result.files) {
                        when (v) {
                            is ByteArray -> {
                                val f = File(textDir, "${text.size}")
                                f.writeBytes(v)
                                text[name] = f
                                textIndex.add(Json.Arr(listOf(Json.Str(name), Json.Str(f.name))))
                            }
                            is ArchiveRef -> {
                                refs[name] = v.archive to v.entry.name
                                refIndex.add(Json.Arr(listOf(Json.Str(name), Json.Num(v.archive.toDouble()), Json.Str(v.entry.name))))
                            }
                        }
                    }
                    removed.addAll(result.removed)
                    warnings.addAll(result.warnings)
                    File(cache, "index.json.tmp").apply {
                        writeText(Json.write(obj("text" to Json.Arr(textIndex), "refs" to Json.Arr(refIndex),
                            "removed" to Json.Arr(removed.map { Json.Str(it) }), "warnings" to Json.Arr(warnings.map { Json.Str(it) }))))
                        renameTo(index)
                    }
                }
                fun ref(name: String): () -> ByteArray? {
                    val (archive, entry) = refs.getValue(name)
                    return { zips[archive].get(entry)?.let { synchronized(zips[archive]) { zips[archive].read(it) } } }
                }
                val overlay = LinkedHashMap<String, () -> ByteArray?>()
                val modAudio = HashMap<String, () -> ByteArray?>()
                for ((name, f) in text) (if (isAudio(name)) modAudio else overlay)[name] = { f.readBytes() }
                for (name in refs.keys) (if (isAudio(name)) modAudio else overlay)[name] = ref(name)

                // The base game has no data/hyperspace.xml, so the overlay alone decides Hyperspace and its scripts.
                val hsXml = overlay["data/hyperspace.xml"]?.invoke()
                val hyperspace = hsXml != null
                val hs = mods.firstOrNull { (it.title ?: it.file.name).equals("hyperspace", ignoreCase = true) || it.file.name.equals("hyperspace", ignoreCase = true) }
                val session = ModSession(setup.key, mods.map { ModSession.Mod(it.file.name, it.file.version.ifEmpty { null }, it.sha256, it.file.size) },
                    hyperspace, if (hyperspace) hs?.file?.version?.ifEmpty { null } else null, if (hyperspace) hyperspaceScripts(hsXml) else emptyList())
                val owners = HashMap<String, Int>()
                for (path in session.scripts) for (i in mods.indices.reversed()) if (zips[i].get(path) != null) { owners[path] = i; break }
                return ModLaunch(gameData.files(overlay, removed, modAudio), session, warnings, zips, mods, owners)
            } catch (e: Exception) {
                zips.forEach { runCatching { it.close() } }
                throw e
            }
        }
    }
}
