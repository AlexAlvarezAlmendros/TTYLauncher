package dev.tty.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * El historial de entradas (functional.md §5.4).
 *
 * Es lo único del producto que se recorre con un toque, así que su comportamiento se nota en cada
 * uso: dar la vuelta, no duplicar repeticiones y no guardar vacíos son las tres cosas que
 * distinguen un historial usable de uno molesto.
 */
class InputHistoryTest {

    @Test
    fun `recuerda de mas reciente a mas antiguo`() {
        val h = InputHistory()
        h.remember("apps")
        h.remember("open spotify")
        h.remember("clear")

        assertEquals(listOf("clear", "open spotify", "apps"), h.all())
    }

    @Test
    fun `no guarda entradas vacias`() {
        val h = InputHistory()
        h.remember("")
        h.remember("   ")
        assertEquals(0, h.size)
    }

    @Test
    fun `repetir el ultimo comando no lo duplica`() {
        // Escribir `apps` tres veces y luego pasar tres veces por `apps` al recorrer es fricción,
        // no memoria.
        val h = InputHistory()
        h.remember("apps")
        h.remember("apps")
        h.remember("apps")
        assertEquals(1, h.size)
    }

    @Test
    fun `repetir uno anterior si cuenta`() {
        // Solo se colapsa lo consecutivo: `apps`, `clear`, `apps` son tres momentos distintos.
        val h = InputHistory()
        h.remember("apps")
        h.remember("clear")
        h.remember("apps")
        assertEquals(listOf("apps", "clear", "apps"), h.all())
    }

    @Test
    fun `guarda como maximo el limite`() {
        val h = InputHistory(max = 3)
        listOf("a", "b", "c", "d").forEach { h.remember(it) }

        assertEquals(3, h.size)
        assertEquals(listOf("d", "c", "b"), h.all())
    }

    @Test
    fun `el limite por defecto es el del funcional`() {
        val h = InputHistory()
        repeat(Limits.INPUT_HISTORY + 10) { h.remember("cmd $it") }
        assertEquals(Limits.INPUT_HISTORY, h.size)
    }

    @Test
    fun `recorre de mas reciente a mas antiguo y vuelve a empezar`() {
        val h = InputHistory()
        h.remember("uno")
        h.remember("dos")

        assertEquals("dos", h.cycle())
        assertEquals("uno", h.cycle())
        // La vuelta pasa por el prompt vacío: es la forma de salir del recorrido sin borrar a mano.
        assertEquals("", h.cycle())
        assertEquals("dos", h.cycle())
    }

    @Test
    fun `sin historial no devuelve nada`() {
        // Y por eso el control no se dibuja: uno que no hace nada es ruido.
        assertNull(InputHistory().cycle())
    }

    @Test
    fun `enviar reinicia el recorrido`() {
        val h = InputHistory()
        h.remember("uno")
        h.remember("dos")

        h.cycle() // dos
        h.remember("tres")

        // Tras enviar, el recorrido empieza de cero: lo más reciente es lo que se acaba de escribir.
        assertEquals("tres", h.cycle())
    }
}
