package xyz.znix.xftl.rendering

import xyz.znix.xftl.Datafile
import xyz.znix.xftl.Languages
import xyz.znix.xftl.SILFontLoader
import xyz.znix.xftl.sys.ResourceContext
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import kotlin.math.roundToInt

/**
 * The fonts a language draws with. ResourceControl::GetFontData hands back, for any font ID but 0
 * (num_font), the current language's font when it has its own: Japanese the bitmap fonts of `fonts/ja/`,
 * Simplified Chinese `fonts/zh-Hans/black.ttf` through FreeType (outlined for IDs 62-64),
 * and under Hyperspace a `<language fontOverride>` TrueType font (CustomLocalization.cpp). Every other language
 * draws with the English fonts, which carry the accented Latin and Cyrillic letters.
 *
 * A [SILFontLoader] of a game font asks [substitute] on every draw and measure; while the language has no fonts of
 * its own that is one null check, so English output is untouched.
 */
object LanguageFonts {
    private var epoch = 0
    private var set: FontSet? = null
    private val context = ResourceContext()
    private val loadedSets = HashMap<String, FontSet>()
    private var datafile: Datafile? = null

    /** A font file of the language (bitmap or TrueType), loaded the first time it is used. */
    private val faces = HashMap<String, Any>()

    /** Hyperspace `<language langcode fontOverride>`: the TrueType file a language's fonts come from. */
    val hsOverrides = LinkedHashMap<String, String>()

    /**
     * The datafile the language fonts are read from (the game content's), and Hyperspace's `<languages>`
     * (CustomLocalization.cpp ParseLanguagesNode): each `<language langcode>` with a non-empty code is added to the
     * language list once, its non-empty `fontOverride` (the first one given for the code) becomes the code's font.
     */
    fun useDatafile(df: Datafile) {
        val overrides = LinkedHashMap<String, String>()
        Languages.resetList()
        if (xyz.znix.xftl.hs.HS.active) {
            xyz.znix.xftl.hs.HsCapability.implement("languages", "language", "language@langcode", "language@fontOverride")
            for (node in xyz.znix.xftl.hs.HS.cfg().nodesOf("languages")) for (child in node.children) {
                if (child.name != "language") continue
                val code = child.getAttributeValue("langcode")?.takeIf { it.isNotEmpty() } ?: continue
                child.getAttributeValue("fontOverride")?.takeIf { it.isNotEmpty() }?.let { overrides.putIfAbsent(code, it) }
                Languages.add(code)
            }
        }
        if (datafile !== df || overrides != hsOverrides) {
            datafile = df
            hsOverrides.clear()
            hsOverrides.putAll(overrides)
            loadedSets.clear()
            faces.clear()
        }
        select(Languages.current)
    }

    /** Picks the font set of [lang] (Languages.current changes call this). */
    fun select(lang: String) {
        epoch++
        val df = datafile
        set = if (df == null) null else loadedSets.getOrPut(lang) { FontSet.forLanguage(lang, df) ?: FontSet.NONE }.takeIf { it !== FontSet.NONE }
    }

    /** Whether the current language draws with fonts of its own. */
    val active: Boolean get() = set != null

    fun substitute(font: SILFontLoader): SILFontLoader? {
        val s = set ?: return null
        if (font.substituteEpoch == epoch && font.substituteScale == font.scale) return font.substituteCache
        val r = s.substitute(font)
        font.substituteCache = r
        font.substituteEpoch = epoch
        font.substituteScale = font.scale
        return r
    }

    /** The replaced font's line spacing (in its own units), moved by the language font's line height. */
    fun lineSpacing(orig: SILFontLoader, sub: SILFontLoader): Int {
        val (id, factor) = nativeId(orig) ?: return sub.lineSpacing
        val c = set?.choice(id) ?: return sub.lineSpacing
        val en = NativeFonts.baseSpec(id)
        val base = orig.fileLineSpacingValue * orig.scale
        return ((base + (c.lineHeight - en.lineHeight) * factor) / orig.scale).roundToInt()
    }

