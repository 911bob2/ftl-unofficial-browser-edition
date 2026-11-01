package ftl.android.app.mods

import ftl.mods.PatchHost
import ftl.mods.PatchNative
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** libftlmanlua.so (android/engine/src/main/jni/ftlman_jni.c). Its state is process-global: one runtime at a time. */
object FtlmanLua : PatchNative {
    private val free = Semaphore(1)
    private var host: PatchHost? = null
    private var loaded = false

    /** path: for tools that load the library from a file; the app loads it by name. */
    @Synchronized
    fun load(path: String? = null) {
        if (loaded) return
        if (path != null) System.load(path) else System.loadLibrary("ftlmanlua")
        loaded = true
    }

    override fun init(instructions: Double, memory: Double, host: PatchHost): Int {
        load()
        if (!free.tryAcquire(10, TimeUnit.MINUTES)) throw IllegalStateException("Another mod merge is still running")
        this.host = host
        return nativeInit(instructions, memory)
    }

    override fun close() {
        if (host == null) return
        nativeClose()
        host = null
        free.release()
    }

    override fun run(codeLen: Int, nameLen: Int, pathLen: Int, docLen: Int, fsLen: Int, flags: Int) = nativeRun(codeLen, nameLen, pathLen, docLen, fsLen, flags)
    override fun bufCap() = nativeBufCap()
    override fun bufGrow(n: Int) = nativeBufGrow(n)
    override fun bufRead(offset: Int, length: Int) = nativeBufRead(offset, length)
    override fun bufWrite(offset: Int, bytes: ByteArray, from: Int, length: Int) = nativeBufWrite(offset, bytes, from, length)
    override fun instructions() = nativeInstructions()
    override fun memory() = nativeMemory()
    override fun memoryPeak() = nativeMemoryPeak()
    override fun nodes() = nativeNodes()

    @JvmStatic private external fun nativeInit(instructions: Double, memory: Double): Int
    @JvmStatic private external fun nativeClose()
    @JvmStatic private external fun nativeRun(codeLen: Int, nameLen: Int, pathLen: Int, docLen: Int, fsLen: Int, flags: Int): Int
    @JvmStatic private external fun nativeBufCap(): Int
    @JvmStatic private external fun nativeBufGrow(n: Int): Boolean
    @JvmStatic private external fun nativeBufRead(offset: Int, length: Int): ByteArray
    @JvmStatic private external fun nativeBufWrite(offset: Int, bytes: ByteArray, from: Int, length: Int)
    @JvmStatic private external fun nativeInstructions(): Double
    @JvmStatic private external fun nativeMemory(): Double
    @JvmStatic private external fun nativeMemoryPeak(): Double
    @JvmStatic private external fun nativeNodes(): Double

    // Called by name from C, on the merge's thread. Every host method catches what it throws.
    @JvmStatic fun hostLog(kind: Int, bytes: ByteArray) { host?.log(kind, bytes) }
    @JvmStatic fun hostFs(fs: Int, op: Int, pathLen: Int, dataLen: Int) = host!!.fs(fs, op, pathLen, dataLen)
    @JvmStatic fun hostXmlParse(len: Int, topLevelText: Int) = host!!.xmlParse(len, topLevelText != 0)
    @JvmStatic fun hostXmlWrite(len: Int) = host!!.xmlWrite(len)
    @JvmStatic fun hostXmlAppend(lowerLen: Int, upperLen: Int, kind: Int) = host!!.xmlAppend(lowerLen, upperLen, kind)
}
