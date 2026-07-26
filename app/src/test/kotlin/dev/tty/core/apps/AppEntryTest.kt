package dev.tty.core.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La generación de handles (functional.md §7).
 *
 * El handle es lo que el usuario escribe: si cambia, cambia el vocabulario del producto entero.
 * Por eso están fijados aquí también los detalles que la especificación no dice —colapso de
 * guiones, extremos limpios, acentos— y no solo el ejemplo del documento.
 */
class AppEntryTest {

    private fun entry(label: String, packageName: String) = AppEntry(
        label = label,
        packageName = packageName,
        component = "$packageName/.MainActivity",
    )

    @Test
    fun `el ejemplo de la especificacion`() {
        assertEquals("nova-launcher", AppEntry.handleOf("Nova Launcher"))
    }

    @Test
    fun `todo a minusculas`() {
        assertEquals("imdb", AppEntry.handleOf("IMDb"))
        assertEquals("spotify", AppEntry.handleOf("SPOTIFY"))
    }

    @Test
    fun `los no alfanumericos se convierten en guiones`() {
        assertEquals("f-droid", AppEntry.handleOf("F-Droid"))
        assertEquals("adobe-acrobat", AppEntry.handleOf("Adobe.Acrobat"))
    }

    @Test
    fun `los guiones consecutivos se colapsan en uno`() {
        // "WhatsApp  (beta)" no debe dar whatsapp---beta
        assertEquals("whatsapp-beta", AppEntry.handleOf("WhatsApp  (beta)"))
        assertEquals("nova-launcher", AppEntry.handleOf("Nova   Launcher"))
    }

    @Test
    fun `no quedan guiones en los extremos`() {
        assertEquals("spotify", AppEntry.handleOf("  Spotify  "))
        assertEquals("spotify", AppEntry.handleOf("(Spotify)"))
        assertEquals("beta", AppEntry.handleOf("-beta-"))
    }

    @Test
    fun `los digitos se conservan`() {
        assertEquals("f1-tv", AppEntry.handleOf("F1 TV"))
    }

    @Test
    fun `las letras acentuadas se conservan`() {
        // Convertirlas en guiones haría ilegible media pantalla de inicio en español.
        assertEquals("cámara", AppEntry.handleOf("Cámara"))
        assertEquals("diseño", AppEntry.handleOf("Diseño"))
        assertEquals("ajustes-del-móvil", AppEntry.handleOf("Ajustes del Móvil"))
    }

    @Test
    fun `una etiqueta sin alfanumericos da un handle vacio`() {
        // handleOf no decide qué hacer con eso: lo decide AppEntry.handle.
        assertEquals("", AppEntry.handleOf("###"))
        assertEquals("", AppEntry.handleOf(""))
        assertEquals("", AppEntry.handleOf("   "))
    }

    @Test
    fun `el handle de una app normal sale de la etiqueta`() {
        assertEquals("nova-launcher", entry("Nova Launcher", "com.teslacoilsw.launcher").handle)
    }

    @Test
    fun `una etiqueta sin alfanumericos cae al paquete`() {
        // Si no, la app se quedaría con un handle vacío que no se puede escribir: inalcanzable.
        val app = entry("###", "com.example.glyph")
        assertEquals("com-example-glyph", app.handle)
    }

    @Test
    fun `el handle nunca esta vacio si el paquete tiene algo escribible`() {
        val app = entry("", "com.example.app")
        assertTrue(app.handle.isNotEmpty())
        assertEquals("com-example-app", app.handle)
    }

    @Test
    fun `dos apps distintas con la misma etiqueta comparten handle`() {
        // No es un bug: es exactamente lo que hace ambigua la resolución, y se resuelve allí.
        val a = entry("Signal", "org.thoughtcrime.securesms")
        val b = entry("Signal", "org.thoughtcrime.securesms.fdroid")
        assertEquals(a.handle, b.handle)
    }
}
