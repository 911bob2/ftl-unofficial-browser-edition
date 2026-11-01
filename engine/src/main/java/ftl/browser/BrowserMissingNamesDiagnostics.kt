package ftl.browser

import org.jdom2.Document
import org.jdom2.Element
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sys.GameContainer

/**
 * Mod data naming something that does not exist: a weapon's explosion or art, a drone's image, a ship's weapon, drone,
 * augment or crew, an event's reward, ship, list or sound. Every kind runs through an event, a fight, a render and a save
 * and load, on whatever game files are loaded, and the game has to go on. tools/hs-oracle's missing-names sessions run the
 * same data in the original. `hsRuntimeProbe("mn CASE...")` prints one line per case.
 */
object BrowserMissingNamesDiagnostics {
    private const val MISSING = "MN_MISSING"

    private const val WEAPON_STATS = "<type>LASER</type><title>T</title><short>T</short><tip>tip_laser</tip><desc>T</desc><tooltip>T</tooltip>" +
        "<damage>1</damage><shots>1</shots><sp>5</sp><fireChance>1</fireChance><breachChance>0</breachChance><cooldown>3</cooldown>" +
        "<power>1</power><cost>20</cost><bp>2</bp><rarity>0</rarity>"

    private fun weapon(name: String, image: String = "laser_light1", explosion: String? = null, art: String = "laser_burst_1",
                       launch: String = "lightLaser1", hit: String = "hitHull2", shield: String = "hitShield1", miss: String = "miss") =
        "<weaponBlueprint name=\"$name\">$WEAPON_STATS<image>$image</image>" + (if (explosion != null) "<explosion>$explosion</explosion>" else "") +
            "<launchSounds><sound>$launch</sound></launchSounds><hitShipSounds><sound>$hit</sound></hitShipSounds>" +
            "<hitShieldSounds><sound>$shield</sound></hitShieldSounds><missSounds><sound>$miss</sound></missSounds>" +
            "<weaponArt>$art</weaponArt><iconImage>laser</iconImage></weaponBlueprint>"

    private fun drone(name: String, kind: String = "DEFENSE", image: String = "drone_defense", shot: String? = "DRONE_LASER") =
        "<droneBlueprint name=\"$name\"><type>$kind</type><level>1</level><tip>tip_defense</tip><title>T</title><short>T</short><desc>T</desc>" +
            "<power>1</power><cooldown>1000</cooldown><dodge>0</dodge><speed>5</speed><cost>50</cost><bp>2</bp><droneImage>$image</droneImage>" +
            (if (shot != null) "<weaponBlueprint>$shot</weaponBlueprint>" else "") + "<iconImage>defense</iconImage><rarity>0</rarity></droneBlueprint>"

    private const val DRONE_SYSTEM = "<drones power=\"4\" room=\"0\" max=\"6\" start=\"true\"/>"

    private fun ship(name: String, weapons: String, drones: String, extra: String = "", room0: String = DRONE_SYSTEM, layout: String = "auto_assault", image: String = "auto_assault") =
        "<shipBlueprint name=\"$name\" layout=\"$layout\" img=\"$image\"><class>T</class><systemList>" +
            "<pilot power=\"1\" max=\"3\" room=\"2\"/><shields power=\"2\" max=\"9\" room=\"3\"/><engines power=\"1\" max=\"5\" room=\"4\"/>" +
            "<weapons power=\"3\" max=\"6\" room=\"1\"/>$room0</systemList>" +
            weapons + drones + "<health amount=\"8\"/><maxPower amount=\"20\"/><crewCount amount=\"0\" max=\"0\" class=\"human\"/>" +
            "<cloakImage>auto_assault</cloakImage>$extra</shipBlueprint>"

    private fun weaponList(vararg names: String) =
        "<weaponList missiles=\"5\" count=\"${names.size}\">" + names.joinToString("") { "<weapon name=\"$it\"/>" } + "</weaponList>"

    private fun droneList(vararg names: String) =
        "<droneList drones=\"5\" count=\"${names.size}\">" + names.joinToString("") { "<drone name=\"$it\"/>" } + "</droneList>"

