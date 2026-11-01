package ftl.browser

/** Native progression rules. The gameplay adapter and instruction UI are separate. */
class BrowserTutorialController(private val definition: BrowserTutorialDefinition) {
    data class Observation(
        val enginePower: Int = 0, val selectedCrew: Int = 0, val engineCrew: Int = 0,
        val engineHealth: Int = 0, val engineCapacity: Int = 1,
        val airlockOpen: Boolean = false, val roomFires: Int = 1, val pilotCrew: Int = 0,
        val mapOpen: Boolean = false, val playerPaused: Boolean = false,
        val nonMissilePowered: Boolean = false, val nonMissileFired: Boolean = false,
        val missileFired: Boolean = false, val enemyPresent: Boolean = true,
        val shipInfoOpen: Boolean = false, val beamEquipped: Boolean = false,
        val upgradesOpen: Boolean = false, val pendingWeaponUpgrades: Int = 0,
        val beamPowered: Boolean = false,
        val doorsSelected: Boolean = false, val drawerOpened: Boolean = false, val weaponsSelected: Boolean = false
    )
    data class Transition(val previous: String, val next: String?, val sequence: Int)

    var index = 0
        private set
    // A patched tutorial.xml without steps has nothing to teach: the lesson is over at once.
    var completed = definition.steps.isEmpty()
        private set
    var canContinue = definition.steps.firstOrNull()?.continueButton ?: false
        private set
    val step get() = definition.steps.getOrNull(index) ?: NO_STEP
    // Native AllowJumping 0x004113b0 and AllowUpgrades 0x00411440.
    // Opening is restricted; an already-open lesson window stays interactive.
    // Hyperspace <disableDefaultTutorial> (CustomTutorial.cpp): the lesson state is replaced by CustomTutorialState's flags.
    val mapOpeningAllowed get() = if (xyz.znix.xftl.hs.tutorial.HsTutorial.disabled) xyz.znix.xftl.hs.tutorial.HsTutorial.allowJumping else completed || step.id == "DAMAGE_SYSTEM_7"
    val shipOpeningAllowed get() = if (xyz.znix.xftl.hs.tutorial.HsTutorial.disabled) xyz.znix.xftl.hs.tutorial.HsTutorial.allowUpgrades else completed || step.id in setOf("INVENTORY_1", "COMBAT_5")
    private var sequence = 0

    // A mod may patch data/tutorial.xml (Multiverse does, with or without <disableDefaultTutorial>): the native
    // TutorialManager keeps its gates by step name, so a step it has no gate for only moves on by its Continue button,
    // and a gate whose step is missing never runs. No step list stops the game.

    fun continueStep(): Transition? = if (!completed && canContinue) advance() else null

    fun observe(state: Observation): Transition? {
        if (completed || canContinue) return null
        val accepted = when (step.id) {
            "POWER_SYSTEM_2" -> state.enginePower > 0
            "DAMAGE_SYSTEM_2" -> state.selectedCrew > 0
            "DAMAGE_SYSTEM_3" -> state.engineCrew > 0
            "DAMAGE_SYSTEM_4" -> state.engineCapacity > 0 && state.engineHealth >= state.engineCapacity
            "FIRE_PC_1" -> state.airlockOpen
            "IPAD_TOOLTIPS" -> state.drawerOpened
            "FIRE_IPAD_1" -> state.doorsSelected
            "FIRE_IPAD_2" -> state.airlockOpen
            "COMBAT_IPAD_0" -> state.weaponsSelected
            "FIRE_END" -> state.roomFires == 0
            "DAMAGE_SYSTEM_6" -> state.pilotCrew > 0
            "DAMAGE_SYSTEM_7" -> state.mapOpen
            "STAR_MAP_1" -> !state.mapOpen
            "COMBAT_0" -> state.playerPaused
            "COMBAT_1" -> state.nonMissilePowered
            "COMBAT_2" -> state.nonMissileFired
            "COMBAT_4" -> state.missileFired
            "COMBAT_4_6" -> !state.enemyPresent
            "INVENTORY_1" -> state.shipInfoOpen
            "INVENTORY_2" -> !state.shipInfoOpen
            "COMBAT_5" -> state.upgradesOpen
            "UPGRADES_1_2" -> !state.upgradesOpen
            "UPGRADES_2" -> state.beamPowered
            else -> false
        }
        if (!accepted) return null
        if (step.id in ENABLE_CONTINUE) {
            // Native CheckState latches Continue; undoing the action afterwards
            // does not disable it again or automatically leave this step.
            canContinue = true
            return null
        }
        return advance()
    }

    fun equipmentAcceptAllowed(state: Observation) = step.id != "INVENTORY_2" || state.beamEquipped
    fun upgradeAcceptAllowed(state: Observation) = when (step.id) {
        "UPGRADES_1" -> false
        "UPGRADES_1_2" -> state.pendingWeaponUpgrades > 0
        else -> true
    }

    private fun advance(): Transition {
        val previous = step.id
        if (index == definition.steps.lastIndex) {
            completed = true
            canContinue = false
        } else {
            index++
            canContinue = step.continueButton
        }
        return Transition(previous, if (completed) null else step.id, ++sequence)
    }

    companion object {
        private val NO_STEP = BrowserTutorialDefinition.Step("", xyz.znix.xftl.GameText.literal(""), false, emptyList())
        private val ENABLE_CONTINUE = setOf("POWER_SYSTEM_2", "IPAD_TOOLTIPS", "DAMAGE_SYSTEM_4", "FIRE_END", "DAMAGE_SYSTEM_6")
    }
}
