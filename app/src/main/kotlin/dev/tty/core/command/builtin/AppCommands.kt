package dev.tty.core.command.builtin

import dev.tty.core.apps.AppResolver
import dev.tty.core.apps.Resolution
import dev.tty.core.command.Command
import dev.tty.core.command.CommandContext
import dev.tty.core.output.Output
import dev.tty.core.output.Role
import dev.tty.core.parse.CommandLine
import dev.tty.core.parse.Flags
import dev.tty.core.text.Columns

/**
 * `apps` — lista las apps que exponen una actividad de lanzamiento (functional.md §6.2).
 *
 * **No tiene alias `ls`**: `ls` es de ficheros desde la decisión del 2026-07-26.
 */
object AppsCommand : Command {
    override val name = "apps"
    override val syntax = "apps [-s] [filter]"
    override val summary = "list installed apps"

    override suspend fun run(line: CommandLine, ctx: CommandContext): Output {
        val flags = Flags.parse(line.tokens, known = setOf('s'))
        if (flags.unknown.isNotEmpty()) return unknownFlags(name, flags.unknown)

        val filter = flags.operands.joinToString(" ").trim().lowercase()
        val apps = ctx.catalog.all()
            .filter { 's' in flags || !it.isSystem }
            .filter {
                filter.isEmpty() ||
                    filter in it.handle ||
                    filter in it.label.lowercase() ||
                    filter in it.packageName.lowercase()
            }

        if (apps.isEmpty()) {
            return Output.status(if (filter.isEmpty()) "no apps" else "no apps matching '$filter'")
        }

        val rows = apps.map { it.handle to it.packageName }
        val lines = Columns.twoColumns(rows).map { it to Role.OUTPUT }
        // La línea de total va tras una línea en blanco para que tenga su propia caja de línea,
        // igual que en el design system. Es una línea de estado, no salida.
        val total = "${apps.size} ${if (apps.size == 1) "app" else "apps"}"
        return Output(lines + ("" to Role.OUTPUT) + (total to Role.STATUS))
    }
}

/**
 * `open` — resuelve a una única app y la lanza.
 *
 * Si tiene éxito **no imprime nada**: la prueba de que funcionó es que la app está delante.
 * Imprimir «abriendo…» sería ruido (§6.2).
 */
object OpenCommand : Command {
    override val name = "open"
    override val aliases = setOf("o")
    override val syntax = "open <app>"
    override val summary = "open an app"

    override suspend fun run(line: CommandLine, ctx: CommandContext): Output {
        val query = line.tokens.joinToString(" ").trim()
        if (query.isEmpty()) return Output.error("$name: needs an app — try 'apps'")

        return when (val r = AppResolver.resolve(query, ctx.catalog.all())) {
            is Resolution.Found ->
                if (ctx.actions.open(r.app)) {
                    Output.silent
                } else {
                    // El límite se dice en el mensaje, no en la documentación (§10).
                    Output.error("$name: ${r.app.handle} has no launchable activity")
                }

            else -> Output.error(AppResolver.errorFor(name, r))
        }
    }
}

/** Error común a todos los comandos: un flag que el verbo no conoce. */
internal fun unknownFlags(verb: String, unknown: Set<Char>): Output {
    val list = unknown.sorted().joinToString(", ") { "-$it" }
    return Output.error("$verb: unknown flag $list")
}
