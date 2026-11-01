// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 (lua/modules/hyperspace.i: the std::unordered_map /
// unordered_multimap templates, Hyperspace.* module names, CustomLockdownDefinition / LockdownShard_Extend, CustomEvent,
// src/game/Ship/CustomLockdowns.cpp) and the oracle rows of tools/hs-oracle/tracer/data/hs_oracle/sweep-world.lua.
package xyz.znix.xftl.hs.lua

import xyz.znix.xftl.hs.crew.HsColour
import xyz.znix.xftl.hs.crew.LockdownDefinition
import xyz.znix.xftl.hs.crew.PowerReadyState
import xyz.znix.xftl.hs.events.CustomEvent
import xyz.znix.xftl.hs.lua.LuaApi as A
import xyz.znix.xftl.hs.lua.LuaArgs as arg
import xyz.znix.xftl.hs.lua.LuaPush as ret
import xyz.znix.xftl.hs.ship.HsAugments
import xyz.znix.xftl.hs.ship.HsLockdowns
import xyz.znix.xftl.hs.stat.StatBoostDefs
import xyz.znix.xftl.hs.systems.HsSystems

/** A `std::unordered_map` / `std::map` made by Lua (`Hyperspace.map_string_int()`), or an engine table behind one. */
class LuaMapObj(override val luaType: Int, @JvmField val map: MutableMap<Any?, Any?>) : LuaTyped

/** A `std::unordered_multimap<string, AugmentFunction>`: [list] is the (possibly engine-owned) entries in insertion order. */
class LuaMultiMap(override val luaType: Int, @JvmField val list: () -> MutableList<Pair<String, HsAugments.AugmentFunction>>) : LuaTyped

/** rapidxml's parse_error (`Hyperspace.parse_error(what, where)`). */
class LuaParseError(@JvmField val what: String) : LuaTyped { override val luaType get() = A.T_parse_error }

/** A CustomLockdownDefinition made by a script (the engine reads it when it is used, as HS does). */
class LuaLockdownDef(
    @JvmField var duration: Float, @JvmField var health: Int, @JvmField val color: GlColorValue,
    @JvmField val anims: ArrayList<Any?>, @JvmField var canDilate: Boolean,
) : LuaTyped {
    override val luaType get() = A.T_CustomLockdownDefinition

    constructor(d: LockdownDefinition) : this(d.duration, d.health, GlColorValue(d.colour.r, d.colour.g, d.colour.b, d.colour.a),
        ArrayList<Any?>(d.anims), d.canDilate)

    fun toDefinition() = LockdownDefinition(duration, health, HsColour(color.r, color.g, color.b, color.a),
        anims.map { it.toString() }, canDilate)
}

/** LockdownShard::extend (LockdownShard_Extend); one per shard so the userdata stays the same object. */
class LuaShardExtend private constructor(@JvmField val shard: xyz.znix.xftl.layout.LockdownShard) : LuaTyped {
    override val luaType get() = A.T_LockdownShard_Extend

    companion object {
        private val cache = xyz.znix.xftl.hs.lua.HsRunMaps.run(java.util.IdentityHashMap<xyz.znix.xftl.layout.LockdownShard, LuaShardExtend>())
        fun of(s: xyz.znix.xftl.layout.LockdownShard): LuaShardExtend = cache.getOrPut(s) { LuaShardExtend(s) }
    }
}

/**
 * Round 14 slice B Lua names: the module-level functions and constants, the `new_*` constructors of every class (value
 * objects for the data types, empty containers, inert objects for the native game classes), the std::map / multimap
 * template methods, CustomLockdownDefinition and LockdownShard_Extend, CustomEvent.unlockShip, ChoiceBox.choiceBoxes.
 */
object LuaAdaptersSweepWorld {
    /** CrewAbilities.cpp nextPowerReadyState (process-wide in HS, never reset). */
    private var nextPowerReadyState = PowerReadyState.NOT_READY_CUSTOM.toLong()

