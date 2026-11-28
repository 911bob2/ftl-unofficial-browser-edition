package xyz.znix.xftl.game

data class RunConfiguration @JvmOverloads constructor(
    val ship: String, val difficulty: Difficulty, val advancedEdition: Boolean,
    val displayName: String? = null,
    val crew: List<StartingCrew>? = null
)

/** Hangar choices contain no live crew, simulation references or blueprint edits. */
/** [male]: the sex of the name drawn for the member (null: from a human's colour, else male; LivingCrewInfo.male). */
data class StartingCrew @JvmOverloads constructor(val species: String, var name: String, var colour: Int, val male: Boolean? = null)
