// HS GPL-3.0-or-later 1b05a07 lua/modules/hyperspace.i (BeamWeapon, WeaponAnimation, ProjectileFactory,
// WeaponSystem, WeaponBlueprint, WeaponMount members); native 1.6.14 BeamWeapon::SetMovingTarget 437670 /
// SetWeaponAnimation 753dc0, WeaponAnimation::SetMount 460810 / ComputeDirection 460870 / GetFireLocation 461640,
// ProjectileFactory::SetMount 40ce80 / NumTargetsRequired 40d5f0 / FireNextShot 40cfa0 / GetBoostLevel 40d6a0,
// WeaponSystem::ForceDecreasePower 635980.
package xyz.znix.xftl.hs.lua

import org.jdom2.Element
import xyz.znix.xftl.Ship
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.weapons.HsWeapons
import xyz.znix.xftl.hs.weapons.HsWeaponsLua
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.Direction
import xyz.znix.xftl.systems.Artillery
import xyz.znix.xftl.systems.SelectedTarget
import xyz.znix.xftl.systems.Weapons
import xyz.znix.xftl.weapons.*
import kotlin.math.atan2
import kotlin.math.hypot
import xyz.znix.xftl.hs.lua.LuaApi as A
import xyz.znix.xftl.hs.lua.LuaArgs as arg
import xyz.znix.xftl.hs.lua.LuaPush as ret

/**
 * The Lua members of the weapon launcher family that the earlier adapters did not cover. Engine mapping:
 * - ProjectileFactory is the engine's weapon instance; WeaponAnimation is [HsWeaponsLua.WeaponVisual] (the same
 *   object as `weaponVisual`); WeaponMount is the hardpoint, with the native (layout image relative) position.
 * - Members the engine does not model (lastTargets, WeaponSystem shot_timer/shot_count/missile_start, localPosition)
 *   keep the value a script writes, starting from FTL's zero, and record an unsupported use: nothing reads them.
 * - Animation-typed members are snapshots (struct copies) of the engine state; writes to them change only the copy.
 */
object LuaAdaptersArmament {
    /** WeaponBlueprint XML facts the engine blueprint does not keep (ProcessWeaponBlueprint fields). */
    class Facts(xml: Element) {
        var weaponArt: String = xml.getChildTextTrim("weaponArt") ?: "default" // the constructor's value (HS oracle)
        var combatIcon: String = xml.getChildTextTrim("iconImage") ?: ""
        var explosion: String = xml.getChildTextTrim("explosion") ?: ""
        val image: String = xml.getChildTextTrim("image") ?: ""
        var spin: Double = xml.getChildTextTrim("spin")?.toDoubleOrNull() ?: 0.0
        // Oracle wp4-armament: -1 unless the blueprint sets it.
        var droneTargetable: Double = xml.getChildTextTrim("drone_targetable")?.toDoubleOrNull() ?: -1.0
        // The real (not fake) mini projectiles: 3 for MV's SHOTGUN_2 in the oracle, as FlakBlueprint.projectileCount.
        var miniCount: Int = xml.getChild("projectiles")?.getChildren("projectile")
            ?.filter { it.getAttributeValue("fake") != "true" }?.sumOf { it.getAttributeValue("count")?.toIntOrNull() ?: 1 } ?: 0
        val sounds: Map<String, List<String>> = listOf("launchSounds", "hitShipSounds", "hitShieldSounds", "missSounds")
            .associateWith { n -> xml.getChild(n)?.getChildren("sound")?.map { it.textTrim } ?: emptyList() }
    }

    /** Values of members the engine has no state for, per owner, as FTL leaves them (zero) until a script writes. */
    private val stored = xyz.znix.xftl.hs.lua.HsRunMaps.any(java.util.IdentityHashMap<Any, HashMap<Int, Double>>())
    private fun storedGet(o: Any, a: Int): Double = stored[o]?.get(a) ?: 0.0
    private fun chargeAI(weapon: xyz.znix.xftl.weapons.AbstractWeaponInstance): xyz.znix.xftl.ai.ShipAI? =
        LuaAdapters.inGame()?.enemyAI?.takeIf { it.ship === weapon.ship }
    private fun chargeGoal(weapon: xyz.znix.xftl.weapons.AbstractWeaponInstance): Int = chargeAI(weapon)?.chargeGoalOf(weapon)
        ?: stored[weapon]?.get(A.A_ProjectileFactory_goalChargeLevel)?.toInt() ?: weapon.maxTotalCharges

    private fun storedSet(o: Any, a: Int, v: Double, owner: String) {
        stored.getOrPut(o) { HashMap() }[a] = v
        HS.unsupported("lua", "attribute", "$owner.${A.attrName(a).substringAfter('.')} (stored, not used by the engine)")
    }
    private fun storedNum(a: Int, owner: String) =
        LuaReg.num(a, NumGet { o -> storedGet(o!!, a) }, NumSet { o, v -> storedSet(o!!, a, v, owner) })
    private fun storedBool(a: Int, owner: String) =
        LuaReg.bool(a, { o -> storedGet(o!!, a) != 0.0 }, { o, v -> storedSet(o!!, a, if (v) 1.0 else 0.0, owner) })

    private fun w(o: Any?) = o as? AbstractWeaponInstance ?: throw LuaError("expected ProjectileFactory")
    private fun visual(o: Any?) = (o as? HsWeaponsLua.WeaponVisual)?.w ?: throw LuaError("expected WeaponAnimation")
    private fun beam(o: Any?) = o as? BeamProjectile ?: throw LuaError("expected BeamWeapon body")
    private fun visualOf(weapon: AbstractWeaponInstance): HsWeaponsLua.WeaponVisual =
        LuaViews.cached(weapon, A.A_ProjectileFactory_weaponVisual) { HsWeaponsLua.WeaponVisual(it as AbstractWeaponInstance) }
    private fun targetable(ship: Ship): LuaAdaptersBeam.TargetableMember =
        LuaViews.cached(ship, A.A_BeamWeapon_movingTarget) { LuaAdaptersBeam.TargetableMember(ship) }

    private fun slot(weapon: AbstractWeaponInstance) = weapon.ship.hardpoints.indexOfFirst { it.weapon === weapon }
    // An artillery's projectileFactory has its own hardpoint after the weapon slots (oracle wp7r-arty: boss mount gib 4, x 163, y 6)
    private fun hardpoint(weapon: AbstractWeaponInstance) = weapon.ship.hardpoints.firstOrNull { it.weapon === weapon }?.spec
        ?: weapon.ship.artillery.firstOrNull { it.hsWeapon === weapon }?.hsHardpoint

    /** The native WeaponMount::position: the layout's mount x/y, relative to the hull image's layout origin. */
    private fun nativeMountPosition(weapon: AbstractWeaponInstance): ConstPoint {
        val hp = hardpoint(weapon) ?: return ConstPoint(-1, -1)
        val t = weapon.ship.type
        return ConstPoint(hp.position.x - t.hullOffset.x - t.roomOffset.x * 35, hp.position.y - t.hullOffset.y - t.roomOffset.y * 35)
    }

    /**
     * WeaponMount::slide: the side an unpowered launcher sits at (GetSlide: 0 up, 1 left, 2 down, 3 right; 4 none,
     * WeaponAnimation::ComputeDirection's default), the opposite of the layout's `slide` direction the engine keeps
     * (oracle wp4-armament: a slide="up" mount reads 2, slide="down" 0).
     */
    private fun nativeSlide(dir: Direction?): Int = when (dir) {
        Direction.DOWN -> 0; Direction.RIGHT -> 1; Direction.UP -> 2; Direction.LEFT -> 3; else -> 4
    }

    /** WeaponAnimation::slideDirection after ComputeDirection: a mount without slide gets (mirror ? 2 : 0) + !rotate. */
    private fun slideDirection(weapon: AbstractWeaponInstance): Int {
        val hp = hardpoint(weapon) ?: return 4
        val d = nativeSlide(hp.slide)
        if (d != 4) return d
        val notRotated = if (hp.rotate) 0 else 1
        return if (hp.mirror) notRotated + 2 else notRotated
    }

