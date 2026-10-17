package xyz.znix.xftl.game

import ftl.browser.BrowserClock
import xyz.znix.xftl.Datafile
import xyz.znix.xftl.rendering.Image
import xyz.znix.xftl.rendering.StagedImageLoad
import xyz.znix.xftl.sys.ResourceContext

/**
 * Loads images ahead of the frame that needs them. Decoding and uploading one image costs from a few milliseconds
 * to a tenth of a second for a full-screen background, and doing that when the picture is first drawn is a visible
 * stall: the jump to a new beacon draws a new backdrop and planet on the very first frame of the arrival.
 *
 * Callers [request] the paths they expect to need soon; [step] (once per frame) loads them a few milliseconds at a
 * time into [images], the cache `getImg` reads, so the later `getImg` finds them. An image asked for before it is
 * ready is finished at once ([takeNow]), which costs no more than loading it plainly would.
 *
 * Prefetching changes no game state and draws from no random stream, only when the pixels are decoded.
 */
class ImagePrefetcher(
    private val datafile: Datafile,
    private val context: ResourceContext,
    private val images: MutableMap<String, Image>
) {
    private val queue = ArrayDeque<String>()
    private var current: StagedImageLoad? = null

    /** True when nothing is waiting or loading. */
    val isIdle: Boolean get() = current == null && queue.isEmpty()

    /** The number of images still to load. */
    val pending: Int get() = queue.size + (if (current != null) 1 else 0)

    /**
     * Asks for [path] to be loaded in the background, unless it already is or does not exist. An [urgent] request
     * goes ahead of the others, for the pictures the next beacon is about to show.
     */
    fun request(path: String, urgent: Boolean = false) {
        if (path in images || datafile.getOrNull(path) == null) return
        if (current?.path == path) return
        queue.remove(path)
        if (urgent) queue.addFirst(path) else queue.addLast(path)
    }

    fun requestAll(paths: Collection<String>, urgent: Boolean = false) {
        // Keep the given order at the front of the queue.
        for (path in if (urgent) paths.reversed() else paths) request(path, urgent)
    }

    /** Works for about [budgetMs] on the queue. */
    fun step(budgetMs: Double) {
        val end = BrowserClock.now() + budgetMs
        while (true) {
            val job = current ?: startNext() ?: return
            val left = end - BrowserClock.now()
            if (left <= 0.0) return

            val done = try {
                job.step(left)
            } catch (e: Exception) {
                // The plain load will report the same problem to whoever really needs the image.
                println("[WARN] Background load of ${job.path} failed: $e")
                job.cancel()
                current = null
                continue
            }
            if (!done) return

            images[job.path] = job.image!!
            current = null
        }
    }

    /**
     * Finishes [path] now if it is the load in progress, and returns the image; null if it is merely queued or not
     * ours (a queued request is dropped, the caller is about to load it anyway).
     */
    fun takeNow(path: String): Image? {
        val job = current
        if (job != null && job.path == path) {
            current = null
            val image = try {
                job.finish()
            } catch (e: Exception) {
                job.cancel()
                return null
            }
            images[path] = image
            return image
        }
        queue.remove(path)
        return null
    }

    /** Drops everything that is queued and frees a half-made texture. */
    fun cancelAll() {
        queue.clear()
        current?.cancel()
        current = null
    }

    private fun startNext(): StagedImageLoad? {
        while (queue.isNotEmpty()) {
            val path = queue.removeFirst()
            if (path in images) continue
            val file = datafile.getOrNull(path) ?: continue
            val job = try {
                StagedImageLoad(context, path, datafile.read(file))
            } catch (e: Exception) {
                println("[WARN] Background load of $path failed: $e")
                continue
            }
            current = job
            return job
        }
        return null
    }
}
