package ftl.android.app.store

import ftl.saves.Json
import ftl.saves.Limits
import ftl.saves.LocalSave
import ftl.saves.OldProfile
import ftl.saves.SaveMeta
import ftl.saves.Setup
import ftl.saves.objOrNull
import ftl.saves.str
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The device copy of every save (docs/SAVES.md): app-private files, each written atomically (temporary
 * file, then rename), so a crash or a killed process leaves the previous or the new version, never half of one.
 *
 * Accounts are kept apart, as in the browser: each has its own folder, and play without an account uses [LOCAL].
 *
 * accounts/<scope>/saves/<id>.json          the LocalSave without its run and profile
 * accounts/<scope>/saves/<id>.xml           the run
 * accounts/<scope>/saves/<id>.profile.xml   the save's unlocks, scores, statistics and Hyperspace data
 * accounts/<scope>/releases.txt
 * settings.xml                              the device's settings, for every account and save
 */
class DeviceStore(private val root: File, scope: String) {
    /** Whose saves the store holds: [scopeOf] an account, or [LOCAL]. */
    @Volatile var scope: String = scope
        @Synchronized set(value) {
            require(SCOPE.matches(value)) { "Invalid store scope" }
            field = value
        }

    init {
        require(SCOPE.matches(scope)) { "Invalid store scope" }
        // Before accounts were kept apart the files sat at the top; they belong to whoever is signed in now.
        val legacy = File(root, "saves")
        if (legacy.isDirectory) {
            val into = File(root, "accounts/$scope").apply { mkdirs() }
            for (name in listOf("saves", "profiles", "releases.txt")) {
                val from = File(root, name)
                val to = File(into, name)
                if (from.exists() && !to.exists()) from.renameTo(to)
            }
        }
        mergeProfiles()
    }

    /**
     * Before a save held its profile each setup had one: every save gets a copy, unsynced when that profile was. A vanilla
     * profile with no save (and no account copy of it) becomes a save of its own. The settings it held become the device's.
     * Once per account folder.
     */
    private fun mergeProfiles() {
        val found = ArrayList<String>()
        for (account in File(root, "accounts").listFiles { f -> f.isDirectory }.orEmpty()) {
            val old = File(account, "profiles")
            if (!old.isDirectory) continue
            val saves = File(account, "saves").apply { mkdirs() }
            val profiles = old.listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull { f -> runCatching { Json.parse(f.readText()).objOrNull() }.getOrNull() }
                .associateBy { it["setupKey"].str() ?: Setup.VANILLA_KEY }
            fun copyOf(key: String): Pair<String, Boolean>? {
                val p = profiles[key] ?: return null
                val current = p["current"].str()
                val dirty = current != null && current != p["engineBase"].str()
                val xml = (if (dirty) current else p["cloud"].str() ?: current) ?: return null
                val split = OldProfile.split(xml)
                split.settings?.let { found.add(it) }
                return split.profile to dirty
            }
            val list = listIn(saves)
            for (s in list) {
                val (xml, dirty) = copyOf(s.setup.key) ?: continue
                if (s.hasProfile) continue
                atomicWrite(File(saves, "${s.id}.profile.xml"), xml)
                atomicWrite(File(saves, "${s.id}.json"), Json.write(s.copy(hasProfile = true, dirty = s.dirty || dirty).toJson(withData = false)))
            }
            val vanilla = copyOf(Setup.VANILLA_KEY)
            if (vanilla != null && list.none { it.setup.vanilla } && list.size < Limits.SAVES && (vanilla.second || account.name == LOCAL)) {
                val id = java.util.UUID.randomUUID().toString()
                atomicWrite(File(saves, "$id.profile.xml"), vanilla.first)
                atomicWrite(File(saves, "$id.json"), Json.write(LocalSave(id, "Save 1", Setup.VANILLA, java.time.Instant.now().toString(), SaveMeta(), 0, true, false,
                    hasProfile = true).toJson(withData = false)))
            }
            for (key in profiles.keys) copyOf(key)
            old.renameTo(File(account, "profiles.old"))
        }
        if (!settings.exists()) OldProfile.choose(found)?.let { atomicWrite(settings, it) }
    }

    private fun dir(scope: String, name: String) = File(root, "accounts/$scope/$name").apply { mkdirs() }
    private val saves get() = dir(scope, "saves")
    private val settings get() = File(root, "settings.xml")
    private val releases get() = File(dir(scope, ""), "releases.txt")

    @Synchronized
    fun list(): List<LocalSave> = listIn(saves)