    private val OK_WEAPON = weaponList("LASER_BURST_1")
    private const val PLAYER_CREW = "<crewCount amount=\"2\" class=\"human\"/>"

    /** Enemy ships: case name to the ship's weapons, drones and whatever else it names. */
    private val SHIPS = linkedMapOf(
        "W_EXPL" to Triple(weaponList("MN_W_EXPLOSION"), "", ""),
        "W_IMAGE" to Triple(weaponList("MN_W_IMAGE"), "", ""),
        "W_ART" to Triple(weaponList("MN_W_ART"), "", ""),
        "W_LAUNCH" to Triple(weaponList("MN_W_LAUNCH"), "", ""),
        "W_HIT" to Triple(weaponList("MN_W_HIT"), "", ""),
        "D_IMAGE" to Triple(OK_WEAPON, droneList("MN_D_IMAGE"), ""),
        "D_WEAPON" to Triple(OK_WEAPON, droneList("MN_D_WEAPON"), ""),
        "D_SHOT" to Triple(OK_WEAPON, droneList("MN_D_SHOT"), ""),
        "D_COMBAT" to Triple(OK_WEAPON, droneList("MN_D_COMBAT_IMAGE"), ""),
        "D_BOARDER" to Triple(OK_WEAPON, droneList("MN_D_BOARDER"), ""),
        "S_WEAPON" to Triple(weaponList(MISSING, "LASER_BURST_1"), "", ""),
        "S_DRONE" to Triple(OK_WEAPON, droneList(MISSING), ""),
        "S_AUG" to Triple(OK_WEAPON, "", "<aug name=\"$MISSING\"/>"),
        "S_CREW" to Triple(OK_WEAPON, "", "<crewCount amount=\"1\" class=\"$MISSING\"/>"),
        "S_LOADW" to Triple("<weaponList count=\"1\" missiles=\"5\" load=\"$MISSING\"/>", "", ""),
        "S_LOADD" to Triple(OK_WEAPON, "<droneList count=\"1\" drones=\"5\" load=\"$MISSING\"/>", ""),
        "S_LOADW_ALL" to Triple("<weaponList count=\"1\" missiles=\"5\" load=\"MN_LIST_ALL_MISSING\"/>", "", ""),
        "S_LOADW_SOME" to Triple("<weaponList count=\"1\" missiles=\"5\" load=\"MN_LIST_SOME\"/>", "", ""),
        "X_WRONGW" to Triple(weaponList("SCRAP_COLLECTOR"), "", ""),
        "X_WRONGD" to Triple(OK_WEAPON, droneList("LASER_BURST_1"), ""),
        "X_WRONGA" to Triple(OK_WEAPON, "", "<aug name=\"LASER_BURST_1\"/>"),
        "X_WRONGC" to Triple(OK_WEAPON, "", "<crewCount amount=\"1\" class=\"LASER_BURST_1\"/>"),
    )

    /** Enemy ships with another system in room 0 or another layout or image. */
    private val OTHER_SHIPS = linkedMapOf(
        "X_ARTY" to ship("MN_SHIP_X_ARTY", OK_WEAPON, "", room0 = "<artillery power=\"1\" room=\"0\" weapon=\"$MISSING\"/>"),
        "X_ARTYNONE" to ship("MN_SHIP_X_ARTYNONE", OK_WEAPON, "", room0 = "<artillery power=\"1\" room=\"0\"/>"),
        "X_LAYOUT" to ship("MN_SHIP_X_LAYOUT", OK_WEAPON, "", layout = "mn_no_layout"),
        "X_IMG" to ship("MN_SHIP_X_IMG", OK_WEAPON, "", image = "mn_no_image"),
    )

