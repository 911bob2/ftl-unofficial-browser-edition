// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 lua/modules/hyperspace.i (LocationEvent, LocationEvent::Choice,
// ChoiceReq, ChoiceBox, ChoiceText, ResourceEvent, EventGenerator members) (the browser engine is AGPL-3.0, see
// docs/HYPERSPACE.md).
package xyz.znix.xftl.hs.lua

import xyz.znix.xftl.Blueprint
import xyz.znix.xftl.hs.events.HsEventsLua
import xyz.znix.xftl.hs.events.HsFlow
import xyz.znix.xftl.hs.events.systemId
import xyz.znix.xftl.sector.Beacon
import xyz.znix.xftl.sector.CreatedChoice
import xyz.znix.xftl.sector.CreatedEvent
import xyz.znix.xftl.sector.EventText
import xyz.znix.xftl.sector.FleetBackground
import xyz.znix.xftl.hs.lua.LuaApi as A
import xyz.znix.xftl.hs.lua.LuaArgs as arg
import xyz.znix.xftl.hs.lua.LuaPush as ret

/**
 * WP6 events: the Lua view of a created event. A LocationEvent is the engine's [CreatedEvent]; its members read what the
 * created event holds (the template's flags, the text and resources the creation drew) and write through to it where the
 * engine reads them again: the text, the choices (their text, requirement, hidden-reward flag and event, AddChoice and
 * RemoveChoice) and the resources (fuel, missiles, drones, scrap, weapon, drone, augment, boarders). The other members
 * (environment, store, quest, ...) are consumed by the engine before CreateChoiceBox - as in HS, where UpdateLocation has
 * already acted on them when PRE_CREATE_CHOICEBOX runs - so a script's write is kept for it to read back
 * ([CreatedEvent.luaExtra]) and changes nothing else.
 */
object LuaAdaptersWP6Events {
    private fun game() = LuaAdapters.inGame()

    // ---------------------------------------------------------------------------------------------------- views

    /** A ResourceEvent of a created event: slot 0 is `stuff`, 1 is `reward`, 2 a ChoiceBox's own `rewards`. */
    /** A ResourceEvent of [ev]; [box]: the choice box's copy (ChoiceBox.rewards, ChoiceText.rewards), whose scrap shows the
     * scrap augments (WorldManager::CreateChoiceBox hook, CustomAugments.cpp). */
    class ResView(@JvmField val ev: CreatedEvent, @JvmField val slot: Int, @JvmField val box: Boolean = false) : LuaTyped { override val luaType get() = A.T_ResourceEvent }

    /** The box copy's scrap: scrap + scrap * the choice-text scrap fraction, unless the event disables scrap augments
     * (oracle sweep-world-yscrap with showScrapCollectorScrap: 20 -> 22, 30 -> 33). */
    private fun boxScrap(ev: CreatedEvent, scrap: Int): Int {
        if (scrap <= 0) return scrap
        val g = game() ?: return scrap
        if (xyz.znix.xftl.hs.events.HsFlow.customFor(g, ev)?.disableScrapAugments == true) return scrap
        val v = xyz.znix.xftl.hs.ship.HsAugments.choiceTextScrap(g.player)
        return if (v == 0f) scrap else (scrap.toFloat() + scrap.toFloat() * v).toInt()
    }

    /** A TextString of the engine: the event's text, a choice's text, or a stored one. */
    class TextView(@JvmField val get: () -> String, @JvmField val set: (String) -> Unit) : LuaTyped {
        @JvmField var literal = true
        override val luaType get() = A.T_TextString
    }

    /** LocationEvent::Choice of a created event. */
    class ChoiceView(@JvmField val owner: CreatedEvent, @JvmField val choice: CreatedChoice) : LuaTyped { override val luaType get() = A.T_Choice }

    class ReqView(@JvmField val choice: CreatedChoice) : LuaTyped { override val luaType get() = A.T_ChoiceReq }
    class BoardView(@JvmField val ev: CreatedEvent) : LuaTyped { override val luaType get() = A.T_BoardingEvent }
    class ChoiceTextView(@JvmField val box: HsEventsLua.ChoiceBoxText, @JvmField val index: Int) : LuaTyped { override val luaType get() = A.T_ChoiceText }
    object EventGeneratorRef : LuaTyped { override val luaType get() = A.T_EventGenerator }

    private fun extra(ev: CreatedEvent): HashMap<Int, Any?> = ev.luaExtra ?: HashMap<Int, Any?>().also { ev.luaExtra = it }
    private const val SLOT = 100000

