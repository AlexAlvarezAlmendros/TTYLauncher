package dev.tty.core.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La resolución por rangos (functional.md §7.1) y la ambigüedad (§7.2).
 *
 * Es la pieza que más determina si el producto se siente bien, y la única cuyo fallo hace que el
 * sistema **adivine**. Lo que aquí se protege no es que resuelva bien los casos fáciles, sino que
 * se niegue a resolver los dudosos.
 */
class AppResolverTest {

    private fun app(label: String, packageName: String, system: Boolean = false) = AppEntry(
        label = label,
        packageName = packageName,
        component = "$packageName/.MainActivity",
        isSystem = system,
    )

    // Catálogo de referencia. En orden desordenado a propósito: la resolución no puede depender
    // del orden en que la plataforma devuelva las apps.
    private val spotify = app("Spotify", "com.spotify.music")               // spotify
    private val nova = app("Nova Launcher", "com.teslacoilsw.launcher")     // nova-launcher
    private val whatsapp = app("WhatsApp", "com.whatsapp")                  // whatsapp
    private val whatsappBsns = app("WhatsApp Bsns", "com.whatsapp.w4b")     // whatsapp-bsns
    private val signal = app("Signal", "org.thoughtcrime.securesms")        // signal

    private val catalog = listOf(whatsappBsns, spotify, nova, signal, whatsapp)

    private fun found(r: Resolution): AppEntry =
        (r as? Resolution.Found)?.app ?: throw AssertionError("esperaba Found, salió $r")

    private fun ambiguous(r: Resolution): Resolution.Ambiguous =
        r as? Resolution.Ambiguous ?: throw AssertionError("esperaba Ambiguous, salió $r")

    // ------------------------------------------------- los cuatro rangos, uno a uno

    @Test
    fun `rango 1 - coincidencia exacta de paquete`() {
        val r = AppResolver.resolve("com.spotify.music", catalog)
        assertEquals(spotify, found(r))
    }

    @Test
    fun `rango 2 - coincidencia exacta de handle`() {
        val r = AppResolver.resolve("nova-launcher", catalog)
        assertEquals(nova, found(r))
    }

    @Test
    fun `rango 3 - prefijo de handle`() {
        val r = AppResolver.resolve("nov", catalog)
        assertEquals(nova, found(r))
    }

    @Test
    fun `rango 3 - prefijo de etiqueta, que el handle no cubre`() {
        // "nova l" no es prefijo de "nova-launcher" (el espacio no es el guión): solo casa contra
        // la etiqueta. Es la mitad del rango 3 que se olvidaría sin un test propio.
        val r = AppResolver.resolve("nova l", catalog)
        assertEquals(nova, found(r))
    }

    @Test
    fun `rango 4 - subcadena`() {
        val r = AppResolver.resolve("launch", catalog)
        assertEquals(nova, found(r))
    }

    @Test
    fun `rango 4 - subcadena que solo aparece en el paquete`() {
        val r = AppResolver.resolve("teslacoilsw", catalog)
        assertEquals(nova, found(r))
    }

    // ------------------------------------------------- prioridad entre rangos

    @Test
    fun `el rango 1 gana al rango 2`() {
        // Un paquete de un solo segmento es artificial, pero es la ÚNICA forma de que los rangos 1
        // y 2 colisionen: un handle nunca contiene puntos, así que jamás puede ser igual a un
        // nombre de paquete real.
        val porPaquete = app("Termux Widget", "termux")   // handle: termux-widget
        val porHandle = app("Termux", "com.termux")       // handle: termux
        val apps = listOf(porHandle, porPaquete)

        val r = AppResolver.resolve("termux", apps)

        assertEquals(porPaquete, found(r))
        assertEquals("termux", found(r).packageName)
    }

    @Test
    fun `el rango 2 gana al rango 3`() {
        // "whatsapp" es el handle exacto de una app y prefijo de dos. Gana el rango 2 y ni se mira
        // el 3: si se mirara, esto sería ambiguo y abrir WhatsApp sería imposible.
        val r = AppResolver.resolve("whatsapp", catalog)
        assertEquals(whatsapp, found(r))
    }

    @Test
    fun `el rango 3 gana al rango 4`() {
        val molly = app("Molly Signal", "im.molly.app")   // handle: molly-signal
        val apps = listOf(signal, molly)

        // Por prefijo solo casa una: se devuelve esa.
        assertEquals(signal, found(AppResolver.resolve("sign", apps)))

        // Y no es que la otra no estuviera: por subcadena casan las dos. El rango 4 existe, pero
        // no se llega a él porque el 3 ya produjo resultado.
        assertEquals(2, ambiguous(AppResolver.resolve("gnal", apps)).candidates.size)
    }

    // ------------------------------------------------- ambigüedad

    @Test
    fun `dos candidatos en el rango que acierta son un error, nunca una eleccion`() {
        val r = AppResolver.resolve("wh", catalog)
        val amb = ambiguous(r)
        assertEquals(listOf("whatsapp", "whatsapp-bsns"), amb.candidates.map { it.handle })
    }

