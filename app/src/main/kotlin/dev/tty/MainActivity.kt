package dev.tty

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import dev.tty.platform.AppContainer
import dev.tty.ui.TerminalState
import dev.tty.ui.terminal.TerminalScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * La actividad HOME de tty. La única del producto.
 *
 * Reglas que se cumplen aquí y no en otro sitio (docs/architecture.md §3):
 *  - `onNewIntent` es un **no-op**: pulsar HOME estando dentro no reinicia estado ni animaciones
 *    (functional.md §5.6). Cualquier reinicialización aquí hace que volver al launcher parpadee.
 *  - El botón atrás no hace nada, y se inutiliza con `BackHandler`. `onBackPressed()` ya no se
 *    ejecuta con targetSdk 36+ y **no avisa en compilación**: usarlo dejaría el botón suelto en
 *    silencio.
 *  - Edge-to-edge antes de `setContent`. El color de las barras sale del degradado.
 *  - **Ni una lectura de disco en el hilo principal.** Un ANR aquí es indistinguible de un móvil
 *    bloqueado, y esta es la pantalla de inicio: no hay a dónde huir.
 *
 * El estado sobrevive a rotar, a cambiar el tamaño de fuente y a volver de otra app porque el
 * `configChanges` del manifest evita que la actividad se recree. Lo que **no** sobrevive es que el
 * sistema mate el proceso: para eso está el scrollback en disco, no el `Bundle`.
 */
class MainActivity : ComponentActivity() {

    private lateinit var container: AppContainer
    private lateinit var state: TerminalState

    /**
     * Para el arranque. No usa `lifecycleScope` a propósito: `androidx.lifecycle` no es una
     * dependencia declarada del proyecto y no va a serlo por una sola corrutina.
     */
    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        container = AppContainer(this)
        state = TerminalState(
            // Lambda y no `container::submit`: los tipos función de Kotlin no son covariantes en el
            // retorno, así que una `(String) -> List<Line>` no encaja donde se espera
            // `(String) -> Unit`. Las líneas que devuelve las usará la Fase 5 para animar; aquí se
            // descartan porque la lista se reconstruye desde el scrollback.
            submitter = { input -> container.submit(input) },
            scrollback = container.scrollback,
        ).also { it.symbolProvider = { container.promptSymbol } }

        // Se pinta primero y se carga después: el prompt tiene que estar listo antes de que el
        // teclado termine de subir (criterio 10). Con 2000 líneas en disco la diferencia se nota.
        setContent { Tty() }

        startupScope.launch {
            container.restore()
            state.refresh()
            // El permiso de ficheros va después del banner, no antes: primero se ve qué es esto.
            container.requestStorageAccessOnFirstRun()
            state.refresh()
        }
    }

    @Composable
    private fun Tty() {
        // La pantalla raíz del sistema no navega hacia atrás (functional.md §5.7).
        BackHandler(enabled = true) { }
        TerminalScreen(state)
    }

    /** Volver al launcher no limpia nada y no reinicia ninguna animación (§5.6). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        container.onStart()
    }

    /**
     * `onStop`, no `onPause`: la documentación es explícita en que `onPause` no ofrece tiempo
     * suficiente para guardar. Y aun así es un refuerzo, no la garantía — el sistema mata el
     * proceso, no la actividad, así que la garantía real es la escritura diferida.
     */
    override fun onStop() {
        container.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        startupScope.cancel()
        state.close()
        container.close()
        super.onDestroy()
    }
}