    private fun mount(weapon: AbstractWeaponInstance, key: Int = A.A_ProjectileFactory_mount): LuaStruct {
        // The original writes the mount again each frame (oracle hw-f-w): a script's write lasts until the frame ends.
        val s = LuaViews.cached(weapon, key) { LuaStructs.create(A.T_WeaponMount) }
        val filled = LuaViews.cached(weapon, -key) { intArrayOf(-1) }
        if (filled[0] == LuaStored.frameNumber) return s
        filled[0] = LuaStored.frameNumber
        val hp = hardpoint(weapon)
        val p = nativeMountPosition(weapon)
        s.values.remove(A.A_WeaponMount_position)
        val pos = LuaStructs.member(s, A.A_WeaponMount_position) as LuaStruct
        pos.values[A.A_Point_x] = p.x.toDouble(); pos.values[A.A_Point_y] = p.y.toDouble()
        s.values[A.A_WeaponMount_mirror] = hp?.mirror ?: false
        s.values[A.A_WeaponMount_rotate] = hp?.rotate ?: false
        s.values[A.A_WeaponMount_slide] = nativeSlide(hp?.slide).toDouble()
        s.values[A.A_WeaponMount_gib] = (hp?.gib ?: 0).toDouble()
        return s
    }

    private fun point(owner: Any, key: Int, x: Int, y: Int): LuaHeldPoint {
        val p = LuaViews.cached(owner, key) { LuaHeldPoint(x, y) }
        if (p.engineX != x || p.engineY != y) { p.engineX = x; p.engineY = y; p.x = x; p.y = y }
        return p
    }

    /** An Animation struct copy of [spec], with its tracker at [time] seconds in. */
    private fun animation(owner: Any, key: Int, spec: xyz.znix.xftl.AnimationSpec?, time: Float = 0f): LuaStruct {
        if (spec == null) return LuaViews.cached(owner, key) { LuaStructs.create(A.T_Animation) }
        val an = LuaViews.cached(owner, key) { LuaAnim.create(spec) }
        val tracker = LuaStructs.member(an, A.A_Animation_tracker) as LuaStruct
        tracker.values[A.A_AnimationTracker_current_time] = time.toDouble()
        return an
    }

    /** The engine's aim of [weapon] in its target ship's space: room centres, or a beam's start and end. */
    private fun aimPoints(weapon: AbstractWeaponInstance): List<Any?> {
        val target = weapon.ship.weapons?.selectedTargets?.getTarget(slot(weapon)) ?: return emptyList()
        return aimPoints(target)
    }

    private fun aimPoints(target: SelectedTarget): List<Any?> {
        return when (target) {
            is SelectedTarget.RoomAim -> listOf(PointfValue(target.room.pixelCentre.x.toFloat(), target.room.pixelCentre.y.toFloat()))
            is SelectedTarget.BeamAim -> {
                val s = target.startShipPoint
                listOf(PointfValue(s.x.toFloat(), s.y.toFloat()),
                    PointfValue(s.x + target.length * kotlin.math.cos(target.angle), s.y + target.length * kotlin.math.sin(target.angle)))
            }
            else -> emptyList()
        }
    }

    /** ProjectileFactory::SetCurrentShip from Lua: (the selected target at the call, the script's ship). */
    private val luaCurrentShip = xyz.znix.xftl.hs.lua.HsRunMaps.run(java.util.IdentityHashMap<AbstractWeaponInstance, Pair<Ship?, Ship?>>())

    private fun currentShip(weapon: AbstractWeaponInstance): Ship? {
        val target = targetShip(weapon)
        // Without an aim the weapon has the ship's current target (ShipManager::AddWeapon, BeamFixes.cpp).
        val set = luaCurrentShip[weapon] ?: return target ?: weapon.ship.sys.getEnemyOf(weapon.ship)
        if (set.first !== target) { luaCurrentShip.remove(weapon); return target }
        return set.second
    }

    private fun targetShip(weapon: AbstractWeaponInstance): Ship? =
        weapon.ship.weapons?.selectedTargets?.getTarget(slot(weapon))?.targetShip
            ?: (weapon as? BeamBlueprint.BeamInstance)?.liveBeam?.movingTarget

    /** iHackLevel: 0, 1 with a hacking drone on the weapons system, 2 during the hacking pulse. */
    private fun hackLevel(weapon: AbstractWeaponInstance): Int {
        weapon.hsLuaHackLevel?.let { return it }
        val sys = weapon.ship.weapons ?: return 0
        return when {
            sys.isHackActive -> 2
            sys.hackedBy != null || sys.hsHackLevel > 0 -> 1
            else -> 0
        }
    }

    private fun fireAngle(weapon: AbstractWeaponInstance) = if (weapon.ship.weaponFireDirection == Direction.RIGHT) 0.0 else 270.0

    fun install() {
        // std::vector<Pointf>() (SWIG overload 0): an empty vector owned by Lua (ProjectileFactory::Fire's points).
        LuaReg.method(A.M_new_vector_Pointf__SWIG_0, LuaMethod { ret.obj(ListView(A.T_vector_Pointf, A.T_Pointf, ArrayList<Any?>()), A.T_vector_Pointf) })
        // ShipManager::AddWeapon(bp, slot) 49f760: 0 without a weapon system. Else WeaponSystem::AddWeapon: a slot beyond the
        // list (or -1) appends while the list is not longer than slot_count, returning 0; a slot in the list replaces that
        // weapon, the old one's power given back, and returns the old weapon's cost.
        LuaReg.method(A.M_ShipManager_AddWeapon, LuaMethod {
            val ship = arg.obj(1) as? Ship ?: throw LuaError("expected ShipManager")
            val bp = arg.obj(2) as? AbstractWeaponBlueprint ?: throw LuaError("expected WeaponBlueprint")
            if (ship.weapons == null) return@LuaMethod ret.num(0.0)
            val slotCount = ship.hsWeaponSlots ?: ship.weaponSlots ?: ship.hardpoints.size
            val wanted = arg.int(3)
            val filled = ship.hardpoints.filter { it.weapon != null }
            if (wanted == -1 || filled.size <= wanted) {
                if (filled.size <= slotCount) {
                    val free = ship.hardpoints.firstOrNull { it.weapon == null } ?: ship.hsExtraHardpoint()
                    free.weapon = bp.buildInstance(ship)
                    ship.cargoUpdated()
                } else {
                    // ShipObject::AddEquipment runs first, so a weapon with no slot still counts in HasEquipment.
                    ship.hsExtraEquipment.merge(bp.name, 1, Int::plus)
                }
                return@LuaMethod ret.num(0.0)
            }
            val hp = filled[wanted]
            val old = hp.weapon!!
            if (old.isPowered) ship.weapons!!.setWeaponPower(old, false)
            val cost = old.type.cost
            ship.hsReplacedWeapons += old
            hp.weapon = bp.buildInstance(ship)
            ship.cargoUpdated()
            ret.num(cost.toDouble())
        })
        beamWeapon()
        weaponAnimation()
        factory()
        weaponSystem()
        blueprint()
        armamentBoxes()
    }

    /** CommandGui::combatControl / CombatControl::weapControl: only the path to the weapon boxes (WP7 owns the rest). */
    object CombatControlView : LuaTyped { override val luaType get() = A.T_CombatControl }
    object WeaponControlView : LuaTyped { override val luaType get() = A.T_WeaponControl }

