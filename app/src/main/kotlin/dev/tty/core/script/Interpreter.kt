package dev.tty.core.script

import dev.tty.core.Limits
import dev.tty.core.output.Output
import dev.tty.core.output.Role

/**
 * Sustitución posicional (functional.md §8.3).
 *
 * `$1`–`$9` sustituyen argumentos; `$@` los sustituye todos. **No hay ninguna otra sustitución**:
 * ni variables de entorno, ni aritmética, ni condicionales, ni bucles. Si un script necesita lógica,
 * esa lógica va en un script de Termux invocado con `sh`.
 *
 * Un `$1` sin argumento se sustituye por **nada**, no por el literal `$1`. Dejar el literal haría
 * que `open $1` intentara abrir una app llamada «$1» y el error hablaría de algo que el usuario no
 * escribió.
 */
object Substitution {

    fun apply(line: String, args: List<String>): String {
        if ('$' !in line) return line

        val out = StringBuilder(line.length)
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c != '$' || i == line.length - 1) {
                out.append(c)
                i++
                continue
            }
            when (val next = line[i + 1]) {
                '@' -> {
                    out.append(args.joinToString(" "))
                    i += 2
                }

                in '1'..'9' -> {
                    val index = next - '1'
                    out.append(args.getOrElse(index) { "" })
                    i += 2
                }

                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }
}

/**
 * El presupuesto de una invocación: profundidad y líneas.
 *
 * Ambos límites existen para que un script recursivo o descontrolado **termine** en lugar de colgar
 * la pantalla de inicio del teléfono (§8.6). Es el criterio 7, y es el más importante de la fase:
 * un launcher colgado es un móvil inutilizable.
 *
 * El contador de líneas es **compartido por toda la invocación**, no por script: si no, cuatro
 * scripts anidados podrían ejecutar 800 líneas entre todos respetando el límite cada uno.
 */
class Budget(
    private val maxDepth: Int = Limits.SCRIPT_MAX_DEPTH,
    private val maxLines: Int = Limits.SCRIPT_MAX_LINES,
) {
    var depth: Int = 0
        private set

    private var lines = 0

    fun enter(): Boolean {
        if (depth >= maxDepth) return false
        depth++
        return true
    }

    fun exit() {
        if (depth > 0) depth--
    }

    /** Consume una línea. `false` cuando se acabó el presupuesto. */
    fun consumeLine(): Boolean {
        if (lines >= maxLines) return false
        lines++
        return true
    }

    fun depthExceeded(): String = "too deep — scripts nest $maxDepth levels max"
    fun linesExceeded(): String = "too many lines — $maxLines max per run"
}

/**
 * Ejecuta un script línea a línea.
 *
 * **Se detiene en la primera línea que falla**, y se imprime la salida acumulada hasta ese punto
 * más el error (§8.5). Fallar rápido y mostrar el trabajo: en un script de tres líneas, saber cuál
 * falló es toda la información que hace falta.
 *
 * El ejecutor de una línea se recibe como función en vez de tomar el registro de comandos entero,
 * porque el orden **incorporado → script → error** lo decide el motor, no el intérprete: si el
 * intérprete pudiera resolver nombres, un script podría sombrear un comando.
 */
class ScriptRunner(private val store: ScriptStore) {

    suspend fun run(
        script: Script,
        args: List<String>,
        budget: Budget,
        execute: suspend (String, Budget) -> Output,
    ): Output {
        if (!budget.enter()) {
            return Output.error("${script.name}: ${budget.depthExceeded()}")
        }
        try {
            val collected = mutableListOf<Pair<String, Role>>()
            for (raw in script.lines) {
                if (Script.isComment(raw)) continue
                if (raw.isBlank()) continue

                if (!budget.consumeLine()) {
                    collected += "${script.name}: ${budget.linesExceeded()}" to Role.ERROR
                    return Output(collected)
                }

                val line = Substitution.apply(raw, args)
                val out = execute(line, budget)
                collected += out.lines

                // Parada en el primer fallo, con lo acumulado ya impreso.
                if (out.lines.any { it.second == Role.ERROR }) return Output(collected)
            }
            return Output(collected)
        } finally {
            budget.exit()
        }
    }

    suspend fun find(name: String): Script? = store.read(name)
}
