// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Gameplay/CustomDrones.cpp.
package xyz.znix.xftl.hs.weapons

import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.drones.AbstractIndoorsDrone
import xyz.znix.xftl.game.PlayerShipUI
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.systems.Drones

object HsDroneControls {
    /** DroneControl::SelectArmament runs this after native powering, including an already-powered drone. */
    fun afterSelect(ui: PlayerShipUI, info: Drones.DroneInfo) {
        if (!HS.active || !HS.cfg().droneSelectHotkeys) return
        val drone = info.instance as? AbstractIndoorsDrone ?: return
        if (!drone.isPowered) return
        val pawn = drone.pawn ?: return // A boarder pod has no selectable body until it lands.
        ui.hsSelectDroneCrew(pawn)
    }
}