    private const val KEY_NOT_FOUND = "SWIG_IndexError:key not found"

    @JvmStatic
    fun install() {
        structTypes()
        module()
        maps()
        constructors()
        lockdowns()
        events()
    }

    // ---------------------------------------------------------------------------------------------------- helpers

    private val typeIds: Map<String, Int> by lazy { HashMap<String, Int>().also { m -> for (t in 1..A.TYPE_COUNT) m[A.typeName(t)] = t } }
    private val methodIds: Map<String, List<Int>> by lazy {
        HashMap<String, ArrayList<Int>>().also { m -> for (i in 1..A.METHOD_COUNT) m.getOrPut(A.methodName(i)) { ArrayList() }.add(i) }
    }

    /** Registers [f] on every overload of method [name] for receivers [mine] accepts; any other receiver goes to what was there. */
    private fun method(name: String, mine: (Any?) -> Boolean, f: LuaMethod) {
        for (id in methodIds[name] ?: return) {
            val prev = LuaReg.methods[id]
            LuaReg.method(id, LuaMethod { n ->
                if (mine(arg.obj(1))) f.call(n)
                else (prev ?: throw LuaError("Hyperspace Lua API not available in the browser engine: $name")).call(n)
            })
        }
    }

    // ------------------------------------------------------------------------------------------ struct attributes

    /**
     * The data types the engine stores as plain fields get their attribute accessors now (LuaStructs does it on the first
     * `new`, which is too late for a struct an adapter returns).
     */
    private fun structTypes() {
        for (n in listOf("EffectsBlueprint", "MiniProjectile", "ImageDesc", "Ellipse", "Slot", "SystemTemplate", "GL_ColorTexVertex",
            "GL_Line", "GL_TexVertex", "BoostPower", "CrewDesc", "SCrewStats", "BoardingGoal", "ToggleValue_int", "ToggleValue_float",
            "ToggleValue_bool", "pair_int_int", "pair_float_float", "pair_CrewExtraCondition_bool", "pair_Animation_int8_t",
            "pair_string_AugmentFunction", "AugmentFunction"))
            typeIds[n]?.let { LuaStructs.register(it) }
    }

    // ------------------------------------------------------------------------------------------------ module level

    private fun module() {
        // float getSkillBonus(int skill, int level)
        LuaReg.method(A.M_getSkillBonus, LuaMethod { ret.num(skillBonus(arg.int(1), arg.int(2))) })
        // unsigned int GetNextPowerReadyState(unsigned int amount): the first of [amount] new PowerReadyStates
        LuaReg.method(A.M_GetNextPowerReadyState, LuaMethod {
            val r = nextPowerReadyState
            nextPowerReadyState = (nextPowerReadyState + arg.num(1).toLong()) and 0xFFFFFFFFL
            ret.num(r.toDouble())
        })
        LuaReg.method(A.M_Get_CrewMember_Extend, LuaMethod {
            val get = LuaReg.objGet[A.A_CrewMember_extend] ?: throw LuaError("Hyperspace Lua API not available in the browser engine: CrewMember.extend")
            ret.obj(get.get(arg.obj(1)), A.T_CrewMember_Extend)
        })
        LuaReg.method(A.M_Get_Projectile_Extend, LuaMethod {
            val get = LuaReg.objGet[A.A_Projectile_extend] ?: throw LuaError("Hyperspace Lua API not available in the browser engine: Projectile.extend")
            ret.obj(get.get(arg.obj(1)), A.T_Projectile_Extend)
        })
        // ErrorMessage(const char*): HS shows an SDL message box and prints the text to stderr; the browser prints
        LuaReg.method(A.M_ErrorMessage, LuaMethod { System.err.print(arg.str(1) ?: ""); 0 })
        LuaReg.bool(A.A_module_useAugmentReq, { HsAugments.useAugmentReq }, { _, v -> HsAugments.useAugmentReq = v })
        // rapidxml's parse_* flags are nil in Lua in HS (rapidxml.i constants are not exported to Hyperspace): left unregistered
        // parse_error(const char* what, void* where)
        val newParseError = LuaMethod { ret.obj(LuaParseError(arg.str(1) ?: ""), A.T_parse_error) }
        for (id in methodIds["parse_error()"] ?: emptyList()) LuaReg.method(id, newParseError)
        LuaReg.method(A.M_parse_error_what, LuaMethod { ret.str((arg.obj(1) as LuaParseError).what) })
        // StatBoostDefinition::savedStatBoostDefs (the `save=` names of the XML stat boosts)
        val savedDefs = ObjGet {
            LuaViews.cached(StatBoostDefs, A.A_module_StatBoostDefinition_savedStatBoostDefs) { _ ->
                @Suppress("UNCHECKED_CAST")
                LuaMapObj(A.T_unordered_map_string_p_StatBoostDefinition, StatBoostDefs.saved as MutableMap<Any?, Any?>)
            }
        }
        LuaReg.obj(A.A_module_StatBoostDefinition_savedStatBoostDefs, savedDefs)
        LuaReg.obj(A.A_StatBoostDefinition_savedStatBoostDefs, savedDefs)
    }

