// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/UserInterface/HullNumbers.cpp and HullNumbers.h
package xyz.znix.xftl.hs.render

import org.jdom2.Element
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.HsConfig
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.NativeFonts

/**
 * `<hullNumbers>`, `<hullBars>` and `<playerHpColorFix>`:
 *
 * - hull numbers (enabled="true"): ShipStatus::RenderShields prints the player's hull integrity after the shields
 *   (white, the colour the shields left set) at `playerText` in the status frame; CombatControl::RenderTarget
 *   prints the target's at `enemyText` (`bossText` for the boss frame; a `language` attribute selects a per-language
 *   position), red (1, 0.2, 0.2) for a hostile target and white otherwise, in screen coordinates. The hull boxes
 *   become `statusUI/top_hull_numbers(_red).png` and `combatUI/box_hostiles2_numbers.png` /
 *   `box_hostiles_boss_numbers.png` (ShipStatus::OnInit and the GetImageId hook). `type` is the freetype font id
 *   (unregistered ids such as MV's 3 fall back like FTL's own, see NativeFonts.spec).
 * - hull bars: CachedImage::SetPartial for the target's hull bar: type 2 scales the bar to hull / max hull of
 *   `maxHull` (22, vanilla's full bar) when the maximum exceeds it (`maxHullBoss` in the boss frame); type 1 draws
 *   layered bars of `maxHull` points in `barColor`s.
 * - playerHpColorFix (HS default true): ShipStatus::RenderHealth colours the player's bar by the hull fraction
 *   (> 0.65 green, > 0.32 yellow, else red) instead of vanilla's 20/10 point steps, and its length is always the
 *   fraction of 360 px.
 */
object HsHullNumbers {
    class Indicator(@JvmField val x: Int, @JvmField val y: Int, @JvmField val type: Int, @JvmField val align: String)

    class Config(
        @JvmField val enabled: Boolean,
        @JvmField val player: Indicator,
        @JvmField val enemy: Indicator, @JvmField val enemyLoc: Map<String, Indicator>,
        @JvmField val boss: Indicator, @JvmField val bossLoc: Map<String, Indicator>,
        @JvmField val barType: Int, @JvmField val barWidth: Int, @JvmField val barWidthBoss: Int,
        @JvmField val barColours: List<Colour>,
    )

    private val HOSTILE = Colour(1f, 0.2f, 0.2f, 1f)
    private var parsedFor: HsConfig? = null
    private var config: Config? = null
    private val fonts = HashMap<Int, NativeFonts.NativeFont>()
    private var fontContent: Any? = null

    @JvmStatic
    fun config(): Config {
        val cfg = HS.cfg()
        if (parsedFor !== cfg) {
            config = parse(if (cfg.hullNumbers) cfg.node("hullNumbers") else null, cfg.node("hullBars"))
            parsedFor = cfg
        }
        return config!!
    }

    private fun indicator(base: Indicator, n: Element): Indicator = Indicator(
        n.getAttributeValue("x")?.trim()?.toInt() ?: base.x,
        n.getAttributeValue("y")?.trim()?.toInt() ?: base.y,
        n.getAttributeValue("type")?.trim()?.toInt() ?: base.type,
        n.getAttributeValue("align") ?: base.align,
    )

    @JvmStatic
    fun parse(numbers: Element?, bars: Element?): Config {
        var player = Indicator(335, 50, 0, "left")
        var enemy = Indicator(988, 59, 0, "center")
        var boss = Indicator(864, 16, 0, "center")
        val enemyLoc = HashMap<String, Indicator>()
        val bossLoc = HashMap<String, Indicator>()
        if (numbers != null) {
            numbers.getChild("playerText")?.let { player = indicator(player, it) }
            for (c in numbers.getChildren("enemyText")) {
                val lang = c.getAttributeValue("language")
                // std::unordered_map::insert keeps the first entry of a language.
                if (lang != null) enemyLoc.putIfAbsent(lang, indicator(Indicator(988, 59, 0, "center"), c)) else enemy = indicator(enemy, c)
            }
            for (c in numbers.getChildren("bossText")) {
                val lang = c.getAttributeValue("language")
                if (lang != null) bossLoc.putIfAbsent(lang, indicator(Indicator(864, 16, 0, "center"), c)) else boss = indicator(boss, c)
            }
        }
        var type = 0
        var width = 22
        var widthBoss = 22
        val colours = arrayListOf(Colour(0.47f, 1f, 0.47f, 1f))
        if (bars != null) {
            bars.getAttributeValue("type")?.let { type = it.trim().toInt() }
            bars.getAttributeValue("maxHull")?.let { width = it.trim().toInt() }
            bars.getAttributeValue("maxHullBoss")?.let { widthBoss = it.trim().toInt() }
            for (c in bars.getChildren("barColor")) {
                fun f(a: String) = c.getAttributeValue(a)?.trim()?.toFloat()?.div(255f) ?: 1f
                colours += Colour(f("r"), f("g"), f("b"), f("a"))
            }
        }
        return Config(numbers != null, player, enemy, enemyLoc, boss, bossLoc, type, width, widthBoss, colours)
    }

