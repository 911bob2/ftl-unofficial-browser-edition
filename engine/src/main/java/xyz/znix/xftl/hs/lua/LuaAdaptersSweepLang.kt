// Ported from FTL-Hyperspace (GPL-3.0-or-later) commit 1b05a07 (lua/modules/hyperspace.i: font_text_width,
// ResourceControl::GetFontData, TextLibrary::GetText, SettingValues::language; lua/modules/graphics.i: freetype;
// src/game/Utilities/CustomLocalization.cpp). The font behaviour is the 1.6.14 executable's (see LanguageFonts).
package xyz.znix.xftl.hs.lua

import xyz.znix.xftl.Languages
import xyz.znix.xftl.rendering.LanguageFonts
import xyz.znix.xftl.hs.lua.LuaApi as A
import xyz.znix.xftl.hs.lua.LuaArgs as arg
import xyz.znix.xftl.hs.lua.LuaPush as ret

/** A `freetype::font_data *` (SWIG's opaque pointer type: no members, only passed to font_text_width). */
class LuaFontData(@JvmField val data: LanguageFonts.FontData) : LuaTyped {
    override val luaType: Int get() = LuaAdaptersSweepLang.T_FONT_DATA
}

/** Languages and fonts: called last by LuaAdaptersMore.install (these replace earlier English-only adapters). */
object LuaAdaptersSweepLang {
    /** TYPE_NAMES "freetype::font_data *". */
    const val T_FONT_DATA = 300

    fun install() {
        // freetype::font_data &ResourceControl::GetFontData(int size, bool ignoreLanguage)
        LuaReg.method(A.M_ResourceControl_GetFontData, LuaMethod {
            val content = HsLua.game?.gameContent
            val data = LanguageFonts.fontData(arg.int(2), arg.bool(3)) { name ->
                content?.getFont(name) ?: throw LuaError("no game content for GetFontData")
            }
            ret.obj(LuaFontData(data), T_FONT_DATA)
        })
        // char *ResourceControl::LoadFile(const std::string &fileName): the file from the resources as SWIG pushes a
        // char * (lua_pushstring, so up to its first NUL); NULL, so nil, when there is no such file.
        LuaReg.method(A.M_ResourceControl_LoadFile, LuaMethod {
            val path = arg.str(2) ?: ""
            if (!ftl.browser.BrowserFiles.contains(path)) ret.nil() else {
                val bytes = ftl.browser.BrowserFiles.read(path)
                val end = bytes.indexOf(0.toByte()).let { if (it < 0) bytes.size else it }
                ret.str(String(bytes, 0, end, Charsets.UTF_8))
            }
        })
        // float font_text_width(freetype::font_data &fontData, const char *str, float size): Hyperspace binds the
        // executable's function by its signature (FTLGameWin32.cpp _func731); under HS 1.23.2 it returns 0 for every
        // font, either ignoreLanguage, text and size the oracle probes (sweep-lang.lua: 1008 rows per language).
        LuaReg.method(A.M_font_text_width, LuaMethod {
            arg.obj(1) as? LuaFontData ?: throw LuaError("font_text_width: expected font_data")
            ret.num(0)
        })
        // std::string TextLibrary::GetText(const std::string &name, const std::string &lang)
        LuaReg.method(A.M_TextLibrary_GetText__SWIG_1, LuaMethod {
            val tr = HsLua.game?.gameContent?.translator
            val key = arg.str(2) ?: ""
            val lang = arg.str(3) ?: ""
            ret.str(tr?.getIn(lang, key) ?: key)
        })
        // Settings::language: the chosen language ("en" until one is chosen, as the capture's settings.ini has it).
        LuaReg.str(A.A_SettingValues_language, StrGet { HsLua.game?.profile?.language ?: "en" })
        // Settings::languageSet: whether a language has been chosen (settings.ini has `language=`).
        LuaReg.bool(A.A_SettingValues_languageSet, { HsLua.game?.profile?.let { xyz.znix.xftl.game.LanguageSelectState.languageSet(it) } ?: true })
        // SDLKey SettingValues::GetHotkey(const std::string &hotkeyName) (Settings::GetHotkey): the key bound to the
        // hotkey of that settings name (the Controls screen's names: pause, jump, increase_font...), 0 when none.
        LuaReg.method(A.M_SettingValues_GetHotkey, LuaMethod {
            ret.num(boundKey(arg.str(2) ?: "")?.let { LuaEvents.sdlKey(it.keyID) } ?: 0)
        })
        // std::string SettingValues::GetHotkeyName(const std::string &name): that key's name as tooltips show it;
        // without a key, SDLK_UNKNOWN's keycap_none ("None", Korean "없음").
        LuaReg.method(A.M_SettingValues_GetHotkeyName, LuaMethod {
            val tr = HsLua.game?.gameContent?.translator
            val key = boundKey(arg.str(2) ?: "")
            // a printable key is SDL's name for it, its character (the backslash key "\\", not the engine's "# or \\"; oracle swm hotkey rows)
            val sdl = key?.let { LuaEvents.sdlKey(it.keyID) } ?: 0
            ret.str(if (key == null) tr?.get("keycap_none") ?: "" else if (sdl in 33..126) sdl.toChar().uppercaseChar().toString()
                else tr?.get(key.text)?.uppercase(java.util.Locale.ROOT) ?: "")
        })
        // ChoiceBox::fontSize of CommandGui's choice box (Hyperspace.App.gui.choiceBox) while no event box is open: the
        // box lasts for the session, so its size (EventFontSize, 12 at start) reads and writes without an event too.
        val fontGet = LuaReg.numGet[A.A_ChoiceBox_fontSize]
        val fontSet = LuaReg.numSet[A.A_ChoiceBox_fontSize]
        LuaReg.numGet[A.A_ChoiceBox_fontSize] = NumGet { o ->
            if (o is LuaPtr && o.luaType == A.T_ChoiceBox) xyz.znix.xftl.game.EventFontSize.size.toDouble()
            else (fontGet ?: throw LuaError("ChoiceBox.fontSize")).get(o)
        }
        LuaReg.numSet[A.A_ChoiceBox_fontSize] = NumSet { o, v ->
            if (o is LuaPtr && o.luaType == A.T_ChoiceBox) xyz.znix.xftl.game.EventFontSize.size = v.toInt()
            else (fontSet ?: throw LuaError("ChoiceBox.fontSize")).set(o, v)
        }
        // freetype::easy_printAutoShrink(int fontId, float x, float y, int maxWidth, bool centered, const std::string &text)
        LuaReg.method(A.M_freetype_easy_printAutoShrink, LuaMethod {
            printAutoShrink(arg.int(1), arg.float(2), arg.float(3), arg.int(4), arg.bool(5), arg.str(6) ?: "")
            0
        })
    }

