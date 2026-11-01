// Ported from FTL-Hyperspace (GPL-3.0-or-later) commit 1b05a07 (lua/modules/hyperspace.i members), not from the 1.23.2 tag.
package xyz.znix.xftl.hs.lua

import xyz.znix.xftl.AbstractSystem
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.hs.crew.HsCrew
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint
import xyz.znix.xftl.weapons.AbstractWeaponInstance
import xyz.znix.xftl.weapons.BeamBlueprint
import xyz.znix.xftl.weapons.Damage
import xyz.znix.xftl.hs.lua.LuaApi as A
import xyz.znix.xftl.hs.lua.LuaArgs as arg
import xyz.znix.xftl.hs.lua.LuaPush as ret

/**
 * Values scripts wrote to members the engine keeps no field for (hyperspace.i leaves them writable, nothing here reads
 * them), per object and attribute. [LuaBridge] answers reads from here first, so a member reads back what was written.
 * A member the original recomputes every frame is written with [frame]: its value holds until the next frame ends.
 */
object LuaStored {
    private const val SLOT = -7
    /** Attributes that have (or had) a stored value on some object: every read of any other attribute costs one array read. */
    @JvmField val active = BooleanArray(A.ATTR_COUNT + 1)
    private class Frame(@JvmField val owner: Any, @JvmField val attr: Int)
    private val frameValues = ArrayList<Frame>()
    private val nextFrame = ArrayList<() -> Unit>()
    /** Counts the game frames: what's recomputed each frame looks at it. */
    @JvmField var frameNumber = 0

    private fun slots(o: Any): HashMap<Int, Any?> = LuaViews.cached(o, SLOT) { HashMap<Int, Any?>() }

    @JvmStatic fun has(o: Any?, a: Int): Boolean = o != null && slots(o).containsKey(a)
    @JvmStatic fun get(o: Any?, a: Int): Any? = o?.let { slots(it)[a] }
    @JvmStatic fun put(o: Any?, a: Int, v: Any?) {
        val owner = o ?: throw LuaError("static member written: " + A.attrName(a))
        active[a] = true
        slots(owner)[a] = v
    }
    fun clear(o: Any, a: Int) { slots(o).remove(a) }
    /** [put], until the next frame ends. */
    fun frame(o: Any?, a: Int, v: Any?) {
        put(o, a, v)
        frameValues += Frame(o!!, a)
    }
    /** Runs [f] when the next frame ends (the original's next OnLoop looked at the written value). */
    fun onNextFrame(f: () -> Unit) { nextFrame += f }

    /** One game frame has run. */
    @JvmStatic fun endFrame() {
        frameNumber++
        if (frameValues.isEmpty() && nextFrame.isEmpty()) return
        for (e in frameValues) slots(e.owner).remove(e.attr)
        frameValues.clear()
        if (nextFrame.isNotEmpty()) {
            val l = ArrayList(nextFrame); nextFrame.clear()
            for (f in l) f()
        }
    }
    fun reset() { active.fill(false); frameValues.clear(); nextFrame.clear() }
}

/** Reads and writes that go through what a script sees, and SWIG's by-value member copy. */
object LuaWritable {
    @JvmStatic fun readNum(o: Any?, a: Int): Double? {
        if (LuaStored.active[a] && LuaStored.has(o, a)) return (LuaStored.get(o, a) as? Number)?.toDouble() ?: if (LuaStored.get(o, a) == true) 1.0 else 0.0
        return LuaReg.numGet[a]?.get(o)
    }
    @JvmStatic fun readStr(o: Any?, a: Int): String? {
        if (LuaStored.active[a] && LuaStored.has(o, a)) return LuaStored.get(o, a) as? String
        return LuaReg.strGet[a]?.get(o)
    }
    @JvmStatic fun readObj(o: Any?, a: Int): Any? {
        if (LuaStored.active[a] && LuaStored.has(o, a)) return LuaStored.get(o, a)
        return LuaReg.objGet[a]?.get(o)
    }

