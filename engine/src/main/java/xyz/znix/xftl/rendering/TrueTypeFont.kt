package xyz.znix.xftl.rendering

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A TrueType (glyf) font read from the game or mod data, rasterised in the engine.
 *
 * The desktop game draws `fonts/zh-Hans/black.ttf` (and Hyperspace a `<language fontOverride>` font) through
 * FreeType. We parse the font ourselves and fill each glyph outline with exact area coverage, the way FreeType's
 * smooth rasteriser does. The TrueType bytecode hinter is not run: the game's SimHei has no glyph programs (its
 * fpgm/prep only set up the CVT), so FreeType's hinted load leaves its outlines as scaled, and the original's frames
 * match the unhinted fill. Advances and the size metrics follow FreeType's rounding for a scalable font requested
 * at an integer pixel size.
 */
class TrueTypeFont(private val data: ByteArray) {
    private val tables = HashMap<String, Int>()

    val unitsPerEm: Int
    private val indexToLocFormat: Int
    val numGlyphs: Int
    val ascender: Int
    val descender: Int
    val lineGap: Int
    private val numHMetrics: Int
    private val cmapOffset: Int
    private val cmapFormat: Int

    init {
        val numTables = u16(4)
        for (i in 0 until numTables) {
            val rec = 12 + 16 * i
            val tag = String(CharArray(4) { (data[rec + it].toInt() and 0xff).toChar() })
            tables[tag] = u32(rec + 8).toInt()
        }
        val head = table("head")
        unitsPerEm = u16(head + 18)
        indexToLocFormat = s16(head + 50)
        numGlyphs = u16(table("maxp") + 4)
        val hhea = table("hhea")
        ascender = s16(hhea + 4)
        descender = s16(hhea + 6)
        lineGap = s16(hhea + 8)
        numHMetrics = u16(hhea + 34)

        // Unicode subtables: full repertoire (3,10 / format 12) first, then the BMP (3,1 or 0,x / format 4).
        val cmap = table("cmap")
        var best = -1
        var bestFormat = 0
        var bestRank = 0
        for (i in 0 until u16(cmap + 2)) {
            val rec = cmap + 4 + 8 * i
            val platform = u16(rec)
            val encoding = u16(rec + 2)
            val off = cmap + u32(rec + 4).toInt()
            val format = u16(off)
            val rank = when {
                format == 12 && (platform == 3 && encoding == 10 || platform == 0) -> 3
                format == 4 && (platform == 3 && encoding == 1 || platform == 0) -> 2
                else -> 0
            }
            if (rank > bestRank) { best = off; bestFormat = format; bestRank = rank }
        }
        cmapOffset = best
        cmapFormat = bestFormat
    }

    private fun table(tag: String) = tables[tag] ?: error("TrueType font without a '$tag' table")
    private fun u8(o: Int) = data[o].toInt() and 0xff
    private fun u16(o: Int) = (u8(o) shl 8) or u8(o + 1)
    private fun s16(o: Int) = u16(o).toShort().toInt()
    private fun u32(o: Int) = (u16(o).toLong() shl 16) or u16(o + 2).toLong()

