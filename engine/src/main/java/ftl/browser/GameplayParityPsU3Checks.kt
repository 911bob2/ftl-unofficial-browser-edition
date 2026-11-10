package ftl.browser

import xyz.znix.xftl.Ship
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.InfoPanel
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.weapons.Damage

/**
 * Parity sweep U3 (rendering, fonts, languages), vanilla and modded: the drawing rules that have no frame of their own
 * in the runner. Leaves both random streams as it found them.
 */
internal object GameplayParityPsU3Checks {
    fun run(main: MainGame, container: GameContainer): String {
        val rng = BrowserRandom.snapshot()
        val native = NativeRandom.snapshot()
        try {
            return "ps-u3 rules ok: ${staleHullNumber(main, container)}, ${detailsBox(main, container)}, " +
                planetSize(main, container)
        } finally {
            BrowserRandom.restore(rng)
            NativeRandom.restore(native)
        }
    }

    /** The damage numbers [block] adds to [ship], by image name (Text_<n>_L as n, text_resist as resist). */
    private fun shown(state: InGameState, ship: Ship, block: () -> Unit): List<String> {
        ship.damageNumbers.clear()
        block()
        val names = HashMap<xyz.znix.xftl.rendering.Image, String>()
        for (n in 0..9) names[state.getImg("img/numbers/Text_${n}_L.png")] = "$n"
        names[state.getImg("img/numbers/text_resist.png")] = "resist"
        return ship.damageNumbers.map { names[it.image] ?: "?" }
    }

    /**
     * 1.6.14 DamageArea (0x4a6100) shows ShipManager::iLastDamage as the hull number; a Rock Plating proc on a hit without
     * hull damage leaves it at the last hull hit's value, so that number shows again. The player's is cleared every frame
     * (CommandGui::OnLoop through GetLastDamage 0x49c8a0). Hyperspace's DamageArea shows nothing for such a hit.
     */
    private fun staleHullNumber(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val player = state.player
        state.debugFlags.noDmg.set = false
        val room = player.rooms.first { it.system == null }
        val full = player.maxHealth
        fun hit(hull: Int, ion: Int) = Damage().also { it.hullDamage = hull; it.ionDamage = ion; it.noCrewDamage = true }
        val hs = xyz.znix.xftl.hs.HS.active
        try {
            player.health = full
            val first = shown(state, player) { player.damage(room, hit(3, 0), rockArmourRoll = false) }
            check(first == listOf("3") && player.nativeLastDamage == 3) { "a 3-damage hit shows $first, iLastDamage ${player.nativeLastDamage}" }
            val ion = shown(state, player) { player.damage(room, hit(0, 1), rockArmourRoll = true) }
            check(ion == if (hs) emptyList() else listOf("3")) { "Rock Plating on an ion-only hit after a 3: shows $ion" }
            check(player.health == full - 3 && player.nativeLastDamage == 3) { "the proc changed hull ${player.health} or iLastDamage ${player.nativeLastDamage}" }
            // without the proc the hit stores its 0, and the next proc shows nothing
            val plain = shown(state, player) { player.damage(room, hit(0, 1), rockArmourRoll = false) }
            check(plain.isEmpty() && player.nativeLastDamage == 0) { "an ion-only hit shows $plain, iLastDamage ${player.nativeLastDamage}" }
            val after = shown(state, player) { player.damage(room, hit(0, 1), rockArmourRoll = true) }
            check(after.isEmpty()) { "Rock Plating after a zero hit shows $after" }
            // the player's value lasts one frame
            player.damage(room, hit(2, 0), rockArmourRoll = false)
            state.updateGameState(0f)
            check(player.nativeLastDamage == 0) { "the player's iLastDamage after a frame: ${player.nativeLastDamage}" }
        } finally {
            player.health = full
            player.damageNumbers.clear()
        }
        return if (hs) "no stale hull number (Hyperspace)" else "stale hull number on a zero-damage Rock Plating proc"
    }

    /**
     * InfoBox::OnRender (1.6.9 0x54c710): details_base_A, then whole details_base_B (width - A - C) / B times, then C; the
     * level rows A - 65 + B x count wide. The ship window's 333 px panel has room for two Bs, the hangar's 323 for one.
     */
    private fun detailsBox(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val a = state.getImg("img/upgradeUI/details_base_A.png").width
        val b = state.getImg("img/upgradeUI/details_base_B.png").width
        val c = state.getImg("img/upgradeUI/details_base_C.png").width
        val ship = InfoPanel.detailsLayout(333, a, b, c)
        val hangar = InfoPanel.detailsLayout(323, a, b, c)
        if (a == 306 && b == 10 && c == 21) {
            check(ship == Pair(2, 261)) { "ship window details box: $ship" }
            check(hangar == Pair(1, 251)) { "hangar details box: $hangar" }
        }
        return "details box B x${ship.first} (ship window), x${hangar.first} (hangar)"
    }

    /**
     * SpaceManager::SwitchPlanet (1.6.14 0x418280, 1.6.9 0x416860) draws the picked planet at its texture's size and
     * places it in 1280 - w by 720 - h; the image list's w and h are not read (HS oracle ps-u3-img: a planet listed at
     * 230 x 230 from a 460 px file shows at 460, currentPlanet.w 460).
     */
    private fun planetSize(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val file = state.eventManager.getImageList("PLANET").images.first()
        val img = file.getImg(state)
        val listed = xyz.znix.xftl.sector.EnvironmentImage(file.path, img.width / 2, img.height / 2)
        val list = xyz.znix.xftl.sector.ImageList("PSU3_PLANET", listOf(listed))
        val map = state.gameMap
        val kept = map.spacePlanet
        try {
            repeat(8) {
                map.spacePlanet = null
                val scene = xyz.znix.xftl.environment.BeaconScene()
                xyz.znix.xftl.environment.BeaconScene.pickPlanet(state, map, scene, list)
                check(scene.planetW == img.width && scene.planetH == img.height) {
                    "a planet listed at ${listed.width} x ${listed.height} from a ${img.width} x ${img.height} file: " +
                        "${scene.planetW} x ${scene.planetH}"
                }
                check(scene.planetX < maxOf(1, 1280 - img.width) && scene.planetY < maxOf(1, 720 - img.height)) {
                    "planet placed at ${scene.planetX}, ${scene.planetY} for a ${img.width} x ${img.height} texture"
                }
            }
        } finally {
            map.spacePlanet = kept
        }
        return "planet drawn at its texture's ${img.width} x ${img.height}"
    }
}
