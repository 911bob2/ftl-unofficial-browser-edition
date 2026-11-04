// Ported from FTL-Hyperspace (GPL-3.0-or-later) commit 1b05a07 (lua/ script API and .i members, src/ files named below),
// not from the 1.23.2 tag.
package xyz.znix.xftl.hs.lua

import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.Image
import xyz.znix.xftl.rendering.NativeFonts
import xyz.znix.xftl.hs.lua.LuaApi as A
import xyz.znix.xftl.hs.lua.LuaArgs as arg
import xyz.znix.xftl.hs.lua.LuaPush as ret

/** FTL's GL_Texture as Lua sees it: one object per image path (ResourceControl caches its textures). */
class LuaTexture(@JvmField var image: Image?, @JvmField val path: String) : LuaTyped {
    override val luaType get() = A.T_GL_Texture
    @JvmField var width = image?.width ?: 0
    @JvmField var height = image?.height ?: 0
    /** False when looked up before the game content existed (scripts load first): [LuaGraphics.resolve] retries. */
    @JvmField var resolved = true
}

/**
 * FTL's GL_Primitive for an image quad (the only kind MV creates): drawn in the frame current at render time.
 * [x], [y], [w], [h] in pixels, [rotation] in degrees around the quad's centre, [mirror] flips horizontally.
 */
class LuaPrimitive(
    @JvmField var texture: LuaTexture?, @JvmField val x: Float, @JvmField val y: Float, @JvmField var w: Float, @JvmField var h: Float,
    @JvmField val rotation: Float, @JvmField val mirror: Boolean, @JvmField val color: GlColorValue,
) : LuaTyped {
    override val luaType get() = A.T_GL_Primitive
    /** FTL's primitives are all triangle fans (GL type 6): oracle type of rect, outline, pixel and image primitives. */
    @JvmField var type = 6
    @JvmField var lineWidth = 0f
    @JvmField var textureAntialias = false
    @JvmField var destroyed = false
    @JvmField var id = nextId++
    /** A script's write to hasTexture / hasColor (informational fields: drawing follows the texture and colour themselves). */
    @JvmField var hasTextureSet: Boolean? = null
    @JvmField var hasColorSet: Boolean? = null
    /** hasColor from the colour the primitive was made with: false for an opaque white one (oracle: CreateImagePrimitive white = false, tinted = true). */
    @JvmField val hasColorInit: Boolean = !(color.r == 1f && color.g == 1f && color.b == 1f && color.a == 1f)
    /** A non-image primitive (GL_CreateRectPrimitive and the like): draws itself with the render colour (LuaAdaptersWP7Render). */
    @JvmField var shape: ((Graphics, Colour) -> Unit)? = null

    companion object { private var nextId = 1 }
}

/**
 * Graphics.CSurface, Graphics.freetype and Hyperspace.Resources (ResourceControl) members on our renderer. They draw
 * only while a render callback runs ([HsLua.graphics] is the frame's Graphics); state changes (matrix, colour) follow
 * the same push/pop discipline as FTL's, and each render event restores the matrix depth afterwards.
 */
object LuaGraphics {
    private val textures = HashMap<String, LuaTexture>()
    private val tint = Colour(1f, 1f, 1f, 1f)
    private val fill = Colour(1f, 1f, 1f, 1f)
    /** CSurface::GL_SetColor: the colour freetype prints with. */
    @JvmField val current = GlColorValue(1f, 1f, 1f, 1f)
    private val fonts = HashMap<Int, NativeFonts.NativeFont>()

    private var missingTexture: LuaTexture? = null

    fun reset() { textures.clear(); fonts.clear(); current.r = 1f; current.g = 1f; current.b = 1f; current.a = 1f }

    /**
     * The game content was rebuilt (the tutorial and a run switch Advanced Edition) and the old one's images freed: the
     * textures the scripts hold (MV's speed buttons, the tutorial's Continue base) take the new content's images on
     * their next use, as FTL's ResourceControl keeps one texture per image for the whole session.
     */
    @JvmStatic
    fun contentReplaced() {
        for (t in textures.values) if (t !== missingTexture) t.resolved = false
    }

