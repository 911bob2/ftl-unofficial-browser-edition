package xyz.znix.xftl.devutil

import xyz.znix.xftl.Blueprint
import xyz.znix.xftl.GameText
import xyz.znix.xftl.LazyShipBlueprint
import xyz.znix.xftl.Translator
import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.crew.CrewBlueprint
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.ShipFamily
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.systems.SystemBlueprint
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint
import xyz.znix.xftl.weapons.DroneBlueprint
import java.util.Locale

/**
 * What the console's `get` and `unlock` commands offer (docs/CONSOLE.md, "get and unlock"): the run's own data, mod
 * items included, each under its in-game English name with spaces turned into underscores (`Burst_Laser_II`), and
 * the ships per layout (`The_Kestrel_A`, `_B`, `_C`). The current language's name and the internal id are taken too.
 * Items a mod hides or never offers stay in the list with a tag.
 */
object ConsoleItems {
    /** A kind of item: its word in the command, and the existing command or game path it goes through. */
    enum class Kind(val word: String, val title: String, val path: String, val amount: Boolean = false, val needsHs: Boolean = false) {
        WEAPON("weapon", "Weapons", "WEAPON"),
        DRONE("drone", "Drones", "DRONE"),
        AUGMENT("augment", "Augments", "AUG"),
        CREW("crew", "Crew", "CREW"),
        SYSTEM("system", "Systems", "SYS"),
        SCRAP("scrap", "Scrap", "SCRAP", amount = true),
        FUEL("fuel", "Fuel", "FUEL", amount = true),
        MISSILES("missiles", "Missiles", "MISSILES", amount = true),
        DRONE_PARTS("drone-parts", "Drone parts", "DRONES", amount = true),
        HULL("hull", "Hull repair", "HULL", amount = true),
        UNLOCK_SHIP("ship", "Ships and layouts", "SHIP ALL / the profile's layout unlock / SHIP_CUSTOM"),
        ACHIEVEMENT("achievement", "Achievements", "ACH"),
    }

    @JvmField val GET_KINDS = listOf(Kind.WEAPON, Kind.DRONE, Kind.AUGMENT, Kind.CREW, Kind.SYSTEM, Kind.SCRAP, Kind.FUEL, Kind.MISSILES, Kind.DRONE_PARTS, Kind.HULL)
    @JvmField val UNLOCK_KINDS = listOf(Kind.UNLOCK_SHIP, Kind.ACHIEVEMENT)

    /** The kinds where `*` stands for any part of a name: the ones whose command makes sense for many items at once. */
    @JvmStatic
    fun wildcardAllowed(kind: Kind) = kind == Kind.UNLOCK_SHIP || kind == Kind.ACHIEVEMENT || kind == Kind.SYSTEM

    const val TAG_EVENT_ONLY = "event only"
    const val TAG_HIDDEN = "hidden"
    const val TAG_LOCKED = "locked"
    const val TAG_UNLOCKED = "unlocked"
    const val TAG_INSTALLED = "installed"
    const val TAG_NO_ROOM = "no room"
    /** The game has no art for it (Artillery's weaponArt is weapons/blank.png): the tile shows a placeholder. */
    const val TAG_NO_ART = "no art"

    /**
     * One item. [key] is what completion fills and what the command takes: the English name with underscores, or the
     * id where two items share that name. [layout] is the ship layout (0 = A) for ships.
     */
    class Item(
        val kind: Kind,
        val id: String,
        val english: String,
        val local: String,
        val tags: List<String>,
        val blueprint: Blueprint? = null,
        val layout: Int = -1,
        val family: ShipFamily? = null,
        /** An achievement's icon, a ship's miniship. */
        val image: String? = null,
        /** Other names it answers to (a weapon's short name). */
        val aliases: List<String> = emptyList(),
    ) {
        var key: String = keyOf(english)
            internal set
        val unlocked: Boolean get() = TAG_LOCKED !in tags
        override fun toString() = key
    }

