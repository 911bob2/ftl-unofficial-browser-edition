// Ported from FTL-Hyperspace (GPL-3.0-or-later) commit 1b05a07 (lua/modules/graphics.i and hyperspace.i members of CSurface,
// GL_Color, GL_Texture, GL_Line, GL_TexVertex, GL_ColorTexVertex, Point, Pointf, Animation, AnimationTracker), not from
// the 1.23.2 tag. The native behaviour behind them is FTL 1.6.9's (the HS oracle); see the WP7 render report for which
// members were compared against an oracle frame or row.
package xyz.znix.xftl.hs.lua

import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.hs.lua.LuaApi as A
import xyz.znix.xftl.hs.lua.LuaArgs as arg
import xyz.znix.xftl.hs.lua.LuaPush as ret

/**
 * WP7 render slice: the CSurface drawing calls, the drawing/animation value classes and their members.
 */
object LuaAdaptersWP7Render {
    private val tmp = Colour(1f, 1f, 1f, 1f)

    @JvmStatic
    fun install() {
        surface()
        points()
        colours()
        vertices()
        textures()
        trackers()
    }

    private fun gfx(): Graphics? = HsLua.graphics

    private fun colour(index: Int): Colour = LuaGraphics.colour(tmp, arg.obj(index) as? GlColorValue)

    /** Runs [draw] with the graphics' colour set to [c] and the line width to [width], restoring both. */
    private inline fun withColour(g: Graphics, c: Colour, width: Float = g.lineWidth, draw: () -> Unit) {
        val prevColour = g.colour
        val prevWidth = g.lineWidth
        g.colour = Colour(c)
        g.lineWidth = width
        draw()
        g.colour = prevColour
        g.lineWidth = prevWidth
    }

    private fun quad(g: Graphics, c: Colour, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float, x4: Float, y4: Float) {
        LuaGraphics.flushQuads()
        g.drawCustomQuads { q ->
            q.pushVert(x1, y1, c.r, c.g, c.b, c.a)
            q.pushVert(x2, y2, c.r, c.g, c.b, c.a)
            q.pushVert(x3, y3, c.r, c.g, c.b, c.a)
            q.pushVert(x4, y4, c.r, c.g, c.b, c.a)
        }
    }

    /** GL_DrawLine's quad: a line of [width] pixels centred on the segment. */
    private fun line(g: Graphics, c: Colour, x1: Float, y1: Float, x2: Float, y2: Float, width: Float) {
        val dx = x2 - x1
        val dy = y2 - y1
        val len = kotlin.math.sqrt(dx * dx + dy * dy)
        if (len == 0f) return
        val nx = -dy / len * width / 2f
        val ny = dx / len * width / 2f
        quad(g, c, x1 + nx, y1 + ny, x2 + nx, y2 + ny, x2 - nx, y2 - ny, x1 - nx, y1 - ny)
    }

