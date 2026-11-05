package xyz.znix.xftl.devutil

import xyz.znix.xftl.weapons.AbstractWeaponBlueprint
import xyz.znix.xftl.Blueprint
import xyz.znix.xftl.Constants
import xyz.znix.xftl.LazyShipBlueprint
import xyz.znix.xftl.crew.CrewBlueprint
import xyz.znix.xftl.crew.LivingCrewInfo
import xyz.znix.xftl.devutil.ConsoleItems.Item
import xyz.znix.xftl.devutil.ConsoleItems.Kind
import xyz.znix.xftl.f
import xyz.znix.xftl.game.ButtonImageSet
import xyz.znix.xftl.game.Buttons
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.Image
import xyz.znix.xftl.systems.SystemBlueprint
import xyz.znix.xftl.weapons.DroneBlueprint
import java.lang.reflect.Parameter

// ---------------------------------------------------------------------------------------- the arguments of get/unlock

/** `get <kind>`, `unlock <kind>`: the kinds, each with what it goes through. */
class CheatKindProcessor(val command: String) : ArgumentTypeProcessor {
    override fun validate(param: Parameter) {}
    override fun process(value: String, console: DebugConsole): Any = value
    override fun getCompleter(debugConsole: DebugConsole, previous: AutoCompleter?): AutoCompleter =
        (previous as? KindCompleter)?.takeIf { it.owner == this } ?: KindCompleter(debugConsole, this, command)
}

/** The name after the kind: the picker of that kind, or the amounts of a resource. */
class CheatNameProcessor(val command: String) : ArgumentTypeProcessor {
    override fun validate(param: Parameter) {}
    override fun process(value: String, console: DebugConsole): Any = value
    override fun getCompleter(debugConsole: DebugConsole, previous: AutoCompleter?): AutoCompleter? {
        val word = debugConsole.input.trimStart().split(' ').getOrNull(1) ?: return null
        val kind = ConsoleCheats.kindOf(command, word) ?: return null
        if (kind.amount) return (previous as? AmountCompleter)?.takeIf { it.owner == this && it.kind == kind } ?: AmountCompleter(debugConsole, this, kind)
        return (previous as? ItemPicker)?.takeIf { it.owner == this && it.kind == kind } ?: ItemPicker(debugConsole, this, command, kind)
    }
}

/** `help [search]`: the command reference. */
object HelpTopicProcessor : ArgumentTypeProcessor {
    override fun validate(param: Parameter) {}
    override fun process(value: String, console: DebugConsole): Any = value
    override fun getCompleter(debugConsole: DebugConsole, previous: AutoCompleter?): AutoCompleter =
        previous as? ConsoleReference ?: ConsoleReference(debugConsole)
}

class KindCompleter(console: DebugConsole, owner: ArgumentTypeProcessor, val command: String) : BasicCompletionEngine<Kind>(console, owner) {
    override val items: List<Kind> = ConsoleCheats.kinds(command)
    override fun getItemName(item: Kind) = item.word
    override fun note(item: Kind) = item.title + " (" + item.path + ")" + if (item.needsHs) ", needs Hyperspace" else ""
    override fun isFeatured(item: Kind) = true
}

class AmountCompleter(console: DebugConsole, owner: ArgumentTypeProcessor, val kind: Kind) : BasicCompletionEngine<String>(console, owner) {
    override val items: List<String> = ConsoleCheats.AMOUNTS
    override fun getItemName(item: String) = item
    override fun note(item: String) = "${kind.path} $item"
}

// ------------------------------------------------------------------------------------------------------- the picker

/**
 * The picker of one item kind: the items with the game's own art (the store's weapon, drone, crew and system boxes,
 * the equipment screen's augment box, the hangar's ship cards, achievement icons), searched by what is typed after the
 * kind. A tap or click runs the command for that item at once and keeps the picker open; Tab fills the best name in.
 */
