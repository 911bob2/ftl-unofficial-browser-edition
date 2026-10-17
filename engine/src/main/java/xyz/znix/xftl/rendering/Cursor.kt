package xyz.znix.xftl.rendering

import xyz.znix.xftl.Datafile
import xyz.znix.xftl.FTLFile
import xyz.znix.xftl.sys.INativeResource
import xyz.znix.xftl.sys.ResourceContext

/**
 * Represents a mouse cursor.
 *
 * On Android there is no OS cursor; touch input is used directly (as on the
 * official iPad version of FTL), so this is a stub that keeps the engine's
 * resource-management invariants intact.
 */
class Cursor(
    context: ResourceContext,
    df: Datafile,
    val mainFile: FTLFile,
    val overlayFile: FTLFile?,
    /** The 32px frame of a strip image (the door cursor), or -1 for the whole image. */
    val frame: Int = -1
) : INativeResource {
    /**
     * What the browser shell draws: MouseControl's render (0x4e6240) draws the image at the
     * mouse with no offset, then the overlay (the armed weapon's numbered crosshair) at the
     * same place. Format: main[,overlay][#frame].
     */
    val key: String = mainFile.name + (overlayFile?.let { "," + it.name } ?: "") + (if (frame >= 0) "#$frame" else "")

    override var freed: Boolean = false
        private set

    init {
        context.register(this)
    }

    fun setActive(glfwWindow: Long) {
        // No OS cursors on Android
    }

    override fun free() {
        check(!freed) { "Cannot double-free cursor '${mainFile.name}'" }
        freed = true
    }
}
