package ftl.android.app.debug

import android.util.Log
import ftl.android.app.game.Input
import ftl.browser.BrowserEngine
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.PlayerShipUI
import xyz.znix.xftl.game.TouchHud
import xyz.znix.xftl.game.TouchRouter
import xyz.znix.xftl.math.Point
import xyz.znix.xftl.systems.SelectedTarget
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Debug builds only: what tools/android-touch asks the running game. "touchstate <tag>" logs one JSON line of the touch
 * and aiming state on the game thread, under the tag FTL-Touch; "touchtrace on" logs every MotionEvent the view receives.
 */
object TouchProbe {
    private const val TAG = "FTL-Touch"

    fun trace(on: Boolean) {
        Input.debugTrace = if (on) { e ->
            val b = StringBuilder("me ").append(android.view.MotionEvent.actionToString(e.action)).append(" n=").append(e.pointerCount)
            for (i in 0 until e.pointerCount) b.append(" [id=").append(e.getPointerId(i)).append(' ').append(e.getX(i).toInt()).append(',').append(e.getY(i).toInt()).append(']')
            Log.i(TAG, b.toString())
        } else null
    }

    private fun field(o: Any, name: String): Any? {
        var c: Class<*>? = o.javaClass
        while (c != null) {
            runCatching { return c!!.getDeclaredField(name).apply { isAccessible = true }.get(o) }
            c = c.superclass
        }
        error("no field $name on ${o.javaClass.name}")
    }

    private fun str(v: Any?): String = when (v) {
        null -> "null"
        is Number, is Boolean -> v.toString()
        is Map<*, *> -> v.entries.joinToString(",", "{", "}") { "\"${it.key}\":${str(it.value)}" }
        is List<*> -> v.joinToString(",", "[", "]") { str(it) }
        else -> "\"" + v.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }

    private fun aim(b: SelectedTarget.BeamAim): Map<String, Any?> {
        val s = b.startShipPoint
        return mapOf(
            "sx" to s.x, "sy" to s.y,
            "ex" to s.x + (b.length * cos(b.angle)).roundToInt(), "ey" to s.y + (b.length * sin(b.angle)).roundToInt(),
            "angle" to b.angle, "len" to b.length, "visible" to b.visible, "rooms" to b.hitRooms.map { it.id },
        )
    }