    /** The ChoiceBox's per-box storage for members the window has no state for. */
    private val boxExtra = xyz.znix.xftl.hs.lua.HsRunMaps.any(java.util.IdentityHashMap<Any, HashMap<Int, Any?>>())
    private fun boxMap(box: Any) = boxExtra.getOrPut(box) { HashMap() }

    // ---------------------------------------------------------------------------------------------------- registration helpers

    private fun missing(a: Int): Nothing = throw LuaError("Hyperspace Lua API not available in the browser engine: " + A.attrName(a))

    /** Registers [get]/[set] for objects of type [T]; any other object goes to whatever was registered before. */
    private inline fun <reified T> numAttr(a: Int, crossinline get: (T) -> Double, noinline set: ((T, Double) -> Unit)? = null) {
        val pg = LuaReg.numGet[a]; val ps = LuaReg.numSet[a]
        LuaReg.numGet[a] = NumGet { o -> if (o is T) get(o) else (pg ?: missing(a)).get(o) }
        LuaReg.numSet[a] = NumSet { o, v ->
            if (o is T) (set ?: throw LuaError("This variable is immutable")).invoke(o, v) else (ps ?: missing(a)).set(o, v)
        }
    }

    private inline fun <reified T> boolAttr(a: Int, crossinline get: (T) -> Boolean, noinline set: ((T, Boolean) -> Unit)? = null) =
        numAttr<T>(a, { if (get(it)) 1.0 else 0.0 }, set?.let { s -> { o, v -> s(o, v != 0.0) } })

    private inline fun <reified T> strAttr(a: Int, crossinline get: (T) -> String, noinline set: ((T, String) -> Unit)? = null) {
        val pg = LuaReg.strGet[a]; val ps = LuaReg.strSet[a]
        LuaReg.strGet[a] = StrGet { o -> if (o is T) get(o) else (pg ?: missing(a)).get(o) }
        LuaReg.strSet[a] = StrSet { o, v ->
            if (o is T) (set ?: throw LuaError("This variable is immutable")).invoke(o, v) else (ps ?: missing(a)).set(o, v)
        }
    }

    private inline fun <reified T> objAttr(a: Int, crossinline get: (T) -> Any?, noinline set: ((T, Any?) -> Unit)? = null) {
        val pg = LuaReg.objGet[a]; val ps = LuaReg.objSet[a]
        LuaReg.objGet[a] = ObjGet { o -> if (o is T) get(o) else (pg ?: missing(a)).get(o) }
        LuaReg.objSet[a] = ObjSet { o, v ->
            if (o is T) (set ?: throw LuaError("This variable is immutable")).invoke(o, v) else (ps ?: missing(a)).set(o, v)
        }
    }

    // ---------------------------------------------------------------------------------------------------- ResourceEvent

    private fun xKey(r: ResView, a: Int) = r.slot * SLOT + a
    private fun xNum(r: ResView, a: Int, def: Double): Double = (extra(r.ev)[xKey(r, a)] as? Double) ?: def
    private fun xPut(r: ResView, a: Int, v: Any?) { extra(r.ev)[xKey(r, a)] = v }

    private fun resNum(a: Int, derived: (ResView) -> Double) {
        numAttr<ResView>(a, { r -> (extra(r.ev)[xKey(r, a)] as? Double) ?: if (r.slot == 0) derived(r) else 0.0 }, { r, v -> xPut(r, a, v) })
    }

    private fun resBool(a: Int, derived: (ResView) -> Boolean) {
        boolAttr<ResView>(a, { r -> (extra(r.ev)[xKey(r, a)] as? Double)?.let { it != 0.0 } ?: (r.slot == 0 && derived(r)) },
            { r, v -> xPut(r, a, if (v) 1.0 else 0.0) })
    }

