package xyz.znix.xftl.devutil

import xyz.znix.xftl.devutil.ConsoleItems.Item
import xyz.znix.xftl.devutil.ConsoleItems.Kind
import xyz.znix.xftl.Blueprint
import xyz.znix.xftl.Ship
import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.render.HsConsole

/**
 * The `get` and `unlock` commands (docs/CONSOLE.md, "get and unlock"): a friendlier front for existing commands, by
 * in-game English name. Each runs the Hyperspace or original command the item kind names ([Kind.path]) through
 * [HsConsole.run], as if that line had been typed, then says what changed. A per-layout unlock is the profile's own
 * layout unlock, the call CustomShipUnlocks::UnlockAllShips makes for each vanilla layout (a Hyperspace mod's own layout:
 * SHIP_CUSTOM). With no name the picker opens: the input line keeps the command, and its completer is the picker.
 *
 * Every get says what it gave and where it went ("Granted: Burst Laser Mark II - in the cargo"). With no room an item
 * goes where an event's reward goes (WorldManager::ModifyResources): the equipment screen's over-capacity box, which
 * the equipment screen opens on; crew past the limit wait in the crew screen for one to be dismissed. While such a box
 * is taken another get of that kind is refused ([refusal]), as the original never fills two at once.
 */
object ConsoleCheats {
    const val GET = "get"
    const val UNLOCK = "unlock"

    /** The suggested amounts of the resource kinds (`get scrap <amount>`). */
    @JvmField val AMOUNTS = listOf("10", "50", "100", "500", "1000")

    @JvmStatic
    fun kinds(command: String) = if (command.equals(UNLOCK, true)) ConsoleItems.UNLOCK_KINDS else ConsoleItems.GET_KINDS

    @JvmStatic
    fun kindOf(command: String, word: String): Kind? = kinds(command).firstOrNull { it.word.equals(word, true) }
        ?: if (command.equals(GET, true) && word.equals("drones", true)) Kind.DRONE_PARTS else null

    /** Runs `get ...` or `unlock ...` (the whole line). */
    @JvmStatic
    fun run(console: DebugConsole, line: String) {
        val parts = line.trim().split(' ').filter { it.isNotEmpty() }
        val command = parts[0].lowercase()
        val kinds = kinds(command)
        if (parts.size < 2) {
            console.addLine("$command what? ${kinds.joinToString(", ") { it.word }}. Pick one below, or see 'help $command'.")
            console.input = "$command "
            return
        }
        val kind = kindOf(command, parts[1])
        if (kind == null && command == GET && parts[1].equals("ship", true)) {
            console.addLine("get ship is gone: unlock the ship (unlock ship <name>) and start a new run with it. SWITCH and SWITCHALL still switch (Hyperspace).")
            return
        }
        if (kind == null) {
            console.addLine("$command: no such kind '${parts[1]}'. Kinds: ${kinds.joinToString(", ") { it.word }}")
            return
        }
        if (kind.needsHs && !HS.active) {
            console.addLine("$command ${kind.word} uses ${kind.path}. " + ConsoleRegistry.needsHsMessage(kind.path))
            return
        }
        val rest = parts.drop(2).joinToString("_")
        if (kind.amount) return amount(console, command, kind, rest)
        if (rest.isEmpty()) {
            console.addLine("Pick from the ${kind.title.lowercase()} below, or type a name: $command ${kind.word} <name>")
            console.input = "$command ${kind.word} "
            return
        }
        // the brief's first spelling: unlock ship all = unlock ship *
        if (kind == Kind.UNLOCK_SHIP && (rest == "*" || rest.equals("all", true))) return unlockAll(console)
        val wild = ConsoleItems.wildcardAllowed(kind)
        if (!wild && '*' in rest) {
            console.addLine("$command ${kind.word} takes one name: '*' would fill the ship with every one at once.")
            return
        }
        val items = ConsoleItems.items(console.game, kind)
        val match = ConsoleItems.resolve(items, rest, wild)
        val one = match.preferred
        when {
            match.items.isEmpty() -> console.addLine("No ${kind.word} named '$rest'. Pick one below.")
            match.wildcard -> many(console, command, kind, rest, match.items)
            one != null -> {
                // a name several items share: the one a store or reward gives, the others by their keys (before the
                // answer, so the answer stays the last line, the one the touch console shows)
                if (match.items.size > 1) console.addLine("'$rest' is ${one.key} here; also " + (match.items - one).take(4).joinToString(", ") { it.key })
                apply(console, command, kind, one)
            }
            else -> {
                console.addLine("'$rest' could be ${match.items.size} ${kind.title.lowercase()}: " +
                    match.items.take(6).joinToString(", ") { it.key } + if (match.items.size > 6) ", ..." else "")
                console.input = "$command ${kind.word} $rest"
                return
            }
        }
        if (match.items.isEmpty()) console.input = "$command ${kind.word} $rest"
    }

