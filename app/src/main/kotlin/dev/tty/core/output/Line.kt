package dev.tty.core.output

import dev.tty.core.Limits

/**
 * El rol de una línea. **Lo fija el comando que la emite**, y de él salen tres cosas: el color
 * (functional.md §4.2), el prefijo (§10) y, en la Fase 5, la animación de aparición.
 *
 * Que `decode` no se use nunca en la salida de `apps`, `ls`, `sh` ni `tmux` (§4.5) es una regla del
 * motor y no criterio de quien escribe cada comando: esos comandos emiten [OUTPUT], y [OUTPUT]
 * nunca se descodifica.
 */
enum class Role {
    /** Salida de comandos. Color primario, sin prefijo. Se anima con `settle`. */
    OUTPUT,

    /** Eco de la entrada. Color atenuado, prefijo `>`. */
    ECHO,

    /** Error. Color alto —un gris más brillante, **no rojo**—, prefijo `!`. */
    ERROR,

    /** Línea corta de estado, banner y confirmaciones. Se anima con `decode` si cabe. */
    STATUS,

    /** Línea capturada en modo grabación. Prefijo `…`. Fase 3. */
    RECORDING,
    ;

    /** El prefijo que precede al texto en pantalla (§10). Cadena vacía si no lleva. */
    val prefix: String
        get() = when (this) {
            OUTPUT, STATUS -> ""
            ECHO -> "> "
            ERROR -> "! "
            RECORDING -> "… "
        }

    /**
     * Si el rol admite `decode`. La Fase 5 lo respeta; aquí se declara para que la regla viva junto
     * al rol y no dispersa por la UI.
     */
    val allowsDecode: Boolean
        get() = this == STATUS
}

/**
 * Una línea del scrollback.
 *
 * El [id] es monótono y es la *key* de la lista de Compose: **nunca se usa el índice**, porque con
 * inserciones en cabeza cambia para todos y rompe el anclaje y la reutilización.
 */
data class Line(
    val id: Long,
    val text: String,
    val role: Role,
) {
    /** El texto tal y como se persiste y se copia: con su prefijo. */
    fun render(): String = role.prefix + text
}

/** Genera identificadores monótonos. No hace falta que sean únicos entre arranques. */
class LineIds(start: Long = 0) {
    private var next = start
    fun next(): Long = next++
}

/**
 * El resultado de ejecutar un comando: una lista de líneas, posiblemente vacía.
 *
 * **La lista vacía es el caso normal**, no un error: el éxito silencioso es el valor por defecto
 * (§10). `open spotify` no imprime nada porque la prueba de que funcionó es que Spotify está
 * delante.
 */
@JvmInline
value class Output(val lines: List<Pair<String, Role>>) {

    companion object {
        val silent = Output(emptyList())

        fun of(vararg lines: String): Output = Output(lines.map { it to Role.OUTPUT })

        fun error(message: String): Output = Output(listOf(message to Role.ERROR))

        fun status(message: String): Output = Output(listOf(message to Role.STATUS))

        fun output(lines: List<String>): Output = Output(lines.map { it to Role.OUTPUT })
    }

    /**
     * Recorta a [Limits.COMMAND_OUTPUT_LINES] **diciendo cuánto se recortó** (§5.8). Un recorte
     * silencioso es una mentira: el usuario creería que eso es todo lo que había.
     *
     * El aviso ocupa una de las líneas del cupo, así que el resultado mide exactamente el máximo y
     * la operación es idempotente: volver a llamarla no recorta otra vez ni falsea la cuenta.
     */
    fun truncated(): Output {
        val max = Limits.COMMAND_OUTPUT_LINES
        if (lines.size <= max) return this
        val kept = max - 1
        val dropped = lines.size - kept
        val notice = if (dropped == 1) "… 1 more line" else "… $dropped more lines"
        return Output(lines.take(kept) + (notice to Role.STATUS))
    }
}