    /** The player's weapon box for slot [slot] (WeaponControl::boxes[slot]). */
    /** What scripts wrote to a weapon box: moves from the box's usual place, read when the box draws. */
    class BoxWrites {
        @JvmField var dx = 0
        @JvmField var dy = 0
        @JvmField var barDx = 0
        @JvmField var nameDx = 0
        @JvmField var nameDy = 0
        @JvmField var nameWidth = 55
    }
    @JvmField var boxWritten = false
    /** The writes to the weapon box of [slot], when any box was written. */
    @JvmStatic fun boxWrites(ship: Ship, slot: Int): BoxWrites? =
        if (boxWritten) (LuaViews.cached(ship, BOX_KEY + slot) { ArmamentBoxView(ship, slot) } as ArmamentBoxView).writes else null

    class ArmamentBoxView(@JvmField val ship: Ship, @JvmField val slot: Int) : LuaTyped {
        override val luaType get() = A.T_ArmamentBox
        @JvmField val writes = BoxWrites()
        val weapon: AbstractWeaponInstance? get() = ship.hardpoints.getOrNull(slot)?.weapon
    }

    /**
     * ArmamentBox members as the oracle reads them on the MV start ship (wp4-armament): location (16 + 97 * slot,
     * 16) from ArmamentBoxOrigin, xOffset 8, nameOffset (34, 3), nameWidth 55, hotKey slot + 1, white iconColor,
     * lastIconPos (INT_MAX, INT_MAX) until drawn; a filled slot shows systemUI/weapbox_icon_W_<iconImage>.png on
     * weapbox_inner_weaponbox.png with largeIconOffset (-5, 2), an empty one nothing on weapbox_inner_droneoff.png.
     */
    private fun armamentBoxes() {
        LuaReg.obj(A.A_CommandGui_combatControl, ObjGet { if (LuaAdapters.inGame() != null) CombatControlView else null })
        LuaReg.obj(A.A_CombatControl_weapControl, ObjGet { WeaponControlView })
        LuaReg.obj(A.A_ArmamentControl_boxes, ObjGet { o ->
            val ship = LuaAdapters.inGame()?.player
            if (o !== WeaponControlView || ship == null) throw LuaError("ArmamentControl.boxes: only the player's weapon control")
            ListView(A.T_vector_p_ArmamentBox, A.T_ArmamentBox) {
                List(ship.weaponSlots ?: ship.hardpoints.size) { i -> LuaViews.cached(ship, BOX_KEY + i) { ArmamentBoxView(ship, i) } }
            }
        })
        fun box(o: Any?) = o as? ArmamentBoxView ?: throw LuaError("expected ArmamentBox")
        LuaReg.method(A.M_ArmamentBox_Powered, LuaMethod { ret.bool(box(arg.obj(1)).weapon?.isPowered == true) })
        LuaReg.str(A.A_ArmamentBox_iconName, StrGet { o ->
            val w = box(o).weapon ?: return@StrGet ""
            "systemUI/weapbox_icon_W_" + (w.type.hsLuaFacts?.combatIcon ?: "") + ".png"
        })
        LuaReg.str(A.A_ArmamentBox_iconBackgroundName, StrGet { o ->
            if (box(o).weapon != null) "systemUI/weapbox_inner_weaponbox.png" else "systemUI/weapbox_inner_droneoff.png"
        })
        fun held(a: Int, x0: (ArmamentBoxView) -> Int, y0: Int, write: (BoxWrites, Int, Int) -> Unit) {
            LuaReg.obj(a, ObjGet { o ->
                val b = box(o)
                point(b, a, x0(b), y0).also { p -> p.onWrite = { boxWritten = true; write(b.writes, p.x - x0(b), p.y - y0) } }
            })
            LuaReg.objSet[a] = ObjSet { o, v ->
                val p = LuaReg.objGet[a]!!.get(o) as LuaHeldPoint
                val from = v as PointValue
                p.x = from.x; p.y = from.y; p.written()
            }
        }
        held(A.A_ArmamentBox_location, { 16 + 97 * it.slot }, 16) { w, dx, dy -> w.dx = dx; w.dy = dy }
        LuaReg.obj(A.A_ArmamentBox_lastIconPos, ObjGet { o -> point(box(o), A.A_ArmamentBox_lastIconPos, Int.MAX_VALUE, Int.MAX_VALUE) })
        LuaReg.obj(A.A_ArmamentBox_largeIconOffset, ObjGet { o ->
            val b = box(o); if (b.weapon != null) point(b, A.A_ArmamentBox_largeIconOffset, -5, 2) else point(b, A.A_ArmamentBox_largeIconOffset, 0, 0)
        })
        held(A.A_ArmamentBox_nameOffset, { 34 }, 3) { w, dx, dy -> w.nameDx = dx; w.nameDy = dy }
        LuaReg.num(A.A_ArmamentBox_xOffset, NumGet { (8 + box(it).writes.barDx).toDouble() }, NumSet { o, v -> boxWritten = true; box(o).writes.barDx = v.toInt() - 8 })
        LuaReg.num(A.A_ArmamentBox_nameWidth, NumGet { box(it).writes.nameWidth.toDouble() }, NumSet { o, v -> boxWritten = true; box(o).writes.nameWidth = v.toInt() })
        LuaReg.num(A.A_ArmamentBox_hotKey, NumGet { (box(it).slot + 1).toDouble() })
        LuaReg.obj(A.A_ArmamentBox_iconColor, ObjGet { o -> LuaViews.cached(box(o), A.A_ArmamentBox_iconColor) { GlColorValue(255f, 255f, 255f, 1f) } })
        LuaReg.bool(A.A_ArmamentBox_droneVariation, { box(it); false })
        LuaReg.bool(A.A_ArmamentBox_bIoned, { box(it).ship.weapons?.isIonised == true })
        // Hover and selection live in the engine's own weapon bar (PlayerShipUI), not exposed here.
        // mouseHover: the weapon box under the mouse (ArmamentBox::MouseMove sets it every move); selected: the box
        // whose weapon is being aimed (WeaponControl's targeting selection).
        LuaReg.bool(A.A_ArmamentBox_mouseHover, { o -> val b = box(o); b.ship.sys.shipUI.hsWeaponBoxHovered(b.slot) },
            { _, _ -> HS.unsupported("lua", "attribute", "ArmamentBox.mouseHover write (the next mouse move sets it)") })
        LuaReg.bool(A.A_ArmamentBox_selected, { o -> val b = box(o); b.ship.sys.shipUI.hsWeaponBoxSelected(b.slot) },
            { _, _ -> HS.unsupported("lua", "attribute", "ArmamentBox.selected write (the targeting selection is the UI's)") })
    }

    private const val BOX_KEY = 1 shl 20