    /** The setter of a number or boolean member without an adapter: the value is kept and read back. */
    @JvmStatic fun fallbackNum(o: Any?, a: Int, v: Double) = LuaStored.put(o, a, if (A.attrKind(a) == 2) v != 0.0 else v)
    @JvmStatic fun fallbackStr(o: Any?, a: Int, v: String) = LuaStored.put(o, a, v)
    /** The setter of an object member: a pointer is kept; an embedded member or value is copied into the owner's. */
    @JvmStatic fun fallbackObj(o: Any?, a: Int, v: Any?) {
        when (A.attrKind(a)) {
            5, 6 -> {
                val dst = readObj(o, a)
                if (dst == null || v == null) LuaStored.put(o, a, v) else copyInto(dst, v, A.attrType(a), 0)
            }
            else -> LuaStored.put(o, a, v)
        }
    }

    /** `dst = src` for a struct or vector member: field by field through the members' own setters. */
    fun copyInto(dst: Any, src: Any, type: Int, depth: Int) {
        if (dst === src || depth > 8) return
        if (dst is ListView) {
            val from = src as? ListView ?: return
            if (sameElements(dst, from)) return
            try {
                val items = (0 until from.size()).map { from.get(it) }
                dst.clear()
                for (e in items) dst.add(e)
            } catch (_: LuaError) {
                // a vector computed from the engine's state: its contents are the engine's, the copy has nowhere to go
            }
            return
        }
        if (dst is LuaStruct && src is LuaStruct) { dst.values.clear(); for ((k, v) in LuaStructs.copy(src).values) dst.values[k] = v; return }
        val t = if (type != 0) type else (dst as? LuaTyped)?.luaType ?: return
        for (a in LuaStructs.attrsOf(t)) {
            when (A.attrKind(a)) {
                1, 2 -> copyNum(dst, src, a)
                3 -> {
                    val v = try { readStr(src, a) } catch (_: Throwable) { continue } ?: continue
                    try { LuaReg.strSet[a]?.set(dst, v) ?: fallbackStr(dst, a, v) } catch (_: LuaError) { fallbackStr(dst, a, v) }
                }
                4 -> {
                    val v = try { readObj(src, a) } catch (_: Throwable) { continue }
                    try { LuaReg.objSet[a]?.set(dst, v) ?: LuaStored.put(dst, a, v) } catch (_: LuaError) { LuaStored.put(dst, a, v) }
                }
                5, 6 -> {
                    val v = try { readObj(src, a) } catch (_: Throwable) { continue } ?: continue
                    val d = try { readObj(dst, a) } catch (_: Throwable) { null }
                    if (d == null) LuaStored.put(dst, a, v) else copyInto(d, v, A.attrType(a), depth + 1)
                }
            }
        }
    }

    private fun copyNum(dst: Any, src: Any, a: Int) {
        val v = try { readNum(src, a) } catch (_: Throwable) { return } ?: return
        val own = try { readNum(dst, a) } catch (_: Throwable) { null }
        if (own == v) return
        // an immutable accessor over the engine's state (a view's second field): the value reads back until overwritten
        try { LuaReg.numSet[a]?.set(dst, v) ?: fallbackNum(dst, a, v) } catch (_: LuaError) { fallbackNum(dst, a, v) }
    }

    private fun sameElements(a: ListView, b: ListView): Boolean {
        if (a.size() != b.size()) return false
        for (i in 0 until a.size()) {
            val x = a.get(i); val y = b.get(i)
            if (x === y || x == y) continue
            if (x is PointfValue && y is PointfValue && x.x == y.x && x.y == y.y) continue
            if (x is PointValue && y is PointValue && x.x == y.x && x.y == y.y) continue
            return false
        }
        return true
    }
}

/**
 * The members hyperspace.i leaves writable that the earlier packages bound read-only: what a write does in the original
 * (tools/hs-oracle sessions prod-hs.json, PH.*). Installed last. A member with no case here goes through
 * [LuaWritable]: the value is kept and read back, a struct or vector member copied through its fields' setters.
 */
