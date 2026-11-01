package ftl.saves

/** What a save's two copies are to each other (docs/SAVES.md, "Sync status and decisions"). */
enum class SyncStatus {
    /** Only on this device, never uploaded. */
    LOCAL,
    SYNCED,
    /** This device has progress the cloud lacks; the cloud has nothing newer. */
    PENDING,
    /** The cloud is newer and this device has nothing unsynced. */
    BEHIND,
    /** Both changed since this device last synced. */
    CONFLICT,
    /** Deleted on another device. */
    DELETED,
    /** In the cloud, not (yet) downloaded to this device (or only part of it). */
    CLOUD,
    /** Another instance overrode this device's lock while it had unsynced progress. */
    OVERRIDDEN,
}

enum class SyncAction {
    NONE,
    /** Upload a save the cloud does not have yet (If-Match 0). */
    CREATE,
    /** Upload this device's copy (If-Match base; needs the lock when one exists). */
    UPLOAD,
    /** Replace this device's copy with the cloud's. Nothing is lost. */
    FETCH,
    /** Ask: "Use the cloud copy" or "Keep this device's copy". */
    ASK_CONFLICT,
    /** Remove this device's copy (it had nothing unsynced). */
    REMOVE_LOCAL,
    /** Ask: "Upload again" or "Delete here too". */
    ASK_DELETED,
    /** Show the save; its run downloads when it is played. */
    LIST_ONLY,
    /** Tell the player once, then replace this device's copy with the cloud's. */
    NOTIFY_OVERRIDDEN,
}

data class SyncDecision(val status: SyncStatus, val action: SyncAction, val rename: Boolean = false)

object Sync {
    /**
     * The decision for one save from the device copy, the cloud's metadata and whether the cloud has a tombstone for
     * it. Pure: the caller acts (and only when online and signed in).
     */
    fun decide(local: LocalSave?, cloud: CloudSave?, deleted: Boolean): SyncDecision {
        if (local == null) {
            return if (cloud == null || deleted) SyncDecision(SyncStatus.DELETED, SyncAction.NONE)
            else SyncDecision(SyncStatus.CLOUD, SyncAction.LIST_ONLY)
        }
        if (local.overridden != null) return SyncDecision(SyncStatus.OVERRIDDEN, SyncAction.NOTIFY_OVERRIDDEN)
        // A save the cloud no longer has: deleted elsewhere (a tombstone, or one purged after it expired).
        if (deleted || (cloud == null && local.base > 0)) {
            return SyncDecision(SyncStatus.DELETED, if (local.dirty) SyncAction.ASK_DELETED else SyncAction.REMOVE_LOCAL)
        }
        if (cloud == null) return SyncDecision(SyncStatus.LOCAL, SyncAction.CREATE)
        val rename = local.nameDirty && local.name != cloud.name
        return when {
            local.base == cloud.revision && local.dirty -> SyncDecision(SyncStatus.PENDING, SyncAction.UPLOAD, rename)
            local.base == cloud.revision && ((!local.hasRun && cloud.hasRun) || (!local.hasProfile && cloud.hasProfile)) -> SyncDecision(SyncStatus.CLOUD, SyncAction.LIST_ONLY, rename)
            local.base == cloud.revision -> SyncDecision(SyncStatus.SYNCED, SyncAction.NONE, rename)
            // The cloud moved on (or went back, after a server restore): with unsynced progress here, the player chooses.
            local.dirty || local.base > cloud.revision -> SyncDecision(SyncStatus.CONFLICT, SyncAction.ASK_CONFLICT, rename)
            else -> SyncDecision(SyncStatus.BEHIND, SyncAction.FETCH, rename)
        }
    }

    /** The device copy after the engine wrote a save: dirty, with fresh metadata. */
    fun written(local: LocalSave, run: String?, meta: SaveMeta): LocalSave =
        local.copy(run = run, hasRun = run != null, meta = meta, dirty = true)

    /** The device copy after the engine wrote the save's profile; null when it did not change. */
    fun profileWritten(local: LocalSave, xml: String): LocalSave? =
        if (local.profile == xml) null else local.copy(profile = xml, hasProfile = true, dirty = true)

    /** The device copy after an upload returned `revision`; still dirty if the run or profile changed meanwhile. */
    fun uploaded(local: LocalSave, uploadedRun: String?, uploadedProfile: String?, revision: Long): LocalSave =
        local.copy(base = revision, dirty = local.run != uploadedRun || local.profile != uploadedProfile, nameDirty = false)

    /** The device copy replaced by the cloud's (fetch, "Use the cloud copy", or after an override notice). */
    fun fetched(local: LocalSave?, cloud: CloudSave, run: String?, profile: String?): LocalSave = LocalSave(
        id = cloud.id, name = cloud.name, setup = cloud.setup, createdUtc = cloud.createdUtc, meta = cloud.meta,
        base = cloud.revision, dirty = false, hasRun = run != null, run = run, nameDirty = false, overridden = null,
        hasProfile = profile != null, profile = profile,
    ).let { if (local != null && local.nameDirty) it.copy(name = local.name, nameDirty = true) else it }

    /** "Keep this device's copy": upload over the cloud's newer revision. */
    fun keepMine(local: LocalSave, cloud: CloudSave): LocalSave = local.copy(base = cloud.revision, dirty = true)

    /**
     * Saves > Advanced: a run moved to other mods belongs to that set from here on. The engine refuses a run of another
     * set, so the run names the new one; the Hyperspace data of the old set goes with its name, and the load drops what
     * the new files lack.
     */
    fun runForSetup(run: String, setupKey: String): String {
        val block = if (setupKey == Setup.VANILLA_KEY) "" else "<hyperspace modSet=\"$setupKey\" />"
        val start = run.indexOf("<hyperspace modSet=")
        if (start >= 0) {
            val open = run.indexOf('>', start)
            val end = if (run[open - 1] == '/') open + 1 else run.lastIndexOf("</hyperspace>").let { if (it < start) open + 1 else it + "</hyperspace>".length }
            return run.substring(0, start) + block + run.substring(end)
        }
        // A run without mods: the block goes last in the root.
        val close = run.lastIndexOf("</")
        return if (close < 0) run else run.substring(0, close) + block + run.substring(close)
    }

    /** The server answered lock_overridden: remember who took it, so the notice shows once. */
    fun overridden(local: LocalSave, by: LockInfo?, at: String): LocalSave =
        local.copy(overridden = Overridden(by?.device ?: "another device", by?.platform ?: "", at))
}

/** Whose lock a save has, seen from one instance. */
enum class LockState { FREE, MINE, OTHER }

object Locks {
    fun state(lock: LockInfo?, instance: String): LockState = when {
        lock == null -> LockState.FREE
        lock.instance == instance -> LockState.MINE
        else -> LockState.OTHER
    }
}
