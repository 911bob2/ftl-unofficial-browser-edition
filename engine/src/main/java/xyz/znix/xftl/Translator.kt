package xyz.znix.xftl

import xyz.znix.xftl.sys.XftlResources

import org.jdom2.Document
import org.jdom2.Element
import ftl.browser.BrowserSaxBuilder

/**
 * TextLibrary. The desktop game keeps English in one dictionary and every other language in a dictionary of its own
 * (the `<text language="xx">` entries of `data/text-xx.xml`); GetText(name, lang) answers from the
 * language's dictionary, falls back to English (and remembers that fallback in the language's dictionary), and
 * otherwise prints "Couldn't find in dictionary" and returns "Could not find: name".
 *
 * [translations] is the current language's view: English with the language's own texts laid over it, which is what
 * GetText(name, currentLanguage) returns for every name. [setLanguage] switches it in place, so every screen holding
 * this translator reads the new language (TextLibrary::SetLanguage).
 */
class Translator {
    val translations: Map<String, String> get() = current

    // The untrimmed texts, for the few the original uses with their spaces (crewlist_comma ", ").
    private val untrimmed = HashMap<String, String>()

    private val df: Datafile?
    private val english = HashMap<String, String>()
    private val englishUntrimmed = HashMap<String, String>()
    private var current: HashMap<String, String> = english
    private val dictionaries = HashMap<String, HashMap<String, String>>()
    private val dictionariesUntrimmed = HashMap<String, HashMap<String, String>>()

    /** TextLibrary::currentLanguage: "en" (or "", the language list's English entry) means English. */
    var language: String = "en"
        private set

    constructor(df: Datafile, lang: String) {
        this.df = df
        fun load(name: String) = parseFile(name, df, english, englishUntrimmed)

        load("data/text_achievements.xml")
        load("data/text_blueprints.xml")
        load("data/text_events.xml")
        load("data/text_misc.xml")
        load("data/text_sectorname.xml")
        load("data/text_tooltips.xml")
        load("data/text_tutorial.xml")

        // Put a when block here if/when our strings are translated.
        parseEmbedded("assets/lang/en.xml", english, englishUntrimmed)
        untrimmed.putAll(englishUntrimmed)
        setLanguage(lang)
    }

    /**
     * Used for the select-a-datafile screen, as we don't have a main
     * datafile to use at that point.
     */
    constructor(embeddedPath: String) {
        df = null
        parseEmbedded(embeddedPath, english, englishUntrimmed)
        untrimmed.putAll(englishUntrimmed)
    }

    /** TextLibrary::SetLanguage: later lookups (and [translations]) answer in [lang]. */
    fun setLanguage(lang: String) {
        language = lang
        val dict = dictionary(lang)
        if (dict == null) {
            current = english
            untrimmed.clear(); untrimmed.putAll(englishUntrimmed)
            return
        }
        val merged = HashMap<String, String>(english)
        merged.putAll(dict)
        current = merged
        untrimmed.clear(); untrimmed.putAll(englishUntrimmed); untrimmed.putAll(dictionariesUntrimmed[lang]!!)
    }

    /**
     * The language's own dictionary (null for English and for a language without texts). ResourceControl::LoadText
     * reads every `data/text*.xml`; the entries of a language other than English come from its
     * `data/text-<lang>.xml` (the shipped data and Hyperspace's languages keep them there), read the first time the
     * language is asked for.
     */
    private fun dictionary(lang: String): HashMap<String, String>? {
        if (isEnglish(lang) || df == null) return null
        dictionaries[lang]?.let { return it }
        val dict = HashMap<String, String>()
        val dictUntrimmed = HashMap<String, String>()
        val file = df.getOrNull("data/text-$lang.xml")
        if (file != null) {
            val ignored = HashMap<String, String>()
            parseDoc(file.name, df.parseXML(file), ignored, HashMap(), lang, dict, dictUntrimmed)
            // Entries without a language attribute belong to the English dictionary.
            for ((k, v) in ignored) { english.putIfAbsent(k, v) }
        }
        dictionaries[lang] = dict
        dictionariesUntrimmed[lang] = dictUntrimmed
        return dict
    }

    private fun parseFile(filename: String, df: Datafile, tl: HashMap<String, String>, raw: HashMap<String, String>) {
        val doc = df.parseXML(df[filename])
        parseDoc(filename, doc, tl, raw)
    }

    private fun parseEmbedded(path: String, tl: HashMap<String, String>, raw: HashMap<String, String>) {
        val builder = com.pocketwormhole.android.SafeXML.builder()
        builder.expandEntities = false
        val doc = builder.build(XftlResources.open(path)!!)

        parseDoc(path, doc, tl, raw)
    }

    private fun parseDoc(
        fileName: String, doc: Document, tl: HashMap<String, String>, raw: HashMap<String, String>,
        lang: String? = null, langTl: HashMap<String, String>? = null, langRaw: HashMap<String, String>? = null
    ) {
        for (elem in doc.rootElement.children) {
            if (elem.name != "text") {
                println("[WARN] Found invalid element '${elem.name}' in translation file '$fileName'")
                continue
            }

            // TextLibrary keeps the text as the file has it (leading and trailing spaces included).
            val name = elem.getAttributeValue("name")
            val text = elem.text.replace("\\n", "\n")
            val entryLang = if (lang == null) null else elem.getAttributeValue("language")
            if (entryLang == null) {
                tl[name] = text
                raw[name] = text
            } else if (entryLang == lang) {
                langTl!![name] = text
                langRaw!![name] = text
            }
        }
    }

