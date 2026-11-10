// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 (lua/modules/hyperspace.i and graphics.i members, the wiki's
// Lua-Hyperspace-Module and Lua-Graphics-Module) and from FTL 1.6.14's own behaviour; verified against the oracle
// rows of tools/hs-oracle/tracer/data/hs_oracle/sweep-ui.lua.
package xyz.znix.xftl.hs.lua

import org.lwjgl.opengl.GL11
import xyz.znix.xftl.Constants
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.lua.LuaApi as A
import xyz.znix.xftl.hs.lua.LuaArgs as arg
import xyz.znix.xftl.hs.lua.LuaPush as ret
import xyz.znix.xftl.rendering.Colour

/** The three images (GL_Texture*[3]) or primitives (GL_Primitive*[3]) of a Button, as `Button.images` / `.primitives`. */
class LuaButtonArray(val type: Int, val owner: LuaStruct) : LuaTyped { override val luaType get() = type }

/**
 * Round 14 slice C (UI and drawing): the Lua members the earlier work packages left out. Buttons made with `new`
 * (GenericButton/Button/TextButton fields and the Button image/primitive arrays), the GL_* state calls of CSurface on
 * the WebGL layer, WindowFrame drawing, Settings hotkeys, SoundControl mixes, CApp exit, Animation strips and the
 * armed-weapon members of WeaponControl.
 */
object LuaAdaptersSweepUi {
    // ---------------------------------------------------------------------------------------------- helpers

    private val methodIds: HashMap<String, ArrayList<Int>> by lazy {
        HashMap<String, ArrayList<Int>>().also { m -> for (i in 1..A.METHOD_COUNT) m.getOrPut(A.methodName(i)) { ArrayList() }.add(i) }
    }
    private val attrIds: HashMap<String, Int> by lazy {
        HashMap<String, Int>().also { m -> for (a in 1..A.ATTR_COUNT) m[A.attrName(a)] = a }
    }
    private fun attr(name: String): Int = attrIds[name] ?: error("no attribute $name")

    /** Registers [f] for every overload of [name]. */
    private fun method(name: String, f: LuaMethod) {
        for (id in methodIds[name] ?: error("no method $name")) LuaReg.method(id, f)
    }

    /** Registers [f] for the owners [mine] accepts, in front of whatever was registered before. */
    private fun methodFor(name: String, mine: (Any?) -> Boolean, f: LuaMethod) {
        for (id in methodIds[name] ?: return) {
            val prev = LuaReg.methods[id]
            LuaReg.method(id, LuaMethod { n ->
                if (mine(arg.obj(1))) f.call(n)
                else (prev ?: throw LuaError("Hyperspace Lua API not available in the browser engine: $name")).call(n)
            })
        }
    }

    private fun isButtonType(t: Int) = t == A.T_Button || t == A.T_FTLButton || t == A.T_TextButton || t == A.T_GenericButton
    private fun isButton(o: Any?) = o is LuaStruct && isButtonType(o.luaType)

    private fun num(s: LuaStruct, a: Int): Double = (s.values[a] as? Double) ?: 0.0
    private fun flag(s: LuaStruct, a: Int): Boolean = s.values[a] == true
    private fun sub(s: LuaStruct, a: Int) = LuaStructs.member(s, a) as LuaStruct

    @JvmStatic
    fun install() {
        glState.reset(); lastPositionHeld = null; slotOverride = null; weaponIsSet = false; channels.clear(); nextChannel = 0x1000001
        LuaStructs.register(A.T_WindowFrame)
        tutorialArrow()
        buttons()
        textButton()
        windowFrame()
        csurface()
        appAndSettings()
        sounds()
        resources()
        animation()
        weaponControl()
    }

    // --------------------------------------------------------------------------------------------- buttons

    /** Called when the mouse moves (CApp::OnMouseMove): MouseControl::lastPosition is held no longer. */
    @JvmStatic
    fun mouseMoved() { lastPositionHeld = null }

    private var lastPositionHeld: PointValue? = null

    private fun imagesOf(b: LuaStruct) = sub(b, A.A_Button_images)
    private fun primitivesOf(b: LuaStruct) = sub(b, A.A_Button_primitives)

    /** Button's ResetPrimitives: the three primitives are destroyed; OnRender builds them again. */
    private fun resetPrimitives(b: LuaStruct) {
        val p = primitivesOf(b)
        for (i in 0..2) { (p.values[i] as? LuaPrimitive)?.destroyed = true; p.values.remove(i) }
    }

    /** Hit test of GenericButton::MouseMove: strictly inside the hit box. */
    private fun inside(b: LuaStruct, x: Int, y: Int): Boolean {
        val r = LuaStructs.fields(sub(b, A.A_GenericButton_hitbox))
        return r[0] < x && x < r[0] + r[2] && r[1] < y && y < r[1] + r[3]
    }