    private fun installResource() {
        LuaStructs.register(A.T_ResourceEvent)
        LuaStructs.register(A.T_TextString)
        // The engine-backed numbers: the created event holds them; a write is applied where the event is applied.
        class Field(val a: Int, val get: (CreatedEvent) -> Int, val set: (CreatedEvent, Int) -> Unit)
        for (f in listOf(
            Field(A.A_ResourceEvent_missiles, { e -> e.missiles }, { e, v -> e.missiles = v }),
            Field(A.A_ResourceEvent_fuel, { e -> e.fuel }, { e, v -> e.fuel = v }),
            Field(A.A_ResourceEvent_drones, { e -> e.droneParts }, { e, v -> e.droneParts = v }),
            Field(A.A_ResourceEvent_scrap, { e -> e.scrap }, { e, v -> e.scrap = v }),
        )) {
            numAttr<ResView>(f.a, { r ->
                if (r.slot != 0) xNum(r, f.a, 0.0)
                else if (r.box && f.a == A.A_ResourceEvent_scrap) boxScrap(r.ev, f.get(r.ev)).toDouble()
                else f.get(r.ev).toDouble()
            }, { r, v ->
                if (r.slot == 0) f.set(r.ev, v.toInt()) else xPut(r, f.a, v)
            })
        }
        resNum(A.A_ResourceEvent_crew, { r -> r.ev.crewAmount().toDouble() })
        resBool(A.A_ResourceEvent_traitor) { r -> false }
        resBool(A.A_ResourceEvent_cloneable) { r -> r.ev.template.removedCrew.firstOrNull()?.clone == true }
        objAttr<ResView>(A.A_ResourceEvent_cloneText, { r -> LuaViews.cached(r, A.A_ResourceEvent_cloneText) { _ -> stored(r.ev, r.slot * SLOT + A.A_ResourceEvent_cloneText) } },
            { r, v -> copyText(v, stored(r.ev, r.slot * SLOT + A.A_ResourceEvent_cloneText)) })
        // crewType is a field of its own in the oracle: "" for an event that hands out crew (crewBlue has the race)
        strAttr<ResView>(A.A_ResourceEvent_crewType, { r -> (extra(r.ev)[xKey(r, A.A_ResourceEvent_crewType)] as? String) ?: "" },
            { r, v -> xPut(r, A.A_ResourceEvent_crewType, v) })
        // the items: engine blueprints
        class Item(val a: Int, val get: (CreatedEvent) -> Blueprint?, val set: (CreatedEvent, Blueprint?) -> Unit)
        for (it in listOf(
            Item(A.A_ResourceEvent_weapon, { e -> e.weapon }, { e, b -> e.weapon = b }),
            Item(A.A_ResourceEvent_drone, { e -> e.drone }, { e, b -> e.drone = b }),
            Item(A.A_ResourceEvent_augment, { e -> e.augment }, { e, b -> e.augment = b }),
        )) {
            objAttr<ResView>(it.a, { r -> if (r.slot == 0) it.get(r.ev) else extra(r.ev)[xKey(r, it.a)] }, { r, v ->
                if (v != null && v !is Blueprint) throw LuaError("expected a blueprint")
                if (r.slot == 0) it.set(r.ev, v as Blueprint?) else extra(r.ev)[xKey(r, it.a)] = v
            })
        }
        // crewBlue is a CrewBlueprint (an empty one without crew)
        LuaStructs.register(A.T_CrewBlueprint)
        objAttr<ResView>(A.A_ResourceEvent_crewBlue, { r ->
            (extra(r.ev)[xKey(r, A.A_ResourceEvent_crewBlue)]) ?: (if (r.slot == 0) r.ev.crew?.race else null)
                ?: LuaViews.cached(r, A.A_ResourceEvent_crewBlue) { _ -> LuaStructs.create(A.T_CrewBlueprint) }
        }, { r, v -> xPut(r, A.A_ResourceEvent_crewBlue, v) })
        // Defaults as the oracle reads them: a plain event holds -1 in systemId (and, as `stuff`, in upgradeId); the
        // counts, intruders and hullDamage of the HS fields are their own (the XML's `<damage>`, `<weapon>` and
        // `<boarders>` do not set them).
        numAttr<ResView>(A.A_ResourceEvent_systemId, { r -> (extra(r.ev)[xKey(r, A.A_ResourceEvent_systemId)] as? Double) ?: -1.0 },
            { r, v -> xPut(r, A.A_ResourceEvent_systemId, v) })
        resNum(A.A_ResourceEvent_weaponCount, { r -> 0.0 })
        resNum(A.A_ResourceEvent_droneCount, { r -> 0.0 })
        resBool(A.A_ResourceEvent_steal) { r -> r.ev.template.itemsModifySteal }
        resBool(A.A_ResourceEvent_intruders) { r -> false }
        resNum(A.A_ResourceEvent_fleetDelay, { r -> 0.0 })
        resNum(A.A_ResourceEvent_hullDamage, { r -> 0.0 })
        resNum(A.A_ResourceEvent_upgradeAmount, { r -> (r.ev.template.systemUpgrades.firstOrNull()?.amount ?: 0).toDouble() })
        numAttr<ResView>(A.A_ResourceEvent_upgradeId, { r ->
            (extra(r.ev)[xKey(r, A.A_ResourceEvent_upgradeId)] as? Double)
                ?: if (r.slot == 0) (r.ev.template.systemUpgrades.firstOrNull()?.let { systemIdOf(it.system) }?.toDouble() ?: -1.0) else 0.0
        }, { r, v -> xPut(r, A.A_ResourceEvent_upgradeId, v) })
        resNum(A.A_ResourceEvent_upgradeSuccessFlag, { r -> 0.0 })
        strAttr<ResView>(A.A_ResourceEvent_removeItem, { r ->
            (extra(r.ev)[xKey(r, A.A_ResourceEvent_removeItem)] as? String) ?: if (r.slot == 0) (r.ev.template.removedStuff.firstOrNull() ?: "") else ""
        }, { r, v -> xPut(r, A.A_ResourceEvent_removeItem, v) })
    }

