// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/UserInterface/MainMenu.cpp
package xyz.znix.xftl.hs.render

import ftl.browser.NativeRandom
import org.jdom2.Element
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.HsConfig

/**
 * `<titleScreen>` (CustomMainMenu): with `enabled="true"` the main menu's background and glowing logo come from
 * `main_base2_hyperspace.png` / `main_FTL2_hyperspace.png` (ResourceControl::GetImageId hook, so every screen that
 * asks for those two images gets the replacement); the logo (MainMenu::glowy) is drawn at `logo_x`/`logo_y`
 * (default 820, 60) and a splash image `main_menus/splash_<name>.png` picked in MainMenu::Open is drawn just before
 * it, at full opacity. The Windows build of HS also takes the Into the Breach button out of the menu (Open), which
 * hides its banner and animation; the oracle (Windows FTL 1.6.9 + HS) shows no banner.
 *
 * The splash pick uses the game's global random32() like HS: one draw `% count` over the splash images without a
 * `chance`, then one `% 10000 <= chance` draw per image with a chance, the first hit replacing the pick.
 */
object HsTitleScreen {
    class Splash(@JvmField val x: Int, @JvmField val y: Int, @JvmField val center: Boolean, @JvmField val image: String,
                 @JvmField val chance: Int)

    class Config(@JvmField val custom: Boolean, @JvmField val logoX: Int, @JvmField val logoY: Int,
                 @JvmField val splashes: List<Splash>)

    private var parsedFor: HsConfig? = null
    private var config: Config? = null

    /** The splash chosen by the last [open], kept across menu sub-screens like HS's static currentSplashImage. */
    @JvmField
    var current: Splash? = null

    /** The config for the loaded hyperspace.xml (HS defaults when `<titleScreen>` is absent). HS sessions only. */
    @JvmStatic
    fun config(): Config {
        val cfg = HS.cfg()
        if (parsedFor !== cfg) {
            config = parse(cfg.node("titleScreen"))
            parsedFor = cfg
            current = null
        }
        return config!!
    }

    /** CustomMainMenu::ParseTitleScreenNode. */
    @JvmStatic
    fun parse(node: Element?): Config {
        if (node == null) return Config(false, 820, 60, emptyList())
        val custom = node.getAttributeValue("enabled")?.let { HsConfig.bool(it) } ?: false
        val logoX = node.getAttributeValue("logo_x")?.trim()?.toInt() ?: 820
        val logoY = node.getAttributeValue("logo_y")?.trim()?.toInt() ?: 60
        val splashes = ArrayList<Splash>()
        for (child in node.children) {
            if (child.name != "splashImages") continue
            for (s in child.children) {
                if (s.name != "splashImage") continue
                splashes += Splash(
                    s.getAttributeValue("x")?.trim()?.toInt() ?: 10,
                    s.getAttributeValue("y")?.trim()?.toInt() ?: 10,
                    // SplashImage::center is uninitialised in HS when the attribute is missing; MV always sets it.
                    s.getAttributeValue("center")?.let { HsConfig.bool(it) } ?: false,
                    s.text,
                    s.getAttributeValue("chance")?.trim()?.toInt() ?: -1
                )
            }
        }
        return Config(custom, logoX, logoY, splashes)
    }

    /** ResourceControl::GetImageId hook: the two title images under a custom title screen. [path] starts with img/. */
    @JvmStatic
    fun imagePath(path: String): String {
        if (!HS.active || !config().custom) return path
        return when (path) {
            "img/main_menus/main_base2.png" -> "img/main_menus/main_base2_hyperspace.png"
            "img/main_menus/main_FTL2.png" -> "img/main_menus/main_FTL2_hyperspace.png"
            else -> path
        }
    }

    /** MainMenu::Open hook (before the original). */
    @JvmStatic
    fun open() {
        val splashes = config().splashes
        if (splashes.isEmpty()) return
        val plain = splashes.filter { it.chance <= 0 }
        if (plain.isNotEmpty()) current = plain[NativeRandom.next31() % plain.size]
        for (s in splashes) {
            if (s.chance > 0 && NativeRandom.next31() % 10000 <= s.chance) {
                current = s
                break
            }
        }
    }

    @JvmStatic
    fun splashPath(s: Splash) = "img/main_menus/splash_${s.image}.png"
}