object LuaAdaptersWritable {
    @JvmStatic
    fun install() {
        crew()
        systems()
        weapons()
        blueprints()
        world()
        ellipses()
        targetable()
    }

    private fun c(o: Any?) = o as? AbstractCrew ?: throw LuaError("expected CrewMember")
    private fun numFrame(a: Int) { LuaReg.numSet[a] = NumSet { o, v -> LuaStored.frame(o, a, if (A.attrKind(a) == 2) v != 0.0 else v) } }

    private fun boolGetter(a: Int, f: (Any?) -> Boolean) { if (LuaReg.numGet[a] == null) LuaReg.numGet[a] = NumGet { if (f(it)) 1.0 else 0.0 } }

    // ---------------------------------------------------------------- CrewMember
    private fun crew() {
        boolGetter(A.A_CrewMember_bSuffocating) { o -> c(o).room.oxygen < 0.05f }
        boolGetter(A.A_CrewMember_intruder) { o -> (c(o) as? LivingCrew)?.hsMindControlled == true || LuaAdapters.crewShipId(c(o)) != (LuaReg.numGet[A.A_CrewMember_currentShipId]?.get(o) ?: 0.0).toInt() }
        if (LuaReg.strGet[A.A_CrewMember_species] == null) LuaReg.strGet[A.A_CrewMember_species] = StrGet { c(it).hsSpecies }
        if (LuaReg.strGet[A.A_CrewMember_type] == null) LuaReg.strGet[A.A_CrewMember_type] = StrGet { c(it).hsSpecies }
        // The next frame's UpdateMovement / manning pass recomputes these (oracle ph-ci rows).
        for (a in intArrayOf(A.A_CrewMember_bActiveManning, A.A_CrewMember_iManningId, A.A_CrewMember_iRoomId, A.A_CrewMember_bSuffocating, A.A_CrewMember_intruder)) numFrame(a)
        LuaReg.strSet[A.A_CrewMember_species] = StrSet { o, v -> LuaStored.frame(o, A.A_CrewMember_species, v) }
        // bDead: the next frame's ship loop drops the crew member: out of the game, currentShipId -1, one fewer aboard.
        LuaReg.numSet[A.A_CrewMember_bDead] = NumSet { o, v ->
            LuaStored.put(o, A.A_CrewMember_bDead, v != 0.0)
            if (v != 0.0) {
                val x = c(o)
                LuaAdaptersWrites.movedAway = true
                LuaStored.onNextFrame {
                    // Health stays as it was (oracle hw-cd): the member only leaves the ship's list.
                    LuaAdaptersWrites.leaveShip(x)
                    LuaStored.put(x, A.A_CrewMember_bOutOfGame, true)
                    LuaStored.put(x, A.A_CrewMember_currentShipId, -1.0)
                }
            }
        }
        LuaReg.numSet[A.A_CrewMember_bMindControlled] = NumSet { o, v ->
            val x = c(o) as? LivingCrew ?: throw LuaError("expected a living crew member")
            x.hsMindControlled = v != 0.0
        }
        // iShipId: the crew member changes sides at once; currentShipId stays, so it now counts as an intruder.
        LuaReg.numSet[A.A_CrewMember_iShipId] = NumSet { o, v ->
            val x = c(o) as? LivingCrew ?: throw LuaError("expected a living crew member")
            val g = LuaAdapters.inGame() ?: return@NumSet
            x.ownerShip = if (v.toInt() == 0) g.player else g.enemy
        }
        // currentShipId: CountCrew stops counting the member at once, the next frame's loop takes it off the ship's list (oracle hw-cs2).
        LuaReg.numSet[A.A_CrewMember_currentShipId] = NumSet { o, v ->
            val x = c(o)
            LuaAdaptersSweepCrew.setCurrentShipId(x, v.toInt())
            if (v.toInt() != LuaAdapters.shipId(x.room.ship)) {
                LuaAdaptersWrites.movedAway = true
                LuaStored.onNextFrame { LuaAdaptersWrites.leaveShip(x) }
            }
        }
        // bOutOfGame: set with bDead when the death animation ends (oracle hw-dtw).
        LuaReg.numGet[A.A_CrewMember_bOutOfGame]?.let { before ->
            LuaReg.numGet[A.A_CrewMember_bOutOfGame] = NumGet { o ->
                if (before.get(o) != 0.0) 1.0 else LuaReg.numGet[A.A_CrewMember_bDead]?.get(o) ?: 0.0
            }
        }
        // Methods that read the fields written above.
        wrapBool(A.M_CrewMember_IsDead) { o -> if (LuaStored.active[A.A_CrewMember_bDead] && LuaStored.get(o, A.A_CrewMember_bDead) == true) true else null }
        wrapBool(A.M_CrewMember_GetControllable) { o ->
            when {
                LuaStored.active[A.A_CrewMember_bDead] && LuaStored.get(o, A.A_CrewMember_bDead) == true -> false
                (c(o) as? LivingCrew)?.hsMindControlled == true -> false
                else -> null
            }
        }
        // GetIntruder compares the ship the crew member stands on with its owner's.
        wrapBool(A.M_CrewMember_GetIntruder) { o ->
            val x = c(o); val cur = LuaWritable.readNum(o, A.A_CrewMember_currentShipId)?.toInt()
            if (cur != null && cur != LuaAdapters.crewShipId(x)) true else null
        }
        // CanRepair reads the intruder field.
        wrapBool(A.M_CrewMember_CanRepair) { o ->
            when {
                LuaStored.active[A.A_CrewMember_bDead] && LuaStored.get(o, A.A_CrewMember_bDead) == true -> false
                LuaStored.active[A.A_CrewMember_intruder] && LuaStored.get(o, A.A_CrewMember_intruder) == true -> false
                (c(o) as? LivingCrew)?.hsMindControlled == true -> false
                else -> null
            }
        }
        // The frame's movement pass sets intruder from the mind control flag too (oracle ph-ci3).
        LuaReg.numGet[A.A_CrewMember_intruder]?.let { before ->
            LuaReg.numGet[A.A_CrewMember_intruder] = NumGet { o -> if ((c(o) as? LivingCrew)?.hsMindControlled == true) 1.0 else before.get(o) }
        }
        // iRepairId stays until the crew member starts or stops a repair.
        LuaReg.numSet[A.A_CrewMember_iRepairId] = NumSet { o, v -> LuaStored.put(o, A.A_CrewMember_iRepairId, v) }
        wrapStr(A.M_CrewMember_GetSpecies) { o -> if (LuaStored.active[A.A_CrewMember_species]) LuaStored.get(o, A.A_CrewMember_species) as? String else null }
    }