    private fun systemIdOf(name: String): Int = systemId(name)

    private fun CreatedEvent.crewAmount(): Int = when {
        crew != null -> maxOf(1, template.crewMemberAmount)
        template.removedCrew.isNotEmpty() -> if (hsCrewLossCleared) 0 else -template.removedCrew.size
        else -> 0
    }

    // ---------------------------------------------------------------------------------------------------- TextString

    private fun stored(ev: CreatedEvent, key: Int): TextView =
        (extra(ev)[key] as? TextView) ?: TextView({ (extra(ev)[-key] as? String) ?: "" }, { extra(ev)[-key] = it }).also { extra(ev)[key] = it }

    private fun copyText(from: Any?, to: TextView) {
        when (from) {
            is TextView -> { to.set(from.get()); to.literal = from.literal }
            is LuaStruct -> { to.set(from.values[A.A_TextString_data] as? String ?: ""); to.literal = from.values[A.A_TextString_isLiteral] != false }
            else -> throw LuaError("expected a TextString")
        }
    }

    private fun installText() {
        strAttr<TextView>(A.A_TextString_data, { it.get() }, { t, v -> t.set(v) })
        boolAttr<TextView>(A.A_TextString_isLiteral, { it.literal }, { t, v -> t.literal = v })
        val prev = LuaReg.methods[A.M_TextString_GetText]
        LuaReg.method(A.M_TextString_GetText, LuaMethod { n ->
            val t = arg.obj(1)
            if (t is TextView) ret.str(if (t.literal) t.get() else LuaAdaptersMore.translate(t.get())) else (prev ?: throw LuaError("TextString:GetText unavailable")).call(n)
        })
    }

    // ---------------------------------------------------------------------------------------------------- LocationEvent

    private fun envInt(e: CreatedEvent): Double = when (e.template.environment) {
        null -> 0.0
        Beacon.EnvironmentType.NORMAL -> 0.0
        Beacon.EnvironmentType.ASTEROID -> 1.0
        Beacon.EnvironmentType.SUN -> 2.0
        Beacon.EnvironmentType.NEBULA -> 3.0
        Beacon.EnvironmentType.ION_STORM -> 4.0
        Beacon.EnvironmentType.PULSAR -> 5.0
        Beacon.EnvironmentType.PDS -> 6.0
    }

    private fun fleetInt(e: CreatedEvent): Double = when (e.template.fleetBackground) {
        null -> 0.0
        FleetBackground.REBEL -> 1.0
        FleetBackground.FEDERATION -> 2.0
        FleetBackground.BOTH -> 3.0
    }

    private fun eNum(a: Int, derived: (CreatedEvent) -> Double, set: ((CreatedEvent, Double) -> Unit)? = null) {
        numAttr<CreatedEvent>(a, { e -> (extra(e)[a] as? Double) ?: derived(e) }, { e, v -> set?.invoke(e, v); extra(e)[a] = v })
    }

    private fun eBool(a: Int, derived: (CreatedEvent) -> Boolean) {
        boolAttr<CreatedEvent>(a, { e -> (extra(e)[a] as? Double)?.let { it != 0.0 } ?: derived(e) }, { e, v -> extra(e)[a] = if (v) 1.0 else 0.0 })
    }

    private fun eStr(a: Int, derived: (CreatedEvent) -> String, set: ((CreatedEvent, String) -> Unit)? = null) {
        strAttr<CreatedEvent>(a, { e -> (extra(e)[a] as? String) ?: derived(e) }, { e, v -> set?.invoke(e, v); extra(e)[a] = v })
    }

    /** An event without choices has FTL's implicit "continue" one: text id "continue", no event, the default requirement. */
    private fun implicitContinue(e: CreatedEvent): CreatedChoice? {
        if (e.hsContinueRemoved || e.template.choices.isNotEmpty()) return null
        return (extra(e)[-7] as? CreatedChoice) ?: CreatedChoice(
            xyz.znix.xftl.sector.Choice(EventText("continue"), lazyOf(e.template), org.jdom2.Element("choice"), ""), EventText("continue"), null
        ).also { it.isContinueChoice = true; extra(e)[-7] = it }
    }

