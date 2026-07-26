package dev.tty.core.scrollback

import dev.tty.core.Limits
import dev.tty.core.output.LineIds
import dev.tty.core.output.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El scrollback en memoria.
 *
 * Dos propiedades y ninguna más: **lo más reciente va primero** —porque es como lo consume la lista
 * de Compose sin invertir nada en cada recomposición— y **el recorte descarta lo más antiguo**, que
 * es lo que hace que el arranque no dependa de cuánto se haya usado el launcher.
 */
class ScrollbackTest {

    private fun textos(s: Scrollback): List<String> = s.lines.map { it.text }

    @Test
    fun `lo mas reciente va primero`() {
        val s = Scrollback()
        s.add("primera", Role.OUTPUT)
        s.add("segunda", Role.OUTPUT)
        s.add("tercera", Role.OUTPUT)

        assertEquals(listOf("tercera", "segunda", "primera"), textos(s))
        assertEquals(3, s.size)
    }

    @Test
    fun `add devuelve la linea que acaba de insertar`() {
        val s = Scrollback()
        val line = s.add("hola", Role.STATUS)

        assertEquals("hola", line.text)
        assertEquals(Role.STATUS, line.role)
        assertEquals(line, s.lines.first())
    }

    @Test
    fun `los identificadores son monotonos y no el indice`() {
        // El id es la key de la lista de Compose: con inserciones en cabeza el índice cambia para
        // todos y rompe el anclaje.
        val s = Scrollback(ids = LineIds())
        val a = s.add("a", Role.OUTPUT)
        val b = s.add("b", Role.OUTPUT)
        val c = s.add("c", Role.OUTPUT)

        assertEquals(0L, a.id)
        assertEquals(1L, b.id)
        assertEquals(2L, c.id)
        // La más reciente es la primera de la lista, pero la de id mayor.
        assertEquals(c.id, s.lines.first().id)
    }

    @Test
    fun `addAll conserva el orden en que se le dan las lineas`() {
        val s = Scrollback()
        val added = s.addAll(
            listOf(
                "una" to Role.OUTPUT,
                "dos" to Role.OUTPUT,
                "tres" to Role.STATUS,
            ),
        )

        assertEquals(listOf("una", "dos", "tres"), added.map { it.text })
        assertEquals(listOf("tres", "dos", "una"), textos(s))
    }

    @Test
    fun `el recorte descarta lo mas antiguo`() {
        val s = Scrollback(max = 3)
        for (t in listOf("a", "b", "c", "d", "e")) s.add(t, Role.OUTPUT)

        assertEquals(3, s.size)
        assertEquals(listOf("e", "d", "c"), textos(s))
    }

    @Test
    fun `el recorte no se dispara justo en el limite`() {
        val s = Scrollback(max = 3)
        for (t in listOf("a", "b", "c")) s.add(t, Role.OUTPUT)

        assertEquals(3, s.size)
        assertEquals(listOf("c", "b", "a"), textos(s))
    }

    @Test
    fun `el limite por defecto es el del producto`() {
        val s = Scrollback()
        repeat(Limits.SCROLLBACK_LINES + 10) { s.add("línea $it", Role.OUTPUT) }

        assertEquals(Limits.SCROLLBACK_LINES, s.size)
        assertEquals("línea ${Limits.SCROLLBACK_LINES + 9}", s.lines.first().text)
        assertEquals("línea 10", s.lines.last().text)
    }

    @Test
    fun `restore coloca lo cronologico invertido`() {
        // Del fichero llegan de la más antigua a la más reciente; en memoria se guardan al revés.
        val s = Scrollback()
        s.restore(
            listOf(
                "la más antigua" to Role.OUTPUT,
                "la de en medio" to Role.ECHO,
                "la más reciente" to Role.ERROR,
            ),
        )

        assertEquals(
            listOf("la más reciente", "la de en medio", "la más antigua"),
            textos(s),
        )
        assertEquals(Role.ERROR, s.lines.first().role)
    }

    @Test
    fun `restore recorta igual que add`() {
        val s = Scrollback(max = 2)
        s.restore(listOf("a" to Role.OUTPUT, "b" to Role.OUTPUT, "c" to Role.OUTPUT))

        assertEquals(2, s.size)
        assertEquals(listOf("c", "b"), textos(s))
    }

    @Test
    fun `chronological devuelve el orden del fichero`() {
        val s = Scrollback()
        s.add("una", Role.OUTPUT)
        s.add("dos", Role.OUTPUT)
        s.add("tres", Role.OUTPUT)

        assertEquals(listOf("una", "dos", "tres"), s.chronological().map { it.text })
        // Y no altera lo que hay en memoria.
        assertEquals(listOf("tres", "dos", "una"), textos(s))
    }

    @Test
    fun `restore y chronological son inversas`() {
        val original = listOf(
            "> apps" to Role.ECHO,
            "spotify" to Role.OUTPUT,
            "1 app" to Role.STATUS,
        )
        val s = Scrollback()
        s.restore(original)

        assertEquals(original, s.chronological().map { it.text to it.role })
    }

    @Test
    fun `clear vacia la memoria`() {
        val s = Scrollback()
        s.add("a", Role.OUTPUT)
        s.add("b", Role.OUTPUT)

        s.clear()

        assertEquals(0, s.size)
        assertTrue(s.lines.isEmpty())
        assertTrue(s.chronological().isEmpty())
    }

    @Test
    fun `despues de clear se puede seguir escribiendo`() {
        val s = Scrollback()
        s.add("vieja", Role.OUTPUT)
        s.clear()
        s.add("nueva", Role.OUTPUT)

        assertEquals(listOf("nueva"), textos(s))
    }

    @Test
    fun `un scrollback recien creado esta vacio`() {
        val s = Scrollback()
        assertEquals(0, s.size)
        assertTrue(s.lines.isEmpty())
    }
}
