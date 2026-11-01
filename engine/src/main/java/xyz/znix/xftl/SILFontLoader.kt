package xyz.znix.xftl

import org.lwjgl.opengl.GL11
import org.newdawn.slick.opengl.ImageData
import xyz.znix.xftl.hs.render.HsTextStyle
import xyz.znix.xftl.rendering.BulkImageRenderer
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.GlyphAtlas
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.Image
import xyz.znix.xftl.rendering.LanguageFonts
import xyz.znix.xftl.rendering.TextureLoader
import xyz.znix.xftl.sys.ResourceContext
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class SILFontLoader {
    private val chars: MutableMap<Char, Charinfo>
    private val unknownChar: Charinfo

    /** The 256-character blocks (code >> 8) a font file of the game has glyphs in ([ZERO_GLYPH]). */
    private val pages: Set<Int>
    private val picture: Image
    private val renderer: BulkImageRenderer

    /** The glyphs of a language font, loaded as strings use them (null for a font file of the game). */
    private val source: GlyphSource?

    /** The file's name (fonts/<name>.font), by which a language's font replaces this one ([LanguageFonts]). */
    var baseName: String = ""

    /** The native font ID this copy stands for, when known (NativeFonts), else the one its name and scale map to. */
    var nativeId: Int = -1

    /** Where a language font's baseline sits below the replaced font's (applied when drawing). */
    var baselineShift: Float = 0f

    /** [LanguageFonts.substitute]'s answer for this copy, valid while [substituteEpoch] matches its epoch. */
    @JvmField internal var substituteCache: SILFontLoader? = null
    @JvmField internal var substituteEpoch: Int = -1
    @JvmField internal var substituteScale: Float = 0f

    private val fileLineSpacing: Int

    /** The file's line spacing, whatever the language. */
    val fileLineSpacingValue: Int get() = fileLineSpacing

    /**
     * The vertical spacing between any two lines of text.
     */
    val lineSpacing: Int get() = sub()?.let { LanguageFonts.lineSpacing(this, it) } ?: fileLineSpacing

    /**
     * How far the visual top of the line is above the baseline.
     *
     * This is set in font-data-override.xml
     */
    val baselineToTop: Int

    /**
     * An offset set in font-data-override.xml for HL2, that moves the baseline
     * down with a positive value.
     */
    val trueBaselineOffset: Int

    var scale: Float = 1f

    constructor(context: ResourceContext, df: Datafile, file: FTLFile) : this(
        context,
        FontOverrideData.fonts[file.name],
        df.read(file)
    ) {
        baseName = file.name.removePrefix("fonts/").removeSuffix(".font")
    }

    constructor(context: ResourceContext, override: FontOverrideData.FontInfo?, data: ByteArray) {
        val seeking = SeekingInputStream(data)
        val bytes = DataInputStream(seeking)

        check(ByteArray(4).also { bytes.readFully(it) }.contentEquals("FONT".toByteArray()))

        val version = bytes.read()
        fileLineSpacing = bytes.read()
        val fileBaseline = bytes.read()
        baselineToTop = override?.lineTop ?: fileBaseline
        trueBaselineOffset = override?.baselineOffset ?: 0

        // Pad
        bytes.read()

        val charinfoOffset = bytes.readInt()

        val charinfoCount = bytes.readUnsignedShort()
        val charinfoSize = bytes.readUnsignedShort()

        chars = HashMap(charinfoCount)

        val textureOffset = bytes.readInt()
        @Suppress("UNUSED_VARIABLE") val textureSize = bytes.readInt()

        check(version == 1)

        seeking.position = charinfoOffset

        for (i in 1..charinfoCount) {
            val ch = bytes.readInt()

            val x = bytes.readShort().toInt()
            val y = bytes.readShort().toInt()

            val w = bytes.readUnsignedByte()
            val h = bytes.readUnsignedByte()

            val accent = bytes.readByte().toInt()

            // Pad
            bytes.read()

            val prekern = bytes.readShort().toInt()
            val postkern = bytes.readShort().toInt()

            chars[ch.toChar()] = Charinfo(ch.toChar(), x, y, w, h, accent, prekern / 256f, postkern / 256f)
        }

        check(seeking.position == charinfoOffset + charinfoSize * charinfoCount)

        // Read the texture

        seeking.position = textureOffset
        val magic = ByteArray(4).also { bytes.readFully(it) }
        check(magic.contentEquals("TEX\u000a".toByteArray()))

        val texVersion = bytes.read()
        check(texVersion == 2)

        val format = bytes.read()
        check(format == 64)

        val mipmaps = bytes.read()
        check(mipmaps == 0)

        val opaqueBitmap = bytes.read()
        check(opaqueBitmap == 0)

        val texWidth = bytes.readUnsignedShort()
        val texHeight = bytes.readUnsignedShort()

        // Throw away four bytes
        bytes.readInt()

        val pixelOffsets = bytes.readInt()
        val pixelSize = bytes.readInt()

        @Suppress("UNUSED_VARIABLE") val bitmapOffset = bytes.readInt()
        @Suppress("UNUSED_VARIABLE") val bitmapSize = bytes.readInt()

        seeking.position = pixelOffsets + textureOffset

        val data = ByteArray(pixelSize)
        bytes.read(data)

        val img = MonochromeImage(texWidth, texHeight, data)

        picture = TextureLoader.loadImage(context, img)
        renderer = BulkImageRenderer()

        // Enlarged fonts are supposed to be pixelated.
        renderer.imageFiltering = GL11.GL_NEAREST

        // The bitmap font layout and its measures skip a character the font lacks: it takes no room and
        // draws nothing, in English as in every language ([ZERO_GLYPH] for one of a block the font has).
        unknownChar = Charinfo('\u0000', 0, 0, 0, 0, 0, 0f, 0f)
        pages = chars.keys.mapTo(HashSet()) { it.code shr 8 }
        source = null
    }

    /** A language font: glyphs come from [source] (a Japanese bitmap font or a TrueType font) as they are drawn. */
    constructor(source: GlyphSource) {
        this.source = source
        chars = HashMap()
        // The per-character advance and the layouts skip a character the font lacks (no advance, nothing drawn).
        unknownChar = Charinfo('\u0000', 0, 0, 0, 0, 0, 0f, 0f)
        pages = emptySet()
        if (source.atlas.pages.isEmpty()) source.glyph(' ')
        picture = source.atlas.pages.first()
        fileLineSpacing = source.lineSpacing
        baselineToTop = source.baseline
        trueBaselineOffset = 0
        renderer = BulkImageRenderer()
        renderer.imageFiltering = GL11.GL_NEAREST
    }

    /**
     * Create a new [SILFontLoader] representing the same font as the supplied instance. This shares
     * the image and character data (thus making clones like this is cheap), but the scale (and any other
     * later adjustable properties) are kept separate.
     */
    constructor(other: SILFontLoader) {
        chars = other.chars
        unknownChar = other.unknownChar
        pages = other.pages
        picture = other.picture
        fileLineSpacing = other.fileLineSpacing
        baselineToTop = other.baselineToTop
        trueBaselineOffset = other.trueBaselineOffset
        renderer = other.renderer
        source = other.source
        baseName = other.baseName
    }

    /** Set on a copy that must measure and draw this font's own glyphs whatever the language. */
    @JvmField var ownGlyphs: Boolean = false

    /** The current language's replacement for this font, if it has one ([LanguageFonts]). */
    private fun sub(): SILFontLoader? = if (source != null || ownGlyphs) null else LanguageFonts.substitute(this)

    /** A glyph, loading a language font's glyph on first use. */
    private fun glyph(ch: Char): Charinfo {
        chars[ch]?.let { return it }
        val src = source ?: return if ((ch.code shr 8) in pages) ZERO_GLYPH else unknownChar
        val g = src.glyph(ch) ?: unknownChar
        chars[ch] = g
        return g
    }

    /** Whether this is the font of a language (glyphs loaded as used) rather than a font file of the game. */
    val isLanguageFont: Boolean get() = source != null

    /**
     * A language font's advance for one character: the bitmap font's width and kerning scaled and
     * rounded, the TrueType font's rounded advance; nothing for a character it lacks.
     */
    fun nativeAdvance(ch: Char): Float {
        val info = glyph(ch)
        if (info === unknownChar) return 0f
        return Math.round((info.w + info.prekern + info.postkern) * scale).toFloat()
    }

    fun drawString(x: Float, y: Float, text: String, col: Colour) {
        drawStringTruncated(x, y, Float.MAX_VALUE, text, col)
    }

    /** Native print coordinates specify the top; this renderer takes a baseline. */
    fun drawStringFromTop(x: Float, top: Float, text: String, col: Colour) {
        drawString(x, top + (baselineToTop + trueBaselineOffset) * scale, text, col)
    }

    /** Baseline for centring a single line by its glyph bounds, including descenders. */
    fun centredBaseline(top: Float, height: Float, text: String): Float {
        sub()?.let { return it.centredBaseline(top, height, text) - it.baselineShift }
        var ascent = 0
        var descent = 0
        for (ch in text) {
            val info = glyph(ch)
            ascent = max(ascent, info.ascent)
            descent = max(descent, info.h - info.ascent)
        }
        return top + height / 2f + (ascent - descent) * scale / 2f
    }

    fun drawStringTruncated(x: Float, y: Float, width: Float, text: String, col: Colour) {
        sub()?.let { return it.drawStringTruncated(x, y + it.baselineShift, width, text, col) }
        if (HsTextStyle.hasFlag(text)) {
            // customTextStyle: each run in its colour, the next starting where the pen ended.
            var pen = x
            for (run in HsTextStyle.parse(text)) {
                val runWidth = width - (pen - x)
                if (runWidth <= 0) break
                drawStringTruncated(pen, y, runWidth, run.text, run.colour ?: col)
                pen += penAdvance(run.text)
            }
            return
        }
        if (source != null) {
            drawLanguageString(source, x, y, width, text, col)
            return
        }
        var next = x
        var page = -1
        for (ch in text) {
            val info = glyph(ch)
            if (info === unknownChar || info === ZERO_GLYPH) continue
            if (info.page != page && page != -1) flushPage(page)
            page = info.page

            val cy = y + (scale * -info.ascent).roundToInt()
            next += (info.prekern * scale).roundToInt()
            val cx = next.roundToInt()

            // Check if this character is going to overflow the allowed area
            val charWidth = min((width + x - cx) / scale, info.w.f)

            renderer.pushImage(
                cx.f, cy,
                cx.f + charWidth * scale, cy + info.h * scale,
                info.x.f, info.y.f,
                info.x + charWidth, (info.y + info.h).f, col
            )
            next += ((info.w + info.postkern) * scale).roundToInt()

            if (next - x > width) {
                if (source != null) flushPage(page)
                return
            }
        }

        flushPage(maxOf(page, 0))
    }

    /**
     * A language font's line: the bitmap font layout rounds each glyph's pen plus its scaled kerning
     * (roundf) and moves on by the scaled width and post-kerning from there, unrounded; glyphs hang from the baseline
     * by their scaled ascent, unrounded, and a character the font lacks takes no room. The TrueType layout
     * is the same with whole-pixel advances; its outlined fonts draw whole strings ([GlyphSource.drawString]).
     */
    private fun drawLanguageString(source: GlyphSource, x: Float, y: Float, width: Float, text: String, col: Colour) {
        if (scale == 1f && source.drawString(x, y, width, text, col)) return
        var pen = x
        var page = -1
        var prev = 0.toChar()
        for (ch in text) {
            val info = glyph(ch)
            if (info === unknownChar) continue
            pen += source.kerning(prev, ch) * scale
            prev = ch
            if (info === ZERO_GLYPH) {
                pen = nativeRound(pen)
                continue
            }
            if (info.page != page && page != -1) flushPage(page)
            page = info.page
            val cx = nativeRound(pen + info.prekern * scale)
            val cy = y - info.ascent * scale
            val charWidth = min((width + x - cx) / scale, info.w.f)
            if (charWidth > 0f) {
                renderer.pushImage(
                    cx, cy,
                    cx + charWidth * scale, cy + info.h * scale,
                    info.x.f, info.y.f,
                    info.x + charWidth, (info.y + info.h).f, col
                )
            }
            pen = cx + (info.w + info.postkern) * scale
            if (pen - x > width) break
        }
        if (page != -1) flushPage(page)
    }

    /** The bitmap and TrueType layouts' extents of one line: the leftmost and rightmost pixel. */
    private fun languageExtents(line: String): FloatArray {
        var pen = 0f
        var left = 0f
        var right = 0f
        var prev = 0.toChar()
        for (ch in line) {
            val info = glyph(ch)
            if (info === unknownChar) continue
            pen += (source?.kerning(prev, ch) ?: 0f) * scale
            prev = ch
            pen += info.prekern * scale
            val edge = nativeRound(info.w * scale + pen)
            pen = nativeRound(pen)
            left = minOf(left, pen)
            right = maxOf(right, edge)
            pen += (info.w + info.postkern) * scale
        }
        return floatArrayOf(left, right)
    }

    /** The bitmap and TrueType layouts' string width: where the pen ends, rounded. */
    private fun languageAdvance(line: String): Float {
        var pen = 0f
        var prev = 0.toChar()
        for (ch in line) {
            val info = glyph(ch)
            if (info === unknownChar) continue
            pen += (source?.kerning(prev, ch) ?: 0f) * scale
            prev = ch
            pen = nativeRound(pen + info.prekern * scale)
            pen += (info.w + info.postkern) * scale
        }
        return nativeRound(pen)
    }

    private fun nativeRound(v: Float): Float = if (v >= 0f) Math.floor(v + 0.5).toFloat() else -Math.floor(-v + 0.5).toFloat()

    private fun flushPage(page: Int) {
        renderer.imageFiltering = Graphics.currentImageFiltering()
        renderer.flush(if (source == null) picture else source.atlas.pages[page])
    }

    /**
     * Draw a string, with characters from another font placed at the same position.
     *
     * This is specifically intended for the HL2 font, with the HL1 font being
     * passed in. This is used for the damage/miss/resist text, which has its
     * background filled in.
     */
    fun drawStringPaired(bgFont: SILFontLoader, x: Float, y: Float, text: String, fg: Colour, bg: Colour) {
        if (sub() != null || bgFont.sub() != null) {
            // A language's font has no glyph-for-glyph background twin: draw the background font's string first,
            // offset as the original offsets HL1 under HL2, then the text over it.
            bgFont.drawString(x + 1 * scale, y + 1 * scale, text, bg)
            drawString(x, y, text, fg)
            return
        }
        // If we don't round off the Y, then at values about half way between
        // integers we can end up drawing the background a pixel below
        // the foreground.
        val roundedY = y.roundToInt()

        var next = x
        for (ch in text) {
            val info = glyph(ch)
            if (info === unknownChar || info === ZERO_GLYPH) continue

            val cy = roundedY + (scale * -info.ascent).roundToInt()
            next += (info.prekern * scale).roundToInt()
            val cx = next.roundToInt()

            // Push the image for the main font
            renderer.pushImage(
                cx.f, cy.f,
                cx.f + info.w * scale, cy + info.h * scale,
                info.x.f, info.y.f,
                info.x.f + info.w, (info.y + info.h).f, fg
            )

            // Push the image for the background font
            val bgInfo = bgFont.glyph(ch)
            val bgCX = cx + 1 * scale // Shift 1px for hl1/hl2 alignment
            val bgCY = cy + 1 * scale
            bgFont.renderer.pushImage(
                bgCX, bgCY,
                bgCX + bgInfo.w * scale, bgCY + bgInfo.h * scale,
                bgInfo.x.f, bgInfo.y.f,
                bgInfo.x.f + bgInfo.w, (bgInfo.y + bgInfo.h).f, bg
            )

            next += ((info.w + info.postkern) * scale).roundToInt()
        }

        bgFont.renderer.imageFiltering = Graphics.currentImageFiltering()
        bgFont.renderer.flush(bgFont.picture)
        renderer.imageFiltering = Graphics.currentImageFiltering()
        renderer.flush(picture)
    }

    fun drawStringCentred(x: Float, y: Float, width: Float, text: String, col: Colour) {
        val textX = x + (width - getWidth(text)) / 2
        drawString(textX, y, text, col)
    }

    /**
     * Split a string into multiple lines, and draw it centred around a given Y value ([centreY]).
     *
     * [centreY] is the Y that, if the string is a single line, it will be drawn at.
     */
    fun drawStringCentredVertical(x: Int, centreY: Int, width: Int, spacing: Int, text: String, col: Colour) {
        val lines = wrapString(text, width)

        var y = centreY - (lines.size - 1) * spacing / 2

        for (line in lines) {
            drawStringCentred(x.f, y.f, width.f, line, col)
            y += spacing
        }
    }

    fun drawStringLeftAligned(x: Float, y: Float, text: String, colour: Colour) {
        drawString(x - getWidth(text), y, text, colour)
    }

    /** Where the pen ends after [drawStringTruncated] draws [str] on one line. */
    private fun penAdvance(str: String): Int {
        if (source != null) return languageAdvance(str).toInt()
        var next = 0
        for (ch in str) {
            val info = glyph(ch)
            next += (info.prekern * scale).roundToInt()
            next += ((info.w + info.postkern) * scale).roundToInt()
        }
        return next
    }

    fun getWidth(str: String): Int {
        sub()?.let { return it.getWidth(str) }
        if (HsTextStyle.hasFlag(str)) return getWidth(HsTextStyle.strip(str))
        // A language font: what centring takes off (the layout starts at x - (left + right) / 2).
        if (source != null) return str.split('\n').maxOf { languageExtents(it).let { e -> (e[0] + e[1]).toInt() } }
        var maxLine = 0
        var current = 0
        var next = 0
        for (ch in str) {
            // If we hit a newline, keep track of this line if it's
            // the longest one we've found. Then reset the width
            // for the next line's length.
            if (ch == '\n') {
                maxLine = max(maxLine, current)
                current = 0
                next = 0
                continue
            }

            val info = glyph(ch)
            if (info === unknownChar) continue
            next += (info.prekern * scale).roundToInt()

            current = next + (info.w * scale).roundToInt()
            next += ((info.w + info.postkern) * scale).roundToInt()
        }
        return max(current, maxLine)
    }

    /**
     * One line's width including the last glyph's trailing advance: the font's string width (vtable, bitmap
     * font layout), which easy_printRightAlign's layout takes off the right edge.
     */
    fun getAdvanceWidth(str: String): Int = getAdvance(str).roundToInt()

    /**
     * Where the pen ends after drawing one line, rounded as the bitmap layout rounds it: each glyph's pen plus its
     * scaled prekern is rounded (roundf), its width and postkern added unrounded, the end rounded.
     */
    fun getAdvance(str: String): Float {
        sub()?.let { return it.getAdvance(str) }
        if (HsTextStyle.hasFlag(str)) return getAdvance(HsTextStyle.strip(str))
        return languageAdvance(str)
    }

    /**
     * freetype::measurePrintLines (0x00595dc0) through the bitmap font's extents
     * (0x00648930): per glyph the pen moves by its prekern, its right edge is the pen
     * plus its width, both rounded, and the pen then advances by width and postkern
     * from the rounded position; a line measures right edge less left edge. The
     * widest line wins.
     */
    fun getExtentWidth(str: String): Int {
        sub()?.let { return it.getExtentWidth(str) }
        if (HsTextStyle.hasFlag(str)) return getExtentWidth(HsTextStyle.strip(str))
        if (source != null) return str.split('\n').maxOf { languageExtents(it).let { e -> (e[1] - e[0]).toInt() } }
        var widest = 0f
        for (line in str.split('\n')) {
            var pen = 0f
            var left = 0f
            var right = 0f
            for (ch in line) {
                val info = glyph(ch)
                if (info === unknownChar) continue
                pen += info.prekern * scale
                val edge = Math.rint((info.w * scale + pen).toDouble()).toFloat()
                pen = Math.rint(pen.toDouble()).toFloat()
                left = minOf(left, pen)
                right = maxOf(right, edge)
                pen += (info.w + info.postkern) * scale
            }
            widest = maxOf(widest, right - left)
        }
        return Math.rint(widest.toDouble()).toInt()
    }

    fun supportsCharacter(c: Char): Boolean {
        sub()?.let { return it.supportsCharacter(c) }
        if (source != null) return glyph(c).let { it !== unknownChar && it !== ZERO_GLYPH }
        return chars.containsKey(c)
    }

    fun wrapString(string: String, maxWidth: Int): List<String> {
        sub()?.let { return it.wrapString(string, maxWidth) }
        if (HsTextStyle.hasFlag(string)) return HsTextStyle.wrap(this, string, maxWidth)
        return wrapPlain(string, maxWidth)
    }

    /**
     * [wrapString] for text without customTextStyle markup: freetype::formatLines with the font's advances,
     * by the current language's rules, for the game's fonts as for a language's (a word wider than the line is broken
     * where it no longer fits, tabs are no break).
     */
    fun wrapPlain(string: String, maxWidth: Int): List<String> {
        sub()?.let { return it.wrapPlain(string, maxWidth) }
        return LanguageFonts.formatLines(this, string, maxWidth)
    }

    @Suppress("unused")
    class Charinfo(
        val ch: Char, val x: Int, val y: Int, val w: Int, val h: Int, val ascent: Int,
        val prekern: Float, val postkern: Float, val page: Int = 0
    )

    companion object {
        /**
         * A bitmap font's glyph for a character it lacks in a block of 256 it has glyphs in: the font's page table
         * (pages[code >> 8][code & 0xff]) holds a zeroed entry there, so the character draws nothing and
         * advances nothing, but the layouts round the pen at it and the extents reach it. A character of a block the
         * font has no glyph in is skipped.
         */
        @JvmField
        val ZERO_GLYPH = Charinfo('\u0000', 0, 0, 0, 0, 0, 0f, 0f)
    }

    /** Glyphs of a language font, copied into [atlas] as they are first drawn. */
    interface GlyphSource {
        val atlas: GlyphAtlas
        /** The font's line spacing and baseline at its own size (the file's, or the TrueType font's at its size). */
        val lineSpacing: Int
        val baseline: Int
        fun glyph(ch: Char): Charinfo?

        /**
         * Draws [text] as one image with its baseline at [y] when the font composes whole strings (the outlined
         * TrueType fonts, whose glyphs take each other's outline away), else returns false.
         */
        fun drawString(x: Float, y: Float, width: Float, text: String, col: Colour): Boolean = false

        /** The pair kerning the layouts add between two characters, in pixels (TrueType fonts with a kern table). */
        fun kerning(prev: Char, ch: Char): Float = 0f
    }

    private class SeekingInputStream(ba: ByteArray) : ByteArrayInputStream(ba) {
        var position: Int
            get() = pos
            set(value) {
                pos = value
            }
    }

    private class MonochromeImage(val w: Int, val h: Int, load: ByteArray) : ImageData {
        val data: ByteBuffer = ByteBuffer.allocateDirect(load.size * 4)

        init {
            // Image is stored as RGBA, and our font should be 0xfff with the alpha loaded
            // from the file. This way we can easily control the colour of the rendered
            // text by applying a filter.
            for (element in load) {
                // Java uses two's complement, so -1 is 0xff
                for (j in 1..3)
                    data.put(-1)

                // Copy over the alpha
                data.put(element)
            }

            data.position(0)
        }

        override fun getHeight(): Int = h

        override fun getTexWidth(): Int = w

        override fun getDepth(): Int = 8 * 4

        override fun getImageBufferData(): ByteBuffer = data

        override fun getWidth(): Int = w

        override fun getTexHeight(): Int = h
    }
}
