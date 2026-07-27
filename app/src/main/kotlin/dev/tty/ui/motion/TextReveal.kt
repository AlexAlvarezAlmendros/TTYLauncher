package dev.tty.ui.motion

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import dev.tty.core.output.Reveal
import dev.tty.ui.theme.Motion
import kotlin.math.min
import kotlin.random.Random

/**
 * `settle` — el modo por defecto de toda salida de comandos (functional.md §4.5).
 *
 * Opacidad 0→100% con un desplazamiento de 4dp, escalonado 25ms respecto a la línea anterior. El
 * escalonado se aplica **solo a las 12 primeras líneas**; el resto entra de golpe, y el bloque
 * entero tiene un techo de 300ms salga lo que salga.
 *
 * Ese techo es lo que impide que un `apps` de 140 líneas tarde tres segundos y medio en aparecer:
 * sin él, «escalonado 25ms» sería una animación de tres segundos y medio.
 */
@Composable
fun rememberSettle(index: Int, enabled: Boolean): State<Float> {
    if (!enabled) return remember { mutableFloatStateOf(1f) }

    val staggered = min(index, Motion.SETTLE_STAGGER_LINES)
    val delay = min(staggered * Motion.SETTLE_STEP_MS, Motion.SETTLE_CAP_MS)

    val target = remember(index) { mutableFloatStateOf(0f) }
    LaunchedEffect(index) { target.floatValue = 1f }

    return animateFloatAsState(
        targetValue = target.floatValue,
        animationSpec = tween(
            durationMillis = Motion.SETTLE_CAP_MS - delay,
            delayMillis = delay,
            easing = Motion.Ease,
        ),
        label = "settle",
    )
}

/**
 * `decode` — solo para líneas cortas de estado, el banner y las confirmaciones.
 *
 * Cada carácter empieza como uno aleatorio de `▚▞░▒▓/\|-_=+*` y se resuelve al final, de izquierda a
 * derecha, ~14ms por carácter y con techo de 500ms.
 *
 * **Nunca se usa en la salida de `apps`, `ls`, `sh` ni `tmux`.** Esa regla no se decide aquí: la
 * fija `RevealPolicy` en `core/`, a partir del rol de la línea, para que no dependa de quién escriba
 * el composable.
 */
@Composable
fun rememberDecode(text: String, enabled: Boolean): State<String> {
    val out = remember(text) { mutableStateOf(if (enabled) "" else text) }
    if (!enabled) return out

    LaunchedEffect(text) {
        val chars = text.toCharArray()
        val total = min(chars.size * Motion.DECODE_CHAR_MS, Motion.DECODE_CAP_MS)
        val random = Random(text.hashCode())
        var elapsed = 0L
        var start = -1L

        while (elapsed < total) {
            withFrameMillis { frame ->
                if (start < 0) start = frame
                elapsed = frame - start
            }
            val resolved = if (total == 0) chars.size else (chars.size * elapsed / total).toInt()
            out.value = buildString(chars.size) {
                for (i in chars.indices) {
                    // Los espacios no se descifran: un hueco parpadeando se lee como suciedad.
                    append(
                        when {
                            i < resolved || chars[i] == ' ' -> chars[i]
                            else -> SCRAMBLE[random.nextInt(SCRAMBLE.length)]
                        },
                    )
                }
            }
        }
        out.value = text
    }
    return out
}

/** El conjunto del que salen los caracteres intermedios. Del design system, no inventado aquí. */
private val SCRAMBLE = Motion.DECODE_CHARSET

/** Si una línea con este modo debe animarse, dado el estado del sistema. */
fun Reveal.isAnimated(reducedMotion: Boolean): Boolean =
    this != Reveal.NONE && !reducedMotion