    private fun surface() {
        val m = LuaReg::method
        // GL_DrawLine(x1, y1, x2, y2, lineWidth, color)
        m(A.M_CSurface_GL_DrawLine, LuaMethod {
            gfx()?.let { g -> line(g, colour(6), arg.float(1), arg.float(2), arg.float(3), arg.float(4), arg.float(5)) }
            ret.bool(true)
        })
        // GL_DrawCircle(x, y, radius, color): a filled disc
        m(A.M_CSurface_GL_DrawCircle, LuaMethod {
            gfx()?.let { g ->
                LuaGraphics.flushQuads()
                val r = arg.float(3)
                withColour(g, colour(4)) { g.fillOval(arg.float(1) - r, arg.float(2) - r, 2 * r, 2 * r) }
            }
            ret.bool(true)
        })
        // GL_DrawEllipse(x, y, a, b, color): a filled ellipse of semi-axes a and b around (x, y)
        m(A.M_CSurface_GL_DrawEllipse, LuaMethod {
            gfx()?.let { g ->
                LuaGraphics.flushQuads()
                val a = arg.float(3)
                val b = arg.float(4)
                withColour(g, colour(5)) { g.fillOval(arg.float(1) - a, arg.float(2) - b, 2 * a, 2 * b) }
            }
            ret.bool(true)
        })
        // GL_DrawTriangle(Point, Point, Point, color)
        m(A.M_CSurface_GL_DrawTriangle, LuaMethod {
            val a = arg.obj(1) as PointValue
            val b = arg.obj(2) as PointValue
            val c = arg.obj(3) as PointValue
            gfx()?.let { g -> quad(g, colour(4), a.x.toFloat(), a.y.toFloat(), b.x.toFloat(), b.y.toFloat(), c.x.toFloat(), c.y.toFloat(), c.x.toFloat(), c.y.toFloat()) }
            ret.bool(true)
        })
        // GL_BlitImagePartial(tex, x, y, size_x, size_y, start_x, end_x, start_y, end_y, alpha, color, mirror)
        m(A.M_CSurface_GL_BlitImagePartial, LuaMethod {
            val t = arg.obj(1) as? LuaTexture
            val g = gfx()
            if (g != null && t != null) {
                if (!t.resolved) LuaGraphics.resolve(t)
                val img = t.image
                if (img != null) {
                    LuaGraphics.flushQuads()
                    val x = arg.float(2); val y = arg.float(3); val w = arg.float(4); val h = arg.float(5)
                    val sx = arg.float(6); val ex = arg.float(7); val sy = arg.float(8); val ey = arg.float(9)
                    val c = LuaGraphics.colour(tmp, arg.obj(11) as? GlColorValue, arg.float(10))
                    val iw = img.width.toFloat(); val ih = img.height.toFloat()
                    if (arg.bool(12)) img.draw(x, y, x + w, y + h, ex * iw, sy * ih, sx * iw, ey * ih, 1f, c)
                    else img.draw(x, y, x + w, y + h, sx * iw, sy * ih, ex * iw, ey * ih, 1f, c)
                }
            }
            ret.bool(true)
        })
        // GL_BlitPixelImage(tex, x, y, x2, y2, rotation, color, mirror): GL_BlitImage with pixel-exact sampling
        m(A.M_CSurface_GL_BlitPixelImage, LuaReg.methods[A.M_CSurface_GL_BlitImage] ?: LuaMethod { ret.bool(true) })
        // GL_BlitPixelImageWide(tex, x, y, x2, y2, opacity, color, mirror)
        m(A.M_CSurface_GL_BlitPixelImageWide, LuaMethod {
            val g = gfx()
            if (g != null) LuaGraphics.drawImage(g, arg.obj(1) as? LuaTexture, arg.float(2), arg.float(3), arg.float(4), arg.float(5), 0f,
                arg.bool(8), LuaGraphics.colour(tmp, arg.obj(7) as? GlColorValue, arg.float(6)))
            ret.bool(true)
        })
        // primitives that draw rectangles and lines
        m(A.M_CSurface_GL_CreateRectPrimitive, LuaMethod {
            val x = arg.float(1); val y = arg.float(2); val w = arg.float(3); val h = arg.float(4)
            val c = arg.obj(5) as? GlColorValue
            val p = LuaPrimitive(null, x, y, w, h, 0f, false, GlColorValue(c?.r ?: 0f, c?.g ?: 0f, c?.b ?: 0f, c?.a ?: 0f))
            p.shape = { g, col -> withColour(g, col) { g.fillRect(x, y, w, h) } }
            ret.obj(p, A.T_GL_Primitive)
        })
        m(A.M_CSurface_GL_CreateRectOutlinePrimitive, LuaMethod {
            val x = arg.int(1).toFloat(); val y = arg.int(2).toFloat(); val w = arg.int(3).toFloat(); val h = arg.int(4).toFloat()
            val lw = arg.float(6)
            val c = arg.obj(5) as? GlColorValue
            val p = LuaPrimitive(null, x, y, w, h, 0f, false, GlColorValue(c?.r ?: 0f, c?.g ?: 0f, c?.b ?: 0f, c?.a ?: 0f))
            p.lineWidth = lw
            p.shape = { g, col ->
                withColour(g, col) {
                    g.fillRect(x, y, w, lw); g.fillRect(x, y + h - lw, w, lw)
                    g.fillRect(x, y + lw, lw, h - 2 * lw); g.fillRect(x + w - lw, y + lw, lw, h - 2 * lw)
                }
            }
            ret.obj(p, A.T_GL_Primitive)
        })
        m(A.M_CSurface_GL_CreatePixelImagePrimitive, LuaMethod {
            val c = arg.obj(7) as? GlColorValue
            ret.obj(LuaPrimitive(arg.obj(1) as? LuaTexture, arg.float(2), arg.float(3), arg.float(4), arg.float(5), arg.float(6), false,
                GlColorValue(c?.r ?: 0f, c?.g ?: 0f, c?.b ?: 0f, c?.a ?: 0f)), A.T_GL_Primitive)
        })
        // The vector<GL_Line / GL_TexVertex / GL_ColorTexVertex / Globals::Rect> arguments are opaque pointer types in
        // HS's Lua: no script can make one, so SWIG's argument check rejects whatever a script passes.
        fun vectorArg(name: String, index: Int, type: String) = LuaMethod {
            throw LuaError("Error in $name (arg $index), expected '$type' got '${if (arg.isNil(index)) "nil" else "userdata"}'")
        }
        m(A.M_CSurface_GL_BlitMultiImage, vectorArg("CSurface_GL_BlitMultiImage", 2, "std::vector< GL_TexVertex > const &"))
        m(A.M_CSurface_GL_BlitMultiColorImage, vectorArg("CSurface_GL_BlitMultiColorImage", 2, "std::vector< GL_ColorTexVertex > const &"))
        m(A.M_CSurface_GL_CreateMultiImagePrimitive, vectorArg("CSurface_GL_CreateMultiImagePrimitive", 2, "std::vector< GL_TexVertex > *"))
        m(A.M_CSurface_GL_CreateMultiLinePrimitive, vectorArg("CSurface_GL_CreateMultiLinePrimitive", 1, "std::vector< GL_Line > &"))
        m(A.M_CSurface_GL_CreateMultiRectPrimitive, vectorArg("CSurface_GL_CreateMultiRectPrimitive", 1, "std::vector< Globals::Rect > &"))
        m(A.M_CSurface_AddTexVertices, vectorArg("CSurface_AddTexVertices", 1, "std::vector< GL_TexVertex > *"))
        // colour state: GL_GetColor is the colour GL_SetColor set
        m(A.M_CSurface_GL_GetColor, LuaMethod {
            val c = LuaGraphics.current
            ret.obj(GlColorValue(c.r, c.g, c.b, c.a), A.T_GL_Color)
        })
        m(A.M_CSurface_GL_LoadIdentity, LuaMethod { gfx()?.loadIdentityMatrix(); ret.num(0) })
        m(A.M_CSurface_GL_SetColorTint, LuaMethod {
            val c = arg.obj(1) as? GlColorValue
            Graphics.tint = Colour(c?.r ?: 0f, c?.g ?: 0f, c?.b ?: 0f, c?.a ?: 0f)
            0
        })
        m(A.M_CSurface_GL_RemoveColorTint, LuaMethod { Graphics.tint = Colour.white; 0 })
        m(A.M_CSurface_GetColorTint, LuaMethod {
            val c = Graphics.tint
            ret.obj(GlColorValue(c.r, c.g, c.b, c.a), A.T_GL_Color)
        })
    }