    /** getSkillBonus(skill, level): FTL's table of the bonus per crew skill and level. */
    @JvmStatic fun skillBonus(skill: Int, level: Int): Double { // also CrewMember::GetSkillModifier (sweep A)
        if (skill < 0 || skill > 5) return 0.0
        return SKILL_BONUS[skill][level.coerceIn(0, 3)].toDouble()
    }

    // The oracle row `skillBonus` (sweep-world.lua): skill (0 pilot..5) by level 0..3; any other skill is 0.
    private val SKILL_BONUS = arrayOf(
        floatArrayOf(0f, 5f, 7f, 10f), floatArrayOf(0f, 5f, 7f, 10f), floatArrayOf(1f, 1.1f, 1.2f, 1.3f),
        floatArrayOf(1f, 0.9f, 0.85f, 0.8f), floatArrayOf(1f, 1f, 1.1f, 1.2f), floatArrayOf(1f, 1f, 1.1f, 1.2f),
    )

    // ------------------------------------------------------------------------------------------ maps and multimaps

    private class MapType(val name: String, val keyString: Boolean, val valueType: Int /* 0: number */)

    private val mapTypes: List<MapType> by lazy {
        listOf(
            MapType("map_string_int", true, 0), MapType("unordered_map_string_int", true, 0),
            MapType("unordered_map_int_RoomDefinition", false, A.T_RoomDefinition),
            MapType("map_int_SystemTemplate", false, A.T_SystemTemplate),
            MapType("unordered_map_unsigned_int_ActivatedPowerResource", false, A.T_ActivatedPowerResource),
            MapType("unordered_map_string_p_StatBoostDefinition", true, A.T_StatBoostDefinition),
        )
    }

