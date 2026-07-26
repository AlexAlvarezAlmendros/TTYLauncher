package dev.tty.core

/**
 * El historial de entradas (functional.md §5.4).
 *
 * Las últimas ~50 líneas **escritas**, que no es lo mismo que el scrollback: aquí no entra la salida
 * de nadie, solo lo que el usuario tecleó. Un único control lo recorre de más reciente a más antiguo
 * y **vuelve a empezar** — uno, no dos: no hay «atrás y adelante», porque un segundo control sería
 * un segundo elemento tocable en un producto que tiene uno.
 *
 * **Es volátil.** Se pierde al reiniciar, y eso es deliberado: el scrollback ya es un registro en
 * disco de lo que haces con el móvil (§5.5), y duplicarlo en un segundo fichero añadiría la misma
 * consecuencia de privacidad por una comodidad mucho menor.
 */
class InputHistory(private val max: Int = Limits.INPUT_HISTORY) {

    private val entries = ArrayDeque<String>()

    /** Dónde está el recorrido. `-1` = en el prompt, sin recorrer nada. */
    private var cursor = -1

    val size: Int get() = entries.size

    /** Lo recordado, de más reciente a más antiguo. */
    fun all(): List<String> = entries.toList()

    /**
     * Recuerda una entrada.
     *
     * Dos reglas que se notan al usarlo: una línea vacía no se guarda, y **repetir el último
     * comando no lo duplica**. Escribir `apps` tres veces seguidas y luego recorrer el historial
     * pasando tres veces por `apps` es fricción, no memoria.
     */
    fun remember(input: String) {
        val line = input.trim()
        cursor = -1
        if (line.isEmpty()) return
        if (entries.firstOrNull() == line) return

        entries.addFirst(line)
        while (entries.size > max) entries.removeLast()
    }

    /**
     * El siguiente del recorrido: más reciente → más antiguo → **vuelta al principio**.
     *
     * Devuelve `null` si no hay nada que recorrer. Al dar la vuelta pasa por el prompt vacío una
     * vez, para que se pueda salir del recorrido sin borrar a mano lo que quedó escrito.
     */
    fun cycle(): String? {
        if (entries.isEmpty()) return null

        cursor++
        if (cursor >= entries.size) {
            cursor = -1
            return ""
        }
        return entries[cursor]
    }

    /** Vuelve al prompt: lo llama el envío, para que el recorrido siguiente empiece de cero. */
    fun reset() {
        cursor = -1
    }
}