    /**
     * ResourceControl::GetImageId: `img/<dir>`, cached per path. A missing image is FTL's shared 25x22 placeholder texture (oracle img-missing:
     * every missing name reads 25x22 and two missing names are the same texture); it has no pixels here.
     */
    @JvmStatic
    fun texture(dir: String): LuaTexture {
        textures[dir]?.let { if (!it.resolved) resolve(it); return it }
        val content = HsLua.game?.gameContent
        val path = "img/$dir"
        val image = if (content != null && exists(content, path)) content.getImg(path) else null
        if (image == null && content != null) {
            HS_missing(dir)
            val m = missingTexture ?: LuaTexture(null, "?missing").also { it.width = 25; it.height = 22; missingTexture = it }
            textures[dir] = m
            return m
        }
        val t = LuaTexture(image, dir)
        t.resolved = content != null
        textures[dir] = t
        return t
    }

    /**
     * A texture looked up while the content was not loaded yet (module-level code of the session's scripts, which
     * run before the content: MV's speed buttons) gets its image, width and height once the content exists, as
     * FTL's ResourceControl had them when HS loaded the scripts (render workstream fix).
     */
    @JvmStatic
    fun resolve(t: LuaTexture) {
        if (t.resolved) return
        val content = HsLua.game?.gameContent ?: return
        t.resolved = true
        val path = "img/${t.path}"
        val image = if (exists(content, path)) content.getImg(path) else null
        if (image == null) { HS_missing(t.path); t.width = 25; t.height = 22; return }
        t.image = image; t.width = image.width; t.height = image.height
    }

    /**
     * The image is in the data (GameContent.hasImage only says whether it is loaded already; render workstream
     * fix: images not drawn yet, such as all of MV's own, read as missing).
     */
    private fun exists(content: xyz.znix.xftl.game.InGameState.GameContent, path: String) =
        content.hasImage(path) || content.datafile.getOrNull(path) != null

    private fun HS_missing(dir: String) = xyz.znix.xftl.hs.HS.unsupported("lua", "ResourceControl.GetImageId", "missing image img/$dir")

    fun colour(target: Colour, c: GlColorValue?, alpha: Float = 1f): Colour {
        if (c == null) { target.r = 0f; target.g = 0f; target.b = 0f; target.a = 0f; return target }
        target.r = c.r; target.g = c.g; target.b = c.b; target.a = c.a * alpha
        return target
    }

    /** True while Lua's rectangles are queued in the engine's quad batch (see [flushQuads]). */
    @JvmField var batching = false

    /**
     * Each FTL GL_DrawRect is its own draw call; here consecutive rectangles share one: the quad batch stays open
     * until anything else draws (images, text) or the event's callbacks return, so the draw order is unchanged.
     */
    @JvmStatic
    fun flushQuads() {
        if (!batching) return
        batching = false
        HsLua.graphics?.nestedDisableBatching()
    }

    fun beginQuads(g: Graphics) {
        if (!batching) { g.nestedEnableBatching(); batching = true }
    }

    fun drawImage(g: Graphics, t: LuaTexture?, x: Float, y: Float, w: Float, h: Float, rotation: Float, mirror: Boolean, c: Colour) {
        if (t != null && !t.resolved) resolve(t)
        val img = t?.image ?: return
        flushQuads()
        val rotated = rotation != 0f
        if (rotated) { g.pushTransform(); g.rotate(x + w / 2f, y + h / 2f, rotation) }
        val iw = img.width.toFloat(); val ih = img.height.toFloat()
        if (mirror) img.draw(x, y, x + w, y + h, iw, 0f, 0f, ih, 1f, c)
        else img.draw(x, y, x + w, y + h, 0f, 0f, iw, ih, 1f, c)
        if (rotated) g.popTransform()
    }

