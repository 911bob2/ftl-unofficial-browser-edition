package ftl.android.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The mixer's own reader for plain PCM WAV files (PcmDecoder.decodeWav; report 106: the platform's codec took about half
 * a second to set up for a button's click). On the emulator the debug console's `pcmcheck <path>.wav` compares it with the
 * platform's decoder, sample for sample.
 */
class WavTest {
    private fun le(v: Int, n: Int) = ByteArray(n) { (v shr (8 * it)).toByte() }
    private fun wav(format: Int, channels: Int, rate: Int, bits: Int, data: ByteArray, declared: Int = data.size, before: ByteArray = ByteArray(0), after: ByteArray = ByteArray(0)): ByteArray {
        val fmt = le(format, 2) + le(channels, 2) + le(rate, 4) + le(rate * channels * bits / 8, 4) + le(channels * bits / 8, 2) + le(bits, 2)
        val body = "WAVE".toByteArray() + before + "fmt ".toByteArray() + le(fmt.size, 4) + fmt + "data".toByteArray() + le(declared, 4) + data + after
        return "RIFF".toByteArray() + le(body.size, 4) + body
    }
    private fun samples(p: Pcm) = List(p.data.capacity()) { p.data.get(it).toInt() }

    @Test
    fun sixteenBitSamplesAsTheyAre() {
        val data = le(1, 2) + le(-2, 2) + le(32767, 2) + le(-32768, 2) + ByteArray(16)
        val mono = PcmDecoder.decodeWav(wav(1, 1, 44100, 16, data))
        assertEquals(listOf(1, -2, 32767, -32768), samples(mono).take(4))
        assertEquals(listOf(1, 44100, 12), listOf(mono.channels, mono.rate, mono.frames))
        val stereo = PcmDecoder.decodeWav(wav(1, 2, 22050, 16, data))
        assertEquals(listOf(2, 22050, 6), listOf(stereo.channels, stereo.rate, stereo.frames))
        assertEquals(listOf(1, -2, 32767, -32768), samples(stereo).take(4))
    }

    @Test
    fun eightBitSamplesCentredAndWidened() {
        val p = PcmDecoder.decodeWav(wav(1, 1, 11025, 8, byteArrayOf(0, 128.toByte(), 255.toByte(), 129.toByte()) + ByteArray(40)))
        assertEquals(listOf(-32768, 0, 32512, 256), samples(p).take(4))
    }

    @Test
    fun chunksBeforeBytesAfterAndADataChunkThatSaysTooMuch() {
        val data = le(100, 2) + le(200, 2) + le(300, 2) + ByteArray(40)
        val list = "LIST".toByteArray() + le(5, 4) + ByteArray(5) + ByteArray(1)
        assertEquals(listOf(100, 200, 300), samples(PcmDecoder.decodeWav(wav(1, 1, 44100, 16, data, before = list, after = ByteArray(7) { 9 }))).take(3))
        // Says more than the file holds: what is there, in whole frames.
        val cut = PcmDecoder.decodeWav(wav(1, 2, 44100, 16, data + ByteArray(1), declared = 9999))
        assertEquals((data.size / 4) * 2, cut.data.capacity())
    }

    @Test
    fun anythingElseIsLeftToThePlatform() {
        val data = ByteArray(64)
        assertNull("float", PcmDecoder.decodeWav(wav(3, 1, 44100, 32, data)))
        assertNull("ADPCM", PcmDecoder.decodeWav(wav(2, 1, 44100, 4, data)))
        assertNull("extensible", PcmDecoder.decodeWav(wav(0xFFFE, 2, 44100, 16, data)))
        assertNull("24 bit", PcmDecoder.decodeWav(wav(1, 1, 44100, 24, data)))
        assertNull("six channels", PcmDecoder.decodeWav(wav(1, 6, 44100, 16, data)))
        assertNull("no data", PcmDecoder.decodeWav(wav(1, 1, 44100, 16, ByteArray(0)) + ByteArray(40)))
        assertNull("not RIFF", PcmDecoder.decodeWav("OggS".toByteArray() + ByteArray(60)))
        assertNull("short", PcmDecoder.decodeWav(ByteArray(10)))
        assertNull("a chunk past the end", PcmDecoder.decodeWav("RIFF".toByteArray() + le(100, 4) + "WAVE".toByteArray() + "junk".toByteArray() + le(1 shl 30, 4) + ByteArray(40)))
    }
}
