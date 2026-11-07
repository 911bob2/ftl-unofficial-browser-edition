package ftl.android.app

/**
 * The words of the save questions (docs/SAVES.md), the website's (web/save-words.mjs; SaveWordsTest keeps them equal).
 * {name}, {device}, {time} and {reason} are filled in by [words].
 */
object SaveWords {
    val WORDS = mapOf(
        "lockTitle" to "In use on {device}",
        "lockLine" to "In use on {device} · last seen {time}",
        "overrideWarning" to "{device} may have progress and unlocks that have not synced yet. Overriding discards them; nothing is kept.",
        "override" to "Override lock",
        "conflictTitle" to "{name} changed in two places",
        "conflictText" to "The account has a newer version than the one this device's unsynced progress started from.",
        "conflictChoices" to "Use the cloud copy: the progress and unlocks on this device that have not synced are discarded. Keep this device's copy: it replaces the version in your account, unlocks too (that version stays a recovery snapshot).",
        "useCloud" to "Use the cloud copy",
        "keepMine" to "Keep this device's copy",
        "overriddenTitle" to "Save taken over",
        "overridden" to "This save was taken over on {device}. Progress and unlocks since your last sync were discarded.",
        "overriddenAt" to "This save was taken over on {device} at {time}. Progress and unlocks since your last sync were discarded.",
        "deletedTitle" to "{name} was deleted on another device",
        "deletedText" to "This device has progress of it that never synced.",
        "deleteHereToo" to "Delete here too",
        "uploadAgain" to "Upload again",
        "deletedRemoved" to "{name} was deleted on another device.",
        "deleteTitle" to "Delete {name}?",
        "deleteCloud" to "The save is deleted on this device and in your account. Its recovery snapshots are deleted with it.",
        "deleteLocal" to "The save is deleted from this device.",
        "deleteLockedWarning" to "That game may have progress and unlocks that have not synced yet. Deleting discards them.",
        "deleteAnyway" to "Delete anyway",
        "deleteOffline" to "Connect to delete this save from your account.",
        "snapshotsText" to "The account keeps the last 10 versions of every save. Restoring makes a snapshot the newest version; the newer ones stay.",
        "restoreTitle" to "Restore the save from {time}?",
        "restoreText" to "The save becomes this snapshot as its newest version; the newer snapshots stay, so this can be undone.",
        "restoreUnsynced" to "Progress on this device that has not synced yet is discarded.",
        "restoreHeld" to "This save is in use on {device}.",
        "restored" to "Restored {name} from {time}.",
        "notSynced" to "Not synced: {reason}",
        "keepOffline" to "Connect to keep this device's copy of {name}: the account has a newer version.",
        "savesFull" to "An account has at most {count} saves. Delete one first.",
    )

    /** One of [WORDS] with its {fields} filled in. */
    fun words(key: String, vararg fields: Pair<String, String>): String {
        val values = fields.toMap()
        // "}" escaped too: Android's regex engine (ICU) refuses a bare one, which the JVM's accepts.
        return Regex("""\{(\w+)\}""").replace(WORDS.getValue(key)) { m -> values[m.groupValues[1]] ?: m.value }
    }

    /** "In use on Pixel 8 · last seen 14:05", or without the time when the server gave none. */
    fun lockLine(device: String, lastSeen: String?): String {
        val time = ftl.android.app.ui.clock(lastSeen)
        return if (time.isNotEmpty()) words("lockLine", "device" to device, "time" to time) else words("lockTitle", "device" to device)
    }

    /**
     * The save list's muted line, as the website's (web/saves-ui.mjs summaryLine): "The Kestrel · sector 3 · 120 scrap ·
     * hull 25/30 · 1 h 5 min · 2 h ago", "Empty" without a run.
     */
    fun summaryLine(hasRun: Boolean, s: ftl.saves.Summary, playedAt: String?, now: java.time.Instant = java.time.Instant.now()): String {
        if (!hasRun) return "Empty"
        val parts = listOfNotNull(ftl.android.app.ui.shipLabel(s.ship ?: s.shipId), s.sector?.let { "sector $it" }, s.scrap?.let { "$it scrap" },
            s.hull?.let { "hull $it" + (s.maxHull?.let { m -> "/$m" } ?: "") }, s.playSeconds.takeIf { it > 0 }?.let { ftl.android.app.ui.duration(it) },
            ftl.android.app.ui.ago(playedAt, now)).filter { it.isNotEmpty() }
        return parts.joinToString(" · ").ifEmpty { "Run in progress" }
    }

    /** The takeover notice: with the override's time when it is known. */
    fun overridden(device: String?, at: String?): String {
        val who = device?.takeIf { it.isNotBlank() } ?: "another device"
        val time = ftl.android.app.ui.clock(at)
        return if (time.isNotEmpty()) words("overriddenAt", "device" to who, "time" to time) else words("overridden", "device" to who)
    }
}