    private fun maps() {
        for (t in mapTypes) {
            fun key(i: Int): Any? = if (t.keyString) arg.str(i) ?: "" else arg.int(i)
            fun value(i: Int): Any? = if (t.valueType == 0) arg.int(i) else arg.obj(i).let { if (it is LuaStruct) LuaStructs.copy(it) else it }
            fun push(v: Any?): Int = if (t.valueType == 0) ret.num((v as? Number)?.toDouble() ?: 0.0) else ret.obj(v, t.valueType)
            fun m(o: Any?) = (o as LuaMapObj).map
            val mine = { o: Any? -> o is LuaMapObj }
            method(t.name + ".size", mine, LuaMethod { ret.num(m(arg.obj(1)).size) })
            method(t.name + ".empty", mine, LuaMethod { ret.bool(m(arg.obj(1)).isEmpty()) })
            method(t.name + ".clear", mine, LuaMethod { m(arg.obj(1)).clear(); 0 })
            method(t.name + ".keys", mine, LuaMethod {
                val keys = ArrayList<Any?>()
                for (k in m(arg.obj(1)).keys) keys.add(if (k is Int) k.toDouble() else k)
                val vt = if (t.keyString) A.T_vector_string else if (t.name.contains("unsigned")) A.T_vector_unsigned_int else A.T_vector_int
                ret.obj(ListView(vt, 0, keys), vt)
            })
            val get = LuaMethod { val mm = m(arg.obj(1)); val k = key(2); if (!mm.containsKey(k)) throw LuaError(KEY_NOT_FOUND); push(mm[k]) }
            method(t.name + ".get", mine, get)
            method(t.name + ".__getitem", mine, get)
            val set = LuaMethod { m(arg.obj(1))[key(2)] = value(3); 0 }
            method(t.name + ".set", mine, set)
            method(t.name + ".__setitem", mine, set)
            method(t.name + ".del", mine, LuaMethod {
                val mm = m(arg.obj(1)); val k = key(2)
                if (!mm.containsKey(k)) throw LuaError(KEY_NOT_FOUND)
                mm.remove(k); 0
            })
            method(t.name + ".has_key", mine, LuaMethod { ret.bool(m(arg.obj(1)).containsKey(key(2))) })
        }
        // unordered_multimap<string, AugmentFunction>: the entries are a flat list (insertion order stands for the hash order)
        val mm = "unordered_multimap_string_AugmentFunction"
        fun list(o: Any?) = (o as LuaMultiMap).list()
        val mine = { o: Any? -> o is LuaMultiMap }
        method("$mm.size", mine, LuaMethod { ret.num(list(arg.obj(1)).size) })
        method("$mm.empty", mine, LuaMethod { ret.bool(list(arg.obj(1)).isEmpty()) })
        method("$mm.clear", mine, LuaMethod { list(arg.obj(1)).clear(); 0 })
        method("$mm.get", mine, LuaMethod {
            val k = arg.str(2) ?: ""
            val e = list(arg.obj(1)).firstOrNull { it.first == k } ?: throw LuaError(KEY_NOT_FOUND)
            ret.obj(e.second, A.T_AugmentFunction)
        })
        val add = LuaMethod { list(arg.obj(1)).add(Pair(arg.str(2) ?: "", augFunction(arg.obj(3)))); 0 }
        method("$mm.set", mine, add)
        method("$mm.__setitem", mine, add)
        method("$mm.del", mine, LuaMethod {
            val l = list(arg.obj(1)); val k = arg.str(2) ?: ""
            val i = l.indexOfFirst { it.first == k }
            if (i < 0) throw LuaError(KEY_NOT_FOUND)
            l.removeAt(i); 0
        })
        method("$mm.has_key", mine, LuaMethod { val k = arg.str(2) ?: ""; ret.bool(list(arg.obj(1)).any { it.first == k }) })
        // __getitem(unsigned idx): a new pair (key, function)
        method("$mm.__getitem", mine, LuaMethod {
            val l = list(arg.obj(1)); val i = arg.num(2).toLong().toInt()
            if (i < 0 || i >= l.size) throw LuaError("SWIG_IndexError:index out of range")
            val pt = A.T_pair_string_AugmentFunction
            val p = LuaStructs.create(pt)
            val a = LuaStructs.attrsOf(pt)
            p.values[a[0]] = l[i].first; p.values[a[1]] = l[i].second
            ret.obj(p, pt)
        })
        // AugmentDefinition::functions: the definition's own entries
        LuaReg.obj(A.A_AugmentDefinition_functions, ObjGet { o ->
            LuaViews.cached(o!!, A.A_AugmentDefinition_functions) { x ->
                LuaMultiMap(A.T_unordered_multimap_string_AugmentFunction) { (x as HsAugments.AugmentDefinition).functions }
            }
        })
    }

