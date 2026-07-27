package dev.tty.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Color. Espejo de `tokens/colors.css` del design system — ver docs/design/DESIGN-SYSTEM.md.
 *
 * Los valores hexadecimales son los del design system, **no** una interpretación local. Si hay
 * que cambiarlos, se cambian allí primero y se sincronizan aquí.
 *
 * Reglas que no se negocian (functional.md §4.2):
 *  - El degradado **nunca** alcanza el blanco. Se corta en acero para que un único color de texto
 *    claro sea legible sobre todo el recorrido.
 *  - Los tres niveles de texto son **acromáticos**. Los errores son un gris más brillante. No rojo.
 *  - Un solo acento visual: el degradado. No hay ningún color con tono en toda la aplicación.
 *
 * Las paradas están fijas al viewport: el degradado no se desplaza con el contenido, y por eso el
 * historial literalmente se hunde en él según envejece.
 */
object Palette {

    /** 0% — prompt y respuesta más reciente. */
    val Ink = Color(0xFF07080B)

    /** 35% — transición. */
    val Deep = Color(0xFF101420)

    /** 62% — la banda azul de la referencia. */
    val Indigo = Color(0xFF26355C)

    /** 86% — historial antiguo. */
    val Slate = Color(0xFF414C62)

    /** 100% — suelo. Nunca más claro que esto. */
    val Steel = Color(0xFF59636F)

    /** Posiciones de las cinco paradas, en fracción del viewport. */
    val Stops = floatArrayOf(0f, 0.35f, 0.62f, 0.86f, 1f)

    val Gradient = listOf(Ink, Deep, Indigo, Slate, Steel)

    /** Salida de comandos. */
    val TextPrimary = Color(0xFFE4E6E9)

    /** Eco de la entrada, símbolo del prompt, etiquetas. */
    val TextDim = Color(0xFF8A9099)

    /** Errores. Un gris más brillante que el primario. **No rojo.** */
    val TextHigh = Color(0xFFF7F8FA)

    /** El único borde del producto: la línea de un píxel bajo el prompt. */
    val Rule = TextPrimary.copy(alpha = 0.22f)

    /**
     * Desvanecimiento por antigüedad: las líneas pierden opacidad según se alejan del prompt,
     * hasta este suelo. No se ocultan, se hunden. **Sustituye a todo separador del producto.**
     */
    const val FADE_MAX = 1f
    const val FADE_MIN = 0.35f

    /**
     * Matriz de puntos: la rejilla 5×5 se dibuja **entera**. Los puntos apagados reposan al 18%,
     * como un píxel apagado sigue viéndose en una matriz real. Es lo que hace que un estado de un
     * solo punto (`READY`) se lea como glifo y no como una mota suelta, y lo que da a `BUSY` y
     * `SHELL` una estela visible detrás del barrido.
     */
    const val DOT_UNLIT = 0.18f
    const val DOT_LIT = 1f

    /**
     * Opacidad en reposo del control del historial (§4.8: el único elemento pulsable del producto).
     *
     * **No es `DOT_UNLIT` ni `FADE_MIN`.** Un punto apagado de una matriz es contexto y puede vivir
     * al 18%; este es un control, y uno que no se ve no se puede pulsar. Se queda por debajo del
     * texto para no competir con él, y muy por encima del suelo de la matriz para existir.
     */
    const val HANDLE_IDLE = 0.45f

    /**
     * Suelo del parpadeo del cursor de bloque. Del `@keyframes tty-cursor` del design system: la
     * opacidad va de 1 a 0.12 y vuelve, con curva suave — nunca encendido/apagado duro.
     */
    const val CURSOR_ALPHA_MIN = 0.12f
}