    /** Draws [t] at its own size, untinted (Button::OnRender). */
    @JvmStatic
    fun draw(t: LuaTexture, x: Float, y: Float) {
        val g = HsLua.graphics ?: return
        tint.r = 1f; tint.g = 1f; tint.b = 1f; tint.a = 1f
        drawImage(g, t, x, y, t.width.toFloat(), t.height.toFloat(), 0f, false, tint)
    }

    /** For engine call sites (the star map's replaced art): GL_RenderPrimitiveWithColor, or with [c] null GL_RenderPrimitive. */
    @JvmStatic
    fun renderPrimitive(p: LuaPrimitive, c: Colour?) { render(p, c ?: colour(tint, p.color)); flushQuads() }

    private fun render(p: LuaPrimitive, c: Colour) {
        if (p.destroyed) return
        val g = HsLua.graphics ?: return
        p.shape?.let { flushQuads(); it(g, c); return }
        // A primitive sized from a texture that was not resolved yet takes the texture's size once it is.
        val t = p.texture
        if (t != null && p.w == 0f && p.h == 0f) {
            if (!t.resolved) resolve(t)
            if (t.width > 0) { p.w = t.width.toFloat(); p.h = t.height.toFloat() }
        }
        drawImage(g, p.texture, p.x, p.y, p.w, p.h, p.rotation, p.mirror, c)
    }

    private fun font(id: Int): NativeFonts.NativeFont? {
        fonts[id]?.let { return it }
        val content = HsLua.game?.gameContent ?: return null
        val f = NativeFonts.font({ content.getFont(it) }, id)
        fonts[id] = f
        return f
    }

    private fun textColour(): Colour = colour(fill, current)

    /** Matrix pushes made by Lua inside one render callback stage; unbalanced ones are popped by [restore]. */
    @JvmField var pushed = 0

    /** After a render event's callbacks: undo pushes a script left open (FTL would corrupt its stack instead). */
    @JvmStatic
    fun restore() {
        flushQuads()
        LuaAdaptersSweepUi.resetAfterEvent()
        val g = HsLua.graphics ?: run { pushed = 0; return }
        while (pushed > 0) { g.popTransform(); pushed-- }
    }

