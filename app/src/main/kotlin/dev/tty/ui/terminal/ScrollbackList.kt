package dev.tty.ui.terminal

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import dev.tty.core.output.LineGlyph
import dev.tty.ui.glyph.Glyph
import dev.tty.ui.glyph.GlyphState
import dev.tty.ui.theme.Motion
import dev.tty.ui.theme.Spacing
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import dev.tty.core.output.Reveal
import dev.tty.ui.motion.isAnimated
import dev.tty.ui.motion.rememberDecode
import dev.tty.ui.motion.rememberSettle
import dev.tty.core.output.Line
import dev.tty.core.output.Role
import dev.tty.ui.theme.Palette
import dev.tty.ui.theme.Type

/**
 * El componente `Scrollback` del design system: la columna del historial.
 *
 * Cómo se pinta un historial invertido en Compose (architecture.md §8.1):
 *  - **`reverseLayout = false`** —el valor por defecto— alimentado con la lista **ya invertida**:
 *    índice 0 = la línea más reciente. `reverseLayout = true` anclaría el índice 0 al borde
 *    inferior, que es exactamente lo contrario de lo que pide un prompt fijo arriba. El
 *    `Scrollback` de `core/` ya guarda lo más reciente primero, así que no hay nada que invertir.
 *  - **`key = { it.id }`, nunca el índice**: con inserciones en cabeza el índice cambia para todos
 *    y rompe el anclaje y la reutilización.
 *  - El prompt **no** es un elemento de la lista.
 *
 * Y es el único sitio del árbol con `contentPadding` e `imePadding()`: el prompt no se mueve con el
 * teclado, solo se encoge el historial (functional.md §5.1, criterio 11).
 */
@Composable
fun ScrollbackList(
    /** Cuántas líneas ya estaban al arrancar: esas no se animan (§5.2). */
    restoredCount: Int = 0,
    reducedMotion: Boolean = false,
    /** `clear` en curso: el historial cae y se desvanece hacia abajo en 120ms (§4.6.5). */
    falling: Boolean = false,
    lines: List<Line>,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    // El desvanecimiento se calcula del ÍNDICE, no de la posición de scroll: leer el scroll haría
    // que las líneas cambiaran de opacidad al arrastrar, y lo que envejece es la línea, no la vista.
    // El recorrido es de un viewport —lo mismo que ocupa el degradado, que también está fijo a la
    // ventana—, así que no hace falta inventar ninguna constante: sale de la caja de línea del
    // design system.
    // La caída de `clear`: el bloque entero baja y se desvanece. Va sobre el contenedor y no
    // línea a línea porque lo que cae es el historial, no cada una de sus líneas por su cuenta.
    val fall by animateFloatAsState(
        targetValue = if (falling) 1f else 0f,
        animationSpec = tween(durationMillis = Motion.CLEAR_MS, easing = Motion.Ease),
        label = "clear-fall",
    )
    val fallOffsetPx = with(LocalDensity.current) { Spacing.S6.toPx() }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = 1f - fall
                translationY = fall * fallOffsetPx
            },
    ) {
        val fadeSpan = (maxHeight / Spacing.S4).coerceAtLeast(1f)

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentPadding = PaddingValues(
                top = Spacing.S4,
                bottom = Spacing.S6,
            ),
        ) {
            itemsIndexed(
                items = lines,
                key = { _, line -> line.id },
            ) { index, line ->
                // El modo de aparición lo decide `core/` a partir del ROL, nunca la UI: es lo
                // que impide que alguien decida un día que `apps` quedaría bonito descifrándose.
                val restored = lines.size - index <= restoredCount
                val reveal = dev.tty.core.output.RevealPolicy.forLine(line, restored)

                ScrollbackLine(
                    line = line,
                    lineAlpha = fadeAlpha(index, fadeSpan),
                    reveal = reveal,
                    // Solo se escalona lo que está arriba del todo: una línea que lleva ahí un rato
                    // no tiene por qué volver a entrar cuando se recompone la lista.
                    revealIndex = index,
                    reducedMotion = reducedMotion,
                )
            }
        }
    }
}

/**
 * Una línea del historial: el componente `Line`.
 *
 * El **rol** decide el color y el prefijo, y los decide el comando que la emitió, no esta función
 * (functional.md §4.2, §10). El prefijo sale de [Line.render], que es el mismo texto que se
 * persiste y se copia: un prefijo escrito aquí sería un segundo sitio donde se pueden separar.
 *
 * La animación de aparición (`settle` / `decode`) es Fase 5. Aquí el texto aparece ya presente, que
 * además es lo que pide la §5.2 para el historial persistido.
 */
