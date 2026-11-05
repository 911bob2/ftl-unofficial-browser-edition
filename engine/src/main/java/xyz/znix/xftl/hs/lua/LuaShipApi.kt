// Ported from FTL-Hyperspace (GPL-3.0-or-later) commit 1b05a07 (lua/ script API and .i members, src/ files named below),
// not from the 1.23.2 tag.
package xyz.znix.xftl.hs.lua

import xyz.znix.xftl.AbstractSystem
import xyz.znix.xftl.Ship
import xyz.znix.xftl.ai.nativeSystemId
import xyz.znix.xftl.game.ShipBlueprint
import xyz.znix.xftl.hs.lua.LuaApi as A
import xyz.znix.xftl.hs.lua.LuaArgs as arg
import xyz.znix.xftl.hs.lua.LuaPush as ret

/** Hyperspace.App.gui (CommandGui), Hyperspace.App.world.space (SpaceManager), Hyperspace.Tutorial, Hyperspace.Resources. */
object LuaGui : LuaTyped { override val luaType get() = A.T_CommandGui }
object LuaSpace : LuaTyped { override val luaType get() = A.T_SpaceManager }
object LuaTutorial : LuaTyped { override val luaType get() = A.T_TutorialManager }
object LuaResources : LuaTyped { override val luaType get() = A.T_ResourceControl }
/**
 * A ship's PowerManager (FTL keeps one per ship id); the iTemp* fields are the ship's HsPower (defaults iTempPowerCap
 * 1000, iTempDividePower 1), which limits the reactor while HS is active.
 */
class LuaPower(@JvmField val ship: Ship) : LuaTyped {
    override val luaType get() = A.T_PowerManager
    var tempPowerCap: Int
        get() = xyz.znix.xftl.hs.systems.HsPower.of(ship).cap
        set(v) { xyz.znix.xftl.hs.systems.HsPower.of(ship).cap = v }
    var tempPowerLoss: Int
        get() = xyz.znix.xftl.hs.systems.HsPower.of(ship).loss
        set(v) { xyz.znix.xftl.hs.systems.HsPower.of(ship).loss = v }
    var tempDividePower: Int
        get() = xyz.znix.xftl.hs.systems.HsPower.of(ship).divide
        set(v) { xyz.znix.xftl.hs.systems.HsPower.of(ship).divide = v }
}
object LuaMenu : LuaTyped { override val luaType get() = A.T_MainMenu }
object LuaShipBuilder : LuaTyped { override val luaType get() = A.T_ShipBuilder }
/** CommandGui::upgradeButton (a Button): active while the ship screen may open (the engine's ShipButton). */
object LuaUpgradeButton : LuaTyped { override val luaType get() = A.T_Button }
object LuaMouse : LuaTyped { override val luaType get() = A.T_MouseControl }

/**
 * ShipManager / ShipObject / Ship members MV reads every frame (systems, blueprint, augments, rooms) and the
 * CommandGui pause flags. System ids are FTL's (0 shields ... 15 hacking), as `nativeSystemId` maps them.
 */
object LuaShipApi {
    private fun ship(n: Int = 1) = arg.obj(n) as Ship

    /** ShipManager::GetSystem / systemKey: the installed system with FTL id [id], or null. */
    @JvmStatic
    // systemKey[id]: for artillery the one made last (AddSystem and RemoveSystem re-key in vSystemList order; oracle
    // sweep-world-y1 y-artsys: the second artillery's room)
    fun system(ship: Ship, id: Int): AbstractSystem? =
        if (id == 11) HsShipLuaState.systemList(ship).lastOrNull { nativeSystemId(it) == id } else ship.systems.firstOrNull { nativeSystemId(it) == id }

