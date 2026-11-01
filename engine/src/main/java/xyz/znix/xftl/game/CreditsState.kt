package xyz.znix.xftl.game

import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.NativeFonts
import xyz.znix.xftl.sys.GameContainer
import kotlin.math.max
import kotlin.math.min

/**
 * The original's credits roll (Credits: start 0x005753b0, render 0x00572240, done 0x005721c0), shared by the
 * main menu's credits and the victory screen, which first thanks the ship and its crew.
 */
class CreditsRoll(content: InGameState.GameContent, val victory: Victory? = null) {
    /** GameOver::SetVictory's (0x004d6630) arguments: the ship's name and its crew's names. */
    class Victory(val shipName: String, val crewNames: List<String>)

    private val touch = TouchMode.enabled
    private val background = content.getImg(if (touch && content.datafile.getOrNull("img/ipad/stars/bg_darknebula.png") != null)
        "img/ipad/stars/bg_darknebula.png" else "img/stars/bg_darknebula.png")
    private val fonts = HashMap<Int, NativeFonts.NativeFont>()
    private val font: (Int) -> NativeFonts.NativeFont = { id -> fonts.getOrPut(id) { NativeFonts.font(content::getFont, id) } }
    val backers = content.datafile.readString(content.datafile["data/credits.txt"])
        .lineSequence().map { it.trimEnd('\r') }.filter { it.isNotEmpty() }.toList()
    private val lines = ArrayList<CreditLine>()

    /** The height the roll is seen in, and where it stops for the first credits. */
    private val screenHeight = if (touch) TouchMode.HUD_H else 720

    /** The roll's offset: -70 for the menu (less on a taller screen), so "A game from:" starts at the bottom; 0 after a victory. */
    var scroll = if (victory == null) screenHeight - 790f else 0f
        private set

    /** Counts down before the roll moves: 5 s after a victory (the first second fading in), 3 s at -720. */
    private var timer = if (victory == null) 0f else 5f

    val endPosition get() = backers.size / 3 * 40 + if (touch) 4270f else 4266f
    val lineCount get() = lines.size

    /** Each block's text, top to bottom, for the parity checks. */
    internal val texts: List<String> get() = lines.map { it.text }

    /** Hyperspace's credits (altCreditSystem, hs.render.HsCredits) replace the roll. */
    private val hs: xyz.znix.xftl.hs.render.HsCredits.Roll? = if (!xyz.znix.xftl.hs.render.HsCredits.enabled()) null else {
        val dv = xyz.znix.xftl.hs.HS.config?.let { content.eventManager.hs?.defaultVictory?.gameOver }
        xyz.znix.xftl.hs.render.HsCredits.Roll(content, victory?.shipName ?: "", victory?.crewNames ?: emptyList(),
            dv?.creditsText ?: "", dv?.creditsBackground ?: "")
    }

    /** Done (0x005721c0) once the last line has passed the top. */
    val isDone get() = hs?.isDone ?: (-scroll > endPosition)

    init {
        val translator = content.translator
        fun text(id: String, y: Int, font: Int, wrap: Int = 0) = add(translator[id], 640, y, font, wrap)

        if (victory != null) {
            text("thank_ship", 150, 14)
            add(victory.shipName, 640, 190, 20)
            text("thank_crew", 270, 14)
            // The names joined by crewlist_comma, the last two by crewlist_and.
            val names = StringBuilder()
            for ((i, name) in victory.crewNames.withIndex()) {
                names.append(name)
                if (i < victory.crewNames.size - 2) names.append(translator.untrimmed("crewlist_comma"))
                else if (i == victory.crewNames.size - 2) names.append(translator.untrimmed("crewlist_and"))
            }
            add(names.toString(), 640, 310, 18, 900)
            text("credit_victory", 510, 14, 750)
        }

        text("game_from", 775, 14); text("subset_games", 815, 18)
        text("created_by", 925, 14); text("matthew_justin", 965, 18)
        text("addition_code", 1075, 14); text("andrew_church", 1115, 18)
        text("music_by", 1205, 14); text("ben_prunty", 1245, 18)
        text("writing_by", 1335, 14); text("tom_jubert", 1375, 18)
        text("guest_writer", 1460, 14); text("chris_a", 1500, 18)
        text("additional_art", 1590, 14); text("bryan_ma", 1630, 18)
        text("editing", 1720, 14); text("alison_w", 1760, 18)
        text("additional_testing", 1850, 14); text("family_friends", 1890, 18, 750)
        text("credits_loc_riotloc", 2090, 18, 750)
        text("credits_loc_project", 2180, 14, 750); text("credits_loc_project_names", 2220, 18, 750)
        for ((language, y) in listOf("fr" to 2320, "it" to 2460, "de" to 2600, "es" to 2706,
            "pl" to 2846, "pt_BR" to 2986, "ru" to 3126)) {
            text("credits_loc_$language", y, 14, 750); text("credits_loc_${language}_names", y + 40, 18, 750)
        }
        text("credits_loc_ja", 3366, 14, 750); text("credits_loc_ja_84", 3406, 18, 750)
        text("credits_loc_ja_names", 3456, 18, 750); text("kickstarter", 3656, 14, 750)
        backers.forEachIndexed { i, name -> add(name, listOf(270, 640, 1010)[i % 3], 3736 + i / 3 * 40, 14) }
        val tail = backers.size / 3 * 40
        for ((id, y) in listOf("third_party" to 3836, "folder" to 3876, "bass" to 3956,
            "rapidxml" to 4006, "freetype" to 4056, "font_halflife" to 4156, "font_dotgothic" to 4206)) text(id, tail + y, 12)
        lines.sortBy { it.y }
    }

