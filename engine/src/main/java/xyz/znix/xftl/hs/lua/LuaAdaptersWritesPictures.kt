// Ported from FTL-Hyperspace (GPL-3.0-or-later) commit 1b05a07 (lua/modules/hyperspace.i members), not from the 1.23.2 tag.
package xyz.znix.xftl.hs.lua

import xyz.znix.xftl.Ship
import xyz.znix.xftl.systems.Shields
import xyz.znix.xftl.hs.lua.LuaApi as A

/** Members whose write changes the picture: the views a script writes to, and what the engine's drawing reads from them. */
object LuaAdaptersWritesPictures {
    private fun member(s: LuaStruct, a: Int) = LuaStructs.member(s, a) as LuaStruct
    private fun num(s: LuaStruct, a: Int) = (s.values[a] as? Double) ?: 0.0

    /** Ship::engineAnim, the first thruster (SWIG's array member is a pointer to its first element). */
    fun engineAnim(ship: Ship): LuaStruct {
        val an = LuaStructs.create(A.T_Animation)
        val info = member(an, A.A_Animation_info)
        info.values[A.A_AnimationDescriptor_numFrames] = 4.0
        info.values[A.A_AnimationDescriptor_frameWidth] = 22.0
        info.values[A.A_AnimationDescriptor_frameHeight] = 70.0
        info.values[A.A_AnimationDescriptor_imageWidth] = 88.0
        info.values[A.A_AnimationDescriptor_imageHeight] = 70.0
        val tracker = member(an, A.A_Animation_tracker)
        tracker.values[A.A_AnimationTracker_time] = 0.5
        tracker.values[A.A_AnimationTracker_loop] = true
        tracker.values[A.A_AnimationTracker_running] = true
        tracker.values[A.A_AnimationTracker_currentDelay] = -1.0
        an.values[A.A_Animation_fScale] = 1.0
        an.values[A.A_Animation_mask_x_size] = 22.0
        an.values[A.A_Animation_mask_y_size] = 70.0
        val pos = member(an, A.A_Animation_position)
        val start = xyz.znix.xftl.hs.render.HsThrusters.firstPos(ship) ?: ship.hsEngineAnimPos()
        pos.values[A.A_Pointf_x] = (start?.x ?: 0).toDouble()
        pos.values[A.A_Pointf_y] = (start?.y ?: 0).toDouble()
        val t0 = xyz.znix.xftl.hs.render.HsThrusters.firstTime(ship) ?: ship.hsEngineAnimTime()
        xyz.znix.xftl.hs.render.HsThrusters.firstName(ship)?.let { an.values[A.A_Animation_animName] = it }
        fun frameAt(t: Float): Int {
            val total = num(tracker, A.A_AnimationTracker_time).toFloat()
            val frames = num(info, A.A_AnimationDescriptor_numFrames).toInt()
            return if (total > 0f && frames > 0) (t / total * frames).toInt().coerceIn(0, frames - 1) else 0
        }
        tracker.values[A.A_AnimationTracker_current_time] = t0.toDouble()
        an.values[A.A_Animation_currentFrame] = frameAt(t0).toDouble()
        ship.hsEngineAnim = object : Ship.EngineAnimView {
            override fun frame() = num(an, A.A_Animation_currentFrame).toInt()
            override fun x() = num(pos, A.A_Pointf_x).toInt()
            override fun y() = num(pos, A.A_Pointf_y).toInt()
            override fun time() = num(tracker, A.A_AnimationTracker_current_time).toFloat()
            override fun updated(time: Float) {
                tracker.values[A.A_AnimationTracker_current_time] = time.toDouble()
                an.values[A.A_Animation_currentFrame] = frameAt(time).toDouble()
            }
        }
        return an
    }

    /** Shields::shieldImage and shieldPrimitive: the bubble draws what is written. */
    @JvmStatic fun install() {
        recomputed()
        fights()
        LuaReg.objSet[A.A_Shields_shieldImage] = ObjSet { o, v ->
            LuaStored.put(o, A.A_Shields_shieldImage, v)
            val t = v as? LuaTexture ?: return@ObjSet
            LuaGraphics.resolve(t)
            (o as Shields).room?.ship?.hsShieldImage = t.image
        }
        LuaReg.objSet[A.A_Shields_shieldPrimitive] = ObjSet { o, v ->
            LuaStored.put(o, A.A_Shields_shieldPrimitive, v)
            val t = (v as? LuaPrimitive)?.texture ?: return@ObjSet
            LuaGraphics.resolve(t)
            (o as Shields).room?.ship?.hsShieldImage = t.image
        }
    }

    internal fun setPos(an: LuaStruct, x: Double, y: Double) {
        val p = member(an, A.A_Animation_position)
        p.values[A.A_Pointf_x] = x; p.values[A.A_Pointf_y] = y
    }