    /** A name as a command argument: one word, spaces turned into underscores. */
    @JvmStatic
    fun keyOf(name: String): String {
        val out = StringBuilder()
        var gap = false
        for (c in name.trim()) {
            if (c.isWhitespace()) { gap = true; continue }
            if (gap) { out.append('_'); gap = false }
            out.append(c)
        }
        return out.toString()
    }

    /** A `*` pattern against [s] (both lower case): `*` is any run of characters. */
    @JvmStatic
    fun globMatches(pattern: String, s: String): Boolean {
        val parts = pattern.split('*')
        if (parts.size == 1) return pattern == s
        val head = parts.first()
        val tail = parts.last()
        if (s.length < head.length + tail.length || !s.startsWith(head) || !s.endsWith(tail)) return false
        var at = head.length
        val end = s.length - tail.length
        for (i in 1 until parts.size - 1) {
            val found = s.indexOf(parts[i], at)
            if (found < 0 || found + parts[i].length > end) return false
            at = found + parts[i].length
        }
        return true
    }

    private fun norm(s: String): String = keyOf(s).lowercase(Locale.ROOT)

    /** The English text of [t] (the commands take the English names whatever the language). */
    @JvmStatic
    fun english(t: GameText?, tr: Translator): String? {
        t ?: return null
        return if (t.hsIsLiteral) t.hsData else tr.getIn("en", t.hsData)
    }

    /** Every item of [kind] in this run, in the order the picker shows them. */
    @JvmStatic
    fun items(game: InGameState, kind: Kind): List<Item> {
        val list = when (kind) {
            Kind.WEAPON -> blueprints(game, AbstractWeaponBlueprint::class.java)
            Kind.DRONE -> blueprints(game, DroneBlueprint::class.java)
            Kind.AUGMENT -> blueprints(game, AugmentBlueprint::class.java)
            Kind.CREW -> blueprints(game, CrewBlueprint::class.java)
            Kind.SYSTEM -> systems(game)
            Kind.UNLOCK_SHIP -> layouts(game)
            Kind.ACHIEVEMENT -> achievements(game)
            else -> emptyList()
        }
        assignKeys(list)
        return list
    }

    /**
     * Two items with one English name are told apart by their ids: `<name>_<ID>` (`Artemis_Missiles_MISSILES_2`,
     * `Artemis_Missiles_MISSILES_2_PLAYER`), so every key is one item's and still reads as the name.
     */
    @JvmStatic
    fun assignKeys(list: List<Item>) {
        val byName = list.groupBy { norm(it.english) }
        for (item in list) item.key = when {
            item.english.isBlank() -> item.id
            (byName[norm(item.english)]?.size ?: 0) > 1 -> keyOf(item.english) + "_" + item.id
            else -> keyOf(item.english)
        }
        // a name can still meet another item's id (a mod's untitled blueprint is named by its id), or two ids differ
        // only in case: the id, then a number, until every key is one item's
        for (round in 0 until 3) {
            val clash = list.groupBy { it.key.lowercase(Locale.ROOT) }.values.filter { it.size > 1 }
            if (clash.isEmpty()) return
            for (group in clash) {
                if (round == 0) { for (item in group) item.key = item.id; continue }
                for ((i, item) in group.withIndex()) if (i > 0) item.key = item.id + "_" + (i + 1)
            }
        }
    }

    /** Does another item of the list share this one's English name (its key then ends in its id)? */
    @JvmStatic
    fun sharesName(item: Item) = item.key != keyOf(item.english)

    private fun titleOf(game: InGameState, bp: Blueprint): Pair<String, String> {
        val en = english(bp.title, game.translator)?.takeIf { it.isNotBlank() } ?: bp.name
        val local = bp.title?.let { game.translator[it] }?.takeIf { it.isNotBlank() && !it.startsWith("Could not find") } ?: en
        return Pair(en, local)
    }