    /** A block centred on [x] from its top [y], wrapped at [wrap] when set (freetype's printers). */
    private fun add(text: String, x: Int, y: Int, fontId: Int, wrap: Int = 0) {
        val f = font(fontId)
        val parts = if (wrap > 0) f.font.wrapString(text, wrap) else listOf(text)
        lines += CreditLine(parts.joinToString("\n"), x, y, f)
    }

    /**
     * The timer runs down first; then the roll moves 3.5 px per 1/16 s (56 px/s), stopping for 3 s the moment
     * it has moved a screen height (-720), as the first credits fill the screen.
     */
    fun advance(delta: Float) {
        hs?.let { it.advance(delta); return }
        var remaining = max(0f, delta)
        if (timer > 0f) {
            val waited = min(remaining, timer)
            timer -= waited
            remaining -= waited
        }
        if (remaining <= 0f) return
        val stop = -screenHeight.toFloat()
        if (scroll > stop && scroll - remaining * 56f <= stop) {
            remaining -= (scroll - stop) / 56f
            scroll = stop
            timer = 3f
            val waited = min(remaining, timer)
            timer -= waited
            remaining -= waited
        }
        scroll -= remaining * 56f
    }

    fun render(g: Graphics) {
        hs?.let {
            // Hyperspace's roll is laid out for the 1280x720 frame, background and all.
            if (touch) TouchArt.cover(g, 1280, 720, TouchMode.canvasW, TouchMode.HUD_H) { it.render(g) }
            else it.render(g)
            return
        }
        // The background (at half brightness) and the text fade in over the first second of the 5 s wait.
        val alpha = if (timer <= 4f) 1f else 5f - timer
        if (touch) TouchArt.cover(g, background.width, background.height, TouchMode.canvasW, TouchMode.HUD_H) {
            background.draw(0f, 0f, Colour(.5f, .5f, .5f, alpha))
        } else background.draw(0f, 0f, Colour(.5f, .5f, .5f, alpha))
        val colour = Colour(1f, 1f, 1f, alpha)
        // The lines are placed around the middle of the 1280 wide frame.
        val shift = if (touch) TouchMode.canvasW / 2 - 640 else 0
        for (line in lines) {
            // Each block is drawn whole while its top is on screen.
            val y = line.y + scroll
            if (y <= -50f || y >= screenHeight) continue
            line.font.printCenter((line.x + shift).toFloat(), y, line.text, colour)
        }
    }

    private class CreditLine(val text: String, val x: Int, val y: Int, val font: NativeFonts.NativeFont)
}

/**
 * The main menu's credits: any key leaves them (MainMenu 0x004d1900); clicks do nothing (0x004d47e0). In the touch
 * layout a finger held down for two seconds leaves them too, and a hint says so while a finger is down.
 */
class CreditsState(private val main: MainGame, private val returnToMenu: () -> Unit) : MainGame.GameState() {
    private val roll = CreditsRoll(main.gameContent)
    private val hintFont by lazy { xyz.znix.xftl.rendering.NativeFonts.font(main.gameContent::getFont, 12) }
    private var fingerTime = 0f
    private var hint = 0f

    val scroll get() = roll.scroll
    val backerCount get() = roll.backers.size
    val lineCount get() = roll.lineCount
    val endPosition get() = roll.endPosition

    override fun update(container: GameContainer, delta: Float) {
        roll.advance(delta)
        (main.gameContent.sounds as? RealSoundManager)?.let { it.updateLoopedSounds(false); it.updateMusic(delta) }
        if (TouchMode.enabled) {
            if (container.input.isMouseButtonDown(xyz.znix.xftl.sys.Input.MOUSE_LEFT_BUTTON)) {
                fingerTime += delta
                hint = 1f
            } else {
                fingerTime = 0f
                hint = max(0f, hint - delta * HINT_FADE)
            }
            if (fingerTime >= SKIP_TIME) { returnToMenu(); return }
        }
        if (roll.isDone) returnToMenu()
    }

    override fun render(container: GameContainer, g: Graphics) {
        g.clear(Colour.black)
        roll.render(g)
        if (hint > 0f) drawHint(g)
    }

    /** The hint in the block's top right corner: a dark box around it, both fading out once the finger lifts. */
    private fun drawHint(g: Graphics) {
        val text = main.gameContent.translator["touch_skip"]
        val alpha = min(1f, hint / 0.75f)
        val w = hintFont.width(text)
        val right = TouchMode.hudX + TouchMode.HUD_W
        g.colour = Colour(0f, 0f, 0f, 0.75f * alpha)
        g.fillRect(right - (w + 20), 0, w + 20, hintFont.lineHeight + 20)
        hintFont.print((right - (w + 10)).toFloat(), 10f, text, Colour(1f, 1f, 0.2f, alpha))
    }

    override fun shutdown() {}
    override fun keyPressed(key: Int, c: Char) = returnToMenu()

    private companion object {
        const val SKIP_TIME = 2f
        /** A sixteenth of the hint per frame at 60 frames a second. */
        const val HINT_FADE = 3.75f
    }
}
