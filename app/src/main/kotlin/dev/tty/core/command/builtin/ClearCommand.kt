package dev.tty.core.command.builtin

import dev.tty.core.command.Command
import dev.tty.core.command.CommandContext
import dev.tty.core.output.Output
import dev.tty.core.parse.CommandLine

/**
 * `clear` — vacía el scrollback en memoria **y en disco**.
 *
 * Con el historial persistente este comando cambia de categoría: **es el único borrado real del
 * producto** (functional.md §6.2). Aun así no pide confirmación —un prompt de confirmación en una
 * terminal es una traición— pero sí deja constancia con una única línea.
 *
 * Es la excepción a la regla del éxito silencioso, y está justificada: el usuario acaba de destruir
 * algo, y una pantalla vacía por sí sola no distingue entre «se borró» y «falló al cargar».
 *
 * La línea sale con rol `STATUS`, que es el que la Fase 5 renderiza con `decode`.
 */
object ClearCommand : Command {
    override val name = "clear"
    override val aliases = setOf("cls", "clean")
    override val syntax = "clear"
    override val summary = "erase scrollback"

    override suspend fun run(line: CommandLine, ctx: CommandContext): Output {
        ctx.session.clearScrollback()
        return Output.status("scrollback cleared")
    }
}
