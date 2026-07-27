package dev.tty.core.scrollback

import dev.tty.core.Limits
import dev.tty.core.output.Line
import dev.tty.core.output.LineGlyph
import dev.tty.core.output.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * El formato del scrollback en disco.
 *
 * Es la parte del producto donde un fallo **no se ve**: el usuario no se entera de que ha perdido
 * historial, solo de que un día había menos. Por eso se testea aquí y no mirando el dispositivo: un
 * off-by-one al descartar la cola truncada se come una línea buena en cada arranque, y con arranques
 * suficientes se come el historial entero sin una sola excepción de por medio.
 */
class ScrollbackFormatTest {

    private fun linea(text: String, role: Role = Role.OUTPUT, glyph: LineGlyph? = null) =
        Line(id = 0, text = text, role = role, glyph = glyph)

    /** Ida y vuelta completa: lo que se escribe es lo que se vuelve a leer. */
    private fun idaYVuelta(text: String, role: Role) {
        assertEquals(text to role, ScrollbackFormat.decode(ScrollbackFormat.encode(linea(text, role))))
    }

    // --- Ida y vuelta ----------------------------------------------------------------------------

    @Test
    fun `encode y decode devuelven el mismo texto y el mismo rol`() {
        for (role in Role.entries) {
            idaYVuelta("hola", role)
        }
    }

    @Test
    fun `el texto con espacios sobrevive entero`() {
        // Solo el PRIMER espacio separa el encabezado del cuerpo. Si se partiera por todos, el texto
        // volvería con las palabras pegadas o recortado por la segunda.
        idaYVuelta("open spotify y luego kill", Role.ECHO)
    }

    @Test
    fun `los espacios del principio y del final no se pierden`() {
        // La salida en columnas de `apps` y `ls` alinea con espacios: recortarlos al releer
        // desalinea el historial restaurado respecto a lo que el usuario vio.
        idaYVuelta("  sangrado a la izquierda   ", Role.OUTPUT)
    }

    @Test
    fun `el texto con dos puntos no se confunde con un glifo`() {
        // El ':' del encabezado separa rol y glifo. Un ':' en el CUERPO no es un separador, y
        // tratarlo como tal cortaría el mensaje justo donde hay información útil.
        idaYVuelta("'wh' is ambiguous: whatsapp, whatsapp-bsns", Role.ERROR)
    }

    @Test
    fun `el texto vacio vuelve vacio y con su rol`() {
        // Las líneas en blanco separan bloques en la salida de `help` e `info`. Si se perdieran, el
        // historial restaurado saldría apelmazado y distinto del que se generó.
        idaYVuelta("", Role.OUTPUT)
        idaYVuelta("", Role.STATUS)
    }

    @Test
    fun `un salto de linea dentro del texto se aplana al codificar`() {
        // Un registro es UNA línea del fichero. Un '\n' colado en el texto partiría el registro en
        // dos y todo lo que viniera detrás se leería corrido: el resto del fichero quedaría
        // desplazado un renglón para siempre.
        val encoded = ScrollbackFormat.encode(linea("primera\nsegunda", Role.OUTPUT))

        assertEquals("OUTPUT primera segunda", encoded)
        assertEquals(1, encoded.lines().size)
        assertEquals("primera segunda" to Role.OUTPUT, ScrollbackFormat.decode(encoded))
    }

    @Test
    fun `el retorno de carro tambien se aplana`() {
        // La salida de Termux llega con finales de línea de todos los sabores. Un '\r' suelto lo
        // vuelve a partir un BufferedReader al releer, igual que un '\n'.
        val encoded = ScrollbackFormat.encode(linea("uno\r\ndos", Role.OUTPUT))

        assertEquals("OUTPUT uno  dos", encoded)
        assertEquals(1, encoded.lines().size)
    }

    // --- Tolerancia ------------------------------------------------------------------------------

    @Test
    fun `un rol desconocido se lee como OUTPUT con el texto entero`() {
        // Un rol de una versión futura (o un byte corrompido) no puede costarle al usuario la línea.
        // Enseñarla sin su color es un defecto cosmético; tirarla es perder lo que hizo con su
        // teléfono. Y el texto que vuelve incluye la etiqueta: no se sabe qué era, así que no se
        // adivina dónde empieza el cuerpo.
        assertEquals(
            "DEBUG algo de una versión futura" to Role.OUTPUT,
            ScrollbackFormat.decode("DEBUG algo de una versión futura"),
        )
    }

    @Test
    fun `un rol conocido en minusculas no cuela y se conserva la linea`() {
        // Los roles se serializan siempre en mayúsculas. Uno en minúsculas es basura, no un rol, y
        // el camino tolerante tiene que preservar la línea igualmente.
        assertEquals("output hola" to Role.OUTPUT, ScrollbackFormat.decode("output hola"))
    }

    @Test
    fun `una linea que es solo un rol conocido es una linea vacia legitima`() {
        // Sin espacio no hay cuerpo, pero eso NO la convierte en basura: es exactamente lo que
        // produce encode de un texto vacío una vez el fichero pierde el espacio final, y las líneas
        // en blanco separan bloques en la salida de los comandos.
        assertEquals("" to Role.STATUS, ScrollbackFormat.decode("STATUS"))
        assertEquals("" to Role.ERROR, ScrollbackFormat.decode("ERROR"))
    }

    @Test
    fun `una linea sin espacio que no es un rol se conserva entera`() {
        assertEquals("basura" to Role.OUTPUT, ScrollbackFormat.decode("basura"))
    }

