package ftl.saves

/** A recovery snapshot of a save in the account (docs/SAVES.md, "Recovery snapshots"): the server keeps the last ten. */
data class Snapshot(val revision: Long, val createdUtc: String, val kind: String)

/** Saves > ⋯ > Recovery snapshots, the same on the website and in the app. */
object Snapshots {
    /** GET /api/saves/{id}/snapshots, newest first; entries that do not read are left out. */
    fun parse(j: Json?): List<Snapshot> = j.arr().take(Limits.SNAPSHOTS * 10).mapNotNull { e ->
        val o = e.objOrNull() ?: return@mapNotNull null
        val revision = o["revision"].long() ?: return@mapNotNull null
        Snapshot(revision, utc(o["createdUtc"].str() ?: ""), o["kind"].str()?.take(16) ?: "")
    }.sortedByDescending { it.revision }

    /** A time without a zone is UTC, as the server stores it. */
    private fun utc(t: String) = if (t.isEmpty() || t.endsWith("Z") || Regex("""[+-]\d\d:\d\d$""").containsMatchIn(t)) t else t + "Z"

    /** The revision the save has now: the cloud's when known, else the newest snapshot (each write makes one). */
    fun current(list: List<Snapshot>, cloudRevision: Long?): Long? = cloudRevision ?: list.firstOrNull()?.revision

    /** Restore is offered for every snapshot but the current one, and never for the save being played. */
    fun canRestore(s: Snapshot, current: Long?, playing: Boolean): Boolean = !playing && s.revision != current

    /** How the list names a snapshot's kind. */
    fun kindLabel(kind: String): String = when (kind) {
        "auto", "manual", "import", "restore" -> kind
        else -> kind.ifEmpty { "save" }
    }

    /** This device's copy after a restore: the account's new revision, nothing unsynced, no takeover notice. */
    fun restored(local: LocalSave?, cloud: CloudSave, run: String?, profile: String?): LocalSave =
        Sync.fetched(local?.copy(overridden = null, dirty = false), cloud, run, profile)
}
