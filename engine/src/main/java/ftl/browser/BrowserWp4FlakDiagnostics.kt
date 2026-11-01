package ftl.browser

import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.weapons.FlakBlueprint

/**
 * WP4 projectiles: the browser half of the native 1.6.14 flak capture (.build/wp4-proj/native-flak): a real Adv. Flak
 * (SHOTGUN_PLAYER) volley. The gameplay stream is set to the capture's state before the factory's first draw, the
 * weapon fires one volley, and the result is every draw the volley took (in order) and per piece its target offset
 * from the room centre and its stopped frame. Argument: "<weapon> <stream state>".
 */
internal object BrowserWp4FlakDiagnostics {
    fun run(main: MainGame, container: GameContainer, args: String): String {
        val parts = args.trim().split(' ')
        val random = BrowserRandom.snapshot(); val native = NativeRandom.snapshot(); val oldTrace = NativeRandom.trace
        try {
            main.startDiagnosticGame("PLAYER_SHIP_HARD", Difficulty.NORMAL)
            main.update(container, 0f)
            val game = main.currentState as InGameState
            val player = game.player
            player.browserJumping = false
            val weapon = (game.blueprintManager[parts[0]] as FlakBlueprint).buildInstance(player) as FlakBlueprint.FlakInstance
            // On the first hardpoint (its mount gives the launch point), as an equipped weapon.
            player.hardpoints[0].weapon?.let { player.weapons!!.setWeaponPower(it, false) }
            player.hardpoints[0].weapon = weapon
            weapon.bindToWeaponsSystem(player.weapons!!)
            val room = player.rooms.first()
            BrowserRandom.nativeStream = true
            NativeRandom.restore(parts[1])
            val trace = ArrayList<Int>()
            NativeRandom.trace = trace
            val pieces = weapon.diagnosticVolley(room)
            NativeRandom.trace = oldTrace
            player.projectiles.clear()
            val out = StringBuilder("{\"draws\":[").append(trace.joinToString(",")).append("],\"pieces\":[")
            for ((i, p) in pieces.withIndex()) {
                if (i > 0) out.append(',')
                out.append("{\"dx\":${p[0]},\"dy\":${p[1]},\"frame\":${p[2].toInt()},\"frames\":${p[3].toInt()},\"fake\":${p[4] > 0f}}")
            }
            return out.append("]}").toString()
        } finally {
            NativeRandom.trace = oldTrace
            BrowserRandom.nativeStream = false
            BrowserRandom.restore(random); NativeRandom.restore(native); main.switchToMainMenu()
        }
    }
}