    private fun beamWeapon() {
        LuaReg.obj(A.A_BeamWeapon_contactAnimations, ObjGet { o ->
            val b = beam(o)
            ListView(A.T_vector_Animation, A.T_Animation, b.contactAnimations.map { a ->
                LuaAnim.create(a.spec).also { an ->
                    (LuaStructs.member(an, A.A_Animation_tracker) as LuaStruct).values[A.A_AnimationTracker_current_time] = a.timer.toDouble()
                    an.values[A.A_Animation_currentFrame] = a.frame.coerceAtLeast(0).toDouble()
                } as Any?
            }.toMutableList())
        })
        LuaReg.obj(A.A_BeamWeapon_smokeAnims, ObjGet { o ->
            val b = beam(o)
            ListView(A.T_vector_Animation, A.T_Animation, b.smokeAnimations.map { s ->
                val an = s.animation?.let { a -> LuaAnim.create(a.spec) } ?: LuaStructs.create(A.T_Animation).also { an ->
                    // effects/fire_smoke.png: 7 frames of 34 px over 0.5 s.
                    val info = LuaStructs.member(an, A.A_Animation_info) as LuaStruct
                    info.values[A.A_AnimationDescriptor_numFrames] = 7.0
                    info.values[A.A_AnimationDescriptor_frameWidth] = 34.0; info.values[A.A_AnimationDescriptor_frameHeight] = 34.0
                    (LuaStructs.member(an, A.A_Animation_tracker) as LuaStruct).values[A.A_AnimationTracker_time] = .5
                    an.values[A.A_Animation_animName] = "" // native: built from the texture, no animations.xml name
                }
                (LuaStructs.member(an, A.A_Animation_tracker) as LuaStruct).values[A.A_AnimationTracker_current_time] =
                    (s.animation?.timer ?: s.age).toDouble()
                val pos = LuaStructs.member(an, A.A_Animation_position) as LuaStruct
                pos.values[A.A_Pointf_x] = s.x.toDouble(); pos.values[A.A_Pointf_y] = s.y.toDouble()
                an.values[A.A_Animation_fScale] = s.scale.toDouble()
                an as Any?
            }.toMutableList())
        })
        // BeamWeapon::weapAnimation (+0x314): the launcher's WeaponAnimation, null for drone beams.
        LuaReg.obj(A.A_BeamWeapon_weapAnimation, ObjGet { o ->
            when (val link = beam(o).weaponAnimation) {
                is AbstractWeaponInstance -> visualOf(link)
                is ForeignLauncher -> visualOf(link.weapon)
                else -> null
            }
        },
            ObjSet { o, v -> setWeaponAnimation(beam(o), v) })
        LuaReg.method(A.M_BeamWeapon_SetWeaponAnimation, LuaMethod { setWeaponAnimation(beam(arg.obj(1)), arg.obj(2)); 0 })
        // SetMovingTarget 437670: only a target in the beam's moving-target space (movingTargetId) is taken.
        LuaReg.method(A.M_BeamWeapon_SetMovingTarget, LuaMethod {
            val b = beam(arg.obj(1))
            val ship = (arg.obj(2) as? LuaAdaptersBeam.TargetableMember)?.ship
            if (ship != null && HsWeapons.shipId(ship) == b.motion.movingTargetId) b.movingTarget = ship
            0
        })
        LuaReg.method(A.M_BeamWeapon_GetType, LuaMethod { n -> (LuaReg.methods[A.M_Projectile_GetType] ?: throw LuaError("GetType")).call(n) })
        // OnRenderSpecific(spaceId): the beam's part in that space, into the current render pass.
        LuaReg.method(A.M_BeamWeapon_OnRenderSpecific, LuaMethod {
            val b = beam(arg.obj(1))
            val g = HsLua.graphics ?: return@LuaMethod 0
            val ship = LuaAdapters.shipManager(arg.int(2)) ?: return@LuaMethod 0
            b.renderSpecific(g, ship)
            0
        })
        // Save/LoadProjectile take a native FileHelper descriptor; the browser saves beams with the ship's projectiles.
        for (m in intArrayOf(A.M_BeamWeapon_SaveProjectile, A.M_BeamWeapon_LoadProjectile))
            LuaReg.method(m, LuaMethod { HS.unsupported("lua", "method", A.methodName(m) + " (FileHelper)"); 0 })
    }

    private fun setWeaponAnimation(b: BeamProjectile, v: Any?) {
        if (v == null) { b.weaponAnimation = null; return }
        val weapon = (v as? HsWeaponsLua.WeaponVisual)?.w ?: throw LuaError("expected WeaponAnimation")
        if (weapon is BeamBlueprint.BeamInstance) b.weaponAnimation = weapon
        else {
            // A non-beam launcher: the beam reads its power, its fire level has no frame to drive in the engine.
            HS.unsupported("lua", "method", "BeamWeapon.SetWeaponAnimation (non-beam launcher frame)")
            b.weaponAnimation = ForeignLauncher(weapon)
        }
    }

    /** A non-beam launcher set as a beam's weapAnimation: its power gates the beam; it has no beam frames. */
    private class ForeignLauncher(val weapon: AbstractWeaponInstance) : BeamProjectile.WeaponAnimationLink {
        override fun setFireLevel(level: Float) = Unit
        override val weaponPowered: Boolean get() = weapon.isPowered
    }

