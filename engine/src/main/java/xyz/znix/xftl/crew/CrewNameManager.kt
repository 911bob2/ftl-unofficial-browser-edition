package xyz.znix.xftl.crew

import xyz.znix.xftl.Datafile
import kotlin.random.Random

/**
 * Stores all the available crew names, from names.xml.
 */
class CrewNameManager(df: Datafile, private val language: String) {
    val names: List<CrewNameList>

    init {
        val allNames = ArrayList<CrewNameList>()

        val root = df.parseXML(df["data/names.xml"]).rootElement

        for (child in root.children) {
            require(child.name == "nameList") { "Found non-nameList element in names.xml root" }

            val listNames = ArrayList<String>()
            val shortVersions = HashMap<String, String>()

            for (nameElem in child.children) {
                require(nameElem.name == "name") { "Found non-name element in nameList (in names.xml)" }
                val name = nameElem.textTrim
                listNames.add(name)

                val shortName = nameElem.getAttributeValue("short")
                if (shortName != null) {
                    shortVersions[name] = shortName
                }
            }

            val isMale = child.getAttributeValue("sex") == "male"
            val listLanguage = child.getAttributeValue("language") ?: "en"

            allNames.add(CrewNameList(listNames, shortVersions, isMale, listLanguage))
        }

        val matchingLanguage = allNames.filter { it.language == language }
        if (matchingLanguage.isNotEmpty()) {
            names = matchingLanguage
        } else {
            names = allNames.filter { it.language == "en" }
        }
    }

    /**
     * The name table the generator draws from (BlueprintManager +0x7c): every
     * name of the selected language's lists once, sorted like the original's
     * std::map<string, bool> (by UTF-8 bytes), with the gender of the last
     * list that names it.
     */
    val flatNames: List<Pair<String, Boolean>> by lazy {
        val merged = HashMap<String, Boolean>()
        for (list in names) {
            for (name in list.names)
                merged[name] = list.isMale
        }
        merged.entries.map { Pair(it.key, it.value) }.sortedWith { a, b -> compareUtf8(a.first, b.first) }
    }

    fun getForGender(male: Boolean?, rand: Random): String {
        val lists = names.filter {
            // If a gender is specified, filter on that
            if (male != null) {
                if (male != it.isMale)
                    return@filter false
            }

            return@filter true
        }

        return lists.random(rand).names.random(rand)
    }

    private fun compareUtf8(a: String, b: String): Int {
        val x = a.encodeToByteArray()
        val y = b.encodeToByteArray()
        val n = minOf(x.size, y.size)
        for (i in 0 until n) {
            val d = (x[i].toInt() and 0xff) - (y[i].toInt() and 0xff)
            if (d != 0)
                return d
        }
        return x.size - y.size
    }

    fun findShort(name: String): String? {
        for (list in names) {
            list.shortVersions[name]?.let { return it }
        }
        return null
    }

    /** CrewMember::SetName's short name, measured with font 6 by [measure]. */
    fun shorten(name: String, measure: (String) -> Int): String = shortenCrewName(name, findShort(name), measure)
}

/**
 * CrewBlueprint::ShortenName (0x0060a1e0): a names.xml short form wins; a name
 * up to 49px is kept; otherwise the last word if under 49px, else the narrowest
 * word (the first of equals), cut until it fits with a trailing '.'. Words are
 * split on spaces and the middle-dot separators (U+00B7, U+30FB). [measure] is
 * freetype::measurePrintLines (0x595dc0) with font 6, which ShortenName calls at
 * 0x60a295 and compares (truncated) with 49 ([SILFontLoader.getExtentWidth]).
 */
fun shortenCrewName(name: String, short: String?, measure: (String) -> Int): String {
    if (short != null) return short
    if (measure(name) <= MAX_SHORT_NAME_WIDTH) return name
    val words = name.split(' ', '\u00b7', '\u30fb').filter { it.isNotEmpty() }
    if (words.isEmpty()) return name
    if (measure(words.last()) < MAX_SHORT_NAME_WIDTH) return words.last()
    var shortest = words.first()
    var shortestWidth = Int.MAX_VALUE
    for (word in words) {
        val width = measure(word)
        if (width < shortestWidth) {
            shortest = word
            shortestWidth = width
        }
    }
    if (shortestWidth <= MAX_SHORT_NAME_WIDTH) return shortest
    val dot = measure("..") - measure(".")
    while (shortest.isNotEmpty() && measure(shortest) > MAX_SHORT_NAME_WIDTH - dot)
        shortest = shortest.dropLast(1)
    return "$shortest."
}

private const val MAX_SHORT_NAME_WIDTH = 49

class CrewNameList(
    val names: List<String>,

    /**
     * This list's mapping between full and shortened names.
     *
     * This is used for names that have custom shortened versions.
     */
    val shortVersions: Map<String, String>,

    val isMale: Boolean,
    val language: String
)
