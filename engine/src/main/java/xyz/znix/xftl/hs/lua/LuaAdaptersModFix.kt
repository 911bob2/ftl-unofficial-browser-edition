// Ported from FTL-Hyperspace (GPL-3.0-or-later) commit 1b05a07 (lua/modules/hyperspace.i members, src/ files named
// below), not from the 1.23.2 tag.
package xyz.znix.xftl.hs.lua

import xyz.znix.xftl.Ship
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint
import xyz.znix.xftl.weapons.AbstractWeaponInstance
import xyz.znix.xftl.hs.lua.LuaApi as A
import xyz.znix.xftl.hs.lua.LuaArgs as arg

/**
 * Members other Hyperspace mods use that the earlier packages bound too narrowly (the round-15 mod runtime test):
 * fields hyperspace.i leaves writable, ShipObject methods on every class that derives from ShipObject, and a ship's
 * Targetable. Installed last, so each registration here wraps the one before it.
 */
object LuaAdaptersModFix {
    @JvmStatic
    fun install() {
        writableFields()
        shipObject()
        shipTargetable()
    }

    private fun w(o: Any?) = o as? AbstractWeaponInstance ?: throw LuaError("expected ProjectileFactory")

    // ---------------------------------------------------------------- writable fields
    private fun writableFields() {
        // ProjectileFactory::radius: the weapon's own copy of the blueprint radius (any weapon type), the disc drawn
        // around its placed crosshairs; scripts change it (MV Fusion's radius reduction).
        LuaReg.num(A.A_ProjectileFactory_radius, NumGet { w(it).aimRadius.toDouble() }, NumSet { o, v -> w(o).aimRadius = v.toInt() })
        // WeaponBlueprint::radius of every weapon type, not only bursts (writable too)
        val radiusBefore = LuaReg.numGet[A.A_WeaponBlueprint_radius]
        val radiusSetBefore = LuaReg.numSet[A.A_WeaponBlueprint_radius]
        LuaReg.num(A.A_WeaponBlueprint_radius, NumGet { o ->
            if (o is AbstractWeaponBlueprint) o.blueprintRadius.toDouble()
            else (radiusBefore ?: throw LuaError("Hyperspace Lua API not available in the browser engine: WeaponBlueprint.radius")).get(o)
        }, NumSet { o, v ->
            if (o is AbstractWeaponBlueprint) o.blueprintRadius = v.toInt()
            else (radiusSetBefore ?: throw LuaError("Hyperspace Lua API not available in the browser engine: WeaponBlueprint.radius")).set(o, v)
        })
        // WeaponBlueprint::cooldown: ProjectileFactory::Update takes the charge goal from the blueprint every frame, so a
        // write changes every weapon of that blueprint at once (MV Hektar's modular weapons).
        val cooldownSetBefore = LuaReg.numSet[A.A_WeaponBlueprint_cooldown]
        LuaReg.numSet[A.A_WeaponBlueprint_cooldown] = NumSet { o, v ->
            if (o is AbstractWeaponBlueprint) o.chargeTime = v.toFloat()
            else (cooldownSetBefore ?: throw LuaError("Hyperspace Lua API not available in the browser engine: WeaponBlueprint.cooldown")).set(o, v)
        }
    }

    // ---------------------------------------------------------------- ShipObject
    /**
     * ShipObject (iShipId and the augment methods) is a base of ShipManager, Ship, Spreader_Fire and ProjectileFactory;
     * its methods read the ShipInfo of iShipId, i.e. the owning ship's augments.
     */
    @JvmStatic
    fun shipOf(o: Any?): Ship? = when (o) {
        is Ship -> o
        is LuaShipGeom -> o.ship
        is LuaFireSpreader -> o.ship
        is AbstractWeaponInstance -> o.ship
        else -> null
    }

    private fun shipObject() {
        val idBefore = LuaReg.numGet[A.A_ShipObject_iShipId]
        LuaReg.numGet[A.A_ShipObject_iShipId] = NumGet { o ->
            val ship = shipOf(o)
            if (ship != null && o !is Ship && o !is LuaShipGeom) LuaAdapters.shipId(ship).toDouble()
            else (idBefore ?: throw LuaError("Hyperspace Lua API not available in the browser engine: ShipObject.iShipId")).get(o)
        }
        for (m in intArrayOf(
            A.M_ShipObject_HasAugmentation, A.M_ShipObject_GetAugmentationValue, A.M_ShipObject_GetAugmentationCount,
            A.M_ShipObject_GetAugmentationList, A.M_ShipObject_AddAugmentation__SWIG_0, A.M_ShipObject_AddAugmentation__SWIG_1,
            A.M_ShipObject_RemoveAugmentation, A.M_ShipObject_ClearShipInfo, A.M_ShipObject_HasEquipment__SWIG_0,
            A.M_ShipObject_HasEquipment__SWIG_1,
        )) asShip(m)
    }

    /** Runs method [m]'s adapter with `self` replaced by the ShipManager behind a ShipObject of another class. */
    private fun asShip(m: Int) {
        val before = LuaReg.methods[m] ?: return
        LuaReg.method(m, LuaMethod { n ->
            val self = arg.obj(1)
            if (self !is Ship) {
                val ship = shipOf(self) ?: throw LuaError("stale or invalid object passed to a Hyperspace method")
                LuaPush.obj(ship, A.T_ShipManager)
                LuaJs.replace(1)
            }
            before.call(n)
        })
    }

