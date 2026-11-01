// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Gameplay/World/CustomEvents.cpp
// (ParseCustomCredits, the CreditScreen Start/Done/OnRender, GameOver and TextLibrary::GetText hooks) and
// src/game/UserInterface/Credits.cpp
package xyz.znix.xftl.hs.render

import org.jdom2.Element
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.Image
import xyz.znix.xftl.rendering.NativeFonts

/**
 * Hyperspace's credits (`<altCreditSystem enabled>` with `<customCredits>`): instead of the original's fixed roll,
 * the roll is `<text id fontSize horizontal spacing lineLenght>` blocks (each centred on `horizontal`, wrapped at
 * `lineLenght`, the next one `spacing` lower), then data/credits.txt's names three to a row (at 256, 640 and 1024,
 * `<creditNames fontSize spacingBetweenNames spacingToFinishTexts>`), then the `<finishText>` blocks, starting
 * 740 px down. After a victory the ship's name, its crew (comma list, cut with crewlist_more past 140 characters)
 * and credit_victory (or the event's `creditsText`) come first from y 144. It scrolls 3.5 speed-factor units a
 * frame times `<scrollSpeed speed>` (56 px/s at speed 1), waits `<scrollPause scrollDelay>` x 50 units at the top
 * (a victory's roll: the original's Start leaves the menu's at -70, so it never waits there) and stops once at
 * `pausePosition` until the pause counter reaches `duration` x 50 + scrollDelay; a victory fades the backdrop out
 * from on top at 3.5 x 0.025 x `<fadeInSpeed speed>` a unit. The backdrop is stars/bg_darknebula at half grey, or
 * an image of the victory event's (`<win creditsBackground>`, else the default victory's) image list; the title
 * playlist that GameOver::OnLoop starts 25 clock units after a victory is `<win music>` when set. It ends when the
 * roll has moved past 750 plus every spacing.
 *
 * Text blocks are culled once their top is above -(width of the text *id* in the block's font, wrapped at its
 * length, + 50): HS measures the id, not the text.
 */
object HsCredits {
    private class Block(val text: String, val font: Int, val spacing: Int, val horizontal: Int, val lineLength: Int, val cutOff: Float)
    private class Name(val names: String, val spacing: Int, val cutOff: Float)

    private var scrollSpeed = 1f
    private var fadeInSpeed = -0.005f
    private var scrollDelay = 250f
    private var pauseDuration = 400f
    private var pausePosition = 675f
    private var namesFont = 14
    private val texts = ArrayList<Block>()
    private val finishTexts = ArrayList<Block>()
    private val names = ArrayList<Name>()

    /** CustomEvents.cpp's `fadeIn` global: never reset for the menu's roll. */
    private var fadeIn = 0f

    // The victory's replacements (replaceCreditsBackground / replaceGameOverCreditsText / replaceCreditsMusic)
    @JvmField var replaceBackground = ""
    @JvmField var replaceText = ""
    @JvmField var replaceMusic = ""

    @JvmStatic
    fun enabled(): Boolean = HS.active && HS.cfg().altCreditSystem

    private fun f(s: String?, d: Float) = s?.trim()?.toFloatOrNull() ?: d
    private fun i(s: String?, d: Int) = s?.trim()?.toIntOrNull() ?: d

    /** CustomEventsParser::ParseCustomCredits (once per content load; [measure] is easy_measurePrintLines' x). */
    @JvmStatic
    fun load(node: Element?, creditsTxt: String?, measure: (Int, Int, String) -> Float) {
        scrollSpeed = 1f; fadeInSpeed = -0.005f; scrollDelay = 250f; pauseDuration = 400f; pausePosition = 675f
        namesFont = 14; texts.clear(); finishTexts.clear(); names.clear(); fadeIn = 0f
        replaceBackground = ""; replaceText = ""; replaceMusic = ""
        node ?: return
        var namesSpacing = 40
        var toFinishSpacing = 100
        for (c in node.children) {
            when (c.name) {
                "scrollSpeed" -> scrollSpeed = f(c.getAttributeValue("speed"), scrollSpeed)
                "fadeInSpeed" -> fadeInSpeed = f(c.getAttributeValue("speed"), fadeInSpeed)
                "scrollPause" -> {
                    c.getAttributeValue("scrollDelay")?.trim()?.toFloatOrNull()?.let { scrollDelay = it * 50 }
                    c.getAttributeValue("duration")?.trim()?.toFloatOrNull()?.let { pauseDuration = it * 50 + scrollDelay }
                    pausePosition = f(c.getAttributeValue("pausePosition"), pausePosition)
                }
                "text", "finishText" -> {
                    val finish = c.name == "finishText"
                    val id = c.getAttributeValue("id") ?: "Error: 'id' attribute missing"
                    val font = i(c.getAttributeValue("fontSize"), 14)
                    val wrap = if (finish) 1000 else 750
                    val b = Block(id, font, i(c.getAttributeValue("spacing"), 40), i(c.getAttributeValue("horizontal"), 360),
                        i(c.getAttributeValue("lineLenght"), wrap), measure(font, wrap, id) + 50)
                    (if (finish) finishTexts else texts) += b
                }
                "creditNames" -> {
                    namesFont = i(c.getAttributeValue("fontSize"), namesFont)
                    namesSpacing = i(c.getAttributeValue("spacingBetweenNames"), namesSpacing)
                    toFinishSpacing = i(c.getAttributeValue("spacingToFinishTexts"), toFinishSpacing)
                }
            }
        }
        // credits.txt: every line (std::getline), padded with empty entries to a multiple of three
        val file = creditsTxt ?: ""
        val total = file.count { it == '\n' }
        var lines = file.split('\n')
        if (file.endsWith('\n')) lines = lines.dropLast(1)
        lines.forEachIndexed { n, raw ->
            val line = raw.trimEnd('\r')
            names += Name(line, if ((n + 1) % 3 == 0) namesSpacing else 0, measure(namesFont, 750, line) + 50)
        }
        val rem = total % 3
        repeat(if (rem == 0) 0 else 3 - rem) { names += Name("\n", 0, 0f) }
        if (names.isNotEmpty()) names[names.size - 1] = names.last().let { Name(it.names, toFinishSpacing, it.cutOff) }
    }