    /**
     * The current language's line height for a native font ID (font_data::lineHeight), or null for the English one:
     * num_font (ID 0) stays English in every language, as GetFontData leaves it.
     */
    fun lineHeight(id: Int): Int? = choice(id)?.lineHeight

    /** The current language's baseline for a native font ID (font_data::baseline), or null for the English one. */
    fun baseline(id: Int): Int? = choice(id)?.baseline

    private fun choice(id: Int): Choice? = NativeFonts.resolve(id).let { if (it == 0) null else set?.choice(it) }

    /** [lineHeight] in language [lang] rather than the current one (null: the English line height). */
    fun lineHeightIn(lang: String, id: Int): Int? {
        val df = datafile ?: return null
        val s = loadedSets.getOrPut(lang) { FontSet.forLanguage(lang, df) ?: FontSet.NONE }.takeIf { it !== FontSet.NONE }
        return NativeFonts.resolve(id).let { if (it == 0) null else s?.choice(it)?.lineHeight }
    }

    /**
     * The font_data a native ID resolves to in the current language (for Hyperspace's GetFontData), with [english]
     * giving the game's font files. An English font_data's size is its file's line spacing times the ID's scale
     * (ResourceControl's font loader passes those to init_bitmap).
     */
    fun fontData(id: Int, ignoreLanguage: Boolean, english: (String) -> SILFontLoader): FontData {
        val resolved = NativeFonts.resolve(id)
        val c = if (ignoreLanguage || resolved == 0) null else set?.choice(resolved)
        if (c != null) return FontData(resolved, c.fontSize, c.baseline.toFloat(), c.lineHeight.toFloat())
        val spec = NativeFonts.baseSpec(resolved)
        val natural = english(spec.name).fileLineSpacingValue.toFloat()
        return FontData(resolved, natural * spec.scale, spec.baseline.toFloat(), spec.lineHeight.toFloat())
    }

    /** A font_data (h, font, fontsize, baseline, lineHeight) as GetFontData returns it. */
    class FontData(val id: Int, val fontSize: Float, val baseline: Float, val lineHeight: Float)

    /** The native ID a font copy stands for, and the scale it is drawn at relative to that ID's font. */
    private fun nativeId(font: SILFontLoader): Pair<Int, Float>? {
        if (font.nativeId >= 0) return Pair(NativeFonts.resolve(font.nativeId), 1f)
        val exact = BY_NAME_SCALE[font.baseName + "@" + font.scale]
        if (exact != null) return Pair(exact, 1f)
        val (id, idScale) = BY_NAME[font.baseName] ?: return null
        return Pair(id, font.scale / idScale)
    }

    /** NativeFonts' table read backwards: the ID that draws a font file at a scale. */
    private val BY_NAME_SCALE = mapOf(
        "c&c@1.0" to 5, "JustinFont8@1.0" to 9, "JustinFont10@1.0" to 10, "JustinFont11Bold@1.0" to 12,
        "JustinFont12Bold@1.0" to 13, "c&cnew@2.0" to 14, "JustinFont11Bold@2.0" to 18, "JustinFont11Bold@3.0" to 20,
        "c&cnew@3.0" to 24, "HL1@1.0" to 51, "HL1@2.0" to 52, "HL1@3.0" to 53, "HL2@1.0" to 61, "HL2@2.0" to 62,
        "HL2@3.0" to 63, "HL2@4.0" to 64,
    )
    private val BY_NAME = mapOf(
        "c&c" to Pair(5, 1f), "JustinFont8" to Pair(9, 1f), "JustinFont10" to Pair(10, 1f),
        "JustinFont11Bold" to Pair(12, 1f), "JustinFont12Bold" to Pair(13, 1f), "c&cnew" to Pair(14, 2f),
        "HL1" to Pair(51, 1f), "HL2" to Pair(61, 1f),
    )

    /** A language font for one native ID: the font, its size, and the font_data metrics GetFontData gives it. */
    class Choice(val font: SILFontLoader, val fontSize: Float, val drawScale: Float, val baseline: Int, val lineHeight: Int)

    private class FontSet(private val make: (Int) -> Choice?) {
        private val choices = HashMap<Int, Choice?>()

