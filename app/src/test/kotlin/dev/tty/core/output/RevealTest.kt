package dev.tty.core.output

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Qué se anima y cómo.
 *
 * Esto vive en `core/` y no en la UI por una razón concreta: si la decisión estuviera en el
 * composable, cualquiera podría decidir un día que la salida de `apps` quedaría bonita
 * descifrándose. Aquí es una **regla del motor**, y estos tests la fijan.
 */
class RevealTest {

    private fun line(text: String, role: Role) = Line(1, text, role)

    @Test
    fun `la salida de comandos se asienta, nunca se descifra`() {
        // Es la regla que impide que `apps`, `ls`, `sh` y `tmux` se descodifiquen: todos emiten
        // OUTPUT, y OUTPUT nunca lleva decode (§4.5).
        assertEquals(Reveal.SETTLE, RevealPolicy.forLine(line("whatsapp com.whatsapp", Role.OUTPUT)))
        assertEquals(Reveal.SETTLE, RevealPolicy.forLine(line("corta", Role.OUTPUT)))
    }

    @Test
    fun `una linea corta de estado si se descifra`() {
        assertEquals(Reveal.DECODE, RevealPolicy.forLine(line("scrollback cleared", Role.STATUS)))
    }

    @Test
    fun `una linea de estado larga se asienta`() {
        // «Solo para líneas cortas» es un límite duro, no una recomendación: 48 caracteres.
        val larga = "x".repeat(RevealPolicy.DECODE_MAX_CHARS + 1)
        assertEquals(Reveal.SETTLE, RevealPolicy.forLine(line(larga, Role.STATUS)))

        val justa = "x".repeat(RevealPolicy.DECODE_MAX_CHARS)
        assertEquals(Reveal.DECODE, RevealPolicy.forLine(line(justa, Role.STATUS)))
    }

    @Test
    fun `los errores se asientan, no se descifran`() {
        // Un error que tarda medio segundo en poder leerse es un error peor.
        assertEquals(Reveal.SETTLE, RevealPolicy.forLine(line("rm: not found", Role.ERROR)))
    }

    @Test
    fun `el eco y las lineas grabadas se asientan`() {
        assertEquals(Reveal.SETTLE, RevealPolicy.forLine(line("apps whats", Role.ECHO)))
        assertEquals(Reveal.SETTLE, RevealPolicy.forLine(line("kill tiktok", Role.RECORDING)))
    }

    @Test
    fun `lo que venia del disco no se anima`() {
        // El historial persistido se muestra ya presente (§5.2). Animarlo al arrancar sería una
        // cascada de 2000 líneas cada vez que desbloqueas el móvil.
        assertEquals(Reveal.NONE, RevealPolicy.forLine(line("lo de ayer", Role.OUTPUT), restored = true))
        assertEquals(Reveal.NONE, RevealPolicy.forLine(line("estado", Role.STATUS), restored = true))
    }

    @Test
    fun `solo el rol de estado admite decode`() {
        // La propiedad vive junto al rol para que la regla no se disperse por la UI.
        assertEquals(true, Role.STATUS.allowsDecode)
        listOf(Role.OUTPUT, Role.ECHO, Role.ERROR, Role.RECORDING).forEach {
            assertEquals(false, it.allowsDecode)
        }
    }
}