    /** Animations the native frame writes again: a script's value lasts until the frame ends (oracle hw-g-wa, hw-g-sp). */
    private fun recomputed() {
        fun cachedAnim(a: Int, name: String?, x: Double, y: Double, frame: Double = 0.0) {
            LuaReg.objGet[a] = ObjGet { o ->
                LuaViews.cached(o!!, a) {
                    val spec = name?.let { HsLua.game?.gameContent?.animations?.getOrNull(it) }
                    (if (spec != null) LuaAnim.create(spec) else LuaStructs.create(A.T_Animation)).also { an ->
                        setPos(an, x, y); an.values[A.A_Animation_currentFrame] = frame
                    }
                }
            }
        }
        cachedAnim(A.A_WeaponAnimation_boostAnim, null, -1.0, -1.0)
        cachedAnim(A.A_WeaponAnimation_hackSparks, "stun_spark_big", 0.0, 0.0)
        cachedAnim(A.A_WeaponAnimation_explosionAnim, null, -32.0, -32.0, -1.0)
        for (a in intArrayOf(A.A_WeaponAnimation_boostAnim, A.A_WeaponAnimation_hackSparks, A.A_Fire_fireAnimation, A.A_Fire_smokeAnimation))
            LuaReg.objSet[a] = ObjSet { o, v -> LuaStored.frame(o, a, v) }
        // explosionAnim: after a write the next frame's weapon takes GetAnimation("explosion_random") in its place.
        LuaReg.objSet[A.A_WeaponAnimation_explosionAnim] = ObjSet { o, v ->
            LuaStored.frame(o, A.A_WeaponAnimation_explosionAnim, v)
            LuaStored.onNextFrame {
                val spec = HsLua.game?.gameContent?.animations?.getOrNull("explosion_random") ?: return@onNextFrame
                LuaStored.put(o, A.A_WeaponAnimation_explosionAnim, LuaAnim.create(spec).also { setPos(it, -32.0, -32.0) })
            }
        }
    }

    /** Values a fight reads (oracle hw-g-*): what the native objects hold from the start. */
    private fun fights() {
        LuaReg.objGet[A.A_WeaponSystem_repowerList] = ObjGet { o ->
            LuaViews.cached(o!!, A.A_WeaponSystem_repowerList) { ListView(A.T_vector_bool, 0, ArrayList<Any?>(List(4) { false })) }
        }
        val slide = LuaReg.objGet[A.A_WeaponAnimation_slideTracker]
        if (slide != null) LuaReg.objGet[A.A_WeaponAnimation_slideTracker] = ObjGet { o ->
            (slide.get(o) as? LuaStruct)?.also { it.values[A.A_AnimationTracker_running] = true }
        }
        // WeaponSystem::weaponsTrashList: every weapon the system made: those on the ship and those a script replaced.
        LuaReg.objGet[A.A_WeaponSystem_weaponsTrashList] = ObjGet { o ->
            val ship = (o as xyz.znix.xftl.AbstractSystem).room!!.ship
            ListView(A.T_vector_ProjectileFactory, A.T_ProjectileFactory, ArrayList<Any?>(ship.hsReplacedWeapons + ship.hardpoints.mapNotNull { it.weapon }))
        }
        LuaReg.objGet[A.A_HackingDrone_flashTracker] = ObjGet { o ->
            LuaViews.cached(o!!, A.A_HackingDrone_flashTracker) {
                LuaStructs.create(A.T_AnimationTracker).also {
                    it.values[A.A_AnimationTracker_time] = 1.0; it.values[A.A_AnimationTracker_running] = true; it.values[A.A_AnimationTracker_loop] = true
                }
            }
        }
        LuaReg.objGet[A.A_OuterHull_breach] = ObjGet { o ->
            LuaViews.cached(o!!, A.A_OuterHull_breach) {
                HsLua.game?.gameContent?.animations?.getOrNull("breach")?.let { LuaAnim.create(it) } ?: LuaStructs.create(A.T_Animation)
            }
        }
        LuaReg.objGet[A.A_OuterHull_heal] = ObjGet { o ->
            LuaViews.cached(o!!, A.A_OuterHull_heal) {
                LuaStructs.create(A.T_Animation).also { an ->
                    member(an, A.A_Animation_info).values[A.A_AnimationDescriptor_numFrames] = 11.0
                    val t = member(an, A.A_Animation_tracker)
                    t.values[A.A_AnimationTracker_time] = 1.0; t.values[A.A_AnimationTracker_running] = true
                }
            }
        }
        // Statics a script assigns back to themselves.
        LuaReg.objSet[A.A_StatBoostDefinition_statBoostDefs] = ObjSet { _, _ -> }
        LuaReg.objSet[A.A_StatBoostDefinition_savedStatBoostDefs] = ObjSet { _, _ -> }
    }

