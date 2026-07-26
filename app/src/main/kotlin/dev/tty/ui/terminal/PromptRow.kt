package dev.tty.ui.terminal

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import dev.tty.ui.theme.Motion
import dev.tty.ui.theme.Palette
import dev.tty.ui.theme.Spacing
import dev.tty.ui.theme.Type
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first

/**
 * El componente `Prompt` del design system: la fila fija de arriba.
 *
 * Contratos que no se negocian (functional.md §5.1, §5.2, §5.3):
 *  - **Nunca se mueve.** Ni con el teclado, ni con el scroll, ni con la cantidad de historial. Vive
 *    fuera del árbol de la lista y el `imePadding()` es cosa del historial, no suya.
 *  - **Nunca pierde el foco** y el teclado sale solo: obligar a un toque previo rompe el modelo.
 *  - Enviar ejecuta, limpia el campo y **mantiene foco y teclado**.
 *  - Nunca se renderizan dos.
 *
 * Lo que todavía no está: el glifo de matriz de puntos que sustituye al símbolo `>` y el barrido de
 * luz de la línea al ejecutar son Fase 5 (functional.md §4.4, §4.6). Aquí el prompt es el símbolo
 * atenuado, el campo y la única línea del producto.
 */
@Composable
fun PromptRow(
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
    state: TextFieldState = rememberTextFieldState(),
    /**
     * `>` normalmente y `…` mientras se graba un script (functional.md §8.2). El símbolo **es** la
     * señal de que estás en un modo distinto: sin él, teclear en grabación se parecería demasiado a
     * teclear comandos.
     */
    symbol: String = PROMPT_SYMBOL,
) {
    val focusRequester = remember { FocusRequester() }
    val windowInfo = LocalWindowInfo.current

    // Foco automático (architecture.md §8.2). El fallo clásico de «a veces no sale el teclado» no es
    // del FocusRequester: es una carrera con el foco de VENTANA, y en un launcher se agrava porque
    // la actividad se reanuda en cada pulsación de HOME. Pedir el foco antes de que la ventana lo
    // tenga funciona en el emulador y falla en el dispositivo real.
    LaunchedEffect(focusRequester, windowInfo) {
        snapshotFlow { windowInfo.isWindowFocused }.filter { it }.first()
        // requestFocus() lanza si el FocusRequester no está adjunto todavía. En la actividad HOME
        // una excepción es un móvil bloqueado: se traga, y el peor caso es un teclado que no sale.
        runCatching { focusRequester.requestFocus() }
    }

    // Ancho de celda, medido sobre una cadena LARGA dividida entre su longitud (architecture.md
    // §8.5): TextLayoutResult.size es IntSize en píxeles enteros, y medir un solo carácter acumula
    // error de subpíxel. Con una monoespaciada el resultado es el avance exacto de cualquier celda.
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val advancePx = remember(measurer, density.density, density.fontScale) {
        val probe = "0".repeat(CELL_PROBE_LENGTH)
        val measured = measurer.measure(AnnotatedString(probe), style = Type.Body)
        measured.size.width.toFloat() / probe.length
    }

    // El layout del campo, capturado en la fase de layout y leído en la de dibujo. Nunca se lee en
    // composición: eso sí sería un bucle.
    val textLayout = remember { mutableStateOf<TextLayoutResult?>(null) }

    // Parpadeo del cursor: bucle de estado, exento del techo de 500ms (functional.md §4.6).
    // CURSOR_MS es el ciclo completo; con RepeatMode.Reverse la ida son la mitad.
    val blinkTransition = rememberInfiniteTransition(label = "cursor")
    val blink = blinkTransition.animateFloat(
        initialValue = 1f,
        targetValue = CURSOR_ALPHA_MIN,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = Motion.CURSOR_MS / 2,
                easing = Motion.EaseSoft,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "cursorAlpha",
    )

    // El scroll horizontal del campo: sin él, el cursor de una línea larga se dibujaría donde el
    // texto ya no está.
    val fieldScroll = rememberScrollState()

    val send: () -> Unit = {
        val typed = state.text.toString()
        // Se limpia antes de ejecutar: la entrada nunca espera a que termine el comando (§4.7).
        // No se toca el foco, así que se mantiene junto con el teclado.
        state.clearText()
        onSubmit(typed)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(text = symbol, style = Type.BodyDim)
            Spacer(modifier = Modifier.width(Spacing.S2))
            BasicTextField(
                state = state,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .drawBehind {
                        // Todo el estado que cambia se lee AQUÍ DENTRO: solo se repite la fase de
                        // dibujo, sin recomposición ni relayout (architecture.md §8.4).
                        val opacity = blink.value
                        val scrolled = fieldScroll.value.toFloat()
                        val box = cursorBox(
                            layout = textLayout.value,
                            caret = state.selection.start,
                            advancePx = advancePx,
                            fallbackHeight = size.height,
                        )
                        val left = box.left - scrolled
                        val boxHeight = if (box.height > 0f) box.height else size.height
                        if (left > -advancePx && left < size.width) {
                            drawRect(
                                color = Palette.TextPrimary,
                                topLeft = Offset(left, box.top),
                                size = Size(advancePx, boxHeight),
                                alpha = opacity,
                            )
                        }
                    },
                textStyle = Type.Body,
                lineLimits = TextFieldLineLimits.SingleLine,
                keyboardOptions = KeyboardOptions(
                    // architecture.md §8.2. Ojo: ninguna de estas opciones apaga las sugerencias de
                    // Gboard —autoCorrectEnabled solo quita TYPE_TEXT_FLAG_AUTO_CORRECT y Ascii solo
                    // pide un teclado capaz de ASCII—. Decisión abierta, tarea 0.12.
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Go,
                    // Más fiable que LocalSoftwareKeyboardController.show(), que es best-effort y
                    // falla en silencio si la vista aún no está adjunta.
                    showKeyboardOnFocus = true,
                ),
                // No se invoca performDefaultAction: la acción por defecto de «Ir» cierra el
                // teclado, y aquí el teclado no se cierra nunca.
                onKeyboardAction = KeyboardActionHandler { send() },
                onTextLayout = { getResult -> textLayout.value = getResult() },
                // El cursor del sistema se apaga y se dibuja el bloque propio (§4.6.1).
                cursorBrush = SolidColor(Color.Transparent),
                scrollState = fieldScroll,
            )
        }

        Spacer(modifier = Modifier.height(Spacing.S2))

        // El único borde del producto (functional.md §4.8). La Fase 5 le añade el barrido de luz de
        // izquierda a derecha, una vez por ejecución: ese barrido es el «enter» hecho visible.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(Spacing.RuleWidth)
                .background(Palette.Rule),
        )
    }
}