    private fun amount(console: DebugConsole, command: String, kind: Kind, rest: String) {
        val n = rest.toIntOrNull()
        if (n == null) {
            console.addLine("$command ${kind.word} <amount>, e.g. $command ${kind.word} 100" + if (rest.isEmpty()) "" else " ('$rest' is not a number)")
            console.input = "$command ${kind.word} "
            return
        }
        val ship = console.game.player ?: return
        fun value() = when (kind) {
            Kind.SCRAP -> ship.scrap; Kind.FUEL -> ship.fuelCount; Kind.MISSILES -> ship.missilesCount
            Kind.DRONE_PARTS -> ship.dronesCount; else -> ship.health
        }
        val before = value()
        HsConsole.run(console.game, "${kind.path} $n")
        val after = value()
        val what = kind.title.lowercase()
        console.addLine(when {
            after != before -> "${kind.title}: $before -> $after (${kind.path} $n)"
            kind == Kind.MISSILES && ship.weapons == null -> "Missiles unchanged: MISSILES needs a weapons system"
            kind == Kind.HULL && before >= ship.maxHealth -> "The hull is already at its maximum ($before)"
            else -> "$what unchanged at $before (${kind.path} $n)"
        })
    }

    /** What the player has of an item: weapon and drone slots plus cargo, augments, crew, systems. */
    private fun count(game: InGameState, item: Item): Int {
        val ship = game.player ?: return 0
        val cargo = ship.allCargo().count { it.name == item.id }
        return when (item.kind) {
            Kind.WEAPON -> ship.hardpoints.count { it.weapon?.type?.name == item.id } + cargo
            Kind.DRONE -> (ship.drones?.drones?.count { it?.type?.name == item.id } ?: 0) + cargo
            Kind.AUGMENT -> ship.augments.count { it.name == item.id } + cargo + (ship.hs?.hiddenAugs?.get(item.id) ?: 0)
            Kind.CREW -> game.playerCrew.count { it.info.race.name == item.id }
            Kind.SYSTEM -> ship.systems.count { it.codename == item.id }
            else -> 0
        }
    }

    /** How many of an item sit in its own slots (weapon, drone, augment slots), not the cargo. */
    private fun inSlots(ship: Ship, item: Item): Int = when (item.kind) {
        Kind.WEAPON -> ship.hardpoints.count { it.weapon?.type?.name == item.id }
        Kind.DRONE -> ship.drones?.drones?.count { it?.type?.name == item.id } ?: 0
        Kind.AUGMENT -> ship.augments.count { it.name == item.id }
        else -> 0
    }

    /**
     * What waits in an over-capacity box of the equipment screen: the item box and the augment box
     * (Ship.cargoBlueprints 4 and 5), under Hyperspace's multipleOverCapacity every page.
     */
    @JvmStatic
    fun pending(ship: Ship): List<Blueprint> =
        if (xyz.znix.xftl.hs.ship.HsEquipment.multipleOver(ship)) xyz.znix.xftl.hs.ship.HsEquipment.overItems(ship)
        else listOfNotNull(ship.cargoBlueprints[4], ship.cargoBlueprints[5])

    /** Is there a place for [kind] without an over-capacity box: a free slot of its own, else (not augments) the cargo? */
    private fun hasRoom(ship: Ship, kind: Kind): Boolean = when (kind) {
        Kind.WEAPON -> ship.hardpoints.take(ship.weaponSlots ?: ship.hardpoints.size).any { it.weapon == null } || !ship.cargoFull()
        Kind.DRONE -> ship.drones?.drones?.any { it == null } == true || !ship.cargoFull()
        Kind.AUGMENT -> ship.augments.size < ship.augmentSlotCount
        else -> true
    }