    fun state(tag: String) {
        val out = LinkedHashMap<String, Any?>()
        out["tag"] = tag
        out["routerPrimary"] = field(TouchRouter, "primary")
        out["routerHud"] = field(TouchRouter, "toHud")
        out["canvasW"] = BrowserEngine.canvasWidth()
        out["canvasH"] = BrowserEngine.canvasHeight()
        val game = BrowserEngine.getGame()?.currentState as? InGameState
        out["flight"] = game != null
        if (game != null) {
            val ui = game.shipUI as PlayerShipUI
            val hud = ui.touch as TouchHud
            out["paused"] = game.isPaused
            out["console"] = game.isDebugConsoleVisible
            out["keyboard"] = xyz.znix.xftl.game.TouchMode.keyboardWanted
            out["jumping"] = game.isPlayerJumping
            out["hudPrimary"] = field(hud, "primary")
            out["extra"] = field(hud, "extraFingers").let { (it as List<*>).toList() }
            out["focusPlayer"] = field(hud, "focusOnPlayer")
            out["hudX"] = xyz.znix.xftl.game.TouchMode.hudX
            out["clickEvent"] = game.clickEvent != null
            out["armedSlot"] = field(ui, "targetingSelectedWeapon")
            out["window"] = field(ui, "currentWindow")?.javaClass?.simpleName
            out["beam"] = (field(ui, "beamTargeting") as? SelectedTarget.BeamAim)?.let(::aim)
            val player = game.player
            // TouchExtras: each desktop HUD piece, where it is on the canvas (HUD x + hudX), its zone and what drew there
            out["extras"] = hud.extras.all.map { p ->
                val placed = p.placed && p.desk != null
                mapOf("key" to p.key, "zone" to p.zone.name, "placed" to placed,
                    "x0" to if (placed) (p.x0() + xyz.znix.xftl.game.TouchMode.hudX).roundToInt() else null,
                    "y0" to if (placed) p.y0().roundToInt() else null,
                    "x1" to if (placed) (p.x1() + xyz.znix.xftl.game.TouchMode.hudX).roundToInt() else null,
                    "y1" to if (placed) p.y1().roundToInt() else null,
                    "desk" to p.desk?.map { Math.round(it * 100) / 100.0 },
                    // The solid art's place on the canvas, the scale and how often the place changed between frames
                    "solid" to if (placed) p.solidPlace().mapIndexed { i, v ->
                        Math.round((v + if (i % 2 == 0) xyz.znix.xftl.game.TouchMode.hudX else 0) * 100) / 100.0 } else null,
                    "s" to Math.round(p.s * 10000) / 10000.0, "moves" to p.moves,
                    "active" to (p as? xyz.znix.xftl.game.TouchExtras.ButtonPiece)?.button?.disabled?.not())
            }
            out["danger"] = game.isInDanger
            out["eventButtonClicked"] = xyz.znix.xftl.hs.render.HsEventButtons.lastClicked
            out["speedLevel"] = xyz.znix.xftl.hs.lua.LuaFps.speedLevel
            out["speedEnabled"] = xyz.znix.xftl.hs.lua.LuaFps.speedEnabled
            out["moreInfo"] = xyz.znix.xftl.hs.render.HsOverlay.altMode
            out["hsActive"] = xyz.znix.xftl.hs.HS.active
            out["luaActive"] = xyz.znix.xftl.hs.HS.luaActive
            out["doors"] = player.doors.map { d -> mapOf("x" to d.offsetX, "y" to d.offsetY, "v" to d.isVertical, "open" to d.open) }
            out["shipOrigin"] = Point(0, 0).let { game.convertScreenToPlayerShip(it); listOf(it.x, it.y) }
            out["crewSel"] = (field(ui, "selectedCrew") as List<*>).size
            out["hull"] = player.health
            val weapons = player.weapons
            out["slots"] = player.hardpoints.map { h ->
                h.weapon?.let { mapOf("id" to it.type.name, "on" to it.isPowered, "cur" to it.timeCharged, "charge" to it.chargeTime) }
            }
            val targets = weapons?.selectedTargets?.let { field(it, "targets") as Map<*, *> }
            out["targets"] = targets?.entries?.associate { (k, v) ->
                k.toString() to if (v is SelectedTarget.BeamAim) aim(v) else (v as? SelectedTarget.RoomAim)?.let { mapOf("room" to it.room.id) }
            }
            val enemy = game.enemy
            out["enemy"] = enemy?.let { e ->
                val rooms = e.rooms.map { r ->
                    val px = (r.x + e.offset.x) * 35
                    val py = (r.y + e.offset.y) * 35
                    val a = game.enemyShipRenderToScreen(px, py)
                    val c = game.enemyShipRenderToScreen(px + r.width * 35, py + r.height * 35)
                    mapOf("id" to r.id, "x" to px, "y" to py, "w" to r.width * 35, "h" to r.height * 35,
                        "cx0" to a.x, "cy0" to a.y, "cx1" to c.x, "cy1" to c.y)
                }
                val probe = Point(0, 0)
                game.convertScreenToEnemyShip(probe)
                mapOf(
                    "hull" to e.health, "superShield" to e.superShield, "sysHealth" to e.systems.sumOf { it.undamagedEnergy }, "dead" to e.isDead,
                    "offsetX" to e.offset.x, "offsetY" to e.offset.y,
                    "originX" to probe.x, "originY" to probe.y, "rooms" to rooms,
                )
            }
        }
        // logcat cuts a line at about 4000 bytes: a longer answer comes in numbered parts ("part <tag> i/n ...").
        val json = str(out)
        if (json.length <= PART) Log.i(TAG, json)
        else {
            val n = (json.length + PART - 1) / PART
            for (i in 0 until n) Log.i(TAG, "part $tag ${i + 1}/$n " + json.substring(i * PART, minOf(json.length, (i + 1) * PART)))
        }
    }

    private const val PART = 3000
}
