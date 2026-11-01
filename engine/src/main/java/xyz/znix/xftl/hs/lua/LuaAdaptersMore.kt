// Ported from FTL-Hyperspace (GPL-3.0-or-later) commit 1b05a07 (lua/ script API and .i members, src/ files named below),
// not from the 1.23.2 tag.
package xyz.znix.xftl.hs.lua

import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.crew.CrewBlueprint
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint
import xyz.znix.xftl.weapons.DroneBlueprint
import xyz.znix.xftl.hs.lua.LuaApi as A
import xyz.znix.xftl.hs.lua.LuaArgs as arg
import xyz.znix.xftl.hs.lua.LuaPush as ret

/** Hyperspace.Text (TextLibrary). */
object LuaText : LuaTyped { override val luaType get() = A.T_TextLibrary }
/** HS's playerVariables / metaVariables maps; hs.events may point [player] and [meta] at its own maps. */
object LuaVariables {
    @JvmField var player: MutableMap<String, Int> = HashMap()
    @JvmField var meta: MutableMap<String, Int> = HashMap()

    class View(private val meta: Boolean) : LuaTyped {
        override val luaType get() = A.T_playerVariableType
        fun map(): MutableMap<String, Int> = if (meta) LuaVariables.meta else player
    }

    @JvmField val playerView = View(false)
    @JvmField val metaView = View(true)
}

/** Hyperspace.Blueprints (BlueprintManager). */
object LuaBlueprints : LuaTyped { override val luaType get() = A.T_BlueprintManager }

/** Further adapters for the members Multiverse's scripts touch (see docs/HS_LUA.md for the coverage list). */
object LuaAdaptersMore {
    @JvmStatic
    fun install() {
        printHelper()
        LuaGraphics.install()
        LuaStructs.installVectors()
        structs()
        text()
        LuaXml.install()
        LuaAnim.install()
        LuaShipApi.install()
        LuaFps.install()
        LuaShipGraphApi.install()
        LuaShieldsApi.install()
        LuaStarMap.install()
        LuaDescriptionApi.install()
        variables()
        blueprints()
        buttons()
        xyz.znix.xftl.hs.ship.HsShipsLua.install()
        xyz.znix.xftl.hs.weapons.HsWeaponsLua.install()
        LuaAdaptersWeapons.install()
        LuaAdaptersProjectiles.install()
        xyz.znix.xftl.hs.crew.StatCrewLua.install()
        xyz.znix.xftl.hs.events.HsEventsLua.install()
        xyz.znix.xftl.hs.systems.HsSystemsLua.install()
        LuaRuntimeApi.install()
        LuaAdaptersWP3.install()
        LuaAdaptersWP2.install()
        LuaAdaptersWP7Render.install()
        LuaAdaptersWP1.install()
        LuaAdaptersDrones.install()
        LuaAdaptersWP5Systems.install()
        LuaAdaptersWP5Crew.install()
        LuaAdaptersWP5Ship.install()
        LuaAdaptersWP6Events.install()
        LuaAdaptersWP6Map.install()
        LuaAdaptersWP6Save.install()
        LuaAdaptersWP6Left.install()
        LuaAdaptersWP7Ui.install()
        LuaAdaptersSweepUi.install()
        LuaAdaptersSweepWorld.install()
        LuaAdaptersSweepCrew.install()
        LuaAdaptersSweepUi.installCrewRender() // after slice A's placeholders: the CrewMember render family draws
        LuaAdaptersSweepLang.install()
        LuaAdaptersModFix.install()
        LuaAdaptersWritable.install()
        LuaAdaptersWrites.install()
        LuaAdaptersGaps.install()
    }

