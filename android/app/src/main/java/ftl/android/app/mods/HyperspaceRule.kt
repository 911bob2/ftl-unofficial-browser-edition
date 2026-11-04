package ftl.android.app.mods

/**
 * "Hyperspace mod without Hyperspace" (docs/MODS.md; the website's web/mods/hs-needed.mjs has the same rule). The engine
 * turns Hyperspace on whenever the merged mods hold data/hyperspace.xml, which Multiverse ships too; Hyperspace.ftl is
 * Hyperspace's own data (images, fonts, translations, sounds, default settings), which native Hyperspace loads first. A set
 * that uses Hyperspace without it plays with some of that data missing, so it is allowed, with a warning per start.
 */
object HyperspaceRule {
    /** An entry that uses Hyperspace: its hyperspace.xml, or a patch of that file (append, raw append or clobber, Lua script). */
    private val MARKER = Regex("""^data/hyperspace\.(xml(\.(append|rawappend|rawclobber))?|(append|rawappend|rawclobber)\.xml|append\.lua)$""", RegexOption.IGNORE_CASE)

    fun usesHyperspace(names: List<String>): Boolean = names.any { MARKER.matches(it.replace('\\', '/')) }

    /** One mod as the rule sees it. */
    data class Mod(val sha256: String, val title: String?, val marker: Boolean)

    /**
     * Hyperspace.ftl itself: the catalogue's file of mod "hyperspace" by SHA-256 (Verified, whatever its name), or an
     * archive whose own metadata title is "Hyperspace" (another build of it). Never by file name.
     */
    fun isHyperspace(mod: Mod, hyperspaceShas: Set<String>): Boolean =
        mod.sha256 in hyperspaceShas || mod.title.equals("hyperspace", ignoreCase = true)

    /** The first mod (load order) that uses Hyperspace when none of them is Hyperspace.ftl, else null. */
    fun without(mods: List<Mod>, hyperspaceShas: Set<String>): Mod? =
        if (mods.any { isHyperspace(it, hyperspaceShas) }) null else mods.firstOrNull { it.marker }
}
