package dev.tty.core.command.builtin

import dev.tty.core.command.Command
import dev.tty.core.command.CommandContext
import dev.tty.core.output.Output
import dev.tty.core.output.Role
import dev.tty.core.parse.CommandLine
import dev.tty.core.script.NameProblem
import dev.tty.core.script.Recording
import dev.tty.core.script.ScriptName
import dev.tty.core.text.Columns
import dev.tty.core.text.Suggest

/**
 * `script` — los cuatro subcomandos (functional.md §8).
 *
 * `new` arranca el modo grabación, que vive en el motor: este comando solo lo pide. Los otros tres
 * son inventario, lectura y borrado, con la salida que fija la §8.6.
 */
object ScriptCommand : Command {
    override val name = "script"
    override val aliases = setOf("s")
    override val syntax = "script <ls|new|cat|rm> [name]"
    override val summary = "saved scripts"

    override suspend fun run(line: CommandLine, ctx: CommandContext): Output {
        val sub = line.tokens.firstOrNull()?.lowercase()
            ?: return Output.error("$name: needs ls, new, cat or rm")
        val arg = line.tokens.drop(1).joinToString(" ").trim()

        return when (sub) {
            "ls" -> list(ctx)
            "new" -> new(arg, ctx)
            "cat" -> cat(arg, ctx)
            "rm" -> remove(arg, ctx)
            else -> Output.error(
                "$name: '$sub' is not a subcommand — try ls, new, cat or rm",
            )
        }
    }

    private suspend fun list(ctx: CommandContext): Output {
        val scripts = ctx.scripts.list()
        if (scripts.isEmpty()) {
            // No tener scripts es lo normal el primer día: es estado, no error.
            return Output.status("no scripts")
        }
        val rows = scripts.map { s ->
            val n = s.lines.size
            s.name to "$n ${if (n == 1) "line" else "lines"}"
        }
        val total = "${scripts.size} ${if (scripts.size == 1) "script" else "scripts"}"
        return Output(
            Columns.twoColumns(rows).map { it to Role.OUTPUT } +
                ("" to Role.OUTPUT) +
                (total to Role.STATUS),
        )
    }

    /**
     * Arranca el modo grabación.
     *
     * El nombre se valida **antes** de entrar: descubrir que no vale después de teclear cinco
     * líneas es la peor forma de enterarse.
     */
    private suspend fun new(arg: String, ctx: CommandContext): Output {
        val problem = ScriptName.validate(arg) { ctx.isReservedName(it) }
        if (problem != null) return Output.error("$name: ${problem.message}")

        if (ctx.scripts.read(arg) != null) {
            return Output.error("$name: '$arg' already exists — remove it first")
        }
        ctx.startRecording(arg)
        return Output.status(Recording.startedMessage(arg))
    }

    private suspend fun cat(arg: String, ctx: CommandContext): Output {
        if (arg.isEmpty()) return Output.error("$name: needs a name")
        val script = ctx.scripts.read(arg)
            ?: return notFound(arg, ctx)
        // Tal y como se guardaron: sin el prefijo `…` y sin numerar, para que se pueda volver a
        // grabar copiando lo que se ve.
        return Output.output(script.lines)
    }

    private suspend fun remove(arg: String, ctx: CommandContext): Output {
        if (arg.isEmpty()) return Output.error("$name: needs a name")
        if (ctx.scripts.read(arg) == null) return notFound(arg, ctx)

        return if (ctx.scripts.delete(arg)) {
            // Destructivo, luego deja rastro. Sin confirmación: la misma regla que `clear`.
            Output.status("removed $arg")
        } else {
            Output.error("$name: could not remove '$arg'")
        }
    }

    private suspend fun notFound(arg: String, ctx: CommandContext): Output {
        val names = ctx.scripts.list().map { it.name }
        return Output.error("$name: '$arg' not found${Suggest.hint(arg, names)}")
    }
}
