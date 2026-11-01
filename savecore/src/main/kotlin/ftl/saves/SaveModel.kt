package ftl.saves

/** Where and when a save was last written. */
data class PlayedOn(val platform: String, val device: String, val deviceId: String, val at: String) {
    fun toJson(): Json.Obj = obj("platform" to platform, "device" to device, "deviceId" to deviceId, "at" to at)

    companion object {
        fun fromJson(j: Json?): PlayedOn? {
            val o = j.objOrNull() ?: return null
            return PlayedOn(o["platform"].str() ?: "", o["device"].str() ?: "", o["deviceId"].str() ?: "", o["at"].str() ?: "")
        }
    }
}

/** What the save list shows about a run. */
data class Summary(
    val ship: String? = null, val shipId: String? = null, val sector: Int? = null, val scrap: Int? = null,
    val hull: Int? = null, val maxHull: Int? = null, val playSeconds: Long = 0,
) {
    fun toJson(): Json.Obj = obj("ship" to ship, "shipId" to shipId, "sector" to sector, "scrap" to scrap, "hull" to hull,
        "maxHull" to maxHull, "playSeconds" to playSeconds)

    companion object {
        fun fromJson(j: Json?): Summary {
            val o = j.objOrNull() ?: return Summary()
            return Summary(o["ship"].str(), o["shipId"].str(), o["sector"].int(), o["scrap"].int(), o["hull"].int(),
                o["maxHull"].int(), o["playSeconds"].long() ?: 0)
        }
    }
}

/** A save's metadata (the `meta` object the server stores as given). Unknown fields are kept. */
data class SaveMeta(
    val playedOn: PlayedOn? = null, val engineBuild: String? = null, val gameData: String? = null,
    val summary: Summary = Summary(), val extra: Map<String, Json> = emptyMap(),
) {
    fun toJson(): Json.Obj {
        val map = LinkedHashMap<String, Json>(extra)
        map["playedOn"] = playedOn?.toJson() ?: Json.Null
        map["engineBuild"] = toJson(engineBuild)
        map["gameData"] = toJson(gameData)
        map["summary"] = summary.toJson()
        return Json.Obj(map)
    }

    companion object {
        private val KNOWN = setOf("playedOn", "engineBuild", "gameData", "summary")

        fun fromJson(j: Json?): SaveMeta {
            val o = j.objOrNull() ?: return SaveMeta()
            return SaveMeta(PlayedOn.fromJson(o["playedOn"]), o["engineBuild"].str(), o["gameData"].str(),
                Summary.fromJson(o["summary"]), o.fields.filterKeys { it !in KNOWN })
        }
    }
}

/** Who holds a save's lock: one running game (browser tab or app install). */
data class LockInfo(val instance: String, val deviceId: String, val device: String, val platform: String, val since: String, val lastSeen: String) {
    fun toJson(): Json.Obj = obj("instance" to instance, "deviceId" to deviceId, "device" to device, "platform" to platform,
        "since" to since, "lastSeen" to lastSeen)

    companion object {
        fun fromJson(j: Json?): LockInfo? {
            val o = j.objOrNull() ?: return null
            return LockInfo(o["instance"].str() ?: "", o["deviceId"].str() ?: "", o["device"].str() ?: "",
                o["platform"].str() ?: "", o["since"].str() ?: "", o["lastSeen"].str() ?: "")
        }
    }
}