    fun install() {
        // textures
        LuaReg.num(A.A_GL_Texture_width, NumGet { (it as LuaTexture).width.toDouble() }, NumSet { o, v -> (o as LuaTexture).width = v.toInt() })
        LuaReg.num(A.A_GL_Texture_height, NumGet { (it as LuaTexture).height.toDouble() }, NumSet { o, v -> (o as LuaTexture).height = v.toInt() })
        LuaReg.method(A.M_ResourceControl_GetImageId, LuaMethod { ret.obj(texture(arg.str(2) ?: ""), A.T_GL_Texture) })
        LuaReg.method(A.M_ResourceControl_ImageExists, LuaMethod {
            val content = HsLua.game?.gameContent
            ret.bool(content != null && exists(content, "img/" + (arg.str(2) ?: "")))
        })
        // primitives
        LuaReg.bool(A.A_GL_Primitive_textureAntialias, { (it as LuaPrimitive).textureAntialias }, { o, v -> (o as LuaPrimitive).textureAntialias = v })
        LuaReg.bool(A.A_GL_Primitive_hasTexture, { (it as LuaPrimitive).let { p -> p.hasTextureSet ?: (p.texture != null) } }, { o, v -> (o as LuaPrimitive).hasTextureSet = v })
        LuaReg.bool(A.A_GL_Primitive_hasColor, { (it as LuaPrimitive).let { p -> p.hasColorSet ?: p.hasColorInit } }, { o, v -> (o as LuaPrimitive).hasColorSet = v })
        // GL_Primitive::texture / color are plain members: a write replaces what GL_RenderPrimitive binds and tints with (a Color is copied)
        LuaReg.obj(A.A_GL_Primitive_texture, ObjGet { (it as LuaPrimitive).texture }, ObjSet { o, v -> (o as LuaPrimitive).texture = v as? LuaTexture })
        LuaReg.obj(A.A_GL_Primitive_color, ObjGet { (it as LuaPrimitive).color }, ObjSet { o, v ->
            val c = (o as LuaPrimitive).color; val s = v as? GlColorValue
            c.r = s?.r ?: 0f; c.g = s?.g ?: 0f; c.b = s?.b ?: 0f; c.a = s?.a ?: 0f
        })
        LuaReg.num(A.A_GL_Primitive_id, NumGet { (it as LuaPrimitive).id.toDouble() }, NumSet { o, v -> (o as LuaPrimitive).id = v.toInt() })
        LuaReg.num(A.A_GL_Primitive_type, NumGet { (it as LuaPrimitive).type.toDouble() }, NumSet { o, v -> (o as LuaPrimitive).type = v.toInt() })
        LuaReg.num(A.A_GL_Primitive_lineWidth, NumGet { (it as LuaPrimitive).lineWidth.toDouble() }, NumSet { o, v -> (o as LuaPrimitive).lineWidth = v.toFloat() })
        // ResourceControl::CreateImagePrimitive(tex, x, y, rotation, color, alpha, mirror): the texture's own size
        LuaReg.method(A.M_ResourceControl_CreateImagePrimitive, LuaMethod {
            val t = arg.obj(2) as? LuaTexture
            ret.obj(imagePrimitive(t, arg.int(3).toFloat(), arg.int(4).toFloat(), arg.int(5).toFloat(), arg.obj(6) as? GlColorValue, arg.float(7), arg.bool(8)), A.T_GL_Primitive)
        })
        LuaReg.method(A.M_ResourceControl_CreateImagePrimitiveString, LuaMethod {
            val t = texture(arg.str(2) ?: "")
            ret.obj(imagePrimitive(t, arg.int(3).toFloat(), arg.int(4).toFloat(), arg.int(5).toFloat(), arg.obj(6) as? GlColorValue, arg.float(7), arg.bool(8)), A.T_GL_Primitive)
        })
        LuaReg.method(A.M_ResourceControl_RenderImage, LuaMethod {
            val t = arg.obj(2) as? LuaTexture
            val g = HsLua.graphics
            if (g != null && t != null) drawImage(g, t, arg.int(3).toFloat(), arg.int(4).toFloat(), t.width.toFloat(), t.height.toFloat(), arg.int(5).toFloat(), arg.bool(8), colour(tint, arg.obj(6) as? GlColorValue, arg.float(7)))
            ret.num(0)
        })
        LuaReg.method(A.M_ResourceControl_RenderImageString, LuaMethod {
            val t = texture(arg.str(2) ?: "")
            val g = HsLua.graphics
            if (g != null) drawImage(g, t, arg.int(3).toFloat(), arg.int(4).toFloat(), t.width.toFloat(), t.height.toFloat(), arg.int(5).toFloat(), arg.bool(8), colour(tint, arg.obj(6) as? GlColorValue, arg.float(7)))
            ret.num(0)
        })
        // CSurface::GL_CreateImagePrimitive(tex, x, y, size_x, size_y, rotate, color)
        LuaReg.method(A.M_CSurface_GL_CreateImagePrimitive, LuaMethod {
            val c = arg.obj(7) as? GlColorValue
            ret.obj(LuaPrimitive(arg.obj(1) as? LuaTexture, arg.float(2), arg.float(3), arg.float(4), arg.float(5), arg.float(6), false,
                GlColorValue(c?.r ?: 0f, c?.g ?: 0f, c?.b ?: 0f, c?.a ?: 0f)), A.T_GL_Primitive)
        })
        LuaReg.method(A.M_CSurface_GL_DestroyPrimitive, LuaMethod { (arg.obj(1) as? LuaPrimitive)?.destroyed = true; 0 })
        LuaReg.method(A.M_CSurface_GL_RenderPrimitive, LuaMethod { val p = arg.obj(1) as LuaPrimitive; render(p, colour(tint, p.color)); 0 })
        LuaReg.method(A.M_CSurface_GL_RenderPrimitiveWithAlpha, LuaMethod { val p = arg.obj(1) as LuaPrimitive; render(p, colour(tint, p.color, arg.float(2))); 0 })
        LuaReg.method(A.M_CSurface_GL_RenderPrimitiveWithColor, LuaMethod { val p = arg.obj(1) as LuaPrimitive; render(p, colour(tint, arg.obj(2) as? GlColorValue)); 0 })
        LuaReg.method(A.M_CSurface_GL_BlitImage, LuaMethod {
            val g = HsLua.graphics
            if (g != null) drawImage(g, arg.obj(1) as? LuaTexture, arg.float(2), arg.float(3), arg.float(4), arg.float(5), arg.float(6), arg.bool(8), colour(tint, arg.obj(7) as? GlColorValue))
            ret.bool(true)
        })
        // matrix and colour state
        LuaReg.method(A.M_CSurface_GL_PushMatrix, LuaMethod { HsLua.graphics?.let { it.pushTransform(); pushed++ }; ret.num(0) })
        LuaReg.method(A.M_CSurface_GL_PopMatrix, LuaMethod { HsLua.graphics?.let { if (pushed > 0) { it.popTransform(); pushed-- } }; ret.num(0) })
        LuaReg.method(A.M_CSurface_GL_Translate__SWIG_0, LuaMethod { HsLua.graphics?.translate(arg.float(1), arg.float(2)); ret.bool(true) })
        LuaReg.method(A.M_CSurface_GL_Translate__SWIG_1, LuaMethod { HsLua.graphics?.translate(arg.float(1), arg.float(2)); ret.bool(true) })
        LuaReg.method(A.M_CSurface_GL_Scale, LuaMethod { HsLua.graphics?.scale(arg.float(1), arg.float(2)); 0 })
        LuaReg.method(A.M_CSurface_GL_Rotate__SWIG_0, LuaMethod { if (arg.float(4) != 0f) HsLua.graphics?.rotate(0f, 0f, arg.float(1) * Math.signum(arg.float(4))); 0 })
        LuaReg.method(A.M_CSurface_GL_Rotate__SWIG_1, LuaMethod { HsLua.graphics?.rotate(0f, 0f, arg.float(1)); 0 })
        LuaReg.method(A.M_CSurface_GL_SetColor, LuaMethod {
            val c = arg.obj(1) as? GlColorValue
            current.r = c?.r ?: 0f; current.g = c?.g ?: 0f; current.b = c?.b ?: 0f; current.a = c?.a ?: 0f
            ret.num(0)
        })
        LuaReg.method(A.M_CSurface_GL_DrawRect, LuaMethod {
            val g = HsLua.graphics ?: return@LuaMethod ret.bool(true)
            beginQuads(g)
            val prev = g.colour
            g.colour = colour(fill, arg.obj(5) as? GlColorValue)
            g.fillRect(arg.float(1), arg.float(2), arg.float(3), arg.float(4))
            g.colour = prev
            ret.bool(true)
        })
        LuaReg.method(A.M_CSurface_GL_DrawRectOutline, LuaMethod {
            val g = HsLua.graphics ?: return@LuaMethod ret.bool(true)
            val x = arg.int(1).toFloat(); val y = arg.int(2).toFloat(); val w = arg.int(3).toFloat(); val h = arg.int(4).toFloat()
            val lw = arg.float(6)
            beginQuads(g)
            val prev = g.colour
            g.colour = colour(fill, arg.obj(5) as? GlColorValue)
            g.fillRect(x, y, w, lw); g.fillRect(x, y + h - lw, w, lw)
            g.fillRect(x, y + lw, lw, h - 2 * lw); g.fillRect(x + w - lw, y + lw, lw, h - 2 * lw)
            g.colour = prev
            ret.bool(true)
        })
        // freetype printers: return the pen position after the text (x of the last line's end, y of its top)
        LuaReg.method(A.M_freetype_easy_print, LuaMethod { print(0) })
        LuaReg.method(A.M_freetype_easy_printCenter, LuaMethod { print(1) })
        LuaReg.method(A.M_freetype_easy_printRightAlign, LuaMethod { print(2) })
        LuaReg.method(A.M_freetype_easy_printAutoNewlines, LuaMethod { printWrapped(false, true) })
        LuaReg.method(A.M_freetype_easy_printNewlinesCentered, LuaMethod { printWrapped(true, true) })
        LuaReg.method(A.M_freetype_easy_measurePrintLines, LuaMethod { printWrapped(false, false) })
        // easy_measureWidth and easy_measurePrintLines take each line's extents (the font's vtable
        // entry for them: leftmost to rightmost pixel), not where its pen ends; a character the font lacks adds nothing.
        LuaReg.method(A.M_freetype_easy_measureWidth, LuaMethod { ret.num(font(arg.int(1))?.measure(arg.str(2) ?: "") ?: 0) })
    }

