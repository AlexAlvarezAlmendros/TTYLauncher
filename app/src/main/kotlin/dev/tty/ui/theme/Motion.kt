package dev.tty.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.ui.unit.dp

/**
 * Movimiento. Espejo de `tokens/motion.css` del design system: **todas** las duraciones del
 * producto viven aquí y en ningún otro sitio.
 *
 * La regla que gobierna la lista (functional.md §4.7):
 *  - Ninguna animación **de transición** (disparada por una acción, un solo tiro) supera 500ms.
 *  - Los **bucles de estado** —los glifos y el cursor— y la deriva ambiental del degradado están
 *    exentos por definición: no son una transición, son un estado sostenido. Cada uno tiene su
 *    propio techo de ciclo, y todos están en esta lista.
 *  - Las animaciones no se encolan: si llega salida nueva mientras se revela la anterior, la
 *    anterior salta a su estado final.
 *  - La entrada nunca se bloquea por una animación.
 *
 * Movimiento reducido: `decode`, `settle`, el barrido y la deriva se desactivan; el cursor se
 * mantiene y los glifos se quedan en su fotograma de estado, sin bucle.
 */
object Motion {

    // --- Transiciones: techo duro de 500ms ---------------------------------------------------

    /** Eco de entrada: destello al 60% antes de asentarse en atenuado. */
    const val ECHO_MS = 80

    /** `settle`: escalonado entre líneas, aplicado solo a las 12 primeras. */
    const val SETTLE_STEP_MS = 25
    const val SETTLE_STAGGER_LINES = 12

    /** Techo absoluto del bloque completo, salga lo que salga. */
    const val SETTLE_CAP_MS = 300

    /** Desplazamiento vertical de entrada de cada línea. */
    val SettleOffset = 4.dp

    /** `decode`: por carácter, y techo del conjunto. */
    const val DECODE_CHAR_MS = 14
    const val DECODE_CAP_MS = 500

    /** `decode` solo es legal en líneas de este largo o menos. */
    const val DECODE_MAX_CHARS = 48

    /** Caída del historial al ejecutar `clear`. */
    const val CLEAR_MS = 120

    /** Glifos de un solo disparo. */
    const val GLYPH_OK_MS = 400
    const val GLYPH_FAIL_MS = 300

    // --- Bucles de estado: exentos del techo, cada uno con su ciclo ---------------------------

    const val GLYPH_BUSY_MS = 600
    const val GLYPH_REC_MS = 800
    const val GLYPH_SHELL_MS = 900
    const val GLYPH_READY_MS = 2400

    /** Cursor de bloque, parpadeo de curva suave. */
    const val CURSOR_MS = 1060

    // --- Ambiental ----------------------------------------------------------------------------

    /** Deriva del degradado: las paradas se desplazan ±2% de forma cíclica. */
    const val DRIFT_MS = 20_000
    const val DRIFT_AMPLITUDE = 0.02f

    // --- Curvas -------------------------------------------------------------------------------

    /** Entradas. Sin rebote, nunca. */
    val Ease: Easing = CubicBezierEasing(0.2f, 0f, 0.1f, 1f)

    /** Bucles. */
    val EaseSoft: Easing = CubicBezierEasing(0.45f, 0f, 0.55f, 1f)

    /** Juego de caracteres del que parte `decode` antes de resolver a la letra final. */
    const val DECODE_CHARSET = "▚▞░▒▓/\\|-_=+*"
}