/** A save as the server lists it (`SaveInfo`, docs/SAVES.md). */
data class CloudSave(
    val id: String, val name: String, val setup: Setup, val createdUtc: String, val updatedUtc: String,
    val revision: Long, val meta: SaveMeta, val hasRun: Boolean, val runBytes: Long, val lock: LockInfo?,
    val hasProfile: Boolean = false, val profileBytes: Long = 0,
) {
    fun toJson(): Json.Obj = obj("id" to id, "name" to name, "setup" to setup.toJson(), "createdUtc" to createdUtc,
        "updatedUtc" to updatedUtc, "revision" to revision, "meta" to meta.toJson(), "hasRun" to hasRun, "runBytes" to runBytes,
        "hasProfile" to hasProfile, "profileBytes" to profileBytes, "lock" to lock?.toJson())

    companion object {
        fun fromJson(j: Json?): CloudSave {
            val o = j.objOrNull() ?: throw IllegalArgumentException("save is not an object")
            return CloudSave(o["id"].str() ?: "", o["name"].str() ?: "", Setup.fromJson(o["setup"]), o["createdUtc"].str() ?: "",
                o["updatedUtc"].str() ?: "", o["revision"].long() ?: 0, SaveMeta.fromJson(o["meta"]), o["hasRun"].bool() ?: false,
                o["runBytes"].long() ?: 0, LockInfo.fromJson(o["lock"]), o["hasProfile"].bool() ?: false, o["profileBytes"].long() ?: 0)
        }
    }
}

/**
 * A save in the device store: its run and its profile (unlocks, achievements, scores, statistics, Hyperspace data), which
 * sync as one. `base` is the cloud revision this copy derives from (0: never uploaded), `dirty` that it has progress the
 * cloud lacks, `hasRun` and `hasProfile` that they are stored here (a cloud save not yet downloaded has neither).
 * `overridden` records that another instance took the lock while this copy had unsynced progress.
 */
data class LocalSave(
    val id: String, val name: String, val setup: Setup, val createdUtc: String, val meta: SaveMeta,
    val base: Long, val dirty: Boolean, val hasRun: Boolean, val run: String? = null, val nameDirty: Boolean = false,
    val overridden: Overridden? = null, val hasProfile: Boolean = false, val profile: String? = null,
) {
    fun toJson(withData: Boolean = true): Json.Obj = obj("id" to id, "name" to name, "setup" to setup.toJson(),
        "createdUtc" to createdUtc, "meta" to meta.toJson(), "base" to base, "dirty" to dirty, "hasRun" to hasRun,
        "run" to if (withData) run else null, "hasProfile" to hasProfile, "profile" to if (withData) profile else null,
        "nameDirty" to nameDirty, "overridden" to overridden?.toJson())

    companion object {
        fun fromJson(j: Json?): LocalSave {
            val o = j.objOrNull() ?: throw IllegalArgumentException("save is not an object")
            return LocalSave(o["id"].str() ?: "", o["name"].str() ?: "", Setup.fromJson(o["setup"]), o["createdUtc"].str() ?: "",
                SaveMeta.fromJson(o["meta"]), o["base"].long() ?: 0, o["dirty"].bool() ?: false, o["hasRun"].bool() ?: false,
                o["run"].str(), o["nameDirty"].bool() ?: false, Overridden.fromJson(o["overridden"]), o["hasProfile"].bool() ?: false, o["profile"].str())
        }
    }
}

/** The notice an overridden device shows once: who took the save and when. */
data class Overridden(val device: String, val platform: String, val at: String) {
    fun toJson(): Json.Obj = obj("device" to device, "platform" to platform, "at" to at)

    companion object {
        fun fromJson(j: Json?): Overridden? {
            val o = j.objOrNull() ?: return null
            return Overridden(o["device"].str() ?: "", o["platform"].str() ?: "", o["at"].str() ?: "")
        }
    }
}

object SaveNames {
    const val MAX = 40

    /** A trimmed name of 1 to 40 characters without control characters, or null. */
    fun clean(name: String?): String? {
        val n = (name ?: "").trim()
        return if (n.isEmpty() || n.length > MAX || n.any { it < ' ' || it == '\u007f' }) null else n
    }

    /** "Save N" with the smallest N not used yet. */
    fun next(existing: Collection<String>): String {
        var n = 1
        while ("Save $n" in existing) n++
        return "Save $n"
    }
}

object Limits {
    const val SAVES = 30
    const val SNAPSHOTS = 10
    const val ACCOUNT_BYTES = 256L * 1024 * 1024
    const val RUN_BYTES = 16 * 1024 * 1024
}
