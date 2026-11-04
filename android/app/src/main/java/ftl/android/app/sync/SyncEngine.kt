package ftl.android.app.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import ftl.android.app.net.ApiError
import ftl.android.app.store.DeviceStore
import ftl.saves.CloudSave
import ftl.saves.Json
import ftl.saves.LocalSave
import ftl.saves.LockInfo
import ftl.saves.LockState
import ftl.saves.Locks
import ftl.saves.Sync
import ftl.saves.SyncAction
import ftl.saves.SyncDecision
import ftl.saves.obj
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Background sync between the device store and the account (docs/SAVES.md). The game never waits for it: saves are
 * on the device already; this uploads them when it can, takes and refreshes locks, fetches what the cloud has newer,
 * and leaves the questions (conflicts, deletions elsewhere, overrides) to the launcher.
 */
class SyncEngine(context: Context, private val account: Account, private val store: DeviceStore, private val instanceId: String) {
    /** The override (its time) the player has been told about, per save. */
    private val acknowledged = context.getSharedPreferences("overrides", Context.MODE_PRIVATE)
    data class State(
        val online: Boolean = false,
        val syncing: Boolean = false,
        val cloud: Map<String, CloudSave> = emptyMap(),
        val deleted: Set<String> = emptySet(),
        val decisions: Map<String, SyncDecision> = emptyMap(),
        val error: String? = null,
        val lastSync: String? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private val me get() = account.api

    init {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val online = cm.activeNetwork?.let { cm.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } == true
        _state.value = _state.value.copy(online = online)
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _state.value = _state.value.copy(online = true)
                requestSync()
            }

            override fun onLost(network: Network) {
                _state.value = _state.value.copy(online = false)
            }
        })
    }

    fun requestSync(): Job = scope.launch { syncAll() }

    private var retry: Job? = null
    private var retryDelay = RETRY_FIRST

    /** The server could not be reached: try again later (15 s, doubling to 10 min) while anything is unsynced. */
    private fun scheduleRetry() {
        if (me == null || retry?.isActive == true) return
        if (store.list().none { it.dirty || it.nameDirty } && store.pendingReleases().isEmpty()) return
        val wait = retryDelay
        retryDelay = (retryDelay * 2).coerceAtMost(RETRY_MAX)
        retry = scope.launch { delay(wait); syncAll() }
    }

    private fun updateState(change: (State) -> State) {
        _state.value = change(_state.value)
    }

    /** The full pass: every save. Safe to call any time; passes do not overlap. */
    suspend fun syncAll() = mutex.withLock {
        val api = me ?: return@withLock
        updateState { it.copy(syncing = true) }
        try {
            val list = api.saves()
            val cloud = list.saves.associateBy { it.id }
            val locals = store.list().associateBy { it.id }
            val decisions = HashMap<String, SyncDecision>()
            for (id in locals.keys + cloud.keys) {
                val local = locals[id]
                val c = cloud[id]
                val d = Sync.decide(local, c, id in list.deleted)
                decisions[id] = runCatching { act(id, local?.let { store.get(id) }, c, d) }.getOrElse { e ->
                    Log.w("FTL", "Sync of $id failed", e)
                    if (e is IOException && e !is ApiError) throw e
                    d
                }
            }
            for (id in store.pendingReleases()) runCatching { release(id) }
            val fresh = api.saves()
            updateState {
                it.copy(cloud = fresh.saves.associateBy { s -> s.id }, deleted = fresh.deleted, decisions = recompute(fresh), error = null,
                    lastSync = Instant.now().toString())
            }
            retryDelay = RETRY_FIRST
        } catch (e: IOException) {
            updateState { it.copy(error = if (e is ApiError) e.message else "Offline: saves stay on this device and sync later.") }
            if (e !is ApiError) scheduleRetry()
        } finally {
            updateState { it.copy(syncing = false) }
        }
    }

    private fun recompute(list: ftl.android.app.net.Api.SaveList): Map<String, SyncDecision> {
        val cloud = list.saves.associateBy { it.id }
        val locals = store.list().associateBy { it.id }
        return (locals.keys + cloud.keys).associateWith { Sync.decide(locals[it], cloud[it], it in list.deleted) }
    }

    /** Carries out what needs no question; returns the decision that is left for the player (or NONE). */
    private fun act(id: String, local: LocalSave?, cloud: CloudSave?, d: SyncDecision): SyncDecision {
        val api = me ?: return d
        if (d.rename && local != null && cloud != null) {
            api.rename(id, local.name)
            store.update(id) { it.copy(nameDirty = false) }
        }
        return when (d.action) {
            SyncAction.CREATE, SyncAction.UPLOAD -> upload(local!!, if (d.action == SyncAction.CREATE) 0 else local.base)
            SyncAction.FETCH -> {
                val (c, run, profile) = api.save(id)
                store.update(id) { current -> if (current.dirty) null else Sync.fetched(current, c, run, profile) }
                SyncDecision(ftl.saves.SyncStatus.SYNCED, SyncAction.NONE)
            }
            SyncAction.REMOVE_LOCAL -> {
                store.delete(id)
                SyncDecision(ftl.saves.SyncStatus.DELETED, SyncAction.NONE)
            }
            else -> d
        }
    }

    private fun body(s: LocalSave) = obj("name" to s.name, "setup" to s.setup.toJson(), "meta" to s.meta.toJson(), "run" to s.run, "profile" to s.profile, "kind" to "auto")

    /** The save the game plays now: its lock stays taken. */
    @Volatile var playing: String? = null

    /** Uploads the device copy over cloud revision `revision`; records an override when the server reports one. */
    private fun upload(local: LocalSave, revision: Long, retried: Boolean = false): SyncDecision {
        val api = me ?: return Sync.decide(local, null, false)
        return try {
            val newRevision = api.putSave(local.id, revision, body(local))
            store.update(local.id) { Sync.uploaded(it, local.run, local.profile, newRevision) }
            SyncDecision(ftl.saves.SyncStatus.SYNCED, SyncAction.NONE)
        } catch (e: ApiError) {
            when (e.code) {
                "lock_overridden" -> {
                    val by = LockInfo.fromJson(e.body?.get("lock"))
                    val at = (e.body?.get("overriddenUtc") as? Json.Str)?.value
                    if (at != null && acknowledged.getString(local.id, null) == at) {
                        // Told already. Held elsewhere: in use there, so the player chooses. Free: what was played here
                        // since (a game started offline) uploads once the notice is acknowledged to the account.
                        if (by != null || retried) return SyncDecision(ftl.saves.SyncStatus.CONFLICT, SyncAction.ASK_CONFLICT)
                        api.lock(local.id, override = false, acknowledge = true)
                        val d = upload(local, revision, retried = true)
                        if (playing != local.id) runCatching { release(local.id) }
                        return d
                    }
                    store.update(local.id) { Sync.overridden(it, by, at ?: Instant.now().toString()) }
                    SyncDecision(ftl.saves.SyncStatus.OVERRIDDEN, SyncAction.NOTIFY_OVERRIDDEN)
                }
                "locked", "revision_conflict" -> SyncDecision(ftl.saves.SyncStatus.CONFLICT, SyncAction.ASK_CONFLICT)
                else -> throw e
            }
        }
    }

    // ---- what the launcher and the game ask for ----

    sealed class LockResult {
        object Granted : LockResult()
        object Offline : LockResult()
        data class Held(val lock: LockInfo) : LockResult()
        data class Overridden(val by: LockInfo?, val at: String?) : LockResult()
    }

    /** Takes the save's lock before play (override: take it from whoever has it). */
    suspend fun lock(id: String, override: Boolean): LockResult = mutex.withLock {
        val api = me ?: return@withLock LockResult.Offline
        try {
            api.lock(id, override)
            LockResult.Granted
        } catch (e: ApiError) {
            val lock = LockInfo.fromJson(e.body?.get("lock"))
            when {
                e.code == "locked" && lock != null -> LockResult.Held(lock)
                e.code == "lock_overridden" -> {
                    // The server repeats an override until this device holds the lock again; once the player has
                    // seen it, the save is simply in use elsewhere.
                    val at = (e.body?.get("overriddenUtc") as? Json.Str)?.value
                    if (at != null && acknowledged.getString(id, null) == at) {
                        if (lock != null) return@withLock LockResult.Held(lock)
                        api.lock(id, override = false, acknowledge = true)
                        return@withLock LockResult.Granted
                    }
                    store.update(id) { if (it.dirty) Sync.overridden(it, lock, at ?: Instant.now().toString()) else null }
                    LockResult.Overridden(lock, at)
                }
                e.status == 404 -> {
                    // Not in the cloud yet: create it, then lock it.
                    store.get(id)?.let { upload(it, 0) }
                    api.lock(id, override)
                    LockResult.Granted
                }
                else -> throw e
            }
        } catch (e: IOException) {
            LockResult.Offline
        }
    }

    /** The save the launcher is about to play: the cloud copy when it is newer and nothing here is unsynced. */
    suspend fun prepare(id: String): SyncDecision? = mutex.withLock {
        val api = me ?: return@withLock null
        try {
            val local = store.get(id)
            val cloud = runCatching { api.save(id) }.getOrElse { e -> if (e is ApiError && e.status == 404) null else throw e }
            val d = Sync.decide(local, cloud?.cloud, false)
            when (d.action) {
                SyncAction.FETCH, SyncAction.LIST_ONLY -> {
                    val (c, run, profile) = cloud!!
                    if (local == null) store.put(Sync.fetched(null, c, run, profile)) else store.update(id) { Sync.fetched(it, c, run, profile) }
                    SyncDecision(ftl.saves.SyncStatus.SYNCED, SyncAction.NONE)
                }
                SyncAction.CREATE -> upload(local!!, 0)
                else -> d
            }
        } catch (e: IOException) {
            if (e is ApiError) throw e
            null
        }
    }

    /** Before sign-out: the locks this game holds go back, as far as the network allows (a lock never expires). */
    suspend fun releaseHeld() = mutex.withLock {
        val api = me ?: return@withLock
        for (c in _state.value.cloud.values) if (lockState(c) == LockState.MINE) runCatching { api.unlock(c.id) }
        for (id in store.pendingReleases()) runCatching { release(id) }
    }

    /** Another account's saves are shown now: forget what the last one's sync knew. */
    fun reset() {
        retryDelay = RETRY_FIRST
        _state.value = State(online = _state.value.online)
    }

    /**
     * Enable cloud saves: the saves of play without an account move into the signed-in account and upload
     * (docs/SAVES.md). Returns how many saves moved.
     */
    suspend fun adoptLocal(): Int {
        val moved = mutex.withLock {
            if (me == null) return@withLock 0
            store.adopt(DeviceStore.LOCAL)
        }
        syncAll()
        return moved
    }

    /** "Use the cloud copy" (keepMine false) or "Keep this device's copy" (true) after a conflict, once this device holds the lock. */
    suspend fun resolveConflict(id: String, keepMine: Boolean) = mutex.withLock {
        val api = me ?: return@withLock
        val (c, run, profile) = api.save(id)
        val d = if (keepMine) {
            val local = store.update(id) { Sync.keepMine(it, c) } ?: return@withLock
            upload(local, c.revision)
        } else {
            store.update(id) { Sync.fetched(it, c, run, profile) }
            SyncDecision(ftl.saves.SyncStatus.SYNCED, SyncAction.NONE)
        }
        updateState { it.copy(decisions = it.decisions + (id to d)) }
    }

    /** After the override notice: this device's copy becomes the cloud's. */
    suspend fun acknowledgeOverride(id: String, at: String?) = mutex.withLock {
        if (at != null) acknowledged.edit().putString(id, at).apply()
        val api = me ?: return@withLock
        val (c, run, profile) = api.save(id)
        store.update(id) { Sync.fetched(it.copy(overridden = null), c, run, profile) }
    }

    /** A deleted-elsewhere save with unsynced progress: upload it again as a new save, or delete it here too. */
    suspend fun resolveDeleted(id: String, uploadAgain: Boolean) = mutex.withLock {
        val local = store.get(id) ?: return@withLock
        store.delete(id)
        if (uploadAgain) {
            val copy = local.copy(id = java.util.UUID.randomUUID().toString(), base = 0, dirty = true)
            store.put(copy)
            upload(copy, 0)
        }
    }

    // ---- during play ----

    private var pendingUpload: Job? = null

    /** The game wrote a save: upload it a few seconds later (writes in quick succession upload once). */
    fun written(id: String) {
        pendingUpload?.cancel()
        pendingUpload = scope.launch {
            delay(3000)
            uploadNow(id)
        }
    }

    /** Uploads the save now if it has unsynced progress. False when offline. */
    suspend fun uploadNow(id: String): Boolean = mutex.withLock {
        if (me == null) return@withLock false
        try {
            val local = store.get(id) ?: return@withLock true
            if (local.dirty && local.overridden == null) upload(local, local.base)
            true
        } catch (e: IOException) {
            if (e is ApiError) Log.w("FTL", "Upload refused: ${e.code}", e) else scheduleRetry()
            false
        }
    }

    /** The lock heartbeat while playing: Overridden when another device has taken the save. */
    suspend fun heartbeat(id: String): LockResult = lock(id, false)

    /** Save and close: upload, then give the lock back (later, if offline now). */
    fun finish(id: String) {
        store.setPendingRelease(id, true)
        scope.launch {
            if (uploadNow(id)) mutex.withLock { runCatching { release(id) } }
        }
    }

    private fun release(id: String) {
        val api = me ?: return
        try {
            api.unlock(id)
            store.setPendingRelease(id, false)
        } catch (e: ApiError) {
            if (e.code == "not_holder" || e.status == 404) store.setPendingRelease(id, false) else throw e
        }
    }

    // ---- recovery snapshots (Saves > ⋯ > Recovery snapshots) ----

    class Unavailable(message: String) : IOException(message)

    /** The save's snapshots; [Unavailable] offline or signed out. */
    suspend fun snapshots(id: String): List<ftl.saves.Snapshot> {
        val api = me ?: throw Unavailable(SNAPSHOTS_OFFLINE)
        return try {
            api.snapshots(id)
        } catch (e: IOException) {
            if (e is ApiError) throw e
            throw Unavailable(SNAPSHOTS_OFFLINE)
        }
    }

    /**
     * Restores a snapshot as the website does: takes the lock (never from another device), makes the snapshot the
     * newest revision and replaces this device's copy with it, unsynced progress too; then gives the lock back.
     * [LockResult.Held] when another device has the save open.
     */
    suspend fun restore(id: String, revision: Long): LockResult? = mutex.withLock {
        val api = me ?: throw Unavailable(SNAPSHOTS_OFFLINE)
        try {
            val current = api.save(id).cloud.revision
            try {
                api.lock(id, false)
            } catch (e: ApiError) {
                val lock = LockInfo.fromJson(e.body?.get("lock"))
                if ((e.code == "locked" || e.code == "lock_overridden") && lock != null) return@withLock LockResult.Held(lock)
                throw e
            }
            try {
                api.restore(id, revision, current)
                val (c, run, profile) = api.save(id)
                if (store.get(id) == null) store.put(ftl.saves.Snapshots.restored(null, c, run, profile))
                else store.update(id) { ftl.saves.Snapshots.restored(it, c, run, profile) }
                updateState { it.copy(cloud = it.cloud + (id to c), decisions = it.decisions + (id to SyncDecision(ftl.saves.SyncStatus.SYNCED, SyncAction.NONE))) }
            } finally {
                if (playing != id) runCatching { api.unlock(id) }
            }
            null
        } catch (e: IOException) {
            if (e is ApiError) throw e
            throw Unavailable("Connect to the internet to restore a recovery snapshot.")
        }
    }

    fun lockState(cloud: CloudSave?): LockState = Locks.state(cloud?.lock, instanceId)

    private companion object {
        const val RETRY_FIRST = 15_000L
        const val RETRY_MAX = 10 * 60_000L
        const val SNAPSHOTS_OFFLINE = "Recovery snapshots are kept in your account. Connect to the internet to see them."
    }
}