    /** FT_Get_Char_Index: the glyph for a code point, 0 (.notdef) when the font lacks it. */
    fun glyphIndex(cp: Int): Int {
        val c = cmapOffset
        if (c < 0) return 0
        if (cmapFormat == 12) {
            val groups = u32(c + 12).toInt()
            var lo = 0
            var hi = groups - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val g = c + 16 + 12 * mid
                val start = u32(g)
                val end = u32(g + 4)
                when {
                    cp < start -> hi = mid - 1
                    cp > end -> lo = mid + 1
                    else -> return (u32(g + 8) + (cp - start)).toInt()
                }
            }
            return 0
        }
        if (cp > 0xffff) return 0
        val segX2 = u16(c + 6)
        val ends = c + 14
        val starts = ends + segX2 + 2
        val deltas = starts + segX2
        val rangeOffsets = deltas + segX2
        var lo = 0
        var hi = segX2 / 2 - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val end = u16(ends + 2 * mid)
            val start = u16(starts + 2 * mid)
            when {
                cp > end -> lo = mid + 1
                cp < start -> hi = mid - 1
                else -> {
                    val ro = u16(rangeOffsets + 2 * mid)
                    val delta = u16(deltas + 2 * mid)
                    if (ro == 0) return (cp + delta) and 0xffff
                    val gi = u16(rangeOffsets + 2 * mid + ro + 2 * (cp - start))
                    return if (gi == 0) 0 else (gi + delta) and 0xffff
                }
            }
        }
        return 0
    }

    /** The glyph's advance width in font units (hmtx). */
    fun advanceWidth(gid: Int): Int {
        val hmtx = table("hmtx")
        return u16(hmtx + 4 * min(gid, numHMetrics - 1))
    }

    /** FreeType's scaled size metrics for an integer pixel size (tt_size_reset, ppem rounded): 26.6 rounded. */
    fun pixelAscender(px: Int) = roundPix(ascender.toDouble() * px / unitsPerEm)
    fun pixelDescender(px: Int) = roundPix(descender.toDouble() * px / unitsPerEm)
    fun pixelHeight(px: Int) = roundPix((ascender - descender + lineGap).toDouble() * px / unitsPerEm)

    /** The 'kern' table's horizontal format 0 pairs, (left shl 16 or right) to the summed value in font units. */
    private val kernPairs: Map<Int, Int> by lazy {
        val out = HashMap<Int, Int>()
        val k = tables["kern"] ?: return@lazy out
        if (u16(k) != 0) return@lazy out
        var o = k + 4
        repeat(u16(k + 2)) {
            val length = u16(o + 2)
            val coverage = u16(o + 4)
            // Format 0, horizontal, neither minimum values nor cross-stream (tt_face_load_kern).
            if (coverage shr 8 == 0 && coverage and 7 == 1) {
                val n = u16(o + 6)
                for (i in 0 until n) {
                    val p = o + 14 + 6 * i
                    val key = (u16(p) shl 16) or u16(p + 2)
                    out[key] = (out[key] ?: 0) + s16(p + 4)
                }
            }
            o += length
        }
        out
    }

    /**
     * FT_Get_Kerning(FT_KERNING_DEFAULT) between two glyphs in whole pixels: the scaled pair value, damped below
     * 25 ppem (times ppem / 25) and rounded to the pixel grid. The layouts add it only when the face has kerning.
     */
    fun pixelKerning(left: Int, right: Int, px: Int): Int {
        if (left == 0 || right == 0) return 0
        val v = kernPairs[(left shl 16) or right] ?: return 0
        var k = Math.round(v.toDouble() * px * 64.0 / unitsPerEm)
        if (px < 25) k = Math.floorDiv(k * px + 12, 25L)
        return Math.floorDiv(k + 32, 64L).toInt()
    }

    /** A hinted load's advance: the linear advance rounded to whole pixels. */
    fun pixelAdvance(gid: Int, px: Int) = roundPix(advanceWidth(gid).toDouble() * px / unitsPerEm)

    private fun roundPix(v: Double): Int {
        // FT_MulFix in 26.6 then FT_PIX_ROUND: (x + 32) & -64.
        val f = Math.round(v * 64.0)
        return Math.floorDiv(f + 32, 64L).toInt()
    }

    private class Point(val x: Float, val y: Float, val on: Boolean)

    /** The glyph's contours in font units, composites resolved. */
    private fun contours(gid: Int, depth: Int = 0): List<List<Point>> {
        if (gid < 0 || gid >= numGlyphs || depth > 8) return emptyList()
        val loca = table("loca")
        val start: Int
        val end: Int
        if (indexToLocFormat == 0) {
            start = u16(loca + 2 * gid) * 2
            end = u16(loca + 2 * gid + 2) * 2
        } else {
            start = u32(loca + 4 * gid).toInt()
            end = u32(loca + 4 * gid + 4).toInt()
        }
        if (end <= start) return emptyList()
        val g = table("glyf") + start
        val n = s16(g)
        if (n >= 0) return simpleContours(g, n)

        val out = ArrayList<List<Point>>()
        var p = g + 10
        while (true) {
            val flags = u16(p)
            val glyph = u16(p + 2)
            p += 4
            val dx: Int
            val dy: Int
            if (flags and 1 != 0) { dx = s16(p); dy = s16(p + 2); p += 4 } else { dx = data[p].toInt(); dy = data[p + 1].toInt(); p += 2 }
            var a = 1f; var b = 0f; var c = 0f; var d = 1f
            when {
                flags and 0x08 != 0 -> { a = f2dot14(p); d = a; p += 2 }
                flags and 0x40 != 0 -> { a = f2dot14(p); d = f2dot14(p + 2); p += 4 }
                flags and 0x80 != 0 -> { a = f2dot14(p); b = f2dot14(p + 2); c = f2dot14(p + 4); d = f2dot14(p + 6); p += 8 }
            }
            // ARGS_ARE_XY_VALUES: an offset (point matching is not supported and leaves the part in place).
            val ox = if (flags and 2 != 0) dx.toFloat() else 0f
            val oy = if (flags and 2 != 0) dy.toFloat() else 0f
            for (contour in contours(glyph, depth + 1))
                out.add(contour.map { Point(it.x * a + it.y * c + ox, it.x * b + it.y * d + oy, it.on) })
            if (flags and 0x20 == 0) break
        }
        return out
    }

    private fun f2dot14(o: Int) = s16(o) / 16384f

    private fun simpleContours(g: Int, n: Int): List<List<Point>> {
        val endPts = IntArray(n) { u16(g + 10 + 2 * it) }
        val count = if (n == 0) 0 else endPts[n - 1] + 1
        var p = g + 10 + 2 * n
        p += 2 + u16(p)
        val flags = IntArray(count)
        var i = 0
        while (i < count) {
            val f = u8(p++)
            flags[i++] = f
            if (f and 8 != 0) {
                var r = u8(p++)
                while (r-- > 0 && i < count) flags[i++] = f
            }
        }
        val xs = IntArray(count)
        var v = 0
        for (k in 0 until count) {
            val f = flags[k]
            if (f and 2 != 0) { val dx = u8(p++); v += if (f and 16 != 0) dx else -dx }
            else if (f and 16 == 0) { v += s16(p); p += 2 }
            xs[k] = v
        }
        val ys = IntArray(count)
        v = 0
        for (k in 0 until count) {
            val f = flags[k]
            if (f and 4 != 0) { val dy = u8(p++); v += if (f and 32 != 0) dy else -dy }
            else if (f and 32 == 0) { v += s16(p); p += 2 }
            ys[k] = v
        }
        val out = ArrayList<List<Point>>(n)
        var first = 0
        for (e in endPts) {
            if (e >= first) out.add((first..e).map { Point(xs[it].toFloat(), ys[it].toFloat(), flags[it] and 1 != 0) })
            first = e + 1
        }
        return out
    }

    /** A rendered glyph: [alpha] is [width] x [height], its top-left [left] right of and [top] above the pen. */
    class Bitmap(val width: Int, val height: Int, val left: Int, val top: Int, val advance: Int, val alpha: ByteArray)

    /**
     * An outlined glyph's two layers in one box ([left], [top] as for [Bitmap]): [stroke] is the glyph widened by
     * the outline radius (FT_Glyph_StrokeBorder, outside border), [fill] the glyph itself.
     */
    class Layers(val width: Int, val height: Int, val left: Int, val top: Int, val advance: Int,
                 val stroke: ByteArray, val fill: ByteArray)

    /** The glyph's pixel box at [scale]: FT_Outline_Get_CBox grid fitted (floor the minimum, ceil the maximum). */
    private class Box(val left: Int, val top: Int, val width: Int, val height: Int)

    private fun box(contours: List<List<Point>>, scale: Float): Box? {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (c in contours) for (pt in c) {
            minX = min(minX, pt.x); maxX = max(maxX, pt.x); minY = min(minY, pt.y); maxY = max(maxY, pt.y)
        }
        if (minX > maxX) return null
        val left = floor(minX * scale).toInt()
        val right = ceil(maxX * scale).toInt()
        val bottom = floor(minY * scale).toInt()
        val top = ceil(maxY * scale).toInt()
        if (right <= left || top <= bottom) return null
        return Box(left, top, right - left, top - bottom)
    }

    /**
     * Renders one glyph at [px] pixels per em. With an [outline] (the outlined button fonts, radius 2) the original
     * adds the stroked border to the string's coverage and then takes the glyph away again, so the glyph is drawn
     * as its outline ring only; this returns that ring for one glyph (strings compose [renderLayers] in order).
     */
    fun render(gid: Int, px: Int, outline: Float = 0f): Bitmap {
        val advance = pixelAdvance(gid, px)
        if (outline > 0f) {
            val l = renderLayers(gid, px, outline) ?: return Bitmap(0, 0, 0, 0, advance, ByteArray(0))
            val ring = ByteArray(l.width * l.height) {
                max(0, (l.stroke[it].toInt() and 0xff) - (l.fill[it].toInt() and 0xff)).toByte()
            }
            return Bitmap(l.width, l.height, l.left, l.top, advance, ring)
        }
        val scale = px.toFloat() / unitsPerEm
        val contours = contours(gid)
        val b = box(contours, scale) ?: return Bitmap(0, 0, 0, 0, advance, ByteArray(0))
        val cov = rasterize(contours, scale, b.left, b.top, b.width, b.height)
        val alpha = ByteArray(b.width * b.height) { (cov[it] * 255f).roundToInt().toByte() }
        return Bitmap(b.width, b.height, b.left, b.top, advance, alpha)
    }

    /**
     * The stroke and fill of an outlined glyph (font_data::init's outline 2.0: FT_Stroker radius 2 px, round caps
     * and joins). The stroke's coverage is the share of each pixel within [outline] of the glyph, measured on a
     * 4x4 grid of the glyph filled at four times the size; its box is the glyph's grown by the radius.
     */
    fun renderLayers(gid: Int, px: Int, outline: Float): Layers? {
        val scale = px.toFloat() / unitsPerEm
        val advance = pixelAdvance(gid, px)
        val contours = contours(gid)
        val b = box(contours, scale) ?: return null
        val r = ceil(outline).toInt()
        val w = b.width + 2 * r
        val h = b.height + 2 * r
        val left = b.left - r
        val top = b.top + r
        val fillCov = rasterize(contours, scale, left, top, w, h)
        val fill = ByteArray(w * h) { (fillCov[it] * 255f).roundToInt().toByte() }

        val ss = 4
        val hw = w * ss
        val hh = h * ss
        val hi = rasterize(contours, scale * ss, left * ss, top * ss, hw, hh)
        val inside = BooleanArray(hw * hh) { hi[it] >= 0.5f }
        // Dilate by a disc of radius outline * ss sub-pixels: per row offset, a horizontal run of half-width k.
        val rad = outline * ss
        val ri = floor(rad).toInt()
        val half = IntArray(2 * ri + 1) { floor(sqrt(max(0f, rad * rad - (it - ri) * (it - ri).toFloat()))).toInt() }
        // Row prefix counts of inside samples.
        val prefix = IntArray((hw + 1) * hh)
        for (y in 0 until hh) {
            var acc = 0
            prefix[y * (hw + 1)] = 0
            for (x in 0 until hw) {
                if (inside[y * hw + x]) acc++
                prefix[y * (hw + 1) + x + 1] = acc
            }
        }
        val grown = BooleanArray(hw * hh)
        for (y in 0 until hh) for (x in 0 until hw) {
            var hit = false
            for (dy in -ri..ri) {
                val sy = y + dy
                if (sy < 0 || sy >= hh) continue
                val k = half[dy + ri]
                val x0 = max(0, x - k)
                val x1 = min(hw, x + k + 1)
                if (x0 < x1 && prefix[sy * (hw + 1) + x1] - prefix[sy * (hw + 1) + x0] > 0) { hit = true; break }
            }
            grown[y * hw + x] = hit
        }
        val stroke = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var n = 0
            for (sy in 0 until ss) for (sx in 0 until ss) if (grown[(y * ss + sy) * hw + x * ss + sx]) n++
            // The border covers the glyph's own antialiased edge at least as much as the fill does.
            val v = max(n * 255 / (ss * ss), fill[y * w + x].toInt() and 0xff)
            stroke[y * w + x] = v.toByte()
        }
        return Layers(w, h, left, top, advance, stroke, fill)
    }

    /**
     * Exact-area coverage of the outline at [scale] in the pixel box whose top-left is ([left], [top]) in pixel
     * units (y up), non-zero winding, like FreeType's smooth rasteriser.
     */
    private fun rasterize(contours: List<List<Point>>, scale: Float, left: Int, top: Int, w: Int, h: Int): FloatArray {
        val stride = w + 2
        val acc = FloatArray(stride * h)

        fun line(x0: Float, y0: Float, x1: Float, y1: Float) {
            if (y0 == y1) return
            val dir: Float
            val ax: Float; val ay: Float; val bx: Float; val by: Float
            if (y0 < y1) { dir = 1f; ax = x0; ay = y0; bx = x1; by = y1 } else { dir = -1f; ax = x1; ay = y1; bx = x0; by = y0 }
            val dxdy = (bx - ax) / (by - ay)
            var x = ax
            val yStart = max(0, floor(ay).toInt())
            val yEnd = min(h, ceil(by).toInt())
            if (ay < 0f) x -= ay * dxdy
            for (y in yStart until yEnd) {
                val row = y * stride
                val dy = min(y + 1f, by) - max(y.toFloat(), ay)
                val xNext = x + dxdy * dy
                val d = dy * dir
                val xa = min(x, xNext).coerceIn(0f, w.toFloat())
                val xb = max(x, xNext).coerceIn(0f, w.toFloat())
                val xaFloor = floor(xa).toInt()
                val xbCeil = ceil(xb).toInt()
                if (xbCeil <= xaFloor + 1) {
                    val xmf = (0.5f * (x + xNext)).coerceIn(0f, w.toFloat()) - xaFloor
                    acc[row + xaFloor] += d - d * xmf
                    acc[row + xaFloor + 1] += d * xmf
                } else {
                    val s = 1f / (xb - xa)
                    val x0f = xa - xaFloor
                    val a0 = 0.5f * s * (1f - x0f) * (1f - x0f)
                    val x1f = xb - xbCeil + 1f
                    val am = 0.5f * s * x1f * x1f
                    acc[row + xaFloor] += d * a0
                    if (xbCeil == xaFloor + 2) {
                        acc[row + xaFloor + 1] += d * (1f - a0 - am)
                    } else {
                        val a1 = s * (1.5f - x0f)
                        acc[row + xaFloor + 1] += d * (a1 - a0)
                        for (xi in xaFloor + 2 until xbCeil - 1) acc[row + xi] += d * s
                        val a2 = a1 + (xbCeil - xaFloor - 3) * s
                        acc[row + xbCeil - 1] += d * (1f - a2 - am)
                    }
                    acc[row + xbCeil] += d * am
                }
                x = xNext
            }
        }

        // Pixel space: x right from the box's left, y down from its top.
        fun tx(v: Float) = v * scale - left
        fun ty(v: Float) = top - v * scale
        for (contour in contours) {
            if (contour.size < 2) continue
            // Expand to on-curve start, then segments with implied on-curve midpoints.
            val pts = contour
            val startIdx = pts.indexOfFirst { it.on }
            val sx: Float; val sy: Float
            if (startIdx >= 0) { sx = pts[startIdx].x; sy = pts[startIdx].y }
            else { sx = (pts[0].x + pts[1].x) / 2; sy = (pts[0].y + pts[1].y) / 2 }
            var cx = sx; var cy = sy
            var ctrlX = 0f; var ctrlY = 0f; var hasCtrl = false
            val n = pts.size
            val begin = if (startIdx >= 0) startIdx + 1 else 0
            fun emitQuad(x0: Float, y0: Float, qx: Float, qy: Float, x2: Float, y2: Float) {
                val px0 = tx(x0); val py0 = ty(y0); val px1 = tx(qx); val py1 = ty(qy); val px2 = tx(x2); val py2 = ty(y2)
                val ddx = px0 - 2 * px1 + px2
                val ddy = py0 - 2 * py1 + py2
                val dd = sqrt(ddx * ddx + ddy * ddy)
                val steps = max(1, ceil(sqrt(dd / 0.2f)).toInt()).coerceAtMost(64)
                var lx = px0; var ly = py0
                for (k in 1..steps) {
                    val t = k.toFloat() / steps
                    val mt = 1 - t
                    val nx = mt * mt * px0 + 2 * mt * t * px1 + t * t * px2
                    val ny = mt * mt * py0 + 2 * mt * t * py1 + t * t * py2
                    line(lx, ly, nx, ny)
                    lx = nx; ly = ny
                }
            }
            for (k in 0..n) {
                val pt = if (k == n) null else pts[(begin + k) % n]
                val px = pt?.x ?: sx
                val py = pt?.y ?: sy
                val on = pt?.on ?: true
                if (on) {
                    if (hasCtrl) emitQuad(cx, cy, ctrlX, ctrlY, px, py) else line(tx(cx), ty(cy), tx(px), ty(py))
                    cx = px; cy = py; hasCtrl = false
                } else {
                    if (hasCtrl) {
                        val mx = (ctrlX + px) / 2
                        val my = (ctrlY + py) / 2
                        emitQuad(cx, cy, ctrlX, ctrlY, mx, my)
                        cx = mx; cy = my
                    }
                    ctrlX = px; ctrlY = py; hasCtrl = true
                }
            }
        }

        val cov = FloatArray(w * h)
        for (y in 0 until h) {
            var sum = 0f
            for (x in 0 until w) {
                sum += acc[y * stride + x]
                cov[y * w + x] = min(1f, abs(sum))
            }
        }
        return cov
    }
}