    /** Hyperspace.Blueprints (BlueprintManager): the engine's blueprints, nil for unknown names. */
    private fun blueprints() {
        LuaReg.obj(A.A_module_Blueprints, ObjGet { LuaBlueprints })
        fun get(n: Int): Any? = HsLua.game?.gameContent?.blueprintManager?.getOrNull(arg.str(2) ?: "")
        // BlueprintManager::GetBlueprintList: a copy (SWIG owns the returned vector) of a <blueprintList>'s names, empty
        // for any other name (the rule hs.events and hs.stat use)
        LuaReg.method(A.M_BlueprintManager_GetBlueprintList, LuaMethod {
            val names = (get(it) as? xyz.znix.xftl.BlueprintList)?.names ?: emptyList()
            ret.obj(ListView(A.T_vector_string, 0, ArrayList<Any?>(names)), A.T_vector_string)
        })
        LuaReg.method(A.M_BlueprintManager_GetWeaponBlueprint, LuaMethod { ret.obj(get(it) as? AbstractWeaponBlueprint, A.T_WeaponBlueprint) })
        LuaReg.method(A.M_BlueprintManager_GetDroneBlueprint, LuaMethod { ret.obj(get(it) as? DroneBlueprint, A.T_DroneBlueprint) })
        LuaReg.method(A.M_BlueprintManager_GetCrewBlueprint, LuaMethod { ret.obj(get(it) as? CrewBlueprint, A.T_CrewBlueprint) })
        LuaReg.method(A.M_BlueprintManager_GetAugmentBlueprint, LuaMethod { ret.obj(get(it) as? AugmentBlueprint, A.T_AugmentBlueprint) })
    }

    /**
     * FTL's Button as HS exposes it (GenericButton fields + images): a struct whose OnInit loads
     * `img/<base>_off.png`, `_on.png` and `_select2.png` and sets position and hitbox from the first image.
     */
    private fun buttons() {
        LuaReg.method(A.M_new_Button, LuaMethod { ret.obj(LuaStructs.create(A.T_Button), A.T_Button) })
        val pos = A.A_GenericButton_position; val hit = A.A_GenericButton_hitbox
        fun point(b: LuaStruct, a: Int) = LuaStructs.member(b, a) as LuaStruct
        // Button::OnInit(img, Point) (SWIG overload 1; render workstream fix: MV's OnInit(name, Point) calls did
        // not reach the registered one)
        val onInit = LuaMethod {
            val b = arg.obj(1) as LuaStruct
            val base = arg.str(2) ?: ""
            val p = arg.obj(3) as? PointValue ?: if (!arg.isNil(3) && !arg.isNil(4)) PointValue(arg.int(3), arg.int(4)) else null
            val off = LuaGraphics.texture("${base}_off.png")
            b.values[BUTTON_IMAGES] = arrayOf(off, LuaGraphics.texture("${base}_on.png"), LuaGraphics.texture("${base}_select2.png"))
            val x = p?.x ?: 0; val y = p?.y ?: 0
            LuaStructs.setFields(point(b, pos), x.toDouble(), y.toDouble())
            LuaStructs.setFields(point(b, A.A_Button_imageSize), off.width.toDouble(), off.height.toDouble())
            LuaStructs.setFields(point(b, hit), x.toDouble(), y.toDouble(), off.width.toDouble(), off.height.toDouble())
            0
        }
        // Button::OnInit(imageBase, Rect *imageStats, Rect *hitStats, bool alwaysActive) (FTL 1.6.14 0x58eb60):
        // images = base + (alwaysActive ? _on : _off), _on, _select2; position = imageStats.xy, imageSize =
        // imageStats.wh, hitbox = hitStats (SWIG overload 0; overload 1 is (img, Point) above)
        LuaReg.method(A.M_Button_OnInit__SWIG_0, LuaMethod {
            val b = arg.obj(1) as LuaStruct
            val base = arg.str(2) ?: ""
            val img = LuaStructs.fields(arg.obj(3) as LuaStruct); val hb = LuaStructs.fields(arg.obj(4) as LuaStruct)
            b.values[BUTTON_IMAGES] = arrayOf(LuaGraphics.texture(base + if (arg.bool(5)) "_on.png" else "_off.png"),
                LuaGraphics.texture("${base}_on.png"), LuaGraphics.texture("${base}_select2.png"))
            LuaStructs.setFields(point(b, pos), img[0], img[1])
            LuaStructs.setFields(point(b, A.A_Button_imageSize), img[2], img[3])
            LuaStructs.setFields(point(b, hit), hb[0], hb[1], hb[2], hb[3])
            0
        })
        LuaReg.method(A.M_Button_OnInit__SWIG_1, onInit)
        LuaReg.method(A.M_GenericButton_SetActive, LuaMethod { (arg.obj(1) as LuaStruct).values[A.A_GenericButton_bActive] = arg.bool(2); 0 })
        LuaReg.method(A.M_GenericButton_MouseMove, LuaMethod {
            val b = arg.obj(1) as LuaStruct
            val r = LuaStructs.fields(point(b, hit))
            val x = arg.int(2); val y = arg.int(3)
            b.values[A.A_GenericButton_bHover] = x >= r[0] && x < r[0] + r[2] && y >= r[1] && y < r[1] + r[3]
            0
        })
        LuaReg.method(A.M_Button_OnRender, LuaMethod {
            val b = arg.obj(1) as LuaStruct
            @Suppress("UNCHECKED_CAST")
            val images = b.values[BUTTON_IMAGES] as? Array<LuaTexture> ?: return@LuaMethod 0
            // images = [_off, _on, _select2] (OnInit): Button::OnRender draws off while inactive, select2 while
            // hovered or selected, on otherwise (render workstream fix: the indices were swapped)
            val img = when {
                b.values[A.A_GenericButton_bActive] != true -> images[0]
                b.values[A.A_GenericButton_bHover] == true || b.values[A.A_GenericButton_bSelected] == true -> images[2]
                else -> images[1]
            }
            val p = LuaStructs.fields(point(b, pos))
            LuaGraphics.draw(img, p[0].toFloat(), p[1].toFloat())
            0
        })
        LuaReg.method(A.M_Button_SetLocation, LuaMethod {
            val b = arg.obj(1) as LuaStruct
            val p = arg.obj(2) as? PointValue ?: return@LuaMethod 0
            val old = LuaStructs.fields(point(b, pos))
            val r = LuaStructs.fields(point(b, hit))
            LuaStructs.setFields(point(b, pos), p.x.toDouble(), p.y.toDouble())
            LuaStructs.setFields(point(b, hit), r[0] + p.x - old[0], r[1] + p.y - old[1], r[2], r[3])
            0
        })
    }