    private fun choiceViews(e: CreatedEvent): List<Any?> =
        (listOfNotNull(implicitContinue(e)) + e.choices).map { c -> LuaViews.cached(c, A.T_Choice) { _ -> ChoiceView(e, c) } }

    private fun eventText(e: CreatedEvent): TextView = LuaViews.cached(e, A.A_LocationEvent_text) { _ ->
        TextView({ (e.text?.localised ?: "") + (e.hsExtraText ?: "") }, { v ->
            e.text = EventText(v, null, e.text?.planet, e.text?.back)
            if (e.hsExtraText != null) e.hsExtraText = ""
        })
    }

    private fun installLocationEvent() {
        objAttr<CreatedEvent>(A.A_LocationEvent_text, { e -> eventText(e) }, { e, v -> copyText(v, eventText(e)) })
        objAttr<CreatedEvent>(A.A_LocationEvent_stuff, { e -> LuaViews.cached(e, A.A_LocationEvent_stuff) { _ -> ResView(e, 0) } })
        objAttr<CreatedEvent>(A.A_LocationEvent_reward, { e -> LuaViews.cached(e, A.A_LocationEvent_reward) { _ -> ResView(e, 1) } })
        objAttr<CreatedEvent>(A.A_LocationEvent_boarders, { e -> LuaViews.cached(e, A.A_LocationEvent_boarders) { _ -> BoardView(e) } })
        eNum(A.A_LocationEvent_environment, ::envInt)
        // HS reads 2 ("all") for every event but an ASB's own target
        eNum(A.A_LocationEvent_environmentTarget, { e -> if (e.template.environment == Beacon.EnvironmentType.PDS) e.template.browserPdsTarget.toDouble() else 2.0 })
        eBool(A.A_LocationEvent_store) { e -> e.template.isStore }
        eBool(A.A_LocationEvent_gap_ex_cleared) { false }
        eNum(A.A_LocationEvent_fleetPosition, ::fleetInt)
        // `<beacon/>` does not set LocationEvent::beacon (the oracle reads false for it)
        eBool(A.A_LocationEvent_beacon) { false }
        eBool(A.A_LocationEvent_reveal_map) { e -> e.template.revealMap }
        eBool(A.A_LocationEvent_distressBeacon) { e -> e.template.isDistressBeacon }
        eBool(A.A_LocationEvent_repair) { e -> e.template.isLastStandRepair }
        eNum(A.A_LocationEvent_modifyPursuit, { e -> e.template.modifyPursuit.toDouble() })
        eStr(A.A_LocationEvent_quest, { e -> e.template.questName ?: "" })
        eStr(A.A_LocationEvent_spaceImage, { e -> e.text?.back ?: e.template.backImg?.name ?: "BACKGROUND" })
        eStr(A.A_LocationEvent_planetImage, { e -> e.text?.planet ?: e.template.planetImg?.name ?: "PLANET" })
        strAttr<CreatedEvent>(A.A_LocationEvent_eventName, { e -> (extra(e)[A.A_LocationEvent_eventName] as? String) ?: (e.template.hsName ?: "") },
            { e, v -> extra(e)[A.A_LocationEvent_eventName] = v })
        eNum(A.A_LocationEvent_unlockShip, { e -> e.template.unlockShip?.toDoubleOrNull() ?: -1.0 })
        objAttr<CreatedEvent>(A.A_LocationEvent_unlockShipText, { e -> LuaViews.cached(e, A.A_LocationEvent_unlockShipText) { _ -> stored(e, A.A_LocationEvent_unlockShipText) } },
            { e, v -> copyText(v, stored(e, A.A_LocationEvent_unlockShipText)) })
        eBool(A.A_LocationEvent_secretSector) { e -> e.template.secretSector }
        objAttr<CreatedEvent>(A.A_LocationEvent_choices, { e ->
            LuaViews.cached(e, A.A_LocationEvent_choices) { _ -> ListView(A.T_vector_locationEventChoice, A.T_Choice) { choiceViews(e) } }
        })

        LuaReg.method(A.M_LocationEvent_GetChoices, LuaMethod {
            val e = arg.self<CreatedEvent>(1)
            ret.obj(ListView(A.T_vector_p_locationEventChoice, A.T_Choice) { choiceViews(e) }, A.T_vector_p_locationEventChoice)
        })
        val add = LuaMethod { n ->
            val e = arg.self<CreatedEvent>(1)
            val target = arg.obj(2) as? CreatedEvent ?: throw LuaError("AddChoice: expected a LocationEvent")
            val text = arg.str(3) ?: ""
            val req = arg.obj(4)
            val hidden = n >= 5 && arg.bool(5)
            val c = CreatedChoice.added(EventText(text), target)
            val def = c.template
            def.hidden = hidden
            when (req) {
                is ReqView -> { val s = req.choice.template; applyReq(def, s.req ?: "", s.minLevel ?: 0, s.maxLevel ?: 0, s.maxGroup ?: 0, s.blue) }
                is LuaStruct -> applyReq(def, req.values[A.A_ChoiceReq_object] as? String ?: "", num(req, A.A_ChoiceReq_min_level),
                    num(req, A.A_ChoiceReq_max_level), num(req, A.A_ChoiceReq_max_group), req.values[A.A_ChoiceReq_blue] == true)
                else -> throw LuaError("AddChoice: expected a ChoiceReq")
            }
            c.blueSet = true
            e.choices.add(c)
            0
        }
        LuaReg.method(A.M_LocationEvent_AddChoice__SWIG_0, add)
        LuaReg.method(A.M_LocationEvent_AddChoice__SWIG_1, add)
        LuaReg.method(A.M_LocationEvent_RemoveChoice, LuaMethod {
            val e = arg.self<CreatedEvent>(1)
            var i = arg.int(2)
            if (implicitContinue(e) != null) {
                // the implicit continue choice is the first of the list
                if (i == 0) { e.hsContinueRemoved = true; return@LuaMethod ret.bool(true) }
                i--
            }
            if (i < 0 || i >= e.choices.size) ret.bool(false) else { e.choices.removeAt(i); ret.bool(true) }
        })
    }

