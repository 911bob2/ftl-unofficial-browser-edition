package ftl.android.app

import org.jdom2.Element
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import xyz.znix.xftl.game.TouchExtras
import xyz.znix.xftl.game.TouchExtras.Zone
import xyz.znix.xftl.hs.render.HsEventButtons

/**
 * Where the touch layout puts desktop HUD elements it has no code for (TouchExtras). The whole path, a made-up mod's
 * button drawn, placed and pressed by a finger, is tools/android-touch/extras.mjs on the emulator.
 */
class TouchExtrasTest {
    // An empty hyperspace.xml: the event buttons take their default colours from it.
    @Before
    fun hyperspace() {
        xyz.znix.xftl.hs.HS.config = xyz.znix.xftl.hs.HsConfig.parse(Element("FTL"))
        xyz.znix.xftl.hs.HS.active = true
    }

    @After
    fun vanilla() {
        xyz.znix.xftl.hs.HS.active = false
        xyz.znix.xftl.hs.HS.config = null
    }

    private fun child(name: String, text: String? = null, vararg attrs: Pair<String, String>) =
        Element(name).also { e -> text?.let { e.text = it }; for ((k, v) in attrs) e.setAttribute(k, v) }

    /** A mod's `<eventButton>`, as an event file has it. */
    private fun modButton(name: String, place: Element): HsEventButtons.Def {
        val node = Element("eventButton").setAttribute("name", name)
        node.addContent(child("event", null, "load" to "${name}_EVENT"))
        node.addContent(child("image", "statusUI/top_ship"))
        node.addContent(place)
        return HsEventButtons.parse(node) { e, _ -> e.getAttributeValue("load") ?: "" }!!
    }

    private fun rect(x0: Int, y0: Int, x1: Int, y1: Int) = floatArrayOf(x0.toFloat(), y0.toFloat(), x1.toFloat(), y1.toFloat())

    @Test
    fun aModButtonInTheTopBarGetsItsPlaceInTheTopRowByItsLocation() {
        HsEventButtons.resetDefs()
        val expected = mapOf(0 to Zone.SECOND_ROW, 1 to Zone.BEFORE_SHIP, 2 to Zone.AFTER_SHIP, 3 to Zone.AFTER_STORE, 4 to Zone.TOP_END, 9 to Zone.TOP_END)
        for ((location, zone) in expected) {
            val def = modButton("MADE_UP_$location", child("location", "$location"))
            assertEquals(location, def.location)
            assertEquals("${def.name}_EVENT", def.event)
            assertEquals(zone, TouchExtras.zoneForLocation(def.location))
        }
    }

    @Test
    fun aModButtonAnywhereElseGoesWhereTheDesktopDrawsIt() {
        HsEventButtons.resetDefs()
        val def = modButton("MADE_UP_CUSTOM", child("customLocation", null, "x" to "900", "y" to "400"))
        assertNull(TouchExtras.zoneForLocation(def.location))
        // Its image measured at the custom location: the dock above the pause button.
        assertEquals(Zone.DOCK, TouchExtras.zoneForPlace(rect(def.customX, def.customY, def.customX + 74, def.customY + 55), 1))
        // Multiverse's jukebox and toggle buttons: the shelf under the status; a button by the crew list: under it.
        assertEquals(Zone.SHELF, TouchExtras.zoneForPlace(rect(338, 46, 380, 89), 1))
        assertEquals(Zone.SHELF, TouchExtras.zoneForPlace(rect(104, 109, 148, 153), 1))
        assertEquals(Zone.CREW, TouchExtras.zoneForPlace(rect(110, 300, 150, 340), 1))
        // Lua drawing in the top bar, such as a speed control: the top row.
        assertEquals(Zone.TOP_END, TouchExtras.zoneForPlace(rect(700, 20, 760, 60), 1))
    }

    /** Multiverse's three UI_STORAGE_CHECK_BUTTON definitions, in [order] (the parse order differs between sessions). */
    private fun storageDefs(order: List<Int>): List<Pair<Element, HsEventButtons.Def>> = order.map { i ->
        val node = Element("eventButton").setAttribute("name", "UI_STORAGE_CHECK_BUTTON")
        node.addContent(child("event", null, "load" to "STORAGE_CHECK_BUTTON"))
        node.addContent(child("image", if (i == 1) "statusUI/top_storage_on" else "statusUI/top_storage"))
        node.addContent(child("location", if (i == 1) "0" else "2"))
        node to HsEventButtons.parse(node) { e, _ -> e.getAttributeValue("load") ?: "" }!!
    }

    @Test
    fun aSavedEventButtonComesBackAsTheDefinitionTheRunHad() {
        // The run's button is the location-2 crate; the save is read in a session that parsed the location-0 one last.
        HsEventButtons.resetDefs()
        val first = storageDefs(listOf(0, 1, 2))
        HsEventButtons.apply(emptyList(), listOf(first[0].first))
        val save = Element("eventButtons")
        HsEventButtons.write(save)
        HsEventButtons.resetDefs()
        storageDefs(listOf(2, 0, 1))
        HsEventButtons.read(save)
        val back = HsEventButtons.buttons().single()
        assertEquals(2, back.location)
        assertEquals("statusUI/top_storage", back.image)
        // A save from before the location and image were kept: its index, while the name there matches.
        HsEventButtons.resetDefs()
        storageDefs(listOf(0, 1, 2))
        HsEventButtons.read(Element("eventButtons").addContent(Element("b").setAttribute("idx", "0").setAttribute("name", "UI_STORAGE_CHECK_BUTTON")))
        assertEquals(2, HsEventButtons.buttons().single().location)
    }

    @Test
    fun drawingInSeveralPlacesOrAPanelKeepsTheDesktopLayout() {
        assertEquals(Zone.OVERLAY, TouchExtras.zoneForPlace(rect(100, 100, 140, 140), 2))
        assertEquals(Zone.OVERLAY, TouchExtras.zoneForPlace(rect(400, 200, 900, 400), 1))
        assertEquals(Zone.DOCK, TouchExtras.zoneForPlace(null, 0))
    }

    @Test
    fun nearbyDrawsAreOnePieceAndFarOnesAreNot() {
        // Three speed buttons a few pixels apart and the number between them.
        val speed = listOf(rect(530, -6, 566, 30), rect(555, -6, 591, 30), rect(580, -6, 616, 30))
        assertEquals(1, TouchExtras.cluster(speed).size)
        assertEquals(listOf(530f, -6f, 616f, 30f), TouchExtras.cluster(speed)[0].toList())
        // An addon icon at the top left and a tutorial box in the middle of the screen.
        assertEquals(2, TouchExtras.cluster(listOf(rect(115, 5, 139, 19), rect(600, 300, 900, 450))).size)
        // A chain that only joins through its middle.
        assertEquals(1, TouchExtras.cluster(listOf(rect(0, 0, 10, 10), rect(100, 0, 110, 10), rect(30, 0, 80, 10))).size)
    }
}