    private fun <T : Blueprint> blueprints(game: InGameState, type: Class<T>): List<Item> {
        val hidden: Set<String> = if (HS.active && type == AugmentBlueprint::class.java)
            (game.eventManager.hs?.hiddenAugNames().orEmpty() + xyz.znix.xftl.hs.ship.HsShips.hiddenAugNames()) else emptySet()
        return game.blueprintManager.blueprints.values.filterIsInstance(type)
            // drone crew races and other internal crew without a title stay out: CREW makes nothing of them
            .filter { it !is CrewBlueprint || it.title != null }
            .map { bp ->
                val (en, local) = titleOf(game, bp)
                val tags = ArrayList<String>()
                // LootPool and the stores skip a blueprint without a rarity or with rarity 0: only events give it
                if ((bp.rarity ?: 0) == 0) tags += TAG_EVENT_ONLY
                if (bp.name in hidden) tags += TAG_HIDDEN
                if (!hasArt(game, bp)) tags += TAG_NO_ART
                val aliases = listOfNotNull(english(bp.short, game.translator), bp.short?.let { game.translator[it] })
                Item(kindOf(bp), bp.name, en, local, tags, bp, aliases = aliases)
            }
            .sortedWith(compareBy({ it.english.lowercase(Locale.ROOT) }, { it.id }))
    }

    private fun kindOf(bp: Blueprint) = when (bp) {
        is AbstractWeaponBlueprint -> Kind.WEAPON
        is DroneBlueprint -> Kind.DRONE
        is AugmentBlueprint -> Kind.AUGMENT
        is CrewBlueprint -> Kind.CREW
        else -> Kind.SYSTEM
    }

    private fun systems(game: InGameState): List<Item> {
        val ship = game.player
        return game.blueprintManager.blueprints.values.filterIsInstance<SystemBlueprint>()
            .filter { xyz.znix.xftl.hs.systems.HsSystems.nameToId(it.name) in 0..15 || HS.active && xyz.znix.xftl.hs.systems.HsSystems.nameToId(it.name) >= 0 }
            .map { bp ->
                val (en, local) = titleOf(game, bp)
                val tags = ArrayList<String>()
                if (ship != null && ship.systems.any { it.codename == bp.name }) tags += TAG_INSTALLED
                // vanilla AddSystem needs the room the layout has for it (HsConsole.addSystem)
                else if (ship != null && !HS.active && ship.systemSlots.none { it.system.name == bp.name }) tags += TAG_NO_ROOM
                Item(Kind.SYSTEM, bp.name, en, local, tags, bp)
            }
    }

    /** The hangar's families: vanilla's, under Hyperspace in the hangar's order with the mod's entries (HsHangar). */
    @JvmStatic
    fun families(game: InGameState): List<ShipFamily> {
        val vanilla = game.content.shipFamilies
        val table = if (HS.active) xyz.znix.xftl.hs.ship.HsHangar.familyTable(vanilla, xyz.znix.xftl.hangar.SelectShipState.NATIVE_ORDER) else vanilla
        return if (HS.active) table.families
        else table.families.sortedBy { xyz.znix.xftl.hangar.SelectShipState.NATIVE_ORDER.indexOf(it.ships.first()).let { i -> if (i < 0) 99 else i } }
    }