    /** Start ships: case name to the ship. A run starts on each of them. */
    private val PLAYER_SHIPS = linkedMapOf(
        "P_W" to ship("PLAYER_SHIP_MN_P_W", weaponList(MISSING, "LASER_BURST_1"), "", PLAYER_CREW),
        "P_WLIST" to ship("PLAYER_SHIP_MN_P_WLIST", weaponList("MN_LIST_SOME", "LASER_BURST_1"), "", PLAYER_CREW),
        "P_WALL" to ship("PLAYER_SHIP_MN_P_WALL", weaponList("MN_LIST_ALL_MISSING"), "", PLAYER_CREW),
        "P_D" to ship("PLAYER_SHIP_MN_P_D", OK_WEAPON, droneList(MISSING), PLAYER_CREW),
        "P_A" to ship("PLAYER_SHIP_MN_P_A", OK_WEAPON, "", PLAYER_CREW + "<aug name=\"$MISSING\"/>"),
        "P_C" to ship("PLAYER_SHIP_MN_P_C", OK_WEAPON, "", PLAYER_CREW + "<crewCount amount=\"1\" class=\"$MISSING\"/>"),
        "P_WD" to ship("PLAYER_SHIP_MN_P_WD", weaponList("DEFENSE", "LASER_BURST_1"), "", PLAYER_CREW),
    )

    private const val SHIP_TEXTS = "<destroyed><text>d</text></destroyed><deadCrew><text>d</text></deadCrew><gotaway><text>d</text></gotaway>"

    /** Events: case name to what the event holds. */
    private val EVENTS = linkedMapOf(
        "E_SHIPBLUEPRINT" to "<ship load=\"MN_E_BLUEPRINT\" hostile=\"true\"/>",
        "E_BOARDERS" to "<ship load=\"MN_E_BOARDERS\" hostile=\"true\"/>",
        "E_SHIPLOAD" to "<ship load=\"$MISSING\" hostile=\"true\"/>",
        "E_WEAPON" to "<weapon name=\"$MISSING\"/>",
        "E_DRONE" to "<drone name=\"$MISSING\"/>",
        "E_AUGMENT" to "<augment name=\"$MISSING\"/>",
        "E_LIST" to "<weapon name=\"MN_LIST_ALL_MISSING\"/>",
        "E_LISTSOME" to "<weapon name=\"MN_LIST_SOME\"/>",
        "E_CREW" to "<crewMember amount=\"1\" class=\"$MISSING\"/>",
        "E_CREWLIST" to "<crewMember amount=\"1\" class=\"MN_LIST_CREW\"/>",
        "E_REMOVECREW" to "<removeCrew><class>$MISSING</class><clone>false</clone><text>t</text></removeCrew>",
        "E_REMOVE" to "<remove name=\"$MISSING\"/>",
        "E_BACK" to "<img back=\"$MISSING\"/>",
        "E_PLANET" to "<img planet=\"$MISSING\"/>",
        "E_CHOICEEVENT" to "<choice><text>go</text><event load=\"$MISSING\"/></choice>",
        "E_PLAYSOUND" to "<playSound>$MISSING</playSound>",
        "E_LOADEVENT" to "<loadEvent>$MISSING</loadEvent>",
        "E_MUSIC" to "<playMusic>$MISSING</playMusic>",
        "E_CHANGEBG" to "<changeBackground>$MISSING</changeBackground>",
        "E_STORE" to "<store>STORE</store>",
        "E_QUEST" to "<quest event=\"$MISSING\"/>",
        "E_TEXT" to "<text load=\"$MISSING\"/>",
        "E_SYSTEMWEAPON" to "<weapon name=\"$MISSING\"/><weapon name=\"LASER_BURST_1\"/>",
        "E_MODIFYITEM" to "<item_modify><item type=\"scrap\" min=\"1\" max=\"1\"/></item_modify><autoReward level=\"LOW\">$MISSING</autoReward>",
        "X_EWRONGW" to "<weapon name=\"SCRAP_COLLECTOR\"/>",
        "X_EWRONGA" to "<augment name=\"LASER_BURST_1\"/>",
        "X_EWRONGD" to "<drone name=\"LASER_BURST_1\"/>",
        "X_EWRONGC" to "<crewMember amount=\"1\" class=\"LASER_BURST_1\"/>",
        "X_EREMOVEW" to "<remove name=\"MN_LIST_EMPTY\"/>",
        "X_BOARD" to "<boarders min=\"1\" max=\"1\" class=\"$MISSING\"/>",
    )

