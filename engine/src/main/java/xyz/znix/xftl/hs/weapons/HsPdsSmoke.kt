// Hyperspace (GPL-3.0-or-later) 1.23.2 CustomDamage.cpp: SpaceManager::CreatePDSFire's smoke puffs.
package xyz.znix.xftl.hs.weapons

import xyz.znix.xftl.Ship
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.rendering.Graphics

/**
 * CreatePDSFire(..., smoke = true) adds `Animation("effects/fire_smoke.png", 7, 1.f, Pointf(x - 8.5, y - 8.5),
 * 238, 34, 0, -1)` to a static list and starts it. The SpaceManager::OnLoop hook updates every puff before the
 * native loop and drops the Done ones; the OnRenderProjectiles hook draws them for ship 0, layer 2, before the
 * native projectiles, with OnRender(1, white, false). The Animation constructor (0x5bad30) leaves fScale (+0x78)
 * at 1, so unlike UpdatePDSFire's own half-scale smoke the puff is drawn 34 px wide from (x - 8.5, y - 8.5).
 */
object HsPdsSmoke {
    private class Puff(val x: Float, val y: Float) { var time = 0f }

    private val puffs = ArrayList<Puff>()
    private var owner: InGameState? = null

    /** The puffs of [game]; the list is static in HS, but the browser drops a previous game's. */
    private fun of(game: InGameState): ArrayList<Puff> {
        if (owner !== game) { puffs.clear(); owner = game }
        return puffs
    }

    fun add(game: InGameState, x: Int, y: Int) {
        // Animation::Start(true): from frame 0.
        of(game) += Puff(x - 8.5f, y - 8.5f)
    }

    /** SpaceManager::OnLoop hook: Animation::Update, then erase when Done (the 1 s tracker has run out). */
    fun update(game: InGameState, dt: Float) {
        val list = of(game)
        if (list.isEmpty()) return
        val it = list.iterator()
        while (it.hasNext()) {
            val puff = it.next()
            puff.time += dt
            if (puff.time >= TIME) it.remove()
        }
    }

    /** OnRenderProjectiles hook, ship 0 and layer 2, before the projectiles. */
    fun render(g: Graphics, ship: Ship) {
        if (ship !== ship.sys.player) return
        val list = of(ship.sys)
        if (list.isEmpty()) return
        val sheet = ship.sys.getImg("img/effects/fire_smoke.png")
        val size = sheet.width / FRAMES
        for (puff in list) {
            val frame = minOf(FRAMES - 1, (puff.time / TIME * FRAMES).toInt())
            sheet.draw(puff.x, puff.y, puff.x + size, puff.y + sheet.height,
                (frame * size).toFloat(), 0f, ((frame + 1) * size).toFloat(), sheet.height.toFloat())
        }
    }

    /** For diagnostics: the live puffs as (x, y, time). */
    fun snapshot(game: InGameState): List<Triple<Float, Float, Float>> = of(game).map { Triple(it.x, it.y, it.time) }

    private const val FRAMES = 7
    private const val TIME = 1f
}
