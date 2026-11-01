package ftl.android.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryNoticeTest {
    private val gb = 1L shl 30
    private val mb = 1L shl 20
    private val multiverse = 725 * mb
    private val hyperspace = 10 * mb

    @Test fun largeModsWarnOnAPhoneSoldAs2Gb() = assertTrue(MemoryNotice.tooSmall(1843 * mb, 512 * mb, multiverse))

    @Test fun largeModsDoNotWarnOnAPhoneSoldAs3Gb() = assertFalse(MemoryNotice.tooSmall(2867 * mb, 512 * mb, multiverse))

    @Test fun largeModsWarnWhenTheJavaHeapIsTooSmallOnAnyPhone() {
        assertTrue(MemoryNotice.tooSmall(4 * gb, 128 * mb, multiverse))
        assertFalse(MemoryNotice.tooSmall(4 * gb, 192 * mb, multiverse))
    }

    @Test fun smallSetupsNeverWarn() {
        assertFalse(MemoryNotice.tooSmall(gb, 128 * mb, hyperspace))
        assertFalse(MemoryNotice.tooSmall(gb, 128 * mb, 0))
    }

    @Test fun anOutOfMemoryErrorIsFoundAmongTheCauses() {
        assertTrue(MemoryNotice.isOutOfMemory(OutOfMemoryError("x")))
        assertTrue(MemoryNotice.isOutOfMemory(RuntimeException("load", IllegalStateException(OutOfMemoryError("x")))))
        assertFalse(MemoryNotice.isOutOfMemory(RuntimeException("load")))
        assertFalse(MemoryNotice.isOutOfMemory(null))
    }
}
