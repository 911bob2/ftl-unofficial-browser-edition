package ftl.browser

import org.jdom2.Element
import xyz.znix.xftl.Blueprint
import xyz.znix.xftl.Constants
import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.crew.CrewBlueprint
import xyz.znix.xftl.game.FlagshipBoss
import xyz.znix.xftl.game.JumpWindow
import xyz.znix.xftl.game.LootPool
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.game.ShipEquipmentPanel
import xyz.znix.xftl.game.ShipWindow
import xyz.znix.xftl.game.TouchMode
import xyz.znix.xftl.game.TouchRouter
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sector.Beacon
import xyz.znix.xftl.sector.SectorType
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.sys.Input
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint
import xyz.znix.xftl.weapons.DroneBlueprint

/**
 * Players' reports on 2.1.1 (Android; Multiverse and plain Hyperspace). Every mode (vanilla, F2, F3):
 * - MV101, a sector's rarity list: BlueprintManager::SetRarity (1.6.14 0x60b8e0) stores any value and the random pick
 *   (0x764be0) weighs a blueprint 6 minus its rarity when the rarity is not 0, so a rarity of 6 is listed and never
 *   picked. Every sector of the loaded data builds its pool; Multiverse's hacked Union sector (mantis_free at 6) is
 *   generated and arrived at.
 * - MV103, the over-capacity slot: it is an EquipmentBox in Equipment's box list (drop table 0x5399f0, MouseUp
 *   0x539d40), so the item in it and an owned one change places whichever of the two is carried onto the other; with
 *   the mouse and with a finger (the touch layout: a tap selects, the next press carries), for a weapon, a drone and
 *   an augment.
 * - HS104, the beacon map: StarMap::OnRender draws the hovered beacon's connections in yellow (not the one to the
 *   ship's beacon), and with Hyperspace's showAllConnections (CustomMap.cpp StarMap::DrawConnection) every other
 *   connection in white. The beacon a finger picked is the hovered one, as on the iPad: its connections are drawn.
 * - 108, the map markers: the ship's art is beside its beacon, level with it, so it flies along its circle; the touch
 *   layout's smaller art stands where the 64 x 64 art has its ship, as on the iPad.
 * - A crash log of 2.1.1 (Android, Multiverse): a strip on a sheet whose declared h is not whole frames. ImportAnimations
 *   (1.6.9 0x589de0) and Animation::InitPrimitives (0x59c5f0) count the rows up from the declared bottom edge.
 * - A crash log of 2.2.3 (Android, Multiverse): an event box drawn with neither an event nor a synthetic box stopped
 *   the game in DialogueWindow.drawContent. Such a box closes when drawn, in touch and mouse layouts.
 */
internal object GameplayParityFixContentChecks {
    fun run(main: MainGame, container: GameContainer): String {
        val rng = BrowserRandom.snapshot()
        val nativeRng = NativeRandom.snapshot()
        val results = ArrayList<String>()
        val failures = ArrayList<String>()
        val paths = main.profile.showBeaconPaths
        val moreInfo = xyz.znix.xftl.hs.render.HsOverlay.altMode
        val allNodes = xyz.znix.xftl.hs.sector.HsStarMap.showAllNodeConnections
        val hoverLoc = xyz.znix.xftl.hs.sector.HsStarMap.hoverLoc
        fun case(name: String, block: () -> String) {
            try {
                results += block()
            } catch (e: Throwable) {
                failures += "$name: " + (e.message ?: e::class.simpleName)
            } finally {
                TouchMode.enabled = false
                TouchRouter.route(null, null, TouchRouter.CANCEL, 0, -1, -1, 0L) { _, _, _, _ -> }
                main.profile.showBeaconPaths = paths
                if (BrowserGameplayParityDiagnostics.modded) moreInfo(moreInfo)
                xyz.znix.xftl.hs.sector.HsStarMap.showAllNodeConnections = allNodes
                xyz.znix.xftl.hs.sector.HsStarMap.hoverLoc = hoverLoc
            }
        }
        try {
            case("sectorRarities") { sectorRarities(main, container) }
            case("hackedSector") { hackedSector(main, container) }
            for (touch in listOf(false, true)) for (kind in listOf("weapon", "drone", "augment"))
                case("overCapacitySwap ${if (touch) "touch" else "mouse"} $kind") { overCapacitySwap(main, container, touch, kind) }
            for (touch in listOf(false, true))
                case("pickedBeaconLines ${if (touch) "touch" else "mouse"}") { pickedBeaconLines(main, container, touch) }
            for (touch in listOf(false, true))
                case("mapMarkerPlace ${if (touch) "touch" else "mouse"}") { mapMarkerPlace(main, container, touch) }
            case("partRowSheets") { partRowSheets(main, container) }
            for (touch in listOf(false, true))
                case("emptyEventBox ${if (touch) "touch" else "mouse"}") { emptyEventBox(main, container, touch) }
        } finally {
            BrowserEngine.resetInput()
            BrowserRandom.restore(rng)
            NativeRandom.restore(nativeRng)
        }
        check(failures.isEmpty()) { failures.joinToString(" | ") }
        return "fix-content ok: " + results.joinToString(", ")
    }