    private fun weaponAnimation() {
        val owner = "WeaponAnimation"
        LuaReg.obj(A.A_WeaponAnimation_anim, ObjGet { o ->
            val weapon = visual(o); val anim = weapon.animation
            val spec = xyz.znix.xftl.AnimationSpec(anim.sheet, weapon.type.launcher, anim.x, anim.y, anim.length, 1f)
            val an = animation(weapon, A.A_WeaponAnimation_anim, spec)
            // Natively built from the weapon's sheet, not by an animations.xml name.
            if (an.values[A.A_Animation_animName] == weapon.type.launcher) an.values[A.A_Animation_animName] = ""
            // SetFireTime 460470 (factory constructor, HS <fireTime>): tracker.time = fireTime / ((n - charged) / n).
            val fireTime = when (weapon) {
                is AbstractProjectileWeaponInstance -> weapon.fireTime
                is BombBlueprint.BombInstance -> weapon.bombFireTime
                else -> null
            }
            val n = anim.length
            // (an animation a script wrote keeps its own timing)
            if (fireTime != null && n > anim.chargedFrame && an.values[A.A_Animation_animName] == "") {
                val tracker = LuaStructs.member(an, A.A_Animation_tracker) as LuaStruct
                val time = fireTime / ((n - anim.chargedFrame).toFloat() / n)
                tracker.values[A.A_AnimationTracker_time] = time.toDouble()
                // WeaponAnimation::StartFire / Update (0x4609e0): firing runs the tracker from the charged frame
                // (SetCurrentFrame 0x5bb520: current_time = frame / n * time) at real time.
                if (weapon is AbstractProjectileWeaponInstance) {
                    tracker.values[A.A_AnimationTracker_running] = weapon.isFiring
                    if (weapon.isFiring) tracker.values[A.A_AnimationTracker_current_time] =
                        (anim.chargedFrame.toFloat() / n * time + weapon.hsFireSequenceTime).toDouble()
                }
            }
            // WeaponAnimation::SetChargeLevel keeps the frame on the charge, whichever animation is in the member.
            if (!weapon.isFiring) an.values[A.A_Animation_currentFrame] = (if (weapon.chargeProgress < 1f)
                (anim.chargedFrame * weapon.chargeProgress).toInt() else anim.chargedFrame).toDouble()
            an
        })
        LuaReg.bool(A.A_WeaponAnimation_bFireShot, { (visual(it) as? AbstractProjectileWeaponInstance)?.hsFireShotPending == true }, { _, _ -> HS.unsupported("lua", "attribute", "$owner.bFireShot write") })
        LuaReg.bool(A.A_WeaponAnimation_bFiring, { visual(it).isFiring })
        LuaReg.num(A.A_WeaponAnimation_fChargeLevel, NumGet { visual(it).chargeProgress.toDouble() })
        LuaReg.num(A.A_WeaponAnimation_fActualChargeLevel, NumGet { visual(it).chargeProgress.toDouble() })
        LuaReg.num(A.A_WeaponAnimation_iChargedFrame, NumGet { visual(it).animation.chargedFrame.toDouble() })
        LuaReg.num(A.A_WeaponAnimation_iFireFrame, NumGet { visual(it).animation.fireFrame.toDouble() })
        LuaReg.bool(A.A_WeaponAnimation_bMirrored, { hardpoint(visual(it))?.mirror == true })
        LuaReg.bool(A.A_WeaponAnimation_bRotation, { hardpoint(visual(it))?.rotate == true })
        LuaReg.bool(A.A_WeaponAnimation_bPowered, { visual(it).isPowered })
        LuaReg.bool(A.A_WeaponAnimation_playerShip, { visual(it).ship.isPlayerShip })
        LuaReg.bool(A.A_WeaponAnimation_bShowCharge, { visual(it).animation.chargeImage != null })
        LuaReg.num(A.A_WeaponAnimation_iChargeOffset, NumGet { visual(it).animation.chargeOffset.toDouble() })
        LuaReg.num(A.A_WeaponAnimation_iHackLevel, NumGet { hackLevel(visual(it)).toDouble() })
        LuaReg.num(A.A_WeaponAnimation_slideDirection, NumGet { slideDirection(visual(it)).toDouble() })
        // fDelayChargeTime (+0x250): read by SetChargedLevel 0x4608b0 for the launcher's charge frame.
        LuaReg.num(A.A_WeaponAnimation_fDelayChargeTime, NumGet { visual(it).hsDelayChargeTime.toDouble() },
            NumSet { o, v -> visual(o).hsDelayChargeTime = v.toFloat() })
        // currentOffset (+0x334): written by WeaponAnimation::Update 0x4609e0, read by nothing (1.6.14 scan).
        storedNum(A.A_WeaponAnimation_currentOffset, owner)
        LuaReg.obj(A.A_WeaponAnimation_fireLocation, ObjGet { o ->
            val weapon = visual(o)
            // The shot starts from it (oracle hw-e-fl).
            point(weapon, A.A_WeaponAnimation_fireLocation, weapon.animation.firePoint.x, weapon.animation.firePoint.y).also { p ->
                p.onWrite = { weapon.hsFireShiftX = p.x - weapon.animation.firePoint.x; weapon.hsFireShiftY = p.y - weapon.animation.firePoint.y }
            }
        })
        LuaReg.obj(A.A_WeaponAnimation_mountPoint, ObjGet { o ->
            val weapon = visual(o); point(weapon, A.A_WeaponAnimation_mountPoint, weapon.animation.mountPoint.x, weapon.animation.mountPoint.y).also { p ->
                        p.onWrite = { weapon.hsMountShiftX = p.x - p.engineX; weapon.hsMountShiftY = p.y - p.engineY }
                    }
        })
        // renderPoint = the mount position (SetMount); fireMountVector = GetFireLocation - renderPoint.
        LuaReg.obj(A.A_WeaponAnimation_renderPoint, ObjGet { o ->
            val weapon = visual(o); val p = nativeMountPosition(weapon); point(weapon, A.A_WeaponAnimation_renderPoint, p.x, p.y)
        })
        LuaReg.obj(A.A_WeaponAnimation_fireMountVector, ObjGet { o ->
            val weapon = visual(o)
            val hp = hardpoint(weapon)
            val sys = weapon.ship.weapons
            val v = if (hp == null || sys == null) ConstPoint.ZERO
                else sys.getProjectileSpawnPos(weapon, weapon.animation.firePoint).let { ConstPoint(it.x - hp.position.x, it.y - hp.position.y) }
            point(weapon, A.A_WeaponAnimation_fireMountVector, v.x, v.y)
        })
        LuaReg.obj(A.A_WeaponAnimation_mount, ObjGet { mount(visual(it), A.A_WeaponAnimation_mount) })
        // slideTracker: the engine slides over 0.5 s (Weapons.update, 2 per second).
        LuaReg.obj(A.A_WeaponAnimation_slideTracker, ObjGet { o ->
            val weapon = visual(o)
            val t = LuaViews.cached(weapon, A.A_WeaponAnimation_slideTracker) { LuaStructs.create(A.T_AnimationTracker) }
            t.values[A.A_AnimationTracker_time] = .5
            t.values[A.A_AnimationTracker_current_time] = (weapon.slide * .5f).toDouble()
            t.values[A.A_AnimationTracker_running] = weapon.slide > 0f && weapon.slide < 1f
            t.values[A.A_AnimationTracker_reverse] = !weapon.isPowered
            t.values[A.A_AnimationTracker_done] = weapon.slide <= 0f || weapon.slide >= 1f
            t
        })
        LuaReg.obj(A.A_WeaponAnimation_explosionAnim, ObjGet { o -> animation(visual(o), A.A_WeaponAnimation_explosionAnim, null) })
        LuaReg.obj(A.A_WeaponAnimation_hackSparks, ObjGet { o -> animation(visual(o), A.A_WeaponAnimation_hackSparks, null) })
        // GetSlide 460600: (1 - slide progress) times half the frame's extent along slideDirection (frame width and
        // height swap for a rotated mount; fScale 1), negative for up/left.
        LuaReg.method(A.M_WeaponAnimation_GetSlide, LuaMethod {
            val weapon = visual(arg.obj(1))
            val sheet = weapon.animation.sheet
            val rotated = hardpoint(weapon)?.rotate == true
            val across = if (rotated) sheet.frameHeight else sheet.frameWidth
            val along = if (rotated) sheet.frameWidth else sheet.frameHeight
            val f = 1f - weapon.slide
            val p = when (slideDirection(weapon)) {
                0 -> PointfValue(0f, -(along / 2) * f); 2 -> PointfValue(0f, (along / 2) * f)
                1 -> PointfValue(-(across / 2) * f, 0f); 3 -> PointfValue((across / 2) * f, 0f)
                else -> PointfValue(0f, 0f)
            }
            ret.obj(p, A.T_Pointf)
        })
        // SetFireTime(t) 460470: the frames from the charged one to the end take t (the fire time per shot).
        LuaReg.method(A.M_WeaponAnimation_SetFireTime, LuaMethod {
            val t = arg.float(2)
            when (val weapon = visual(arg.obj(1))) {
                is xyz.znix.xftl.weapons.AbstractProjectileWeaponInstance -> weapon.fireTime = t
                is xyz.znix.xftl.weapons.BombBlueprint.BombInstance -> weapon.bombFireTime = t
                else -> HS.unsupported("lua", "method", "WeaponAnimation.SetFireTime on a beam launcher (BeamWeapon drives its frames)")
            }
            0
        })
    }