    /** An AugmentFunction value: an engine one, or a script-made struct (its fields by attribute). */
    private fun augFunction(o: Any?): HsAugments.AugmentFunction {
        if (o is HsAugments.AugmentFunction) return o
        val s = o as? LuaStruct ?: throw LuaError("expected AugmentFunction")
        fun num(a: Int) = (s.values[a] as? Double) ?: 0.0
        fun bool(a: Int) = s.values[a] == true || (s.values[a] as? Double ?: 0.0) != 0.0
        val sys = num(A.A_AugmentFunction_sys).toInt()
        return HsAugments.AugmentFunction(num(A.A_AugmentFunction_value).toFloat(), bool(A.A_AugmentFunction_preferHigher),
            bool(A.A_AugmentFunction_useForReqs), bool(A.A_AugmentFunction_warning),
            if (sys < 0) null else HsSystems.idToName(sys), bool(A.A_AugmentFunction_modifyChoiceTextScrap))
    }

    // ----------------------------------------------------------------------------------------------- constructors

    /**
     * `new_*` of every class nobody registered: empty std containers, copies, and value-initialised structs. The native game
     * classes (ShipManager(), CrewMember(), ...) are inert objects here: in HS they are uninitialised native memory, which
     * crashes when the game uses them.
     */
    private fun constructors() {
        for (m in 1..A.METHOD_COUNT) {
            val name = A.methodName(m)
            if (!name.endsWith("()")) continue
            // overload dispatchers are never called by the glue
            if (m < A.METHOD_COUNT && A.methodName(m + 1) == name && (m == 1 || A.methodName(m - 1) != name)) continue
            if (LuaReg.methods[m] != null) continue
            val cls = name.removeSuffix("()")
            val type = typeIds[cls] ?: continue
            LuaReg.method(m, constructorOf(cls, type))
        }
    }

    private fun constructorOf(cls: String, type: Int): LuaMethod {
        val elem = LuaStructs.vectorElement(type)
        if (elem != null) return vectorConstructor(cls, type, elem)
        if (mapTypes.any { it.name == cls }) return LuaMethod { n ->
            val copy = if (n >= 1) arg.obj(1) as? LuaMapObj else null
            ret.obj(LuaMapObj(type, copy?.let { LinkedHashMap(it.map) } ?: LinkedHashMap()), type)
        }
        if (cls == "unordered_multimap_string_AugmentFunction") return LuaMethod { n ->
            val copy = if (n >= 1) arg.obj(1) as? LuaMultiMap else null
            val own = ArrayList<Pair<String, HsAugments.AugmentFunction>>(copy?.list?.invoke() ?: emptyList())
            ret.obj(LuaMultiMap(type) { own }, type)
        }
        if (cls == "CustomLockdownDefinition") return LuaMethod { n ->
            val copy = if (n >= 1) arg.obj(1) as? LuaLockdownDef else null
            ret.obj(LuaLockdownDef(copy?.toDefinition() ?: HsLockdowns.defaults()), type)
        }
        // value types and the native classes: a struct (a same-type argument: its copy; else the first fields in order)
        return LuaMethod { n ->
            val first = if (n >= 1) arg.obj(1) else null
            if (first is LuaStruct && first.luaType == type) return@LuaMethod ret.obj(LuaStructs.copy(first), type)
            val s = LuaStructs.create(type)
            if (n >= 1) {
                val a = LuaStructs.attrsOf(type)
                for (k in 0 until minOf(n, a.size)) {
                    s.values[a[k]] = when (A.attrKind(a[k])) {
                        1 -> arg.num(k + 1)
                        2 -> arg.bool(k + 1)
                        3 -> arg.str(k + 1) ?: ""
                        else -> arg.obj(k + 1).let { v -> if (v is LuaStruct) LuaStructs.copy(v) else v }
                    }
                }
            }
            ret.obj(s, type)
        }
    }