    // ---- Point / Pointf (FTL's value types; the rows are from the HS oracle) ----

    private fun isPointf(o: Any?) = o is PointfValue || o is LuaPointfMember || (o is LuaStruct && o.luaType == A.T_Pointf)
    private fun px(o: Any?): Double = (LuaReg.numGet[if (isPointf(o)) A.A_Pointf_x else A.A_Point_x] ?: throw LuaError("Point is not available")).get(o)
    private fun py(o: Any?): Double = (LuaReg.numGet[if (isPointf(o)) A.A_Pointf_y else A.A_Point_y] ?: throw LuaError("Point is not available")).get(o)
    private fun setXY(o: Any?, x: Double, y: Double) {
        val f = isPointf(o)
        LuaReg.numSet[if (f) A.A_Pointf_x else A.A_Point_x]!!.set(o, x)
        LuaReg.numSet[if (f) A.A_Pointf_y else A.A_Point_y]!!.set(o, y)
    }
    private fun pt(x: Double, y: Double): Int = ret.obj(PointValue(x.toInt(), y.toInt()), A.T_Point)
    private fun ptf(x: Double, y: Double): Int = ret.obj(PointfValue(x.toFloat(), y.toFloat()), A.T_Pointf)

    /** SWIG's argument check for a Point / Pointf userdata argument (other types are a type error). */
    private fun pointArg(method: String, index: Int, type: String): Any {
        val o = arg.obj(index)
        val ok = if (type == "Point") o is PointValue || o is LuaPointMember || (o is LuaStruct && o.luaType == A.T_Point) else isPointf(o)
        if (!ok) throw LuaError("Error in $method (arg $index), expected '$type *' got '${if (arg.isNil(index)) "nil" else "userdata"}'")
        return o!!
    }