    private fun factory() {
        val owner = "ProjectileFactory"
        LuaReg.num(A.A_ProjectileFactory_baseCooldown, NumGet { w(it).type.chargeTime.toDouble() })
        LuaReg.num(A.A_ProjectileFactory_cooldownModifier, NumGet { w(it).hsCooldownMod.toDouble() }, NumSet { o, v -> w(o).hsCooldownMod = v.toFloat() })
        LuaReg.method(A.M_ProjectileFactory_SetCooldownModifier, LuaMethod { w(arg.obj(1)).hsCooldownMod = arg.float(2); 0 })
        LuaReg.obj(A.A_ProjectileFactory_mount, ObjGet { mount(w(it)) })
        LuaReg.obj(A.A_ProjectileFactory_localPosition, ObjGet { o ->
            LuaViews.cached(o!!, A.A_ProjectileFactory_localPosition) { PointValue(-20, -12) }
        })
        LuaReg.obj(A.A_ProjectileFactory_flight_animation, ObjGet { o ->
            val weapon = w(o)
            val spec = weapon.type.projectile?.let { weapon.ship.sys.animations.getOrNull(it) }
            LuaViews.cached(weapon, A.A_ProjectileFactory_flight_animation) {
                if (spec == null) LuaStructs.create(A.T_Animation)
                else LuaAnim.create(spec).also { (LuaStructs.member(it, A.A_Animation_tracker) as LuaStruct).values[A.A_AnimationTracker_running] = true }
            }
        })
        LuaReg.bool(A.A_ProjectileFactory_fireWhenReady, { o -> val weapon = w(o); weapon.ship.weapons?.selectedTargets?.getTarget(slot(weapon)) != null })
        LuaReg.obj(A.A_ProjectileFactory_targets, ObjGet { o -> ListView(A.T_vector_Pointf, A.T_Pointf, aimPoints(w(o)).toMutableList()) })
        // lastTargets: ProjectileFactory::Fire reverses the aim points and copies them into targets and lastTargets; both
        // are empty again once the weapon is untargeted (its shot, ClearAiming, power loss: oracle hs-gaps aim rows).
        LuaReg.obj(A.A_ProjectileFactory_lastTargets, ObjGet { o ->
            val aim = w(o).hsLastAim as? SelectedTarget
            ListView(A.T_vector_Pointf, A.T_Pointf, (if (aim == null) emptyList() else aimPoints(aim).asReversed()).toMutableList())
        })
        LuaReg.num(A.A_ProjectileFactory_targetId, NumGet { HsWeapons.shipId(targetShip(w(it))).toDouble() })
        LuaReg.obj(A.A_ProjectileFactory_currentShipTarget, ObjGet { o -> currentShip(w(o))?.let(::targetable) })
        // ProjectileFactory::name: the blueprint's (translated) title, e.g. "Burst Laser MK I" in MV.
        LuaReg.str(A.A_ProjectileFactory_name, StrGet { val weapon = w(it); weapon.type.translateTitle(weapon.ship.sys) })
        // A write stays until the weapon fires.
        LuaReg.num(A.A_ProjectileFactory_currentFiringAngle, NumGet { w(it).hsFiringAngle ?: fireAngle(w(it)) }, NumSet { o, v -> w(o).hsFiringAngle = v })
        LuaReg.num(A.A_ProjectileFactory_radius, NumGet { ((w(it).type as? FlakBlueprint)?.radius ?: 0).toDouble() })
        // GetBoostLevel: the chain count (boostLevel, +0x624).
        LuaReg.num(A.A_ProjectileFactory_boostLevel, NumGet { w(it).chainCount.toDouble() }, NumSet { o, v -> w(o).chainCount = v.toInt() })
        LuaReg.num(A.A_ProjectileFactory_lastProjectileId, NumGet { ((w(it) as? BeamBlueprint.BeamInstance)?.lastProjectileId ?: -1).toDouble() })
        LuaReg.num(A.A_ProjectileFactory_iHackLevel, NumGet { hackLevel(w(it)).toDouble() })
        LuaReg.num(A.A_ProjectileFactory_iBonusPower, NumGet { o ->
            val weapon = w(o); val s = slot(weapon)
            (if (s < 0) 0 else weapon.ship.weapons?.getWeaponForcedPower(s) ?: 0).toDouble()
        })
        LuaReg.bool(A.A_ProjectileFactory_isArtillery, { o -> w(o).ship.systems.any { it is Artillery && it.browserWeapon === o } })
        // goalChargeLevel (+0x634): SelectChargeGoal writes it, IsChargedGoal (0x40cc50, from CombatAI 0x62c000) reads it.
        // An enemy weapon's goal lives in its ShipAI, so a script's write changes when the AI fires.
        LuaReg.num(A.A_ProjectileFactory_goalChargeLevel, NumGet { o -> chargeGoal(w(o)).toDouble() },
            NumSet { o, v ->
                storedSet(o!!, A.A_ProjectileFactory_goalChargeLevel, v, owner)
                val weapon = w(o); chargeAI(weapon)?.setChargeGoal(weapon, v.toInt())
            })
        // iAmmo: 3 on every launcher in the oracle (constructor value); nothing in the engine spends it.
        // iAmmo: the ship's missile count (oracle wp4-queue: 13 with 13 missiles, 3 at the MV start beacon).
        // A write holds until the next ship loop (the copy of the missile count is not otherwise modelled).
        LuaReg.num(A.A_ProjectileFactory_iAmmo, NumGet { o -> w(o).let { it.hsLuaAmmo ?: it.ship.missilesCount }.toDouble() },
            NumSet { o, v -> w(o).hsLuaAmmo = v.toInt() })
        LuaReg.num(A.A_ProjectileFactory_iSpendMissile, NumGet { o -> w(o).hsSpendMissile.toDouble() },
            NumSet { o, v -> w(o).hsSpendMissile = v.toInt() })
        // currentEntryAngle: degrees, random32() % 360 at Fire; the volley's projectiles take it at construction.
        LuaReg.num(A.A_ProjectileFactory_currentEntryAngle, NumGet { o ->
            when (o) {
                is AbstractProjectileWeaponInstance -> Math.round(Math.toDegrees(o.entryAngle.toDouble())).toDouble()
                is BombBlueprint.BombInstance -> o.hsEntryAngle.toDouble()
                else -> 0.0
            }
        }, NumSet { o, v ->
            when (o) {
                is AbstractProjectileWeaponInstance -> o.entryAngle = Math.toRadians(v).toFloat()
                is BombBlueprint.BombInstance -> o.hsEntryAngle = v.toFloat()
                else -> HS.unsupported("lua", "attribute", "ProjectileFactory.currentEntryAngle on a beam")
            }
        })
        // bFiredOnce (0x610): set by every GetProjectile pop (weapons/HsProjectileQueue); beams keep a stored flag.
        LuaReg.bool(A.A_ProjectileFactory_bFiredOnce, { o -> (o as? xyz.znix.xftl.weapons.HsProjectileQueue)?.hsFiredOnce ?: (storedGet(o!!, A.A_ProjectileFactory_bFiredOnce) != 0.0) },
            { o, v -> val q = o as? xyz.znix.xftl.weapons.HsProjectileQueue; if (q != null) q.hsFiredOnce = v else storedSet(o!!, A.A_ProjectileFactory_bFiredOnce, if (v) 1.0 else 0.0, owner) })

        // NumTargetsRequired 40d5f0: max(1, chargeLevel) for a charge weapon, else numShots.
        LuaReg.method(A.M_ProjectileFactory_NumTargetsRequired, LuaMethod {
            val weapon = w(arg.obj(1))
            ret.num(if (weapon.maxTotalCharges > 1) maxOf(1, weapon.totalReadyCharges) else weapon.type.shots)
        })
        // IsChargedGoal 0x40cc50: goalChargeLevel <= chargeLevel (every blueprint has chargeLevels >= 1: the
        // constructor 0x40bff0 draws the goal as rand() % chargeLevels + 1).
        LuaReg.method(A.M_ProjectileFactory_IsChargedGoal, LuaMethod {
            val weapon = w(arg.obj(1))
            ret.bool(chargeGoal(weapon) <= weapon.totalReadyCharges)
        })
        // FireNextShot 40cfa0: charged, powered, aimed and not already firing.
        LuaReg.method(A.M_ProjectileFactory_FireNextShot, LuaMethod {
            val weapon = w(arg.obj(1))
            ret.bool(weapon.totalReadyCharges > 0 && weapon.isPowered && !weapon.isFiring &&
                weapon.ship.weapons?.selectedTargets?.getTarget(slot(weapon)) != null)
        })
        // Fire(points, target): aim the weapon at the given points of ship `target` (it fires once charged).
        LuaReg.method(A.M_ProjectileFactory_Fire, LuaMethod {
            val weapon = w(arg.obj(1))
            val points = (arg.obj(2) as? ListView)?.let { l -> List(l.size()) { l.get(it) } } ?: emptyList()
            val ship = LuaAdapters.shipManager(arg.int(3))
            fire(weapon, points, ship)
            0
        })
        // SetCurrentShip(target) 0x40ccc0: a different ship clears shotsFiredAtTarget (+0x61c) and boostLevel (+0x624);
        // nil clears boostLevel and the target; the same ship changes nothing. currentShipTarget (+0x140) is otherwise
        // set by the weapon system's targeting, so the script's ship holds until the weapon's selected target changes.
        LuaReg.method(A.M_ProjectileFactory_SetCurrentShip, LuaMethod {
            val weapon = w(arg.obj(1))
            val ship = (arg.obj(2) as? LuaAdaptersBeam.TargetableMember)?.ship
            if (ship == null) weapon.chainCount = 0
            else if (ship !== currentShip(weapon)) { weapon.hsShotsFired = 0; weapon.chainCount = 0 }
            luaCurrentShip[weapon] = targetShip(weapon) to ship
            0
        })
        // SetHacked(level): iHackLevel (factory and launcher animation) until WeaponSystem::OnLoop sets the system's
        // level again on the next frame.
        LuaReg.method(A.M_ProjectileFactory_SetHacked, LuaMethod { w(arg.obj(1)).hsLuaHackLevel = arg.int(2); 0 })
    }

