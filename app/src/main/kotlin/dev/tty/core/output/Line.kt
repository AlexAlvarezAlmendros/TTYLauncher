package dev.tty.core.output

import dev.tty.core.Limits

/**
 * El rol de una línea. **Lo fija el comando que la emite**, y de él salen cuatro cosas: el color
 * (functional.md §4.2), el tamaño (§4.3), el prefijo o el glifo (§4.4, §10) y la animación de
 * aparición (§4.5).
 *
 * Que `decode` no se use nunca en la salida de `apps`, `ls`, `sh` ni `tmux` es una regla del motor y
 * no criterio de quien escribe cada comando: esos comandos emiten [OUTPUT], y [OUTPUT] nunca se
 * descodifica.
 */
enum class Role {
    /** Salida de comandos. Color primario. Se anima con `settle`. */
    OUTPUT,

    /** Eco de la entrada. Color atenuado; el glifo ocupa la celda donde iba el `>`. */
    ECHO,

    /** Error. Color alto —un gris más brillante, **no rojo**—; el glifo ocupa la celda del `!`. */
    ERROR,

    /** Línea corta de estado y confirmaciones. Se anima con `decode` si cabe. */
    STATUS,

    /** Línea capturada en modo grabación. Prefijo `…`, que **sí** se mantiene como carácter. */
    RECORDING,

    /**
     * Banner y cabeceras: 10sp, tracking amplio, mayúsculas (§4.3).
     *
     * Es el segundo de los **dos únicos tamaños** del producto. Sin este rol el banner salía como
     * cuerpo atenuado y el producto tenía un solo tamaño, que es justo lo que la §4.3 no admite.
     */
    LABEL,
    ;

    /**
     * El prefijo que precede al texto (§10).
     *
     * `ECHO` y `ERROR` **no llevan prefijo de carácter**: su celda la ocupa un glifo congelado
     * (§4.4). El `…` de grabación sí se mantiene como carácter, porque `REC` ya comunica el modo
     * desde el prompt y dos señales del mismo estado es una de más.
     */
    val prefix: String
        get() = when (this) {
            OUTPUT, STATUS, LABEL, ECHO, ERROR -> ""
            RECORDING -> "… "
        }

    /** Si el rol admite `decode`. Vive junto al rol para que la regla no se disperse por la UI. */
    val allowsDecode: Boolean
        get() = this == STATUS || this == LABEL
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
    /**
     * El glifo congelado que acompaña a la línea, si lleva (§4.4).
     *
     * Lo pone el motor: un eco recibe `OK` o `FAIL` **según cómo terminara su comando**, que es lo
     * que permite recorrer el scrollback y ver de un vistazo qué falló. Los del historial van
     * siempre congelados en su fotograma final — como máximo un glifo animado en pantalla, y es el
     * del prompt.
     */
    val glyph: LineGlyph? = null,
) {
    /** El texto tal y como se copia: con su prefijo, si lo lleva. */
    fun render(): String = role.prefix + text
}

/**
 * Los dos glifos que pueden acompañar a una línea ya emitida.
 *
 * Es un enum aparte del de la UI a propósito: `core/` decide **cuándo** hay glifo y cuál, sin saber
 * cómo se dibuja. La UI mapea estos dos a sus estados.
 */
enum class LineGlyph {
    /** Comando completado con salida. */
    OK,

    /** Error. */
    FAIL,
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

    /** Si alguna línea es un error. Es lo que decide el glifo del eco. */
    val failed: Boolean get() = lines.any { it.second == Role.ERROR }

    /**
     * Recorta a [Limits.COMMAND_OUTPUT_LINES] **diciendo cuánto se recortó** (§5.8). Un recorte
     * silencioso es una mentira: el usuario creería que eso es todo lo que había.
     *
     * El aviso ocupa una de las líneas del cupo, así que el resultado mide exactamente el máximo y
     * la operación es idempotente: volver a llamarla no recorta otra vez ni falsea la cuenta.
     *
     * El aviso **no empieza por `…`**: ese carácter está reservado para las líneas grabadas (§10), y
     * un prefijo que significa dos cosas deja de significar una.
     */
    fun truncated(): Output {
        val max = Limits.COMMAND_OUTPUT_LINES
        if (lines.size <= max) return this
        val kept = max - 1
        val dropped = lines.size - kept
        return Output(lines.take(kept) + ("$dropped more lines not shown" to Role.STATUS))
    }
}
