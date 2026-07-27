package dev.tty.core.parse

/**
 * La línea escrita en el prompt, ya troceada.
 *
 * El parser es **solo un tokenizador**. No hay `$PATH`, ni pipes, ni redirecciones, ni globbing, ni
 * sustitución de comandos (functional.md §1.2): cada comando interpreta sus propios flags con
 * [Flags], que es más honesto que un parser que adivine qué flag lleva valor.
 */
data class CommandLine(
    /** El verbo, en minúsculas. Nunca vacío. */
    val verb: String,
    /** Todo lo demás, ya sin comillas. */
    val tokens: List<String>,
    /**
     * La línea tal y como se escribió, sin tocar.
     *
     * La necesita `sh`: el parser quita las comillas, y bash las necesita ver. `sh echo "hola
     * mundo"` tiene que llegar a Termux con sus comillas puestas, o serían dos argumentos.
     */
    val raw: String = "",
) {
    companion object {

        /**
         * Trocea una línea. Devuelve `null` si no hay nada que ejecutar — entrada vacía o solo
         * espacios, que según la §5.3 **no hace nada, no imprime y no da error**.
         */
        fun parse(input: String): CommandLine? {
            val tokens = tokenize(input)
            val verb = tokens.firstOrNull()?.lowercase()
            // Un verbo vacío es lo que produce escribir solo `""` o una barra invertida suelta.
            // Se trata como entrada vacía en lugar de despachar un comando sin nombre, que daría
            // el error `: command not found` — inútil y feo.
            if (verb.isNullOrEmpty()) return null
            return CommandLine(verb, tokens.drop(1), input.trim())
        }

        /**
         * Separa por espacios respetando comillas simples y dobles, y la barra invertida como
         * escape. Las comillas agrupan y desaparecen; agrupar es su única función, porque no hay
         * expansión de ningún tipo dentro de ellas.
         *
         * Una comilla sin cerrar no es un error: agrupa hasta el final de la línea. En un prompt de
         * una sola línea no hay continuación posible, así que rechazarla solo sería fricción.
         */
        fun tokenize(input: String): List<String> {
            val tokens = mutableListOf<String>()
            val current = StringBuilder()
            var started = false
            var quote: Char? = null
            var escaped = false

            for (c in input) {
                when {
                    escaped -> {
                        current.append(c)
                        escaped = false
                    }

                    c == '\\' -> {
                        escaped = true
                        started = true
                    }

                    quote != null -> {
                        if (c == quote) quote = null else current.append(c)
                    }

                    c == '"' || c == '\'' -> {
                        quote = c
                        started = true
                    }

                    c.isWhitespace() -> {
                        if (started) {
                            tokens += current.toString()
                            current.setLength(0)
                            started = false
                        }
                    }

                    else -> {
                        current.append(c)
                        started = true
                    }
                }
            }
            if (started) tokens += current.toString()
            return tokens
        }
    }
}

/**
 * Flags de un comando, interpretados por el propio comando.
 *
 * Solo hay flags cortos de una letra (`-s`, `-l`, `-a`, `-r`, `-p`, `-o`, `-n`). No hay flags
 * largos: el vocabulario es corto a propósito y `--recursive` no cabe en la filosofía de escribir
 * tres letras y enviar.
 */
class Flags private constructor(
    private val present: Set<Char>,
    private val values: Map<Char, String>,
    /** Lo que no era un flag: los argumentos de verdad. */
    val operands: List<String>,
    /** Flags que se escribieron y el comando no conoce. */
    val unknown: Set<Char>,
) {
    operator fun contains(flag: Char): Boolean = flag in present

    /** El valor de un flag que lo lleva (`-n 40`), o `null` si no se escribió. */
    fun value(flag: Char): String? = values[flag]

    companion object {

        /**
         * @param known flags booleanos que el comando acepta
         * @param withValue flags que consumen el token siguiente como valor
         *
         * Un flag escrito que el comando no declara se devuelve en [unknown], para que dé un error
         * concreto en vez de ignorarlo en silencio. Un `-` suelto **no** es un flag: se trata como
         * operando, igual que en cualquier shell.
         *
         * `--` termina el procesado de flags: todo lo que venga después es operando. Es lo que
         * permite escribir `rm -- -fichero-raro`.
         */
        fun parse(
            tokens: List<String>,
            known: Set<Char> = emptySet(),
            withValue: Set<Char> = emptySet(),
        ): Flags {
            val present = mutableSetOf<Char>()
            val values = mutableMapOf<Char, String>()
            val operands = mutableListOf<String>()
            val unknown = mutableSetOf<Char>()

            var i = 0
            var flagsDone = false
            while (i < tokens.size) {
                val t = tokens[i]
                when {
                    flagsDone -> operands += t

                    t == "--" -> flagsDone = true

                    t.length > 1 && t.startsWith("-") -> {
                        for (c in t.drop(1)) {
                            when (c) {
                                in withValue -> {
                                    present += c
                                    val next = tokens.getOrNull(i + 1)
                                    if (next != null) {
                                        values[c] = next
                                        i++
                                    }
                                }

                                in known -> present += c
                                else -> unknown += c
                            }
                        }
                    }

                    else -> operands += t
                }
                i++
            }
            return Flags(present, values, operands, unknown)
        }
    }
}