    private fun listIn(dir: File): List<LocalSave> = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        .mapNotNull { f -> runCatching { LocalSave.fromJson(Json.parse(f.readText())) }.getOrNull() }
        .sortedByDescending { it.meta.playedOn?.at ?: it.createdUtc }

    /** The save with its run and profile loaded. */
    @Synchronized
    fun get(id: String): LocalSave? = getIn(saves, id)

    private fun getIn(dir: File, id: String): LocalSave? {
        val meta = File(dir, "$id.json")
        if (!validId(id) || !meta.exists()) return null
        val save = LocalSave.fromJson(Json.parse(meta.readText()))
        val run = File(dir, "$id.xml")
        val profile = File(dir, "$id.profile.xml")
        return (if (save.hasRun && run.exists()) save.copy(run = run.readText()) else save.copy(run = null, hasRun = false))
            .let { if (it.hasProfile && profile.exists()) it.copy(profile = profile.readText()) else it.copy(profile = null, hasProfile = false) }
    }

    /** The saves another scope holds (play without an account), for "Enable cloud saves". */
    @Synchronized
    fun savesOf(scope: String): List<LocalSave> = listIn(dir(scope, "saves"))

    /** Moves another scope's saves into this one as new, unsynced saves (they upload next); returns how many moved. */
    @Synchronized
    fun adopt(from: String): Int {
        if (from == scope) return 0
        val dir = dir(from, "saves")
        var moved = 0
        for (s in listIn(dir)) {
            val save = getIn(dir, s.id) ?: continue
            put(save.copy(base = 0, dirty = true, overridden = null, nameDirty = false))
            File(dir, "${s.id}.json").delete()
            File(dir, "${s.id}.xml").delete()
            File(dir, "${s.id}.profile.xml").delete()
            moved++
        }
        return moved
    }

    /** Stores the save with its run and profile. */
    @Synchronized
    fun put(save: LocalSave) {
        require(validId(save.id)) { "Invalid save id" }
        val run = File(saves, "${save.id}.xml")
        if (save.run != null) atomicWrite(run, save.run!!) else run.delete()
        val profile = File(saves, "${save.id}.profile.xml")
        if (save.profile != null) atomicWrite(profile, save.profile!!) else profile.delete()
        atomicWrite(File(saves, "${save.id}.json"), Json.write(save.toJson(withData = false)))
    }

    /** Reads, changes and writes one save under the store's lock, so the game and the syncer never overwrite each other. */
    @Synchronized
    fun update(id: String, change: (LocalSave) -> LocalSave?): LocalSave? {
        val current = get(id) ?: return null
        val next = change(current) ?: return current
        put(next)
        return next
    }

    /** Saves whose lock this device still has to give back (closed while offline). */
    @Synchronized
    fun pendingReleases(): Set<String> = releases.takeIf { it.exists() }?.readLines()?.filter { validId(it) }?.toSet() ?: emptySet()

    @Synchronized
    fun setPendingRelease(id: String, pending: Boolean) {
        val set = pendingReleases().toMutableSet()
        if (pending) set.add(id) else set.remove(id)
        atomicWrite(releases, set.joinToString("\n"))
    }

    @Synchronized
    fun delete(id: String) {
        if (!validId(id)) return
        File(saves, "$id.json").delete()
        File(saves, "$id.xml").delete()
        File(saves, "$id.profile.xml").delete()
    }

    /** The device's settings (language, volumes, hotkeys, options): no save or account holds them. */
    @Synchronized
    fun settings(): String? = settings.takeIf { it.exists() }?.readText()

    @Synchronized
    fun putSettings(xml: String) {
        if (settings() != xml) atomicWrite(settings, xml)
    }

    companion object {
        private val ID = Regex("[0-9a-fA-F-]{1,64}")
        private val SCOPE = Regex("[a-z0-9-]{1,64}")

        /** The scope of play without an account (the browser's `local-device`). */
        const val LOCAL = "local-device"

        /** An account's scope: its server and user id, hashed into a folder name. */
        fun scopeOf(server: String, userId: String): String =
            "u-" + java.security.MessageDigest.getInstance("SHA-256").digest("$server|$userId".encodeToByteArray())
                .take(16).joinToString("") { "%02x".format(it) }

        fun validId(id: String) = ID.matches(id)

        fun atomicWrite(target: File, text: String) {
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.outputStream().use { out ->
                out.write(text.encodeToByteArray())
                out.fd.sync()
            }
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }
}