    private fun vectorConstructor(cls: String, type: Int, elem: Int): LuaMethod {
        val str = cls == "vector_string"
        val bool = cls == "vector_bool"
        val nested = elem != 0 && LuaStructs.vectorElement(elem) != null
        fun read(i: Int): Any? = when {
            elem != 0 -> arg.obj(i).let { v -> if (v is LuaStruct) LuaStructs.copy(v) else v }
            str -> arg.str(i) ?: ""
            bool -> arg.bool(i)
            else -> arg.num(i)
        }
        fun default(): Any? = when {
            nested -> ListView(elem, LuaStructs.vectorElement(elem) ?: 0, ArrayList<Any?>())
            elem != 0 -> LuaStructs.create(elem)
            str -> ""
            bool -> false
            else -> 0.0
        }
        return LuaMethod { n ->
            val list = ArrayList<Any?>()
            val first = if (n >= 1) arg.obj(1) else null
            when {
                n == 0 -> {}
                first is ListView -> for (i in 0 until first.size()) list += first.get(i)
                else -> { val count = arg.num(1).toLong().toInt(); repeat(count) { list += if (n >= 2) read(2) else default() } }
            }
            ret.obj(ListView(type, elem, list), type)
        }
    }

    // ----------------------------------------------------------------------------------------------------- lockdown

    private fun lockdowns() {
        fun sh(o: Any?) = (o as LuaLockdownShard).shard
        fun ex(o: Any?) = (o as LuaShardExtend).shard
        LuaReg.obj(A.A_LockdownShard_extend, ObjGet { LuaShardExtend.of(sh(it)) })
        LuaReg.num(A.A_LockdownShard_Extend_health, NumGet { ex(it).health.toDouble() }, NumSet { o, v -> ex(o).health = v.toInt() })
        LuaReg.obj(A.A_LockdownShard_Extend_door, ObjGet { ex(it).door })
        fun colour(s: xyz.znix.xftl.layout.LockdownShard): GlColorValue =
            s.colourValue ?: GlColorValue(s.colour.r, s.colour.g, s.colour.b, s.colour.a).also { s.colourValue = it }
        LuaReg.obj(A.A_LockdownShard_Extend_color, ObjGet { colour(ex(it)) }, ObjSet { o, v ->
            val c = v as? GlColorValue ?: return@ObjSet
            val d = colour(ex(o))
            d.r = c.r; d.g = c.g; d.b = c.b; d.a = c.a
        })
        LuaReg.str(A.A_LockdownShard_Extend_anim, StrGet { ex(it).animName })
        LuaReg.bool(A.A_LockdownShard_Extend_canDilate, { ex(it).canDilate }, { o, v -> ex(o).canDilate = v })

        // CustomLockdownDefinition
        fun def(o: Any?) = o as LuaLockdownDef
        LuaReg.num(A.A_CustomLockdownDefinition_duration, NumGet { def(it).duration.toDouble() }, NumSet { o, v -> def(o).duration = v.toFloat() })
        LuaReg.num(A.A_CustomLockdownDefinition_health, NumGet { def(it).health.toDouble() }, NumSet { o, v -> def(o).health = v.toInt() })
        LuaReg.bool(A.A_CustomLockdownDefinition_canDilate, { def(it).canDilate }, { o, v -> def(o).canDilate = v })
        LuaReg.obj(A.A_CustomLockdownDefinition_color, ObjGet { def(it).color }, ObjSet { o, v ->
            val c = v as? GlColorValue ?: return@ObjSet
            val d = def(o).color
            d.r = c.r; d.g = c.g; d.b = c.b; d.a = c.a
        })
        LuaReg.obj(A.A_CustomLockdownDefinition_anims, ObjGet { o ->
            LuaViews.cached(o!!, A.A_CustomLockdownDefinition_anims) { x -> ListView(A.T_vector_string, 0, def(x).anims) }
        }, ObjSet { o, v ->
            val l = v as? ListView ?: return@ObjSet
            val copy = ArrayList<Any?>(); for (i in 0 until l.size()) copy += l.get(i)
            val a = def(o).anims
            a.clear(); a.addAll(copy)
        })

        // Ship::LockdownRoom(roomId, pos, CustomLockdownDefinition&): the definition is the current lockdown for the call
        LuaReg.method(A.M_Ship_LockdownRoom__SWIG_1, LuaMethod {
            val ship = (arg.obj(1) as LuaShipGeom).ship
            val room = ship.rooms.firstOrNull { it.id == arg.int(2) } ?: return@LuaMethod 0
            var x: Float? = null; var y: Float? = null
            when (val p = arg.obj(3)) {
                is PointfValue -> { x = p.x; y = p.y }
                is LuaPointfMember -> { x = p.x; y = p.y }
                is PointValue -> { x = p.x.toFloat(); y = p.y.toFloat() }
            }
            val d = arg.obj(4) as? LuaLockdownDef ?: throw LuaError("expected CustomLockdownDefinition &")
            HsLockdowns.lockdown(room, d.toDefinition(), x, y)
            0
        })
    }

