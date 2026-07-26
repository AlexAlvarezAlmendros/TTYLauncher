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

        // El aviso ocupa una de las líneas del cupo: el total es EXACTAMENTE el límite, nunca uno
        // más. Un límite que se pasa por uno no es un límite.
        assertEquals(Limits.COMMAND_OUTPUT_LINES, t.lines.size)
        assertEquals("${sobran + 1} more lines not shown", t.lines.last().first)
        assertEquals(Role.STATUS, t.lines.last().second)
    }

    @Test
    fun `lo que se conserva es el principio, en orden`() {
        val t = deLineas(Limits.COMMAND_OUTPUT_LINES + 7).truncated()

        assertEquals("line 0", t.lines.first().first)
        assertEquals("line ${Limits.COMMAND_OUTPUT_LINES - 2}", t.lines[Limits.COMMAND_OUTPUT_LINES - 2].first)
        assertEquals("8 more lines not shown", t.lines[Limits.COMMAND_OUTPUT_LINES - 1].first)
    }

    @Test
    fun `una sola linea de mas ya se anuncia, en singular`() {
        // Sobra una respecto al cupo, pero el aviso también ocupa sitio: se van dos.
        val t = deLineas(Limits.COMMAND_OUTPUT_LINES + 1).truncated()
        assertEquals("2 more lines not shown", t.lines.last().first)

        // Y el singular se escribe en singular: "1 more lines" es de robot.
        val justa = deLineas(Limits.COMMAND_OUTPUT_LINES).truncated()
        assertEquals(Limits.COMMAND_OUTPUT_LINES, justa.lines.size)
    }

    @Test
    fun `recortar es idempotente`() {
        // Como el aviso entra dentro del cupo, el resultado mide justo el límite y una segunda
        // pasada no toca nada: ni recorta otra vez, ni falsea la cuenta con un "1 more lines not shown"
        // que se comería la cifra real.
        val una = deLineas(Limits.COMMAND_OUTPUT_LINES + 5).truncated()
        val dos = una.truncated()

        assertEquals(una.lines, dos.lines)
        assertEquals("6 more lines not shown", dos.lines.last().first)
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
        // ECHO y ERROR ya no llevan prefijo de carácter: su celda la ocupa un glifo congelado
        // (§4.4). El `…` de grabación sí se mantiene, porque REC ya informa desde el prompt.
        assertEquals("", Role.ECHO.prefix)
        assertEquals("", Role.ERROR.prefix)
        assertEquals("… ", Role.RECORDING.prefix)
    }

    @Test
    fun `render antepone el prefijo del rol, y ECHO y ERROR ya no llevan`() {
        // La celda del `>` y la del `!` la ocupa ahora un glifo congelado (§4.4), así que el texto
        // sale limpio: el prefijo lo pone la UI dibujando, no la cadena.
        assertEquals("open spotify", Line(0, "open spotify", Role.ECHO).render())
        assertEquals("open: 'wh' is ambiguous", Line(1, "open: 'wh' is ambiguous", Role.ERROR).render())
        assertEquals("spotify", Line(2, "spotify", Role.OUTPUT).render())
        // El de grabación sí sigue siendo un carácter.
        assertEquals("… kill tiktok", Line(3, "kill tiktok", Role.RECORDING).render())
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