    private fun num(s: LuaStruct, a: Int) = (s.values[a] as? Double)?.toInt() ?: 0

    /** ChoiceReq -> the engine's choice fields: an empty object is no requirement; the values are the raw ints. */
    private fun applyReq(def: xyz.znix.xftl.sector.Choice, obj: String, min: Int, max: Int, group: Int, blue: Boolean) {
        def.req = obj.ifEmpty { null }
        def.minLevel = min
        def.maxLevel = max
        def.maxGroup = group
        def.blue = blue
    }

    // ---------------------------------------------------------------------------------------------------- Choice, ChoiceReq

    private fun installChoice() {
        objAttr<ChoiceView>(A.A_Choice_event, { c -> c.choice.event }, { c, v ->
            c.choice.event = v as? CreatedEvent ?: throw LuaError("expected a LocationEvent")
        })
        objAttr<ChoiceView>(A.A_Choice_text, { c ->
            LuaViews.cached(c.choice, A.A_Choice_text) { _ -> TextView({ c.choice.text.localised }, { v -> c.choice.text = EventText(v) }).also { t -> t.literal = !c.choice.isContinueChoice } }
        }, { c, v -> copyText(v, LuaViews.cached(c.choice, A.A_Choice_text) { _ -> TextView({ c.choice.text.localised }, { x -> c.choice.text = EventText(x) }) }) })
        objAttr<ChoiceView>(A.A_Choice_requirement, { c -> LuaViews.cached(c.choice, A.A_Choice_requirement) { _ -> ReqView(c.choice) } },
            { c, v ->
                val d = c.choice.ownTemplate()
                c.choice.blueSet = true
                when (v) {
                    is ReqView -> { val s = v.choice.template; applyReq(d, s.req ?: "", s.minLevel ?: 0, s.maxLevel ?: 0, s.maxGroup ?: 0, s.blue) }
                    is LuaStruct -> applyReq(d, v.values[A.A_ChoiceReq_object] as? String ?: "", num(v, A.A_ChoiceReq_min_level),
                        num(v, A.A_ChoiceReq_max_level), num(v, A.A_ChoiceReq_max_group), v.values[A.A_ChoiceReq_blue] == true)
                    else -> throw LuaError("expected a ChoiceReq")
                }
            })
        boolAttr<ChoiceView>(A.A_Choice_hiddenReward, { c -> c.choice.template.hidden }, { c, v -> c.choice.ownTemplate().hidden = v })

        LuaStructs.register(A.T_ChoiceReq)
        // ChoiceReq(): a standalone requirement the script fills and hands to AddChoice / Choice.requirement
        LuaReg.method(A.M_new_ChoiceReq, LuaMethod { ret.obj(LuaStructs.create(A.T_ChoiceReq), A.T_ChoiceReq) })
        strAttr<ReqView>(A.A_ChoiceReq_object, { r -> r.choice.template.req ?: "" }, { r, v ->
            val t = r.choice.ownTemplate()
            // the native blue default is true: a requirement given to a choice that had none keeps it
            if (t.req == null && !r.choice.blueSet && v.isNotEmpty()) t.blue = true
            t.req = v.ifEmpty { null }
        })
        numAttr<ReqView>(A.A_ChoiceReq_min_level, { r -> (r.choice.template.minLevel ?: 1).toDouble() }, { r, v -> r.choice.ownTemplate().minLevel = v.toInt() })
        numAttr<ReqView>(A.A_ChoiceReq_max_level, { r -> (r.choice.template.maxLevel ?: Int.MAX_VALUE).toDouble() }, { r, v -> r.choice.ownTemplate().maxLevel = v.toInt() })
        numAttr<ReqView>(A.A_ChoiceReq_max_group, { r -> (r.choice.template.maxGroup ?: -1).toDouble() }, { r, v -> r.choice.ownTemplate().maxGroup = v.toInt() })
        boolAttr<ReqView>(A.A_ChoiceReq_blue, { r -> if (r.choice.blueSet || r.choice.template.req != null) r.choice.template.blue else true },
            { r, v -> r.choice.ownTemplate().blue = v; r.choice.blueSet = true })
    }

