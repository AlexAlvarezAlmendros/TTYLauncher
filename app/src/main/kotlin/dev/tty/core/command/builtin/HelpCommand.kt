package dev.tty.core.command.builtin

import dev.tty.core.command.Command
import dev.tty.core.command.CommandContext
import dev.tty.core.output.Output
import dev.tty.core.output.Role
import dev.tty.core.parse.CommandLine
import dev.tty.core.text.Columns

/**
 * `help` — **la única documentación del producto** (functional.md §6.2).
 *
 * Sin argumentos, imprime todos los comandos en dos columnas: sintaxis y descripción de una línea.
 * Con argumento, imprime la ficha de ese comando: sintaxis, descripción y alias.
 *
 * El criterio de aceptación 4 dice que tiene que caber en una pantalla sin scroll horizontal. Si
 * algún día deja de caber, la respuesta no es recortar la salida: es que sobra un comando o que uno
 * está mal diseñado.
 */
object HelpCommand : Command {
    override val name = "help"
    override val aliases = setOf("?", "h")
    override val syntax = "help [command]"
    override val summary = "list commands"

    override suspend fun run(line: CommandLine, ctx: CommandContext): Output {
        val target = line.tokens.firstOrNull()?.lowercase()
            ?: return list(ctx)

        val command = ctx.commands.firstOrNull { it.name == target || target in it.aliases }
            ?: return Output.error("$name: '$target' is not a command")

        return card(command)
    }

    private fun list(ctx: CommandContext): Output {
        val rows = ctx.commands.map { it.syntax to it.summary }
        return Output.output(Columns.twoColumns(rows))
    }

    /** La ficha de un comando. Los alias solo se imprimen aquí, en ningún otro sitio. */
    private fun card(command: Command): Output {
        val lines = mutableListOf<Pair<String, Role>>()
        lines += command.syntax to Role.OUTPUT
        lines += command.summary to Role.OUTPUT
        if (command.aliases.isNotEmpty()) {
            lines += "" to Role.OUTPUT
            lines += "aliases: ${command.aliases.sorted().joinToString(" ")}" to Role.STATUS
        }
        return Output(lines)
    }
}
