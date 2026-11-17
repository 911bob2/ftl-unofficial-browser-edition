package ftl.android.app

import ftl.android.audio.MixerCore
import ftl.android.audio.MixerCore.AL_BUFFER
import ftl.android.audio.MixerCore.AL_INITIAL
import ftl.android.audio.MixerCore.AL_LOOPING
import ftl.android.audio.MixerCore.AL_PITCH
import ftl.android.audio.MixerCore.AL_PLAYING
import ftl.android.audio.MixerCore.AL_SEC_OFFSET
import ftl.android.audio.MixerCore.AL_SOURCE_STATE
import ftl.android.audio.MixerCore.AL_STOPPED
import ftl.android.audio.Pcm
import java.nio.ShortBuffer
import java.util.function.BooleanSupplier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Report 106, "single sounds go silent until the app is restarted": no state of the Android mixer may leave a sound
 * unplayable for the rest of the game (MixerCore; AudioMixer adds the decoder, the threads and the AudioTrack). The
 * engine asks for a sound's id once and keeps it (RealSoundManager.loadRawSound), which every test here does too.
 */
class AudioMixerTest {
    private val effectBytes = 2 * 400L
    private val musicBytes = 2 * 100_000L

    /** One game's files: what each path decodes to, or the failures it meets first. */
    private inner class Files : MixerCore.Loader {
        val loads = ArrayList<String>()
        val failures = HashMap<String, ArrayDeque<Throwable>>()
        val missing = HashSet<String>()

        override fun load(path: String, stopped: BooleanSupplier): Pcm {
            loads += path
            if (path in missing) throw MixerCore.MissingAudio(path)
            failures[path]?.removeFirstOrNull()?.let { throw it }
            val frames = if (path.startsWith("audio/music/")) 100_000 else 400
            return Pcm(ShortBuffer.allocate(frames).also { b -> repeat(frames) { b.put(it, 8000) } }, 1, MixerCore.RATE)
        }

        fun failOnce(path: String, e: Throwable) { failures.getOrPut(path) { ArrayDeque() }.addLast(e) }
    }

    private class Speaker : MixerCore.Output {
        var takes = true
        var error = 0
        var reopened = 0
        var heard = false
        var reopenWorks = true

        override fun write(data: ShortArray, length: Int): Int {
            if (error != 0) return error
            if (!takes) return 0
            heard = data.any { it.toInt() != 0 }
            return length
        }

        override fun reopen() {
            reopened++
            if (!reopenWorks) throw IllegalStateException("No output")
            error = 0
            takes = true
        }
    }

    private val files = Files()
    private val decodes = ArrayDeque<Runnable>()
    private val musicDecodes = ArrayDeque<Runnable>()
    private var now = 0L
    private val errors = ArrayList<String>()
    private val speaker = Speaker()

    private fun mixer(cacheBytes: Long = 1 shl 20) = MixerCore(cacheBytes, { decodes += it }, { musicDecodes += it }, { files }, { now },
        object : MixerCore.Log {
            override fun debug(message: String) {}
            override fun error(message: String, e: Throwable?) { errors += message }
        })

    private fun decodeAll() {
        while (decodes.isNotEmpty()) decodes.removeFirst().run()
        while (musicDecodes.isNotEmpty()) musicDecodes.removeFirst().run()
    }

    private fun MixerCore.source() = call("source", 0, 0, 0.0).toInt()
    private fun MixerCore.state(source: Int) = call("getSource", source, AL_SOURCE_STATE, 0.0).toInt()
    private fun MixerCore.play(source: Int, buffer: Int, loop: Boolean = false) {
        call("stop", source, 0, 0.0)
        call("sourcei", source, AL_BUFFER, buffer.toDouble())
        call("sourcei", source, AL_LOOPING, if (loop) 1.0 else 0.0)
        call("play", source, 0, 0.0)
    }
    private fun MixerCore.pumps(n: Int): Boolean {
        var heard = false
        repeat(n) { speaker.heard = false; pump(speaker); heard = heard || speaker.heard }
        return heard
    }
    private fun MixerCore.count(name: String) = Regex("\\b$name=(\\d+)").find(stats())!!.groupValues[1].toInt()
    private fun seconds(s: Double) { now += (s * 1e9).toLong() }

    /** Plays the sound on the source and says whether it was heard and then ended. */
    private fun MixerCore.heardToTheEnd(source: Int, buffer: Int): Boolean {
        play(source, buffer)
        decodeAll()
        val heard = pumps(3)
        return heard && state(source) == AL_STOPPED
    }