        fun choice(id: Int): Choice? = choices.getOrPut(id) { make(id) }

        fun substitute(font: SILFontLoader): SILFontLoader? {
            val (id, factor) = nativeId(font) ?: return null
            if (id == 0) return null
            val c = choice(id) ?: return null
            val en = NativeFonts.baseSpec(id)
            return SILFontLoader(c.font).also {
                it.scale = c.drawScale * factor
                it.baselineShift = (c.baseline - en.baseline) * factor
            }
        }

        companion object {
            val NONE = FontSet { null }

            fun forLanguage(lang: String, df: Datafile): FontSet? {
                hsOverrides[lang]?.let { path -> if (df.getOrNull(path) != null) return hsOverride(df, path) }
                return when (lang) {
                    "ja" -> japanese(df)
                    "zh-Hans" -> chinese(df)
                    else -> null
                }
            }

            /** ResourceControl::GetJapaneseFont: font file, size (null: the font's own), line and baseline offsets. */
            private val JA = mapOf(
                5 to JaRow("misaki_gothic", null, 3, 2), 9 to JaRow("mplus10", null, 3, 2),
                10 to JaRow("mplus12", null, 3, 1), 12 to JaRow("mplus12", null, 5, 1),
                13 to JaRow("DotGothic-32", 22f, 0, -3), 14 to JaRow("mplus10", 24f, 10, 6),
                16 to JaRow("mplus10", 24f, 2, 6), 18 to JaRow("DotGothic-24", null, 2, 0),
                20 to JaRow("DotGothic-32", null, 5, 0), 24 to JaRow("DotGothic-24", null, 14, 13),
                51 to JaRow("mplus10", null, 2, -1), 52 to JaRow("DotGothic-32", 22f, 8, 1),
                53 to JaRow("DotGothic-24", null, 13, 2), 59 to JaRow("DotGothic-32", 22f, 0, 1),
                61 to JaRow("DotGothic-32-O", 12f, 2, 0), 62 to JaRow("DotGothic-16-O", null, 4, 0),
                63 to JaRow("DotGothic-24-O", null, 9, 1), 64 to JaRow("DotGothic-32-O", null, 9, -1),
            )

            private class JaRow(val file: String, val size: Float?, val lineOffset: Int, val baselineOffset: Int)

            private fun japanese(df: Datafile): FontSet? {
                if (df.getOrNull("fonts/ja/DotGothic-32.font") == null) return null
                return FontSet { id ->
                    val row = JA[id] ?: JaRow("DotGothic-32", id.toFloat(), 0, 0)
                    val font = bitmapFont(df, "fonts/ja/${row.file}.font") ?: return@FontSet null
                    // init_bitmap gives the font its line spacing as its size; the bitmap font's metrics
                    // scale the glyphs, the baseline and the height by size / line spacing.
                    val natural = font.fileLineSpacingValue.toFloat()
                    val size = row.size ?: natural
                    val scale = size / natural
                    Choice(font, size, scale, nativeRound(font.baselineToTop * scale) + row.baselineOffset,
                        nativeRound(size) + row.lineOffset)
                }
            }

            /** ResourceControl::GetChineseFont: size, line and baseline offsets; 62-64 outlined. */
            private val ZH = mapOf(
                5 to intArrayOf(11, -2, 1), 9 to intArrayOf(13, 0, 1), 10 to intArrayOf(14, 1, 0),
                12 to intArrayOf(16, 2, -2), 13 to intArrayOf(17, 3, -3), 14 to intArrayOf(20, 11, 7),
                16 to intArrayOf(20, -3, 7), 18 to intArrayOf(32, -3, -3), 20 to intArrayOf(48, -2, -4),
                24 to intArrayOf(27, 22, 13), 51 to intArrayOf(11, -3, 0), 61 to intArrayOf(11, -3, 0),
                52 to intArrayOf(18, 3, 0), 53 to intArrayOf(27, 7, 1), 59 to intArrayOf(18, -3, 1),
                62 to intArrayOf(16, 6, 2), 63 to intArrayOf(24, 11, 2), 64 to intArrayOf(31, 17, 3),
            )

            private fun chinese(df: Datafile): FontSet? {
                val path = "fonts/zh-Hans/black.ttf"
                if (df.getOrNull(path) == null) return null
                return ttfSet(df, path) { id -> id - 62 in 0..2 }
            }

            /**
             * Hyperspace FreeTypeFont::AdjustForSizeType (CustomLocalization.cpp): GetChineseFont's table for the
             * override font, the outlined copy (outline 2.0) for IDs 62 and 63 only.
             */
            private fun hsOverride(df: Datafile, path: String): FontSet = ttfSet(df, path) { id -> id - 0x3e in 0..1 }

            private fun ttfSet(df: Datafile, path: String, outlined: (Int) -> Boolean): FontSet = FontSet { id ->
                val row = ZH[id] ?: intArrayOf(id, 0, 0)
                val px = row[0]
                val ttf = trueType(df, path) ?: return@FontSet null
                val outline = if (outlined(id)) 2f else 0f
                val font = ttfFont(path, ttf, px, outline)
                // FreeType face metrics at the size: baseline = ascender, height = face height, unrounded.
                val baseline = nativeRound(ttf.ascender.toFloat() * px / ttf.unitsPerEm) + row[2]
                val lineHeight = nativeRound((ttf.ascender - ttf.descender + ttf.lineGap).toFloat() * px / ttf.unitsPerEm) + row[1]
                Choice(font, px.toFloat(), 1f, baseline, lineHeight)
            }
        }
    }