    private var loadedFor: Any? = null

    /** ParseCustomCredits for [content] (its fonts measure the cut-offs), once. */
    private fun ensureLoaded(content: InGameState.GameContent) {
        if (loadedFor === content) return
        val keep = Triple(replaceBackground, replaceText, replaceMusic)
        val fonts = HashMap<Int, NativeFonts.NativeFont>()
        val df = content.datafile
        val txt = df.getOrNull("data/credits.txt")?.let { df.readString(it) }
        load(HS.cfg().node("customCredits"), txt) { id, width, text ->
            val f = fonts.getOrPut(id) { NativeFonts.font(content::getFont, id) }
            text.split('\n').flatMap { if (it.isEmpty()) listOf("") else f.font.wrapString(it, width) }
                .maxOfOrNull { f.advance(it).toFloat() } ?: 0f
        }
        replaceBackground = keep.first; replaceText = keep.second; replaceMusic = keep.third
        loadedFor = content
    }

    /** The victory event's `<win>` (CustomEvents.cpp's CreateLocation part for gameOver.victory). */
    @JvmStatic
    fun victoryEvent(game: InGameState, creditsText: String, creditsBackground: String, music: String) {
        replaceText = creditsText
        replaceBackground = imageFromList(game.eventManager, creditsBackground)
        replaceMusic = music
    }

    /** EventGenerator::GetImageFromList: an image of the list, drawn from the global stream; "" for no list. */
    private fun imageFromList(events: xyz.znix.xftl.sector.EventManager, list: String): String {
        if (list.isEmpty() || !events.hasImageList(list)) return ""
        return events.getImageList(list).getRandom(ftl.browser.NativeRandom.next31())?.path ?: ""
    }

    /** The title playlist GameOver::OnLoop starts (Credits.cpp): the victory's music, else "title". */
    @JvmStatic
    fun creditsMusic(): String = if (HS.active && replaceMusic.isNotEmpty()) replaceMusic else "title"

    /** A new run (StarMap::NewGame) clears the replacements. */
    @JvmStatic
    fun newGame() {
        replaceBackground = ""; replaceMusic = ""
    }

    @JvmStatic
    fun registerSave() {
        xyz.znix.xftl.hs.save.HsSave.register("credits", xyz.znix.xftl.hs.save.HsSave.Scope.RUN, { e ->
            e.setAttribute("background", replaceBackground).setAttribute("music", replaceMusic)
        }, { e ->
            replaceBackground = e?.getAttributeValue("background") ?: ""
            replaceMusic = e?.getAttributeValue("music") ?: ""
        })
    }

