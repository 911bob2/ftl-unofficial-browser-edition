// Ported from FTL-Hyperspace (GPL-3.0-or-later) commit 1b05a07 (lua/ script API and .i members, src/ files named below),
// not from the 1.23.2 tag.
package xyz.znix.xftl.hs.lua

import xyz.znix.xftl.AnimationSpec
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.hs.lua.LuaApi as A
import xyz.znix.xftl.hs.lua.LuaArgs as arg
import xyz.znix.xftl.hs.lua.LuaPush as ret

/** Hyperspace.Animations (AnimationControl). */
object LuaAnimations : LuaTyped { override val luaType get() = A.T_AnimationControl }

/**
 * AnimationControl::GetAnimation returns an Animation by value: a struct filled from the engine's animation spec
 * (info = AnimationDescriptor, tracker = AnimationTracker with the strip's total time). Start/Update/OnRender/
 * SetCurrentFrame/Done follow FTL's AnimationTracker model: Update advances one 60 fps frame of game time.
 * Not checked against the original yet (oracle): the tracker's time step and frame rounding.
 */
object LuaAnim {
    const val SPEC = -2
    private const val SPRITES = -3
    private const val STRIP = -4
    private const val FRAME_TIME = 1f / 60f

    private fun s(a: Int, owner: LuaStruct) = LuaStructs.member(owner, a) as LuaStruct
    private fun num(st: LuaStruct, a: Int): Double = (st.values[a] as? Double) ?: 0.0
    private val tint = Colour(1f, 1f, 1f, 1f)

    fun create(spec: AnimationSpec): LuaStruct {
        val an = LuaStructs.create(A.T_Animation)
        an.values[SPEC] = spec
        val info = s(A.A_Animation_info, an)
        val sheet = spec.sheet
        info.values[A.A_AnimationDescriptor_numFrames] = spec.length.toDouble()
        info.values[A.A_AnimationDescriptor_imageWidth] = sheet.sheetWidth.toDouble()
        info.values[A.A_AnimationDescriptor_imageHeight] = sheet.sheetHeight.toDouble()
        info.values[A.A_AnimationDescriptor_stripStartX] = spec.x.toDouble()
        info.values[A.A_AnimationDescriptor_stripStartY] = (sheet.verticalCount - spec.y - 1).toDouble()
        info.values[A.A_AnimationDescriptor_frameWidth] = sheet.frameWidth.toDouble()
        info.values[A.A_AnimationDescriptor_frameHeight] = sheet.frameHeight.toDouble()
        val tr = s(A.A_Animation_tracker, an)
        tr.values[A.A_AnimationTracker_time] = spec.totalTime.toDouble()
        // a fresh Animation (HS oracle): finished (done), no loop delay running (currentDelay -1), fScale 1, fYStretch 0 (no
        // stretch), the mask the frame's size
        tr.values[A.A_AnimationTracker_done] = true
        tr.values[A.A_AnimationTracker_currentDelay] = -1.0
        an.values[A.A_Animation_fScale] = 1.0
        an.values[A.A_Animation_fYStretch] = 0.0
        an.values[A.A_Animation_mask_x_size] = sheet.frameWidth.toDouble()
        an.values[A.A_Animation_mask_y_size] = sheet.frameHeight.toDouble()
        an.values[A.A_Animation_animName] = spec.name
        HsLua.game?.gameContent?.let { an.values[A.A_Animation_animationStrip] = LuaTexture(it.getImg(sheet.sheetPath), sheet.sheetPath) }
        return an
    }

    private fun frameOf(an: LuaStruct): Int {
        val tr = s(A.A_Animation_tracker, an); val n = num(s(A.A_Animation_info, an), A.A_AnimationDescriptor_numFrames).toInt()
        val time = num(tr, A.A_AnimationTracker_time)
        if (n <= 0 || time <= 0.0) return 0
        var p = num(tr, A.A_AnimationTracker_current_time) / time
        return (p * n).toInt().coerceIn(0, n - 1)
    }

