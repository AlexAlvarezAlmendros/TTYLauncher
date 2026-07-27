package dev.tty.ui

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import dev.tty.core.output.Line
import dev.tty.core.scrollback.Scrollback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * El estado de la pantalla del terminal.
 *
 * **No es un `ViewModel`**: `androidx.lifecycle` no es una dependencia del proyecto y no va a serlo
 * (architecture.md §9). Es una clase normal que la actividad crea una vez y conserva, con su propio
 * `CoroutineScope`; la actividad la cierra con [close] en `onDestroy`. Con `configChanges` cubriendo
 * rotación, densidad y `fontScale`, la actividad no se recrea y no hace falta nada más.
 *
 * Dos invariantes que este estado existe para garantizar:
 *
 *  1. **La entrada nunca se bloquea.** [submit] lanza y vuelve: se puede escribir y enviar el
 *     comando siguiente mientras el anterior sigue corriendo (functional.md §4.7, architecture.md
 *     §5.2). No hay flag que deshabilite el campo, y [busy] es solo información para el glifo
 *     `BUSY`/`SHELL` de la Fase 5.
 *  2. **El scrollback de `core/` es la única fuente de verdad.** [lines] es un espejo observable que
 *     se resincroniza desde él. Eso es lo que hace que `clear` —que vacía el scrollback desde
 *     dentro del propio comando— deje la pantalla correcta sin que la UI tenga que saber qué hace
 *     cada verbo, y lo que hace que el recorte a 2000 líneas se refleje solo.
 */
