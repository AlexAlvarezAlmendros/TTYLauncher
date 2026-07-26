package dev.tty.core.output

import dev.tty.core.Limits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El contrato de salida y el recorte de la §5.8.
 *
 * La regla que se protege: **un recorte silencioso es una mentira**. Si se recorta, se dice cuánto,
 * porque si no el usuario creería que eso era todo lo que había.
 */
class OutputTest {

    private fun deLineas(n: Int): Output =
        Output((0 until n).map { "line $it" to Role.OUTPUT })

    @Test
    fun `por debajo del limite no toca nada`() {
        val o = deLineas(3)
        assertEquals(o.lines, o.truncated().lines)
    }

    @Test
    fun `justo en el limite no toca nada`() {
        val o = deLineas(Limits.COMMAND_OUTPUT_LINES)
        val t = o.truncated()

        assertEquals(Limits.COMMAND_OUTPUT_LINES, t.lines.size)
        assertEquals(o.lines, t.lines)
    }

    @Test
    fun `una salida vacia se queda vacia`() {
        assertTrue(Output.silent.truncated().lines.isEmpty())
    }

    @Test
    fun `por encima del limite recorta y lo dice`() {
        val sobran = 100
        val t = deLineas(Limits.COMMAND_OUTPUT_LINES + sobran).truncated()

        // El límite de líneas, más la línea que cuenta lo que se ha quitado.
        assertEquals(Limits.COMMAND_OUTPUT_LINES + 1, t.lines.size)
        assertEquals("… $sobran more lines", t.lines.last().first)
        assertEquals(Role.STATUS, t.lines.last().second)
    }

    @Test
    fun `lo que se conserva es el principio, en orden`() {
        val t = deLineas(Limits.COMMAND_OUTPUT_LINES + 7).truncated()

        assertEquals("line 0", t.lines.first().first)
        assertEquals("line ${Limits.COMMAND_OUTPUT_LINES - 1}", t.lines[Limits.COMMAND_OUTPUT_LINES - 1].first)
        assertEquals("… 7 more lines", t.lines[Limits.COMMAND_OUTPUT_LINES].first)
    }

    @Test
    fun `una sola linea de mas ya se anuncia`() {
        val t = deLineas(Limits.COMMAND_OUTPUT_LINES + 1).truncated()
        assertEquals("… 1 more lines", t.lines.last().first)
    }

    @Test
    fun `recortar dos veces no acumula avisos`() {
        // La línea de estado deja la salida justo uno por encima del límite, así que una segunda
        // pasada vuelve a recortar. Lo que se protege es que quede UNA sola línea de aviso: dos
        // avisos seguidos serían ruido, y contarían mal.
        val una = deLineas(Limits.COMMAND_OUTPUT_LINES + 5).truncated()
        val dos = una.truncated()

        assertEquals(Limits.COMMAND_OUTPUT_LINES + 1, dos.lines.size)
        assertEquals("… 1 more lines", dos.lines.last().first)
        assertEquals(1, dos.lines.count { it.second == Role.STATUS })
    }

    // ------------------------------------------------- constructores de conveniencia

    @Test
    fun `silent es la lista vacia, que es el caso normal`() {
        assertTrue(Output.silent.lines.isEmpty())
    }

    @Test
    fun `of, output, error y status ponen el rol correcto`() {
        assertEquals(
            listOf("a" to Role.OUTPUT, "b" to Role.OUTPUT),
            Output.of("a", "b").lines,
        )
        assertEquals(listOf("a" to Role.OUTPUT), Output.output(listOf("a")).lines)
        assertEquals(listOf("boom" to Role.ERROR), Output.error("boom").lines)
        assertEquals(listOf("cleared" to Role.STATUS), Output.status("cleared").lines)
    }

    // ------------------------------------------------- roles y prefijos (§10)

    @Test
    fun `cada rol tiene su prefijo`() {
        assertEquals("", Role.OUTPUT.prefix)
        assertEquals("", Role.STATUS.prefix)
        assertEquals("> ", Role.ECHO.prefix)
        assertEquals("! ", Role.ERROR.prefix)
        assertEquals("… ", Role.RECORDING.prefix)
    }

    @Test
    fun `render antepone el prefijo del rol`() {
        assertEquals("> open spotify", Line(0, "open spotify", Role.ECHO).render())
        assertEquals("! open: 'wh' is ambiguous", Line(1, "open: 'wh' is ambiguous", Role.ERROR).render())
        assertEquals("spotify", Line(2, "spotify", Role.OUTPUT).render())
    }

    @Test
    fun `solo status admite decode`() {
        // Que apps, ls, sh y tmux no se descodifiquen es una regla del motor, no criterio de quien
        // escribe cada comando (§4.5): esos comandos emiten OUTPUT.
        assertTrue(Role.STATUS.allowsDecode)
        assertFalse(Role.OUTPUT.allowsDecode)
        assertFalse(Role.ECHO.allowsDecode)
        assertFalse(Role.ERROR.allowsDecode)
        assertFalse(Role.RECORDING.allowsDecode)
    }

    @Test
    fun `los identificadores de linea son monotonos`() {
        val ids = LineIds()
        assertEquals(0L, ids.next())
        assertEquals(1L, ids.next())
        assertEquals(2L, ids.next())
    }

    @Test
    fun `LineIds puede arrancar donde se le diga`() {
        // Al restaurar del disco los ids siguen desde donde estaban, no desde cero.
        val ids = LineIds(start = 40)
        assertEquals(40L, ids.next())
        assertEquals(41L, ids.next())
    }
}
