package dev.tty.core

import dev.tty.core.command.CommandContext
import dev.tty.core.command.CommandRegistry
import dev.tty.core.output.Line
import dev.tty.core.output.Role
import dev.tty.core.parse.CommandLine
import dev.tty.core.scrollback.Scrollback

/**
 * El ciclo completo de una entrada: eco → despacho → salida.
 *
 * Es Kotlin puro y no sabe nada de Android ni de Compose: se puede ejecutar entero en un test de
 * JVM, que es justo lo que hace que el motor sea barato de verificar sin emulador.
 */
class TerminalEngine(
    private val registry: CommandRegistry,
    private val scrollback: Scrollback,
    private val context: CommandContext,
) {

    /**
     * Ejecuta una línea escrita en el prompt y devuelve **las líneas nuevas**, de la más antigua a
     * la más reciente, para que la UI sepa cuáles animar (las persistidas no se animan, §5.2).
     *
     * Reglas de la §5.3 que se cumplen aquí y no en la UI:
     * - Entrada vacía: **no hace nada, no imprime, no da error**.
     * - La entrada **se ecoa siempre antes de la salida**.
     */
    suspend fun submit(input: String): List<Line> {
        val parsed = CommandLine.parse(input) ?: return emptyList()

        val added = mutableListOf<Line>()
        added += scrollback.add(input.trim(), Role.ECHO)

        val output = try {
            registry.run(parsed, context)
        } catch (e: Throwable) {
            // Ningún comando debe poder dejar la actividad en un estado no recuperable (§16).
            // Un fallo se cuenta, no se traga: el scrollback es el único sitio donde el usuario
            // puede verlo, porque no hay logcat en un móvil de diario.
            dev.tty.core.output.Output.error(
                "${parsed.verb}: failed — ${e::class.simpleName}: ${e.message ?: "no message"}",
            )
        }

        for ((text, role) in output.lines) added += scrollback.add(text, role)
        return added
    }
}
