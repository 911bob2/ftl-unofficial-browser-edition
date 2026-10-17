package xyz.znix.xftl.game

import xyz.znix.xftl.VisualRandom
import xyz.znix.xftl.f
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics

/**
 * Displays a message with an optional line pointing to it.
 */
class WarningFlasher(
    val game: InGameState,
    var centre: IPoint,
    key: String,
    val warningWarning: Boolean = true,
    var linePoints: List<IPoint> = emptyList(),
    val colour: GlowColour = GlowColour.RED,
    val animated: Boolean = true,
    val alwaysOn: Boolean = false,
    /** Underline WARNING! across its width, as the original's WarningMessage does without a pointer line. */
    val underline: Boolean = false,
    /** Centre each line on [centre]'s x; otherwise the lines start there (WarningMessage's centred flag). */
    val centred: Boolean = true,
    /**
     * WarningMessage::SetSound (0x005d7a10): played by Start (0x005d7a50) only when the message wasn't running
     * already. U28's hook - see [startSound].
     */
    val sound: String? = null
) {
    private var wasShowing = false
    private var lastDrawNanos = 0L
    private var textLines: List<String> = game.translator[key].split("\n")

    /** The message's current text, its lines joined by newlines. */
    val text: String get() = textLines.joinToString("\n")

    /** Swap the text, keeping the message's place and timer (the original re-inits it with another string). */
    fun setText(key: String) {
        textLines = game.translator[key].split("\n")
    }

    /** Put the message's top at the original's [x], [y] for its current text (see [atNative]). */
    fun placeNative(x: Int, y: Int) {
        centre = ConstPoint(x, nativeCentreY(y, textLines.size, warningWarning))
    }
    private val warningText: String = game.translator["warning_warning"]

    private val font = game.getFont("HL1", 2f)

    private var stopTime: Long = 0

    // Stop all the warnings flashing in sync.
    private val flashOffset = VisualRandom.nextLong()

    var timeRemaining: Float = 0f
        private set

    // Read the clock rather than the last drawn frame's timeRemaining, so a message started this frame counts.
    val isRunning: Boolean get() = alwaysOn || stopTime - System.nanoTime() > 0

    /**
     * If this flasher is flashing, this checks if it's at it's stronger intensity.
     *
     * This is used for flashing other components when this warning running.
     */
    var isFlashingHigh: Boolean = false
        private set

    fun draw(g: Graphics) {
        update()

        // A message drawn only while its condition holds (alwaysOn) counts as started when it reappears.
        val now = System.nanoTime()
        if (isRunning && (!wasShowing || (alwaysOn && now - lastDrawNanos > 200_000_000L)))
            startSound()
        wasShowing = isRunning
        lastDrawNanos = now

        if (!isRunning)
            return

        val alpha = when {
            !animated -> 1f // Not flashing
            isFlashingHigh -> 0.9f
            else -> 0.4f
        }

        val lineSpacing = 18
        val topY = centre.y - lineSpacing * (textLines.size - 1)

        for ((index, line) in textLines.withIndex()) {
            val x = if (centred) centre.x - font.getWidth(line) / 2 else centre.x
            UIUtils.drawStringWithGlow(game, font, line, x, topY + lineSpacing * index, colour, alpha)
        }

        if (warningWarning) {
            // WARNING! sits above the message's first line.
            val spacing = if (linePoints.isEmpty()) 24 else 23
            val x = centre.x - font.getWidth(warningText) / 2

            UIUtils.drawStringWithGlow(game, font, warningText, x, topY - spacing, colour, alpha)

            if (underline) {
                g.colour = Colour(this.colour.colour).also { it.a = alpha }
                // 0x005d6490: the underline is 20 below the top of WARNING!, whose baseline is 16 below it.
                g.fillRect(x.f, topY - spacing + 4f, font.getWidth(warningText).f, 2f)
            }
        }

        g.colour = Colour(this.colour.colour).also { it.a = alpha }
        for (i in 0 until linePoints.size - 1) {
            val a = linePoints[i]
            val b = linePoints[i + 1]

            // TODO make the line thickness properly match FTL - on angles
            //  ours doesn't look as good.

            g.lineWidth = 2f
            g.drawLine(a.x.f, a.y.f, b.x.f, b.y.f)
            g.lineWidth = 1f
        }
    }

    private fun update() {
        val current = System.nanoTime()
        val remainingNS = stopTime - current

        timeRemaining = when {
            remainingNS < 0 -> 0f

            // Guard against overflow, however unlikely that might be.
            remainingNS > (1L shl 50) -> 0f

            else -> remainingNS / 1_000_000_000f
        }

        if (!isRunning) {
            isFlashingHigh = false
        } else {
            // Calculate our flash timer.
            // Don't use the time remaining, as that might be constantly reset.
            val period = 1_000_000_000
            var timeNS = (current + flashOffset) % period
            if (timeNS < 0)
                timeNS += period
            val time = timeNS.toFloat() / period

            isFlashingHigh = time > 0.5f
        }
    }

    fun startFor(time: Float) {
        if (alwaysOn) {
            throw IllegalStateException("Cannot start always-on warning")
        }

        val current = System.nanoTime()
        stopTime = current + (time * 1_000_000_000f).toLong()
    }

    /** The message's not-running to running transition (WarningMessage::Start): its sound, if it has one. */
    private fun startSound() {
        val name = sound ?: return
        game.sounds.getSampleOrWarn(name)?.play()
    }

    /** Keep a looping message (WarningMessage::SetLoop) running while [on] holds, and stop it otherwise. */
    fun loopWhile(on: Boolean) {
        if (on) {
            if (timeRemaining < 1f) startFor(60f)
        } else if (timeRemaining > 0f) {
            stop()
        }
    }

    fun stop() {
        if (alwaysOn) {
            throw IllegalStateException("Cannot stop always-on warning")
        }

        stopTime = 0
    }

    companion object {
        /**
         * A WarningMessage placed as the original's text render (0x005d6490) does: [x], [y] is the
         * top of WARNING! when [warningLine] is set, with the text 24 below it, and otherwise the top
         * of the text. The lines are font 59, baseline 16 below their top and 18 apart.
         */
        fun atNative(
            game: InGameState, x: Int, y: Int, key: String, warningLine: Boolean,
            colour: GlowColour, flash: Boolean, centred: Boolean = true, sound: String? = null
        ): WarningFlasher {
            val lines = game.translator[key].split("\n").size
            return WarningFlasher(
                game, ConstPoint(x, nativeCentreY(y, lines, warningLine)), key, warningLine,
                colour = colour, animated = flash, underline = warningLine, centred = centred, sound = sound
            )
        }

        /** The [centre] y that puts a message of [lines] lines at the original's [y] (see [atNative]). */
        fun nativeCentreY(y: Int, lines: Int, warningLine: Boolean): Int =
            y + 16 + (if (warningLine) 24 else 0) + 18 * (lines - 1)
    }
}