    private fun wrapBool(m: Int, f: (Any?) -> Boolean?) {
        val before = LuaReg.methods[m] ?: return
        LuaReg.method(m, LuaMethod { n -> val r = f(arg.obj(1)); if (r != null) ret.bool(r) else before.call(n) })
    }
    private fun wrapStr(m: Int, f: (Any?) -> String?) {
        val before = LuaReg.methods[m] ?: return
        LuaReg.method(m, LuaMethod { n -> val r = f(arg.obj(1)); if (r != null) ret.str(r) else before.call(n) })
    }

    // ---------------------------------------------------------------- ShipSystem
    private fun systems() {
        fun s(o: Any?) = o as? AbstractSystem ?: throw LuaError("expected ShipSystem")
        // SetBonusPower's field: the frame's crew count replaces it (oracle ph-s s1/s2).
        // iBonusPower: counts in GetEffectivePower until the next frame's power pass recomputes it (oracle ph-s s1-bonus, s2-sh).
        LuaReg.numSet[A.A_ShipSystem_iBonusPower] = NumSet { o, v -> LuaStored.frame(s(o), A.A_ShipSystem_iBonusPower, v) }
        LuaReg.numSet[A.A_ShipSystem_iBatteryPower] = NumSet { o, v -> LuaWritableSystems.batteryPower(s(o), v.toInt()) }
        // iHackEffect: above 1 the system is locked (ShipSystem::GetLocked); it stays until the hacking system changes it.
        LuaReg.numSet[A.A_ShipSystem_iHackEffect] = NumSet { o, v -> s(o).hsHackLevel = v.toInt() }
    }