    // ---- A: a sound freed to save memory ----

    @Test
    fun aSoundFreedForMemoryKeepsItsIdAndPlaysAgain() {
        // Room for one track and two effects: as on a phone whose cache is about the size of the music that is playing
        // (a vanilla track decodes to 24 to 54 MB, an explore and battle pair to as much as 88; a 512 MB heap's cache
        // is 128 MB).
        val m = mixer(cacheBytes = musicBytes + 2 * effectBytes)
        val effects = m.source()
        val music = m.source()
        val flak = m.buffer("audio/waves/weapons/flak.ogg")
        val jump = m.buffer("audio/waves/ui/jump.ogg")
        assertTrue(m.heardToTheEnd(effects, flak))
        seconds(1.0)
        assertTrue(m.heardToTheEnd(effects, jump))
        // The run goes on: one sector's track after the other, each decoded when it starts.
        for (track in 1..6) {
            seconds(60.0)
            m.music(music, "audio/music/track$track.ogg")
            decodeAll()
            assertTrue("track $track is heard", m.pumps(2))
            // A third effect while the cache is full: something that is not playing has to go.
            assertTrue(m.heardToTheEnd(effects, m.buffer("audio/waves/ui/select$track.ogg")))
        }
        assertTrue("the cache was over its size: ${m.stats()}", m.count("freed") > 0)
        assertTrue(m.describe("audio/waves/weapons/flak.ogg"), "freed" in m.describe("audio/waves/weapons/flak.ogg"))
        // The engine plays the id it got at the start.
        assertEquals(flak, m.buffer("audio/waves/weapons/flak.ogg"))
        assertTrue("a freed sound is decoded again and heard: ${m.stats()}", m.heardToTheEnd(effects, flak))
        assertTrue(m.heardToTheEnd(effects, jump))
        assertTrue(m.count("decodedAgain") >= 2)
        // And the music still plays.
        assertEquals(AL_PLAYING, m.state(music))
    }

    @Test
    fun musicNobodyPlaysGoesBeforeEffectsAndPlayingSoundsNever() {
        val m = mixer(cacheBytes = musicBytes + 2 * effectBytes)
        val effects = m.source()
        val music = m.source()
        val loopSource = m.source()
        val old = m.buffer("audio/waves/old.ogg")
        assertTrue(m.heardToTheEnd(effects, old))
        val hum = m.buffer("audio/waves/hum.ogg")
        m.play(loopSource, hum, loop = true)
        m.music(music, "audio/music/a.ogg")
        decodeAll()
        m.pumps(1)
        seconds(5.0)
        m.music(music, "audio/music/b.ogg")
        decodeAll()
        m.pumps(1)
        // Over the size by one track: the track that ended goes, the older effect stays, the loop and the track that play stay.
        assertTrue(m.describe("audio/music/a.ogg"), "freed" in m.describe("audio/music/a.ogg"))
        assertTrue(m.describe("audio/waves/old.ogg"), "ready" in m.describe("audio/waves/old.ogg"))
        assertTrue("ready" in m.describe("audio/waves/hum.ogg"))
        assertTrue("ready" in m.describe("audio/music/b.ogg"))
        // A cache smaller than what is playing frees nothing that plays.
        m.setCacheBytes(0)
        m.buffer("audio/waves/new.ogg")
        decodeAll()
        assertTrue("ready" in m.describe("audio/waves/hum.ogg"))
        assertTrue("ready" in m.describe("audio/music/b.ogg"))
        assertTrue("freed" in m.describe("audio/waves/old.ogg"))
        assertTrue(m.pumps(1))
    }

    // ---- B: a decode that failed ----

    @Test
    fun aFailedDecodeIsTriedAgainWhenTheSoundIsNextPlayed() {
        val m = mixer()
        val s = m.source()
        files.failOnce("audio/waves/laser.ogg", java.io.IOException("codec error"))
        val laser = m.buffer("audio/waves/laser.ogg")
        m.play(s, laser)
        decodeAll()
        assertFalse(m.pumps(MixerCore.STALE_BLOCKS + 2))
        assertEquals("the play that met the failure is given up", AL_STOPPED, m.state(s))
        assertEquals(1, files.loads.size)
        assertEquals(1, errors.size)
        // Played again later in the fight: decoded again, heard.
        seconds(1.5)
        assertTrue("silent for the rest of the game: ${m.stats()}", m.heardToTheEnd(s, laser))
        assertEquals(2, files.loads.size)
    }