    private fun blueprintsXml(): String {
        val out = StringBuilder("<FTL>")
        out.append(weapon("MN_W_EXPLOSION", explosion = MISSING))
        out.append(weapon("MN_W_IMAGE", image = MISSING))
        out.append(weapon("MN_W_ART", art = MISSING))
        out.append(weapon("MN_W_LAUNCH", launch = MISSING))
        out.append(weapon("MN_W_HIT", hit = MISSING, shield = MISSING, miss = MISSING))
        out.append(drone("MN_D_IMAGE", image = MISSING))
        out.append(drone("MN_D_WEAPON", shot = MISSING))
        out.append(weapon("MN_W_SHOT", image = MISSING, explosion = MISSING))
        out.append(drone("MN_D_SHOT", shot = "MN_W_SHOT"))
        out.append(drone("MN_D_COMBAT_IMAGE", kind = "COMBAT", image = MISSING))
        out.append(drone("MN_D_BOARDER", kind = "BOARDER", image = MISSING, shot = null))
        out.append("<blueprintList name=\"MN_LIST_ALL_MISSING\"><name>$MISSING</name></blueprintList>")
        out.append("<blueprintList name=\"MN_LIST_SOME\"><name>$MISSING</name><name>LASER_BURST_1</name></blueprintList>")
        out.append("<blueprintList name=\"MN_LIST_CREW\"><name>$MISSING</name></blueprintList>")
        out.append("<blueprintList name=\"MN_LIST_EMPTY\"></blueprintList>")
        for ((k, v) in SHIPS) out.append(ship("MN_SHIP_$k", v.first, v.second, v.third))
        for (v in OTHER_SHIPS.values) out.append(v)
        for (v in PLAYER_SHIPS.values) out.append(v)
        return out.append("</FTL>").toString()
    }

    private fun eventsXml(): String {
        val out = StringBuilder("<FTL>")
        for (k in SHIPS.keys + OTHER_SHIPS.keys) out.append("<ship name=\"MN_E_$k\" auto_blueprint=\"MN_SHIP_$k\">$SHIP_TEXTS</ship><event name=\"MN_$k\"><text>case $k</text><ship load=\"MN_E_$k\" hostile=\"true\"/></event>")
        out.append("<ship name=\"MN_E_BLUEPRINT\" auto_blueprint=\"$MISSING\">$SHIP_TEXTS</ship>")
        out.append("<ship name=\"MN_E_BOARDERS\" auto_blueprint=\"AUTO_ASSAULT\"><boarders min=\"1\" max=\"1\" class=\"$MISSING\"/>$SHIP_TEXTS</ship>")
        for ((k, v) in EVENTS) out.append("<event name=\"MN_$k\">" + (if (k == "E_TEXT") "" else "<text>case $k</text>") + v + "</event>")
        return out.append("</FTL>").toString()
    }

    private fun append(state: InGameState) {
        if (state.blueprintManager.getOrNull("MN_W_EXPLOSION") != null) return
        state.blueprintManager.diagnosticAppend(state.eventManager.df, BrowserXml.parse(blueprintsXml()).rootElement.clone() as Element)
        state.blueprintManager.finishLoading(state.content)
        state.eventManager.diagnosticAppend(BrowserXml.parse(eventsXml()))
    }

    private fun startEvent(state: InGameState, name: String) {
        if (HS.active) xyz.znix.xftl.hs.events.HsFlow.loadEvent(state, state.shipUI.hsHost, name, true, -1, null)
        // The loader drops an event it cannot read.
        else if (state.eventManager.getOrNull(name) != null) state.debugShowEvent(name)
    }

    private fun play(main: MainGame, container: GameContainer, graphics: Graphics?, state: InGameState, seconds: Int) {
        for (i in 0 until seconds * 20) {
            main.update(container, .05f)
            val now = main.currentState as? InGameState ?: return
            hullLost += now.player.maxHealth - now.player.health
            now.player.health = now.player.maxHealth
            for (crew in now.player.crew) if (crew.mode == xyz.znix.xftl.crew.AbstractCrew.SlotType.CREW) crew.health = crew.maxHealth
            if (i % 10 == 0) BrowserDiagnostics.closeBoxes(main, container)
            if (i % 20 == 0) fireAtEnemy(now)
            if (now.isPaused && !now.shipUI.isWindowOpen) now.toggleQuickPause()
            if (graphics != null && i % 40 == 0) main.render(container, graphics)
        }
    }

