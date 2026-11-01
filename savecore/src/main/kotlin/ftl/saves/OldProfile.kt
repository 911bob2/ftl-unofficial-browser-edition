package ftl.saves

/**
 * The one-time conversion of a profile from before the settings left it (docs/SAVES.md, "Migration"): the engine reads
 * only the new format, so every migration step puts an old profile through this. The options, hotkeys and volumes move into
 * the device's settings document; a profile without them comes back unchanged.
 */
object OldProfile {
    data class Split(val profile: String, val settings: String?)

    private val ROOT = Regex("<xftl-profile\\b[^>]*>")
    private val ATTRIBUTES = Regex("\\s+(achPopups|hangarAE|fpsLimit|touchAutoPause|autoPause|beaconPaths|dialogKeys|language)=\"[^\"]*\"")
    private val ELEMENTS = Regex("\\s*<(?:hotkey|soundVolume)\\b[^>]*/>")

    fun split(xml: String): Split {
        val root = ROOT.find(xml) ?: return Split(xml, null)
        val attributes = ATTRIBUTES.findAll(root.value).map { it.value }.joinToString("")
        // Only the profile's own children: the Hyperspace block keeps whatever its entries wrote.
        val bodyStart = root.range.last + 1
        val blockStart = xml.indexOf("<hyperspace", bodyStart).let { if (it < 0) xml.length else it }
        val body = xml.substring(bodyStart, blockStart)
        val elements = ELEMENTS.findAll(body).map { it.value.trim() }.joinToString("")
        if (attributes.isEmpty() && elements.isEmpty()) return Split(xml, null)
        val profile = xml.substring(0, root.range.first) + ATTRIBUTES.replace(root.value) { "" } + ELEMENTS.replace(body) { "" } + xml.substring(blockStart)
        return Split(profile, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<xftl-settings$attributes>$elements</xftl-settings>")
    }

    /** Which settings a device with several old profiles keeps: the first with a language, else the first. */
    fun choose(settings: List<String>): String? = settings.firstOrNull { " language=\"" in it } ?: settings.firstOrNull()
}
