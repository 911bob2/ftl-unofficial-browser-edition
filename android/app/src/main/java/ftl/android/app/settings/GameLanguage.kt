package ftl.android.app.settings

/**
 * The game's language in the device's settings document (`xftl-settings`, docs/SAVES.md): the same value the game's own
 * language list writes, and read at the next start. No value means the game asks at its first start. The names are
 * each language's own, as that list shows them. A mod's language (Hyperspace can add some) is chosen in the game and is
 * kept here as it stands.
 */
object GameLanguage {
    val CHOICES = listOf(
        "" to "English", "de" to "Deutsch", "es" to "Español", "fr" to "Français", "it" to "Italiano",
        "pl" to "Polski", "pt" to "Português do Brasil", "ru" to "Русский", "ja" to "日本語", "zh-Hans" to "中文",
    )

    private val ROOT = Regex("<xftl-settings\\b[^>]*>")
    private val ATTRIBUTE = Regex("\\slanguage=\"([^\"]*)\"")

    /** The chosen language's code, null when none has been chosen. */
    fun read(settings: String?): String? = settings?.let { ROOT.find(it) }?.let { ATTRIBUTE.find(it.value) }?.groupValues?.get(1)

    fun label(code: String?): String = when {
        code == null -> "Not chosen yet: the game asks at its first start"
        code == "en" -> "English"
        else -> CHOICES.firstOrNull { it.first == code }?.second ?: code
    }

    /** The document with this language; everything else in it stays. */
    fun write(settings: String?, code: String): String {
        val root = settings?.let { ROOT.find(it) } ?: return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<xftl-settings language=\"$code\"></xftl-settings>\n"
        val attribute = ATTRIBUTE.find(root.value)
        val next = if (attribute != null) root.value.replaceRange(attribute.range, " language=\"$code\"")
        else root.value.replaceFirst("<xftl-settings", "<xftl-settings language=\"$code\"")
        return settings.replaceRange(root.range, next)
    }
}