class TerminalState(
    /**
     * Quien ejecuta de verdad. **No es el [dev.tty.core.TerminalEngine] a pelo**: es
     * `AppContainer::submit`, que además persiste lo que el motor produce. Llamar al motor
     * directamente desde aquí se llevaría por delante la persistencia sin que nada fallara — que es
     * justo la clase de bug que no se ve hasta que reinicias el móvil y el historial no está.
     */
    private val submitter: suspend (String) -> Unit,
    private val scrollback: Scrollback,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {

    /** Lo más reciente primero: el orden que espera la lista de Compose (architecture.md §8.1). */
    private val _lines = mutableStateListOf<Line>()
    val lines: List<Line> get() = _lines

    private val running = mutableIntStateOf(0)

    private val _promptSymbol = androidx.compose.runtime.mutableStateOf(">")

    /** El símbolo del prompt: `…` mientras se graba un script. Lo decide el motor, no la UI. */
    val promptSymbol: String get() = _promptSymbol.value

    /** Cómo preguntarle al motor por el símbolo. Lo inyecta quien construye el estado. */
    var symbolProvider: () -> String = { ">" }

    /** Cómo recorrer el historial de entradas. Lo inyecta quien construye el estado. */
    var historyCycler: () -> String? = { null }

    /** El siguiente del historial, o `null` si no hay nada que recorrer. */
    fun previousInput(): String? = historyCycler()

    /** Si hay alguna ejecución viva. Lo lee el glifo del prompt; nadie bloquea con él. */
    val busy: Boolean get() = running.intValue > 0

    private val shell = mutableIntStateOf(0)

    /** Si lo que corre es una ejecución en Termux: el glifo `SHELL` en vez de `BUSY` (§4.4). */
    val shellBusy: Boolean get() = shell.intValue > 0

    private val _executions = mutableIntStateOf(0)

    /** Cuántas líneas se han enviado. Dispara el barrido del prompt, una vez por ejecución. */
    val executions: Int get() = _executions.intValue

    private val _restoredCount = mutableIntStateOf(0)

    /**
     * Cuántas líneas venían del disco. **Esas no se animan** (§5.2): lo persistido se muestra ya
     * presente, y solo lo que llega durante la sesión entra con `settle` o `decode`.
     */
    val restoredCount: Int get() = _restoredCount.intValue

    /** Lo llama el arranque tras cargar el historial. */
    fun markRestored(count: Int) {
        _restoredCount.intValue = count
    }

    private val _falling = androidx.compose.runtime.mutableStateOf(false)

    /**
     * Si el historial está cayendo por un `clear` (§4.6.5).
     *
     * `clear` **no hace desaparecer** el historial: lo deja caer y desvanecerse hacia abajo en
     * 120ms. La diferencia importa porque una pantalla que se vacía en un fotograma no distingue
     * «se borró» de «falló al cargar», y `clear` es el único borrado real del producto.
     */
    val falling: Boolean get() = _falling.value

    /**
     * **Este bloque va después de TODAS las propiedades que [sync] lee, y no es un detalle de
     * estilo.** Kotlin inicializa en orden de declaración: un `init` que llama a un método que lee
     * una propiedad declarada más abajo la lee valiendo `null`, y en la actividad HOME eso es un
     * crash en `onCreate` antes de pintar un solo fotograma — un móvil sin pantalla de inicio.
     *
     * Es exactamente lo que pasó cuando la Fase 5 añadió `_falling` junto a `fallAndClear()`, al
     * final de la clase, en vez de aquí arriba con el resto del estado.
     */
    init {
        sync()
    }

    /**
     * Ejecuta una línea escrita en el prompt.
     *
     * Devuelve inmediatamente: el trabajo va en el scope propio, nunca en el hilo de la llamada más
     * allá de lo que el comando tarde en suspenderse. Cada comando es responsable de irse a
     * `Dispatchers.IO` si toca disco — un ANR en la actividad HOME es indistinguible de un móvil
     * bloqueado.
     */
    fun submit(input: String) {
        // Entrada vacía: no hace nada, no imprime, no da error (functional.md §5.3). El motor
        // también lo garantiza; aquí se evita además una resincronización inútil por cada Intro.
        if (input.isBlank()) return

        running.intValue += 1
        _executions.intValue += 1
        scope.launch {
            try {
                // Devuelve las líneas nuevas, de la más antigua a la más reciente, para que la
                // Fase 5 sepa cuáles animar. Hoy no se usan: la lista se reconstruye desde el
                // scrollback, que ya las contiene.
                submitter(input)
            } finally {
                running.intValue -= 1
                // Un scrollback vacío después de ejecutar solo lo produce `clear`. Detectarlo así
                // —y no preguntándole al comando— mantiene a la UI sin saber qué hace cada verbo,
                // que es la propiedad que hace que esto no se rompa al añadir el siguiente.
                if (scrollback.size == 0 && _lines.isNotEmpty()) fallAndClear() else sync()
            }
        }

        // El eco tiene que verse YA, no cuando el comando termine (functional.md §5.3). Con
        // Dispatchers.Main.immediate `launch` no despacha si ya estamos en el hilo principal: el
        // cuerpo corre hasta la primera suspensión real, y para entonces el motor ya ha metido el
        // eco en el scrollback. Este segundo sync lo publica. Si el comando no suspendió, es un
        // no-op porque el `finally` ya sincronizó.
        sync()
    }

    /**
     * Vacía la pantalla con la caída.
     *
     * El orden es al revés que en todo lo demás: primero se anima con las líneas todavía puestas, y
     * solo cuando la caída termina se publica el scrollback ya vacío.
     */
    private fun fallAndClear() {
        _falling.value = true
        scope.launch {
            kotlinx.coroutines.delay(dev.tty.ui.theme.Motion.CLEAR_MS.toLong())
            _falling.value = false
            sync()
        }
    }

    /**
     * Vuelve a publicar lo que haya en el scrollback.
     *
     * Lo llama quien lo modifique desde fuera de [submit]: la carga del historial persistido al
     * arrancar (en `Dispatchers.IO`, y luego esto), el banner de primera ejecución y la
     * implementación de `Session.clearScrollback`.
     *
     * **Desde el hilo principal.** La lectura del disco va en `Dispatchers.IO`, pero el volcado al
     * espejo no: se hace en dos pasos y desde otro hilo podría verse el instante vacío de en medio.
     */
    fun refresh() {
        sync()
    }

    /** Cancela el trabajo en curso. Se llama desde `onDestroy`. */
    fun close() {
        scope.cancel()
    }

    private fun sync() {
        _promptSymbol.value = symbolProvider()
        // Mientras cae, la lista se queda como estaba: es lo que hay que animar.
        if (_falling.value) return
        // Reconstruir la lista entera cuesta lo que copiar 2000 referencias, y a cambio no hay
        // ninguna ruta por la que el espejo pueda divergir del scrollback. Las keys son los ids, así
        // que Compose reutiliza los ítems y no se pierde la posición del scroll.
        _lines.clear()
        _lines.addAll(scrollback.lines)
    }
}