    /** roundf: half away from zero. */
    private fun nativeRound(v: Float): Int = if (v >= 0) Math.floor(v + 0.5).toInt() else -Math.floor(-v + 0.5).toInt()

    private fun bitmapFont(df: Datafile, path: String): SILFontLoader? {
        (faces[path] as? SILFontLoader)?.let { return it }
        val file = df.getOrNull(path) ?: return null
        val font = SILFontLoader(BitmapSource(GlyphAtlas(context), df.read(file)))
        faces[path] = font
        return font
    }

    private fun trueType(df: Datafile, path: String): TrueTypeFont? {
        (faces[path] as? TrueTypeFont)?.let { return it }
        val file = df.getOrNull(path) ?: return null
        val ttf = try { TrueTypeFont(df.read(file)) } catch (e: Exception) { println("[WARN] Can't read font $path: $e"); return null }
        faces[path] = ttf
        return ttf
    }

    private fun ttfFont(path: String, ttf: TrueTypeFont, px: Int, outline: Float): SILFontLoader {
        val key = "$path@$px@$outline"
        (faces[key] as? SILFontLoader)?.let { return it }
        val font = SILFontLoader(TrueTypeSource(GlyphAtlas(context), ttf, px, outline))
        faces[key] = font
        return font
    }

    /** A SIL bitmap font file read glyph by glyph (the Japanese fonts' textures are up to 4096x4096). */
    private class BitmapSource(override val atlas: GlyphAtlas, private val data: ByteArray) : SILFontLoader.GlyphSource {
        override val lineSpacing: Int
        override val baseline: Int
        private val infos = HashMap<Char, IntArray>()
        private val blocks = HashSet<Int>()
        private val texWidth: Int
        private val pixels: Int

        init {
            val b = DataInputStream(ByteArrayInputStream(data))
            check(String(ByteArray(4).also { b.readFully(it) }, Charsets.ISO_8859_1) == "FONT")
            b.read()
            lineSpacing = b.read()
            baseline = b.read()
            b.read()
            val charinfoOffset = b.readInt()
            val count = b.readUnsignedShort()
            val size = b.readUnsignedShort()
            val textureOffset = b.readInt()
            for (i in 0 until count) {
                val o = charinfoOffset + i * size
                val ch = int(o)
                blocks.add(ch shr 8)
                infos[ch.toChar()] = intArrayOf(s16(o + 4), s16(o + 6), u8(o + 8), u8(o + 9), data[o + 10].toInt(), s16(o + 12), s16(o + 14))
            }
            texWidth = u16(textureOffset + 8)
            pixels = textureOffset + int(textureOffset + 16)
        }