    @Test
    fun aFailingFileIsNotDecodedOverAndOver() {
        val m = mixer()
        val s = m.source()
        repeat(20) { files.failOnce("audio/waves/bad.ogg", java.io.IOException("not a sound")) }
        val bad = m.buffer("audio/waves/bad.ogg")
        decodeAll()
        // Played every frame for four seconds: the waits are 1 s, then 2 s, then 4 s.
        repeat(240) {
            m.play(s, bad)
            decodeAll()
            m.pumps(1)
            seconds(1.0 / 60)
        }
        assertEquals(files.loads.toString(), 3, files.loads.size)
    }

    @Test
    fun outOfMemoryEmptiesTheCacheAndTriesOnceMore() {
        val m = mixer()
        val s = m.source()
        val idle = m.buffer("audio/waves/idle.ogg")
        assertTrue(m.heardToTheEnd(s, idle))
        files.failOnce("audio/waves/big.ogg", OutOfMemoryError("heap full"))
        val big = m.buffer("audio/waves/big.ogg")
        assertTrue("decoded on the second try of the same request", m.heardToTheEnd(s, big))
        assertEquals(listOf("audio/waves/idle.ogg", "audio/waves/big.ogg", "audio/waves/big.ogg"), files.loads)
        assertTrue("freed" in m.describe("audio/waves/idle.ogg"))
        // Out of memory both times: failed for now, and tried again later like any failure.
        files.failOnce("audio/waves/huge.ogg", OutOfMemoryError("heap full"))
        files.failOnce("audio/waves/huge.ogg", OutOfMemoryError("heap full"))
        val huge = m.buffer("audio/waves/huge.ogg")
        decodeAll()
        assertTrue(m.describe("audio/waves/huge.ogg"), "failed" in m.describe("audio/waves/huge.ogg"))
        seconds(2.0)
        assertTrue(m.heardToTheEnd(s, huge))
        assertTrue(m.heardToTheEnd(s, idle))
    }

    @Test
    fun aMissingFileNeitherPlaysNorEndsAndIsNotAskedForAgain() {
        val m = mixer()
        val s = m.source()
        files.missing += "audio/waves/none.ogg"
        files.missing += "audio/music/none.ogg"
        val none = m.buffer("audio/waves/none.ogg")
        m.play(s, none)
        decodeAll()
        m.pumps(2)
        assertEquals(AL_INITIAL, m.state(s))
        seconds(120.0)
        m.play(s, none)
        decodeAll()
        assertEquals(AL_INITIAL, m.state(s))
        // A missing track is not taken for one that ended (the playlist stays silent there, web/audio.mjs).
        m.music(s, "audio/music/none.ogg")
        decodeAll()
        m.pumps(2)
        assertEquals(AL_INITIAL, m.state(s))
        assertEquals(listOf("audio/waves/none.ogg", "audio/music/none.ogg"), files.loads)
    }

    @Test
    fun musicWaitsForItsTrackAndTriesAgainByItself() {
        val m = mixer()
        val music = m.source()
        files.failOnce("audio/music/sector.ogg", OutOfMemoryError("heap full"))
        files.failOnce("audio/music/sector.ogg", OutOfMemoryError("heap full"))
        m.music(music, "audio/music/sector.ogg")
        decodeAll()
        assertFalse(m.pumps(MixerCore.STALE_BLOCKS + 5))
        assertEquals("the playlist must not take the track for ended or missing", AL_PLAYING, m.state(music))
        assertTrue(decodes.isEmpty() && musicDecodes.isEmpty())
        seconds(1.1)
        m.pumps(1)
        decodeAll()
        assertTrue("the track starts once its decode works", m.pumps(1))
    }

    // ---- F: voices ----

    @Test
    fun aDecodeThatNeverEndsHoldsNoVoice() {
        val m = mixer()
        val voices = List(61) { m.source() }
        val loop = m.source()
        val music = m.source()
        // The decoder thread is stuck: nothing queued ever runs.
        val stuck = m.buffer("audio/waves/stuck.ogg")
        for (v in voices) m.play(v, stuck)
        m.play(loop, m.buffer("audio/waves/hum.ogg"), loop = true)
        m.music(music, "audio/music/a.ogg")
        m.pumps(MixerCore.STALE_BLOCKS)
        assertTrue(voices.all { m.state(it) == AL_PLAYING })
        m.pumps(1)
        assertTrue("every effect voice is free again: ${m.stats()}", voices.all { m.state(it) == AL_STOPPED })
        assertEquals(61, m.count("givenUpWaiting"))
        // A loop and the music wait for as long as it takes.
        assertEquals(AL_PLAYING, m.state(loop))
        assertEquals(AL_PLAYING, m.state(music))
        // The decoder comes back: the same sound plays at its next request.
        assertTrue(m.heardToTheEnd(voices[0], stuck))
    }