    private val graphics: Graphics get() = BrowserEngine.diagnosticGraphics()

    /** Hyperspace's More Info mode, applied to the options as CommandGui::OnLoop applies it. */
    private fun moreInfo(on: Boolean) {
        xyz.znix.xftl.hs.render.HsOverlay.altMode = on
        xyz.znix.xftl.hs.render.HsOverlay.altModeChanged = true
        xyz.znix.xftl.hs.render.HsMoreInfo.onLoop()
    }

    // ---- MV101

    private fun sectorRarities(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val map = state.gameMap
        var lists = 0
        val outside = ArrayList<String>()
        for (name in map.sectorTypeNames()) {
            val type = map.sectorType(name) ?: continue
            if (type.rarityOverrides.isNotEmpty()) lists++
            // The pool of a sector with a rarity outside 0..5 could not be made ("Invalid rarity").
            val pool = LootPool(state.blueprintManager, type)
            for ((bp, rarity) in type.rarityOverrides) {
                if (rarity !in 0..5) outside += "$name/$bp=$rarity"
                // (Vanilla without Advanced Edition answers AE names by lookup only: those are in no pool.)
                val item = state.blueprintManager.blueprints[bp] as? Blueprint ?: continue
                check(pool.rarityOf(item) == rarity) { "$name: $bp has the rarity ${pool.rarityOf(item)} in the pool, the sector's list says $rarity" }
                check((item in pool.pool) == (rarity != 0)) { "$name: $bp at rarity $rarity is ${if (item in pool.pool) "in" else "out of"} the pick list" }
            }
        }
        // Any data set: a sector that gives a crew race the rarity 6. The race stays in the list and is never drawn.
        val base = LootPool(state.blueprintManager, null)
        val race = base.pool.filterIsInstance<CrewBlueprint>().firstOrNull() ?: error("no crew race in the pool")
        val e = Element("sectorDescription").setAttribute("name", "FIX_CONTENT_RARITY").setAttribute("unique", "false")
        e.addContent(Element("nameList").addContent(Element("name").setText("Rarity six")))
        e.addContent(Element("trackList"))
        e.addContent(Element("rarityList").addContent(Element("blueprint").setAttribute("name", race.name).setAttribute("rarity", "6")))
        state.eventCreator.setSectorRarity(SectorType(state.eventManager, e))
        val pool = state.lootPool
        check(race in pool.pool && pool.rarityOf(race) == 6) { "${race.name} at rarity 6: in the list ${race in pool.pool}, rarity ${pool.rarityOf(race)}" }
        val others = pool.pool.filterIsInstance<CrewBlueprint>().count { pool.rarityOf(it) in 1..5 }
        if (others > 0) repeat(3000) {
            val got = pool.getCrewOrRandom(NativeRandom, "random")
            check(got !== race) { "${race.name} was drawn at rarity 6 (weight 0) on draw $it" }
        }
        return "rarity lists of ${map.sectorTypeNames().size} sectors ($lists with a list; outside 0..5: ${if (outside.isEmpty()) "none" else outside.joinToString(" ")})"
    }