    fun install() {
        LuaReg.obj(A.A_module_Animations, ObjGet { LuaAnimations })
        LuaReg.method(A.M_AnimationControl_GetAnimation, LuaMethod {
            val spec = HsLua.game?.gameContent?.animations?.getOrNull(arg.str(2) ?: "")
            if (spec == null) ret.obj(LuaStructs.create(A.T_Animation), A.T_Animation) else ret.obj(create(spec), A.T_Animation)
        })
        LuaReg.method(A.M_Animation_Start, LuaMethod {
            val an = arg.obj(1) as LuaStruct; val tr = s(A.A_Animation_tracker, an)
            if (arg.bool(2)) tr.values[A.A_AnimationTracker_current_time] = 0.0
            tr.values[A.A_AnimationTracker_reverse] = false
            tr.values[A.A_AnimationTracker_running] = true; tr.values[A.A_AnimationTracker_done] = false
            an.values[A.A_Animation_currentFrame] = frameOf(an).toDouble()
            0
        })
        // Animation::SetProgress(p): the tracker's time p of the way through; the frame to match only while it runs
        LuaReg.method(A.M_Animation_SetProgress, LuaMethod {
            val an = arg.obj(1) as LuaStruct; val tr = s(A.A_Animation_tracker, an)
            tr.values[A.A_AnimationTracker_current_time] = (arg.float(2) * num(tr, A.A_AnimationTracker_time).toFloat()).toDouble()
            // the frame follows the tracker's Progress, which is 0 while it is not running (oracle A3.SetProgress)
            an.values[A.A_Animation_currentFrame] = if (tr.values[A.A_AnimationTracker_running] == true) frameOf(an).toDouble() else 0.0
            0
        })
        // Animation::StartReverse(reset): runs backwards from the end (or from where it is when [reset] is false)
        LuaReg.method(A.M_Animation_StartReverse, LuaMethod {
            val an = arg.obj(1) as LuaStruct; val tr = s(A.A_Animation_tracker, an)
            if (arg.bool(2)) tr.values[A.A_AnimationTracker_current_time] = num(tr, A.A_AnimationTracker_time)
            tr.values[A.A_AnimationTracker_reverse] = true
            tr.values[A.A_AnimationTracker_running] = true; tr.values[A.A_AnimationTracker_done] = false
            an.values[A.A_Animation_currentFrame] = frameOf(an).toDouble()
            0
        })
        // Animation::RandomStart: starts a random number of frames in (random32() % frames), as the background objects do
        LuaReg.method(A.M_Animation_RandomStart, LuaMethod {
            val an = arg.obj(1) as LuaStruct; val tr = s(A.A_Animation_tracker, an)
            val n = num(s(A.A_Animation_info, an), A.A_AnimationDescriptor_numFrames).toInt()
            val time = num(tr, A.A_AnimationTracker_time)
            tr.values[A.A_AnimationTracker_current_time] = if (n > 0) (ftl.browser.NativeRandom.next31() % n) * time / n else 0.0
            tr.values[A.A_AnimationTracker_reverse] = false
            tr.values[A.A_AnimationTracker_running] = true; tr.values[A.A_AnimationTracker_done] = false
            0
        })
        LuaReg.method(A.M_Animation_Update, LuaMethod {
            val an = arg.obj(1) as LuaStruct; val tr = s(A.A_Animation_tracker, an)
            if (tr.values[A.A_AnimationTracker_running] == true) {
                val time = num(tr, A.A_AnimationTracker_time)
                val rev = tr.values[A.A_AnimationTracker_reverse] == true
                var t = num(tr, A.A_AnimationTracker_current_time) + (if (rev) -FRAME_TIME else FRAME_TIME)
                if (if (rev) t <= 0.0 else t >= time) {
                    if (tr.values[A.A_AnimationTracker_loop] == true && time > 0.0) t = if (rev) time else t % time
                    else { t = if (rev) 0.0 else time; tr.values[A.A_AnimationTracker_running] = false; tr.values[A.A_AnimationTracker_done] = true }
                }
                tr.values[A.A_AnimationTracker_current_time] = t
            }
            an.values[A.A_Animation_currentFrame] = frameOf(an).toDouble()
            0
        })
        LuaReg.method(A.M_Animation_SetCurrentFrame, LuaMethod {
            val an = arg.obj(1) as LuaStruct; val tr = s(A.A_Animation_tracker, an)
            val n = num(s(A.A_Animation_info, an), A.A_AnimationDescriptor_numFrames)
            val f = arg.int(2)
            an.values[A.A_Animation_currentFrame] = f.toDouble()
            if (n > 0) tr.values[A.A_AnimationTracker_current_time] = num(tr, A.A_AnimationTracker_time) * f / n
            0
        })
        LuaReg.method(A.M_Animation_Done, LuaMethod { ret.bool(s(A.A_Animation_tracker, arg.obj(1) as LuaStruct).values[A.A_AnimationTracker_done] == true) })
        LuaReg.method(A.M_Animation_OnRender, LuaMethod {
            val an = arg.obj(1) as LuaStruct
            val spec = an.values[SPEC] as? AnimationSpec ?: return@LuaMethod 0
            val content = HsLua.game?.gameContent ?: return@LuaMethod 0
            if (HsLua.graphics == null) return@LuaMethod 0
            val frame = (num(an, A.A_Animation_currentFrame).toInt()).coerceIn(0, maxOf(0, spec.length - 1))
            // K10: the strip frame images are made once per animation (a sub-image per call was the callback's main cost)
            // the strip a script put in Animation::animationStrip (SetAnimationId) replaces the sheet; its sprites are made again
            val strip = (an.values[A.A_Animation_animationStrip] as? LuaTexture)?.also { LuaGraphics.resolve(it) }?.image ?: content.getImg(spec.sheet.sheetPath)
            if (an.values[STRIP] !== strip) { an.values[SPRITES] = null; an.values[STRIP] = strip }

            val sprites = (an.values[SPRITES] as? Array<xyz.znix.xftl.rendering.Image?>)
                ?: arrayOfNulls<xyz.znix.xftl.rendering.Image>(maxOf(1, spec.length)).also { an.values[SPRITES] = it }
            val img = sprites[frame] ?: spec.sheet.getSprite(strip, spec.x + frame, spec.y).also { sprites[frame] = it }
            val pos = s(A.A_Animation_position, an)
            val px = num(pos, A.A_Pointf_x).toFloat(); val py = num(pos, A.A_Pointf_y).toFloat()
            val c = arg.obj(3) as? GlColorValue
            tint.r = c?.r ?: 1f; tint.g = c?.g ?: 1f; tint.b = c?.b ?: 1f; tint.a = (c?.a ?: 1f) * arg.float(2)
            val scale = num(an, A.A_Animation_fScale).toFloat()
            val w = img.width * scale; val ys = num(an, A.A_Animation_fYStretch).toFloat(); val h = img.height * scale * (if (ys == 0f) 1f else ys)
            LuaGraphics.flushQuads()
            val iw = img.width.toFloat(); val ih = img.height.toFloat()
            if (arg.bool(4) || an.values[A.A_Animation_bAlwaysMirror] == true) img.draw(px, py, px + w, py + h, iw, 0f, 0f, ih, 1f, tint)
            else img.draw(px, py, px + w, py + h, 0f, 0f, iw, ih, 1f, tint)
            0
        })
    }
}
