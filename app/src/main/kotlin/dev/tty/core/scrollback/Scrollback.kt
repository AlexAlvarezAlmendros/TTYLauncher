package dev.tty.core.scrollback

import dev.tty.core.Limits
import dev.tty.core.output.Line
import dev.tty.core.output.LineIds
import dev.tty.core.output.Role

/**
 * El scrollback en memoria.
 *
 * **Ordenado con lo más reciente primero** (índice 0 = la última línea). No es un capricho: la §4.1
 * fija el prompt arriba y el historial creciendo hacia abajo, y la forma correcta de pintar eso en
 * Compose es una lista normal —`reverseLayout = false`— alimentada con la lista ya invertida. Si se
 * guardara al revés habría que invertirla en cada recomposición.
 *
 * El recorte a [Limits.SCROLLBACK_LINES] es lo que hace que el arranque no dependa de cuánto se
 * haya usado el launcher (§5.5).
 */
class Scrollback(
    private val ids: LineIds = LineIds(),
    private val max: Int = Limits.SCROLLBACK_LINES,
) {
    private val _lines = ArrayDeque<Line>()

    /** Lo más reciente primero. */
    val lines: List<Line> get() = _lines

    val size: Int get() = _lines.size

    /** Añade una línea nueva en cabeza y recorta la cola si hace falta. */
    fun add(text: String, role: Role): Line {
        val line = Line(ids.next(), text, role)
        _lines.addFirst(line)
        trim()
        return line
    }

    fun addAll(items: List<Pair<String, Role>>): List<Line> = items.map { (t, r) -> add(t, r) }

    /**
     * Carga el historial persistido. Las líneas llegan **en orden cronológico** (la más antigua
     * primero), que es como se escriben en el fichero, y se colocan invertidas.
     *
     * Se usa solo al arrancar, sobre un scrollback vacío: lo persistido se muestra ya presente, sin
     * animación de entrada (§5.2).
     */
    fun restore(chronological: List<Pair<String, Role>>) {
        for ((text, role) in chronological) add(text, role)
    }

    /** Vacía la memoria. Del disco se encarga la sesión: aquí no se sabe qué es un fichero. */
    fun clear() = _lines.clear()

    /** Lo persistible, **en orden cronológico**: es como se escribe y se relee el fichero. */
    fun chronological(): List<Line> = _lines.reversed()

    private fun trim() {
        while (_lines.size > max) _lines.removeLast()
    }
}