    private fun buttons() {
        // Button() (GenericButton's constructor): position at INT_MIN, an empty hit box (the oracle's TextButton() rows; Button() reads INT_MIN too)
        LuaReg.method(A.M_new_Button, LuaMethod {
            val b = LuaStructs.create(A.T_Button)
            LuaStructs.setFields(sub(b, A.A_GenericButton_position), Int.MIN_VALUE.toDouble(), Int.MIN_VALUE.toDouble())
            LuaStructs.setFields(sub(b, A.A_GenericButton_hitbox), -1.0, -1.0, 0.0, 0.0)
            ret.obj(b, A.T_Button)
        })
        // GenericButton::MouseMove(x, y, silent): bHover follows the hit box
        for (cls in listOf("GenericButton", "Button", "FTLButton", "TextButton")) {
            if (methodIds.containsKey("$cls.MouseMove")) methodFor("$cls.MouseMove", ::isButton, LuaMethod {
                val b = arg.obj(1) as LuaStruct
                b.values[A.A_GenericButton_bHover] = inside(b, arg.int(2), arg.int(3)); 0
            })
            // GenericButton::Reset: hover, activated and selected off, no active touch
            if (methodIds.containsKey("$cls.Reset")) methodFor("$cls.Reset", ::isButton, LuaMethod {
                val b = arg.obj(1) as LuaStruct
                b.values[A.A_GenericButton_bHover] = false; b.values[A.A_GenericButton_bActivated] = false
                b.values[A.A_GenericButton_bSelected] = false; b.values[A.A_GenericButton_activeTouch] = 0.0; 0
            })
            // GenericButton::SetLocation(Point): nothing when unchanged; the hit box moves with the position
            if (methodIds.containsKey("$cls.SetLocation")) methodFor("$cls.SetLocation", ::isButton, LuaMethod {
                val b = arg.obj(1) as LuaStruct
                val p = arg.obj(2) as? PointValue ?: throw LuaError("Point expected")
                val pos = sub(b, A.A_GenericButton_position); val old = LuaStructs.fields(pos)
                if (old[0].toInt() == p.x && old[1].toInt() == p.y) return@LuaMethod 0
                val hit = sub(b, A.A_GenericButton_hitbox); val h = LuaStructs.fields(hit)
                LuaStructs.setFields(hit, h[0] + (p.x - old[0].toInt()), h[1] + (p.y - old[1].toInt()), h[2], h[3])
                LuaStructs.setFields(pos, p.x.toDouble(), p.y.toDouble())
                resetPrimitives(b)
                0
            })
            if (methodIds.containsKey("$cls.ResetPrimitives")) methodFor("$cls.ResetPrimitives", ::isButton, LuaMethod { resetPrimitives(arg.obj(1) as LuaStruct); 0 })
        }
        // Button::OnInit, both overloads: the three images, position and image size from the first rect, the hit box
        // from the second (or the image's size at the point for the short form), primitives destroyed
        fun init(b: LuaStruct, base: String, offUsesOn: Boolean, px: Int, py: Int, iw: Int, ih: Int, hit: DoubleArray?) {
            val w = imagesOf(b)
            w.values[0] = LuaGraphics.texture(base + if (offUsesOn) "_on.png" else "_off.png")
            w.values[1] = LuaGraphics.texture("${base}_on.png")
            w.values[2] = LuaGraphics.texture("${base}_select2.png")
            LuaStructs.setFields(sub(b, A.A_GenericButton_position), px.toDouble(), py.toDouble())
            LuaStructs.setFields(sub(b, A.A_Button_imageSize), iw.toDouble(), ih.toDouble())
            LuaStructs.setFields(sub(b, A.A_GenericButton_hitbox), *(hit ?: doubleArrayOf(px.toDouble(), py.toDouble(), iw.toDouble(), ih.toDouble())))
            resetPrimitives(b)
        }
        LuaReg.method(A.M_Button_OnInit__SWIG_0, LuaMethod {
            val img = LuaStructs.fields(arg.obj(3) as LuaStruct); val hb = LuaStructs.fields(arg.obj(4) as LuaStruct)
            init(arg.obj(1) as LuaStruct, arg.str(2) ?: "", arg.bool(5), img[0].toInt(), img[1].toInt(), img[2].toInt(), img[3].toInt(), hb)
            0
        })
        LuaReg.method(A.M_Button_OnInit__SWIG_1, LuaMethod {
            val b = arg.obj(1) as LuaStruct
            val base = arg.str(2) ?: ""
            val p = arg.obj(3) as? PointValue ?: if (!arg.isNil(3) && !arg.isNil(4)) PointValue(arg.int(3), arg.int(4)) else null
            val off = LuaGraphics.texture("${base}_off.png")
            init(b, base, false, p?.x ?: 0, p?.y ?: 0, off.width, off.height, null)
            0
        })
        // Button::SetImageBase(base): the three images, primitives reset; SetInactiveImage / SetActiveImage replace one
        methodFor("Button.SetImageBase", ::isButton, LuaMethod {
            val b = arg.obj(1) as LuaStruct; val base = arg.str(2) ?: ""; val w = imagesOf(b)
            w.values[0] = LuaGraphics.texture(base + "_off.png"); w.values[1] = LuaGraphics.texture(base + "_on.png")
            w.values[2] = LuaGraphics.texture(base + "_select2.png"); resetPrimitives(b); 0
        })
        methodFor("Button.SetInactiveImage", ::isButton, LuaMethod {
            val b = arg.obj(1) as LuaStruct; val t = arg.obj(2) as? LuaTexture; val w = imagesOf(b)
            if (w.values[0] !== t) { w.values[0] = t; resetPrimitives(b) }; 0
        })
        methodFor("Button.SetActiveImage", ::isButton, LuaMethod {
            val b = arg.obj(1) as LuaStruct; val t = arg.obj(2) as? LuaTexture; val w = imagesOf(b)
            if (w.values[1] !== t) { w.values[1] = t; resetPrimitives(b) }; 0
        })
        // Button::OnRender: primitives are made from the images when the first one is missing; the image drawn is
        // off while bRenderOff, select2 while bRenderSelected, off while inactive, then select2 while selected or hovered,
        // otherwise on
        methodFor("Button.OnRender", { o -> o is LuaStruct && o.luaType == A.T_Button || o is LuaStruct && o.luaType == A.T_FTLButton }, LuaMethod {
            val b = arg.obj(1) as LuaStruct
            val prims = primitivesOf(b); val imgs = imagesOf(b)
            if (prims.values[0] == null) {
                val p = LuaStructs.fields(sub(b, A.A_GenericButton_position)); val s = LuaStructs.fields(sub(b, A.A_Button_imageSize))
                for (i in 0..2) prims.values[i] = LuaPrimitive(imgs.values[i] as? LuaTexture, p[0].toFloat(), p[1].toFloat(), s[0].toFloat(), s[1].toFloat(),
                    0f, flag(b, A.A_Button_bMirror), GlColorValue(1f, 1f, 1f, 1f))
            }
            val idx = when {
                flag(b, A.A_GenericButton_bRenderOff) -> 0
                flag(b, A.A_GenericButton_bRenderSelected) -> 2
                !flag(b, A.A_GenericButton_bActive) -> 0
                flag(b, A.A_GenericButton_bSelected) -> 2
                flag(b, A.A_GenericButton_bHover) -> 2
                else -> 1
            }
            (prims.values[idx] as? LuaPrimitive)?.takeIf { it.texture != null }?.let { LuaGraphics.renderPrimitive(it, null) }
            0
        })
        // Button.images / .primitives (GL_Texture*[3] / GL_Primitive*[3] wrappers): indexed views of the button's arrays
        for ((a, type) in listOf(A.A_Button_images to A.T_GL_Texture_Pointer_Array_Size_3_Wrapper, A.A_Button_primitives to A.T_GL_Primitive_Pointer_Array_Size_3_Wrapper)) {
            val prevG = LuaReg.objGet[a]
            LuaReg.objGet[a] = ObjGet { o -> if (o is LuaStruct) LuaButtonArray(type, LuaStructs.member(o, a) as LuaStruct) else prevG?.get(o) }
            LuaReg.objSet[a] = ObjSet { _, _ -> throw LuaError("This variable is immutable") }
        }
        fun index(): Int {
            val i = arg.num(2)
            if (i < 0) throw LuaError("number must not be negative")
            if (i.toLong() >= 3) throw LuaError("SWIG_IndexError:Index out of range")
            return i.toInt()
        }
        LuaReg.method(A.M_GL_Texture_Pointer_Array_Size_3_Wrapper___getitem, LuaMethod {
            val w = arg.obj(1) as LuaButtonArray; ret.obj(w.owner.values[index()], A.T_GL_Texture)
        })
        LuaReg.method(A.M_GL_Texture_Pointer_Array_Size_3_Wrapper___setitem, LuaMethod {
            val w = arg.obj(1) as LuaButtonArray; w.owner.values[index()] = arg.obj(3) as? LuaTexture; 0
        })
        for ((name, id) in listOf("GL_Primitive_Pointer_Array_Size_3_Wrapper.__getitem" to A.M_GL_Primitive_Pointer_Array_Size_3_Wrapper___getitem,
            "GL_Primitive_Pointer_Array_Size_3_Wrapper.__setitem" to A.M_GL_Primitive_Pointer_Array_Size_3_Wrapper___setitem)) {
            val prev = LuaReg.methods[id]
            LuaReg.method(id, LuaMethod { n ->
                val w = arg.obj(1)
                if (w is LuaButtonArray) {
                    if (name.endsWith("__getitem")) ret.obj(w.owner.values[index()], A.T_GL_Primitive)
                    else { w.owner.values[index()] = arg.obj(3) as? LuaPrimitive; 0 }
                } else (prev ?: throw LuaError("Hyperspace Lua API not available in the browser engine: $name")).call(n)
            })
        }
    }