@Composable
private fun ScrollbackLine(
    line: Line,
    lineAlpha: Float,
    reveal: Reveal = Reveal.NONE,
    revealIndex: Int = 0,
    reducedMotion: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val base = styleFor(line.role)
    val settleOffsetPx = with(LocalDensity.current) { Motion.SettleOffset.toPx() }

    // El glifo ocupa exactamente una celda de carácter: es un carácter más de la retícula, no un
    // icono junto al texto. Se mide sobre una cadena larga y se divide, porque medir un solo
    // carácter acumula error de subpíxel.
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val glyphCell = remember(measurer, density.density, density.fontScale) {
        with(density) { (measurer.measure("M".repeat(64), Type.Body).size.width / 64f).toDp() }
    }
    val animate = reveal.isAnimated(reducedMotion)
    val settle = rememberSettle(index = revealIndex, enabled = animate && reveal == Reveal.SETTLE)
    // El destello solo va en el eco: es lo que confirma que la tecla entró (§4.6.2).
    val flash = dev.tty.ui.motion.rememberEchoFlash(
        id = line.id,
        enabled = animate && line.role == Role.ECHO,
    )
    val decoded = rememberDecode(
        text = line.render(),
        enabled = animate && reveal == Reveal.DECODE,
    )

    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        // La celda del prefijo: el glifo la ocupa cuando lo hay (§4.4), y cuando no, se reserva
        // igual para que el texto de todas las líneas quede alineado. Sin la reserva, un eco con
        // glifo y una salida sin él empezarían en columnas distintas.
        Box(modifier = Modifier.width(glyphCell + Spacing.S1)) {
            val g = line.glyph
            if (g != null) {
                Glyph(
                    state = if (g == LineGlyph.FAIL) GlyphState.FAIL else GlyphState.OK,
                    cell = glyphCell,
                    color = base.color.copy(alpha = lineAlpha),
                    // SIEMPRE congelado: como máximo un glifo animado en pantalla, y es el del
                    // prompt. Sin esta regla, una pantalla llena de historial es una discoteca.
                    frozen = true,
                )
            }
        }

        BasicText(
            text = decoded.value,
            modifier = Modifier
                .weight(1f)
                // `settle`: opacidad 0→100% y +4dp. Va en un graphicsLayer y no en el color
                // porque hay que mover la línea, no solo atenuarla — y el progreso se lee DENTRO
                // del lambda, que es lo que mantiene esto en la fase de dibujo.
                .graphicsLayer {
                    val p = settle.value
                    alpha = p * flash.value
                    translationY = (1f - p) * settleOffsetPx
                },
            // El desvanecimiento por antigüedad va en el color y no en la capa: evita una capa
            // fuera de pantalla por línea, y el color base siempre es opaco.
            style = base.copy(color = base.color.copy(alpha = lineAlpha)),
        )
    }
}

/**
 * Los tres niveles de texto, todos acromáticos (functional.md §4.2). **Los errores son un gris más
 * brillante, nunca rojo**: un color con tono es una derrota del producto.
 */
private fun styleFor(role: Role): TextStyle = when (role) {
    // El segundo de los dos únicos tamaños: 10sp con tracking amplio (§4.3).
    Role.LABEL -> Type.Label
    Role.OUTPUT -> Type.Body
    Role.ERROR -> Type.BodyHigh
    // El eco y el estado van atenuados. RECORDING acompaña al prefijo `…` y es entrada capturada,
    // no salida: se lee con el mismo nivel que el eco.
    Role.ECHO, Role.STATUS, Role.RECORDING -> Type.BodyDim
}

/**
 * Desvanecimiento por antigüedad: de [Palette.FADE_MAX] a [Palette.FADE_MIN] a lo largo de [span]
 * líneas. **Sustituye a todo separador del producto** — no hay reglas, ni divisores, ni tarjetas.
 *
 * Las líneas no se ocultan: se hunden. Por eso el suelo es 0.35 y no 0.
 */
internal fun fadeAlpha(index: Int, span: Float): Float {
    val t = (index / span).coerceIn(0f, 1f)
    return Palette.FADE_MAX + (Palette.FADE_MIN - Palette.FADE_MAX) * t
}