    /**
     * Hyperspace.playerVariables / metaVariables (std::unordered_map<std::string,int> with HS's get/set/__getitem/
     * __setitem: unknown names read 0). The maps belong to the custom events code (hs.events), which replaces
     * [LuaVariables.player]/[LuaVariables.meta] with its own; until then they live here for the session.
     */
    private fun variables() {
        LuaReg.obj(A.A_module_playerVariables, ObjGet { LuaVariables.playerView })
        LuaReg.obj(A.A_module_metaVariables, ObjGet { LuaVariables.metaView })
        fun map(): MutableMap<String, Int> = (arg.obj(1) as LuaVariables.View).map()
        LuaReg.method(A.M_playerVariableType___getitem, LuaMethod { ret.num(map()[arg.str(2) ?: ""] ?: 0) })
        LuaReg.method(A.M_playerVariableType_get, LuaMethod { ret.num(map()[arg.str(2) ?: ""] ?: 0) })
        LuaReg.method(A.M_playerVariableType___setitem, LuaMethod { map()[arg.str(2) ?: ""] = arg.int(3); 0 })
        LuaReg.method(A.M_playerVariableType_set, LuaMethod { map()[arg.str(2) ?: ""] = arg.int(3); 0 })
        LuaReg.method(A.M_playerVariableType_del, LuaMethod { map().remove(arg.str(2) ?: ""); 0 })
        LuaReg.method(A.M_playerVariableType_has_key, LuaMethod { ret.bool(map().containsKey(arg.str(2) ?: "")) })
        LuaReg.method(A.M_playerVariableType_size, LuaMethod { ret.num(map().size) })
        LuaReg.method(A.M_playerVariableType_empty, LuaMethod { ret.bool(map().isEmpty()) })
        LuaReg.method(A.M_playerVariableType_clear, LuaMethod { map().clear(); 0 })
    }

    /** Key in a Button struct for its three textures (not a Lua-visible attribute id). */
    private const val BUTTON_IMAGES = -1

    /** StatBoostDefinition::statBoostDefs: definitions Lua registers (realBoostId = index); read by the stat boost code. */
    @JvmField val statBoostDefs = ArrayList<Any?>()