    // ---------------------------------------------------------------- ProjectileFactory
    private fun weapons() {
        fun w(o: Any?) = o as? AbstractWeaponInstance ?: throw LuaError("expected ProjectileFactory")
        // ProjectileFactory::Update recomputes these from the blueprint, the system and the aim every frame (oracle ph-p p1/p2).
        for (a in intArrayOf(A.A_ProjectileFactory_baseCooldown, A.A_ProjectileFactory_iBonusPower, A.A_ProjectileFactory_iHackLevel,
            A.A_ProjectileFactory_fireWhenReady, A.A_ProjectileFactory_targetId)) numFrame(a)
        // The weapon's own copies, made when it is built: the blueprint's later writes do not reach them.
        LuaReg.numSet[A.A_ProjectileFactory_requiredPower] = NumSet { o, v -> w(o).hsRequiredPower = v.toInt() }
        LuaReg.numGet[A.A_ProjectileFactory_requiredPower] = NumGet { w(it).hsRequiredPower.toDouble() }
        LuaReg.numSet[A.A_ProjectileFactory_numShots] = NumSet { o, v -> w(o).hsNumShots = v.toInt() }
        LuaReg.numGet[A.A_ProjectileFactory_numShots] = NumGet { w(it).hsNumShots.toDouble() }
        LuaReg.numSet[A.A_ProjectileFactory_powered] = NumSet { o, v -> w(o).hsWritePowered(v != 0.0) }
        // cooldown.first is the charge; cooldown.second the goal, written by the next Update from the blueprint.
        LuaReg.objSet[A.A_ProjectileFactory_cooldown] = ObjSet { o, v ->
            val p = v as? FloatPairView ?: return@ObjSet
            w(o).hsWriteCharge(p.first)
            val view = LuaReg.objGet[A.A_ProjectileFactory_cooldown]?.get(o)
            if (view != null) LuaStored.frame(view, A.A_pair_float_float_second, p.second.toDouble())
        }
        // blueprint: charge goal and base cooldown follow it next frame; the weapon's name and required power keep their
        // copies. Stats read through type change with the pointer; a blueprint of another weapon family keeps the
        // weapon's own projectile kind.
        LuaReg.objSet[A.A_ProjectileFactory_blueprint] = ObjSet { o, v ->
            val b = v as? AbstractWeaponBlueprint ?: throw LuaError("expected WeaponBlueprint")
            val weapon = w(o)
            if (!LuaStored.has(o!!, A.A_ProjectileFactory_name)) LuaStored.put(o, A.A_ProjectileFactory_name, weapon.type.translateTitle(weapon.ship.sys))
            if (!LuaStored.has(o, A.A_ProjectileFactory_baseCooldown)) LuaStored.frame(o, A.A_ProjectileFactory_baseCooldown, weapon.type.chargeTime.toDouble())
            weapon.hsSetBlueprint(b)
        }
        LuaReg.objGet[A.A_ProjectileFactory_blueprint] = ObjGet { w(it).type }
    }