    // ---------------------------------------------------------------------------------------------- TextButton

    private const val TB_SIZE = -20
    private const val TB_INSET = -21
    private const val TB_LABEL = -22
    private const val TB_FONT = -23

    /**
     * TextButton::OnInit(Point pos, Point size, int cornerInset, TextString *label, int font): the button at [pos] with the
     * hit box of its size; OnRender draws the chamfered fill (inactive, selected or normal colour) and the centred label.
     */
    private val TB_BLUE = Colour(58 / 255f, 127 / 255f, 1f, 1f)
    private val TB_DISABLED = Colour(160 / 255f, 163 / 255f, 171 / 255f, 0.8f)
    private val TB_TEXT = Colour(23 / 255f, 44 / 255f, 62 / 255f, 1f)

    private fun textButton() {
        val own = { o: Any? -> o is LuaStruct && o.luaType == A.T_TextButton }
        methodFor("TextButton.OnInit", own, LuaMethod {
            val b = arg.obj(1) as LuaStruct
            val p = arg.obj(2) as? PointValue ?: throw LuaError("Point expected")
            val s = arg.obj(3) as? PointValue ?: throw LuaError("Point expected")
            LuaStructs.setFields(sub(b, A.A_GenericButton_position), p.x.toDouble(), p.y.toDouble())
            LuaStructs.setFields(sub(b, A.A_GenericButton_hitbox), p.x.toDouble(), p.y.toDouble(), s.x.toDouble(), s.y.toDouble())
            b.values[TB_SIZE] = s; b.values[TB_INSET] = arg.int(4); b.values[TB_LABEL] = arg.obj(5); b.values[TB_FONT] = arg.int(6)
            0
        })
        methodFor("TextButton.OnRender", own, LuaMethod {
            val b = arg.obj(1) as LuaStruct
            val g = HsLua.graphics; val content = HsLua.game?.gameContent
            val size = b.values[TB_SIZE] as? PointValue
            if (g == null || content == null || size == null) return@LuaMethod 0
            val p = LuaStructs.fields(sub(b, A.A_GenericButton_position))
            // TextButton::InitColors leaves a script's button white, blue (58,127,255) while hovered or selected, and a
            // translucent grey (the colour 160,163,171 at 0.8: over black 128,130,137, over white 179,181,188) when not active;
            // the label is (23,44,62) (oracle tbutton, tbutton2)
            val colour = when {
                !flag(b, A.A_GenericButton_bActive) -> TB_DISABLED
                flag(b, A.A_GenericButton_bSelected) || flag(b, A.A_GenericButton_bHover) -> TB_BLUE
                else -> Colour.white
            }
            LuaGraphics.flushQuads()
            val label = (b.values[TB_LABEL] as? LuaStruct)?.let { t ->
                val text = t.values[A.A_TextString_data] as? String ?: ""
                if (t.values[A.A_TextString_isLiteral] == true) text else LuaAdaptersMore.translate(text)
            } ?: ""
            g.colour = colour
            xyz.znix.xftl.game.Buttons.drawChamfer(g, p[0].toInt(), p[1].toInt(), size.x, size.y, (b.values[TB_INSET] as? Int) ?: 0)
            val font = xyz.znix.xftl.rendering.NativeFonts.font({ content.getFont(it) }, (b.values[TB_FONT] as? Int) ?: 0)
            font.printButtonLabel(p[0].toInt(), p[1].toInt(), size.x, size.y, label, TB_TEXT)
            0
        })
    }

    // --------------------------------------------------------------------------------------------- WindowFrame

    private fun windowRenderer() = LuaAdapters.inGame()?.getWindowRenderer()

    /**
     * WindowFrame::Draw(x, y): matrix pushed and translated by (x, y); the mask primitive is written into the stencil
     * (reference and mask 0x80), the pattern drawn where it is set, then the outline. Draw(ref) is the same with
     * [ref] as stencil reference and without the translation, DrawMask(ref) only the first step, and the static
     * Draw(x, y, w, h, ref) makes a frame of that rectangle, draws it with Draw(ref) and frees it.
     */
    private fun windowFrame() {
        // WindowFrame(x, y, w, h): the rect and the three primitives FTL builds (all triangle fans: oracle outline.type 6)
        LuaReg.method(A.M_new_WindowFrame, LuaMethod {
            val f = LuaStructs.create(A.T_WindowFrame)
            LuaStructs.setFields(sub(f, A.A_WindowFrame_rect), arg.int(1).toDouble(), arg.int(2).toDouble(), arg.int(3).toDouble(), arg.int(4).toDouble())
            for (a in intArrayOf(A.A_WindowFrame_mask, A.A_WindowFrame_outline, A.A_WindowFrame_pattern)) {
                val p = LuaPrimitive(null, arg.int(1).toFloat(), arg.int(2).toFloat(), arg.int(3).toFloat(), arg.int(4).toFloat(), 0f, false, GlColorValue(1f, 1f, 1f, 1f))
                p.type = 6; f.values[a] = p
            }
            ret.obj(f, A.T_WindowFrame)
        })
        fun frame(f: LuaStruct, dx: Int, dy: Int, ref: Int, parts: Int) {
            val r = LuaStructs.fields(sub(f, A.A_WindowFrame_rect))
            val wr = windowRenderer() ?: return
            val x = r[0].toInt() + dx; val y = r[1].toInt() + dy; val w = r[2].toInt(); val h = r[3].toInt()
            glState.flush()
            if (parts and 1 != 0) { glState.setStencil(1, ref, ref); wr.drawMask(x, y, w, h); glState.flush(); glState.setStencil(0, 0, 0) }
            if (parts and 2 != 0) { glState.setStencil(2, ref, ref); wr.drawBackground(x, y, w, h); glState.flush(); glState.setStencil(0, 0, 0) }
            if (parts and 4 != 0) { wr.drawOutline(x, y, w, h); glState.flush() }
        }
        val draw = LuaMethod { n ->
            xyz.znix.xftl.game.TouchPointers.panel()
            when (n) {
                2 -> frame(arg.obj(1) as LuaStruct, 0, 0, arg.int(2), 7)
                3 -> frame(arg.obj(1) as LuaStruct, arg.int(2), arg.int(3), 0x80, 7)
                5 -> {
                    val f = LuaStructs.create(A.T_WindowFrame)
                    LuaStructs.setFields(sub(f, A.A_WindowFrame_rect), arg.int(1).toDouble(), arg.int(2).toDouble(), arg.int(3).toDouble(), arg.int(4).toDouble())
                    frame(f, 0, 0, arg.int(5), 7)
                }
                else -> throw LuaError("Wrong arguments for overloaded function 'WindowFrame_Draw'")
            }
            0
        }
        for (id in methodIds["WindowFrame.Draw"] ?: error("no WindowFrame.Draw")) LuaReg.method(id, draw)
        method("WindowFrame.DrawMask", LuaMethod { frame(arg.obj(1) as LuaStruct, 0, 0, arg.int(2), 1); 0 })
    }