    /** freetype::easy_printAutoNewlines in [colour] (PrintHelper::Render). */
    @JvmStatic
    fun printAutoNewlines(fontId: Int, x: Float, y: Float, width: Int, text: String, colour: Colour) {
        val f = font(fontId) ?: return
        if (HsLua.graphics == null) return
        flushQuads()
        val lines = text.split('\n').flatMap { if (it.isEmpty()) listOf("") else f.font.wrapString(it, width) }
        lines.forEachIndexed { i, line -> f.print(x, y + i * f.lineHeight, line, colour) }
    }

    private fun imagePrimitive(t: LuaTexture?, x: Float, y: Float, rotation: Float, c: GlColorValue?, alpha: Float, mirror: Boolean) =
        LuaPrimitive(t, x, y, (t?.width ?: 0).toFloat(), (t?.height ?: 0).toFloat(), rotation, mirror,
            GlColorValue(c?.r ?: 0f, c?.g ?: 0f, c?.b ?: 0f, (c?.a ?: 0f) * alpha))

    private fun print(align: Int): Int {
        val f = font(arg.int(1)); val x = arg.float(2); val y = arg.float(3); val text = arg.str(4) ?: ""
        if (f == null) return ret.obj(PointfValue(x, y), A.T_Pointf)
        val col = textColour()
        flushQuads()
        if (HsLua.graphics != null) when (align) {
            0 -> f.print(x, y, text, col)
            1 -> f.printCenter(x, y, text, col)
            else -> f.printRight(x, y, text, col)
        }
        val lines = text.split('\n')
        val last = lines.last()
        val endX = when (align) { 0 -> x + f.advance(last); 1 -> x + f.advance(last) / 2f; else -> x }
        return ret.obj(PointfValue(endX, y + (lines.size - 1) * f.lineHeight), A.T_Pointf)
    }

    private fun printWrapped(centred: Boolean, draw: Boolean): Int {
        val f = font(arg.int(1)); val x = arg.float(2); val y = arg.float(3); val width = arg.int(4); val text = arg.str(5) ?: ""
        if (f == null) return ret.obj(PointfValue(x, y), A.T_Pointf)
        val lines = text.split('\n').flatMap { if (it.isEmpty()) listOf("") else f.font.wrapString(it, width) }
        if (draw && HsLua.graphics != null) {
            flushQuads()
            val col = textColour()
            lines.forEachIndexed { i, line ->
                val ly = y + i * f.lineHeight
                if (centred) f.printCenter(x + width / 2f, ly, line, col) else f.print(x, ly, line, col)
            }
        }
        var w = 0f
        for (l in lines) w = maxOf(w, f.measure(l).toFloat())
        return ret.obj(PointfValue(if (draw) x + w else w, if (draw) y + lines.size * f.lineHeight else (lines.size * f.lineHeight).toFloat()), A.T_Pointf)
    }
}
