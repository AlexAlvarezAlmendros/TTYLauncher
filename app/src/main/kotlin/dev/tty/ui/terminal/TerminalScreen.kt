package dev.tty.ui.terminal

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.Modifier
import dev.tty.ui.TerminalState
import dev.tty.ui.theme.Spacing

/**
 * La pantalla entera: degradado, prompt fijo arriba y el historial debajo.
 *
 * El patrón de insets es el de architecture.md §3.4 y no se toca:
 *
 * ```
 * TerminalSurface  → el degradado llega a los bordes de la ventana, SIN insets
 *   Column         → windowInsetsPadding(safeDrawing) + el gutter del design system
 *     PromptRow    → fijo arriba, nunca se mueve
 *     ScrollbackList → imePadding(): el teclado solo encoge el historial
 * ```
 *
 * `safeDrawing` incluye el IME, y su padding es **inferior**: por eso el prompt, que está arriba,
 * no se mueve ni un píxel cuando sale el teclado. Es lo que hace trivial el criterio 11.
 */
@Composable
fun TerminalScreen(
    state: TerminalState,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val lines = state.lines
    val reducedMotion = dev.tty.ui.motion.rememberReducedMotion().value

    // El foco vive aquí, no dentro del prompt, porque tocar en cualquier parte tiene que devolverlo.
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    val onSubmit: (String) -> Unit = remember(state, listState) {
        { input: String ->
            state.submit(input)
            // requestScrollToItem, NO scrollToItem ni animateScrollToItem: esos son `suspend` y
            // compiten con el remeasure, y el salto se ve (architecture.md §8.1).
            listState.requestScrollToItem(0)
        }
    }

    // Insertar en cabeza NO desplaza la vista por sí solo: LazyList reancla por la key del primer
    // ítem visible y la línea nueva se queda fuera de pantalla. El bug es invisible con tres líneas
    // y aparece a las doscientas. La salida de un comando llega después del eco y de forma
    // asíncrona, así que hay que reanclar también cuando cambia la cabeza, no solo al enviar
    // (functional.md §5.3: cualquier entrada nueva devuelve la vista arriba).
    val headId = lines.firstOrNull()?.id
    LaunchedEffect(headId) {
        if (headId != null) listState.requestScrollToItem(0)
    }

    TerminalSurface(
        reducedMotion = reducedMotion,
        modifier = modifier
            // Tocar en cualquier sitio devuelve el foco al prompt y saca el teclado. En un terminal
            // no hay nada más que tocar, así que el gesto no compite con nada.
            //
            // `indication = null` explícito: ni ripple ni el overlay por defecto de foundation
            // (§4.8). Un destello de Material a pantalla completa sería la peor forma de romper el
            // producto.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                runCatching { focusRequester.requestFocus() }
                keyboard?.show()
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                // Margen horizontal idéntico para el prompt y para el contenido (§5.1).
                .padding(
                    start = Spacing.Gutter,
                    end = Spacing.Gutter,
                    top = Spacing.S3,
                ),
        ) {
            PromptRow(
                onSubmit = onSubmit,
                symbol = state.promptSymbol,
                // El glifo informa SIEMPRE, incluida la grabación: la §4.4 dice que el `…` se
                // mantiene como carácter de línea **porque** `REC` ya comunica el modo desde el
                // prompt. Anular el glifo ahí dejaba el modo sin su señal.
                glyph = dev.tty.ui.glyph.promptGlyph(
                    busy = state.busy,
                    shell = state.shellBusy,
                    recording = state.promptSymbol == "…",
                ),
                reducedMotion = reducedMotion,
                sweepKey = state.executions,
                focusRequester = focusRequester,
                onHistory = { state.previousInput() },
            )
            ScrollbackList(
                lines = lines,
                listState = listState,
                restoredCount = state.restoredCount,
                reducedMotion = reducedMotion,
                falling = state.falling,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
