package xyz.znix.xftl.game

/**
 * The touch layout of the Android app, the same controls as FTL on iPad: flight, the main menu, its options and credits
 * and the hangar draw on a W×768 canvas with a 1024×768 block of controls centred in it. Other screens keep the
 * 1280×720 frame until they have their touch layout.
 * Off in the browser build.
 */
object TouchMode {
    const val HUD_W = 1024
    const val HUD_H = 768

    @JvmField
    var enabled = false

    /** A name is being typed: the host shows its on-screen keyboard. */
    @JvmField
    var keyboardWanted = false

    /** The bottom of the name being typed on the 768 high canvas: the host keeps it above its keyboard. */
    @JvmField
    var keyboardFocusY = 0

    /** The flight canvas width for the screen's aspect ratio (set by the host). */
    @JvmField
    var canvasW = HUD_W

    /** The HUD block's left edge on the canvas. */
    @JvmStatic
    val hudX: Int get() = (canvasW - HUD_W) / 2

    @JvmStatic
    fun canvasWidthFor(viewW: Int, viewH: Int): Int =
        if (viewH <= 0) HUD_W else Math.round(HUD_H * viewW / viewH.toFloat()).coerceAtLeast(HUD_W)
}