    fun install() {
        LuaReg.obj(A.A_module_Resources, ObjGet { LuaResources })
        LuaReg.obj(A.A_module_Tutorial, ObjGet { LuaTutorial })
        LuaReg.obj(A.A_CApp_gui, ObjGet { LuaGui })
        LuaReg.obj(A.A_WorldManager_space, ObjGet { LuaSpace })

        // CommandGui pause state (CommandGui::bPaused is the player's pause; menu/event pauses come from windows)
        LuaReg.bool(A.A_CommandGui_bPaused, { LuaAdapters.inGame()?.isPlayerPaused == true }, { _, value ->
            LuaAdapters.inGame()?.let { if (it.isPlayerPaused != value) it.toggleQuickPause() }
        })
        LuaReg.bool(A.A_CommandGui_bAutoPaused, { LuaAdapters.inGame()?.isFocusPaused == true })
        // The command console's box (CommandGui::inputBox) is one of CommandGui's focus windows too, so Multiverse's
        // ON_KEY_DOWN hotkeys ([ and ]) stay out of the text typed into it. That box is the one debug console here.
        LuaReg.bool(A.A_CommandGui_menu_pause, {
            val game = LuaAdapters.inGame()
            val ui = game?.shipUI
            ui != null && ((ui.isWindowOpen && !ui.tutorialDialogueOpen) || game.isDebugConsoleVisible)
        })
        LuaReg.bool(A.A_CommandGui_event_pause, { LuaAdapters.inGame()?.shipUI?.tutorialDialogueOpen == true })
        LuaReg.bool(A.A_CommandGui_choiceBoxOpen, { LuaAdapters.inGame()?.shipUI?.tutorialDialogueOpen == true })

        // SpaceManager::drones: the drones flying in space (both ships')
        val spaceDrones = ListView(A.T_vector_SpaceDrone, A.T_SpaceDrone) {
            val s = LuaAdapters.inGame()
            if (s == null) emptyList() else s.player.externalDrones + (s.enemy?.externalDrones ?: emptyList())
        }
        LuaReg.obj(A.A_SpaceManager_drones, ObjGet { spaceDrones })

        // SpaceManager::projectiles: FTL keeps one list in space; the engine keeps one per ship space. push_back puts
        // the projectile into its currentSpace (MV push_projectiles_to_world); removing through Lua is not supported.
        val spaceProjectiles = ListView(A.T_vector_Projectile, A.T_Projectile) {
            val s = LuaAdapters.inGame()
            // the drone pods fly here but are SpaceDrones natively, in SpaceManager::drones
            val all = if (s == null) emptyList() else (s.player.projectiles + (s.enemy?.projectiles ?: emptyList()))
                .filter { it !is xyz.znix.xftl.weapons.AbstractProjectile || it.hsNativeProjectile }
            object : java.util.AbstractList<Any?>() {
                override val size: Int get() = all.size
                override fun get(index: Int): Any? = all[index]
                override fun add(index: Int, element: Any?) {
                    val p = element as? xyz.znix.xftl.weapons.AbstractProjectile ?: throw LuaError("expected a Projectile")
                    val game = s ?: throw LuaError("no game")
                    xyz.znix.xftl.weapons.ProjectileRuntime.added(p, p.hsCurrentSpace ?: game.player)
                }
                override fun removeAt(index: Int): Any? = throw LuaError("SpaceManager::projectiles removal is not supported")
            }
        }
        LuaReg.obj(A.A_SpaceManager_projectiles, ObjGet { spaceProjectiles })
        LuaReg.bool(A.A_SpaceManager_bStorm, {
            LuaAdapters.inGame()?.currentBeacon?.environmentTypes?.contains(xyz.znix.xftl.sector.Beacon.EnvironmentType.ION_STORM) == true
        })

        // PowerManager::GetPowerManager(shipId): currentPower = (used, reactor). The iTemp* fields are the ship's
        // HsPower and limit the reactor from the next power update (hs.systems).
        LuaReg.method(A.M_PowerManager_GetPowerManager, LuaMethod {
            val id = arg.int(1)
            val ship = LuaEvents.generatingShip?.takeIf { id == 1 } ?: LuaAdapters.shipManager(id)
            if (ship == null) ret.nil() else ret.obj(LuaViews.cached(ship, A.T_PowerManager) { o -> LuaPower(o as Ship) }, A.T_PowerManager)
        })
        LuaReg.obj(A.A_PowerManager_currentPower, ObjGet { o ->
            val p = o as LuaPower
            LuaViews.cached(p.ship, A.A_PowerManager_currentPower) { s ->
                val ship = s as Ship
                fun held() = ship.mainSystems.sumOf { xyz.znix.xftl.hs.systems.HsSystemsLua.reactorPower(it) } +
                    xyz.znix.xftl.hs.systems.HsSystemInstall.ghostPower(ship)
                IntPairView(A.T_pair_int_int, IGet { held() + ship.hsReactorUsedOffset }, ISet { ship.hsReactorUsedOffset = it - held() },
                    IGet { ship.reactorPower }, ISet { ship.purchasedReactorPower = it })
            }
        })
        LuaReg.num(A.A_PowerManager_iTempPowerCap, NumGet { (it as LuaPower).tempPowerCap.toDouble() }, NumSet { o, v -> (o as LuaPower).tempPowerCap = v.toInt() })
        LuaReg.num(A.A_PowerManager_iTempPowerLoss, NumGet { (it as LuaPower).tempPowerLoss.toDouble() }, NumSet { o, v -> (o as LuaPower).tempPowerLoss = v.toInt() })
        LuaReg.num(A.A_PowerManager_iTempDividePower, NumGet { (it as LuaPower).tempDividePower.toDouble() }, NumSet { o, v -> (o as LuaPower).tempDividePower = v.toInt() })
        LuaReg.method(A.M_PowerManager_GetMaxPower, LuaMethod { ret.num((arg.obj(1) as LuaPower).ship.reactorPower) })
        LuaReg.method(A.M_PowerManager_GetAvailablePower, LuaMethod { ret.num((arg.obj(1) as LuaPower).ship.powerAvailable) })

        // systems
        LuaReg.method(A.M_ShipManager_HasSystem, LuaMethod { ret.bool(system(ship(), arg.int(2)) != null) })
        LuaReg.method(A.M_ShipManager_GetSystem, LuaMethod { ret.obj(system(ship(), arg.int(2)), A.T_ShipSystem) })
        LuaReg.obj(A.A_ShipManager_vSystemList, ObjGet { LuaViews.cached(it!!, A.A_ShipManager_vSystemList) { o -> ListView(A.T_vector_ShipSystem, A.T_ShipSystem) { HsShipLuaState.systemList(o as Ship) } } })
        LuaReg.obj(A.A_ShipManager_weaponSystem, ObjGet { (it as Ship).weapons })
        LuaReg.obj(A.A_ShipManager_shieldSystem, ObjGet { (it as Ship).shields ?: xyz.znix.xftl.hs.systems.HsDetachedShields.of(it) })
        LuaReg.obj(A.A_ShipManager_droneSystem, ObjGet { (it as Ship).drones })
        LuaReg.obj(A.A_ShipManager_engineSystem, ObjGet { (it as Ship).engines })
        LuaReg.obj(A.A_ShipManager_oxygenSystem, ObjGet { (it as Ship).oxygen })
        LuaReg.obj(A.A_ShipManager_medbaySystem, ObjGet { (it as Ship).medbay })
        LuaReg.obj(A.A_ShipManager_cloneSystem, ObjGet { (it as Ship).clonebay })
        LuaReg.obj(A.A_ShipManager_cloakSystem, ObjGet { (it as Ship).cloaking })
        LuaReg.obj(A.A_ShipManager_teleportSystem, ObjGet { (it as Ship).teleporter })
        LuaReg.obj(A.A_ShipManager_hackingSystem, ObjGet { (it as Ship).hacking })
        LuaReg.obj(A.A_ShipManager_mindSystem, ObjGet { (it as Ship).mindControl })
        LuaReg.obj(A.A_ShipManager_batterySystem, ObjGet { (it as Ship).systems.firstOrNull { s -> nativeSystemId(s) == 12 } })
        LuaReg.obj(A.A_ShipManager_artillerySystems, ObjGet { LuaViews.cached(it!!, A.A_ShipManager_artillerySystems) { o -> ListView(A.T_vector_ArtillerySystem, A.T_ArtillerySystem) { (o as Ship).artillery } } })

        // resources
        LuaReg.num(A.A_ShipManager_fuel_count, NumGet { (it as Ship).fuelCount.toDouble() }, NumSet { o, v -> (o as Ship).fuelCount = v.toInt() })
        LuaReg.method(A.M_ShipManager_GetMissileCount, LuaMethod { ret.num(ship().missilesCount) })

        // blueprint
        LuaReg.obj(A.A_ShipManager_myBlueprint, ObjGet { xyz.znix.xftl.hs.render.HsSwitchShip.myBlueprint(it as Ship) }) // the player's own copy (round 14 UI)
        LuaReg.str(A.A_ShipBlueprint_layoutFile, StrGet { (it as ShipBlueprint).layout })
        LuaReg.str(A.A_ShipBlueprint_imgFile, StrGet { (it as ShipBlueprint).img })
        LuaReg.str(A.A_ShipBlueprint_blueprintName, StrGet { (it as ShipBlueprint).name })

        // augments: ShipObject::HasAugmentation counts the installed augments with that name
        LuaReg.method(A.M_ShipObject_HasAugmentation, LuaMethod {
            val name = arg.str(2) ?: ""
            ret.num(ship().augments.count { a -> a.name == name })
        })
        LuaReg.method(A.M_ShipObject_GetAugmentationValue, LuaMethod { ret.num(ship().getAugmentValue(arg.str(2) ?: "")) })

        // WeaponSystem::weapons: the weapons in the hardpoints, in slot order
        LuaReg.obj(A.A_WeaponSystem_weapons, ObjGet {
            LuaViews.cached(it!!, A.A_WeaponSystem_weapons) { o ->
                val w = o as xyz.znix.xftl.systems.Weapons
                ListView(A.T_vector_ProjectileFactory, A.T_ProjectileFactory) {
                    val s = LuaAdapters.inGame()
                    val ship = when { s == null -> null; s.player.weapons === w -> s.player; s.enemy?.weapons === w -> s.enemy; else -> null }
                    ship?.hardpoints?.mapNotNull { h -> h.weapon } ?: emptyList()
                }
            }
        })
        // ShipSystem::Powered: GetEffectivePower() > 0 (allocated power for main systems, undamaged for subsystems)
        LuaReg.method(A.M_ShipSystem_Powered, LuaMethod {
            ret.bool(when (val s = arg.obj(1)) {
                is xyz.znix.xftl.systems.MainSystem -> s.powerSupplied > 0
                is xyz.znix.xftl.systems.SubSystem -> s.effectivePower > 0
                else -> false
            })
        })
        // CApp::menu (MainMenu): open while the main menu shows; TutorialManager::bRunning; MouseControl::position
        LuaReg.obj(A.A_CApp_menu, ObjGet { LuaMenu })
        // The hangar is MainMenu's ShipBuilder (CApp::OnLoop starts the run from MainMenu::Choice), so the menu stays
        // open while it shows; MainMenu::shipBuilder.bOpen is the hangar alone.
        LuaReg.bool(A.A_MainMenu_bOpen, { HsLua.game?.currentState.let { it is xyz.znix.xftl.game.MainMenuState || it is xyz.znix.xftl.hangar.SelectShipState } })
        LuaReg.obj(A.A_MainMenu_shipBuilder, ObjGet { LuaShipBuilder })
        // CommandGui::upgradeButton.bActive: the engine's ship (upgrade) button is enabled, i.e. not in danger and not
        // locked by the tutorial (Buttons.ShipButton.disabled); other GenericButtons keep their own adapter.
        LuaReg.obj(A.A_CommandGui_upgradeButton, ObjGet { LuaUpgradeButton })
        val prevActive = LuaReg.numGet[A.A_GenericButton_bActive]; val prevSetActive = LuaReg.numSet[A.A_GenericButton_bActive]
        LuaReg.num(A.A_GenericButton_bActive, NumGet { o ->
            if (o === LuaUpgradeButton) {
                val g = LuaAdapters.inGame()
                if (g != null && !g.isInDanger && g.mainGame?.tutorialAdapter?.controller?.shipOpeningAllowed != false) 1.0 else 0.0
            } else (prevActive ?: throw LuaError("Hyperspace Lua API not available in the browser engine: GenericButton.bActive")).get(o)
        }, NumSet { o, v ->
            if (o === LuaUpgradeButton) xyz.znix.xftl.hs.HS.unsupported("lua", "attribute", "CommandGui.upgradeButton.bActive (set)")
            else (prevSetActive ?: throw LuaError("Hyperspace Lua API not available in the browser engine: GenericButton.bActive")).set(o, v)
        })
        LuaReg.bool(A.A_ShipBuilder_bOpen, { HsLua.game?.currentState is xyz.znix.xftl.hangar.SelectShipState })
        LuaReg.bool(A.A_TutorialManager_bRunning, { HsLua.game?.tutorialOverlay != null })
        LuaReg.obj(A.A_module_Mouse, ObjGet { LuaMouse })
        val mouse = PointValue(0, 0)
        LuaReg.obj(A.A_MouseControl_position, ObjGet { mouse.x = LuaEvents.deskX; mouse.y = LuaEvents.deskY; mouse })

        // ProjectileFactory::blueprint (the weapon's blueprint), Blueprint::name, ShipSystem::iHackEffect
        // (0 not hacked, 1 hacking drone attached, 2 hack pulse active)
        LuaReg.obj(A.A_ProjectileFactory_blueprint, ObjGet { (it as xyz.znix.xftl.weapons.AbstractWeaponInstance).type })
        LuaReg.str(A.A_Blueprint_name, StrGet { (it as xyz.znix.xftl.Blueprint).name })
        LuaReg.num(A.A_ShipSystem_iHackEffect, NumGet { val s = it as AbstractSystem; if (s.hackedBy == null) 0.0 else if (s.isHackActive) 2.0 else 1.0 })

        // rooms
        LuaReg.obj(A.A_Ship_vRoomList, ObjGet { o ->
            LuaViews.cached((o as LuaShipGeom).ship, A.A_Ship_vRoomList) { s -> ListView(A.T_vector_Room, A.T_Room) { (s as Ship).rooms } }
        })
    }
}