    /** Rounds to a 32-bit float (TeaVM keeps Float arithmetic in doubles, so the bits round trip does the rounding). */
    private fun f32(x: Double): Double = java.lang.Float.intBitsToFloat(java.lang.Float.floatToIntBits(x.toFloat())).toDouble()

    /**
     * RotatePoint in 32-bit float arithmetic (cos 90 degrees is -4.4e-8 there, so a rotated Point truncates one short,
     * as the oracle's Point.Rot rows show).
     */
    private fun rotate(x: Double, y: Double, angle: Double, ox: Double, oy: Double): DoubleArray {
        val r = f32(f32(angle) * f32(0.017453292))
        val c = f32(Math.cos(r))
        val s = f32(Math.sin(r))
        val dx = f32(f32(x) - f32(ox))
        val dy = f32(f32(y) - f32(oy))
        return doubleArrayOf(f32(f32(ox) + f32(f32(dx * c) - f32(dy * s))), f32(f32(oy) + f32(f32(dx * s) + f32(dy * c))))
    }

    private fun points() {
        val m = LuaReg::method
        // Point (ints)
        m(A.M_Point___add, LuaMethod { val o = pointArg("Point::operator +", 2, "Point"); pt(px(arg.obj(1)) + px(o), py(arg.obj(1)) + py(o)) })
        m(A.M_Point___sub, LuaMethod { val o = pointArg("Point::operator -", 2, "Point"); pt(px(arg.obj(1)) - px(o), py(arg.obj(1)) - py(o)) })
        m(A.M_Point___mul, LuaMethod { val k = arg.int(2); pt((px(arg.obj(1)).toInt() * k).toDouble(), (py(arg.obj(1)).toInt() * k).toDouble()) })
        m(A.M_Point___div, LuaMethod { val k = arg.int(2); pt((px(arg.obj(1)).toInt() / k).toDouble(), (py(arg.obj(1)).toInt() / k).toDouble()) })
        m(A.M_Point___lt, LuaMethod {
            val a = arg.obj(1)
            val o = pointArg("Point::operator <", 2, "Point")
            ret.bool(px(a) < px(o) || (px(a) == px(o) && py(a) < py(o)))
        })
        m(A.M_Point_RelativeDistance, LuaMethod {
            val a = arg.obj(1)
            val o = pointArg("Point::RelativeDistance", 2, "Point")
            val dx = px(a).toInt() - px(o).toInt()
            val dy = py(a).toInt() - py(o).toInt()
            ret.num(dx * dx + dy * dy)
        })
        m(A.M_Point_Distance, LuaMethod {
            val a = arg.obj(1)
            val o = pointArg("Point::Distance", 2, "Point")
            val dx = px(a) - px(o)
            val dy = py(a) - py(o)
            ret.num(Math.sqrt(dx * dx + dy * dy).toInt())
        })
        // static Point::DistancePath(vector<Point>): the whole-pixel float lengths of the legs, summed;
        // a single point is 0 (an empty path reads past the vector in HS; 0 here)
        m(A.M_Point_DistancePath, LuaMethod {
            val v = arg.obj(1) as? ListView ?: throw LuaError("Point::DistancePath: vector<Point> expected")
            var sum = 0
            for (i in 1 until v.size()) {
                val a = v.get(i - 1); val b = v.get(i)
                val dx = px(a).toInt() - px(b).toInt(); val dy = py(a).toInt() - py(b).toInt()
                sum += Math.sqrt((dy * dy + dx * dx).toFloat().toDouble()).toFloat().toInt()
            }
            ret.num(sum)
        })
        m(A.M_Point_Slope, LuaMethod {
            val a = arg.obj(1)
            val o = pointArg("Point::Slope", 2, "Point")
            ret.num(((px(o) - px(a)) / (py(o) - py(a))).toFloat())
        })
        m(A.M_Point_equals, LuaMethod {
            val a = arg.obj(1)
            val o = pointArg("Point::equals", 2, "Point")
            ret.bool(px(a) == px(o) && py(a) == py(o))
        })
        m(A.M_Point_constructor, LuaMethod { setXY(arg.obj(1), arg.int(2).toDouble(), arg.int(3).toDouble()); 0 })
        // Pointf (floats)
        m(A.M_Pointf___add, LuaMethod { val o = pointArg("Pointf::operator +", 2, "Pointf"); ptf(px(arg.obj(1)) + px(o), py(arg.obj(1)) + py(o)) })
        val fsub = LuaMethod { val o = pointArg("Pointf::operator -", 2, "Pointf"); ptf(px(arg.obj(1)) - px(o), py(arg.obj(1)) - py(o)) }
        m(A.M_Pointf___sub__SWIG_0, fsub)
        m(A.M_Pointf___sub__SWIG_1, fsub)
        m(A.M_Pointf___mul, LuaMethod { val k = arg.float(2); ptf((px(arg.obj(1)).toFloat() * k).toDouble(), (py(arg.obj(1)).toFloat() * k).toDouble()) })
        m(A.M_Pointf___div, LuaMethod { val k = arg.float(2); ptf((px(arg.obj(1)).toFloat() / k).toDouble(), (py(arg.obj(1)).toFloat() / k).toDouble()) })
        m(A.M_Pointf_RelativeDistance, LuaMethod {
            val a = arg.obj(1)
            val o = pointArg("Pointf::RelativeDistance", 2, "Pointf")
            val dx = (px(a) - px(o)).toFloat()
            val dy = (py(a) - py(o)).toFloat()
            ret.num(dx * dx + dy * dy)
        })
        m(A.M_Pointf_Distance, LuaMethod {
            val a = arg.obj(1)
            val o = pointArg("Pointf::Distance", 2, "Pointf")
            val dx = (px(a) - px(o)).toFloat()
            val dy = (py(a) - py(o)).toFloat()
            ret.num(Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat())
        })
        m(A.M_Pointf_Normalize, LuaMethod {
            val a = arg.obj(1)
            val x = px(a).toFloat()
            val y = py(a).toFloat()
            val len = Math.sqrt((x * x + y * y).toDouble()).toFloat()
            ptf((x / len).toDouble(), (y / len).toDouble())
        })
        m(A.M_Pointf_Slope, LuaMethod {
            val a = arg.obj(1)
            val o = pointArg("Pointf::Slope", 2, "Pointf")
            ret.num(((px(o) - px(a)) / (py(o) - py(a))).toFloat())
        })
        m(A.M_Pointf_equals, LuaMethod {
            val a = arg.obj(1)
            val o = pointArg("Pointf::equals", 2, "Pointf")
            ret.bool(px(a) == px(o) && py(a) == py(o))
        })
        // SWIG_0 is the (x, y) overload, SWIG_1 the default one (-1, -1) (oracle Pointf.ctor rows)
        m(A.M_Pointf_constructor__SWIG_0, LuaMethod { setXY(arg.obj(1), arg.float(2).toDouble(), arg.float(3).toDouble()); 0 })
        m(A.M_Pointf_constructor__SWIG_1, LuaMethod { setXY(arg.obj(1), -1.0, -1.0); 0 })
        // RotatePoint(angle[, origin]) in degrees about the origin (or (0, 0)): the maths is checked against oracle rows
        val frot = LuaMethod {
            val a = arg.obj(1)
            val o = if (arg.isNil(3)) null else pointArg("Pointf::RotatePoint", 3, "Pointf")
            val r = rotate(px(a), py(a), arg.float(2).toDouble(), if (o == null) 0.0 else px(o), if (o == null) 0.0 else py(o))
            ptf(r[0], r[1])
        }
        m(A.M_Pointf_RotatePoint__SWIG_0, frot)
        m(A.M_Pointf_RotatePoint__SWIG_1, frot)
        val prot = LuaMethod {
            val a = arg.obj(1)
            val o = if (arg.isNil(3)) null else pointArg("Point::RotatePoint", 3, "Point")
            val r = rotate(px(a), py(a), arg.float(2).toDouble(), if (o == null) 0.0 else px(o), if (o == null) 0.0 else py(o))
            pt(r[0], r[1])
        }
        m(A.M_Point_RotatePoint__SWIG_0, prot)
        m(A.M_Point_RotatePoint__SWIG_1, prot)
    }

