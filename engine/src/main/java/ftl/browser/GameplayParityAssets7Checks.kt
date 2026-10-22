package ftl.browser

import org.lwjgl.opengl.GL11
import org.newdawn.slick.opengl.PNGDecoder
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.game.Resource
import xyz.znix.xftl.game.ShipProgression
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.Image
import xyz.znix.xftl.rendering.StagedImageLoad
import xyz.znix.xftl.rendering.Texture
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.sys.ResourceContext
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.math.abs

/**
 * Round 7: a playtester saw the scrap, missile and drone-part reward icons as a mess of pixels next
 * to a correct "+N".
 *
 * The reward icons were kept in the global [Resource] enum, which outlives the game content. Replacing the content
 * (the tutorial, a run in the other Advanced Edition mode) deletes its textures, and the kept images then bound a
 * deleted texture: WebGL refuses the bind and the quad samples whatever was bound before. The checks replace the
 * content both ways and compare the drawn icons with their pictures pixel for pixel, and check that a draw of a
 * deleted texture draws nothing instead of another picture.
 */
internal object GameplayParityAssets7Checks {
    private const val X = 40
    private const val Y = 40

    fun run(main: MainGame, container: GameContainer, graphics: Graphics): String {
        val rng = BrowserRandom.snapshot()
        val nativeRng = NativeRandom.snapshot()
        BrowserGameplayParityDiagnostics.fresh(main, container)
        val mode = main.gameContent.enableAdvancedEdition
        val savedPreference = main.profile.lastAdvancedEdition
        val savedGlobal = BrowserFiles.enableAE
        try {
            val swap = contentSwap(main, container, graphics)
            val tutorial = tutorialSwap(main, container, graphics)
            val guard = deletedTextureDraw(main, container, graphics)
            return "assets7 ok: $swap, $tutorial, $guard"
        } finally {
            main.changeHangarMode(mode, ShipProgression.KESTREL, Difficulty.NORMAL)
            main.profile.lastAdvancedEdition = savedPreference
            BrowserFiles.enableAE = savedGlobal
            main.switchToMainMenu()
            BrowserRandom.restore(rng)
            NativeRandom.restore(nativeRng)
        }
    }

    private fun iconPath(resource: Resource) = "img/ui_icons/icon_${resource.name.toLowerCase(Locale.UK)}.png"

    /** Every reward icon of [state] is its content's own, alive, and draws exactly its picture. */
    private fun iconsIntact(state: InGameState, graphics: Graphics, label: String): Int {
        var pixels = 0
        for (resource in Resource.values()) {
            val icon = resource.getIcon(state)
            // Pixels first, so a failure shows what the reward line would draw.
            pixels += drawnMatches(state, graphics, icon, iconPath(resource), "$label ${resource.name}")
            check(!icon.texture.freed) { "$label: the ${resource.name} icon is drawn from a deleted texture of replaced content" }
            check(icon === state.getImg(iconPath(resource))) { "$label: the ${resource.name} icon is not the game content's image" }
        }
        return pixels
    }

    /** Reward lines drawn in one content, then the content is rebuilt for the other mode (a run in that mode). */
    private fun contentSwap(main: MainGame, container: GameContainer, graphics: Graphics): String {
        val first = BrowserGameplayParityDiagnostics.fresh(main, container)
        val firstMode = first.content.enableAdvancedEdition
        val shown = Resource.values().map { it.getIcon(first) }
        iconsIntact(first, graphics, "before the swap")
        val old = main.gameContent
        main.changeHangarMode(!firstMode, ShipProgression.KESTREL, Difficulty.NORMAL)
        val second = BrowserGameplayParityDiagnostics.fresh(main, container)
        check(second.content !== old && second.content.enableAdvancedEdition == !firstMode) { "the content was not rebuilt" }
        check(shown.all { it.texture.freed }) { "replacing the content kept its textures, so the swap tests nothing" }
        val pixels = iconsIntact(second, graphics, "after the swap to AE ${!firstMode}")
        return "icons after a content swap ($pixels icon pixels)"
    }

