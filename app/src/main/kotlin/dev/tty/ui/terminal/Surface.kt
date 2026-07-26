package dev.tty.ui.terminal

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
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
 * Aquí todavía **no hay deriva**: la Fase 5 añade el desplazamiento cíclico de ±2% cada 20s
 * (`Motion.DRIFT_MS` / `Motion.DRIFT_AMPLITUDE`) leyendo su fase justo donde está la nota de abajo.
 */
@Composable
fun TerminalSurface(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                // FASE 5: la fase de la deriva se lee AQUÍ DENTRO, nunca fuera.
                //   val t = drift.value
                // y desplaza cada parada ±Motion.DRIFT_AMPLITUDE. Hoy el degradado es estático.
                drawRect(
                    brush = Brush.verticalGradient(
                        colorStops = GradientStops,
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
