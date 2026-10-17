package xyz.znix.xftl.crew

import ftl.browser.NativeRandom

/**
 * The original's crew member generation (CrewBlueprint 0x602e30 colours,
 * BlueprintManager 0x601ea0 / 0x605750 names, CrewBlueprint 0x6058a0
 * skills), drawing from the global [NativeRandom] stream in the original's
 * order. Everything that makes crew from nothing (event rewards, store
 * crew, boarders, enemy crews) should go through it so a seed gives the
 * same people:
 *
 *  1. [drawColours]: one draw per colour layer of the race (a race without
 *     layers draws nothing);
 *  2. [newName]: a draw per try, up to 21 tries to avoid a name in use;
 *  3. [randomSkills]: only for crew that don't get skills from elsewhere.
 *
 * [createRandom] runs the three steps in order.
 */
class NativeCrewFactory(private val nameManager: CrewNameManager) {
    /**
     * The names in use (BlueprintManager +0x10c): every name generated is
     * appended, and the game replaces the list with its crew's names
     * (CrewMemberFactory 0x467dd0) whenever it counts them. A generated name
     * that is already here is re-drawn.
     */
    private val usedNames = ArrayList<String>()

    /** BlueprintManager::SetUsedCrewNames: the player's crew at the moment. */
    fun setUsedNames(crew: Collection<LivingCrewInfo>) {
        usedNames.clear()
        for (info in crew)
            usedNames.add(info.name)
    }

    fun setUsedNames(names: List<String>) {
        usedNames.clear()
        usedNames.addAll(names)
    }

    fun usedNames(): List<String> = usedNames

    class NewName(val name: String, val male: Boolean)

    /**
     * FUN_00605750: draw a name from the sorted name table, at most 21
     * times while it is in use; the last name is taken (and recorded)
     * either way.
     */
    fun newName(): NewName {
        val table = nameManager.flatNames
        var tries = 21
        var entry: Pair<String, Boolean>
        while (true) {
            entry = table[NativeRandom.nextInt(table.size)]
            if (usedNames.isEmpty() || !usedNames.contains(entry.first))
                break
            tries--
            if (tries == 0)
                break
        }
        usedNames.add(entry.first)
        return NewName(entry.first, entry.second)
    }

    /** FUN_00602e30: the colour index of each layer, `rand % layer size`. */
    fun drawColours(race: CrewBlueprint): IntArray =
        IntArray(race.colourFilters.size) { NativeRandom.nextInt(race.colourFilters[it].size) }

    /**
     * The [LivingCrewInfo.colour] for drawn layer colours: the engine keeps
     * one index (the race's largest layer; the only layer with a choice in
     * vanilla) and humans add the base count for the female sprite.
     */
    fun colourIndex(race: CrewBlueprint, colours: IntArray, male: Boolean): Int {
        if (colours.isEmpty())
            return 0

        var best = 0
        for (i in colours.indices) {
            if (race.colourFilters[i].size > race.colourFilters[best].size)
                best = i
        }
        val index = colours[best]
        return if (race.name == "human" && !male) index + race.baseNumberOfColours else index
    }

    /**
     * CrewBlueprint 0x6058a0: `min + rand % (max - min + 1)` skill steps for
     * the world level (at most four), spread over two distinct random skills
     * (a step is half a bar). Always draws the step count and the two
     * skills, and chooses a skill per step.
     */
    fun randomSkillMap(worldLevel: Int): Map<Skill, Float> {
        val level = worldLevel.coerceAtLeast(0)
        var min = 0
        var max = 0
        if (level <= 7) {
            min = RANDOM_SKILL_LEVELS[level * 2]
            max = RANDOM_SKILL_LEVELS[level * 2 + 1]
        }
        val steps = min + NativeRandom.nextInt(max - min + 1)
        val boosts = if (steps < 5) steps else 4

        val skills = Skill.values()
        val first = skills[NativeRandom.nextInt(6)]
        var second = first
        while (second == first)
            second = skills[NativeRandom.nextInt(6)]

        val result = LinkedHashMap<Skill, Float>()
        if (steps > 0) {
            repeat(boosts) {
                val skill = if (NativeRandom.nextInt(2) == 0) first else second
                result[skill] = kotlin.math.min((result[skill] ?: 0f) + 0.5f, 1f)
            }
        }
        return result
    }

    fun randomSkills(info: LivingCrewInfo, worldLevel: Int) {
        info.skills.putAll(randomSkillMap(worldLevel))
    }

    /** A crew member as the original makes one: colours, then the name, then (optionally) skills. */
    fun createRandom(game: xyz.znix.xftl.game.InGameState, race: CrewBlueprint, skillsForLevel: Int? = null): LivingCrewInfo {
        val colours = drawColours(race)
        val name = newName()
        val info = LivingCrewInfo(game, race, name.name, colourIndex(race, colours, name.male))
        if (skillsForLevel != null)
            randomSkills(info, skillsForLevel)
        return info
    }

    /** An event boarder with the crystal start-up roll its constructor draws (CrystalAlien 0x487e70). */
    class Boarder(val info: LivingCrewInfo, val crystalNeverReady: Boolean)

    /**
     * One event boarder as WorldManager 0x408260 -> ShipManager::AddCrewMemberFromString 0x4aea60 makes it, in the
     * original's draw order: a name (0x605750); then the class: "random" is a weighted pick (BlueprintManager
     * ::GetRandomCrew) followed by a second name that replaces the first (the blueprint has none), any other class
     * is looked up with GetCrewBlueprint (0x60e020), which draws its own name that is then dropped; then the
     * crew constructor (0x4685a0) draws the animation timer and the colour of each layer, and a crystal draws
     * whether its power ever gets ready. The sex is that of the first name.
     */
    fun createBoarder(game: xyz.znix.xftl.game.InGameState, raceName: String): Boarder {
        val first = newName()
        val race = game.lootPool.getCrewOrRandom(NativeRandom, raceName)
        var name = first.name
        if (raceName == "random")
            name = newName().name
        else
            newName()
        NativeRandom.next31() // the animation timer's start delay (TimerHelper::Start)
        val colours = drawColours(race)
        val crystalNeverReady = if (race.name == "crystal") NativeRandom.next31() and 1 == 0 else false
        return Boarder(LivingCrewInfo(game, race, name, colourIndex(race, colours, first.male)), crystalNeverReady)
    }

    /**
     * CrewStoreBox 0x51ae20: the race was picked by the caller; the store
     * draws the name first, then the colours, then the skills of the world
     * level (events draw the colours first and skills only when unauthored).
     */
    fun createStoreCrew(game: xyz.znix.xftl.game.InGameState, race: CrewBlueprint, worldLevel: Int): LivingCrewInfo {
        val name = newName()
        val colours = drawColours(race)
        val info = LivingCrewInfo(game, race, name.name, colourIndex(race, colours, name.male))
        randomSkills(info, worldLevel)
        return info
    }

    companion object {
        // The skill step table (0x884760): (min, max) steps for world levels 0-7.
        private val RANDOM_SKILL_LEVELS = intArrayOf(0, 0, 0, 0, 0, 1, 0, 2, 1, 2, 1, 2, 1, 3, 2, 3)
    }
}