    /**
     * Why a get of [kind] is refused, or null. The original gives one item past capacity at a time: an event's reward
     * with no room goes into the equipment screen's over-capacity box (one for weapons and drones, one for augments),
     * and while that box is taken Equipment::IsCompletelyFull holds, so WorldManager::ModifyResources turns the next
     * reward into scrap (EQUIP_FULL); crew past the limit wait in the crew screen's one extra box. So while
     * [pendingItem] or [pendingAugment] waits and there is no [room], or while the crew is over its limit
     * ([crewOver]), the get is refused with what to do first. Under Hyperspace's multipleOverCapacity
     * ([multiple]) IsCompletelyFull is false for weapons, drones and augments and every item is kept, so they are not.
     */
    @JvmStatic
    fun refusal(kind: Kind, room: Boolean, pendingItem: String?, pendingAugment: String?, multiple: Boolean, crewOver: Boolean): String? = when {
        kind == Kind.CREW && crewOver -> "Crew full: dismiss the extra crew member first (crew screen)"
        room || multiple -> null
        (kind == Kind.WEAPON || kind == Kind.DRONE) && pendingItem != null ->
            "Cargo full: sell or discard the pending $pendingItem first (equipment screen)"
        kind == Kind.AUGMENT && pendingAugment != null ->
            "Augments full: sell or discard the pending $pendingAugment first (equipment screen)"
        else -> null
    }

    private fun title(game: InGameState, bp: Blueprint) = bp.translateTitle(game)

    @JvmStatic
    fun apply(console: DebugConsole, command: String, kind: Kind, item: Item) {
        val game = console.game
        when (kind) {
            Kind.UNLOCK_SHIP -> { unlockLayouts(console, listOf(item)); return }
            Kind.ACHIEVEMENT -> { unlockAchievements(console, listOf(item)); return }
            else -> {}
        }
        val ship = game.player
        if (ship == null) { console.addLine("${item.key} not added: no ship"); return }
        val name = item.local
        val multiple = xyz.znix.xftl.hs.ship.HsEquipment.multipleOver(ship)
        refusal(kind, hasRoom(ship, kind), ship.cargoBlueprints[4]?.let { title(game, it) },
            ship.cargoBlueprints[5]?.let { title(game, it) }, multiple, game.playerHasTooManyCrew())?.let {
            console.addLine("$it; $name not added")
            return
        }
        val before = count(game, item)
        val slotsBefore = inSlots(ship, item)
        val overBefore = pending(ship).count { it.name == item.id }
        val line = if (kind == Kind.SYSTEM) "SYS ${item.id}" else "${kind.path} ${item.id}"
        HsConsole.run(game, line)
        var path = line
        // AUG adds only below three augments and the ship's slots; past that, the path of an event's augment reward
        // (WorldManager::ModifyResources: a free slot, else the over-capacity box)
        val bp = item.blueprint
        if (kind == Kind.AUGMENT && bp is AugmentBlueprint && count(game, item) == before && ship.augments.size >= minOf(3, ship.augmentSlotCount)) {
            ship.addBlueprint(bp, true)
            path = "an event's augment reward"
        }
        val after = count(game, item)
        if (after <= before) {
            console.addLine("$name not added ($path): " + when {
                kind == Kind.AUGMENT && bp is AugmentBlueprint && !bp.stackable && ship.hasAugment(bp.name) ->
                    "the ship has it and it does not stack, so the game pays 25 scrap for it (AUGMENT_FULL)"
                kind == Kind.AUGMENT -> "no free augment slot"
                kind == Kind.SYSTEM && before > 0 -> "the ship has it"
                kind == Kind.SYSTEM -> if (HS.active) "the ship has no room for it, or another system takes its place" else "the layout has no room for it"
                kind == Kind.CREW -> "the ship has no room to put them"
                else -> "no free slot and the cargo is full"
            })
            return
        }
        if (pending(ship).count { it.name == item.id } > overBefore) {
            // the equipment screen opens on the box, as when an event's item has no room (Equipment::Open)
            game.shipUI.hsOpenEquipmentForOverflow()
            val full = if (kind == Kind.AUGMENT) "augments full" else "cargo full"
            console.addLine("Granted: $name - $full: it waits in the equipment screen's over-capacity box" +
                (if (multiple) " (${pending(ship).size} waiting)" else "") + " ($path)")
            return
        }
        if (kind == Kind.CREW && game.playerHasTooManyCrew()) {
            console.addLine("Granted: $name - crew full: dismiss one in the crew screen ($path)")
            return
        }
        val where = when (kind) {
            Kind.WEAPON -> if (inSlots(ship, item) > slotsBefore) "in a weapon slot" else "to the cargo (no free weapon slot)"
            Kind.DRONE -> if (inSlots(ship, item) > slotsBefore) "in a drone slot" else "to the cargo (no free drone slot)"
            Kind.AUGMENT -> "in an augment slot"
            Kind.CREW -> "aboard"
            else -> "installed"
        }
        console.addLine("Granted: $name - $where ($path)")
    }

