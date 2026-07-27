package dev.tty.ui.motion

import android.animation.ValueAnimator
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Movimiento reducido (functional.md §4.7, criterio 13).
 *
 * **En Android no existe un `isReduceMotionEnabled`.** La API pública es
 * `ValueAnimator.areAnimatorsEnabled()` (API 26+, justo el `minSdk`), respaldada por
 * `Settings.Global.ANIMATOR_DURATION_SCALE`, que el interruptor «Quitar animaciones» de
 * Accesibilidad pone a 0.
 *
 * El caso que se escapa si solo se observa el ajuste: el **Ahorro de batería** también desactiva los
 * animadores **sin tocarlo**. Por eso se leen los dos — el observador para reaccionar en vivo, y
 * `areAnimatorsEnabled()` para no perderse ese caso.
 *
 * Qué se apaga y qué no: se van `decode`, `settle`, el barrido y la deriva del degradado; **se
 * quedan** el cursor y los glifos, estos últimos en su fotograma de estado, sin bucle. La app sigue
 * siendo completamente usable, que es lo que pide el criterio 13.
 */
@Composable
fun rememberReducedMotion(): State<Boolean> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(isReduced(context)) }

    DisposableEffect(context) {
        val resolver = context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                state.value = isReduced(context)
            }
        }
        // Se observa el ajuste para reaccionar en vivo; el valor se recalcula leyendo también
        // areAnimatorsEnabled(), que es lo que cubre el Ahorro de batería.
        runCatching {
            resolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
                false,
                observer,
            )
        }
        onDispose { runCatching { resolver.unregisterContentObserver(observer) } }
    }
    return state
}

private fun isReduced(context: Context): Boolean {
    if (!ValueAnimator.areAnimatorsEnabled()) return true
    val scale = runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }.getOrDefault(1f)
    return scale == 0f
}
