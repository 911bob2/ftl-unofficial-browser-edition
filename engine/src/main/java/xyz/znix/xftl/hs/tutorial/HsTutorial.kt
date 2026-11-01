// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Gameplay/CustomTutorial.cpp (the TutorialManager hooks at
// priority 1000 and CustomTutorialState).
package xyz.znix.xftl.hs.tutorial

import xyz.znix.xftl.hs.HS

/**
 * `<disableDefaultTutorial>`: while the tutorial runs, TutorialManager::OnLoop only advances its open timer, OnRender only
 * updates the tracker, MouseMove and MouseClick do nothing, and AllowJumping / AllowUpgrades return
 * [CustomTutorialState]'s flags (both false) instead of the lesson's. So the tutorial ship stays at its first state: no
 * instruction boxes or arrows, no lesson progress, and no jump map or ship window until a script allows them.
 */
object HsTutorial {
    /** True when the option is on in the loaded Hyperspace configuration. */
    @JvmStatic
    val disabled: Boolean get() = forced ?: (HS.active && HS.config?.disableDefaultTutorial == true)

    /** Diagnostics only (hsRuntimeProbe "wp6-save-tutorial"): the option's value in place of the configuration's. */
    @JvmField var forced: Boolean? = null

    /** CustomTutorialState::allowJumping / allowUpgrades (static in HS, false at start). */
    @JvmField var allowJumping = false
    @JvmField var allowUpgrades = false
}
