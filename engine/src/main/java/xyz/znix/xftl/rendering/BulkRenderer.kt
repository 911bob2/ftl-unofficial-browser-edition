package xyz.znix.xftl.rendering

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/** Accumulates one draw's vertices until it is flushed to the [DrawQueue]. */
abstract class BulkRenderer {
    protected var data: ByteBuffer = generateBuffer(128)
    protected var numVerts = 0

    /**
     * Ensures there's at least [required] bytes of space remaining in [data].
     */
    protected fun checkSize(required: Int) {
        if (data.remaining() >= required)
            return

        val current = data.position() + data.remaining()
        val newData = generateBuffer(max(required, current * 2))

        // Copy the old data into the new buffer
        data.flip()
        newData.put(data)
        data = newData
    }

    protected fun generateBuffer(size: Int): ByteBuffer {
        return ByteBuffer.allocateDirect(size).apply {
            order(ByteOrder.nativeOrder())
        }
    }
}
