// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Ship/ShipSystems/CustomSystems.cpp (CustomUserSystems,
// the ShipSystem constructor hooks for custom ids) and src/game/Ship/ShipSystems/System_Extend.cpp (bNeedsPower of a
// custom id). FTL-Hyperspace is GPL-3.0-or-later; this engine is AGPL-3.0-or-later.
package xyz.znix.xftl.hs.systems

import org.jdom2.Element
import xyz.znix.xftl.SystemInfo
import xyz.znix.xftl.Translator
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.systems.MainSystem
import xyz.znix.xftl.systems.SubSystem
import xyz.znix.xftl.systems.SystemBlueprint

/**
 * A Lua-defined system (`<customSystems><customSystem id=".."/>`, ids from 21): HS builds it as a plain ShipSystem with no
 * behaviour of its own; the mod's scripts give it one through the system box and loop callbacks. It needs power unless
 * the definition marks it `subSystem` (then its power follows its health, as HS's constructor hook sets
 * powerState.first = healthState.first). Power, damage, repair, ion locks, upgrades and the system box are the engine's
 * common system code.
 */
class HsCustomSystem(blueprint: SystemBlueprint) : MainSystem(blueprint) {
    // Only Hyperspace has custom systems, and HS orders the boxes by SystemPositionManager (Ship.updateAvailableSystems).
    override val sortingType: SortingType get() = SortingType.CUSTOM

    override fun drawManningIcon(g: xyz.znix.xftl.rendering.Graphics, x: Int, y: Int) = customManningIcon(this, x, y)

    // ShipSystem::SaveState / LoadState of a plain ShipSystem write nothing beyond the common system state.
    override fun saveSystem(elem: Element, refs: ObjectRefs) = Unit
    override fun loadSystem(elem: Element, refs: RefLoader) = Unit
}

/**
 * A custom system defined with `subSystem="true"`: no reactor power (bNeedsPower false), the subsystem bar. Its power
 * follows its health as ShipSystem::OnLoop sets it: an upgrade (ShipSystem::UpgradeSystem adds level and health only)
 * shows from the next unpaused loop (oracle sweep-world-y1: level 2, power 1 while the world stood; sweep-world-ys1:
 * 2/2 after a second), damage takes power away at once. HS's constructor hook makes it with power 0 (HsSystemState).
 */
class HsCustomSubSystem(blueprint: SystemBlueprint) : SubSystem(blueprint) {
    override val sortingType: SortingType get() = SortingType.CUSTOM

    /** powerState.first as the last unpaused loop set it (-1 until the first). */
    private var looped = -1

    override fun update(dt: Float) {
        super.update(dt)
        if (dt > 0f) looped = undamagedEnergy
    }

    override val effectivePower: Int get() = if (looped < 0) undamagedEnergy else minOf(looped, undamagedEnergy)

    override fun drawManningIcon(g: xyz.znix.xftl.rendering.Graphics, x: Int, y: Int) = customManningIcon(this, x, y)

    override fun saveSystem(elem: Element, refs: ObjectRefs) = Unit
    override fun loadSystem(elem: Element, refs: RefLoader) = Unit
}

/**
 * ShipSystem::RenderPowerBoxes for a custom id: the crew-slot person icon of a bBoostable system (no skill of its own:
 * the outline, white while a crew member mans it at a console), above a main and a sub system alike (oracle
 * sweep-world-y1 y-bar0: sw_main, sw_slotless and sw_sub each show the outline).
 */
private fun customManningIcon(s: xyz.znix.xftl.AbstractSystem, x: Int, y: Int) {
    if (!HsSystemState.of(s).bBoostable) return
    s.hsDrawManningSkillIcon(x, y, if (s.manningCrew != null) xyz.znix.xftl.crew.SkillLevel.BASE else null)
}

/**
 * The SystemInfo of a custom system [name]. HS's constructor leaves bBoostable true (a script may clear it, as the
 * example's absorber does), so crew man it at a console: AddSystem gives a mannable system the
 * blueprint's `<slot>`; a player system without one gets no console, an enemy's a drawn one (ShipConstructionDraws).
 */
class HsCustomSystemInfo(name: String, private val sub: Boolean) : SystemInfo(name) {
    override val canBeManned: Boolean get() = true
    override val isSubSystem: Boolean get() = sub

    override fun create(blueprint: SystemBlueprint) = if (sub) HsCustomSubSystem(blueprint) else HsCustomSystem(blueprint)

    /** ShipSystem::GetLevelDescription(id >= 21): the native switch has no text for the id; GET_LEVEL_DESCRIPTION supplies it. */
    override fun getLevelName(level: Int, translator: Translator): String = ""
}
