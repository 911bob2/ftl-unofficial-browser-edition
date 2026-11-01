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
    /** Diagnostics: the names drawn again because they were in use (FUN_00605750's retries), since the last reset. */
    @JvmField var browserNameRetries = 0

    fun newName(): NewName {
        val table = nameManager.flatNames
        // Hyperspace (CrewNames.cpp): one random32() seeds a std::mt19937, whose `() >> 1` replaces every random32()
        // inside BlueprintManager::GetUnusedCrewName.
        val hsNames = if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.Mt19937(NativeRandom.next31()) else null
        var tries = 21
        var entry: Pair<String, Boolean>
        while (true) {
            entry = table[if (hsNames == null) NativeRandom.nextInt(table.size) else (hsNames.next() ushr 1) % table.size]
            if (usedNames.isEmpty() || !usedNames.contains(entry.first))
                break
            tries--
            if (tries == 0)
                break
            browserNameRetries++
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
    /**
     * Keeps the drawn index of every layer on [info] when a layer other than the largest has a choice (Hyperspace
     * races: each layer is drawn with its own choice); vanilla races have one such layer and keep only [colourIndex].
     */
    fun layered(info: LivingCrewInfo, colours: IntArray): LivingCrewInfo {
        val race = info.race
        val choosing = colours.indices.count { race.colourFilters[it].size > 1 }
        if (choosing > 1) info.hsLayerColours = colours.copyOf()
        return info
    }

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
        val info = layered(LivingCrewInfo(game, race, name.name, colourIndex(race, colours, name.male)), colours)
        if (skillsForLevel != null)
            randomSkills(info, skillsForLevel)
        return info
    }

    /**
     * CrystalAlien's constructor draws whether its lockdown ever gets ready. Hyperspace makes a crew race its own
     * body, which does not draw this.
     */
    fun crystalPowerDraw(race: String): Boolean =
        race == "crystal" && !xyz.znix.xftl.hs.crew.CustomCrewManager.isRace(race) && NativeRandom.next31() and 1 == 0

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
        if (raceName == "random") {
            // Hyperspace keeps the first name: a random boarder draws no second one under it.
            if (!xyz.znix.xftl.hs.HS.active) name = newName().name
        } else
            newName()
        NativeRandom.next31() // the animation timer's start delay (TimerHelper::Start)
        val colours = drawColours(race)
        hsInitializeDraw(race.name)
        val crystalNeverReady = crystalPowerDraw(race.name)
        return Boarder(layered(LivingCrewInfo(game, race, name, colourIndex(race, colours, first.male)), colours), crystalNeverReady)
    }

    /**
     * ShipManager::AddCrewMemberFromString (1.6.14 0x4aea60) with an empty name, as BossShip::StartStage (0x406980)
     * and Hyperspace's flagship crew make them: "random" first picks the class (BlueprintManager::GetRandomCrew);
     * the class's blueprint comes from GetCrewBlueprint (0x60e020), which draws a name (0x605750) for it; the empty
     * name then makes 0x4aea60 draw a second one with its sex, which is kept; the crew constructor (0x4685a0) draws
     * the animation timer and the colours, and a crystal whether its power ever gets ready.
     */
    fun createFromString(game: xyz.znix.xftl.game.InGameState, raceName: String): Boarder {
        val race = game.lootPool.getCrewOrRandom(NativeRandom, raceName)
        newName() // GetCrewBlueprint's name, replaced below
        val name = newName()
        NativeRandom.next31() // the animation timer's start delay (TimerHelper::Start)
        val colours = drawColours(race)
        hsInitializeDraw(race.name)
        val crystalNeverReady = crystalPowerDraw(race.name)
        return Boarder(layered(LivingCrewInfo(game, race, name.name, colourIndex(race, colours, name.male)), colours), crystalNeverReady)
    }

    /**
     * CrewStoreBox 0x51ae20: the race was picked by the caller; the store
     * draws the name first, then the colours, then the skills of the world
     * level (events draw the colours first and skills only when unauthored).
     */
    fun createStoreCrew(game: xyz.znix.xftl.game.InGameState, race: CrewBlueprint, worldLevel: Int,
                        namedBlueprint: Boolean = false): LivingCrewInfo {
        // CrewStoreBox named-species path: GetCrewBlueprint creates a discarded name, then
        // GetUnusedCrewName chooses the displayed name (1.6.9 0x508380; 1.6.14 0x51ae20).
        // The ordinary random section takes its blueprint from the pool without this lookup.
        if (namedBlueprint) newName()
        val name = newName()
        val colours = drawColours(race)
        val info = layered(LivingCrewInfo(game, race, name.name, colourIndex(race, colours, name.male)), colours)
        randomSkills(info, worldLevel)
        return info
    }

    companion object {
        /**
         * Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Ship/CustomCrew.cpp (CrewMember constructor
         * hook, CrewMember_Extend::Initialize): a crew member whose race hyperspace.xml defines gets a new crew
         * animation after the original's constructor, whose start-up timer draws once more (after the colours, before
         * a crystal's own draw). Ion intruders keep theirs. Measured against the original's stream (storescan).
         *
         * A race whose `<animBase>` is rock gets a RockAnimation there instead and draws once more (r7b oracle probes,
         * AddCrewMemberFromBlueprint from fixed seeds: rock, rock_outcast and rock_commando 1 + layers + 2, every other
         * race 1 + layers + 1; in seed 505's sector-5 guard ship the three rock_outcasts were the 3 missing draws).
         */
        @JvmStatic
        fun hsInitializeDraw(race: String, ionDrone: Boolean = false) {
            if (xyz.znix.xftl.hs.HS.active && !ionDrone && xyz.znix.xftl.hs.crew.CustomCrewManager.isRace(race)) {
                NativeRandom.next31()
                if (xyz.znix.xftl.hs.crew.CustomCrewManager.definitionOrNull(race)?.animBase == "rock")
                    NativeRandom.next31()
            }
        }

        // The skill step table (0x884760): (min, max) steps for world levels 0-7.
        private val RANDOM_SKILL_LEVELS = intArrayOf(0, 0, 0, 0, 0, 1, 0, 2, 1, 2, 1, 2, 1, 3, 2, 3)
    }
}