    // ------------------------------------------------------------------------------------------------------ events

    // CombatControl::selectedRoom / selectedSelfRoom: the room under the pointer on the enemy / player ship, set by
    // CombatControl::MouseMove (oracle sweep-world-ex1: room 5 at (665, 380), -1 over the systems bar). A script's write
    // lasts until the pointer moves.
    private class RoomOverride(@JvmField val value: Int, @JvmField val x: Int, @JvmField val y: Int)
    private val roomOverrides = HashMap<Int, RoomOverride>()

    private fun roomUnderPointer(self: Boolean): Int {
        val g = LuaAdapters.inGame() ?: return -1
        val ship = (if (self) g.player else g.enemy) ?: return -1
        val p = xyz.znix.xftl.math.Point(LuaEvents.mouseX, LuaEvents.mouseY)
        if (self) g.convertScreenToPlayerShip(p) else g.convertScreenToEnemyShip(p)
        ship.screenPosToShipPos(p)
        return ship.shipToRoomPos(p)?.room?.id ?: -1
    }

    private fun combatControl() {
        for ((attr, self) in listOf(A.A_CombatControl_selectedSelfRoom to true, A.A_CombatControl_selectedRoom to false)) {
            LuaReg.num(attr, NumGet {
                val o = roomOverrides[attr]
                if (o != null && o.x == LuaEvents.mouseX && o.y == LuaEvents.mouseY) o.value.toDouble() else roomUnderPointer(self).toDouble()
            }, NumSet { _, v -> roomOverrides[attr] = RoomOverride(v.toInt(), LuaEvents.mouseX, LuaEvents.mouseY) })
        }
        // CombatControl::playerShipPosition: the screen point of the player ship's room-space origin (oracle: 350, 170).
        // A field scripts may write (whole or one coordinate); the written point stays (see [shipPos]).
        LuaReg.obj(A.A_CombatControl_playerShipPosition, ObjGet {
            val g = LuaAdapters.inGame() ?: return@ObjGet PointValue(0, 0)
            shipPos(g).point
        }, ObjSet { _, v ->
            val p = v as? PointValue ?: throw LuaError("Point expected")
            val g = LuaAdapters.inGame() ?: return@ObjSet
            val s = shipPos(g)
            s.point.x = p.x; s.point.y = p.y; s.written = true
        })
    }

    /**
     * The game's CombatControl::playerShipPosition: one point that follows the engine's layout until a script writes it
     * (assigns a point or changes a coordinate of the one it read); from then on it keeps the script's value, which
     * CustomBackgroundObject::MouseMove subtracts for the player-layer objects' hover and click areas.
     */
    private class ShipPos(@JvmField val point: PointValue, @JvmField var lastX: Int, @JvmField var lastY: Int) {
        @JvmField var written = false
    }

