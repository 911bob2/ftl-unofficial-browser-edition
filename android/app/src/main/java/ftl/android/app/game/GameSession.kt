package ftl.android.app.game

import ftl.android.app.BuildConfig
import ftl.android.app.FtlApplication
import ftl.saves.LocalSave
import ftl.saves.PlayedOn
import ftl.saves.RunSummary
import ftl.saves.SaveMeta
import ftl.saves.Sync
import java.time.Instant
import java.util.concurrent.Executors

/**
 * One running game: the save it plays. Everything the engine writes goes to the device store
 * at once (docs/SAVES.md, "The device first"); the syncer uploads it in the background. Engine callbacks arrive on the
 * GL thread; files are written on one background thread, in order, through the store's update so the syncer's
 * bookkeeping (base revision) is never overwritten.
 */
class GameSession(initial: LocalSave, private val onWritten: (String) -> Unit) {
    private val app = FtlApplication.app
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "ftl-save-io") }
    val id = initial.id

    /** The run and profile the engine starts with. */
    val runXml: String? = initial.run
    val profileXml: String? = initial.profile

    /** Set when another device took the save: nothing this game writes is kept any more. */
    @Volatile var frozen = false

    private val startedPlaySeconds = initial.meta.summary.playSeconds
    private var playedNanos = 0L
    private var resumedAt = System.nanoTime()
    private var running = true

    @Synchronized
    fun setRunning(value: Boolean) {
        val now = System.nanoTime()
        if (running) playedNanos += now - resumedAt
        running = value
        resumedAt = now
    }

    @Synchronized
    private fun playSeconds(): Long {
        val total = playedNanos + if (running) System.nanoTime() - resumedAt else 0
        return startedPlaySeconds + total / 1_000_000_000L
    }

    /** AndroidHost.onSave: kind "run", "profile" or "settings" (the device's, not the save's). */
    fun onSave(kind: String, xml: String?) {
        if (frozen) return
        when (kind) {
            "run" -> {
                val playSeconds = playSeconds()
                val at = Instant.now().toString()
                io.execute {
                    if (frozen) return@execute
                    app.store.update(id) { current ->
                        val meta = SaveMeta(
                            playedOn = PlayedOn("android", app.identity.name, app.identity.deviceId, at),
                            engineBuild = BuildConfig.ENGINE_BUILD,
                            gameData = app.gameData.info?.version,
                            summary = RunSummary.read(xml, playSeconds),
                            extra = current.meta.extra,
                        )
                        Sync.written(current, xml, meta)
                    }
                    onWritten(id)
                }
            }
            "profile" -> if (xml != null) io.execute {
                if (frozen) return@execute
                app.store.update(id) { Sync.profileWritten(it, xml) }
                onWritten(id)
            }
            "settings" -> if (xml != null) io.execute { app.store.putSettings(xml) }
        }
    }

    /** Waits until every save written so far is on disk. */
    fun flush() {
        if (!io.isShutdown) io.submit {}.get()
    }

    fun close() {
        flush()
        io.shutdown()
    }
}