    // ------------------------------------------------------------------------------------------------- GL state

    /**
     * CSurface's GL state calls on WebGL. Every change first draws what is queued (batched rectangles, bulk renderers), so
     * it applies to later draws only. The stencil mode and its (ref, mask) are remembered as FTL does (globals at
     *..) for Push/PopStencilMode; scripts that leave a state set get it reset after the render event
     * ([resetAfterEvent]), where FTL would carry it into the engine's next draw.
     */
    private class GlState {
        var mode = 0; var ref = 0; var mask = 0
        val stack = ArrayList<IntArray>()
        var touched = false

        fun flush() { LuaGraphics.flushQuads(); HsLua.graphics?.flushAll() }

        fun reset() { mode = 0; ref = 0; mask = 0; stack.clear(); touched = false }

        /** CSurface::GL_SetStencilMode: 1 writes the reference where pixels pass (no colour), 2 draws where it matches, 0 off. */
        fun setStencil(m: Int, r: Int, k: Int) {
            flush()
            mode = m; ref = r; mask = k
            when (m) {
                1 -> {
                    touched = true
                    GL11.glStencilMask(0xff); GL11.glEnable(GL11.GL_STENCIL_TEST)
                    GL11.glStencilFunc(GL11.GL_ALWAYS, r, k); GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_REPLACE)
                    GL11.glColorMask(false, false, false, false)
                }
                2 -> {
                    touched = true
                    GL11.glEnable(GL11.GL_STENCIL_TEST)
                    GL11.glStencilFunc(GL11.GL_EQUAL, r, k); GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP)
                    GL11.glColorMask(true, true, true, true)
                }
                0 -> { GL11.glDisable(GL11.GL_STENCIL_TEST); GL11.glColorMask(true, true, true, true) }
            }
        }

        fun blend(on: Boolean) {
            flush(); touched = true
            GL11.glEnable(GL11.GL_BLEND)
            if (on) GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA) else GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ZERO)
        }
    }

    private val glState = GlState()

    /** After a render event's callbacks (LuaGraphics.restore): blend, stencil and scissor back to what the engine draws with. */
    @JvmStatic
    fun resetAfterEvent() {
        if (!glState.touched) return
        glState.flush()
        GL11.glDisable(GL11.GL_SCISSOR_TEST)
        glState.blend(true)
        glState.setStencil(0, 0, 0)
        glState.stack.clear()
        glState.touched = false
    }

    private fun csurface() {
        LuaReg.method(A.M_CSurface_GL_SetStencilMode, LuaMethod { glState.setStencil(arg.int(1), arg.int(2), arg.int(3)); 0 })
        method("CSurface.GL_PushStencilMode", LuaMethod { glState.stack.add(intArrayOf(glState.mode, glState.ref, glState.mask)); glState.touched = true; 0 })
        method("CSurface.GL_PopStencilMode", LuaMethod {
            val s = glState.stack.removeLastOrNull() ?: intArrayOf(0, 0, 0)
            glState.setStencil(s[0], s[1], s[2]); 0
        })
        method("CSurface.GL_EnableBlend", LuaMethod { glState.blend(true); ret.bool(true) })
        method("CSurface.GL_DisableBlend", LuaMethod { glState.blend(false); ret.bool(true) })
        // GL_PopScissor: the engine's scissor stack is empty here, so the scissor is switched off
        method("CSurface.GL_PopScissor", LuaMethod { glState.flush(); GL11.glDisable(GL11.GL_SCISSOR_TEST); 0 })
        // GL_OrthoProjection(left, right, bottom, top, near, far): the 1280x720 screen shows [left,right] x [top,bottom]
        method("CSurface.GL_OrthoProjection", LuaMethod {
            val g = HsLua.graphics ?: return@LuaMethod ret.num(0)
            glState.flush()
            val l = arg.float(1); val r = arg.float(2); val b = arg.float(3); val t = arg.float(4)
            if (r != l && b != t) {
                g.loadIdentityMatrix()
                g.scale(1280f / (r - l), 720f / (b - t))
                g.translate(-l, -t)
            }
            ret.num(0)
        })
        method("CSurface.GL_ClearColor", LuaMethod { glState.flush(); GL11.glClear(GL11.GL_COLOR_BUFFER_BIT); 0 })
        // GL_DrawShield(x, y, a, b, startAngle, endAngle, color, thickness): a ring of an ellipse (semi-axes a and b) from
        // startAngle to endAngle degrees; the outer edge fades in then out with the square of a ramp that peaks half way, the inner
        // edge (thickness inside, same aspect) is transparent
        method("CSurface.GL_DrawShield", LuaMethod {
            val g = HsLua.graphics ?: return@LuaMethod ret.bool(true)
            val x = arg.float(1); val y = arg.float(2); val a = arg.float(3); val b = arg.float(4)
            val start = arg.int(5); val end = arg.int(6)
            val c = LuaGraphics.colour(Colour(0f, 0f, 0f, 0f), arg.obj(7) as? GlColorValue)
            val innerA = a - arg.float(8)
            val aspect = b / a
            if (start < end) {
                LuaGraphics.flushQuads()
                var step = 2f / (end - start)
                var t = 0f
                val vx = FloatArray((end - start) * 2); val vy = FloatArray(vx.size); val va = FloatArray(vx.size)
                var n = 0
                for (i in start until end) {
                    val ang = (i * 3.1415927f) / 180f
                    vx[n] = (kotlin.math.cos(ang.toDouble()).toFloat() * a + x); vy[n] = (kotlin.math.sin(ang.toDouble()).toFloat() * b + y); va[n] = c.a * t * t; n++
                    t += step
                    if (t >= 1f) step = -step
                    val ang2 = 0.001f + ang
                    vx[n] = (kotlin.math.cos(ang2.toDouble()).toFloat() * innerA + x); vy[n] = (kotlin.math.sin(ang2.toDouble()).toFloat() * aspect * innerA + y); va[n] = 0f; n++
                }
                g.drawCustomTriangles { r ->
                    for (k in 0 until n - 2) {
                        // a triangle strip: the winding alternates
                        val i0 = k; val i1 = if (k % 2 == 0) k + 1 else k + 2; val i2 = if (k % 2 == 0) k + 2 else k + 1
                        for (i in intArrayOf(i0, i1, i2)) r.pushVert(vx[i], vy[i], c.r, c.g, c.b, va[i])
                    }
                }
            }
            ret.bool(true)
        })
        // GL_DrawLaser(x, y, w, h, color): a box centred on (x + w/2, y), w wide and h high, fading to nothing at the
        // top and bottom edges
        method("CSurface.GL_DrawLaser", LuaMethod {
            val g = HsLua.graphics ?: return@LuaMethod ret.bool(true)
            val x = arg.int(1); val y = arg.int(2); val w = arg.int(3); val h = arg.int(4)
            val c = LuaGraphics.colour(Colour(0f, 0f, 0f, 0f), arg.obj(5) as? GlColorValue)
            val cx = (x + w / 2).toFloat(); val cy = y.toFloat()
            val l = cx - (w / 2); val r = cx + (w / 2)
            val top = cy - (h / 2); val bottom = cy + (h / 2)
            LuaGraphics.flushQuads()
            g.drawCustomQuads { q ->
                q.pushVert(l, top, c.r, c.g, c.b, 0f); q.pushVert(r, top, c.r, c.g, c.b, 0f)
                q.pushVert(r, cy, c.r, c.g, c.b, c.a); q.pushVert(l, cy, c.r, c.g, c.b, c.a)
                q.pushVert(l, cy, c.r, c.g, c.b, c.a); q.pushVert(r, cy, c.r, c.g, c.b, c.a)
                q.pushVert(r, bottom, c.r, c.g, c.b, 0f); q.pushVert(l, bottom, c.r, c.g, c.b, 0f)
            }
            ret.bool(true)
        })
        method("CSurface.GL_ClearAll", LuaMethod {
            glState.flush()
            GL11.glStencilMask(0xff)
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT or GL11.GL_DEPTH_BUFFER_BIT or GL11.GL_STENCIL_BUFFER_BIT); 0
        })
    }

    // ---------------------------------------------------------------------------------- CApp, Settings, mixes

    private fun appAndSettings() {
        // CApp::gui / world are pointer members: writing the object back is a no-op, anything else cannot be mounted here
        LuaReg.obj(A.A_CApp_gui, ObjGet { LuaGui }, ObjSet { _, v -> if (v !== LuaGui) HS.unsupported("lua", "CApp.gui", "replacing the command gui") })
        LuaReg.obj(A.A_CApp_world, ObjGet { LuaWorld }, ObjSet { _, v -> if (v !== LuaWorld) HS.unsupported("lua", "CApp.world", "replacing the world manager") })
        // CApp::OnExit: settings and score saved, the app stops running; OnRequestExit saves the run first
        method("CApp.OnExit", LuaMethod { LuaAdapters.inGame()?.mainGame?.exitGame(); 0 })
        method("CApp.OnRequestExit", LuaMethod {
            val g = LuaAdapters.inGame()
            if (g != null) g.mainGame.quitGame()
            0
        })
        // MouseControl::lastPosition: a write holds until the mouse moves again
        val lp = A.A_MouseControl_lastPosition
        val prev = LuaReg.objGet[lp]
        LuaReg.obj(lp, ObjGet { o -> lastPositionHeld ?: prev?.get(o) }, ObjSet { _, v ->
            val p = v as? PointValue; if (p != null) lastPositionHeld = PointValue(p.x, p.y)
        })
        // Settings::GetHotkey: the key of the hotkey called [name] ("pause", "activate_cloak",...), 0 for an unknown name
        fun binding(name: String): xyz.znix.xftl.game.HotkeyButton? {
            val g = LuaAdapters.inGame() ?: return null
            val hk = g.hotkeyManager.groups.flatMap { it.hotkeys }.firstOrNull { it.uiName == "hotkey_$name" } ?: return null
            return g.reverseHotkeyBindings[hk]
        }
        method("SettingValues.GetHotkey", LuaMethod { ret.num(binding(arg.str(2) ?: "")?.let { LuaEvents.sdlKey(it.keyID) } ?: 0) })
        method("SettingValues.GetHotkeyName", LuaMethod {
            val g = LuaAdapters.inGame()
            // SDL's key name, upper case (a printable key is its character); "None" for a hotkey with no key or an unknown name (oracle rows)
            ret.str(binding(arg.str(2) ?: "")?.let { b ->
                val sdl = LuaEvents.sdlKey(b.keyID)
                if (sdl in 33..126) sdl.toChar().uppercaseChar().toString() else g?.translator?.get(b.text)?.uppercase()
            } ?: "None")
        })
    }

    private val channels = HashMap<Int, xyz.znix.xftl.game.SoundInstance>()
    /** Diagnostics (LuaProbe): the sound of the [back]-th last PlaySoundMix channel (0: the last one). */
    @JvmStatic fun lastSoundChannel(back: Int): xyz.znix.xftl.game.SoundInstance? = channels[nextChannel - 1 - back]
    private var nextChannel = 0x1000001

    /**
     * SoundControl::PlaySoundMix(name, volume, loop): plays the sound and returns its channel; StopChannel(channel, fade)
     * stops it (the fade is not applied: the sound stops at once).
     */
    private fun sounds() {
        method("SoundControl.PlaySoundMix", LuaMethod {
            val g = LuaAdapters.inGame()
            val name = arg.str(2) ?: ""
            val sample = g?.sounds?.getSampleOrWarn(name)
            val spec = (sample as? xyz.znix.xftl.game.FTLSound)
            if (spec == null) return@LuaMethod ret.num(0)
            // hyperspace.i passes the volume straight to PlaySoundMix (0x5d5160): above 0 it is the channel's volume, 0 or
            // less (scripts pass -1, as HS's own calls do) the sound's own sounds.xml volume (FTLSound.playAt, U4)
            val inst = try { sample.playAt(arg.float(3)) } catch (e: IllegalArgumentException) { null } // a looping sound has no one-shot play
            if (inst == null) return@LuaMethod ret.num(0)
            val ch = nextChannel++
            channels[ch] = inst
            ret.num(ch)
        })
        method("SoundControl.StopChannel", LuaMethod {
            channels.remove(arg.int(2))?.stop(); 0
        })
    }

    private fun resources() {
        // ResourceControl::LoadFile(name): the file's bytes as a string (nil for a file the data does not have)
        method("ResourceControl.LoadFile", LuaMethod {
            val content = HsLua.game?.gameContent
            val df = content?.datafile
            val bytes = df?.getOrNull(arg.str(2) ?: "")?.let { f -> df.read(f) }
            if (bytes == null) ret.str(null) else ret.str(String(bytes, Charsets.ISO_8859_1))
        })
        // GetFontData returns a freetype::font_data: the freetype bindings belong to the language and font slice
    }

    private fun animation() {
        // Animation::SaveState / LoadState write to and read from an FTL save file descriptor, which no script can hold
        method("Animation.SaveState", LuaMethod { 0 })
        method("Animation.LoadState", LuaMethod { 0 })
        // Animation::SetAnimationId(strip): the sprite strip is replaced and the primitives are rebuilt (CustomCrew.cpp)
        method("Animation.SetAnimationId", LuaMethod {
            val an = arg.obj(1) as LuaStruct
            an.values[A.A_Animation_animationStrip] = arg.obj(2); 0
        })
    }

    // ----------------------------------------------------------------------------------------- WeaponControl

    private var slotOverride: Int? = null
    private var slotEngineAt = -1

    /** A script's write to armedWeapon (a plain pointer in FTL, independent of armedSlot), kept while the engine's armed slot is unchanged. */
    private var weaponOverride: Any? = null
    private var weaponIsSet = false
    private var weaponEngineAt = -1

    private fun engineSlot() = LuaAdapters.inGame()?.shipUI?.hsArmedSlot ?: -1

    /** The armed slot: the engine's, or a script's write for as long as the engine's armed slot has not changed since. */
    private fun armedSlot(): Int {
        val ov = slotOverride
        if (ov != null && engineSlot() == slotEngineAt) return ov
        slotOverride = null
        return engineSlot()
    }

    // --------------------------------------------------------------------------------------------- TutorialArrow

    /**
     * TutorialArrow(Pointf position, float rotation) (CustomTutorial.h, the only constructor; `%nodefaultctor` drops the
     * default one): the TutorialManager's arrow and arrow2 textures, blitSize 164x38, white colours. OnRender
     * (CustomTutorial.cpp) blits arrow, then arrow2 with its alpha set to the tutorial tracker's GetAlphaLevel(false).
     */
    // Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Gameplay/CustomTutorial.h, CustomTutorial.cpp
    private fun tutorialArrow() {
        LuaStructs.register(A.T_TutorialArrow)
        LuaReg.method(A.M_new_TutorialArrow, LuaMethod {
            val p = arg.obj(1)
            val (px, py) = when (p) {
                is PointfValue -> p.x to p.y
                is LuaStruct -> LuaStructs.fields(p).let { it.getOrElse(0) { 0.0 }.toFloat() to it.getOrElse(1) { 0.0 }.toFloat() }
                else -> throw LuaError("Wrong arguments for overloaded function 'new_TutorialArrow'")
            }
            val s = LuaStructs.create(A.T_TutorialArrow)
            s.values[A.A_TutorialArrow_arrow] = LuaGraphics.texture("tutorial/arrow.png")
            s.values[A.A_TutorialArrow_arrow2] = LuaGraphics.texture("tutorial/arrow2.png")
            LuaStructs.setFields(sub(s, A.A_TutorialArrow_position), px.toDouble(), py.toDouble())
            LuaStructs.setFields(sub(s, A.A_TutorialArrow_blitSize), 164.0, 38.0)
            s.values[A.A_TutorialArrow_rotation] = arg.float(2).toDouble()
            LuaStructs.setFields(sub(s, A.A_TutorialArrow_arrow_color), 1.0, 1.0, 1.0, 1.0)
            LuaStructs.setFields(sub(s, A.A_TutorialArrow_arrow2_color), 1.0, 1.0, 1.0, 1.0)
            ret.obj(s, A.T_TutorialArrow)
        })
        method("TutorialArrow.OnRender", LuaMethod {
            val s = arg.obj(1) as? LuaStruct ?: return@LuaMethod 0
            val g = HsLua.graphics ?: return@LuaMethod 0
            val pos = LuaStructs.fields(sub(s, A.A_TutorialArrow_position))
            val size = LuaStructs.fields(sub(s, A.A_TutorialArrow_blitSize))
            val rot = num(s, A.A_TutorialArrow_rotation).toFloat()
            fun colourOf(a: Int): Colour { val c = LuaStructs.fields(sub(s, a)); return Colour(c[0].toFloat(), c[1].toFloat(), c[2].toFloat(), c[3].toFloat()) }
            fun render(x: Float, y: Float, rot: Float) {
                LuaGraphics.drawImage(g, s.values[A.A_TutorialArrow_arrow] as? LuaTexture, x, y,
                    size[0].toFloat(), size[1].toFloat(), rot, false, colourOf(A.A_TutorialArrow_arrow_color))
                // arrow2_color.a = G_->GetTutorialManager()->tracker.GetAlphaLevel(false), written back as HS does
                val tracker = LuaReg.objGet[A.A_TutorialManager_tracker]?.get(LuaTutorial) as? LuaStruct
                val time = tracker?.let { num(it, A.A_AnimationTracker_time).toFloat() } ?: 0f
                val ct = tracker?.let { num(it, A.A_AnimationTracker_current_time).toFloat() } ?: 0f
                val alpha = 1f - Math.abs(1f - 2f * (if (time == 0f) 0f else ct / time))
                val c2 = sub(s, A.A_TutorialArrow_arrow2_color)
                c2.values[LuaStructs.attrsOf(c2.luaType)[3]] = alpha.toDouble()
                LuaGraphics.drawImage(g, s.values[A.A_TutorialArrow_arrow2] as? LuaTexture, x, y,
                    size[0].toFloat(), size[1].toFloat(), rot, false, colourOf(A.A_TutorialArrow_arrow2_color))
                LuaGraphics.flushQuads()
            }
            // The touch layout draws it at the touch place of what it points at (TouchPointers).
            LuaGraphics.flushQuads()
            if (!xyz.znix.xftl.game.TouchPointers.arrow(g, pos[0].toFloat(), pos[1].toFloat(), size[0].toFloat(), size[1].toFloat(), rot, ::render))
                render(pos[0].toFloat(), pos[1].toFloat(), rot)
            0
        })
    }

    // --------------------------------------------------------------------------------------------- CrewMember render

    /**
     * The CrewMember render family a script calls inside a render event, drawn into the current frame as the ship
     * render draws it: OnRender(outlineOnly) the outline alone or outline and body (CrewAnimation::OnRender with HS's power
     * and stat-boost animations, CustomCrew.cpp), OnRenderHealth the health bar pass (HS's power effects, healing and
     * mind-control icons first), OnRenderPath the green destination box while a player crew member walks
     * (iShipId 0, away from its goal by half a pixel or more; HS also takes a mind-controlled enemy while the
     * player has MIND_ORDER), RenderSkillUpAnimation the skill-up icons (none run in the browser engine).
     * Installed after slice A's adapters (LuaAdaptersMore).
     */
    @JvmStatic
    fun installCrewRender() {
        fun crew(o: Any?) = o as? xyz.znix.xftl.crew.AbstractCrew
        LuaReg.method(A.M_CrewMember_OnRender, LuaMethod {
            val c = crew(arg.obj(1)); val g = HsLua.graphics
            // outlineOnly: the selection outline alone, nothing for a crew member that is neither selected nor hovered
            // (oracle sw3-crewr2 q0); false: outline and body, as the ship draws them
            val outlineOnly = arg.bool(2)
            if (c != null && g != null && (!outlineOnly || c.room.ship.sys.shipUI.isCrewHighlighted(c))) {
                LuaGraphics.flushQuads()
                c.hsRenderPart = if (outlineOnly) 1 else 0
                try { c.draw(g) } finally { c.hsRenderPart = 0 }
            }
            0
        })
        LuaReg.method(A.M_CrewMember_OnRenderHealth, LuaMethod {
            val c = crew(arg.obj(1)); val g = HsLua.graphics
            if (c != null && g != null) { LuaGraphics.flushQuads(); c.drawForeground(g) }
            0
        })
        LuaReg.method(A.M_CrewMember_OnRenderPath, LuaMethod {
            val c = crew(arg.obj(1)); val g = HsLua.graphics
            if (c != null && g != null) renderPath(c)
            0
        })
        // RenderSkillUpAnimation(Point pos): the crew box's skill-up indicator at pos while the crew member trains
        LuaReg.method(A.M_CrewMember_RenderSkillUpAnimation, LuaMethod {
            val c = arg.obj(1) as? xyz.znix.xftl.crew.LivingCrew; val g = HsLua.graphics
            val p = arg.obj(2) as? PointValue
            val ui = LuaAdapters.inGame()?.shipUI
            if (c != null && g != null && p != null && ui != null) { LuaGraphics.flushQuads(); ui.drawSkillUp(g, c, p.x, p.y) }
            0
        })
        // levelUp / lastLevelUp: the engine's level-up animation (LivingCrew.levelUpTime/levelUpRunning/lastLevelUp)
        LuaReg.obj(A.A_CrewMember_levelUp, ObjGet { o ->
            val c = o as? xyz.znix.xftl.crew.LivingCrew
            if (c == null) LuaStructs.create(A.T_AnimationTracker) else {
                LuaStructs.register(A.T_AnimationTracker)
                val e = levelUps.getOrPut(c) { LevelUp(LuaStructs.create(A.T_AnimationTracker)) }
                // the script's writes since the last hand-back first, then the engine's state now
                pullLevelUp(c); pushLevelUp(c)
                e.struct
            }
        })
        LuaReg.num(A.A_CrewMember_lastLevelUp, NumGet { o -> ((o as? xyz.znix.xftl.crew.LivingCrew)?.lastLevelUp?.ordinal ?: 0).toDouble() },
            NumSet { o, v -> (o as? xyz.znix.xftl.crew.LivingCrew)?.lastLevelUp = xyz.znix.xftl.crew.Skill.values().getOrNull(v.toInt()) })
        // vCrewList: the crew, then the crew drones not deployed yet (AbstractIndoorsDrone.hsIdlePawn); those read
        // bDead, IsDead, no room, at 0,0 and not Functional (oracle sw4-cdrone d0-c3)
        LuaReg.obj(A.A_ShipManager_vCrewList, ObjGet {
            LuaViews.cached(it!!, A.A_ShipManager_vCrewList) { o -> ListView(A.T_vector_CrewMember, A.T_CrewMember) { crewList(o as xyz.znix.xftl.Ship) } }
        })
        fun idle(o: Any?): Boolean = o is xyz.znix.xftl.drones.AbstractIndoorsDrone.Pawn && o.drone.hsIsIdle(o)
        for ((a, v) in listOf(A.A_CrewMember_bDead to 1.0, A.A_CrewMember_x to 0.0, A.A_CrewMember_y to 0.0, A.A_CrewMember_iRoomId to -1.0)) {
            val before = LuaReg.numGet[a] ?: continue
            LuaReg.numGet[a] = NumGet { o -> if (idle(o)) v else before.get(o) }
        }
        LuaReg.objGet[A.A_CrewMember_health]?.let { before ->
            LuaReg.objGet[A.A_CrewMember_health] = ObjGet { o ->
                if (idle(o)) {
                    val c = o as xyz.znix.xftl.drones.AbstractIndoorsDrone.Pawn
                    val h = c.drone.hsIdleHealth(c)
                    FloatPairView(A.T_pair_float_float, FGet { h }, FSet { }, FGet { h }, null)
                } else before.get(o)
            }
        }
        for ((m, v) in listOf(A.M_CrewMember_Functional to false, A.M_CrewMember_IsDead to true)) {
            val before = LuaReg.methods[m] ?: continue
            LuaReg.methods[m] = LuaMethod { n -> if (idle(arg.obj(1))) ret.bool(v) else before.call(n) }
        }
        // CrewControl firstMouse / currentMouse / worldFirstMouse / worldCurrentMouse: the selection's mouse points
        // (PlayerShipUI.hsFirstMouse; a Point member: reads hand out the point itself, writes copy into it)
        fun ccPoint(id: Int, p: (xyz.znix.xftl.game.PlayerShipUI) -> PointValue) {
            LuaReg.obj(id, ObjGet { LuaAdapters.inGame()?.shipUI?.let(p) }, ObjSet { _, v ->
                val src = v as? PointValue; val dst = LuaAdapters.inGame()?.shipUI?.let(p)
                if (src != null && dst != null) { dst.x = src.x; dst.y = src.y }
            })
        }
        ccPoint(A.A_CrewControl_firstMouse) { it.hsFirstMouse }
        ccPoint(A.A_CrewControl_currentMouse) { it.hsCurrentMouse }
        ccPoint(A.A_CrewControl_worldFirstMouse) { it.hsWorldFirstMouse }
        ccPoint(A.A_CrewControl_worldCurrentMouse) { it.hsWorldCurrentMouse }
        // ShipManager.bInvincible: the wreck's emergency jump (Ship.invincible)
        LuaReg.bool(A.A_ShipManager_bInvincible, { o -> (o as xyz.znix.xftl.Ship).invincible }, { o, v -> (o as xyz.znix.xftl.Ship).invincible = v })
    }

    /** Drones not deployed have no engine instance: a dormant one stands for each (its body is never spawned). */
    private val dormant = xyz.znix.xftl.hs.lua.HsRunMaps.run(java.util.IdentityHashMap<xyz.znix.xftl.drones.NativeDrone, xyz.znix.xftl.drones.AbstractIndoorsDrone>())

    private fun crewList(ship: xyz.znix.xftl.Ship): List<Any?> {
        if (LuaAdapters.isEscaped(ship)) return emptyList()
        val system = ship.drones ?: return ship.crew
        val room = system.room ?: return ship.crew
        val idle = ArrayList<Any?>()
        for (d in system.drones) {
            if (d == null) continue
            if (d.type.type != xyz.znix.xftl.weapons.DroneBlueprint.DroneType.BATTLE && d.type.type != xyz.znix.xftl.weapons.DroneBlueprint.DroneType.REPAIR) continue
            // a stand-in that cannot be made leaves the list as the engine has it (never a script error)
            try {
                val drone = d.instance as? xyz.znix.xftl.drones.AbstractIndoorsDrone ?: d.hsCrewActor
                    ?: dormant.getOrPut(d) { (d.type.makeInstance() as xyz.znix.xftl.drones.AbstractIndoorsDrone).also { it.hsStandsFor = d } }
                drone.hsIdlePawn(room)?.let { idle += it }
            } catch (e: RuntimeException) {
                dormant.remove(d)
            }
        }
        return if (idle.isEmpty()) ship.crew else ship.crew + idle
    }

    private class LevelUp(val struct: LuaStruct) { var snapshot: DoubleArray? = null }
    private val levelUps = xyz.znix.xftl.hs.lua.HsRunMaps.run(java.util.IdentityHashMap<xyz.znix.xftl.crew.LivingCrew, LevelUp>())

    /** A script's writes to [c]'s levelUp tracker since it was last handed back (current_time, running). */
    @JvmStatic
    fun pullLevelUp(c: xyz.znix.xftl.crew.LivingCrew) {
        val e = levelUps[c] ?: return
        val snap = e.snapshot ?: return
        val ct = num(e.struct, A.A_AnimationTracker_current_time)
        val run = if (flag(e.struct, A.A_AnimationTracker_running)) 1.0 else 0.0
        if (ct != snap[0] || run != snap[1]) {
            c.levelUpTime = ct.toFloat().coerceIn(0f, xyz.znix.xftl.crew.LivingCrew.LEVEL_UP_TIME)
            c.levelUpRunning = run != 0.0
        }
    }

    /** [c]'s levelUp tracker as the engine has it now (AnimationTracker: time 2, no loop). */
    @JvmStatic
    fun pushLevelUp(c: xyz.znix.xftl.crew.LivingCrew) {
        val e = levelUps[c] ?: return
        val v = e.struct.values
        v[A.A_AnimationTracker_time] = xyz.znix.xftl.crew.LivingCrew.LEVEL_UP_TIME.toDouble()
        v[A.A_AnimationTracker_loop] = false
        v[A.A_AnimationTracker_current_time] = c.levelUpTime.toDouble()
        v[A.A_AnimationTracker_running] = c.levelUpRunning
        v[A.A_AnimationTracker_reverse] = false
        v[A.A_AnimationTracker_done] = !c.levelUpRunning
        if (v[A.A_AnimationTracker_loopDelay] == null) v[A.A_AnimationTracker_loopDelay] = 0.0
        if (v[A.A_AnimationTracker_currentDelay] == null) v[A.A_AnimationTracker_currentDelay] = 0.0
        e.snapshot = doubleArrayOf(c.levelUpTime.toDouble(), if (c.levelUpRunning) 1.0 else 0.0)
    }

    private fun renderPath(c: xyz.znix.xftl.crew.AbstractCrew) {
        val game = LuaAdapters.inGame() ?: return
        val player = game.player ?: return
        val id = xyz.znix.xftl.hs.crew.HsCrew.shipId(c)
        val mindOrder = id == 1 && c is xyz.znix.xftl.crew.LivingCrew && c.isMindControlled && player.hasAugment("MIND_ORDER")
        if (id != 0 && !mindOrder) return
        for (room in c.room.ship.rooms) {
            val i = room.hsReservedSlotOf(c)
            if (i < 0) continue
            val slot = room.slotToPoint(i)
            if (c.standingPosition?.posEq(slot) == true && c.room == room) return
            LuaGraphics.flushQuads()
            game.getImg("img/people/green_destination.png").draw(
                (slot.x * xyz.znix.xftl.Constants.ROOM_SIZE + room.offsetX).toFloat(), (slot.y * xyz.znix.xftl.Constants.ROOM_SIZE + room.offsetY).toFloat())
            return
        }
    }

    private fun weaponControl() {
        val slotAttr = A.A_WeaponControl_armedSlot
        LuaReg.num(slotAttr, NumGet { armedSlot().toDouble() }, NumSet { _, v -> slotOverride = v.toInt(); slotEngineAt = engineSlot() })
        // armedWeapon and armedSlot are separate fields in FTL: writing one leaves the other as it was (oracle w-setweapon, w-setnil)
        LuaReg.obj(A.A_WeaponControl_armedWeapon, ObjGet {
            if (weaponIsSet && engineSlot() == weaponEngineAt) weaponOverride
            else {
                weaponIsSet = false
                // an armed targetable artillery (ArtilleryBox, aim_artillery hotkeys) is armedWeapon with armedSlot -1
                // (oracle sweep-world-y1 y-armed)
                LuaAdapters.inGame()?.shipUI?.hsArmedArtillery()?.let { return@ObjGet it.hsWeapon }
                val slot = engineSlot()
                if (slot < 0) null else LuaAdapters.inGame()?.player?.hardpoints?.getOrNull(slot)?.weapon
            }
        }, ObjSet { _, v -> weaponOverride = v; weaponIsSet = true; weaponEngineAt = engineSlot() })
        val weapons = { o: Any? -> o === LuaAdaptersArmament.WeaponControlView }
        // WeaponControl::DeselectArmament(slot): the weapon is powered down (a locked system plays the power-up
        // failure sound); the engine's own click path does the same for Shift+key
        for (name in listOf("ArmamentControl.DeselectArmament", "WeaponControl.DeselectArmament")) methodFor(name, weapons, LuaMethod {
            LuaAdapters.inGame()?.shipUI?.weaponHotkeyPressed(arg.int(2), true); 0
        })
        // SelectArmament(slot) is the weapon box click: power the weapon on, or arm it when it is powered
        for (name in listOf("ArmamentControl.SelectArmament", "WeaponControl.SelectArmament")) methodFor(name, weapons, LuaMethod {
            LuaAdapters.inGame()?.shipUI?.weaponHotkeyPressed(arg.int(2), false); 0
        })
        // ArmamentControl::SwapArmaments(a, b) is an empty virtual, but WeaponControl's swaps the two weapons (oracle w-order)
        for (name in listOf("ArmamentControl.SwapArmaments", "WeaponControl.SwapArmaments")) methodFor(name, weapons, LuaMethod {
            LuaAdapters.inGame()?.player?.weapons?.swapSlots(arg.int(2), arg.int(3)); 0
        })
    }
}
