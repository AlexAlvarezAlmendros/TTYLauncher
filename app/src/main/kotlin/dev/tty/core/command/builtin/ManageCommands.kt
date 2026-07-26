package dev.tty.core.command.builtin

import dev.tty.core.apps.AppEntry
import dev.tty.core.apps.AppResolver
import dev.tty.core.apps.KillMode
import dev.tty.core.apps.Resolution
import dev.tty.core.command.Command
import dev.tty.core.command.CommandContext
import dev.tty.core.output.Output
import dev.tty.core.parse.CommandLine
import dev.tty.core.parse.Flags
import dev.tty.core.text.Columns

/**
 * Resuelve el argumento a una única app o devuelve el error ya formateado.
 *
 * Se comparte entre los cuatro verbos de la fase porque **la resolución tiene que ser idéntica en
 * todos**: el usuario debe poder confiar en que el sistema jamás adivina, y eso solo se sostiene si
 * `open` y `uninstall` fallan igual ante la misma ambigüedad (functional.md §7.2).
 */
private inline fun withApp(
    verb: String,
    line: CommandLine,
    ctx: CommandContext,
    hint: String,
    operands: List<String> = line.tokens,
    body: (AppEntry) -> Output,
): Output {
    val query = operands.joinToString(" ").trim()
    if (query.isEmpty()) return Output.error("$verb: needs an app — $hint")

    val apps = ctx.catalog.all()
    return when (val r = AppResolver.resolve(query, apps)) {
        is Resolution.Found -> body(r.app)
        else -> Output.error(AppResolver.errorFor(verb, r, apps))
    }
}

/**
 * `kill` — abre el diálogo del sistema donde vive «Forzar detención».
 *
 * **Revisado el 2026-07-26** (functional.md §6.2, architecture.md §4.4). La versión original decía
 * «detiene los procesos en segundo plano»: desde Android 14 eso ya no es posible, y lo grave es que
 * `killBackgroundProcesses()` **falla en silencio** sobre otra app. El verbo sobrevive porque sigue
 * siendo el correcto, y el límite se dice en el propio mensaje.
 *
 * Nunca un indicador de progreso esperando a que el proceso muera: no va a morir, y además la §4.8
 * prohíbe los spinners.
 */
object KillCommand : Command {
    override val name = "kill"
    override val aliases = setOf("stop")
    override val syntax = "kill <app>"
    override val summary = "open the force-stop dialog for an app"

    override suspend fun run(line: CommandLine, ctx: CommandContext): Output =
        withApp(name, line, ctx, hint = "try 'apps'") { app ->
            if (!ctx.killer.requestStop(app)) {
                return@withApp Output.error("$name: could not open settings for ${app.handle}")
            }
            when (ctx.killer.mode) {
                KillMode.SYSTEM_DIALOG ->
                    Output.status(
                        "force stop ${app.handle} in the system dialog " +
                            "(android 14+ blocks it from an app)",
                    )

                KillMode.DIRECT -> Output.status("killed ${app.handle}")
            }
        }
}

/**
 * `uninstall` — abre el diálogo de desinstalación del sistema.
 *
 * Se llamaba `rm` y perdió el nombre en favor del `rm` de ficheros. Que el verbo más destructivo del
 * producto tenga un nombre largo y explícito no es un accidente: es una mejora.
 *
 * **Rechaza apps de sistema** con un error propio en vez de dejar que el diálogo falle (§6.2), y
 * deja constancia en el scrollback de que se pidió aunque el usuario cancele — principio 4: nada
 * silencioso que sea destructivo.
 */
object UninstallCommand : Command {
    override val name = "uninstall"
    override val syntax = "uninstall <app>"
    override val summary = "open the uninstall dialog for an app"

    override suspend fun run(line: CommandLine, ctx: CommandContext): Output =
        withApp(name, line, ctx, hint = "try 'apps'") { app ->
            if (app.isSystem) {
                return@withApp Output.error("$name: ${app.handle} is a system app — cannot be removed")
            }
            if (!ctx.actions.requestUninstall(app)) {
                return@withApp Output.error("$name: could not open the uninstall dialog for ${app.handle}")
            }
            Output.status("confirm in the system dialog")
        }
}

/**
 * `info` — etiqueta, handle, paquete, actividad y si es del sistema, en columnas alineadas.
 *
 * Con `-o`, en lugar de imprimir, abre la pantalla de ajustes de esa app.
 */
object InfoCommand : Command {
    override val name = "info"
    override val syntax = "info <app> [-o]"
    override val summary = "show package details, or open its settings with -o"

    override suspend fun run(line: CommandLine, ctx: CommandContext): Output {
        val flags = Flags.parse(line.tokens, known = setOf('o'))
        if (flags.unknown.isNotEmpty()) return unknownFlags(name, flags.unknown)

        return withApp(name, line, ctx, hint = "try 'apps'", operands = flags.operands) { app ->
            if ('o' in flags) {
                // Éxito silencioso: la pantalla de ajustes ya está delante (§10).
                if (ctx.actions.openAppSettings(app)) {
                    Output.silent
                } else {
                    Output.error("$name: could not open settings for ${app.handle}")
                }
            } else {
                Output.output(
                    Columns.twoColumns(
                        listOf(
                            "label" to app.label,
                            "handle" to app.handle,
                            "package" to app.packageName,
                            "activity" to app.component,
                            "system" to if (app.isSystem) "yes" else "no",
                        ),
                        maxFirst = 8,
                    ),
                )
            }
        }
    }
}

/**
 * `settings` — abre los ajustes generales de Android.
 *
 * Existe porque sin él no hay forma de volver al launcher anterior sin instalar otra cosa
 * (functional.md §6.2). En un producto que se instala **como pantalla de inicio**, eso no es una
 * comodidad: es la salida de emergencia.
 */
object SettingsCommand : Command {
    override val name = "settings"
    override val syntax = "settings"
    override val summary = "open android settings"

    override suspend fun run(line: CommandLine, ctx: CommandContext): Output =
        if (ctx.actions.openSystemSettings()) {
            Output.silent
        } else {
            Output.error("$name: could not open android settings")
        }
}