    /** The key the profile binds to the hotkey with that Settings name (the hotkey whose ui name is hotkey_<name>). */
    private fun boundKey(name: String): xyz.znix.xftl.game.HotkeyButton? {
        val main = HsLua.game ?: return null
        val manager = main.gameContent.hotkeyManager
        val hotkey = manager.groups.flatMap { it.hotkeys }.firstOrNull { it.uiName == "hotkey_$name" } ?: return null
        val forward = HashMap<xyz.znix.xftl.game.HotkeyButton, xyz.znix.xftl.game.Hotkey>()
        val reverse = HashMap<xyz.znix.xftl.game.Hotkey, xyz.znix.xftl.game.HotkeyButton>()
        manager.calculateBindings(main.profile, forward, reverse)
        return reverse[hotkey]
    }

    private val colour = xyz.znix.xftl.rendering.Colour(1f, 1f, 1f, 1f)

    /**
     * freetype::easy_printAutoShrink. Fonts 53 and 64 (and 63) step down towards 52 and 62 while the text,
     * measured unwrapped (easy_measurePrintLines with maxWidth + 999), is wider than [maxWidth]; every step moves the
     * text down by half the smallest font's (51's or 61's) measured height, rounded. Any other font prints as given.
     * The text is then printed as easy_print does, or centred on [x].
     */
    private fun printAutoShrink(fontId: Int, x: Float, y0: Float, maxWidth: Int, centered: Boolean, text: String) {
        val content = HsLua.game?.gameContent ?: return
        fun font(id: Int) = xyz.znix.xftl.rendering.NativeFonts.font({ content.getFont(it) }, id)
        fun measure(f: xyz.znix.xftl.rendering.NativeFonts.NativeFont, width: Int): Pair<Float, Int> {
            val lines = text.split('\n').flatMap { if (it.isEmpty()) listOf("") else f.font.wrapString(it, width) }
            var w = 0f
            for (l in lines) w = maxOf(w, f.measure(l).toFloat())
            return w to lines.size * f.lineHeight
        }
        val (hi, lo) = when (fontId) { 52, 53 -> 52 to 51; 62, 63, 64 -> 62 to 61; else -> fontId to fontId }
        var id = fontId
        var y = y0
        if (id > hi) {
            val wrap = maxWidth + 999
            while (true) {
                if (maxWidth.toFloat() >= measure(font(id), wrap).first) break
                id--
                val h = measure(font(lo), wrap).second * 0.5f
                y += Math.floor(h + 0.5).toFloat()
                if (id == hi) break
            }
        }
        if (HsLua.graphics == null) return
        LuaGraphics.flushQuads()
        val f = font(id)
        LuaGraphics.colour(colour, LuaGraphics.current)
        if (centered) f.printCenter(x, y, text, colour) else f.print(x, y, text, colour)
    }

    /** The current language code, for scripts that read Hyperspace.Text.currentLanguage through GetText. */
    @JvmStatic fun currentLanguage(): String = Languages.current
}