    private fun colours() {
        // GL_Color::FromHSV(h, s, v, a): every argument within 0..1 (a contract violation otherwise); FTL's own function
        LuaReg.method(A.M_GL_Color_FromHSV, LuaMethod {
            val h = arg.float(1)
            val s = arg.float(2)
            val v = arg.float(3)
            val a = arg.float(4)
            if (!(h >= 0f && h <= 1f && s >= 0f && s <= 1f && v >= 0f && v <= 1f && a >= 0f && a <= 1f))
                throw LuaError("Contract violation: require: (arg1>=0.0)&&(arg1<=1.0)&&(arg2>=0.0)&&(arg2<=1.0)&&(arg3>=0.0)&&(arg3<=1.0)&&(arg4>=0.0)&&(arg4<=1.0)")
            // FTL treats the hue as degrees (0..1 is the first 60 degree sector), with the green and blue results
            // swapped against the textbook formula: (v, p, t) with f = h / 60 (oracle HSVsweep / HSVsat rows)
            val f = h / 60f
            val p = v * (1f - s)
            val t = v * (1f - s * (1f - f))
            val rgb = floatArrayOf(v, p, t)
            ret.obj(GlColorValue(rgb[0], rgb[1], rgb[2], a), A.T_GL_Color)
        })
    }