    // ---------------------------------------------------------------- WeaponBlueprint
    private fun blueprints() {
        fun b(o: Any?) = o as? AbstractWeaponBlueprint ?: throw LuaError("expected WeaponBlueprint")
        LuaReg.numSet[A.A_WeaponBlueprint_shots] = NumSet { o, v -> b(o).shots = v.toInt() }
        LuaReg.numSet[A.A_WeaponBlueprint_missiles] = NumSet { o, v -> b(o).missilesUsed = v.toInt() }
        LuaReg.numSet[A.A_WeaponBlueprint_power] = NumSet { o, v -> b(o).power = v.toInt() }
        LuaReg.numSet[A.A_WeaponBlueprint_speed] = NumSet { o, v -> b(o).speed = v.toInt() }
        LuaReg.numSet[A.A_WeaponBlueprint_chargeLevels] = NumSet { o, v -> b(o).chargeLevels = v.toInt() }
        LuaReg.numGet[A.A_WeaponBlueprint_chargeLevels] = NumGet { o -> (b(o).chargeLevels ?: 1).toDouble() }
        LuaReg.numSet[A.A_WeaponBlueprint_length] = NumSet { o, v ->
            LuaStored.put(o, A.A_WeaponBlueprint_length, v)
        }
        val lengthGet = LuaReg.numGet[A.A_WeaponBlueprint_length]
        LuaReg.numGet[A.A_WeaponBlueprint_length] = NumGet { o -> if (o is BeamBlueprint) lengthGet?.get(o) ?: -1.0 else -1.0 }
        // Damage is one object per blueprint: the next hit built from the weapon reads what was written into it.
        val damageGet = LuaReg.objGet[A.A_WeaponBlueprint_damage]
        LuaReg.objGet[A.A_WeaponBlueprint_damage] = ObjGet { o ->
            val d = damageGet?.get(o)
            if (o is AbstractWeaponBlueprint && d is Damage) o.hsLuaDamage = d
            d
        }
    }

    // ---------------------------------------------------------------- Shield ellipse
    private fun ellipses() {
        LuaReg.objSet[A.A_Shields_baseShield] = ObjSet { o, v ->
            val from = v as? LuaStruct ?: throw LuaError("expected an Ellipse")
            val c = LuaStructs.member(from, A.A_Ellipse_center) as? LuaStruct
            LuaAdaptersWP5Systems.shields(o as xyz.znix.xftl.systems.Shields).ellipse = (doubleArrayOf((c?.values?.get(A.A_Point_x) as? Double) ?: 0.0, (c?.values?.get(A.A_Point_y) as? Double) ?: 0.0,
                (from.values[A.A_Ellipse_a] as? Double) ?: 0.0, (from.values[A.A_Ellipse_b] as? Double) ?: 0.0))
        }
        // Ship.baseEllipse is read once, when the shields are built; a later write changes what scripts read.
        val shipGet = LuaReg.objGet[A.A_Ship_baseEllipse]
        LuaReg.objGet[A.A_Ship_baseEllipse] = ObjGet { o -> LuaStored.get(o, A.A_Ship_baseEllipse) ?: shipGet?.get(o) }
        LuaReg.objSet[A.A_Ship_baseEllipse] = ObjSet { o, v -> LuaStored.put(o, A.A_Ship_baseEllipse, v) }
    }

    // ---------------------------------------------------------------- StarMap, SpaceManager
    private fun world() {
        // dangerZone: the fleet's centre on the map; a write moves the fleet.
        val zoneGet = LuaReg.objGet[A.A_StarMap_dangerZone]
        LuaReg.objSet[A.A_StarMap_dangerZone] = ObjSet { o, v ->
            val zone = zoneGet?.get(o) as? PointValue ?: return@ObjSet
            val from = v as? PointValue ?: return@ObjSet
            zone.x = from.x; zone.y = from.y
            LuaAdapters.inGame()?.currentBeacon?.sector?.dangerZoneCentre?.let { it.x = from.x; it.y = from.y }
        }
        LuaReg.numSet[A.A_SpaceManager_envTarget] = NumSet { _, v ->
            LuaStored.put(HsStaticKey, A.A_SpaceManager_envTarget, v)
        }
        val envGet = LuaReg.numGet[A.A_SpaceManager_envTarget]
        LuaReg.numGet[A.A_SpaceManager_envTarget] = NumGet { o ->
            if (LuaStored.active[A.A_SpaceManager_envTarget] && LuaStored.has(HsStaticKey, A.A_SpaceManager_envTarget))
                (LuaStored.get(HsStaticKey, A.A_SpaceManager_envTarget) as Double)
            else envGet?.get(o) ?: 2.0
        }
        // Spreader_Fire::UpdateSpread recounts the fires every frame.
        numFrame(A.A_Spreader_Fire_count)
    }
    private object HsStaticKey