    // ---------------------------------------------------------------------------------------------------- BoardingEvent

    private fun installBoarding() {
        LuaStructs.register(A.T_BoardingEvent)
        strAttr<BoardView>(A.A_BoardingEvent_type, { b -> (extra(b.ev)[A.A_BoardingEvent_type] as? String) ?: (b.ev.template.boarderRace ?: "random") },
            { b, v -> extra(b.ev)[A.A_BoardingEvent_type] = v })
        numAttr<BoardView>(A.A_BoardingEvent_min, { b -> (extra(b.ev)[A.A_BoardingEvent_min] as? Double) ?: b.ev.template.boarderCount.first.toDouble() },
            { b, v -> extra(b.ev)[A.A_BoardingEvent_min] = v })
        numAttr<BoardView>(A.A_BoardingEvent_max, { b -> (extra(b.ev)[A.A_BoardingEvent_max] as? Double) ?: b.ev.template.boarderCount.last.toDouble() },
            { b, v -> extra(b.ev)[A.A_BoardingEvent_max] = v })
        numAttr<BoardView>(A.A_BoardingEvent_amount, { b -> b.ev.boarders.toDouble() }, { b, v -> b.ev.boarders = v.toInt() })
        boolAttr<BoardView>(A.A_BoardingEvent_breach, { b -> (extra(b.ev)[A.A_BoardingEvent_breach] as? Double)?.let { it != 0.0 } ?: b.ev.template.boarderBreach },
            { b, v -> extra(b.ev)[A.A_BoardingEvent_breach] = if (v) 1.0 else 0.0 })
    }

    // ---------------------------------------------------------------------------------------------------- ChoiceBox, ChoiceText

    private fun entry(v: ChoiceTextView) = v.box.choiceEntries.getOrNull(v.index) ?: throw LuaError("stale ChoiceText")

