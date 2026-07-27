package dev.tty.ui.terminal

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.drawBehind
import dev.tty.ui.theme.Motion
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import dev.tty.ui.theme.Palette

/**
 * El componente `Surface` del design system: **el degradado de cinco paradas y nada más**.
 *
 * Reglas que se cumplen aquí (functional.md §4.2, docs/design/DESIGN-SYSTEM.md):
 *  - Una superficie por vista. Nunca una segunda superficie encima: no hay tarjetas ni paneles.
 *  - Las paradas están **fijas al viewport**, no al contenido: por eso el historial se hunde en el
 *    degradado según envejece en lugar de arrastrarlo consigo.
 *  - **Los insets no se aplican aquí.** El degradado llega a los bordes de la ventana; el padding
 *    de `safeDrawing` es cosa del contenido (architecture.md §3.4).
 *
 * Cómo se pinta (architecture.md §8.4): `Modifier.drawBehind`, y **todo el estado de animación se
 * lee dentro del lambda de dibujo**. Leerlo en el cuerpo del composable recompondría el árbol entero
 * 60-120 veces por segundo. `drawWithCache` tampoco vale: su bloque de caché se reejecuta cuando
 * cambia el estado que lee, así que reasignaría el `Shader` en cada fotograma.
 *
 * La deriva de ±2% cada 20s (§4.6.4) lee su fase **dentro** del lambda de dibujo. Es lo que impide
 * que la pantalla parezca una captura estática, y la única animación exenta del techo de 500ms: no
 * es una transición, es un estado sostenido.
 */
@Composable
fun TerminalSurface(
    /**
     * Con movimiento reducido la deriva se apaga: el degradado queda estático, que es su fotograma
     * correcto. No es una degradación — el degradado es el producto, no su animación.
     */
    reducedMotion: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    // La deriva del degradado: las paradas se desplazan ±2% de forma cíclica cada 20s. Casi
    // imperceptible, ambiental. Es lo que impide que la pantalla parezca una captura estática, y la
    // única animación exenta del techo de 500ms por no ser una transición.
    val drift = if (reducedMotion) {
        remember { mutableFloatStateOf(0f) }
    } else {
        rememberInfiniteTransition(label = "drift").animateFloat(
            initialValue = -1f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = Motion.DRIFT_MS, easing = Motion.EaseSoft),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "drift-phase",
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                // La fase se lee AQUÍ DENTRO, nunca en el cuerpo del composable: leerla fuera
                // recompondría el árbol entero 60-120 veces por segundo.
                val t = drift.value

                // Se cuantiza a ~10 Hz: con un ciclo de 20s y ±2%, es visualmente idéntico y
                // ahorra construir un Shader nuevo en cada frame.
                val phase = kotlin.math.round(t * 100f) / 100f
                val shift = phase * Motion.DRIFT_AMPLITUDE

                drawRect(
                    brush = Brush.verticalGradient(
                        colorStops = GradientStops
                            .map { (stop, color) -> (stop + shift).coerceIn(0f, 1f) to color }
                            .toTypedArray(),
                        // Fijo al viewport: de borde a borde de la ventana, no del contenido.
                        startY = 0f,
                        endY = size.height,
                    ),
                )
            },
        content = content,
    )
}

/**
 * Las cinco paradas del design system, emparejadas una sola vez.
 *
 * Ni un color ni una posición se escriben aquí: salen de [Palette], que es el espejo en Kotlin de
 * `tokens/colors.css`.
 */
private val GradientStops: Array<Pair<Float, Color>> =
    Array(Palette.Gradient.size) { i -> Palette.Stops[i] to Palette.Gradient[i] }
