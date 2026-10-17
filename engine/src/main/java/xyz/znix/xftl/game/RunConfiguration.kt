package xyz.znix.xftl.game

data class RunConfiguration @JvmOverloads constructor(
    val ship: String, val difficulty: Difficulty, val advancedEdition: Boolean,
    val displayName: String? = null,
    val crew: List<StartingCrew>? = null
)

/** Hangar choices contain no live crew, simulation references or blueprint edits. */
data class StartingCrew(val species: String, var name: String, var colour: Int)
