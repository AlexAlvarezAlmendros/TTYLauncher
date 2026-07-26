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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import dev.tty.core.output.Line
import dev.tty.core.output.Role
import dev.tty.ui.theme.Palette
import dev.tty.ui.theme.Spacing
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
    lines: List<Line>,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    // El desvanecimiento se calcula del ÍNDICE, no de la posición de scroll: leer el scroll haría
    // que las líneas cambiaran de opacidad al arrastrar, y lo que envejece es la línea, no la vista.
    // El recorrido es de un viewport —lo mismo que ocupa el degradado, que también está fijo a la
    // ventana—, así que no hace falta inventar ninguna constante: sale de la caja de línea del
    // design system.
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
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
                ScrollbackLine(line = line, lineAlpha = fadeAlpha(index, fadeSpan))
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
    modifier: Modifier = Modifier,
) {
    val base = styleFor(line.role)
    BasicText(
        text = line.render(),
        modifier = modifier.fillMaxWidth(),
        // El desvanecimiento va en el color y no en un graphicsLayer: evita una capa fuera de
        // pantalla por línea, y el color base siempre es opaco, así que sustituir el alfa es exacto.
        style = base.copy(color = base.color.copy(alpha = lineAlpha)),
    )
}

/**
 * Los tres niveles de texto, todos acromáticos (functional.md §4.2). **Los errores son un gris más
 * brillante, nunca rojo**: un color con tono es una derrota del producto.
 */
private fun styleFor(role: Role): TextStyle = when (role) {
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