    private fun vertices() {
        // value-initialised structs (zero fields), their members then readable and writable through the generic accessors
        LuaReg.method(A.M_new_GL_TexVertex, LuaMethod { ret.obj(LuaStructs.create(A.T_GL_TexVertex), A.T_GL_TexVertex) })
        LuaReg.method(A.M_new_GL_ColorTexVertex, LuaMethod { ret.obj(LuaStructs.create(A.T_GL_ColorTexVertex), A.T_GL_ColorTexVertex) })
        // GL_Line(float x1, y1, x2, y2) and GL_Line(Pointf start, Pointf end)
        LuaReg.method(A.M_new_GL_Line__SWIG_0, LuaMethod {
            val l = LuaStructs.create(A.T_GL_Line)
            l.values[A.A_GL_Line_start] = LuaStructs.create(A.T_Pointf).also { LuaStructs.setFields(it, arg.float(1).toDouble(), arg.float(2).toDouble()) }
            l.values[A.A_GL_Line_c_end] = LuaStructs.create(A.T_Pointf).also { LuaStructs.setFields(it, arg.float(3).toDouble(), arg.float(4).toDouble()) }
            ret.obj(l, A.T_GL_Line)
        })
        LuaReg.method(A.M_new_GL_Line__SWIG_1, LuaMethod {
            val l = LuaStructs.create(A.T_GL_Line)
            val a = pointArg("new_GL_Line", 1, "Pointf")
            val b = pointArg("new_GL_Line", 2, "Pointf")
            l.values[A.A_GL_Line_start] = LuaStructs.create(A.T_Pointf).also { LuaStructs.setFields(it, px(a), py(a)) }
            l.values[A.A_GL_Line_c_end] = LuaStructs.create(A.T_Pointf).also { LuaStructs.setFields(it, px(b), py(b)) }
            ret.obj(l, A.T_GL_Line)
        })
    }

    private var nextTextureId = 2789.0