    @Test
    fun `la linea vacia del todo no es un registro`() {
        // Cadena vacía es ausencia de registro, no un registro vacío: el fichero puede tener un
        // renglón de más al final y no debe materializarse como una línea del historial.
        assertNull(ScrollbackFormat.decode(""))
    }

    // --- Cola truncada ---------------------------------------------------------------------------

    @Test
    fun `sin salto final se descarta la ultima linea y se conserva la anterior`() {
        // Este es EL caso: un append interrumpido por la muerte del proceso deja la última línea a
        // medias. Se tira solo esa. Un off-by-one aquí se lleva también la anterior, que estaba
        // completa, y lo hace en cada arranque hasta vaciar el historial.
        val leidas = ScrollbackFormat.parse(
            listOf("OUTPUT primera", "OUTPUT segunda", "OUTPUT terc"),
            endsWithNewline = false,
        )

        assertEquals(
            listOf("primera" to Role.OUTPUT, "segunda" to Role.OUTPUT),
            leidas,
        )
    }

    @Test
    fun `sin salto final y con una sola linea no queda nada`() {
        assertEquals(emptyList<Pair<String, Role>>(), ScrollbackFormat.parse(listOf("OUTPUT a m"), endsWithNewline = false))
    }

    @Test
    fun `sin salto final y sin lineas no revienta`() {
        // El arranque lee este fichero antes de pintar nada: una excepción aquí deja la actividad
        // HOME muerta, que es un móvil inutilizable.
        assertEquals(emptyList<Pair<String, Role>>(), ScrollbackFormat.parse(emptyList(), endsWithNewline = false))
    }

    @Test
    fun `con salto final no se descarta nada`() {
        // El fichero está cerrado en limpio: la última línea es tan buena como las demás y tirarla
        // sería perder la línea más reciente en cada arranque normal.
        val leidas = ScrollbackFormat.parse(
            listOf("ECHO apps", "OUTPUT spotify", "STATUS 1 app"),
            endsWithNewline = true,
        )

        assertEquals(
            listOf(
                "apps" to Role.ECHO,
                "spotify" to Role.OUTPUT,
                "1 app" to Role.STATUS,
            ),
            leidas,
        )
    }

    // --- Recorte ---------------------------------------------------------------------------------

    @Test
    fun `parse conserva las ultimas lineas y no las primeras`() {
        // El recorte descarta lo ANTIGUO. Al revés, el arranque enseñaría el historial del primer día
        // de uso y nunca lo de ayer, que es lo único que el usuario busca.
        val crudas = (0 until Limits.SCROLLBACK_LINES + 10).map { "OUTPUT línea $it" }

        val leidas = ScrollbackFormat.parse(crudas, endsWithNewline = true)

        assertEquals(Limits.SCROLLBACK_LINES, leidas.size)
        assertEquals("línea 10" to Role.OUTPUT, leidas.first())
        assertEquals("línea ${Limits.SCROLLBACK_LINES + 9}" to Role.OUTPUT, leidas.last())
    }

    @Test
    fun `el recorte no se dispara justo en el limite`() {
        val crudas = (0 until Limits.SCROLLBACK_LINES).map { "OUTPUT línea $it" }

        val leidas = ScrollbackFormat.parse(crudas, endsWithNewline = true)

        assertEquals(Limits.SCROLLBACK_LINES, leidas.size)
        assertEquals("línea 0" to Role.OUTPUT, leidas.first())
    }

    // --- Glifo -----------------------------------------------------------------------------------

    @Test
    fun `el glifo va y vuelve`() {
        // El glifo congelado del eco es lo que permite recorrer el scrollback y ver de un vistazo qué
        // falló. Si no sobrevive al reinicio, el historial restaurado miente: todo parecería haber
        // ido bien.
        val encoded = ScrollbackFormat.encode(linea("rm /sdcard", Role.ECHO, LineGlyph.FAIL))

        assertEquals("ECHO:FAIL rm /sdcard", encoded)
        assertEquals(LineGlyph.FAIL, ScrollbackFormat.decodeGlyph(encoded))
    }

    @Test
    fun `una linea con glifo se decodifica con su rol y su texto`() {
        // El ':' del encabezado no puede colarse en el cuerpo: el texto restaurado saldría con un
        // 'FAIL ' pegado delante y el usuario vería un mensaje que nunca se emitió.
        val encoded = ScrollbackFormat.encode(linea("open spotify", Role.ECHO, LineGlyph.OK))

        assertEquals("open spotify" to Role.ECHO, ScrollbackFormat.decode(encoded))
        assertEquals(LineGlyph.OK, ScrollbackFormat.decodeGlyph(encoded))
    }

    @Test
    fun `una linea sin glifo devuelve null`() {
        val encoded = ScrollbackFormat.encode(linea("spotify", Role.OUTPUT))

        assertEquals("OUTPUT spotify", encoded)
        assertNull(ScrollbackFormat.decodeGlyph(encoded))
    }

    @Test
    fun `un glifo desconocido no rompe la linea`() {
        // Mismo criterio que con el rol: de una versión futura puede venir un glifo que aquí no
        // existe, y la línea tiene que seguir leyéndose con su texto y su rol intactos.
        assertNull(ScrollbackFormat.decodeGlyph("ECHO:WARN open spotify"))
        assertEquals("open spotify" to Role.ECHO, ScrollbackFormat.decode("ECHO:WARN open spotify"))
    }

    @Test
    fun `un dos puntos en el cuerpo no se lee como glifo`() {
        // decodeGlyph mira solo hasta el primer espacio. Si mirara la línea entera, cualquier ':' del
        // mensaje se interpretaría como encabezado.
        assertNull(ScrollbackFormat.decodeGlyph("OUTPUT total: 42"))
    }
}
