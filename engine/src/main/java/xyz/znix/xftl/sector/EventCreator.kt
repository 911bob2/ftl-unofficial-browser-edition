package xyz.znix.xftl.sector

import ftl.browser.NativeRandom
import xyz.znix.xftl.Blueprint
import xyz.znix.xftl.BlueprintList
import xyz.znix.xftl.crew.CrewBlueprint
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.LootDropGenerator
import xyz.znix.xftl.game.LootPool
import xyz.znix.xftl.game.Resource
import kotlin.random.Random

/**
 * EventGenerator::CreateEvent (0x5df690) and GetEvent (0x5dce90): creates an
 * event and, recursively, every event its choices lead to, drawing from the
 * global [NativeRandom] stream in the original's order. A map generation
 * reseeds that stream, so the same seed always gives the same events.
 *
 * What a creation draws, in order: the list picks down to the event, the
 * text from a text list, the resource gates and ranges (fuel, drones,
 * missiles, a throwaway draw, scrap), the crew member (race, colours,
 * name, skills), the weapon, drone and augment, the automatic reward, the
 * ship seed, each choice (its event first, then its text), and finally the
 * boarder count - which is always drawn.
 */
class EventCreator(private val game: InGameState) {
    val dlcEnabled: Boolean get() = game.isAdvancedEdition

    private val pool: LootPool get() = game.lootPool

    fun eventFor(name: String): IEvent = game.eventManager[name]

    /**
     * CreateEvent with the global stream (seed -1): the event is created
     * again from the top while a pick fails, as the original's loop does -
     * a consumed unique event, or a choice that leads to one, yields nothing.
     */
    fun create(root: IEvent, level: Int): CreatedEvent? {
        if (root is Event && root.isConsumedUnique)
            return null
        for (attempt in 0 until (if (root is EventList) MAX_ATTEMPTS else 1)) {
            val created = getEvent(root, level)
            if (created != null)
                return created
        }
        return null
    }

    /**
     * GetEvent for an event whose list entries were already picked (see
     * [EventList.resolve]), outside a map generation. The crew names in use are
     * the player's crew plus the names generated earlier in this frame (the list
     * is reset every frame, see [refreshUsedNames]).
     */
    fun createFrom(event: Event, level: Int): CreatedEvent? = create(event, level)

    /**
     * A ship's ending event (destroyed, deadCrew, gotaway, surrender, escape):
     * WorldManager::UpdateLocation1 (0x61b930) and PauseLoop call CreateEvent
     * (0x5df690) with the world level and the ship event's seed
     * (WorldManager+0x1218), which reseeds the global stream first. A ship
     * without a known seed continues the global stream (seed -1).
     */
    fun createShipEnding(event: IEvent, ship: xyz.znix.xftl.Ship?): CreatedEvent? {
        ship?.eventSeed?.let { NativeRandom.seed(it) }
        return create(event, currentLevel())
    }

    /** Diagnostics: generate with these names in use instead of the player's crew's. */
    var usedNamesFixture: List<String>? = null

    /**
     * BlueprintManager::SetUsedCrewNames via CrewMemberFactory 0x467dd0: the names the crew generator avoids are
     * those of the player's crew (not drones, not boarders or enemy crew). The game does this every frame
     * ([InGameState.update]), when a crew member is made or removed, and when crews are counted; a generated
     * name is added to the list until the next reset.
     */
    fun refreshUsedNames() {
        usedNamesFixture?.let {
            game.crewFactory.setUsedNames(it)
            return
        }
        if (game.player == null)
            return
        game.crewFactory.setUsedNames(game.playerCrew.map { it.info })
    }

    /**
     * StarMap::NewGame (0x5f9e00) after creating START_GAME: space-newline-space-newline and a tip are appended to its text, the tip
     * being `random32() % size` over TIPS_LIST_PC (a plain pick, nothing is removed). The original shows the
     * first-run hint instead on a profile's very first game; a fresh profile is not told apart here.
     */
    fun appendStartTip(event: CreatedEvent) {
        val nl = "\n"
        val list = game.eventManager.getTextList("TIPS_LIST_PC") ?: return
        val tip = list.pick(NativeRandom, false) as? EventText ?: return
        val text = event.text ?: return
        event.text = EventText(text.localised + " " + nl + " " + nl + tip.localised, null, text.planet, text.back)
    }

    /** The level events are created with now: the world level, the current sector. */
    fun currentLevel(): Int = game.currentBeacon?.sector?.sectorNumber ?: 0

    /** EventGenerator 0x5db020: the unique events and used texts are returned for a new map. */
    fun clearEvents() {
        game.eventManager.restoreEventState()
    }

    /** BlueprintManager 0x60ba60 + SetRarity: the sector's rarity list applies to random picks. */
    fun setSectorRarity(type: SectorType) {
        game.setLootPool(LootPool(game.blueprintManager, type))
    }

