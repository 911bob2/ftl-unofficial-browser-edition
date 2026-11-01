// Ported from FTL-Hyperspace (GPL-3.0-or-later) commit 1b05a07 (lua/modules/hyperspace.i CFPS and its speedEnabled
// %init members, src/game/UserInterface/CommandConsole.cpp CFPS::OnLoop hook), not from the 1.23.2 tag.
package xyz.znix.xftl.hs.lua

import xyz.znix.xftl.hs.lua.LuaApi as A

/**
 * Hyperspace.FPS: FTL's CFPS (G_->GetCFPS()). FTL 1.6.14 CFPS::OnLoop (0x5ba020) per frame: once a second
 * NumFrames = Frames, Frames = 0, OldTime = now; SpeedFactor = (now - LastTime) / 1000 * (16 + 7 * speedLevel),
 * clamped to [0, 0.5]; Frames++; LastTime = now (times in milliseconds). CFPS::OnLoopUnpaused (0x5ba1c0):
 * RunningTime += SpeedFactor / 16. The game advances SpeedFactor / 16 seconds a frame, so speedLevel is the game
 * speed (0 normal, -2 = 2/16, 2 = 30/16). HS's OnLoop hook treats speedLevel as 0 while `speedEnabled` is false
 * (the "speed" hotkey toggles it; default true). Reached only while scripts run (HS.luaActive); vanilla keeps
 * BrowserEngine's own min(dt, 1/32).
 */
object LuaFps : LuaTyped {
    override val luaType get() = A.T_CFPS

    @JvmField var runningTime = 0f
    @JvmField var oldTime = 0.0
    @JvmField var lastTime = 0.0
    @JvmField var speedFactor = 0f
    @JvmField var numFrames = 0
    @JvmField var frames = 0
    @JvmField var speedLevel = 0
    @JvmField var speedEnabled = true
    private var now = 0.0
    private var started = false

    fun reset() {
        runningTime = 0f; oldTime = 0.0; lastTime = 0.0; speedFactor = 0f; numFrames = 0; frames = 0
        speedLevel = 0; speedEnabled = true; now = 0.0; started = false
    }

    /** The clock only (times, frame counts, RunningTime, SpeedFactor); speedLevel/speedEnabled stay as scripts set them. */
    fun resetClock() {
        runningTime = 0f; oldTime = 0.0; lastTime = 0.0; speedFactor = 0f; numFrames = 0; frames = 0
        now = 0.0; started = false
    }

    /** One frame of [dt] real seconds; returns the game seconds it advances (SpeedFactor / 16). */
    @JvmStatic
    fun onLoop(dt: Float): Float {
        // CApp::OnLoop calls OnLoopUnpaused only for a running, unpaused game; the arrival's hidden event box pauses it
        // from the location switch (oracle sw2-jump: RunningTime stays put through the fly-in)
        val st = HsLua.game?.currentState as? xyz.znix.xftl.game.InGameState
        val paused = st == null || st.isPaused || st.isArrivalHold
        now += dt * 1000.0
        if (!started) { started = true; oldTime = now; lastTime = now - dt * 1000.0 }
        if (oldTime + 1000.0 < now) { numFrames = frames; frames = 0; oldTime = now }
        val level = if (speedEnabled) speedLevel else 0
        var sf = ((now - lastTime) / 1000.0 * ((level * 7).toFloat() + 16f).toDouble()).toFloat()
        if (sf >= 0.5f) sf = 0.5f else if (sf <= 0f) sf = 0f
        speedFactor = sf
        frames++
        lastTime = now
        if (!paused) runningTime += sf * 0.0625f
        return sf * 0.0625f
    }

    fun install() {
        LuaReg.obj(A.A_module_FPS, ObjGet { LuaFps })
        LuaReg.num(A.A_CFPS_RunningTime, NumGet { runningTime.toDouble() })
        LuaReg.num(A.A_CFPS_OldTime, NumGet { oldTime })
        LuaReg.num(A.A_CFPS_LastTime, NumGet { lastTime })
        LuaReg.num(A.A_CFPS_SpeedFactor, NumGet { speedFactor.toDouble() })
        LuaReg.num(A.A_CFPS_NumFrames, NumGet { numFrames.toDouble() })
        LuaReg.num(A.A_CFPS_Frames, NumGet { frames.toDouble() })
        // SWIG: arg2 = (int)lua_tonumber(L, 2), a C truncation toward zero
        LuaReg.num(A.A_CFPS_speedLevel, NumGet { speedLevel.toDouble() }, NumSet { _, v -> speedLevel = v.toInt() })
        LuaReg.bool(A.A_CFPS_speedEnabled, { speedEnabled }, { _, v -> speedEnabled = v })
    }
}