    /** Every layout as `<the ship's name>_A/_B/_C` (the name of its type A layout, as the hangar calls the ship). */
    private fun layouts(game: InGameState): List<Item> {
        val profile = game.mainGame?.profile
        val out = ArrayList<Item>()
        for (family in families(game)) {
            val first = game.blueprintManager.getOrNull(family.ships.first()) as? LazyShipBlueprint ?: continue
            val baseEn = english(first.shipTitle, game.translator)?.takeIf { it.isNotBlank() } ?: first.name
            val baseLocal = first.shipTitle?.let { game.translator[it] }?.takeIf { it.isNotBlank() } ?: baseEn
            val classEn = english(first.shipClass, game.translator)
            for ((i, id) in family.ships.withIndex()) {
                if (i > 2) break
                val bp = game.blueprintManager.getOrNull(id) as? LazyShipBlueprint ?: continue
                if (HS.active && !xyz.znix.xftl.hs.ship.HsHangar.variantExists(id)) continue
                val letter = ('A' + i).toString()
                val tags = ArrayList<String>()
                tags += if (profile?.isShipUnlocked(id) ?: true) TAG_UNLOCKED else TAG_LOCKED
                if (!exists(game, "img/customizeUI/miniship_${bp.img}.png")) tags += TAG_NO_ART
                val layoutName = english(bp.shipTitle, game.translator)
                out += Item(Kind.UNLOCK_SHIP, id, baseEn + " " + letter, baseLocal + " " + letter, tags, bp, i, family,
                    image = bp.img, aliases = listOfNotNull(layoutName?.takeIf { i > 0 }, classEn?.let { "$it $letter" }))
            }
        }
        return out
    }

    // ------------------------------------------------------------------------------------------------------ art

    /** Is [path] one of the data's files (no image is decoded)? */
    @JvmStatic
    fun exists(game: InGameState, path: String): Boolean = game.datafile.getOrNull(xyz.znix.xftl.Languages.localImage(game.datafile, path)) != null

    /**
     * Has the game art for [bp], drawn the way the store, equipment and crew boxes draw it? A weapon: its launcher's
     * sheet (or Hyperspace's `<iconReplace>`), not weapons/blank.png (the Federation cruiser's Artillery and Flak
     * Artillery: their art is part of the ship's hull image); a drone: its icon; a crew race: its portrait; a system:
     * its icon. An augment's box is text only, so it always has its art.
     */
    @JvmStatic
    fun hasArt(game: InGameState, bp: Blueprint): Boolean = when (bp) {
        is AbstractWeaponBlueprint -> {
            val replace = bp.hs?.iconReplace?.takeIf { it.isNotEmpty() }?.let { game.animations.getOrNull(it) }
            if (replace != null) exists(game, replace.sheet.sheetPath)
            else {
                val anim = game.animations.weaponAnimations[bp.launcher]
                anim != null && !anim.sheet.sheetPath.endsWith("/blank.png") && exists(game, anim.sheet.sheetPath)
            }
        }
        is DroneBlueprint -> when (bp.type) {
            DroneBlueprint.DroneType.REPAIR, DroneBlueprint.DroneType.BATTLE, DroneBlueprint.DroneType.BOARDER -> true
            else -> bp.name == "DRONE_HACKING" || bp.droneImage != null && exists(game, "img/ship/drones/${bp.droneImage}_charged.png")
        }
        is CrewBlueprint -> {
            // LivingCrewInfo's portrait: the race's, under Hyperspace else its <animSheet> race's, else that sheet
            val sheetRace = if (!HS.active) bp.name else xyz.znix.xftl.hs.crew.CustomCrewManager.definitionOrNull(bp.name)
                ?.animSheet?.getOrNull(1)?.ifEmpty { null } ?: bp.name
            val portrait = game.animations.getOrNull("${bp.name}_portrait") ?: if (HS.active) game.animations.getOrNull("${sheetRace}_portrait") else null
            val sheet = portrait?.sheet?.sheetPath ?: if (HS.active) game.animations.sheet(sheetRace)?.sheetPath else null
            sheet != null && exists(game, sheet)
        }
        is SystemBlueprint -> exists(game, bp.onIconPath)
        else -> true
    }