    private fun getEvent(root: IEvent, level: Int): CreatedEvent? {
        val rand = NativeRandom

        var current = root
        while (current is EventList)
            current = current.pickEntry()
        val base = current as Event
        if (base.isConsumedUnique)
            return null

        val created = CreatedEvent(base, level)

        base.text?.let { created.text = pickText(it, true) }

        // The resources: up to `limit` of them are rolled (a limit of 100
        // without item_modify limits), each only when its gate draw passes.
        var limit = 100
        val limitSpec = base.resourceLimit
        if (limitSpec.min != 0 || limitSpec.max != 0)
            limit = roll(limitSpec)
        if (rand.next31() % 4 < limit) {
            created.fuel = roll(base.resourceRanges.getValue(Resource.FUEL))
            if (created.fuel > 0) limit--
        }
        if (rand.next31() % 3 < limit) {
            created.droneParts = roll(base.resourceRanges.getValue(Resource.DRONES))
            if (created.droneParts > 0) limit--
        }
        if (rand.next31() % 2 < limit) {
            created.missiles = roll(base.resourceRanges.getValue(Resource.MISSILES))
            if (created.missiles > 0) limit--
        }
        rand.next31()
        if (limit > 0)
            created.scrap = roll(base.resourceRanges.getValue(Resource.SCRAP))

        base.addedCrew.firstOrNull()?.let { created.crew = createCrew(it, level) }

        created.weapon = pickItem(base.weaponName, 0)
        created.drone = pickItem(base.droneName, 1)
        created.augment = pickItem(base.augmentName, 2)

        base.autoRewards?.let { (type, tier) -> applyAutoReward(created, type, tier, level) }

        // A ship seed whenever the event has a ship; load-less ships are
        // defined inline and always exist.
        if (base.hasShip) {
            val load = base.loadShipName
            if (load == null || game.eventManager.hasShip(load))
                created.shipSeed = rand.next31()
        }

        for (choice in base.choices) {
            val child = getEvent(choice.event, level) ?: return null
            created.choices.add(CreatedChoice(choice, pickText(choice.text, false), child))
        }

        // The boarders are always rolled: a count between min and max.
        val boarders = base.boarderCount
        created.boarders = rand.next31() % (boarders.last + 1 - boarders.first) + boarders.first

        base.consumeUnique()
        return created
    }

    private fun roll(spec: RangeSpec): Int = rangeRoll(NativeRandom, spec.min, spec.max, spec.chance)

    /** A text out of a text list (removing it while two or more remain, for the event's own text). */
    private fun pickText(text: IEventText, remove: Boolean): EventText {
        var current = text
        while (current is TextList)
            current = current.pick(NativeRandom, remove)
        return current as EventText
    }

    /**
     * The crew member: `random` is a weighted pick, an explicit class is
     * GetCrewBlueprint 0x60e020 (which draws a name of its own that is
     * thrown away), then the colour draw of each layer, then the name
     * (unless the event names one), then the skills (unless the event sets
     * some).
     */
    private fun createCrew(spec: AddCrew, level: Int): CreatedCrew {
        val factory = game.crewFactory
        val race: CrewBlueprint = when (spec.race) {
            null, "random" -> pool.getRandom(NativeRandom) { it is CrewBlueprint } as? CrewBlueprint ?: error("No available crew!")
            else -> {
                factory.newName()
                game.blueprintManager.getOrNull(spec.race) as? CrewBlueprint ?: game.blueprintManager["human"] as CrewBlueprint
            }
        }
        val colours = factory.drawColours(race)

        val name: String
        val male: Boolean
        if (spec.name != null) {
            name = game.translator[spec.name]
            male = true
        } else {
            val generated = factory.newName()
            name = generated.name
            male = generated.male
        }

        val skills = if (spec.skills.isNotEmpty()) spec.skills else factory.randomSkillMap(level)
        return CreatedCrew(race, name, factory.colourIndex(race, colours, male), skills)
    }

    /** The weapon, drone or augment: RANDOM is a weighted pick, a list name draws one of its entries. */
    private fun pickItem(name: String?, kind: Int): Blueprint? {
        if (name == null)
            return null
        if (name == "RANDOM") {
            return when (kind) {
                0 -> pool.getWeapon(NativeRandom)
                1 -> pool.getDrone(NativeRandom)
                else -> pool.getAugment(NativeRandom)
            }
        }

        val blueprint = game.blueprintManager[name]
        if (blueprint is BlueprintList) {
            val names = blueprint.names
            return game.blueprintManager[names[NativeRandom.next31() % names.size]].resolve(NativeRandom)
        }
        return blueprint.resolve(NativeRandom)
    }

    /** The automatic reward (0x5dff60): its numbers replace the event's, and its item fills an empty slot. */
    private fun applyAutoReward(created: CreatedEvent, type: xyz.znix.xftl.game.RewardType, tier: xyz.znix.xftl.game.RewardTier, level: Int) {
        val explicit = created.weapon != null || created.drone != null || created.augment != null
        val rewards = LootDropGenerator.generateRewards(game, NativeRandom, tier, type, level, game.difficulty, explicit)
        if (rewards.fuel != 0) created.fuel = rewards.fuel
        if (rewards.missiles != 0) created.missiles = rewards.missiles
        if (rewards.droneParts != 0) created.droneParts = rewards.droneParts
        if (rewards.scrap != 0) created.scrap = rewards.scrap
        for (item in rewards.items) {
            when (item) {
                is xyz.znix.xftl.weapons.AbstractWeaponBlueprint -> created.weapon = item
                is xyz.znix.xftl.weapons.DroneBlueprint -> created.drone = item
                is xyz.znix.xftl.augments.AugmentBlueprint -> created.augment = item
            }
        }
    }

    companion object {
        private const val MAX_ATTEMPTS = 1000

        /**
         * The range roll (0x5caf40): a chance above zero is an outright
         * zero on `rand % 100 < 100 * chance`; otherwise the minimum plus
         * `rand % (max + 1 - min)`, or just the minimum when the maximum is
         * zero.
         */
        @JvmStatic
        fun rangeRoll(rand: Random, min: Int, max: Int, chance: Float): Int {
            if (0f < chance) {
                if ((rand.nextInt() % 100).toFloat() < 100f * chance)
                    return 0
            }
            if (max == 0)
                return min
            return min + rand.nextInt() % (max + 1 - min)
        }
    }
}
