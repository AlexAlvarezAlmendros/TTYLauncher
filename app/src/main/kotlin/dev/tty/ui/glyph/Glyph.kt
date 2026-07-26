package dev.tty.ui.glyph

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import dev.tty.ui.theme.Motion
import dev.tty.ui.theme.Palette
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Los seis estados del glifo (functional.md §4.4).
 *
 * Sustituyen a los iconos y son el elemento distintivo del producto. **No son iconografía**: un
 * glifo es una rejilla de 5×5 puntos que ocupa exactamente una celda de carácter — el mismo ancho
 * que cualquier letra de la monoespaciada. Es un carácter más de la retícula, solo que animado.
 */
enum class GlyphState {
    /** Prompt en reposo. Punto central respirando, 2.4s. */
    READY,

    /** Comando ejecutándose. Columna barriendo de izquierda a derecha, 600ms. */
    BUSY,

    /** Ejecución en Termux en curso. Cascada de filas de arriba abajo, 900ms. */
    SHELL,

    /** Modo grabación. Punto central pulsando, 800ms. */
    REC,

    /** Comando completado con salida. Diagonal ascendente que se atenúa, 400ms, una vez. */
    OK,

    /** Error. Una X con una vibración única de 300ms, luego estático. */
    FAIL,
    ;

    /** Duración del ciclo o del disparo, en ms. Todas salen de `Motion`, ninguna de aquí. */
    val durationMs: Int
        get() = when (this) {
            READY -> Motion.GLYPH_READY_MS
            BUSY -> Motion.GLYPH_BUSY_MS
            SHELL -> Motion.GLYPH_SHELL_MS
            REC -> Motion.GLYPH_REC_MS
            OK -> Motion.GLYPH_OK_MS
            FAIL -> Motion.GLYPH_FAIL_MS
        }

    /** Si el estado late en bucle o se dispara una vez. */
    val loops: Boolean get() = this == READY || this == BUSY || this == SHELL || this == REC
}

/**
 * El glifo de matriz de puntos.
 *
 * Reglas que lo mantienen del lado de la tipografía y no de la iconografía:
 *
 * - **La rejilla se dibuja entera, siempre.** Los apagados reposan al 18% y los encendidos al 100%,
 *   como un píxel apagado sigue viéndose en una matriz real. Es lo que hace que `READY` —un solo
 *   punto— se lea como un glifo y no como una mota, y lo que da a `BUSY` y `SHELL` una estela.
 * - **Un solo color**, el de la línea a la que pertenece. Nunca se tiñe.
 * - **Como máximo uno animado en pantalla** (§4.4). Los del historial van [frozen]: sin esa regla,
 *   una pantalla llena de historial es una discoteca.
 *
 * El progreso se lee **dentro** del `DrawScope`, nunca en el cuerpo del composable: leerlo fuera
 * recompondría el árbol 60-120 veces por segundo.
 */
@Composable
fun Glyph(
    state: GlyphState,
    cell: Dp,
    color: Color,
    modifier: Modifier = Modifier,
    /** Congelado en su fotograma final. Es lo que llevan todos los glifos del scrollback. */
    frozen: Boolean = false,
    /**
     * Con movimiento reducido, los glifos **se mantienen pero sin bucle**: solo su fotograma de
     * estado (§4.7). No desaparecen — siguen informando.
     */
    reducedMotion: Boolean = false,
) {
    val still = frozen || reducedMotion || !state.loops && frozen
    val progress: State<Float> = if (still) {
        remember(state) { mutableFloatStateOf(1f) }
    } else {
        val transition = rememberInfiniteTransition(label = "glyph")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(state.durationMs, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "glyph-progress",
        )
    }

    Canvas(
        // graphicsLayer: el glifo queda en su propio RenderNode y sus invalidaciones no tocan a los
        // hermanos. Con un glifo animado y decenas congelados, es la diferencia.
        modifier = modifier.size(cell).graphicsLayer(),
    ) {
        val p = progress.value
        val step = size.width / GRID
        val radius = step * DOT_RATIO

        for (row in 0 until GRID.toInt()) {
            for (col in 0 until GRID.toInt()) {
                val lit = intensity(state, row, col, p)
                val alpha = Palette.DOT_UNLIT + (Palette.DOT_LIT - Palette.DOT_UNLIT) * lit
                drawCircle(
                    color = color,
                    radius = radius,
                    center = Offset(
                        x = step * (col + 0.5f),
                        y = step * (row + 0.5f),
                    ),
                    alpha = alpha.coerceIn(0f, 1f),
                )
            }
        }
    }
}

private const val GRID = 5f
private const val DOT_RATIO = 0.22f

/**
 * Cuánto está encendido un punto, de 0 a 1. **Es toda la gramática del sistema de glifos.**
 *
 * Se separa del dibujo para poder razonarla —y testearla— sin Compose delante: es aritmética pura
 * sobre fila, columna y progreso.
 */
internal fun intensity(state: GlyphState, row: Int, col: Int, progress: Float): Float {
    val center = row == 2 && col == 2
    return when (state) {
        // Respiración: el punto central va de 40% a 100% y vuelta. Los demás, apagados.
        GlyphState.READY ->
            if (center) 0.4f + 0.6f * triangle(progress) else 0f

        // Pulso: más marcado que la respiración, y más rápido. Informa de que estás en un modo.
        GlyphState.REC ->
            if (center) triangle(progress) else 0f

        // Columna que barre de izquierda a derecha, con estela: los puntos por los que ya pasó
        // decaen de vuelta al suelo en lugar de apagarse de golpe.
        GlyphState.BUSY -> sweep(col.toFloat(), progress)

        // Cascada: filas encendiéndose de arriba abajo. Misma estela, otro eje.
        GlyphState.SHELL -> sweep(row.toFloat(), progress)

        // Diagonal ascendente que se atenúa. Un solo disparo: al terminar se queda ahí.
        GlyphState.OK -> if (row + col == 4) progress else 0f

        // Una X. La vibración va en el desplazamiento, no en la intensidad.
        GlyphState.FAIL -> if (row == col || row + col == 4) progress else 0f
    }
}

/** 0 → 1 → 0. Es lo que hace que la respiración sea suave y no un encendido/apagado duro. */
private fun triangle(progress: Float): Float = 1f - abs(progress * 2f - 1f)

/**
 * Un barrido con estela sobre un eje de cinco posiciones.
 *
 * La estela no es decoración: sin ella, `BUSY` sería un punto saltando y no se leería como
 * movimiento en una celda de 13sp.
 */
private fun sweep(position: Float, progress: Float): Float {
    val head = progress * (GRID + 1f) - 1f
    val distance = head - position
    return when {
        distance < 0f -> 0f
        distance > 2f -> 0f
        else -> 1f - distance / 2f
    }
}

/** El estado que le toca al prompt según lo que esté pasando. */
fun promptGlyph(busy: Boolean, shell: Boolean, recording: Boolean): GlyphState = when {
    recording -> GlyphState.REC
    shell -> GlyphState.SHELL
    busy -> GlyphState.BUSY
    else -> GlyphState.READY
}

/** El glifo que se congela junto a una línea ya emitida. */
fun lineGlyph(isError: Boolean, hasOutput: Boolean): GlyphState? = when {
    isError -> GlyphState.FAIL
    hasOutput -> GlyphState.OK
    else -> null
}

@Suppress("unused")
private fun unusedGuard(size: Size, x: Float): Int = (size.width + x).roundToInt()