    /** The player's whole battery goes off: a defence drone shoots at the missiles, and the enemy is kept whole. */
    private fun fireAtEnemy(state: InGameState) {
        val enemy = state.enemy ?: return
        val weapons = state.player.weapons ?: return
        enemy.health = enemy.maxHealth
        state.player.missilesCount = 20
        for ((slot, mount) in state.player.hardpoints.withIndex()) {
            val weapon = mount.weapon ?: continue
            weapon.forceSetPowered(true)
            weapon.timeCharged = weapon.chargeTime
            weapons.selectedTargets.targetRoom(slot, enemy.rooms.first())
        }
        weapons.selectedTargets.fireChargedWeapons()
    }

    /** What the last case's fight had in it. */
    private var seen = ""
    private var hullLost = 0

    /** One case: the event, a fight of [seconds], a save and load, a little more play. Null when the game went on. */
    private fun case(main: MainGame, container: GameContainer, graphics: Graphics?, name: String, seconds: Int): String? = try {
        hullLost = 0
        var state = BrowserDiagnostics.freshState(main, container, "PLAYER_SHIP_HARD", Difficulty.NORMAL)
        append(state)
        if (name in PLAYER_SHIPS) state = BrowserDiagnostics.freshState(main, container, "PLAYER_SHIP_MN_$name", Difficulty.NORMAL)
        else startEvent(state, "MN_$name")
        play(main, container, graphics, state, seconds)
        val now = main.currentState as InGameState
        seen = "hullLost=$hullLost enemy=${now.enemy?.type?.name} weapons=${now.enemy?.hardpoints?.map { it.weapon?.type?.name }} drones=${now.enemy?.drones?.drones?.map { it?.type?.name }}" +
            " crew=${now.player.crew.size} playerweapons=${now.player.hardpoints.map { it.weapon?.type?.name }}"
        // A save of pretended mods has no mod set to load under; tests/mods-play.mjs loads a real one.
        if (!pretending) {
            main.loadSavedGame(BrowserXml.parse(BrowserXml.write(now.saveGameState())))
            BrowserDiagnostics.closeBoxes(main, container)
            play(main, container, graphics, main.currentState as InGameState, 2)
        }
        null
    } catch (e: Throwable) {
        (e.message ?: e.toString()).take(300)
    }

    private val ALL get() = SHIPS.keys + OTHER_SHIPS.keys + EVENTS.keys + PLAYER_SHIPS.keys

    /** `hsRuntimeProbe("mn CASE...")`. */
    fun probe(main: MainGame, container: GameContainer, only: List<String>): String = asModded {
        val out = StringBuilder()
        for (name in only.ifEmpty { ALL }) {
            out.append(name).append('|').append(case(main, container, null, name, 12) ?: "ok").append('|').append(seen).append('\n')
        }
        out.toString()
    }

    /** `hsRuntimeProbe("mn-show EVENT")`: the event's box in the running game. */
    fun show(main: MainGame, name: String): String {
        (main.currentState as InGameState).debugShowEvent(name)
        return "ok"
    }

    /** Without mods this runs as a data mod without Hyperspace would: vanilla play never meets a bad name. */
    private inline fun <T> asModded(block: () -> T): T {
        val was = HS.modded
        pretending = !was
        HS.modded = true
        try { return block() } finally { HS.modded = was }
    }

    private var pretending = false

    fun check(main: MainGame, container: GameContainer, graphics: Graphics): String = asModded {
        val failed = ArrayList<String>()
        val all = ALL
        for (name in all) case(main, container, graphics, name, 3)?.let { failed.add("$name: $it") }
        check(failed.isEmpty()) { "Missing names: " + failed.joinToString("; ") }
        "missing names: ${all.size} kinds"
    }
}