    /** The playtester's likely path: the tutorial (vanilla content on a temporary profile), then an Advanced Edition run. */
    private fun tutorialSwap(main: MainGame, container: GameContainer, graphics: Graphics): String {
        main.changeHangarMode(true, ShipProgression.KESTREL, Difficulty.NORMAL)
        BrowserGameplayParityDiagnostics.fresh(main, container)
        val aeContent = main.gameContent
        main.switchToMainMenu()
        val tutorial = main.startTutorialSession()
        check(tutorial.content !== aeContent && !tutorial.content.enableAdvancedEdition) { "the tutorial did not get vanilla content" }
        val shown = Resource.values().map { it.getIcon(tutorial) }
        iconsIntact(tutorial, graphics, "in the tutorial")
        main.endTutorialSession()
        check(shown.all { it.texture.freed }) { "leaving the tutorial kept its textures" }
        val run = BrowserGameplayParityDiagnostics.fresh(main, container)
        check(run.content.enableAdvancedEdition) { "the run after the tutorial is not AE" }
        val pixels = iconsIntact(run, graphics, "after the tutorial")
        return "icons after the tutorial ($pixels icon pixels)"
    }

    /** A draw whose texture was deleted is skipped and counted, never drawn with the texture bound before it. */
    private fun deletedTextureDraw(main: MainGame, container: GameContainer, graphics: Graphics): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val path = iconPath(Resource.SCRAP)
        val datafile = state.content.datafile
        val context = ResourceContext()
        val doomed = StagedImageLoad(context, path, datafile.read(datafile[path])).finish()
        // Leave another picture bound: the one a refused bind would sample.
        drawAt(graphics, state.getImg(iconPath(Resource.FUEL)), Colour.white)
        context.freeAll()
        val before = Texture.freedDraws
        val got = drawAt(graphics, doomed, Colour.white)
        check(Texture.freedDraws == before + 1) { "the draw of a deleted texture was not refused (${Texture.freedDraws - before})" }
        for (i in got.indices) {
            val value = got[i].toInt() and 255
            check(value == (if (i % 4 == 3) 255 else 0)) { "a deleted texture drew something at byte $i ($value)" }
        }
        return "deleted-texture draw refused"
    }

    /** Clears to black, draws [image] at (X, Y) at 1:1 and reads its rectangle back. */
    private fun drawAt(graphics: Graphics, image: Image, colour: Colour): ByteArray {
        val savedTint = Graphics.tint
        GL11.glClearColor(0f, 0f, 0f, 1f)
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)
        graphics.markCurrentImageTransformSource()
        graphics.pushTransform()
        try {
            Graphics.tint = Colour.white
            graphics.loadIdentityMatrix()
            image.draw(X, Y, colour)
        } finally {
            graphics.popTransform()
            Graphics.tint = savedTint
        }
        return BrowserPixels.read(X, Y, image.width, image.height)
    }

    /**
     * Draws [image] on black and compares every pixel with the PNG at [path] blended onto black (the game's
     * SRC_ALPHA, ONE_MINUS_SRC_ALPHA). Returns the number of opaque pixels compared.
     */
    private fun drawnMatches(state: InGameState, graphics: Graphics, image: Image, path: String, label: String): Int {
        val datafile = state.content.datafile
        val decoder = PNGDecoder(ByteArrayInputStream(datafile.read(datafile[path])))
        val w = decoder.width
        val h = decoder.height
        check(image.width == w && image.height == h) { "$label: image ${image.width}x${image.height}, picture ${w}x$h" }
        val buffer = ByteBuffer.allocate(w * h * 4)
        decoder.decode(buffer, w * 4, PNGDecoder.RGBA)
        val want = buffer.array()
        val got = drawAt(graphics, image, Colour.white)
        var opaque = 0
        for (i in 0 until w * h) {
            val a = want[i * 4 + 3].toInt() and 255
            if (a > 128) opaque++
            for (c in 0 until 3) {
                val expected = ((want[i * 4 + c].toInt() and 255) * a + 127) / 255
                val actual = got[i * 4 + c].toInt() and 255
                check(abs(expected - actual) <= 3) {
                    "$label: pixel (${i % w},${i / w}) channel $c is $actual, the picture gives $expected"
                }
            }
        }
        check(opaque >= 64) { "$label: only $opaque opaque pixels in the picture" }
        return opaque
    }
}