    /** Hull numbers on: the status/target box images carry a number field. [path] starts with img/. */
    @JvmStatic
    fun imagePath(path: String): String {
        if (!config().enabled) return path
        return when (path) {
            "img/statusUI/top_hull.png" -> "img/statusUI/top_hull_numbers.png"
            "img/statusUI/top_hull_red.png" -> "img/statusUI/top_hull_numbers_red.png"
            "img/combatUI/box_hostiles2.png" -> "img/combatUI/box_hostiles2_numbers.png"
            "img/combatUI/box_hostiles_boss.png" -> "img/combatUI/box_hostiles_boss_numbers.png"
            else -> path
        }
    }

    private fun font(content: xyz.znix.xftl.game.InGameState.GameContent, id: Int): NativeFonts.NativeFont {
        if (fontContent !== content) { fonts.clear(); fontContent = content }
        return fonts.getOrPut(id) { NativeFonts.font(content::getFont, id) }
    }

    /** HullNumbers::PrintAlignment. */
    private fun print(content: xyz.znix.xftl.game.InGameState.GameContent, i: Indicator, x: Float, y: Float, text: String, colour: Colour) {
        val f = font(content, i.type)
        when (i.align) {
            "center" -> f.printCenter(x, y, text, colour)
            "right" -> f.printRight(x, y, text, colour)
            else -> f.print(x, y, text, colour)
        }
    }

    /** ShipStatus::RenderShields hook, in the status frame (the player's top bar at 0,0). */
    @JvmStatic
    fun drawPlayer(content: xyz.znix.xftl.game.InGameState.GameContent, hull: Int) {
        val c = config()
        if (!c.enabled) return
        print(content, c.player, c.player.x.toFloat(), c.player.y.toFloat(), hull.toString(), Colour.white)
    }

    /**
     * CombatControl::RenderTarget hook. ([originX], [originY]) is where screen (0, 0) is in the caller's frame, so
     * the number keeps its screen position relative to the target box.
     */
    @JvmStatic
    fun drawEnemy(content: xyz.znix.xftl.game.InGameState.GameContent, hull: Int, hostile: Boolean, boss: Boolean,
                  language: String, originX: Float, originY: Float) {
        val c = config()
        if (!c.enabled) return
        val i = if (boss) c.bossLoc[language] ?: c.boss else c.enemyLoc[language] ?: c.enemy
        print(content, i, originX + i.x, originY + i.y, hull.toString(), if (hostile) HOSTILE else Colour.white)
    }

    /**
     * The target hull bar's texture fraction under `<hullBars>` (CachedImage::SetPartial while RenderTarget runs),
     * or [vanilla] when the bar is unchanged. Type 1's full layers are drawn by [layers].
     */
    @JvmStatic
    fun enemyBarFraction(vanilla: Float, hull: Int, maxHull: Int, boss: Boolean): Float {
        val c = config()
        val width = if (boss) c.barWidthBoss else c.barWidth
        return when {
            c.barType == 2 && maxHull > width -> hull.toFloat() / maxHull.toFloat() * (width / 22f)
            c.barType == 1 && hull > width -> 0f
            else -> vanilla
        }
    }

    /** Type 1 layered bars after RenderTarget: (layer colour below, top colour, top fraction), or null. */
    @JvmStatic
    fun layers(hull: Int, boss: Boolean): FloatArray? {
        val c = config()
        val width = if (boss) c.barWidthBoss else c.barWidth
        if (c.barType != 1 || hull <= width) return null
        val ibar = hull / width
        return floatArrayOf(ibar - 1f, ibar.toFloat(), width / 22f, (hull % width).toFloat() / width * (width / 22f))
    }

    @JvmStatic
    fun barColour(index: Int): Colour {
        val l = config().barColours
        return l[Math.floorMod(index, l.size)]
    }

    /** RenderHealth with playerHpColorFix: the bar colour by hull fraction. */
    @JvmStatic
    fun playerBarColour(hull: Int, maxHull: Int, vanilla: Colour): Colour {
        if (!HS.cfg().playerHpColorFix) return vanilla
        val ratio = hull.toFloat() / maxHull.toFloat()
        return when {
            ratio > 0.65f -> xyz.znix.xftl.Constants.SHIP_HEALTH_HIGH
            ratio > 0.32f -> xyz.znix.xftl.Constants.SHIP_HEALTH_MED
            else -> xyz.znix.xftl.Constants.SHIP_HEALTH_LOW
        }
    }
}
