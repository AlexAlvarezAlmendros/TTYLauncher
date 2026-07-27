package dev.tty.core.command.builtin

import dev.tty.core.command.Command
import dev.tty.core.command.CommandContext
import dev.tty.core.output.Output
import dev.tty.core.output.Role
import dev.tty.core.parse.CommandLine
import dev.tty.core.parse.Flags
import dev.tty.core.termux.TermuxCommands
import dev.tty.core.termux.TermuxError
import dev.tty.core.termux.TermuxResult

/**
 * `sh` — ejecuta una línea en el bash de Termux (functional.md §9.2).
 *
 * Es **la única escotilla** del vocabulario cerrado, y es explícita: cualquier cosa que se pueda
 * escribir en un script de shell se convierte en un comando del launcher envolviéndola en un script
 * de `tty`. Sin ella el vocabulario sería fijo.
 *
 * La línea llega entera y sin tocar: `sh` no parsea nada. Lo que va detrás del verbo es de bash.
 */
object ShCommand : Command {
    override val name = "sh"
    override val aliases = setOf("!")
    override val syntax = "sh <línea>"
    override val summary = "run a line in termux"

    override suspend fun run(line: CommandLine, ctx: CommandContext): Output {
        // Se reconstruye desde la entrada cruda, no desde los tokens: el parser quitó las comillas
        // que bash necesita ver. `sh echo "hola mundo"` tiene que llegar con sus comillas puestas.
        val script = line.raw.substringAfter(line.verb).trim()
        if (script.isEmpty()) return Output.error("$name: needs a line to run")

        ctx.termux.check()?.let { return Output.error(it.message(name)) }

        return ctx.termux.run(TermuxCommands.BASH, TermuxCommands.shell(script))
            .fold(
                onSuccess = { render(it) },
                onFailure = { Output.error(errorOf(it).message(name)) },
            )
    }
}

/**
 * `tmux` — fotografía un pane (functional.md §9.3).
 *
 * **No se adjunta a una terminal.** Se le pide a tmux una captura del contenido y se imprime como
 * texto plano. Ese es el compromiso central del proyecto y hay que defenderlo: una foto periódica
 * cubre el 90% de los usos reales —una compilación, un log, un `htop`— y cuesta una fracción de lo
 * que cuesta un emulador de terminal completo.
 *
 * La sesión vive en Termux, así que sobrevive a que Android mate el launcher. Y lo que se ve usa la
 * tipografía y el degradado de `tty`, no un segundo lenguaje visual.
 */
object TmuxCommand : Command {
    override val name = "tmux"
    override val aliases = setOf("t")
    override val syntax = "tmux [sesión] [-n N] [-k teclas]"
    override val summary = "snapshot a tmux pane"

    private const val DEFAULT_SESSION = "tty"
    private const val DEFAULT_LINES = 40

    override suspend fun run(line: CommandLine, ctx: CommandContext): Output {
        val flags = Flags.parse(line.tokens, withValue = setOf('n', 'k'))
        if (flags.unknown.isNotEmpty()) return unknownFlags(name, flags.unknown)

        val n = flags.value('n')?.toIntOrNull()
        if (flags.value('n') != null && (n == null || n <= 0)) {
            return Output.error("$name: -n needs a positive number")
        }
        val lines = n ?: DEFAULT_LINES
        val session = flags.operands.firstOrNull() ?: DEFAULT_SESSION
        val keys = flags.value('k')

        ctx.termux.check()?.let { return Output.error(it.message(name)) }

        // 1. ¿Existe la sesión? Un exit code distinto de 0 significa que no.
        val exists = ctx.termux.run(TermuxCommands.TMUX, TermuxCommands.Tmux.hasSession(session))
            .fold(onSuccess = { it.ok }, onFailure = { return Output.error(errorOf(it).message(name)) })

        // 2. Si no, se crea. `tmux build` con una sesión nueva es el caso normal, no un error.
        if (!exists) {
            val created = ctx.termux.run(TermuxCommands.TMUX, TermuxCommands.Tmux.newSession(session))
            created.exceptionOrNull()?.let { return Output.error(errorOf(it).message(name)) }
        }

        // 3. Las teclas, si las hay.
        if (keys != null) {
            val sent = ctx.termux.run(TermuxCommands.TMUX, TermuxCommands.Tmux.sendKeys(session, keys))
            sent.exceptionOrNull()?.let { return Output.error(errorOf(it).message(name)) }
        }

        // 4. Y la foto. Volver a escribir `tmux <sesión>` la refresca: no hay nada que refrescar
        //    solo, porque no hay interactividad continua y eso es deliberado.
        return ctx.termux.run(TermuxCommands.TMUX, TermuxCommands.Tmux.capturePane(session, lines))
            .fold(
                onSuccess = { result ->
                    if (result.stdout.isEmpty() && result.stderr.isEmpty()) {
                        Output.status("$session is empty")
                    } else {
                        render(result)
                    }
                },
                onFailure = { Output.error(errorOf(it).message(name)) },
            )
    }
}

/**
 * Convierte el resultado en líneas.
 *
 * `stdout` como salida normal y `stderr` como error, **sin mezclarlos**: en un terminal esa
 * distinción es la mitad de la información. Y ambos con rol `OUTPUT`/`ERROR`, que nunca se
 * descodifican — ver 140 líneas de un log descifrándose sería el error que convierte una
 * herramienta en un juguete (§4.5).
 */
private fun render(result: TermuxResult): Output {
    val lines = mutableListOf<Pair<String, Role>>()
    lines += result.stdout.map { it to Role.OUTPUT }
    lines += result.stderr.map { it to Role.ERROR }

    // El código de salida solo se dice cuando importa: un 0 es el silencio esperado.
    val code = result.exitCode
    if (code != null && code != 0) lines += "exit $code" to Role.STATUS

    return if (lines.isEmpty()) Output.silent else Output(lines)
}

/** Traduce la excepción de la capa de plataforma al error tipado. */
private fun errorOf(t: Throwable): TermuxError =
    (t as? TermuxException)?.error ?: TermuxError.Failed(t.message ?: "unknown failure")

/** El envoltorio con el que `platform/` devuelve un [TermuxError] a través de `Result`. */
class TermuxException(val error: TermuxError) : Exception(error.message("termux"))