    private fun many(console: DebugConsole, command: String, kind: Kind, pattern: String, items: List<Item>) {
        when (kind) {
            Kind.UNLOCK_SHIP -> if (pattern == "*" || pattern.equals("all", true)) unlockAll(console) else unlockLayouts(console, items)
            Kind.ACHIEVEMENT -> unlockAchievements(console, items)
            Kind.SYSTEM -> if (pattern == "*") {
                val before = console.game.player?.systems?.size ?: 0
                HsConsole.run(console.game, "SYS ALL")
                console.addLine("Systems: $before -> ${console.game.player?.systems?.size ?: 0} (SYS ALL)")
            } else for (item in items) apply(console, command, kind, item)
            else -> {}
        }
    }

    private fun unlocked(game: InGameState, id: String) = game.mainGame.profile.isShipUnlocked(id)

    /** `unlock ship *`: SHIP ALL (CustomShipUnlocks::UnlockAllShips). */
    @JvmStatic
    fun unlockAll(console: DebugConsole) {
        val game = console.game
        val layouts = ConsoleItems.items(game, Kind.UNLOCK_SHIP)
        val before = layouts.count { unlocked(game, it.id) }
        HsConsole.run(game, "SHIP ALL")
        val after = layouts.count { unlocked(game, it.id) }
        console.addLine("Ships unlocked: $before -> $after of ${layouts.size} layouts (SHIP ALL)" + seededNote(before, after))
    }

    /** One layout: the profile's layout unlock as UnlockAllShips makes it (no popup); a mod's own layout: SHIP_CUSTOM. */
    @JvmStatic
    fun unlockLayouts(console: DebugConsole, items: List<Item>) {
        val game = console.game
        for (item in items) {
            if (unlocked(game, item.id)) { console.addLine("${item.key} is unlocked already"); continue }
            if (HS.active && xyz.znix.xftl.hs.ship.HsHangar.isCustomLayout(item.id)) HsConsole.run(game, "SHIP_CUSTOM ${item.id}")
            else game.mainGame.profile.unlockLayout(item.id, Difficulty.EASY, popup = false)
            console.addLine(if (unlocked(game, item.id)) "Unlocked ${item.key} (${item.id}); the hangar shows it"
                else "${item.key} stays locked" + seededNote(0, 0))
        }
    }

    private fun seededNote(before: Int, after: Int) =
        if (before == after && HS.active && !xyz.znix.xftl.hs.ship.HsShipUnlocks.unlocksAllowed()) " - a seeded run unlocks nothing without <seeds allowUnlocks>" else ""

    @JvmStatic
    fun unlockAchievements(console: DebugConsole, items: List<Item>) {
        val game = console.game
        fun has(item: Item) = if (HS.active) xyz.znix.xftl.hs.ship.HsAchievements.getAchievementStatus(item.id) >= 0
            else game.content.achievements.achievements[item.id]?.let { game.mainGame.profile.getAchievement(it) } != null
        var done = 0
        for (item in items) {
            if (has(item)) { if (items.size == 1) console.addLine("${item.key} is unlocked already"); continue }
            HsConsole.run(game, "ACH ${item.id}")
            if (has(item)) { done++; if (items.size <= 5) console.addLine("Unlocked ${item.key} (ACH ${item.id})") }
            else console.addLine("${item.key} stays locked (ACH ${item.id}: a ship achievement needs its ship, a seeded run needs <seeds allowAchievements>)")
        }
        if (items.size > 5) console.addLine("Unlocked $done of ${items.size} achievements (ACH each)")
    }
}
