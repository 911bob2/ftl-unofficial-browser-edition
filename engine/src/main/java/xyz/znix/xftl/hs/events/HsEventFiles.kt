// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Gameplay/World/CustomEvents.cpp (GPL-3.0-or-later; the browser engine
// is AGPL-3.0, see docs/HYPERSPACE.md). Load-order glue between the engine's EventManager and HsEventParser.
package xyz.znix.xftl.hs.events

import org.jdom2.Element
import xyz.znix.xftl.BlueprintList
import xyz.znix.xftl.BlueprintManager
import xyz.znix.xftl.Datafile
import xyz.znix.xftl.hs.HS

/**
 * When the HS parts of the event data are read, relative to the vanilla event files (CApp::OnInit 0x4037f0):
 *
 * 1. PreloadResources -> Global::PreInitializeResources hook: hyperspace.xml `<defaults>` and the early `<events>`
 *    children (eventFile, named loadEventList, triggeredEventBox, timerSounds, warningMessage, beaconType), then
 *    ReadCustomEventFiles: `data/events_hyperspace.xml` and `data/events_<eventFile>.xml`, before the vanilla files.
 * 2. The vanilla event files (EventsParser::LoadEvents).
 * 3. Later (LoadAchievementDescriptions hook): the rest of `<events>` (ParseCustomEventNode), then PostProcess.
 *
 * A bad number ends a pass (HsConfig): each step reads hyperspace.xml only as far as its pass got
 * ([HsConfig.passRoot]); after an early-pass error HS reads no event files, after a main-pass error no PostProcess.
 */
object HsEventFiles {
    /** Steps 1: returns the HS event files in load order (missing ones skipped, as LoadFile returns null). */
    fun beforeVanilla(hs: HsEventParser, fullRoot: Element?, df: Datafile): List<String> {
        val files = ArrayList<String>()
        val cfg = HS.config
        val earlyRoot = cfg?.passRoot(fullRoot, true) ?: fullRoot
        val hyperspaceRoot = cfg?.passRoot(fullRoot, false) ?: fullRoot
        // Event buttons are parsed with the events (render: hs.render.HsEventButtons) and saved with the run.
        xyz.znix.xftl.hs.render.HsEventButtons.resetDefs { df.getOrNull(it) != null }
        xyz.znix.xftl.hs.render.HsEventButtons.registerSave()
        xyz.znix.xftl.hs.render.HsBackgroundObjects.resetDefs()
        xyz.znix.xftl.hs.render.HsBackgroundObjects.registerSave()
        xyz.znix.xftl.hs.render.HsCredits.registerSave()
        HsFinalBoss.registerSave()
        xyz.znix.xftl.hs.save.HsSpaceId.registerSave()
        // The run block (variables, queues, aliases, triggered events, seeds) and the profile block (meta variables).
        HsEvents.registerSaves { key, run, writer, reader ->
            xyz.znix.xftl.hs.save.HsSave.register(key, if (run) xyz.znix.xftl.hs.save.HsSave.Scope.RUN else xyz.znix.xftl.hs.save.HsSave.Scope.PROFILE, writer, reader)
        }
        HsCustomFleet.reset() // the fleetDefs come with ParseCustomEventNode (step 3)
        xyz.znix.xftl.hs.sector.HsSeeds.readConfig(hyperspaceRoot)
        // Hyperspace.playerVariables / metaVariables for the Lua bridge are these maps (one store, no copies).
        xyz.znix.xftl.hs.lua.LuaVariables.player = HsEvents.playerVariables
        xyz.znix.xftl.hs.lua.LuaVariables.meta = HsEvents.metaVariables
        // CustomRewardsManager::ParseRewardsNode for each <rewards> (Resources.cpp).
        HsRewards.reset()
        hyperspaceRoot?.getChildren("rewards")?.forEach { HsRewards.parse(it) }
        if (earlyRoot != null) {
            hs.readDefaults(earlyRoot)
            // Every `<events>` node in file order: HS's early pass visits each root node (an add-on's hyperspace.xml
            // append often brings a second `<events>` block with its own eventFiles).
            for (events in earlyRoot.getChildren("events")) hs.earlyParse(events)
        }
        // ReadCustomEventFiles comes after the early loop, inside its try.
        if (cfg != null && cfg.earlyStop >= 0) return files
        if (df.getOrNull("data/events_hyperspace.xml") != null) files += "data/events_hyperspace.xml"
        for (name in hs.eventFiles) {
            val file = "data/events_$name.xml"
            if (df.getOrNull(file) != null) files += file
            else HS.unsupported("file", "hyperspace.xml eventFile", file)
        }
        return files
    }

    /** Step 3, once the event files and blueprints are loaded. */
    fun afterEvents(hs: HsEventParser, fullRoot: Element?, bp: BlueprintManager) {
        val cfg = HS.config
        val hyperspaceRoot = cfg?.passRoot(fullRoot, false) ?: fullRoot
        hyperspaceRoot?.getChildren("events")?.forEach { hs.parseCustomEventNode(it) }
        hs.resolveReqLists { list -> (bp.getOrNull(list) as? BlueprintList)?.names?.toList() ?: emptyList() }
        // PostProcessCustomEvents follows the main loop inside its try.
        if (cfg == null || cfg.mainStop < 0) hs.postProcess()
        // A new profile: WipeProfile's initial meta variables (the fixed ones; ranged ones need a game, see resetProfile).
        if (HsEvents.profileNeedsReset) {
            HsEvents.profileNeedsReset = false
            HsEvents.resetProfile(null, hs)
        }
    }

    /** EventsParser::ProcessBaseNode ignores root nodes it doesn't know (`mod:` leftovers, HS-only nodes). */
    fun unknownRootNode(file: String, elem: Element) {
        HS.unsupported("xml", file, elem.name)
    }

    /** A reference to something the content does not define (the original reads it as empty). */
    fun missing(kind: String, where: String, name: String) {
        HS.unsupported("missing-$kind", where, name)
    }

    /** A root node our loader could not read (the original would have read it): skipped and reported. */
    fun badNode(file: String, elem: Element, e: RuntimeException) {
        val id = elem.getAttributeValue("name") ?: ""
        HS.unsupported("parse", file, "${elem.name} $id: ${e.javaClass.simpleName}: ${e.message}")
    }
}