class ItemPicker(console: DebugConsole, owner: ArgumentTypeProcessor, val command: String, val kind: Kind) :
    BasicCompletionEngine<Item>(console, owner) {
    private companion object {
        const val KEY_LINE_H = 11
        const val TAG_ROW_H = KEY_LINE_H + 6
        /** The art area of a store box: wider or higher weapon art is drawn scaled down to it. */
        const val ART_W = 104f
        const val ART_H = 56f
    }
    private val game get() = console.game
    private var all: List<Item> = ConsoleItems.items(game, kind)
    override val items: List<Item> get() = all

    /** The ship picker's layout tab: -1 every layout, else 0..2 (the hangar's type A/B/C buttons). */
    var layoutTab = -1

    override fun getItemName(item: Item) = item.key
    override fun note(item: Item) = (if (item.local != item.english) item.local + "  " else "") + item.tags.joinToString(", ")

    override fun sortEntries(entries: ArrayList<Item>, currentToken: String) {
        val match = ConsoleItems.resolve(entries.toList(), currentToken, ConsoleItems.wildcardAllowed(kind))
        val keep = match.items.toSet()
        entries.retainAll(keep)
        val q = ConsoleItems.keyOf(currentToken).lowercase()
        // names that start with the search first, then the data order
        entries.sortBy { if (it.key.lowercase().startsWith(q)) 0 else 1 }
    }

    override val contentKey: Any? get() = Pair(lastToken, layoutTab)

    /** Runs the command for [item] and keeps the picker open for the next one. */
    private fun pick(item: Item) {
        val prior = priorCommandText
        console.runKeeping("$prior${item.key}", prior)
        all = ConsoleItems.items(game, kind)
        invalidate()
    }

    private val shown: List<Item> get() =
        if (kind == Kind.UNLOCK_SHIP) entries.filter { layoutTab < 0 || it.layout == layoutTab } else entries

    override fun draw(g: Graphics, ui: ConsoleUi, area: Area, scroll: Int): Int {
        var top = area.y
        // the header: what this is, the layout tabs of the ship pickers, Unlock every ship
        val headerH = if (ui.touch) 52 else 20
        val list = shown
        val title = "${kind.title}: ${list.size}" + (if (list.size != all.size) " of ${all.size}" else "") +
            if (ui.touch && kind == Kind.UNLOCK_SHIP) "" else if (command == ConsoleCheats.UNLOCK) " - tap one to unlock it" else " - tap one to get it"
        ui.font.drawString(area.x.f, ui.baseline(top, headerH).f, title, Colour.white)
        var bx = area.x + ui.font.getWidth(title) + ui.pad * 3
        if (kind == Kind.UNLOCK_SHIP) {
            val tabs = listOf(-1, 0, 1, 2)
            for (t in tabs) {
                val label = if (t < 0) "All" else game.translator["type_" + ('a' + t)].ifBlank { "Type " + ('A' + t) }
                val w = ui.font.getWidth(label) + ui.pad * 4
                ui.button(g, Area(bx, top + 2, w, headerH - 4), label, on = layoutTab == t) { layoutTab = t }
                bx += w + ui.pad
            }
            val label = "Unlock every ship"
            val w = ui.font.getWidth(label) + ui.pad * 4
            ui.button(g, Area(bx + ui.pad * 2, top + 2, w, headerH - 4), label) {
                console.runKeeping("${ConsoleCheats.UNLOCK} ${Kind.UNLOCK_SHIP.word} *", priorCommandText)
                all = ConsoleItems.items(game, kind); invalidate()
            }
        }
        top += headerH + ui.pad
        val gridArea = Area(area.x, top, area.w, area.bottom - top)
        val (tw, artH) = tileSize(ui)
        // a row under each tile for its tags, so they cover no name
        val th = artH + TAG_ROW_H + keyRowH(ui)
        val gap = ui.pad
        val columns = maxOf(1, (gridArea.w + gap) / (tw + gap))
        val rows = (list.size + columns - 1) / columns
        for ((i, item) in list.withIndex()) {
            val x = gridArea.x + (i % columns) * (tw + gap)
            val y = gridArea.y + (i / columns) * (th + gap) - scroll
            // only whole tiles: the console has no clipping
            if (y < gridArea.y) continue
            if (y + th > gridArea.bottom) break
            val tile = Area(x, y, tw, th)
            drawTile(g, ui, item, tile, artH)
            ui.hit(tile) { pick(item) }
        }
        return headerH + ui.pad + rows * (th + gap)
    }

    // ---- tiles

    private val weaponImages by lazy { ButtonImageSet.select2(game, "img/storeUI/store_buy_weapons") }
    private val droneImages by lazy { ButtonImageSet.select2(game, "img/storeUI/store_buy_drones") }
    private val crewImages by lazy { ButtonImageSet.select2(game, "img/storeUI/store_buy_crew") }
    private val systemImages by lazy { ButtonImageSet.select2(game, "img/storeUI/store_systems") }
    private val subsystemImages by lazy { ButtonImageSet.select2(game, "img/storeUI/store_subsystems") }
    private val augmentFont by lazy { game.getFont("JustinFont12Bold") }
    private val crewFont by lazy { game.getFont("JustinFont8") }
    private val labelFont by lazy { game.getFont("JustinFont10") }
    private val keyFont by lazy { game.getFont("JustinFont8") }
    private val buttons = HashMap<String, Tile>()
    private val crewInfo = HashMap<String, LivingCrewInfo>()

    /** The art's size (the box the game draws), without the rows under it. */
    private fun tileSize(ui: ConsoleUi): Pair<Int, Int> = when (kind) {
        Kind.WEAPON -> weaponImages.normal.width to weaponImages.normal.height
        Kind.DRONE -> droneImages.normal.width to droneImages.normal.height
        Kind.CREW -> crewImages.normal.width to crewImages.normal.height + 6
        Kind.SYSTEM -> systemImages.normal.width to systemImages.normal.height
        Kind.AUGMENT -> 235 to 40
        Kind.UNLOCK_SHIP -> game.getImgIfExists("img/customizeUI/ship_list_button_on.png").let { (it?.width ?: 191) to (it?.height ?: 121) }
        else -> 170 to 64 + ui.smallH + 14
    }

    /**
     * The rows under the art: the key (what to type: items that share a name end in their id, so each tile tells them
     * apart), and for ships the local and class name.
     */
    private fun keyRowH(ui: ConsoleUi) = KEY_LINE_H * 2 + 4 + if (kind == Kind.UNLOCK_SHIP) KEY_LINE_H + 2 else 0

    /** The store's boxes draw a blueprint's art and name (Buttons.BlueprintButton, StoreBox::OnRender). */
    private inner class Tile(override val blueprint: Blueprint, images: ButtonImageSet, private val art: Boolean) : Buttons.BlueprintButton(ConstPoint.ZERO, game, images) {
        override val makesHoverNoise: Boolean get() = false

        /** A weapon whose art is larger than the box (a mod's artillery, never sold in a store), drawn scaled to fit. */
        val oversized: Image? = (blueprint as? AbstractWeaponBlueprint)?.takeIf { art && it.hs?.iconReplace.isNullOrEmpty() }
            ?.getLauncher(game)?.getChargedImage(game)?.takeIf { it.height > ART_W || it.width > ART_H }

        // no art: the store box's fallback (the missing-file image) is not drawn, the placeholder is; oversized art
        // is drawn by drawOversized
        override val drawsArt: Boolean get() = art && oversized == null
        override fun click(button: Int) {}
        // the weapons no store sells (a drone's or a boss's) have no short name: their title, cut to the box
        override fun weaponName(blueprint: AbstractWeaponBlueprint): String =
            if (blueprint.short != null) super.weaponName(blueprint) else shortened(blueprint.translateTitle(game))
    }

    private fun shortened(s: String): String {
        if (crewFont.getWidth(s) <= 94) return s
        var n = s.length
        while (n > 0 && crewFont.getWidth(s.substring(0, n) + "..") > 94) n--
        return s.substring(0, n) + ".."
    }

    private fun drawTile(g: Graphics, ui: ConsoleUi, item: Item, a: Area, artH: Int) {
        val over = ui.hovered(a)
        when (kind) {
            Kind.WEAPON, Kind.DRONE, Kind.SYSTEM -> {
                val bp = item.blueprint ?: return
                val images = when {
                    kind == Kind.WEAPON -> weaponImages
                    kind == Kind.DRONE -> droneImages
                    (bp as? SystemBlueprint)?.info?.isSubSystem == true -> subsystemImages
                    else -> systemImages
                }
                val b = buttons.getOrPut(item.id) { Tile(bp, images, ConsoleItems.TAG_NO_ART !in item.tags) }
                b.windowOffset = ConstPoint(a.x, a.y)
                b.update(if (ui.touch) -1 else ui.mouseX, if (ui.touch) -1 else ui.mouseY)
                b.draw(g)
                b.oversized?.let { drawOversized(g, bp as AbstractWeaponBlueprint, it, a) }
                if (ConsoleItems.TAG_NO_ART in item.tags) placeholder(g, ui, bp, a)
            }
            Kind.CREW -> {
                val race = item.blueprint as? CrewBlueprint ?: return
                (if (over) crewImages.hover else crewImages.normal).draw(a.x, a.y)
                // CrewStoreBox: the portrait twice its size, the name under it
                val info = crewInfo.getOrPut(item.id) { LivingCrewInfo(game, race, "", 0) }
                if (ConsoleItems.TAG_NO_ART in item.tags) noArt(ui, a.x + 11, a.y + 20, 96)
                val yOffset = when (race.name) { "rock" -> -6; "mantis", "crystal" -> -10; else -> -5 }
                info.drawPortrait(a.x + 9 + (96 - 70) / 2 + 3, a.y + 9 + yOffset, 2f)
                // and the race walking, as crew walk on the ship (its walk_down strip, in its layer colours)
                val walk = game.animations.crewStrip("${race.name}_walk_down")
                if (!walk.blank && walk.sheet.sheetPath == game.animations.getOrNull("${race.name}_portrait")?.sheet?.sheetPath) {
                    val frame = walk.spriteAt(game, ((ui.time / walk.time.coerceAtLeast(0.05f)).toInt()) % walk.length)
                    info.drawImage(a.right - frame.width - 2f, a.y + 2f, a.right - 2f, a.y + 2f + frame.height, frame, 1f, false)
                }
                crewFont.drawStringCentred(a.x + 11f, a.y + 70f, 96f, item.local, if (over) Constants.STORE_BUY_HOVER else Constants.SECTOR_CUTOUT_TEXT)
            }
            Kind.AUGMENT -> {
                // the equipment screen's augment box (ShipEquipmentPanel)
                g.colour = if (over) Constants.AUGMENT_BOX_OUTLINE_HOVER else Constants.AUGMENT_BOX_OUTLINE
                g.fillRect(a.x.f, a.y.f, 235f, 40f)
                g.colour = Constants.AUGMENT_BOX_INSIDE
                g.fillRect(a.x + 3f, a.y + 3f, 229f, 34f)
                augmentFont.drawStringCentred(a.x.f, a.y + 27f, 235f, item.local, Constants.AUGMENT_NAME_TEXT)
            }
            Kind.UNLOCK_SHIP -> drawShip(g, ui, item, a, over)
            else -> {
                val img = item.image?.let { game.getImgIfExists(it) }
                ui.box(g, a, if (over) Colour(70, 70, 75, 220) else Colour(30, 30, 35, 220), Colour(120, 125, 135, 255))
                if (img != null) {
                    val s = 64f / maxOf(img.width, img.height, 1)
                    img.draw(a.x + (a.w - img.width * s) / 2f, a.y + 6f, a.x + (a.w + img.width * s) / 2f, a.y + 6f + img.height * s,
                        0f, 0f, img.width.f, img.height.f, if (item.unlocked) 1f else 0.45f, Colour.white)
                }
                else if (img == null) noArt(ui, a.x, a.y + 26, a.w)
                labelFont.drawStringCentred(a.x.f, a.y + 64f + 18f, a.w.f, ui.fit(labelFont, item.local, a.w - 6), Colour.white)
            }
        }
        // the key under the art, then the tags in their row under it
        keyRow(item, a.x + 2, a.y + artH + 2, a.w - 4)
        // in the store box's small font, like the key, so a tile's tags fit its width on a phone too
        ui.tags(g, a.x + 2, a.y + artH + keyRowH(ui) + 2, item.tags.filter { it != ConsoleItems.TAG_UNLOCKED || kind == Kind.ACHIEVEMENT }, a.w - 4, keyFont, KEY_LINE_H - 3)
    }

    /**
     * The key under the art, as typed, in the store box's own small font (JustinFont8) on up to two lines, split at
     * its underscores. One that ends in its id (items sharing a name) keeps the id whole on its own line, in white.
     */
    private fun keyRow(item: Item, x: Int, top: Int, w: Int) {
        for ((i, line) in keyLines(item, w).withIndex())
            keyFont.drawString(x.f, top + KEY_LINE_H * (i + 1) - 2f, line.first, line.second)
    }

    private fun keyLines(item: Item, w: Int): List<Pair<String, Colour>> {
        val key = item.key
        if (keyFont.getWidth(key) <= w) return listOf(key to if (ConsoleItems.sharesName(item)) Colour.white else Colour.lightGray)
        val id = item.id
        if (ConsoleItems.sharesName(item) && key.endsWith("_$id") && key != id) {
            // the id is what tells it apart: whole, over both lines when it needs them (the name is on the box)
            if (keyFont.getWidth(id) > w) return wrapKey(id, w).map { it to Colour.white }
            return listOf(fitKey(key.removeSuffix("_$id") + "_", w) to Colour.lightGray, id to Colour.white)
        }
        return wrapKey(key, w).map { it to Colour.lightGray }
    }

    /** [text] on up to two lines of [w] pixels, split at its underscores, the second cut with ".." when too long. */
    private fun wrapKey(text: String, w: Int): List<String> {
        val words = text.split('_')
        var n = 1
        while (n < words.size && keyFont.getWidth(words.take(n + 1).joinToString("_") + "_") <= w) n++
        val first = words.take(n).joinToString("_") + if (n < words.size) "_" else ""
        val rest = words.drop(n).joinToString("_")
        return listOfNotNull(fitKey(first, w), rest.takeIf { it.isNotEmpty() }?.let { fitKey(it, w) })
    }

    private fun fitKey(text: String, w: Int): String {
        if (keyFont.getWidth(text) <= w) return text
        var n = text.length
        while (n > 0 && keyFont.getWidth(text.substring(0, n) + "..") > w) n--
        return text.substring(0, n) + ".."
    }

    /**
     * A weapon or drone with no art of its own: the weapon box's icon of its type (systemUI/weapbox_icon_W_*, the icon
     * the game's weapon and drone boxes show; a weapon's `<iconImage>`, else its kind), with "no art" under it.
     */
    private fun placeholder(g: Graphics, ui: ConsoleUi, bp: Blueprint, a: Area) {
        val icon = when (bp) {
            is AbstractWeaponBlueprint -> {
                val own = bp.hsLuaFacts?.combatIcon?.takeIf { it.isNotEmpty() }
                val type = when (bp) {
                    is xyz.znix.xftl.weapons.BeamBlueprint -> "beam"
                    is xyz.znix.xftl.weapons.BombBlueprint -> "bomb"
                    is xyz.znix.xftl.weapons.MissileBlueprint -> "missile"
                    is xyz.znix.xftl.weapons.FlakBlueprint -> "flak"
                    else -> "laser"
                }
                (own?.let { game.getImgIfExists("img/systemUI/weapbox_icon_W_$it.png") }) ?: game.getImgIfExists("img/systemUI/weapbox_icon_W_$type.png")
            }
            is DroneBlueprint -> bp.iconImage?.let { game.getImgIfExists("img/systemUI/weapbox_icon_D_$it.png") }
            else -> null
        }
        if (icon != null) icon.draw(a.x + 60f - icon.width, a.y + 28f - icon.height, a.x + 60f + icon.width, a.y + 28f + icon.height,
            0f, 0f, icon.width.f, icon.height.f, 1f, Colour.white)
        noArt(ui, a.x + 11, a.y + 44, 96)
    }

    /** The store box's turned launcher sprite (StoreBox::OnRender centres it on (60,34)), scaled down to the art area. */
    private fun drawOversized(g: Graphics, bp: AbstractWeaponBlueprint, img: Image, a: Area) {
        val w = img.height.f
        val h = img.width.f
        val s = minOf(ART_W / w, ART_H / h)
        g.pushTransform()
        g.translate(a.x + 60f, a.y + 34f)
        g.scale(s, s)
        bp.drawLauncherRotated(img, g, -w / 2f, -h / 2f)
        g.popTransform()
    }

    private fun noArt(ui: ConsoleUi, x: Int, y: Int, w: Int) {
        val t = "no art"
        labelFont.drawStringCentred(x.f, y + 12f, w.f, t, ui.tagColour(ConsoleItems.TAG_NO_ART))
    }

    /** The hangar's ship list card (ShipButton::OnRender): the card, its miniship, a lock while locked; the name under it. */
    private fun drawShip(g: Graphics, ui: ConsoleUi, item: Item, a: Area, over: Boolean) {
        val bp = item.blueprint as? LazyShipBlueprint ?: return
        val locked = ConsoleItems.TAG_LOCKED in item.tags
        val card = game.getImgIfExists("img/customizeUI/ship_list_button_" + (if (over) "select2" else "on") + ".png")
        card?.draw(a.x, a.y)
        val alt = if (locked && xyz.znix.xftl.hs.HS.active && xyz.znix.xftl.hs.HS.cfg().altLockedMiniships)
            game.getImgIfExists("img/customizeUI/miniship_${bp.img}_base.png") else null
        val mini = alt ?: game.getImgIfExists("img/customizeUI/miniship_${bp.img}.png")
        if (mini != null) {
            mini.draw(a.x, a.y)
            if (locked) mini.draw(a.x, a.y, Colour.black)
        } else noArt(ui, a.x, a.y + 30, card?.width ?: a.w)
        if (locked) game.getImgIfExists("img/customizeUI/box_lock_" + (if (over) "selected" else "on") + ".png")?.draw(a.x + 90, a.y + 50)
        val cardH = card?.height ?: 121
        // the key (drawTile), then the local name and the class
        val local = item.local.takeIf { it != item.english }
        val classTitle = bp.shipClass?.let { game.translator[it] }
        keyFont.drawString(a.x + 2f, a.y + cardH + 2f + KEY_LINE_H * 3 - 2f, fitKey(listOfNotNull(local, classTitle).joinToString(" - "), a.w - 4), Colour(170, 170, 170))
    }
}