/**
 * La caja del cursor de bloque.
 *
 * **La trampa documentada (architecture.md §8.3):** `getBoundingBox(offset)` valida `[0, length)` —
 * excluye el final— y en un prompt el cursor está al final del texto prácticamente siempre. Llamarlo
 * ahí lanza `IllegalArgumentException`, y ese crash cae en la actividad HOME. `getCursorRect`
 * admite `[0, length]` pero devuelve una caja de grosor ~0, así que se ensancha con el avance de
 * celda ya medido.
 *
 * Si todavía no hay layout —el primer fotograma— se cae al cálculo monoespaciado puro, que en esta
 * fuente da exactamente lo mismo. Nunca se devuelve `null` ni se lanza: el cursor es lo único que
 * hace legible el campo.
 */
private fun cursorBox(
    layout: TextLayoutResult?,
    caret: Int,
    advancePx: Float,
    fallbackHeight: Float,
): Rect {
    if (layout == null) {
        val left = caret.coerceAtLeast(0) * advancePx
        return Rect(left, 0f, left + advancePx, fallbackHeight)
    }
    // Se compara contra la longitud del texto YA MAQUETADO, no contra la del estado: es la que
    // valida getBoundingBox, y entre ambas puede haber un fotograma de diferencia.
    val length = layout.layoutInput.text.length
    val offset = caret.coerceIn(0, length)
    return if (offset < length) {
        layout.getBoundingBox(offset)
    } else {
        val thin = layout.getCursorRect(offset)
        Rect(thin.left, thin.top, thin.left + advancePx, thin.bottom)
    }
}

/** El símbolo del prompt (functional.md §10). En atenuado, como el eco de la entrada. */
private const val PROMPT_SYMBOL = ">"

/** Alias local del token. El valor vive en `Palette`, que es el espejo del design system. */
private const val CURSOR_ALPHA_MIN = Palette.CURSOR_ALPHA_MIN

/** Celdas de la cadena de medida. Cuantas más, menor el error de subpíxel al dividir. */
private const val CELL_PROBE_LENGTH = 64