    // ---------------------------------------------------------------- ShipManager::_targetable
    /**
     * A ship as a Targetable (ShipManager::_targetable, the target of drones and of a weapon's currentShipTarget):
     * the methods that ShipManager answers itself go to the ShipManager adapters; the projectile Targetable keeps its
     * own. Points are in the ship's own pixels (room grid plus layout offset, 35 per tile), as the HS oracle reads
     * them: the world centre is the base class's (-1, -1), every room centre is a targeting point, the shape is the box
     * around the rooms and the shield shape is the layout's ellipse at the shield's centre.
     */
    private fun shipTargetable() {
        fun shipBody(o: Any?): Ship? = LuaAdaptersProjectiles.targetableOwner(o) as? Ship
        fun wrap(m: Int, ship: (Ship, Int) -> Int) {
            val before = LuaReg.methods[m]
            LuaReg.method(m, LuaMethod { n ->
                val s = shipBody(arg.obj(1))
                if (s != null) ship(s, n) else (before ?: throw LuaError("Targetable method not available")).call(n)
            })
        }
        // ShipManager::GetShieldPower
        val shieldPower = LuaReg.methods[A.M_ShipManager_GetShieldPower]
        wrap(A.M_Targetable_GetShieldPower) { s, n ->
            LuaPush.obj(s, A.T_ShipManager); LuaJs.replace(1)
            (shieldPower ?: throw LuaError("ShipManager.GetShieldPower not available")).call(n)
        }
        wrap(A.M_Targetable_IsCloaked) { s, _ -> LuaPush.bool(s.isCloakActive) }
        wrap(A.M_Targetable_GetIsJumping) { s, _ -> LuaPush.bool(s.browserJumping) }
        wrap(A.M_Targetable_GetIsDying) { s, _ -> LuaPush.bool(s.nativeDestroyed) }
        wrap(A.M_Targetable_ValidTarget) { s, _ -> LuaPush.bool(!s.nativeDestroyed && !s.browserJumping) }
        wrap(A.M_Targetable_GetSpeed) { _, _ -> LuaPush.obj(PointfValue(0f, 0f), A.T_Pointf) }
        wrap(A.M_Targetable_GetWorldCenterPoint) { _, _ -> LuaPush.obj(PointfValue(-1f, -1f), A.T_Pointf) }
        fun centre(r: Room) = PointfValue(r.pixelCentre.x.toFloat(), r.pixelCentre.y.toFloat())
        wrap(A.M_Targetable_GetAllTargettingPoints) { s, _ ->
            LuaPush.obj(ListView(A.T_vector_Pointf, A.T_Pointf, s.rooms.mapTo(ArrayList<Any?>()) { centre(it) }), A.T_vector_Pointf)
        }
        // GetRandomTargettingPoint(true): a random system's room; false: any room (one random draw, as the drones' picks)
        wrap(A.M_Targetable_GetRandomTargettingPoint) { s, _ ->
            val systems = if (arg.bool(2)) s.rooms.filter { it.system != null } else emptyList()
            LuaPush.obj(centre(systems.ifEmpty { s.rooms }.random(ftl.browser.BrowserRandom)), A.T_Pointf)
        }
        wrap(A.M_Targetable_GetShape) { s, _ ->
            val ox = s.offset.x; val oy = s.offset.y
            val rect = LuaStructs.create(A.T_Rect)
            rect.values[A.A_Rect_x] = (35.0 * (ox + s.rooms.minOf { it.x }))
            rect.values[A.A_Rect_y] = (35.0 * (oy + s.rooms.minOf { it.y }))
            rect.values[A.A_Rect_w] = 35.0 * (s.rooms.maxOf { it.x + it.width } - s.rooms.minOf { it.x })
            rect.values[A.A_Rect_h] = 35.0 * (s.rooms.maxOf { it.y + it.height } - s.rooms.minOf { it.y })
            LuaPush.obj(rect, A.T_Rect)
        }
        wrap(A.M_Targetable_GetShieldShape) { s, _ ->
            val e = LuaStructs.create(A.T_Ellipse)
            (LuaStructs.member(e, A.A_Ellipse_center) as? LuaStruct)?.let { LuaStructs.setFields(it, s.shieldOrigin.x.toDouble(), s.shieldOrigin.y.toDouble()) }
            e.values[A.A_Ellipse_a] = s.selectedShieldHalfSize.x.toDouble(); e.values[A.A_Ellipse_b] = s.selectedShieldHalfSize.y.toDouble()
            LuaPush.obj(e, A.T_Ellipse)
        }
        // Targetable::targeted of a ship: a plain flag on the ship's Targetable (false until a script sets it)
        val targetedGet = LuaReg.numGet[A.A_Targetable_targeted]; val targetedSet = LuaReg.numSet[A.A_Targetable_targeted]
        LuaReg.num(A.A_Targetable_targeted, NumGet { o ->
            if (o is LuaAdaptersBeam.TargetableMember) (if (o.targeted) 1.0 else 0.0)
            else (targetedGet ?: throw LuaError("Targetable.targeted not available")).get(o)
        }, NumSet { o, v ->
            if (o is LuaAdaptersBeam.TargetableMember) o.targeted = v != 0.0
            else (targetedSet ?: throw LuaError("Targetable.targeted not available")).set(o, v)
        })
    }
}