    private fun achievements(game: InGameState): List<Item> {
        if (HS.active) {
            // Hyperspace's ACH is CustomAchievementTracker::SetAchievement: the mod's own achievements
            return xyz.znix.xftl.hs.ship.HsAchievements.customAchievements.values.map { a ->
                val en = english(a.name, game.translator)?.takeIf { it.isNotBlank() } ?: a.nameId
                val local = a.name?.let { game.translator[it] }?.takeIf { it.isNotBlank() } ?: en
                val tags = ArrayList<String>()
                tags += if (xyz.znix.xftl.hs.ship.HsAchievements.getAchievementStatus(a.nameId) >= 0) TAG_UNLOCKED else TAG_LOCKED
                if (a.hidden) tags += TAG_HIDDEN
                // CAchievement::icon is achievements/<icon>.png already (HsAchievements)
                val image = a.icon.takeIf { it.isNotEmpty() }?.let { "img/$it" }
                if (image == null || !exists(game, image)) tags += TAG_NO_ART
                Item(Kind.ACHIEVEMENT, a.nameId, en, local, tags, image = image)
            }
        }
        val profile = game.mainGame?.profile
        return game.content.achievements.achievements.values.sortedBy { it.id }.map { a ->
            val en = english(a.name, game.translator)?.takeIf { it.isNotBlank() } ?: a.id
            val unlocked = profile?.getAchievement(a) != null
            val tags = listOf(if (unlocked) TAG_UNLOCKED else TAG_LOCKED) + if (!exists(game, a.img)) listOf(TAG_NO_ART) else emptyList()
            Item(Kind.ACHIEVEMENT, a.id, en, game.translator[a.name], tags, image = a.img)
        }
    }

    // ------------------------------------------------------------------------------------------------ resolving

    /** What a typed name means: one item, several (ambiguous, or a wildcard), or none. */
    class Match(val items: List<Item>, val exact: Boolean, val wildcard: Boolean, val named: Boolean = false) {
        val one: Item? get() = if (items.size == 1 && (exact || !wildcard)) items[0] else null

        /**
         * The item a whole name means when several share it ([named]): the only one a store or reward can give (not
         * event only), so `get weapon Fire_Beam` is the Fire Beam and not the drone's weapon of that name; null when
         * that is not one item (both Artemis Missiles are event only: their keys tell them apart).
         */
        val preferred: Item? get() = one ?: if (named) items.filter { TAG_EVENT_ONLY !in it.tags }.singleOrNull() else null
    }

    /**
     * Resolves [query] in [items]: the key, the internal id, the English or current-language name (underscores or
     * spaces, any case), an alias; then a name with every word of the query as a whole word; then a name that holds every word. With [wildcards], `*` matches any
     * run of characters in the key, id or names; a lone `*` is every item.
     */
    @JvmStatic
    fun resolve(items: List<Item>, query: String, wildcards: Boolean): Match {
        val q = norm(query)
        if (q.isEmpty()) return Match(emptyList(), false, false)
        if (wildcards && '*' in q) {
            return Match(items.filter { item -> names(item).any { globMatches(q, it) } }, false, true)
        }
        items.filter { norm(it.key) == q }.let { if (it.size == 1) return Match(it, true, false) }
        items.filter { it.id.lowercase(Locale.ROOT) == q }.let { if (it.isNotEmpty()) return Match(it, true, false) }
        items.filter { item -> names(item).any { it == q } }.let { if (it.isNotEmpty()) return Match(it, it.size == 1, false, named = true) }
        val words = q.split('_').filter { it.isNotEmpty() }
        // every word of the query a whole word of a name ("kestrel c" is The_Kestrel_C, not Kestrel_Cruiser_A)
        items.filter { item -> names(item).any { n -> val nw = n.split('_'); words.all { it in nw } } }.let { if (it.isNotEmpty()) return Match(it, false, false) }
        val some = items.filter { item -> names(item).any { n -> words.all { w -> w in n } } }
        return Match(some, false, false)
    }

    private fun names(item: Item): List<String> =
        (listOf(item.key, item.id, item.english, item.local) + item.aliases).map(::norm)
}