    /** One roll: CreditScreen::Start for the menu ([shipName] empty) or a victory. */
    class Roll(
        private val content: InGameState.GameContent, private val shipName: String, crewNames: List<String>,
        defaultVictoryText: String, defaultVictoryBackground: String
    ) {
        private val fonts = HashMap<Int, NativeFonts.NativeFont>()
        private fun font(id: Int) = fonts.getOrPut(id) { NativeFonts.font(content::getFont, id) }

        var scroll: Float
            private set
        private var pausing: Float
        private val scrollEnd: Float
        private val crewString: String
        private val crewCutOff: Float
        private val bg: Image?
        private val tr = content.translator

        init {
            ensureLoaded(content)
            if (replaceBackground.isEmpty() && defaultVictoryBackground.isNotEmpty())
                replaceBackground = imageFromList(content.eventManager, defaultVictoryBackground)
            if (replaceText.isEmpty() && defaultVictoryText.isNotEmpty()) replaceText = defaultVictoryText
            if (shipName.isNotEmpty()) fadeIn = 1f
            scrollEnd = 750f + texts.sumOf { it.spacing } + names.sumOf { it.spacing } + finishTexts.sumOf { it.spacing }
            // the original's Start: -70 and no wait for the menu, 0 and a 5 unit wait after a victory
            scroll = if (shipName.isEmpty()) -70f else 0f
            pausing = if (shipName.isEmpty()) 0f else 5f
            bg = (if (replaceBackground.isEmpty()) "img/stars/bg_darknebula.png" else replaceBackground).let {
                if (content.datafile.getOrNull(it) != null) content.getImg(it) else null
            }
            // The original's crew list (names joined, the last two with crewlist_and), then HS's patch for 2+
            val comma = tr.untrimmed("crewlist_comma")
            val and = tr.untrimmed("crewlist_and")
            var s = when (crewNames.size) { 0 -> ""; 1 -> crewNames[0]; else -> "" }
            if (crewNames.size > 1) {
                val more = tr.untrimmed("crewlist_more")
                val sb = StringBuilder()
                var total = 0
                for (n in crewNames) {
                    sb.append(comma).append(n)
                    total += n.length + comma.length
                    if (total > 140) { sb.append(more); break }
                }
                s = sb.toString().substring(minOf(comma.length, sb.length))
                if (!s.contains(more)) {
                    val at = s.lastIndexOf(comma)
                    if (at >= 0) s = s.substring(0, at) + and + s.substring(minOf(s.length, at + 2))
                }
            }
            crewString = s
            crewCutOff = font(18).let { f -> wrap(f, 720, s).maxOfOrNull { f.advance(it).toFloat() } ?: 0f } + 50
        }

        /** CreditScreen::Done (alt): the roll has passed the end. */
        val isDone: Boolean get() = scroll <= -scrollEnd

        private fun wrap(f: NativeFonts.NativeFont, width: Int, text: String): List<String> =
            text.split('\n').flatMap { if (it.isEmpty()) listOf("") else f.font.wrapString(it, width) }

        /** The scroll and fade of OnRender, for [dt] seconds (speed factor 16 per second). */
        fun advance(dt: Float) {
            val speed = dt * 16f
            val trueScroll = speed * 3.5f * scrollSpeed
            fadeIn += speed * 3.5f * -0.025f * fadeInSpeed
            if (scroll == 0f && pausing > -scrollDelay) pausing -= trueScroll
            else if (maxOf(scroll, -pausePosition) == -pausePosition && pausing > -pauseDuration) pausing -= trueScroll
            else scroll -= trueScroll
        }

        /** freetype::easy_printNewlinesCentered: each wrapped line centred on [x]. */
        private fun print(fontId: Int, x: Int, y: Int, width: Int, text: String) {
            val f = font(fontId)
            f.printCenter(x.toFloat(), y.toFloat(), wrap(f, width, text).joinToString("\n"), Colour.white)
        }

        fun render(g: Graphics) {
            val gray = Colour(.5f, .5f, .5f, 1f)
            bg?.draw(0f, 0f, gray)
            if (shipName.isNotEmpty()) {
                var y = (144 + scroll).toInt()
                if (y > -100 && y < 800) print(14, 640, y, 1200, tr["thank_ship"])
                y += 40
                if (y > -100 && y < 800) print(20, 640, y, 1200, shipName)
                if (crewString.isNotEmpty()) {
                    y += 80
                    if (y > -720 && y < 800) print(14, 640, y, 1200, tr["thank_crew"])
                    y += 40
                    if (y > -crewCutOff && y < 800) print(18, 640, y, 720, crewString)
                } else {
                    y += 80
                    if (y > -100 && y < 800) print(14, 640, y, 1200, tr["thank_ai"])
                }
                y += 246
                // TextLibrary::GetText hook: credit_victory is the victory's creditsText while the credits render
                if (y > -100 && y < 800) print(14, 640, y, 750, tr[replaceText.ifEmpty { "credit_victory" }])
            }
            var h = 740f
            for (t in texts) {
                if (h + scroll > -t.cutOff && h + scroll < 800) print(t.font, t.horizontal, (h + scroll).toInt(), t.lineLength, tr[t.text])
                h += t.spacing
            }
            var n = 0
            for (name in names) {
                val x = when (n) { 0 -> 256; 1 -> 640; else -> 1024 }
                if (h + scroll > -name.cutOff && h + scroll < 800) print(namesFont, x, (h + scroll).toInt(), 1280, name.names)
                n++
                if (n == 3) { n = 0; h += name.spacing }
            }
            for (t in finishTexts) {
                if (h + scroll > -t.cutOff && h + scroll < 800) print(t.font, t.horizontal, (h + scroll).toInt(), t.lineLength, tr[t.text])
                h += t.spacing
            }
            if (fadeIn > 0) bg?.draw(0f, 0f, Colour(.5f, .5f, .5f, minOf(1f, fadeIn)))
            // Done(): the replacements end with the roll
            if (isDone) { replaceBackground = ""; replaceText = "" }
        }
    }

    @JvmStatic
    fun capabilityNames() = arrayOf("customCredits", "altCreditSystem")
}