    private fun fire(weapon: AbstractWeaponInstance, points: List<Any?>, ship: Ship?) {
        val s = slot(weapon)
        val targets = weapon.ship.weapons?.selectedTargets
        if (ship == null || s < 0 || targets == null || points.isEmpty()) return
        fun xy(p: Any?): Pair<Float, Float>? = when (p) {
            is PointfValue -> p.x to p.y
            is LuaPointfMember -> p.x to p.y
            else -> null
        }
        val first = xy(points[0]) ?: return
        if (weapon is BeamBlueprint.BeamInstance) {
            val second = points.getOrNull(1)?.let(::xy) ?: first
            val aim = SelectedTarget.BeamAim(weapon, s, ship, ConstPoint(first.first.toInt(), first.second.toInt()))
            aim.angle = atan2(second.second - first.second, second.first - first.first)
            aim.length = minOf(hypot(second.first - first.first, second.second - first.second).toInt(), aim.length).coerceAtLeast(1)
            aim.updateHitRooms()
            targets.targetBeam(s, aim)
            return
        }
        val room = ship.rooms.firstOrNull { it.containsShipSpace(ConstPoint(first.first.toInt(), first.second.toInt())) } ?: return
        if (weapon is IRoomTargetingWeapon) targets.targetRoom(s, room)
    }

    private fun weaponSystem() {
        val owner = "WeaponSystem"
        fun sys(o: Any?) = o as? Weapons ?: throw LuaError("expected WeaponSystem")
        fun shipOf(sys: Weapons): Ship? {
            val s = LuaAdapters.inGame() ?: return null
            return when { s.player.weapons === sys -> s.player; s.enemy?.weapons === sys -> s.enemy; else -> null }
        }
        LuaReg.obj(A.A_WeaponSystem_target, ObjGet { o -> LuaViews.cached(o!!, A.A_WeaponSystem_target) { PointfValue(-1f, -1f) } })
        LuaReg.obj(A.A_WeaponSystem_weaponsTrashList, ObjGet { o ->
            LuaViews.cached(o!!, A.A_WeaponSystem_weaponsTrashList) { ListView(A.T_vector_ProjectileFactory, A.T_ProjectileFactory, ArrayList<Any?>()) }
        })
        LuaReg.num(A.A_WeaponSystem_slot_count, NumGet { o -> val ship = shipOf(sys(o)); (ship?.weaponSlots ?: ship?.hardpoints?.size ?: 0).toDouble() })
        LuaReg.obj(A.A_WeaponSystem_userPowered, ObjGet { o ->
            val ship = shipOf(sys(o))
            val slots = ship?.weaponSlots ?: ship?.hardpoints?.size ?: 0
            ListView(A.T_vector_bool, 0, MutableList<Any?>(slots) { i -> ship?.hardpoints?.getOrNull(i)?.weapon?.hsUserPowered == true })
        })
        LuaReg.obj(A.A_WeaponSystem_repowerList, ObjGet { o ->
            LuaViews.cached(o!!, A.A_WeaponSystem_repowerList) { ListView(A.T_vector_bool, 0, ArrayList<Any?>()) }
        })
        // missile_count (+0x1e8): the ship's missiles (ModifyMissileCount 636200 keeps it >= 0).
        LuaReg.num(A.A_WeaponSystem_missile_count, NumGet { o -> (shipOf(sys(o))?.missilesCount ?: 0).toDouble() },
            NumSet { o, v -> shipOf(sys(o))?.let { it.missilesCount = v.toInt() } })
        // ShipManager::GetSystemRoom(systemId): the room of that system, -1 without it.
        if (LuaReg.methods[A.M_ShipManager_GetSystemRoom] == null) LuaReg.method(A.M_ShipManager_GetSystemRoom, LuaMethod {
            val ship = arg.obj(1) as? Ship ?: throw LuaError("expected ShipManager")
            ret.num((LuaShipApi.system(ship, arg.int(2))?.room?.id ?: -1).toDouble())
        })
        LuaReg.method(A.M_ShipManager_ModifyMissileCount, LuaMethod {
            val ship = arg.obj(1) as? Ship ?: throw LuaError("expected ShipManager")
            if (ship.weapons != null) ship.missilesCount = maxOf(0, ship.missilesCount + arg.int(2)); 0
        })
        // shot_timer / shot_count / missile_start: only the constructor (0x635ca0) touches them natively (displacement
        // scan of the 1.6.14 executable), so they keep a script's value.
        for (a in intArrayOf(A.A_WeaponSystem_shot_timer, A.A_WeaponSystem_shot_count,
            A.A_WeaponSystem_missile_start)) storedNum(a, owner)
        // iStartingBatteryPower (+0x20c): the pool PowerWeapon (0x636230) spends before the reactor.
        LuaReg.num(A.A_WeaponSystem_iStartingBatteryPower, NumGet { o -> sys(o).powerManager.hsBatteryPool.toDouble() },
            NumSet { o, v -> sys(o).powerManager.hsBatteryPool = v.toInt() })
        LuaReg.method(A.M_WeaponSystem_AddDamage, LuaMethod { n -> (LuaReg.methods[A.M_ShipSystem_AddDamage] ?: throw LuaError("AddDamage")).call(n) })
        LuaReg.method(A.M_WeaponSystem_CheckMaxPower, LuaMethod { n -> (LuaReg.methods[A.M_ShipSystem_CheckMaxPower] ?: throw LuaError("CheckMaxPower")).call(n) })
        // RemoveWeapon(slot): the weapon leaves its slot (unpowered) and is returned to the caller; the oracle shows
        // weaponsTrashList unchanged (wp4-armament st-remove).
        LuaReg.method(A.M_WeaponSystem_RemoveWeapon, LuaMethod {
            val s = sys(arg.obj(1)); val ship = shipOf(s) ?: return@LuaMethod ret.nil()
            // The slot indexes WeaponSystem::weapons (the filled hardpoints in order), not the hardpoint list.
            val hp = ship.hardpoints.filter { it.weapon != null }.getOrNull(arg.int(2)) ?: return@LuaMethod ret.nil()
            val weapon = hp.weapon ?: return@LuaMethod ret.nil()
            if (weapon.isPowered) s.setWeaponPower(weapon, false)
            hp.weapon = null
            ship.cargoUpdated()
            ret.obj(weapon, A.T_ProjectileFactory)
        })
        // ForceDecreasePower(n) 635980: unpower weapons from the last slot back until n power is freed.
        LuaReg.method(A.M_WeaponSystem_ForceDecreasePower, LuaMethod {
            val s = sys(arg.obj(1)); val ship = shipOf(s) ?: return@LuaMethod ret.bool(false)
            var left = arg.int(2)
            for (hp in ship.hardpoints.reversed()) {
                val weapon = hp.weapon ?: continue
                if (!weapon.isPowered) continue
                left -= weapon.type.power
                s.setWeaponPower(weapon, false)
                if (left < 1) return@LuaMethod ret.bool(true)
            }
            ret.bool(false)
        })
        // ForceIncreasePower(power) (HS rewrite, CustomWeapons.cpp): power the first unpowered weapon that
        // PowerWeapon(weapon, false, false) accepts (an enemy weapon past its shot limit with nothing queued refuses).
        LuaReg.method(A.M_WeaponSystem_ForceIncreasePower, LuaMethod {
            val s = sys(arg.obj(1)); val ship = shipOf(s) ?: return@LuaMethod ret.bool(false)
            for (hp in ship.hardpoints) {
                val weapon = hp.weapon ?: continue
                if (weapon.isPowered) continue
                if (!ship.isPlayerShip && HsWeapons.hitShotLimit(weapon) &&
                    (weapon as? xyz.znix.xftl.weapons.HsProjectileQueue)?.hsQueue?.isEmpty() != false) continue
                if (s.setWeaponPower(weapon, true)) return@LuaMethod ret.bool(true)
            }
            ret.bool(false)
        })
        // SetBonusPower(amount, permanentPower): the bonus power until the next frame's crew count replaces it (HS
        // ShipManager::OnLoop), applied at once (WeaponSystem 636630 clamps it to the system's maximum). The Lua
        // permanentPower argument is not read: script bonus bars are never permanent here (MainSystem.hsLuaBonusPower).
        fun bonusPower(o: Any?, amount: Int) {
            val s = o as? xyz.znix.xftl.systems.MainSystem ?: throw LuaError("expected a ShipSystem")
            val ship = LuaAdapters.inGame()?.let { g -> listOfNotNull(g.player, g.enemy).firstOrNull { it.systems.contains(s) } } ?: return
            s.hsLuaBonusPower = maxOf(0, if (s is Weapons) amount.coerceAtMost(s.energyLevels) else amount)
            ship.updateAvailablePower()
        }
        // ForceBatteryPower(power) (WeaponSystem 635630 also sets iStartingBatteryPower): the battery field only.
        fun batteryPower(o: Any?, power: Int) {
            val s = o as? xyz.znix.xftl.systems.MainSystem ?: throw LuaError("expected a ShipSystem")
            // WeaponSystem 0x635630 / DroneSystem 0x637620 also set the pool their PowerWeapon / PowerDrone spends first.
            if (s is Weapons) s.powerManager.hsBatteryPool = power
            if (s is xyz.znix.xftl.systems.Drones) s.powerManager.hsBatteryPool = power
            s.hsLuaBatteryPower = power
        }
        for (m in intArrayOf(A.M_WeaponSystem_SetBonusPower, A.M_ShipSystem_SetBonusPower, A.M_DroneSystem_SetBonusPower))
            LuaReg.method(m, LuaMethod { bonusPower(arg.obj(1), arg.int(2)); 0 })
        for (m in intArrayOf(A.M_WeaponSystem_ForceBatteryPower, A.M_ShipSystem_ForceBatteryPower, A.M_DroneSystem_ForceBatteryPower))
            LuaReg.method(m, LuaMethod { batteryPower(arg.obj(1), arg.int(2)); 0 })
        // RemoveBatteryPower: the battery field back to 0; the weapons run WeaponSystem's own (vtable 0x636660,
        // WeaponPowerManager.hsRemoveBatteryPower): at least one bar off the last powered weapon, then power again.
        for (m in intArrayOf(A.M_WeaponSystem_RemoveBatteryPower, A.M_ShipSystem_RemoveBatteryPower, A.M_DroneSystem_RemoveBatteryPower))
            LuaReg.method(m, LuaMethod {
                val s = arg.obj(1) as? xyz.znix.xftl.systems.MainSystem ?: throw LuaError("expected a ShipSystem")
                s.hsLuaBatteryPower = when (s) {
                    is Weapons -> s.powerManager.hsRemoveBatteryPower(s.hsLuaBatteryPower ?: 0)
                    // DroneSystem 0x639030: ForceDecreasePower(1) (0x637b70, DePowerDrone from the last drone), then
                    // PowerDrone (0x638b80) for every drone that was on: the same steps as the weapons.
                    is xyz.znix.xftl.systems.Drones -> s.powerManager.hsRemoveBatteryPower(s.hsLuaBatteryPower ?: 0)
                    else -> 0
                }
                0
            })
    }