    /** Multiverse's SECTOR_HACKER_UNIQUE (the report's sector): generated, arrived at, its map and a store drawn. */
    private fun hackedSector(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val type = state.gameMap.sectorType("SECTOR_HACKER_UNIQUE") ?: return "hacked sector: not in this data"
        check(type.rarityOverrides["mantis_free"] == 6) { "mantis_free in SECTOR_HACKER_UNIQUE: ${type.rarityOverrides["mantis_free"]}" }
        val info = state.gameMap.sectors[1].first()
        val before = info.type
        info.type = type
        try {
            val sector = state.gameMap.generateSector(info, state)
            check(sector.beacons.size > 5 && sector.type === type) { "the hacked sector has ${sector.beacons.size} beacons" }
            state.setCurrentBeacon(sector.startBeacon)
            repeat(10) { main.update(container, 0.05f) }
            main.render(container, graphics)
            val mantis = state.blueprintManager.getOrNull("mantis_free") as? CrewBlueprint ?: error("no mantis_free blueprint")
            check(mantis in state.lootPool.pool && state.lootPool.rarityOf(mantis) == 6) { "mantis_free in the sector's pool: rarity ${state.lootPool.rarityOf(mantis)}" }
            repeat(2000) { check(state.lootPool.getCrewOrRandom(NativeRandom, "random") !== mantis) { "mantis_free drawn at rarity 6" } }
            BrowserDiagnostics.closeBoxes(main, container)
            JumpWindow(state, {}, {}).draw(graphics)
            return "hacked Union sector: ${sector.beacons.size} beacons, ${type.names.size} names, mantis_free never drawn"
        } finally {
            info.type = before
        }
    }

    // ---- crash log of 2.1.1 (Android): a strip on a sheet that is not whole frames

    /**
     * Every strip of the loaded data whose sheet's declared h is not whole frames (Multiverse's drone_vamp_foreman: 242 of
     * 35 px frames) gives all its frames from its real image, each where the original's rows from the declared bottom
     * edge put it (Animations.SpriteSheetSpec.getSprite). The Foreman's walk down, a row above the sheet's whole rows,
     * ended the run ("Failed requirement"); its other strips showed the row above their own.
     */
    private fun partRowSheets(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val strips = state.animations.animations.values.filter { it.sheet.frameHeight > 0 && it.sheet.sheetHeight % it.sheet.frameHeight != 0 }
        for (spec in strips) {
            val sheet = spec.sheet
            val image = state.getImg(sheet.sheetPath)
            val top = sheet.sheetHeight - (sheet.verticalCount - spec.y) * sheet.frameHeight
            for (i in 0 until spec.length) {
                val frame = spec.spriteAt(state, i)
                check(frame.textureOffsetY - image.textureOffsetY == maxOf(top, 0)) { "${spec.name} frame $i: row at ${frame.textureOffsetY - image.textureOffsetY}, the original's at $top" }
                check(frame.height == minOf(sheet.frameHeight - (maxOf(top, 0) - top), image.height)) { "${spec.name} frame $i: ${frame.height} px high" }
            }
        }
        // The report's strip as a crew member walks it (AbstractCrew: crewStrip(..._walk_down).startLooping builds every frame).
        val walk = state.animations.getOrNull("drone_vamp_foreman_walk_down")
        if (walk != null) check(walk.startLooping(state).sprites.size == walk.length) { "the Foreman's walk down" }
        return "part-row sheets: ${strips.map { it.sheet.sheetPath }.distinct().size} sheets, ${strips.size} strips" +
            (if (walk != null) ", the Foreman walks down" else "")
    }