    /** Door::gotHit over the engine's tracker. */
    fun doorGotHit(d: xyz.znix.xftl.layout.Door): LuaStruct {
        val t = LuaStructs.create(A.T_AnimationTracker)
        push(d.gotHit, t)
        d.hsGotHitIn = { pull(t, d.gotHit) }
        d.hsGotHitOut = { push(d.gotHit, t) }
        return t
    }

    /** Door::forcedOpen over the engine's tracker. */
    fun doorForced(d: xyz.znix.xftl.layout.Door): LuaStruct {
        val t = LuaStructs.create(A.T_AnimationTracker)
        push(d.forcedOpen, t)
        d.hsForcedIn = { pull(t, d.forcedOpen) }
        d.hsForcedOut = { push(d.forcedOpen, t) }
        return t
    }

    /** Door::doorAnim: its position moves the door's picture (Door::OnRender draws the animation there). */
    fun doorAnim(d: xyz.znix.xftl.layout.Door, moves: Boolean = true): LuaStruct {
        val an = LuaStructs.create(A.T_Animation)
        val pos = member(an, A.A_Animation_position)
        val info = member(an, A.A_Animation_info)
        info.values[A.A_AnimationDescriptor_numFrames] = 5.0
        info.values[A.A_AnimationDescriptor_frameWidth] = 35.0
        info.values[A.A_AnimationDescriptor_frameHeight] = 35.0
        member(an, A.A_Animation_tracker).values[A.A_AnimationTracker_time] = 0.2
        member(an, A.A_Animation_tracker).values[A.A_AnimationTracker_done] = true
        an.values[A.A_Animation_fScale] = 1.0
        if (moves) pos.onWrite = {
            d.hsAnimShiftX = num(pos, A.A_Pointf_x).toInt()
            d.hsAnimShiftY = num(pos, A.A_Pointf_y).toInt()
        }
        return an
    }

    /** A tracker a script may have written: the engine's copy follows the struct before it is used. */
    private fun pull(from: LuaStruct, to: Shields.ShieldTracker) {
        to.running = from.values[A.A_AnimationTracker_running] == true
        to.finished = from.values[A.A_AnimationTracker_done] == true
        to.current = num(from, A.A_AnimationTracker_current_time).toFloat()
        to.time = num(from, A.A_AnimationTracker_time).toFloat()
    }
    private fun push(from: Shields.ShieldTracker, to: LuaStruct) {
        to.values[A.A_AnimationTracker_running] = from.running
        to.values[A.A_AnimationTracker_current_time] = from.current.toDouble()
        to.values[A.A_AnimationTracker_time] = from.time.toDouble()
        to.values[A.A_AnimationTracker_done] = from.finished
    }

    /** From the Shields members' coordinates to the engine's drawing ones. */
    fun luaOffset(s: Shields): Pair<Int, Int> {
        val ship = s.room?.ship ?: return Pair(0, 0)
        val c = LuaReg.objGet[A.A_Shields_center]?.get(s)
        val cx = when (c) { is LuaPointMember -> c.x; is PointValue -> c.x; else -> ship.shieldOrigin.x }
        val cy = when (c) { is LuaPointMember -> c.y; is PointValue -> c.y; else -> ship.shieldOrigin.y }
        return Pair(ship.shieldOrigin.x - cx, ship.shieldOrigin.y - cy)
    }

    /** The scripts' Shields trackers into the engine (before it draws or runs a frame). */
    @JvmStatic fun shieldsIn(s: Shields) {
        val st = LuaAdaptersWP5Systems.shieldsOrNull(s) ?: return
        val off = luaOffset(s)
        s.downX = st.downX.toInt() + off.first; s.downY = st.downY.toInt() + off.second
        s.superUpX = st.superUpX + off.first; s.superUpY = st.superUpY + off.second
        s.superShieldDown = st.superShieldDown
        pull(st.shieldsDown, s.downTracker); pull(st.shieldsUp, s.upTracker); pull(st.superShieldUp, s.superUpTracker)
    }
    /** The engine's trackers out to what scripts read. */
    @JvmStatic fun shieldsOut(s: Shields) {
        val st = LuaAdaptersWP5Systems.shieldsOrNull(s) ?: return
        val off = luaOffset(s)
        if (s.downTracker.running) { st.downX = (s.downX - off.first).toFloat(); st.downY = (s.downY - off.second).toFloat() }
        if (s.superUpTracker.running) { st.superUpX = s.superUpX - off.first; st.superUpY = s.superUpY - off.second }
        st.superShieldDown = s.superShieldDown
        push(s.downTracker, st.shieldsDown); push(s.upTracker, st.shieldsUp); push(s.superUpTracker, st.superShieldUp)
    }
}