    private fun shipPos(g: xyz.znix.xftl.game.InGameState): ShipPos {
        val s = g.hsCombatShipPosition as? ShipPos ?: ShipPos(PointValue(0, 0), 0, 0).also { g.hsCombatShipPosition = it }
        if (!s.written && (s.point.x != s.lastX || s.point.y != s.lastY)) s.written = true
        if (!s.written) {
            val p = xyz.znix.xftl.math.Point(0, 0)
            g.convertScreenToPlayerShip(p)
            val scale = g.playerShipRenderScale
            // (Room rects already hold the ship's room offset: Room.offsetX)
            s.point.x = Math.round(-p.x * scale); s.point.y = Math.round(-p.y * scale)
            s.lastX = s.point.x; s.lastY = s.point.y
        }
        return s
    }

    /** The script-written CombatControl::playerShipPosition of [g], or null while it follows the engine. */
    @JvmStatic
    fun writtenShipPosition(g: xyz.znix.xftl.game.InGameState): xyz.znix.xftl.math.IPoint? {
        val s = g.hsCombatShipPosition as? ShipPos ?: return null
        if (!s.written && (s.point.x != s.lastX || s.point.y != s.lastY)) s.written = true
        return if (s.written) xyz.znix.xftl.math.ConstPoint(s.point.x, s.point.y) else null
    }

    /**
     * Room::highlightPrimitive / highlightPrimitive2: the selection outline Room::OnRenderFloor draws on a room chosen for
     * targeting, as GL_Primitives a script can render (the example absorber draws both on SHIP_FLOOR for its target): the
     * 3 px yellow frame 2 px in from the walls, and the 1 px orange frame inside it (the engine's own selection drawing).
     */
    private fun roomHighlights() {
        fun prim(room: xyz.znix.xftl.layout.Room, inner: Boolean): LuaPrimitive {
            val c = if (inner) xyz.znix.xftl.Constants.ROOM_BORDER_COLOUR_SELECTED_INNER else xyz.znix.xftl.Constants.ROOM_BORDER_COLOUR_SELECTED
            val p = LuaPrimitive(null, room.offsetX.toFloat(), room.offsetY.toFloat(), room.pixelWidth.toFloat(), room.pixelHeight.toFloat(),
                0f, false, GlColorValue(c.r, c.g, c.b, c.a))
            p.shape = { g, _ ->
                val x = room.offsetX; val y = room.offsetY
                for (i in if (inner) 5..5 else 2..4) {
                    g.colour = c
                    g.drawRect((x + i).toFloat(), (y + i).toFloat(), (room.pixelWidth - 1 - i * 2).toFloat(), (room.pixelHeight - 1 - i * 2).toFloat())
                }
            }
            return p
        }
        for ((a, inner) in listOf(A.A_Room_highlightPrimitive to false, A.A_Room_highlightPrimitive2 to true))
            LuaReg.obj(a, ObjGet { o -> LuaViews.cached(o!!, a) { r -> prim(r as xyz.znix.xftl.layout.Room, inner) } }, ObjSet { _, _ -> })
    }

    private fun events() {
        combatControl()
        roomHighlights()
        // CustomEvent::unlockShip (std::string): the ship the event unlocks (HsFlow reads it when the event runs)
        LuaReg.str(A.A_CustomEvent_unlockShip, StrGet { (it as CustomEvent).unlockShip }, StrSet { o, v -> (o as CustomEvent).unlockShip = v })
        // ChoiceBox::choiceBoxes (vector<Rect>): the rectangles of the choices; the engine draws its own box, so empty
        val a = A.A_ChoiceBox_choiceBoxes
        if (LuaReg.objGet[a] == null) {
            val vt = A.attrType(a)
            val el = LuaStructs.vectorElement(vt) ?: A.T_Rect
            LuaReg.obj(a, ObjGet { o -> LuaViews.cached(o!!, a) { _ -> ListView(vt, el, ArrayList<Any?>()) } })
        }
        // CustomShipGenerator.CreateShip stays unregistered: HS 1.23.2's class table has no such field (oracle sweep-world-ex1
        // csg-create: "attempt to call a nil value (field 'CreateShip')")
    }
}