    private fun blueprint() {
        fun f(o: Any?): Facts = (o as? AbstractWeaponBlueprint)?.hsLuaFacts ?: throw LuaError("expected WeaponBlueprint")
        LuaReg.str(A.A_WeaponBlueprint_weaponArt, StrGet { f(it).weaponArt })
        LuaReg.str(A.A_WeaponBlueprint_combatIcon, StrGet { f(it).combatIcon })
        LuaReg.str(A.A_WeaponBlueprint_explosion, StrGet { f(it).explosion })
        LuaReg.num(A.A_WeaponBlueprint_spin, NumGet { f(it).spin })
        LuaReg.num(A.A_WeaponBlueprint_drone_targetable, NumGet { f(it).droneTargetable })
        LuaReg.num(A.A_WeaponBlueprint_miniCount, NumGet { f(it).miniCount.toDouble() })
        LuaReg.obj(A.A_WeaponBlueprint_flavorType, ObjGet { o ->
            val b = o as AbstractWeaponBlueprint
            LuaViews.cached(b, A.A_WeaponBlueprint_flavorType) {
                // The oracle reads the translated text as a literal TextString ("" without a flavorType).
                LuaTextRef({
                    val t = b.flavourType
                    xyz.znix.xftl.GameText.literal(if (t == null) "" else if (t.hsIsLiteral) t.hsData else LuaAdaptersMore.translate(t.hsData))
                }, { g -> b.flavourType = g })
            }
        })
        // BoostPower: type 0 none, 1 cooldown, 2 damage (HS CooldownNumbers.cpp boosts damage for 2; oracle: MV's
        // cooldown-chaining LASER_CHAINGUN reads 1); amount; count.
        LuaReg.obj(A.A_WeaponBlueprint_boostPower, ObjGet { o ->
            val b = o as AbstractWeaponBlueprint
            LuaViews.cached(b, A.A_WeaponBlueprint_boostPower) {
                LuaStructs.create(A.T_BoostPower).also { s ->
                    val boost = b.boost
                    s.values[A.A_BoostPower_type] = when (boost?.type) {
                        AbstractWeaponBlueprint.BoostType.COOLDOWN -> 1.0; AbstractWeaponBlueprint.BoostType.DAMAGE -> 2.0; else -> 0.0
                    }
                    s.values[A.A_BoostPower_amount] = (boost?.perShot ?: b.boostAmount).toDouble()
                    s.values[A.A_BoostPower_count] = (boost?.maxCount ?: b.boostCount).toDouble()
                    // ProjectileFactory reads blueprint->boostPower on every shot.
                    s.onWrite = {
                        val amount = (s.values[A.A_BoostPower_amount] as? Double ?: 0.0).toFloat()
                        val count = (s.values[A.A_BoostPower_count] as? Double ?: 0.0).toInt()
                        val kind = when ((s.values[A.A_BoostPower_type] as? Double ?: 0.0).toInt()) {
                            1 -> AbstractWeaponBlueprint.BoostType.COOLDOWN; 2 -> AbstractWeaponBlueprint.BoostType.DAMAGE; else -> null
                        }
                        b.boostAmount = amount; b.boostCount = count
                        b.boost = kind?.let { AbstractWeaponBlueprint.ChainBoost(it, amount, count) }
                    }
                }
            }
        })
        LuaReg.obj(A.A_WeaponBlueprint_effects, ObjGet { o ->
            val b = o as AbstractWeaponBlueprint
            LuaViews.cached(b, A.A_WeaponBlueprint_effects) {
                LuaStructs.create(A.T_EffectsBlueprint).also { s ->
                    val facts = b.hsLuaFacts ?: throw LuaError("WeaponBlueprint.effects without Hyperspace")
                    fun sounds(a: Int, n: String, set: (AbstractWeaponBlueprint.SoundList) -> Unit) {
                        s.values[a] = ListView(A.T_vector_string, 0, LuaTrackedList(facts.sounds[n].orEmpty()) { l ->
                            HsLua.game?.gameContent?.let { set(AbstractWeaponBlueprint.SoundList(l.map { it.toString() }, it)) }
                        })
                    }
                    sounds(A.A_EffectsBlueprint_launchSounds, "launchSounds") { b.launchSounds = it }
                    sounds(A.A_EffectsBlueprint_hitShipSounds, "hitShipSounds") { b.hitShipSounds = it }
                    sounds(A.A_EffectsBlueprint_hitShieldSounds, "hitShieldSounds") { b.hitShieldSounds = it }
                    sounds(A.A_EffectsBlueprint_missSounds, "missSounds") { b.missSounds = it }
                    s.values[A.A_EffectsBlueprint_image] = facts.image
                    s.onWrite = { b.projectile = s.values[A.A_EffectsBlueprint_image] as? String }
                }
            }
        })
        // WeaponBlueprint::GetType 77b920: the blueprint kind, 0 for every weapon (not the weapon type).
        LuaReg.method(A.M_WeaponBlueprint_GetType, LuaMethod { ret.num(0) })
    }
}