    private fun installChoiceBox() {
        strAttr<HsEventsLua.ChoiceBoxText>(A.A_ChoiceBox_mainText, { it.mainText }, { b, v -> b.mainText = v })
        val texts = { b: HsEventsLua.ChoiceBoxText -> b.choiceEntries.indices.map { i -> LuaViews.cached(b, 1_000_000 + i) { _ -> ChoiceTextView(b, i) } } }
        objAttr<HsEventsLua.ChoiceBoxText>(A.A_ChoiceBox_choices, { b ->
            LuaViews.cached(b, A.A_ChoiceBox_choices) { _ -> ListView(A.T_vector_choiceText, A.T_ChoiceText) { texts(b) } }
        })
        LuaReg.method(A.M_ChoiceBox_GetChoices, LuaMethod {
            val b = arg.self<HsEventsLua.ChoiceBoxText>(1)
            ret.obj(ListView(A.T_vector_p_choiceText, A.T_ChoiceText) { texts(b) }, A.T_vector_p_choiceText)
        })
        numAttr<HsEventsLua.ChoiceBoxText>(A.A_ChoiceBox_potentialChoice, { it.potentialChoice.toDouble() }, { b, v -> boxMap(b)[A.A_ChoiceBox_potentialChoice] = v })
        // layout state the window has no counterpart for: kept as written (defaults from the HS box)
        for ((a, def) in listOf(
            A.A_ChoiceBox_columnSize to 3.0, A.A_ChoiceBox_selectedChoice to -1.0,
            A.A_ChoiceBox_gap_size to 30.0, A.A_ChoiceBox_openTime to 0.0,
        )) numAttr<HsEventsLua.ChoiceBoxText>(a, { b -> (boxMap(b)[a] as? Double) ?: def }, { b, v -> boxMap(b)[a] = v })
        // fontSize: the event box's font size (EventFontSize), which the box prints in, clamped to 9..13 when drawn
        numAttr<HsEventsLua.ChoiceBoxText>(A.A_ChoiceBox_fontSize, { xyz.znix.xftl.game.EventFontSize.size.toDouble() },
            { _, v -> xyz.znix.xftl.game.EventFontSize.size = v.toInt() })
        boolAttr<HsEventsLua.ChoiceBoxText>(A.A_ChoiceBox_centered, { b -> ((boxMap(b)[A.A_ChoiceBox_centered] as? Double) ?: 1.0) == 1.0 },
            { b, v -> boxMap(b)[A.A_ChoiceBox_centered] = if (v) 1.0 else 0.0 })
        objAttr<HsEventsLua.ChoiceBoxText>(A.A_ChoiceBox_rewards, { b -> boxRewards(b) })
        // currentTextColor (white until the box draws a hovered row) and lastChoice (layout state, stored only)
        objAttr<HsEventsLua.ChoiceBoxText>(A.A_ChoiceBox_currentTextColor, { b -> LuaViews.cached(b, A.A_ChoiceBox_currentTextColor) { _ -> GlColorValue(1f, 1f, 1f, 1f) } })
        objAttr<HsEventsLua.ChoiceBoxText>(A.A_ChoiceBox_lastChoice, { b -> LuaViews.cached(b, A.A_ChoiceBox_lastChoice) { _ -> PointValue(0, 0) } })

        strAttr<ChoiceTextView>(A.A_ChoiceText_text, { entry(it).text }, { c, v -> entry(c).text = v })
        numAttr<ChoiceTextView>(A.A_ChoiceText_type, { entry(it).type.toDouble() }, { c, v -> entry(c).type = v.toInt() })
        objAttr<ChoiceTextView>(A.A_ChoiceText_rewards, { c ->
            // the implicit "Continue..." choice leads to an empty event: an all-zero ResourceEvent (oracle sweep-world-y1)
            val ev = entry(c).created ?: return@objAttr (HsEventsLua.currentBoxEvent ?: throw LuaError("this choice has no event"))
                .let { box -> LuaViews.cached(box, A.A_ChoiceText_rewards + 7_000_000) { _ -> ResView(box, 3) } }
            // a hidden-reward choice shows nothing: slot 3 is an all-zero ResourceEvent
            if (entry(c).hidden) LuaViews.cached(ev, A.A_ChoiceText_rewards + 7_000_000) { _ -> ResView(ev, 3) }
            else LuaViews.cached(ev, A.A_ChoiceText_rewards) { _ -> ResView(ev, 0, true) }
        })
    }

    /** ChoiceBox.rewards: the stuff of the box's own event (the one whose text it shows). */
    private fun boxRewards(b: HsEventsLua.ChoiceBoxText): Any? {
        val ev = HsEventsLua.currentBoxEvent ?: return null
        return LuaViews.cached(ev, A.A_ChoiceBox_rewards) { _ -> ResView(ev, 0, true) }
    }

    // ---------------------------------------------------------------------------------------------------- EventGenerator

    private fun installGenerator() {
        LuaReg.method(A.M_Global_GetEventGenerator, LuaMethod { ret.obj(EventGeneratorRef, A.T_EventGenerator) })
        LuaReg.obj(A.A_module_Event, ObjGet { EventGeneratorRef })
        // CreateEvent(name, worldLevel, ignoreUnique): the native creator, no reseed
        LuaReg.method(A.M_EventGenerator_CreateEvent, LuaMethod {
            val g = game() ?: return@LuaMethod ret.nil()
            val root = g.eventManager.getOrNull(arg.str(2) ?: "") ?: return@LuaMethod ret.nil()
            ret.obj(g.eventCreator.createSeeded(root, arg.int(3), arg.bool(4), -1), A.T_LocationEvent)
        })
        // GetBaseEvent(name, worldLevel, ignoreUnique, seed): the HS-hooked creator (reseeds unless the seed is -1)
        LuaReg.method(A.M_EventGenerator_GetBaseEvent, LuaMethod {
            val g = game() ?: return@LuaMethod ret.nil()
            ret.obj(HsFlow.getBaseEvent(g, arg.str(2) ?: "", arg.int(3), arg.bool(4), arg.int(5)), A.T_LocationEvent)
        })
    }

    @JvmStatic
    fun install() {
        installResource()
        installText()
        installLocationEvent()
        installChoice()
        installBoarding()
        installChoiceBox()
        installGenerator()
    }
}
