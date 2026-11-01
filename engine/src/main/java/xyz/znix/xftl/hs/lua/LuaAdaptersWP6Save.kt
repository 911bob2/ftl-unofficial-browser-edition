// Ported from FTL-Hyperspace (GPL-3.0-or-later) commit 1b05a07 (lua/modules/hyperspace.i: class Global, ScoreKeeper,
// TopScore, the Global_*_* aliases; src/game/Gameplay/World/Seeds.cpp for Global::currentSeed), not from the 1.23.2 tag.
package xyz.znix.xftl.hs.lua

import xyz.znix.xftl.hs.lua.LuaApi as A
import xyz.znix.xftl.hs.lua.LuaPush as ret

/** Hyperspace.Score / Global::GetScoreKeeper (the run's ScoreKeeper; HS exposes only `currentScore`). */
object LuaScoreKeeper : LuaTyped { override val luaType get() = A.T_ScoreKeeper }

/** Hyperspace.Sounds / Global::GetSoundControl: the object only; its methods belong to the sound workstream. */
object LuaSounds : LuaTyped { override val luaType get() = A.T_SoundControl }

/**
 * A `TopScore` (hyperspace.i: only `sector` and `score` are exposed, both writable). [live] is
 * `ScoreKeeper::currentScore`: it reads the run's running score and sector; a write holds until the run's own value
 * changes (HS refreshes the member from the stats when the profile is saved or a sector begins, which the oracle row
 * `score|write-score` cannot tell apart from this within one frame).
 */
class LuaTopScore(private val live: Boolean) : LuaTyped {
    override val luaType get() = A.T_TopScore

    private var plainSector = 0
    private var plainScore = 0
    private var sectorSet: Int? = null; private var sectorBase = 0
    private var scoreSet: Int? = null; private var scoreBase = 0

    private fun runSector(): Int = LuaAdapters.inGame()?.let { it.currentBeacon.sector.sectorNumber + 1 } ?: 0
    private fun runScore(): Int = LuaAdapters.inGame()?.browserProgress?.score ?: 0

    var sector: Int
        get() {
            if (!live) return plainSector
            val now = runSector()
            val held = sectorSet
            if (held != null && now == sectorBase) return held
            sectorSet = null
            return now
        }
        set(v) { if (live) { sectorSet = v; sectorBase = runSector() } else plainSector = v }

    var score: Int
        get() {
            if (!live) return plainScore
            val now = runScore()
            val held = scoreSet
            if (held != null && now == scoreBase) return held
            scoreSet = null
            return now
        }
        set(v) { if (live) { scoreSet = v; scoreBase = runScore() } else plainScore = v }
}

/** Lua members of Global (statics and getters) and ScoreKeeper/TopScore (WP6 slice C). Called by LuaAdaptersMore.install. */
object LuaAdaptersWP6Save {
    private val current = LuaTopScore(true)

    // Global::dronePosition / weaponPosition: `static Point*` into the vanilla globals, read-only in Lua. The oracle's
    // values (rows `global|dronePosition`, `global|weaponPosition`) are the constants of Global.cpp's static data.
    private val dronePosition = PointValue(0, 0)
    private val weaponPosition = PointValue(202, 651)

    @JvmStatic
    fun install() {
        // Global::currentSeed is `unsigned int`; the class member and the Hyperspace.Global_currentSeed alias
        val seed = NumGet { (xyz.znix.xftl.hs.sector.HsSeeds.currentSeed.toLong() and 0xffffffffL).toDouble() }
        LuaReg.num(A.A_Global_currentSeed, seed)
        LuaReg.num(A.A_module_Global_currentSeed, seed)
        LuaReg.obj(A.A_Global_dronePosition, ObjGet { dronePosition })
        LuaReg.obj(A.A_module_Global_dronePosition, ObjGet { dronePosition })
        LuaReg.obj(A.A_Global_weaponPosition, ObjGet { weaponPosition })
        LuaReg.obj(A.A_module_Global_weaponPosition, ObjGet { weaponPosition })

        LuaReg.method(A.M_Global_GetBlueprints, LuaMethod { ret.obj(LuaBlueprints, A.T_BlueprintManager) })
        LuaReg.method(A.M_Global_GetSoundControl, LuaMethod { ret.obj(LuaSounds, A.T_SoundControl) })
        LuaReg.method(A.M_Global_GetAnimationControl, LuaMethod { ret.obj(LuaAnimations, A.T_AnimationControl) })
        LuaReg.method(A.M_Global_GetScoreKeeper, LuaMethod { ret.obj(LuaScoreKeeper, A.T_ScoreKeeper) })
        LuaReg.method(A.M_Global_GetTutorialManager, LuaMethod { ret.obj(LuaTutorial, A.T_TutorialManager) })
        LuaReg.method(A.M_Global_GetMouseControl, LuaMethod { ret.obj(LuaMouse, A.T_MouseControl) })
        LuaReg.method(A.M_Global_GetTextLibrary, LuaMethod { ret.obj(LuaText, A.T_TextLibrary) })

        LuaReg.obj(A.A_module_Score, ObjGet { LuaScoreKeeper })
        LuaReg.obj(A.A_module_Sounds, ObjGet { LuaSounds })

        // ScoreKeeper::currentScore (immutable member), TopScore::sector / score
        LuaReg.obj(A.A_ScoreKeeper_currentScore, ObjGet { current })
        LuaReg.num(A.A_TopScore_sector, NumGet { (it as LuaTopScore).sector.toDouble() }, NumSet { o, v -> (o as LuaTopScore).sector = v.toInt() })
        LuaReg.num(A.A_TopScore_score, NumGet { (it as LuaTopScore).score.toDouble() }, NumSet { o, v -> (o as LuaTopScore).score = v.toInt() })
        LuaReg.method(A.M_new_TopScore, LuaMethod { ret.obj(LuaTopScore(false), A.T_TopScore) })
    }
}
