package xyz.znix.xftl.devutil

import xyz.znix.xftl.Blueprint
import xyz.znix.xftl.Ship
import xyz.znix.xftl.game.Achievement
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.ShipFamily
import xyz.znix.xftl.shipgen.EnemyShipSpec
import java.lang.reflect.Parameter
import kotlin.reflect.KClass

/**
 * Marks a given function as representing a console command.
 *
 * The function's arguments are matched up to [ArgumentTypeProcessor]
 * instances, so commands can easily get parsing and auto-completion
 * for their arguments.
 *
 * Each parameter must either be of a specially-known type, or be annotated
 * with [ParType] to manually set their type.
 *
 * The specially-known types are:
 *
 * - int
 * - String
 * - [Blueprint] and it's subclasses
 * - [EnemyShipSpec]
 * - [Achievement]
 * - [ShipFamily]
 * - Any enum
 *
 * The last parameter can also be annotated with [CmdVarArg] (which requires
 * the parameter is of type List<String>), to take any arguments remaining
 * after the previously-specified parameters were processed.
 */
@Target(AnnotationTarget.FUNCTION)
annotation class ConsoleCommand(
    val name: String
)

@Target(AnnotationTarget.FUNCTION)
annotation class CmdHelp(
    val help: String
)

/**
 * Put this on a List<String> parameter to a console command, to accept any additional
 * number of arguments after the previous arguments were parsed.
 *
 * This can only go on the last parameter.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
annotation class CmdVarArg

/**
 * Sets the user-visible name of the parameter, as shown in the greyed-out
 * parameter hints.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
annotation class ParName(
    val name: String
)

/**
 * Specifies the type processor that should be used to parse a given argument.
 *
 * The referred class can either have a getInstance(Parameter) static method,
 * or have an INSTANCE field (the latter of which is automatically defined
 * for Kotlin's top-level objects).
 *
 * When using Kotlin, be sure to annotate methods and fields with [JvmStatic].
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
annotation class ParType(
    val type: KClass<out ArgumentTypeProcessor>
)

/**
 * Represents a class that adds console commands.
 */
abstract class ConsoleCommandProvider(val console: DebugConsole) {
    open val ship: Ship get() = console.game.player
    open val game: InGameState get() = console.game

    abstract val commands: List<DebugConsole.Cmd>

    fun addLine(line: String) { console.addLine(line) }
}