    // ---------------------------------------------------------------- Targetable::DamageTarget on a ship
    private fun targetable() {
        val before = LuaReg.methods[A.M_Targetable_DamageTarget]
        LuaReg.method(A.M_Targetable_DamageTarget, LuaMethod { n ->
            val ship = LuaAdaptersProjectiles.targetableOwner(arg.obj(1)) as? xyz.znix.xftl.Ship
                ?: return@LuaMethod (before ?: throw LuaError("Targetable method not available")).call(n)
            val p = arg.obj(2)
            val x: Float; val y: Float
            when (p) {
                is PointfValue -> { x = p.x; y = p.y }
                is LuaPointfMember -> { x = p.x; y = p.y }
                else -> throw LuaError("expected Pointf")
            }
            damageArea(ship, x, y, arg.obj(3))
            0
        })
    }

    /** ShipManager::DamageArea(location, dmg, force = true): the room under the point takes the hit; a point outside every room does nothing. */
    private fun damageArea(ship: xyz.znix.xftl.Ship, x: Float, y: Float, dmgObj: Any?) {
        val room: Room = ship.rooms.firstOrNull { it.containsPixelInclusive(x.toInt() - ship.offset.x * 35, y.toInt() - ship.offset.y * 35) }
            ?: ship.rooms.firstOrNull { it.containsPixelInclusive(x.toInt(), y.toInt()) } ?: return
        ship.damage(room, LuaAdaptersWritableDamage.of(dmgObj))
    }
}

/** The system power fields (WeaponSystem and DroneSystem run the same fields through their own adapters). */
object LuaWritableSystems {
    /**
     * iBatteryPower with no battery system behind it: it counts as power this frame; the next frame's power pass takes it
     * back and the system draws the bars from the reactor instead (oracle ph-s s1-batt/s2-en).
     */
    fun batteryPower(s: AbstractSystem, power: Int) {
        val m = s as? xyz.znix.xftl.systems.MainSystem ?: return
        m.hsLuaBatteryPower = power
        LuaStored.onNextFrame {
            m.hsLuaBatteryPower = 0
            repeat(power) { m.increasePower() }
        }
    }
}

/** A script's Damage object as the engine's. */
object LuaAdaptersWritableDamage {
    fun of(o: Any?): Damage {
        if (o is Damage) return o
        val d = Damage()
        fun n(a: Int): Int = (LuaWritable.readNum(o, a) ?: 0.0).toInt()
        fun b(a: Int): Boolean = (LuaWritable.readNum(o, a) ?: 0.0) != 0.0
        d.hullDamage = n(A.A_Damage_iDamage)
        d.ionDamage = n(A.A_Damage_iIonDamage)
        d.pureSysDamage = n(A.A_Damage_iSystemDamage)
        d.pureCrewDamage = n(A.A_Damage_iPersDamage) * 15
        d.emptyRoomBonus = if (b(A.A_Damage_bHullBuster)) 1 else 0
        d.fireChance = n(A.A_Damage_fireChance) * 10
        d.breachChance = n(A.A_Damage_breachChance) * 10
        d.stunChance = n(A.A_Damage_stunChance) * 10
        d.stunDuration = n(A.A_Damage_iStun).toFloat()
        d.lockdown = b(A.A_Damage_bLockdown)
        d.hsShieldPiercing = n(A.A_Damage_iShieldPiercing)
        return d
    }
}
