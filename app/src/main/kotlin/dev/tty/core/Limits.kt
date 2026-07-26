package dev.tty.core

/**
 * Todos los límites del producto, en un solo sitio.
 *
 * No se duplican en ningún otro fichero: un límite escrito dos veces acaba siendo dos límites
 * distintos. Cuando un límite se alcanza, **se dice en el mensaje** (functional.md §10), no se
 * recorta en silencio.
 */
object Limits {

    /** Scrollback, en memoria y en disco (functional.md §5.8). */
    const val SCROLLBACK_LINES = 2000

    /** Salida de un solo comando (§5.8). Lo que sobra se recorta diciendo cuánto. */
    const val COMMAND_OUTPUT_LINES = 500

    /** Entradas recordadas del historial del prompt (§5.4). Fase 6. */
    const val INPUT_HISTORY = 50

    /** Escritura diferida del scrollback tras la última línea (§5.5). */
    const val SCROLLBACK_FLUSH_DELAY_MS = 1_000L

    /** Profundidad máxima de anidamiento de scripts (§8.6). Fase 3. */
    const val SCRIPT_MAX_DEPTH = 4

    /** Líneas ejecutadas por invocación de script (§8.6). Fase 3. */
    const val SCRIPT_MAX_LINES = 200

    /** Timeout de una ejecución en Termux (§9.2). Fase 4. */
    const val TERMUX_TIMEOUT_MS = 15_000L
}
