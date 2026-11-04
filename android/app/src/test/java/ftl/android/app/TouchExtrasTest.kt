package ftl.android.app

import org.jdom2.Element
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        assertEquals(Zone.SHELF, TouchExtras.zoneForPlace(rect(105, 110, 147, 153), 1))
        assertEquals(Zone.CREW, TouchExtras.zoneForPlace(rect(110, 300, 150, 340), 1))
        // Lua drawing in the top bar, such as a speed control: the top row.
        assertEquals(Zone.TOP_END, TouchExtras.zoneForPlace(rect(700, 20, 760, 60), 1))
    }

    /** Three of Multiverse's UI_STORAGE_CHECK_BUTTON definitions, in [order] (a changed mod list parses them in another). */
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

    /** The game files of [storageDefs]: Multiverse has no `top_storage_on_on.png`, so the location-0 definition draws nothing. */
    private val multiverseImages = { path: String -> path == "img/statusUI/top_storage_on.png" }

    private fun entry(idx: Int, vararg attrs: Pair<String, String>) = Element("b").setAttribute("idx", "$idx")
        .setAttribute("name", "UI_STORAGE_CHECK_BUTTON").also { e -> for ((k, v) in attrs) e.setAttribute(k, v) }

    @Test
    fun theStorageCrateLostByAnOlderSaveComesBack() {
        // 2.0.1 loaded a button by its name, the last definition parsed: SPAWN_STORAGE's at location 0, which no event
        // loads and which draws nothing. Its save kept that index and name, and 2.0.2 kept location and image too.
        // As in Multiverse, that definition is the last of its name parsed (index 2 here).
        for (old in listOf(entry(2), entry(2, "loc" to "0", "img" to "statusUI/top_storage_on"), entry(7))) {
            HsEventButtons.resetDefs(multiverseImages)
            storageDefs(listOf(0, 2, 1))
            HsEventButtons.read(Element("eventButtons").addContent(old))
            val back = HsEventButtons.buttons().single()
            assertEquals(old.attributes.toString(), 2, back.location)
            assertEquals("statusUI/top_storage", back.image)
            assertEquals(0, back.idx)
        }
        // Without the game files to look at, the saved definition stays as it is.
        HsEventButtons.resetDefs()
        storageDefs(listOf(0, 2, 1))
        HsEventButtons.read(Element("eventButtons").addContent(entry(2)))
        assertEquals(0, HsEventButtons.buttons().single().location)
    }

    @Test
    fun aSavedButtonComesBackByItsIndexAsNativeLoads() {
        // The same mods give the same numbers in every session (EventButtonManager::Load takes the index): two definitions
        // that look the same come back as the one saved.
        HsEventButtons.resetDefs(multiverseImages)
        storageDefs(listOf(0, 1, 2))
        HsEventButtons.read(Element("eventButtons").addContent(entry(2, "loc" to "2", "img" to "statusUI/top_storage")))
        assertEquals(2, HsEventButtons.buttons().single().idx)
    }

    @Test
    fun aMeasureDoesNotMoveForFloatNoise() {
        // The speed control's quads mapped back through its own transform: 530 one frame, 529.99997 the next.
        val first = TouchExtras.settle(null, floatArrayOf(529.99997f, -6.00002f, 616.00003f, 29.99998f))
        assertEquals(listOf(530f, -6f, 616f, 30f), first.toList())
        val again = TouchExtras.settle(first, floatArrayOf(530.00003f, -5.99997f, 615.99996f, 30.00002f))
        assertTrue("noise keeps the measure in use", again === first)
        // A real change of the drawing (the button row grew by a pixel) is taken.
        assertEquals(listOf(530f, -6f, 617f, 30f), TouchExtras.settle(first, floatArrayOf(530f, -6f, 617f, 30f)).toList())
        // Half pixels stay half pixels.
        assertEquals(10.5f, TouchExtras.settle(null, floatArrayOf(10.5f, 0f, 20f, 20f))[0])
    }

    @Test
    fun theShelfLinesUpMultiversesButtonsByTheirArt() {
        // Jukebox (338,46), toggle (105,110) and the addon icon (115,5): images and their solid art (alpha >= 128).
        val desks = listOf(rect(338, 46, 380, 89), rect(105, 110, 147, 153), rect(115, 5, 139, 19))
        val solids = listOf(rect(343, 46, 375, 76), rect(110, 115, 142, 148), rect(118, 6, 137, 18))
        val places = TouchExtras.shelfLayout(desks, solids, 8f, 400f, 63f, 46f, 1.25f)
        // One scale: the desktop's sizes relative to each other; the tallest image fills the shelf.
        assertEquals(1, places.map { it[0] }.toSet().size)
        assertEquals(46f / 43f, places[0][0], 1e-4f)
        val art = solids.zip(places).map { (sd, p) -> floatArrayOf(sd[0] * p[0] + p[1], sd[1] * p[0] + p[2], sd[2] * p[0] + p[1], sd[3] * p[0] + p[2]) }
        // The art's middles on one line, the shelf's.
        for (a in art) assertEquals(63f + 23f, (a[1] + a[3]) / 2, 1e-3f)
        // The same gap between each one's art and the next one's.
        assertEquals(TouchExtras.SHELF_GAP, art[1][0] - art[0][2], 1e-3f)
        assertEquals(TouchExtras.SHELF_GAP, art[2][0] - art[1][2], 1e-3f)
        // The images stay inside the shelf.
        for ((d, p) in desks.zip(places)) assertTrue(d[3] * p[0] + p[2] - (d[1] * p[0] + p[2]) <= 46f + 1e-3f)
    }

    @Test
    fun theProbeSeesAnImagesSolidArt() {
        // The jukebox image (42x43, solid 5..37 x 0..30) drawn at (338,46) at twice its size: one quad, six vertices.
        val tex = xyz.znix.xftl.rendering.Texture(42, 43, 42, 43, 0)
        tex.solid = intArrayOf(5, 0, 37, 30)
        val probe = xyz.znix.xftl.rendering.DrawProbe()
        probe.newDraw(tex)
        val (x0, y0, x1, y1) = listOf(338f, 46f, 338f + 84f, 46f + 86f)
        for ((x, y, u, v) in listOf(listOf(x0, y0, 0f, 0f), listOf(x1, y0, 1f, 0f), listOf(x0, y1, 0f, 1f),
                listOf(x1, y0, 1f, 0f), listOf(x1, y1, 1f, 1f), listOf(x0, y1, 0f, 1f))) probe.point(x, y, u, v, 1f)
        probe.end()
        assertEquals(listOf(x0, y0, x1, y1), probe.rects.single().toList())
        val solid = probe.solids.single()
        assertEquals(338f + 10f, solid[0], 1e-3f)
        assertEquals(46f, solid[1], 1e-3f)
        assertEquals(338f + 74f, solid[2], 1e-3f)
        assertEquals(46f + 60f, solid[3], 1e-3f)
        // A colour fill is solid where it is.
        probe.reset()
        probe.newDraw(null)
        probe.point(0f, 0f, 0f, 0f, 1f)
        probe.point(10f, 10f, 0f, 0f, 1f)
        probe.end()
        assertEquals(probe.rects.single().toList(), probe.solids.single().toList())
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