    private fun textures() {
        // GL_Texture: the size of the image, an id, isLogical false, the unused texture window 0 (what the oracle reads)
        val ids = java.util.WeakHashMap<LuaTexture, Double>()
        val win = java.util.WeakHashMap<LuaTexture, DoubleArray>()
        fun w(o: Any?) = win.getOrPut(o as LuaTexture) { DoubleArray(4) }
        LuaReg.num(A.A_GL_Texture_width, NumGet { (it as LuaTexture).width.toDouble() }, NumSet { o, v -> (o as LuaTexture).width = v.toInt() })
        LuaReg.num(A.A_GL_Texture_height, NumGet { (it as LuaTexture).height.toDouble() }, NumSet { o, v -> (o as LuaTexture).height = v.toInt() })
        LuaReg.num(A.A_GL_Texture_id, NumGet { ids.getOrPut(it as LuaTexture) { nextTextureId++ } }, NumSet { o, v -> ids[o as LuaTexture] = v })
        LuaReg.bool(A.A_GL_Texture_isLogical, { false }, { _, _ -> })
        LuaReg.num(A.A_GL_Texture_u_base, NumGet { w(it)[0] }, NumSet { o, v -> w(o)[0] = v })
        LuaReg.num(A.A_GL_Texture_v_base, NumGet { w(it)[1] }, NumSet { o, v -> w(o)[1] = v })
        LuaReg.num(A.A_GL_Texture_u_size, NumGet { w(it)[2] }, NumSet { o, v -> w(o)[2] = v })
        LuaReg.num(A.A_GL_Texture_v_size, NumGet { w(it)[3] }, NumSet { o, v -> w(o)[3] = v })
    }

    private fun trackers() {
        // AnimationTracker (members are plain fields of a struct): the methods follow the oracle's T2/T3 rows
        fun tr(i: Int) = arg.obj(i) as LuaStruct
        fun n(t: LuaStruct, a: Int) = (t.values[a] as? Double) ?: 0.0
        LuaReg.method(A.M_AnimationTracker_Start, LuaMethod {
            val t = tr(1)
            t.values[A.A_AnimationTracker_current_time] = arg.float(2).toDouble()
            t.values[A.A_AnimationTracker_reverse] = false; t.values[A.A_AnimationTracker_running] = true; t.values[A.A_AnimationTracker_done] = false
            0
        })
        LuaReg.method(A.M_AnimationTracker_StartReverse, LuaMethod {
            val t = tr(1)
            t.values[A.A_AnimationTracker_current_time] = arg.float(2).toDouble()
            t.values[A.A_AnimationTracker_reverse] = true; t.values[A.A_AnimationTracker_running] = true; t.values[A.A_AnimationTracker_done] = false
            0
        })
        LuaReg.method(A.M_AnimationTracker_SetProgress, LuaMethod {
            val t = tr(1)
            t.values[A.A_AnimationTracker_current_time] = (arg.float(2) * n(t, A.A_AnimationTracker_time).toFloat()).toDouble()
            0
        })
        LuaReg.method(A.M_AnimationTracker_SetLoop, LuaMethod {
            val t = tr(1)
            t.values[A.A_AnimationTracker_loop] = arg.bool(2); t.values[A.A_AnimationTracker_loopDelay] = arg.float(3).toDouble()
            0
        })
        // Stop(keepTime): not running; the time goes back to 0 unless the argument is true (oracle T3.Stop rows)
        LuaReg.method(A.M_AnimationTracker_Stop, LuaMethod {
            val t = tr(1)
            t.values[A.A_AnimationTracker_running] = false
            if (!arg.bool(2)) t.values[A.A_AnimationTracker_current_time] = 0.0
            0
        })
        // Progress(speed): 0 while not running; negative speed = time / total (unclamped); else min(1, time / speed)
        // (speed 0: 1 once the time is above 0)
        LuaReg.method(A.M_AnimationTracker_Progress, LuaMethod {
            val t = tr(1)
            val time = n(t, A.A_AnimationTracker_time).toFloat()
            val ct = n(t, A.A_AnimationTracker_current_time).toFloat()
            val speed = arg.float(2)
            ret.num(when {
                t.values[A.A_AnimationTracker_running] != true -> 0f
                speed < 0f -> ct / time
                speed == 0f -> if (ct > 0f) 1f else 0f
                else -> minOf(1f, ct / speed)
            })
        })
        // GetAlphaLevel(reverse): the triangle 1 - |1 - 2 t / total| (not clamped), or its complement
        LuaReg.method(A.M_AnimationTracker_GetAlphaLevel, LuaMethod {
            val t = tr(1)
            val time = n(t, A.A_AnimationTracker_time).toFloat()
            val ct = n(t, A.A_AnimationTracker_current_time).toFloat()
            val a = 1f - Math.abs(1f - 2f * (if (time == 0f) 0f else ct / time))
            ret.num(if (arg.bool(2)) 1f - a else a)
        })
        LuaReg.method(A.M_Animation_AddSoundQueue, LuaMethod { 0 })
    }
}