    /** A text with its surrounding whitespace kept, as TextLibrary stores it. */
    fun untrimmed(key: String): String = untrimmed[key] ?: get(key)

    operator fun get(key: String): String {
        // Make it really obvious if a translation is missing
        return current[key] ?: "Could not find: $key"
    }

    /** TextLibrary::GetText(name, lang): [lang]'s text, else English. */
    fun getIn(lang: String, key: String): String {
        if (lang == language) return get(key)
        return dictionary(lang)?.get(key) ?: english[key] ?: "Could not find: $key"
    }

    /**
     * Convenience helper for [GameText.get].
     */
    operator fun get(text: GameText): String {
        return text.get(this)
    }

    companion object {
        fun isEnglish(lang: String) = lang.isEmpty() || lang == "en"
    }
}

/**
 * Represents a string parsed from the XML files that may or may not be localised.
 */
class GameText private constructor(private val literal: String?, private val key: String?) {

    fun get(translator: Translator): String {
        if (literal != null) {
            return literal
        }

        return translator[key!!]
    }

    /** TextString::data: the text id, or the literal text (Hyperspace hashes a secret sector's name with it). */
    val hsData: String get() = literal ?: key ?: ""

    /** TextString::isLiteral. */
    val hsIsLiteral: Boolean get() = literal != null

    // These are for use in automated tests only!
    fun getLiteralForTesting(): String? = literal
    fun getKeyForTesting(): String? = key

    companion object {
        fun parse(elem: Element): GameText {
            val id: String? = elem.getAttributeValue("id")
            if (id != null) {
                return GameText(null, id)
            }

            return GameText(elem.textTrim, null)
        }

        fun localised(key: String): GameText {
            return GameText(null, key)
        }

        fun literal(string: String): GameText {
            return GameText(string, null)
        }
    }
}

fun Element.getGameTextChild(name: String): GameText? {
    val elem = this.getChild(name) ?: return null
    return GameText.parse(elem)
}

/**
 * The game's languages and the current one. OptionsScreen::languageList is built at start-up: English
 * (the empty code), de, es, fr, it, pl, pt, ru, ja, zh-Hans; Hyperspace's `<languages>` appends its own
 * (AddLanguage). Settings::language (settings.ini `language=`, written only once chosen) picks the current one,
 * which TextLibrary and GetFontData read; we keep it in the profile as `language`.
 */
object Languages {
    @JvmField val VANILLA = listOf("", "de", "es", "fr", "it", "pl", "pt", "ru", "ja", "zh-Hans")

    /** OptionsScreen::languageList. */
    @JvmField val list = ArrayList(VANILLA)

    /** TextLibrary::currentLanguage ("en" until a language is chosen; the chooser's English is ""). */
    @JvmStatic var current: String = "en"
        private set

    /**
     * The language the settings were loaded in (the game's start): the LanguageChooser's buttons were created then,
     * so their labels keep that language's line height after a switch (native capture ch2-ja, started in English).
     */
    @JvmStatic var atStart: String = "en"

    /** Hyperspace AddLanguage: appended once. */
    @JvmStatic fun add(code: String) { if (code !in list) list.add(code) }

    /** Back to the game's own list (a new mod session). */
    @JvmStatic fun resetList() { list.clear(); list.addAll(VANILLA) }

    /**
     * Settings::LoadSettings: a `language` value with a character outside A-Z, a-z, 0-9, '-' and '_' is
     * "Invalid language string in settings.ini, ignoring" and becomes English.
     */
    @JvmStatic fun validSetting(value: String): String =
        if (value.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }) value else ""

    private val localImages = HashMap<String, String>()
    private var localImagesFor: Any? = null

    /**
     * ResourceControl::GetImageId: an image the language has its own copy of is drawn from
     * `img/loc-<language>/` (main menu buttons, pause banners, the map's fuel notices, MISS/RESIST and others).
     */
    @JvmStatic fun localImage(df: Datafile, path: String): String {
        if (!path.startsWith("img/")) return path
        if (localImagesFor !== df) { localImages.clear(); localImagesFor = df }
        val key = current + "|" + path
        return localImages.getOrPut(key) {
            val local = "img/loc-" + current + path.substring(3)
            if (df.getOrNull(local) != null) local else path
        }
    }

    /** TextLibrary::SetLanguage for the language fonts; the game content's translator follows (LanguageSwitch). */
    @JvmStatic fun select(code: String) {
        current = code
        xyz.znix.xftl.rendering.LanguageFonts.select(code)
    }
}

/**
 * The characters a name field takes (TextInput::OnTextInput, the text mode the hangar and the crew
 * screen use): printable ASCII always; beyond it, in a Chinese language (one starting "zh") General Punctuation up to
 * U+206F, the ideographic space and the CJK ideographs; in Japanese U+2000-27FF, U+3000-30FF, the CJK ideographs and
 * the full-width forms U+FF00-FF9F; in any other language the Latin and Cyrillic letters of the game's fonts.
 */
object TextInputChars {
    @JvmStatic fun accepts(c: Char): Boolean {
        val v = c.code
        if (v in 0x20..0x7e) return true
        val lang = Languages.current
        return when {
            lang.startsWith("zh") -> v in 0x2000..0x206f || v == 0x3000 || v in 0x4e00..0x9fff
            lang == "ja" -> v in 0x2000..0x27ff || v in 0x3000..0x30ff || v in 0x4e00..0x9fff || v in 0xff00..0xff9f
            else -> v in 0xa0..0xa1 || v == 0xab || v == 0xbb || v in 0xbf..0x17e || v == 0x401 || v in 0x410..0x44f ||
                v == 0x451 || v in 0x201d..0x201e || v == 0x20ac
        }
    }
}
