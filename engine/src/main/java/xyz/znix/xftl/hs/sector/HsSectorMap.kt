// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Gameplay/World/CustomSectors.cpp and CustomSectors.h (GPL-3.0-or-later;
// the browser engine is AGPL-3.0, see docs/HYPERSPACE.md).
package xyz.znix.xftl.hs.sector

import ftl.browser.NativeRandom
import org.jdom2.Element
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.sector.SectorType

/**
 * `<sectorMap>` in sector_data.xml (CustomSectorManager): the sector lists the tree draws from per level, replacing
 * the CIVILIAN/HOSTILE/NEBULA class of each node.
 */
class HsSectorMap {
    class GeneratorList(val maxSector: Int) {
        var totalChance = 0
        val sectorTypes = ArrayList<Pair<Int, String>>()
    }

    val generators = ArrayList<GeneratorList>()

    /** ParseCustomSectorMapNode: a later `<sectorMap>` replaces the generators. `minSector` is not read by HS. */
    fun parse(node: Element) {
        generators.clear()
        for (child in node.children) {
            if (child.name != "sectorList") continue
            val list = GeneratorList(child.getAttributeValue("maxSector")?.let { it.trim().toIntOrNull() ?: bad(it) } ?: -1)
            for (entry in child.children) {
                val chance = entry.getAttributeValue("chance")?.let { it.trim().toIntOrNull() ?: bad(it) } ?: 1
                list.totalChance += chance
                list.sectorTypes += chance to entry.name
            }
            if (list.totalChance > 0) generators += list
        }
    }

    private fun bad(text: String): Int {
        HS.unsupported("value", "sectorMap", text)
        return 0
    }

    /** GetSectorType: the first list covering [level] draws one of its sector lists (`random32() % total`). */
    fun getSectorType(level: Int): String? {
        for (generator in generators) {
            if (generator.maxSector == -1 || generator.maxSector >= level) {
                var rng = NativeRandom.next31() % generator.totalChance
                for ((chance, name) in generator.sectorTypes) {
                    rng -= chance
                    if (rng < 0) return name
                }
                return null
            }
        }
        return null
    }

    companion object {
        /** HS's sector "type" number (Sector::type): 0 civilian, 1 hostile, 2 nebula, 4 unknown. */
        const val TYPE_UNKNOWN = 4

        /**
         * EventGenerator::GetSectorDescriptionCustom: the descriptions of the named list ([lists], "OVERRIDE_" first
         * with the DLC) that are allowed at [level] (minSector, unique use, HS maxSector), one picked with
         * `random32() % n`, or null (the caller takes STANDARD_SPACE) when none is left. The name draw is made by the
         * caller, as in the vanilla path.
         */
        fun pickDescription(
            listName: String, level: Int, lists: Map<String, List<String>>, types: Map<String, SectorType>,
            used: Set<SectorType>, dlc: Boolean, maxSector: (String) -> Int
        ): SectorType? {
            val base = lists[listName]
            if (base == null) {
                // HS logs "INVALID SECTOR TYPE" and returns an empty description; STANDARD_SPACE is used here.
                HS.unsupported("sector-list", "sector_data.xml", listName)
                return null
            }
            val names = (if (dlc) lists["OVERRIDE_$listName"] else null) ?: base
            val candidates = ArrayList<SectorType>()
            for (name in names) {
                val type = types[name]
                if (type == null) {
                    HS.unsupported("missing-sector", listName, name)
                    continue
                }
                if (level < type.minSector || type in used) continue
                val max = maxSector(name)
                if (max != -1 && level > max) continue
                candidates += type
            }
            if (candidates.isEmpty()) return null
            return candidates[NativeRandom.next31() % candidates.size]
        }

        /** CustomSectorManager::SectorIsType: [name] is in the list [type] ("OVERRIDE_" list first with the DLC). */
        fun sectorIsType(name: String, type: String, lists: Map<String, List<String>>, dlc: Boolean): Boolean {
            val base = lists[type] ?: return false
            val names = (if (dlc) lists["OVERRIDE_$type"] else null) ?: base
            return name in names
        }
    }
}
