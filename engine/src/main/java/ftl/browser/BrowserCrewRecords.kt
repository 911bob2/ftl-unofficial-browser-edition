package ftl.browser

import org.jdom2.Element
import xyz.znix.xftl.crew.LivingCrewInfo
import xyz.znix.xftl.crew.Skill

enum class CrewRecordKind(val label: String) {
    REPAIRS("Repairs completed"), KILLS("Combat kills"), EVASIONS("Piloted evasions"),
    JUMPS("Jumps survived"), SKILLS("Skill levels earned")
}

/** Facts follow a crew identity through teleportation, renaming and cloning. */
class BrowserCrewCounters {
    var complete = true
        private set
    private val values = IntArray(CrewRecordKind.entries.size)
    private val learned = HashMap<Skill, Int>()
    operator fun get(kind: CrewRecordKind): Int = values[kind.ordinal]
    fun increment(kind: CrewRecordKind) { values[kind.ordinal]++ }

    fun learned(skill: Skill, old: Float, current: Float) {
        var flags = learned[skill] ?: 0
        if (old < .5f && current >= .5f) flags = flags or 1
        if (old < 1f && current >= 1f) flags = flags or 2
        learned[skill] = flags
        values[CrewRecordKind.SKILLS.ordinal] = learned.values.sumOf { (it and 1) + ((it shr 1) and 1) }
    }

    fun save(): Element = Element("browserCrewCounters").setAttribute("complete", complete.toString()).also { elem ->
        for (kind in CrewRecordKind.entries) elem.setAttribute(kind.name, get(kind).toString())
        for ((skill, flags) in learned) elem.addContent(Element("learned").setAttribute("skill", skill.name).setAttribute("flags", flags.toString()))
    }

    fun load(elem: Element?) {
        complete = elem?.getAttributeValue("complete") == "true"
        for (kind in CrewRecordKind.entries) values[kind.ordinal] = elem?.getAttributeValue(kind.name)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        learned.clear()
        for (entry in elem?.getChildren("learned") ?: emptyList()) {
            val skill = Skill.entries.firstOrNull { it.name == entry.getAttributeValue("skill") } ?: continue
            learned[skill] = (entry.getAttributeValue("flags")?.toIntOrNull() ?: 0) and 3
        }
        values[CrewRecordKind.SKILLS.ordinal] = learned.values.sumOf { (it and 1) + ((it shr 1) and 1) }
    }
}

data class BrowserCrewRecord(val kind: CrewRecordKind, val value: Int, val name: String, val species: String, val female: Boolean) {
    fun save(): Element = Element("crewRecord").setAttribute("kind", kind.name).setAttribute("value", value.toString())
        .setAttribute("name", name).setAttribute("species", species).setAttribute("female", female.toString())
    companion object {
        fun from(kind: CrewRecordKind, crew: LivingCrewInfo) = BrowserCrewRecord(kind, crew.browserCounters[kind], crew.name, crew.race.name, crew.isFemale)
        fun load(elem: Element): BrowserCrewRecord? {
            val kind = CrewRecordKind.entries.firstOrNull { it.name == elem.getAttributeValue("kind") } ?: return null
            val value = elem.getAttributeValue("value")?.toIntOrNull()?.takeIf { it > 0 } ?: return null
            val name = elem.getAttributeValue("name") ?: return null
            val species = elem.getAttributeValue("species") ?: return null
            return BrowserCrewRecord(kind, value, name, species, elem.getAttributeValue("female") == "true")
        }
    }
}