    // ---- E: the output ----

    @Test
    fun aDeadOutputIsOpenedAgainAndNothingPlaysAwayMeanwhile() {
        val m = mixer()
        val music = m.source()
        m.music(music, "audio/music/a.ogg")
        decodeAll()
        assertTrue(m.pumps(1))
        // The AudioTrack died (the audio device or server changed) and cannot be opened for a while.
        speaker.error = -6
        speaker.reopenWorks = false
        assertFalse(m.pump(speaker))
        val before = m.call("getSource", music, AL_SEC_OFFSET, 0.0)
        repeat(119) { assertFalse(m.pump(speaker)) }
        assertEquals("opened at once, then once in 50 blocks", 3, speaker.reopened)
        assertEquals("the track did not run on unheard", before, m.call("getSource", music, AL_SEC_OFFSET, 0.0), 0.0)
        assertEquals(AL_PLAYING, m.state(music))
        speaker.reopenWorks = true
        repeat(60) { m.pump(speaker) }
        assertTrue("sound is back by itself", m.pumps(2))
        // An output that just takes nothing (no error) is opened again after about a second.
        val reopened = speaker.reopened
        speaker.takes = false
        repeat(49) { m.pump(speaker) }
        assertEquals(reopened, speaker.reopened)
        m.pump(speaker)
        assertEquals(reopened + 1, speaker.reopened)
        assertTrue(m.pumps(2))
    }

    // ---- J: background and audio focus ----

    @Test
    fun pausedHoldsTheSourcesAndMutedPlaysThemUnheard() {
        val m = mixer()
        val s = m.source()
        val effect = m.buffer("audio/waves/hit.ogg")
        decodeAll()
        m.play(s, effect)
        m.setPaused(true)
        assertFalse(m.pumps(10))
        assertEquals("in the background the sound waits", AL_PLAYING, m.state(s))
        m.setPaused(false)
        assertTrue(m.pumps(3))
        assertEquals(AL_STOPPED, m.state(s))
        // Without the audio focus the game goes on: the effect ends unheard and its voice is free.
        m.play(s, effect)
        m.setMuted(true)
        assertFalse(m.pumps(3))
        assertEquals(AL_STOPPED, m.state(s))
        m.setMuted(false)
        assertTrue(m.heardToTheEnd(s, effect))
    }

    // ---- D: the mixing thread ----

    @Test
    fun aSourceThatCannotBeMixedEndsAloneAndTheMixerGoesOn() {
        val m = mixer()
        val broken = m.source()
        val fine = m.source()
        val hum = m.buffer("audio/waves/hum.ogg")
        decodeAll()
        m.play(fine, hum, loop = true)
        m.play(broken, hum)
        m.call("sourcef", broken, AL_PITCH, -4.0)
        m.call("sourcef", broken, AL_SEC_OFFSET, 0.001)
        assertTrue(m.pumps(3))
        assertEquals(AL_STOPPED, m.state(broken))
        assertEquals(1, m.count("mixErrors"))
        assertEquals(AL_PLAYING, m.state(fine))
        // A position that is not a number ends the sound instead of holding its voice.
        m.play(broken, hum)
        m.call("sourcef", broken, AL_PITCH, 1.0)
        m.call("sourcef", broken, AL_SEC_OFFSET, Double.NaN)
        m.pumps(2)
        assertEquals(AL_STOPPED, m.state(broken))
    }

    // ---- a game ends ----

    @Test
    fun resetForgetsTheSoundsDropsQueuedDecodesAndKeepsTheSources() {
        val m = mixer()
        val s = m.source()
        val first = m.buffer("audio/waves/a.ogg")
        m.play(s, first)
        m.buffer("audio/waves/queued.ogg")
        m.reset()
        decodeAll()
        assertTrue("jobs of the ended game decode nothing", files.loads.isEmpty())
        assertEquals(AL_INITIAL, m.state(s))
        // The next game: the same path is another game's file and is decoded for it.
        val again = m.buffer("audio/waves/a.ogg")
        assertTrue(again != first)
        assertTrue(m.heardToTheEnd(s, again))
        // An id of the ended game plays nothing and holds nothing.
        m.play(s, first)
        assertEquals(AL_STOPPED, m.state(s))
    }
}
