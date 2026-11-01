// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Gameplay/CustomFleetShips.cpp (the browser engine is
// AGPL-3.0, see docs/HYPERSPACE.md).
package xyz.znix.xftl.hs.events

import ftl.browser.NativeRandom
import org.jdom2.Element
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.hs.HS
import org.lwjgl.opengl.GL11
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics

/**
 * `<fleetDef>` and the `<customFleet>`/`<clearCustomFleet>` event actions: background fleet ships drawn instead of
 * the vanilla fleet, in five layers, from the left and/or the right side, plus the big ship of a firing fleet.
 */
object HsCustomFleet {
    class FleetDefinition(val name: String) {
        val shipTextures = Array(5) { ArrayList<String>() }
        val numShips = IntArray(5)
        var bigShip = ""
    }

    class FleetShip(val image: String, val x: Int, val y: Int, val mirror: Boolean, val autoDarkening: Boolean)

    private val defs = HashMap<String, FleetDefinition>()
    private val layers = Array(5) { ArrayList<FleetShip>() }
    private var left = EventFleet()
    private var right = EventFleet()

    /** SpaceManager::fleetShip: the big ship shown while the ASB is live (fleet_big unless a firing fleet set it). */
    var bigShip = "fleet_big"; private set

    val active: Boolean get() = layers.any { it.isNotEmpty() }

    /** Diagnostics: the fleets' names, the ships per layer and their places. */
    val diagnosticState: String
        get() = "${left.fleetDefName}/${right.fleetDefName}|" + layers.joinToString(",") { it.size.toString() } + "|" +
            layers.flatMap { it }.joinToString(";") { "${it.x},${it.y}" }

    fun reset() {
        defs.clear()
        clear()
    }

    /** ParseFleetShipNode: `<fleetDef name>` (default "default") with `<ship layer>`, `<numShips layer>`, `<bigShip>`. */
    fun parse(node: Element) {
        val def = FleetDefinition(node.getAttributeValue("name") ?: "default")
        for (child in node.children) {
            val layer = child.getAttributeValue("layer")?.trim()?.toIntOrNull() ?: 0
            when (child.name) {
                "ship" -> def.shipTextures[layer].add(child.text)
                "numShips" -> def.numShips[layer] = child.text.trim().toInt()
                "bigShip" -> def.bigShip = child.text
            }
        }
        defs[def.name] = def
    }

    /** ClearCustomFleet: no fleet ships, fleet_big as the big ship, no fleets recorded. */
    fun clear() {
        bigShip = "fleet_big"
        for (l in layers) l.clear()
        left = EventFleet()
        right = EventFleet()
    }

    /** The WorldManager::CreateLocation hook (after the original): clear, then the arrival event's fleets. */
    fun createLocation(custom: CustomEvent?) {
        clear()
        if (custom != null) createFull(custom.leftFleet, custom.rightFleet)
    }

    /** The WorldManager::UpdateLocation hook (after the original). */
    fun updateLocation(custom: CustomEvent?) {
        custom ?: return
        if (custom.clearCustomFleet) clear()
        if ((custom.leftFleet.fleetDefName.isNotEmpty() || custom.rightFleet.fleetDefName.isNotEmpty()) &&
            !(custom.leftFleet.fleetDefName == left.fleetDefName && custom.rightFleet.fleetDefName == right.fleetDefName)
        ) {
            clear()
            createFull(custom.leftFleet, custom.rightFleet)
        }
    }

    /** CreateFullFleet: the left fleet (over the whole width when there is no right one), then the right one. */
    private fun createFull(l: EventFleet, r: EventFleet) {
        if (l.fleetDefName.isNotEmpty()) {
            create(def(l.fleetDefName), false, l.firing, l.autoDarkening, r.fleetDefName.isEmpty())
            left = copy(l)
        }
        if (r.fleetDefName.isNotEmpty()) {
            create(def(r.fleetDefName), true, r.firing, r.autoDarkening, false)
            right = copy(r)
        }
    }

    // g_fleetDefs[name] default-inserts an empty definition.
    private fun def(name: String) = defs.getOrPut(name) { FleetDefinition(name) }

    private fun copy(f: EventFleet) = EventFleet().also { it.fleetDefName = f.fleetDefName; it.firing = f.firing; it.autoDarkening = f.autoDarkening }

