package dev.tty.core.output

/**
 * Cómo aparece una línea (functional.md §4.5).
 *
 * Dos modos, elegidos por el **rol** de la línea, nunca por gusto. Que la elección viva aquí —en
 * `core/`, junto al rol— y no en la UI es lo que impide que alguien decida un día que la salida de
 * `apps` quedaría bonita descifrándose.
 */
enum class Reveal {
    /**
     * Por defecto, para toda salida de comandos. Opacidad 0→100% y +4dp, escalonado 25ms entre
     * líneas, y solo las 12 primeras: el resto entra de golpe. Techo de 300ms para el bloque.
     */
    SETTLE,

    /**
     * Solo líneas cortas de estado, el banner y las confirmaciones. Cada carácter parte de un glifo
     * aleatorio y se resuelve de izquierda a derecha.
     */
    DECODE,

    /** Sin animación: el historial persistido se muestra ya presente (§5.2). */
    NONE,
}

/**
 * Decide el modo de aparición de una línea.
 *
 * **Es una regla del motor, no criterio de quien escribe el comando** (§4.5). `decode` no se usa
 * nunca en la salida de `apps`, `ls`, `sh` ni `tmux`: ver un listado de 140 apps descifrándose es
 * exactamente el error que convierte una herramienta en un juguete. Esos comandos emiten `OUTPUT`,
 * y `OUTPUT` nunca se descodifica.
 */
object RevealPolicy {

    /** Techo de longitud para `decode`. Por encima, una línea deja de ser «corta» (§4.5). */
    const val DECODE_MAX_CHARS = 48

    fun forLine(line: Line, restored: Boolean = false): Reveal = when {
        restored -> Reveal.NONE
        !line.role.allowsDecode -> Reveal.SETTLE
        line.text.length > DECODE_MAX_CHARS -> Reveal.SETTLE
        else -> Reveal.DECODE
    }
}