    private fun structs() {
        statBoostDefs.clear()
        LuaReg.method(A.M_new_StatBoostDefinition, LuaMethod { ret.obj(LuaStructs.create(A.T_StatBoostDefinition), A.T_StatBoostDefinition) })
        val defs = ListView(A.T_vector_StatBoostDefinition, A.T_StatBoostDefinition, statBoostDefs)
        LuaReg.obj(A.A_StatBoostDefinition_statBoostDefs, ObjGet { defs })
        LuaReg.obj(A.A_module_StatBoostDefinition_statBoostDefs, ObjGet { defs })
        LuaReg.method(A.M_new_Rect__SWIG_0, LuaMethod { ret.obj(LuaStructs.create(A.T_Rect), A.T_Rect) })
        LuaReg.method(A.M_new_Rect__SWIG_1, LuaMethod {
            val r = LuaStructs.create(A.T_Rect)
            val a = LuaStructs.attrsOf(A.T_Rect) // x, y, w, h
            for (k in 0 until 4) r.values[a[k]] = arg.int(k + 1).toDouble()
            ret.obj(r, A.T_Rect)
        })
        LuaReg.method(A.M_new_TextString__SWIG_0, LuaMethod { ret.obj(LuaStructs.create(A.T_TextString), A.T_TextString) })
        LuaReg.method(A.M_new_TextString__SWIG_1, LuaMethod {
            val t = LuaStructs.create(A.T_TextString)
            t.values[A.A_TextString_data] = arg.str(1) ?: ""
            t.values[A.A_TextString_isLiteral] = arg.bool(2)
            ret.obj(t, A.T_TextString)
        })
    }

    /** TextLibrary::GetText: the text as the library stores it (untrimmed); TextString::GetText translates non-literals. */
    private fun text() {
        LuaReg.obj(A.A_module_Text, ObjGet { LuaText })
        LuaReg.method(A.M_TextLibrary_GetText__SWIG_0, LuaMethod { ret.str(translate(arg.str(2) ?: "")) })
        LuaReg.method(A.M_TextLibrary_GetText__SWIG_1, LuaMethod { ret.str(translate(arg.str(2) ?: "")) })
        LuaReg.method(A.M_TextString_GetText, LuaMethod {
            val t = arg.obj(1) as LuaStruct
            val data = t.values[A.A_TextString_data] as? String ?: ""
            ret.str(if (t.values[A.A_TextString_isLiteral] == true) data else translate(data))
        })
    }

    @JvmStatic
    fun translate(key: String): String {
        val t = HsLua.game?.gameContent?.translator?.untrimmed(key) ?: return key
        // TextLibrary::GetText's missing text is "Could not find: <id>" (FTL 1.6.14 string 0x881742); the engine's
        // translator writes it in lower case
        return if (t == "could not find: $key") "Could not find: $key" else t
    }

    private fun printHelper() {
        LuaReg.method(A.M_PrintHelper_GetInstance, LuaMethod { ret.obj(LuaPrint, A.T_PrintHelper) })
        LuaReg.num(A.A_PrintHelper_x, NumGet { LuaPrint.x.toDouble() }, NumSet { _, v -> LuaPrint.x = v.toInt() })
        LuaReg.num(A.A_PrintHelper_y, NumGet { LuaPrint.y.toDouble() }, NumSet { _, v -> LuaPrint.y = v.toInt() })
        LuaReg.num(A.A_PrintHelper_font, NumGet { LuaPrint.font.toDouble() }, NumSet { _, v -> LuaPrint.font = v.toInt() })
        LuaReg.num(A.A_PrintHelper_lineLength, NumGet { LuaPrint.lineLength.toDouble() }, NumSet { _, v -> LuaPrint.lineLength = v.toInt() })
        LuaReg.num(A.A_PrintHelper_messageLimit, NumGet { LuaPrint.messageLimit.toDouble() }, NumSet { _, v -> LuaPrint.messageLimit = v.toInt() })
        LuaReg.num(A.A_PrintHelper_duration, NumGet { LuaPrint.duration.toDouble() }, NumSet { _, v -> LuaPrint.duration = v.toInt() })
        LuaReg.bool(A.A_PrintHelper_useSpeed, { LuaPrint.useSpeed }, { _, v -> LuaPrint.useSpeed = v })
    }
}