    /**
     * CreateFleetShips: per layer, numShips ships, each with random32 draws (the global stream) for the texture, x in
     * [minX, maxX], y within its 720/n band, then a random insertion index into the layer when it is not empty.
     */
    private fun create(def: FleetDefinition, rightSide: Boolean, firing: Boolean, autoDarkening: Boolean, fillFullSpace: Boolean) {
        val minX = if (fillFullSpace) -40 else if (rightSide) 540 else -40
        val maxX = if (fillFullSpace) 1280 else if (rightSide) 1280 else 720
        for (layer in 0 until 5) {
            val textures = def.shipTextures[layer]
            if (textures.isEmpty()) continue
            val n = def.numShips[layer]
            for (i in 0 until n) {
                val tex = textures[Integer.remainderUnsigned(NativeRandom.next31(), textures.size)]
                val x = NativeRandom.next31() % ((maxX + 1) - minX) + minX
                val band = 720 / n
                val y = band * i + NativeRandom.next31() % band
                val ship = FleetShip(tex, x, y, rightSide, autoDarkening)
                val list = layers[layer]
                if (list.isNotEmpty()) list.add(NativeRandom.next31() % list.size, ship) else list.add(ship)
            }
        }
        if (firing) bigShip = def.bigShip
    }

    /**
     * SpaceManager::OnRenderFleet with fleet ships (CustomFleetShips.cpp): per ship, in layer order, its shadow (black,
     * 0.6, 5 px down) drawn only where an earlier ship was (stencil USE of the 0x80 bit), then the ship marked into the
     * stencil (SET: nothing drawn), then the ship itself under the background tint, darkened step by step when
     * autoDarkening, mirrored on the right side; the stencil is cleared at the end, then the big ship (tinted) while
     * the ASB is live. Returns false when there are none (the vanilla fleet draws).
     */
    @JvmStatic
    fun render(game: InGameState, g: Graphics, tint: Colour, paused: Boolean, pds: Boolean): Boolean {
        if (!HS.active || !active) return false
        val count = layers.sumOf { it.size }
        var maxTint = if (paused) 0.4f else tint.g
        maxTint *= 0.6f
        var opacity = maxTint
        val step = maxTint / count
        GL11.glClear(GL11.GL_STENCIL_BUFFER_BIT)
        GL11.glStencilMask(0xff)
        for (layer in layers) for (ship in layer) {
            val img = game.getImg("img/ship/fleet/${ship.image}.png")
            fun drawShip(dy: Float, colour: Colour) {
                if (ship.mirror) {
                    g.pushTransform()
                    g.translate(ship.x + img.width.toFloat(), ship.y + dy)
                    g.scale(-1f, 1f)
                    img.draw(0f, 0f, colour)
                    g.popTransform()
                } else img.draw(ship.x.toFloat(), ship.y + dy, colour)
            }
            // STENCIL_USE: only where an earlier ship's silhouette is marked
            GL11.glEnable(GL11.GL_STENCIL_TEST)
            GL11.glStencilFunc(GL11.GL_EQUAL, 0x80, 0x80)
            GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP)
            drawShip(5f, Colour(0f, 0f, 0f, 0.6f))
            // STENCIL_SET: every pixel fails (nothing drawn) and writes the reference
            GL11.glStencilFunc(GL11.GL_NEVER, 0x80, 0x80)
            GL11.glStencilOp(GL11.GL_REPLACE, GL11.GL_KEEP, GL11.GL_KEEP)
            drawShip(0f, tint)
            // STENCIL_IGNORE
            GL11.glDisable(GL11.GL_STENCIL_TEST)
            val colour = if (ship.autoDarkening) {
                Colour(tint.r - opacity, tint.g - opacity, tint.b - opacity, 1f).also { opacity -= step }
            } else Colour.white
            drawShip(0f, colour)
        }
        GL11.glClear(GL11.GL_STENCIL_BUFFER_BIT)
        if (pds) game.getImg("img/ship/fleet/$bigShip.png").draw(550f, 447f, tint)
        return true
    }

    // SpaceManager::SaveSpace / LoadSpace: the two fleets (name, firing, autoDarkening); loading creates them again.
    fun write(elem: Element) {
        for ((tag, f) in listOf("left" to left, "right" to right)) {
            if (f.fleetDefName.isEmpty()) continue
            elem.addContent(Element(tag).setAttribute("name", f.fleetDefName)
                .setAttribute("firing", f.firing.toString()).setAttribute("autoDarkening", f.autoDarkening.toString()))
        }
    }

    fun read(elem: Element?) {
        clear()
        elem ?: return
        fun fleet(tag: String) = EventFleet().also { f ->
            elem.getChild(tag)?.let {
                f.fleetDefName = it.getAttributeValue("name") ?: ""
                f.firing = it.getAttributeValue("firing") == "true"
                f.autoDarkening = it.getAttributeValue("autoDarkening") == "true"
            }
        }
        createFull(fleet("left"), fleet("right"))
    }
}
