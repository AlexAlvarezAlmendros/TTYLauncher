package dev.tty.ui

import dev.tty.core.output.Role
import dev.tty.core.scrollback.Scrollback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * El estado de la pantalla.
 *
 * **Por qué existe este fichero.** Durante seis fases el proyecto tuvo 297 tests, todos en `core/`,
 * bajo la regla «no se testean composables uno a uno: el valor está en el motor». La regla es buena
 * y sigue en pie — pero [TerminalState] **no es un composable**: es una clase de Kotlin normal, sin
 * un solo `@Composable`, que la actividad construye en `onCreate`. Que no tuviera tests fue un
 * descuido, no una decisión, y costó un crash de arranque al 100% de los lanzamientos.
 *
 * Lo que se prueba aquí es el contrato de la clase, no cómo se pinta: construirla, que el espejo
 * siga al scrollback, y que ninguna propiedad se lea antes de existir.
 */
class TerminalStateTest {

    /**
     * Un ámbito propio: el de por defecto es `Dispatchers.Main.immediate`, que en la JVM no existe.
     * `Unconfined` ejecuta en el hilo de la llamada, así que [TerminalState.submit] queda síncrono y
     * no hace falta esperar a nada.
     */
    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun state(
        scrollback: Scrollback,
        submitter: suspend (String) -> Unit = {},
    ) = TerminalState(submitter = submitter, scrollback = scrollback, scope = scope())

    @Test
    fun `construirlo no lanza y publica lo que ya hay`() {
        // EL TEST DE REGRESIÓN. El `init` llama a `sync()`, y `sync()` lee `_falling`. Cuando la
        // Fase 5 declaró `_falling` DESPUÉS del `init`, Kotlin —que inicializa en orden de
        // declaración— la dejaba a `null` durante la construcción y esto reventaba con un NPE
        // dentro de `MainActivity.onCreate`: la actividad HOME muriendo antes del primer fotograma.
        //
        // Un móvil con el launcher por defecto crasheando en el arranque no tiene pantalla de
        // inicio. Por eso este caso, que parece trivial, es el más importante del fichero.
        val scrollback = Scrollback()
        scrollback.add("lo de ayer", Role.OUTPUT)

        val state = state(scrollback)

        assertEquals(listOf("lo de ayer"), state.lines.map { it.text })
        assertFalse(state.falling)
    }

    @Test
    fun `un scrollback vacio da una pantalla vacia`() {
        val state = state(Scrollback())
        assertEquals(0, state.lines.size)
    }

    @Test
    fun `el espejo se resincroniza desde el scrollback`() {
        // La invariante 2 de la clase: el scrollback de `core/` es la única fuente de verdad, y
        // `lines` es un espejo. Nadie escribe en el espejo directamente.
        val scrollback = Scrollback()
        val state = state(scrollback)

        scrollback.add("nueva", Role.OUTPUT)
        assertEquals(0, state.lines.size) // todavía no se ha publicado
        state.refresh()
        assertEquals(listOf("nueva"), state.lines.map { it.text })
    }

    @Test
    fun `lo mas reciente va primero`() {
        // El orden que espera la lista de Compose: `reverseLayout = false` con la lista ya invertida.
        val scrollback = Scrollback()
        scrollback.add("vieja", Role.OUTPUT)
        scrollback.add("nueva", Role.OUTPUT)

        assertEquals(listOf("nueva", "vieja"), state(scrollback).lines.map { it.text })
    }

    @Test
    fun `una entrada en blanco no ejecuta nada`() {
        // functional.md §5.3: no hace nada, no imprime, no da error.
        var ejecutadas = 0
        val state = state(Scrollback()) { ejecutadas++ }

        state.submit("")
        state.submit("   ")

        assertEquals(0, ejecutadas)
        assertEquals(0, state.executions)
    }

    @Test
    fun `enviar ejecuta y cuenta la ejecucion`() {
        val scrollback = Scrollback()
        val state = state(scrollback) { scrollback.add("resultado", Role.OUTPUT) }

        state.submit("apps")

        assertEquals(1, state.executions)
        assertEquals(listOf("resultado"), state.lines.map { it.text })
    }

    @Test
    fun `el simbolo del prompt sale del motor, no de la UI`() {
        val state = state(Scrollback())
        assertEquals(">", state.promptSymbol)

        // `…` mientras se graba un script (§8.2). La UI no decide esto: lo pregunta.
        state.symbolProvider = { "…" }
        state.refresh()
        assertEquals("…", state.promptSymbol)
    }

    @Test
    fun `sin ciclador de historial no se recorre nada`() {
        // El control no se dibuja si no hay historial: uno que no hace nada es ruido.
        assertEquals(null, state(Scrollback()).previousInput())
    }

    @Test
    fun `el historial se recorre por quien lo inyecta`() {
        val state = state(Scrollback())
        state.historyCycler = { "open spotify" }
        assertEquals("open spotify", state.previousInput())
    }

    @Test
    fun `las lineas restauradas se cuentan aparte`() {
        // Lo que viene del disco no se anima (§5.2): la lista lo necesita para saber cuáles saltar.
        val state = state(Scrollback())
        assertEquals(0, state.restoredCount)

        state.markRestored(12)
        assertEquals(12, state.restoredCount)
    }
}