        private fun u8(o: Int) = data[o].toInt() and 0xff
        private fun u16(o: Int) = (u8(o) shl 8) or u8(o + 1)
        private fun s16(o: Int) = u16(o).toShort().toInt()
        private fun int(o: Int) = (u16(o) shl 16) or u16(o + 2)

        override fun glyph(ch: Char): SILFontLoader.Charinfo? {
            val i = infos[ch] ?: return if ((ch.code shr 8) in blocks) SILFontLoader.ZERO_GLYPH else null
            val (x, y, w, h) = i
            val slot = atlas.add(w, h, data, pixels + y * texWidth + x, texWidth)
            return SILFontLoader.Charinfo(ch, slot.x, slot.y, w, h, i[4], i[5] / 256f, i[6] / 256f, slot.page)
        }
    }

    /** A TrueType font rendered at one pixel size ([TrueTypeFont]). */
    private class TrueTypeSource(override val atlas: GlyphAtlas, private val ttf: TrueTypeFont, private val px: Int,
                                 private val outline: Float) : SILFontLoader.GlyphSource {
        override val lineSpacing: Int = ttf.pixelHeight(px)
        override val baseline: Int = ttf.pixelAscender(px)

        override fun glyph(ch: Char): SILFontLoader.Charinfo? {
            val gid = ttf.glyphIndex(ch.code)
            if (gid == 0) return null
            val bmp = ttf.render(gid, px, outline)
            val slot = atlas.add(bmp.width, bmp.height, bmp.alpha)
            return SILFontLoader.Charinfo(ch, slot.x, slot.y, bmp.width, bmp.height, bmp.top,
                bmp.left.toFloat(), (bmp.advance - bmp.left - bmp.width).toFloat(), slot.page)
        }

        private val layers = HashMap<Int, TrueTypeFont.Layers?>()

        override fun kerning(prev: Char, ch: Char): Float =
            if (prev.code == 0) 0f else ttf.pixelKerning(ttf.glyphIndex(prev.code), ttf.glyphIndex(ch.code), px).toFloat()

        /** Laid-out strings, as the original caches them; the least recently drawn ones are freed. */
        private val strings = object : LinkedHashMap<String, Composed>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Composed>): Boolean {
                if (size <= 256) return false
                eldest.value.image?.texture?.free()
                return true
            }
        }

        private class Composed(val image: Image?, val offsetX: Int, val baselineRow: Int)

        /**
         * The TrueType layout renders a whole line into one coverage buffer: per glyph, at its whole-
         * pixel pen, an outlined font adds the stroked border (saturating) and then takes the glyph's own coverage
         * away, so a glyph also erases its neighbour's outline where they meet. The buffer is ceil(ascent) + 1 +
         * outline rows above the baseline and spans the line's extents plus a pixel each side; it is drawn at the
         * rounded pen. Only the outlined fonts come here (the plain ones match glyph by glyph).
         */
        override fun drawString(x: Float, y: Float, width: Float, text: String, col: Colour): Boolean {
            if (outline <= 0f) return false
            val c = strings.getOrPut(text) { compose(text) }
            val image = c.image ?: return true
            val x0 = nativeRound(x) - c.offsetX
            val y0 = nativeRound(y) - c.baselineRow
            val w = minOf(image.width.toFloat(), width + x - x0)
            if (w <= 0f) return true
            image.draw(x0.toFloat(), y0.toFloat(), x0 + w, y0.toFloat() + image.height, 0f, 0f, w, image.height.toFloat(), 1f, col)
            return true
        }

        private fun compose(text: String): Composed {
            val placed = ArrayList<Pair<Int, TrueTypeFont.Layers>>()
            var pen = 0
            var left = 0
            var right = 0
            var prev = 0
            for (ch in text) {
                val gid = ttf.glyphIndex(ch.code)
                if (gid == 0) continue
                pen += ttf.pixelKerning(prev, gid, px)
                prev = gid
                val l = layers.getOrPut(gid) { ttf.renderLayers(gid, px, outline) }
                if (l != null) {
                    placed.add(pen to l)
                    left = minOf(left, pen + l.left)
                    right = maxOf(right, pen + l.left + l.width)
                }
                pen += ttf.pixelAdvance(gid, px)
            }
            val r = kotlin.math.ceil(outline).toInt()
            val ascent = kotlin.math.ceil(ttf.ascender.toDouble() * px / ttf.unitsPerEm).toInt()
            val descent = kotlin.math.ceil(ttf.descender.toDouble() * px / ttf.unitsPerEm).toInt()
            val bw = 2 - left + right
            val bh = ascent + 2 - descent + 2 * r
            val baselineRow = ascent + 1 + r
            val offsetX = 1 - left
            if (placed.isEmpty() || bw <= 0 || bh <= 0) return Composed(null, offsetX, baselineRow)
            val buf = IntArray(bw * bh)
            for ((at, l) in placed) {
                val col0 = minOf(maxOf(0, offsetX + at + l.left), bw - (l.width + 1))
                val row0 = minOf(maxOf(0, baselineRow - l.top), bh - l.height)
                for (yy in 0 until l.height) {
                    val ry = row0 + yy
                    if (ry < 0 || ry >= bh) continue
                    for (xx in 0 until l.width) {
                        val rx = col0 + xx
                        if (rx < 0 || rx >= bw) continue
                        val v = l.stroke[yy * l.width + xx].toInt() and 0xff
                        if (v != 0) buf[ry * bw + rx] = minOf(255, buf[ry * bw + rx] + v)
                    }
                }
                for (yy in 0 until l.height) {
                    val ry = row0 + yy
                    if (ry < 0 || ry >= bh) continue
                    for (xx in 0 until l.width) {
                        val rx = col0 + xx
                        if (rx < 0 || rx >= bw) continue
                        val v = l.fill[yy * l.width + xx].toInt() and 0xff
                        if (v != 0) buf[ry * bw + rx] = maxOf(0, buf[ry * bw + rx] - v)
                    }
                }
            }
            // The loader uploads a power-of-two texture from a buffer of that stride (as the image decoder pads).
            val tw = org.newdawn.slick.opengl.InternalTextureLoader.get2Fold(bw)
            val th = org.newdawn.slick.opengl.InternalTextureLoader.get2Fold(bh)
            val rgba = java.nio.ByteBuffer.allocateDirect(tw * th * 4)
            for (yy in 0 until bh) {
                rgba.position(yy * tw * 4)
                for (xx in 0 until bw) { rgba.put(-1); rgba.put(-1); rgba.put(-1); rgba.put(buf[yy * bw + xx].toByte()) }
            }
            rgba.position(0)
            val image = TextureLoader.loadImage(context, object : org.newdawn.slick.opengl.ImageData {
                override fun getHeight(): Int = bh
                override fun getTexWidth(): Int = tw
                override fun getDepth(): Int = 32
                override fun getImageBufferData(): java.nio.ByteBuffer = rgba
                override fun getWidth(): Int = bw
                override fun getTexHeight(): Int = th
            })
            return Composed(image, offsetX, baselineRow)
        }
    }

    // freetype::formatLines: the line breaker. Japanese never breaks before set A (closing marks,
    // small kana, prolonged sounds), after set B (opening marks) or inside a run of set C (dashes, leaders), nor
    // between two printable ASCII characters; Chinese (any "zh" language) never before set D or after set E, nor
    // between printable ASCII. Every other language breaks only before a space. The sets are the original's
    // (-), including its full-width I and H where the brackets were surely meant.
    private val JA_NO_BREAK_BEFORE = ",)Ｉ]］}｝、〕〉》」』】〙〗〟’”｠»ヽヾーァィゥェォッャュョヮヵヶぁぃぅぇぉっゃゅょゎゕゖㇰㇱㇳㇲㇳㇴㇵㇶㇷㇸㇹㇺㇻㇼㇽㇾㇿ々〻-－‐゠–〜?!‼⁇⁈⁉・:：;；。.．°¢′″‰℃ℓ㏋".toSet()
    private val JA_NO_BREAK_AFTER = "(Ｈ[［{｛〔〈《「『【〘〖〝‘“｟«¥￥£$＄#＃€№".toSet()
    private val JA_INSEPARABLE = "—…‥〳〴〵".toSet()
    private val ZH_NO_BREAK_BEFORE = "!%),.:;?]}¢°·’”†‡›℃∶、。〃〆〕〗〞﹚﹜！＂％＇），．：；？！］｝～".toSet()
    private val ZH_NO_BREAK_AFTER = "$(£¥·‘“〈《「『【〔〖〝﹙﹛＄（．［｛￡￥".toSet()

    private fun printable(c: Char) = c.code - 0x21 in 0 until 0x5e

    /**
     * freetype::formatLines with [font]'s advances, for the current language's rules. Hyperspace runs
     * on 1.6.9, whose formatLines has the Chinese rules for "zh-Hans" alone and none for Japanese, which
     * breaks before spaces like the other languages there (oracle swl-ja: "Hello World 12" in 120 px).
     */
    fun formatLines(font: SILFontLoader, text: String, maxWidth: Int): List<String> {
        val lang = Languages.current
        val v169 = xyz.znix.xftl.hs.HS.active
        val ja = !v169 && lang == "ja"
        val zh = if (v169) lang == "zh-Hans" else lang.startsWith("zh")
        val spaceW = font.nativeAdvance(' ')
        val hyphenW = font.nativeAdvance('-')
        val max = maxWidth.toFloat()
        val lines = ArrayList<String>()
        var lineStart = 0
        var cur = 0
        var breakPos = -1
        var breakWidth = 0f
        var hyphen = false
        var width = 0f
        var prev = 0
        var i = 0
        while (true) {
            val c = if (i < text.length) text[i].code else 0
            val next = i + 1
            if (c == 10 || c == 0) {
                lines.add(text.substring(lineStart, cur))
                if (c == 0) return lines
                width = 0f; prev = 0; breakPos = -1; breakWidth = 0f; hyphen = false
                lineStart = next; cur = next; i = next
                continue
            }
            if (c == 1) {
                // A hyphenation point: a break here ends the line with '-'.
                prev = 1
                cur = next
                if (max > 0 && width + hyphenW <= max) { breakPos = next; breakWidth = width; hyphen = true }
                i = next
                continue
            }
            if (max <= 0f) { prev = c; cur = next; i = next; continue }
            if (prev != 0) {
                val p = prev.toChar()
                val ch = c.toChar()
                val canBreak = when {
                    ja -> !(printable(p) && printable(ch)) && p !in JA_NO_BREAK_AFTER && ch !in JA_NO_BREAK_BEFORE &&
                            !(p in JA_INSEPARABLE && ch in JA_INSEPARABLE)
                    zh -> !(printable(p) && printable(ch)) && p !in ZH_NO_BREAK_AFTER && ch !in ZH_NO_BREAK_BEFORE
                    else -> c == 0x20 && prev != 0x20
                }
                if (canBreak) { hyphen = false; breakWidth = width; breakPos = cur }
            }
            val adv = font.nativeAdvance(c.toChar())
            if (width <= 0f) {
                width += adv; cur = next; prev = c; i = next
                continue
            }
            if (max < adv + width) {
                if (breakPos < 0) { breakPos = cur; breakWidth = width }
                lines.add(text.substring(lineStart, breakPos) + if (hyphen) "-" else "")
                var newWidth = width - breakWidth
                var n = 0
                while (breakPos + n < text.length && text[breakPos + n] == ' ') n++
                lineStart = breakPos
                var advNow = adv
                var cNow = c
                var nextNow = next
                if (n > 0) {
                    lineStart = breakPos + n
                    if (lineStart < next) newWidth -= n * spaceW
                    else { advNow = 0f; cNow = 0x20; newWidth = 0f; nextNow = lineStart }
                }
                hyphen = false
                width = newWidth + advNow
                breakWidth = 0f
                breakPos = -1
                cur = nextNow; prev = cNow; i = nextNow
                continue
            }
            cur = next; width += adv; prev = c; i = next
        }
    }
}