    /** An event box with neither an event nor a synthetic box (the event-less constructor alone) closes when drawn. */
    private fun emptyEventBox(main: MainGame, container: GameContainer, touch: Boolean): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        TouchMode.enabled = touch
        var closed = 0
        val box = xyz.znix.xftl.game.DialogueWindow(state, state.player, null as xyz.znix.xftl.sector.CreatedEvent?) { closed++ }
        check(closed == 0) { "the event-less box closed before it was drawn" }
        box.draw(graphics)
        check(closed == 1) { "the empty box closed $closed times when drawn" }
        return "empty event box (${if (touch) "touch" else "mouse"}): closed when drawn"
    }

    // ---- MV103

    /** A pointer on the open ship window: the mouse, or a finger through the touch router as the Android host sends it. */
    private class Pointer(val window: () -> ShipWindow, val touch: Boolean) {
        private val mouse = TouchRouter.Mouse { x, y, type, _ ->
            when (type) {
                0 -> window().updateUI(x, y)
                1 -> window().mouseClick(Input.MOUSE_LEFT_BUTTON, x, y)
                else -> window().mouseReleased(Input.MOUSE_LEFT_BUTTON, x, y)
            }
        }
        private val targets = TouchRouter.LiftTargets { x, y -> window().liftTarget(x, y) }
        private fun settle() { var n = 0; while (TouchRouter.afterStep() && n++ < 4) Unit }
        private fun screen(p: ConstPoint) = window().diagnosticScreenPoint(p.x, p.y)

        fun down(at: ConstPoint) {
            val p = screen(at)
            if (touch) TouchRouter.route(null, targets, TouchRouter.DOWN, 0, p.x, p.y, 0L, mouse)
            else { mouse.event(p.x, p.y, 0, 0); mouse.event(p.x, p.y, 1, 0) }
            if (touch) settle()
        }

        fun move(at: ConstPoint) {
            val p = screen(at)
            if (touch) TouchRouter.route(null, targets, TouchRouter.MOVE, 0, p.x, p.y, 0L, mouse) else mouse.event(p.x, p.y, 0, 0)
        }

        fun up(at: ConstPoint) {
            val p = screen(at)
            if (touch) TouchRouter.route(null, targets, TouchRouter.UP, 0, p.x, p.y, 0L, mouse) else mouse.event(p.x, p.y, 2, 0)
            if (touch) settle()
        }

        /** Carry what is at [from] to [to]: the mouse drags; a finger taps the box first, then presses it again and drags. */
        fun carry(from: ConstPoint, to: ConstPoint) {
            if (touch) { down(from); up(from) }
            down(from)
            for (i in 1..8) move(ConstPoint(from.x + (to.x - from.x) * i / 8, from.y + (to.y - from.y) * i / 8))
            up(to)
        }
    }

    /** Where an item box is held and dropped on: the point the touch layout carries an item by. */
    private fun grab(box: ShipEquipmentPanel.DiagnosticItemBox) =
        if (box.kind == "augment" || box.item is AugmentBlueprint || box.w > 200) ConstPoint(box.x + 117, box.y + 20) else ConstPoint(box.x + 50, box.y + 33)

    private fun overCapacitySwap(main: MainGame, container: GameContainer, touch: Boolean, kind: String): String {
        TouchMode.enabled = touch
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val ship = state.player
        val pool = LootPool(state.blueprintManager, null).pool
        val overSlot = if (kind == "augment") 5 else 4
        var owned: Blueprint? = null
        val fresh: Blueprint
        when (kind) {
            "weapon" -> {
                val weapons = pool.filterIsInstance<AbstractWeaponBlueprint>()
                check(weapons.size >= 2) { "fewer than two weapons in the pool" }
                val item = weapons[0]
                owned = item; fresh = weapons[1]
                var n = 0
                while (n++ < 60 && ship.addBlueprint(item, false)) Unit
            }
            "drone" -> {
                val drones = pool.filterIsInstance<DroneBlueprint>()
                check(drones.size >= 2) { "fewer than two drones in the pool" }
                val item = drones[0]
                owned = item; fresh = drones[1]
                var n = 0
                while (n++ < 60 && ship.addBlueprint(item, false)) Unit
            }
            else -> {
                val augments = pool.filterIsInstance<AugmentBlueprint>().filter { a -> ship.augments.none { it.name == a.name } }
                val spare = ArrayList<AugmentBlueprint>()
                for (a in augments) {
                    if (ship.augments.size >= ship.augmentSlotCount) { spare += a; continue }
                    ship.addBlueprint(a, false)
                    BrowserDiagnostics.closeBoxes(main, container)
                }
                check(ship.augments.isNotEmpty()) { "the ship holds no augment" }
                var over: AugmentBlueprint? = null
                for (a in spare) {
                    ship.addBlueprint(a, true)
                    BrowserDiagnostics.closeBoxes(main, container)
                    if (ship.cargoBlueprints[5] === a) { over = a; break }
                }
                fresh = over ?: error("no augment went to the over-capacity box (${spare.size} tried, ${ship.augments.size} of ${ship.augmentSlotCount} slots)")
            }
        }
        if (kind != "augment") {
            check(ship.cargoFull()) { "the cargo did not fill with ${owned?.name}" }
            ship.addBlueprint(fresh, true)
            BrowserDiagnostics.closeBoxes(main, container)
        }
        check(ship.cargoBlueprints[overSlot] === fresh) { "${fresh.name} is not in the over-capacity box: ${ship.cargoBlueprints[overSlot]?.name}" }

        state.shipUI.hsOpenEquipmentForOverflow()
        fun window() = state.shipUI.diagnosticOpenShipWindow ?: error("the equipment screen is not open")
        main.render(container, graphics)
        window().diagnosticRelayout(graphics)
        val pointer = Pointer(::window, touch)

        fun boxes() = window().diagnosticItemBoxes()
        fun over(box: ShipEquipmentPanel.DiagnosticItemBox) = box.kind == "cargo" && box.index in 4..5
        // The owned augment: one in a box on the screen that may be moved (Hyperspace locks some).
        if (kind == "augment") owned = boxes().firstOrNull { it.kind == "augment" && it.item != null &&
            !(xyz.znix.xftl.hs.HS.active && xyz.znix.xftl.hs.ship.HsAugments.isLocked(it.item?.name ?: "")) }?.item ?: error("no augment box holds a movable augment")
        val mine = owned ?: error("no owned $kind")
        fun overBox() = boxes().firstOrNull { it.kind == "cargo" && it.index == overSlot } ?: error("no over-capacity slot on the screen: ${boxes().map { it.kind + it.index }}")
        // The owned item's box: a weapon box (a cargo box for the drone, which needs no drone system), the augment's box.
        fun ownedBox(item: Blueprint) = boxes().firstOrNull { it.item === item && !over(it) && it.kind == when (kind) { "weapon" -> "weapon"; "drone" -> "cargo"; else -> "augment" } }
            ?: error("no ${kind} box holds ${item.name}: ${boxes().map { it.kind + it.index + "=" + it.item?.name }}")
        fun holds(item: Blueprint): Boolean = when (kind) {
            "weapon" -> ship.hardpoints.any { it.weapon?.type === item } || (0 until ship.cargoSlotCount).any { ship.cargoAt(it) === item }
            "drone" -> (0 until ship.cargoSlotCount).any { ship.cargoAt(it) === item } || ship.drones?.drones?.any { it?.type === item } == true
            else -> ship.augments.any { it === item }
        }
        val where = if (touch) "touch" else "mouse"

        // 1. The new item carried onto an owned one: the two change places.
        val target = ownedBox(mine)
        val ownedBefore = boxes().count { it.item === mine }
        pointer.carry(grab(overBox()), grab(target))
        window().draw(graphics)
        check(ship.cargoBlueprints[overSlot] === mine && holds(fresh)) {
            "$where, $kind: ${fresh.name} carried from the over-capacity box onto ${mine.name}: the box holds ${ship.cargoBlueprints[overSlot]?.name}, the ship ${if (holds(fresh)) "has" else "has not"} taken ${fresh.name}"
        }
        check(boxes().count { it.item === mine } == ownedBefore) { "$where, $kind: ${mine.name} boxes changed from $ownedBefore to ${boxes().count { it.item === mine }}" }

        // 2. An owned item carried onto the over-capacity slot: they change places again.
        val back = boxes().firstOrNull { it.item === fresh && !over(it) } ?: error("$where, $kind: no box shows ${fresh.name}")
        pointer.carry(grab(back), grab(overBox()))
        window().draw(graphics)
        check(ship.cargoBlueprints[overSlot] === fresh && !holds(fresh)) {
            "$where, $kind: ${fresh.name} carried from its box onto the over-capacity slot: the slot holds ${ship.cargoBlueprints[overSlot]?.name}"
        }
        state.shipUI.diagnosticCloseWindow()
        return "over-capacity $kind swapped both ways ($where)"
    }

    // ---- HS104

    private fun rgb(c: Colour) = "${(c.r * 255).toInt()},${(c.g * 255).toInt()},${(c.b * 255).toInt()}"

    private fun pickedBeaconLines(main: MainGame, container: GameContainer, touch: Boolean): String {
        TouchMode.enabled = touch
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        main.profile.showBeaconPaths = true
        val sector = state.currentBeacon.sector
        val here = state.currentBeacon
        val index = { b: Beacon -> sector.beacons.indexOf(b) }
        // A beacon away from the ship's with connections of its own, and a neighbour of the ship's beacon.
        val far = sector.beacons.firstOrNull { it !== here && it !in here.neighbours && it.neighbours.size >= 2 } ?: error("no beacon two jumps away")
        val near = here.neighbours.firstOrNull { n -> n.neighbours.any { it !== here } } ?: error("no neighbour with another connection")
        val yellow = rgb(Constants.BEACON_LINE_HOVER)
        val green = rgb(Constants.BEACON_LINE_PLAYER)
        val white = rgb(Colour.white)
        val where = if (touch) "touch" else "mouse"
        val window = JumpWindow(state, {}, {})

        fun drawn(pick: Beacon): List<String> {
            val lines = ArrayList<String>()
            window.browserLines = lines
            window.hovered = pick
            window.draw(graphics)
            window.browserLines = null
            return lines
        }
        fun pair(line: String): Set<Int> = line.substringBefore(':').split('-').map { it.toInt() }.toSet()

        for (pick in listOf(far, near)) {
            val lines = drawn(pick)
            for (n in pick.neighbours) {
                val got = lines.filter { pair(it) == setOf(index(pick), index(n)) }
                if (n === here) check(got.isNotEmpty() && got.all { it.endsWith(":$green") }) { "$where: the connection from the ship's beacon to the picked one: $got (StarMap::OnRender draws it with the ship's own)" }
                else check(got.size == 1 && got[0].endsWith(":$yellow")) { "$where: the picked beacon's connection ${index(pick)}-${index(n)}: $got, the hovered beacon's are yellow" }
            }
            for (n in here.neighbours) check(lines.any { pair(it) == setOf(index(here), index(n)) && it.endsWith(":$green") }) { "$where: the ship's connection to ${index(n)} is not drawn" }
            check(lines.none { it.endsWith(":$yellow") && index(pick) !in pair(it) }) { "$where: yellow lines away from the picked beacon: $lines" }
        }
        if (!BrowserGameplayParityDiagnostics.modded) return "picked beacon's connections drawn ($where)"

        // Hyperspace, every connection shown (More Info, or the map's grid button under splitAllNodeView): each
        // connection of the sector is on the map, the picked beacon's among them.
        moreInfo(true)
        xyz.znix.xftl.hs.sector.HsStarMap.showAllNodeConnections = true
        check(xyz.znix.xftl.hs.sector.HsStarMap.showAllConnections()) { "showAllConnections is off" }
        val all = HashSet<Set<Int>>()
        for (a in sector.beacons) for (b in a.neighbours) all += setOf(index(a), index(b))
        for (pick in listOf(far, near)) {
            val lines = drawn(pick)
            val shown = lines.map { pair(it) }.toSet()
            val missing = all - shown
            check(missing.isEmpty()) { "$where, all connections shown, beacon ${index(pick)} picked: ${missing.size} of ${all.size} connections are not drawn: ${missing.take(6)}" }
            for (line in lines) {
                val p = pair(line)
                val want = when { index(here) in p -> green; index(pick) in p -> yellow; else -> white }
                check(line.endsWith(":$want")) { "$where, all connections shown: $line, expected $want" }
            }
        }
        return "picked beacon's connections drawn, with all ${all.size} connections shown too ($where)"
    }

    // ---- 108

    private fun mapMarkerPlace(main: MainGame, container: GameContainer, touch: Boolean): String {
        TouchMode.enabled = touch
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val window = JumpWindow(state, {}, {})
        val markers = ArrayList<String>()
        window.browserMarkers = markers
        window.draw(graphics)
        val at = markers.firstOrNull { it.startsWith("shipAt:") }?.removePrefix("shipAt:")?.split(',')?.map { it.toInt() } ?: error("no ship marker drawn: $markers")
        // The middle of the art from the beacon it turns about: 22 px beside it and level with it (the 64 x 64 art
        // at (-10, -32)); above the beacon it would point away from it instead of along its circle.
        fun beside(what: String, x: Int, y: Int, w: Int, h: Int) {
            val cx = x + w / 2f
            val cy = y + h / 2f
            check(cy in -1f..1f && cx in 20f..24f) { "$what marker's middle is ($cx, $cy) from its beacon (art $w x $h at ($x, $y)): StarMap has it 22 px beside the beacon, level with it" }
        }
        beside("the ship", at[0], at[1], at[2], at[3])
        if (!touch) check(at[0] == -10 && at[1] == -32) { "the desktop ship marker is at $at, StarMap's constructor puts its 64 x 64 art at (-10, -32)" }
        val sector = state.currentBeacon.sector
        val boss = FlagshipBoss(sector, state, state.currentBeacon.neighbours.first())
        val icon = state.getImgIfExists("img/ipad/map/map_icon_boss.png")?.takeIf { touch } ?: state.getImg("img/map/map_icon_boss.png")
        val off = boss.mapIconOffset()
        beside("the flagship", off.x, off.y, icon.width, icon.height)
        return "map markers beside their beacons (${if (touch) "touch" else "mouse"}: ship ${at[2]} x ${at[3]} at (${at[0]}, ${at[1]}), flagship ${icon.width} x ${icon.height} at (${off.x}, ${off.y}))"
    }
}