    @Test
    fun `dos apps con el mismo handle exacto son ambiguas`() {
        // El mismo programa instalado desde dos orígenes: misma etiqueta, mismo handle.
        val store = app("Signal", "org.thoughtcrime.securesms")
        val fdroid = app("Signal", "org.thoughtcrime.securesms.fdroid")

        val amb = ambiguous(AppResolver.resolve("signal", listOf(store, fdroid)))

        assertEquals(2, amb.candidates.size)
        assertEquals(
            setOf("org.thoughtcrime.securesms", "org.thoughtcrime.securesms.fdroid"),
            amb.candidates.map { it.packageName }.toSet(),
        )
    }

    @Test
    fun `un rango posterior no rescata a uno ambiguo`() {
        // LA propiedad del §7.1. Se construye sobre el rango 1 porque los rangos 2, 3 y 4 están
        // anidados por construcción (handle exacto ⊆ prefijo ⊆ subcadena): un rango posterior
        // nunca puede tener MENOS candidatos que el anterior, así que el par 1→2 es el único
        // donde un rescate sería siquiera posible. Y no ocurre.
        val dosConElMismoPaquete = listOf(
            app("Termux Widget", "termux"),   // handle: termux-widget
            app("Termux Float", "termux"),    // handle: termux-float
        )
        val elQueCasaExacto = app("Termux", "com.termux")   // handle: termux
        val apps = dosConElMismoPaquete + elQueCasaExacto

        val amb = ambiguous(AppResolver.resolve("termux", apps))

        // El rango 2 habría devuelto exactamente uno. Da igual: no se llega a mirar.
        assertEquals(listOf("termux-float", "termux-widget"), amb.candidates.map { it.handle })
        assertTrue(
            "el rango 2 no puede colarse entre los candidatos del rango 1",
            elQueCasaExacto !in amb.candidates,
        )
    }

    @Test
    fun `los candidatos salen ordenados por handle, no por el orden del catalogo`() {
        val z = app("Zeta App", "com.z.app")     // zeta-app
        val a = app("Zeta Alfa", "com.a.app")    // zeta-alfa
        val amb = ambiguous(AppResolver.resolve("zeta", listOf(z, a)))
        assertEquals(listOf("zeta-alfa", "zeta-app"), amb.candidates.map { it.handle })
    }

    // ------------------------------------------------- nada que resolver

    @Test
    fun `lo que no casa con nada es not found`() {
        val r = AppResolver.resolve("firefox", catalog)
        assertTrue("esperaba NotFound, salió $r", r is Resolution.NotFound)
    }

    @Test
    fun `una consulta vacia es not found, no un error de programa`() {
        assertTrue(AppResolver.resolve("", catalog) is Resolution.NotFound)
        assertTrue(AppResolver.resolve("   ", catalog) is Resolution.NotFound)
    }

    @Test
    fun `un catalogo vacio es not found`() {
        assertTrue(AppResolver.resolve("spotify", emptyList()) is Resolution.NotFound)
    }

    @Test
    fun `la consulta se recorta y se pasa a minusculas`() {
        assertEquals(spotify, found(AppResolver.resolve("  SPOTIFY  ", catalog)))
        assertEquals(spotify, found(AppResolver.resolve("COM.SPOTIFY.MUSIC", catalog)))
    }

    // ------------------------------------------------- el mensaje de error (§7.2, §10)

    @Test
    fun `el mensaje de ambiguedad enumera los candidatos, con el formato del funcional`() {
        // functional.md §7.2, literalmente:
        //     ! rm: 'wh' is ambiguous — whatsapp, whatsapp-bsns
        val r = AppResolver.resolve("wh", catalog)
        assertEquals(
            "rm: 'wh' is ambiguous — whatsapp, whatsapp-bsns",
            AppResolver.errorFor("rm", r),
        )
    }

    @Test
    fun `el mensaje lleva delante el verbo con el que se escribio`() {
        // El comando no cambia de nombre entre sintaxis, ayuda y error (§10).
        val r = AppResolver.resolve("wh", catalog)
        assertTrue(AppResolver.errorFor("open", r).startsWith("open: "))
        assertTrue(AppResolver.errorFor("kill", r).startsWith("kill: "))
    }

    @Test
    fun `el mensaje de no encontrado dice que se buscaba`() {
        val r = AppResolver.resolve("firefox", catalog)
        assertEquals("open: 'firefox' not found", AppResolver.errorFor("open", r))
    }

    @Test
    fun `el mensaje de error repite la consulta tal y como se escribio`() {
        // Sin recortar ni pasar a minúsculas: el usuario tiene que reconocer lo que tecleó.
        val r = AppResolver.resolve("FireFox", catalog)
        assertEquals("open: 'FireFox' not found", AppResolver.errorFor("open", r))
    }
}
