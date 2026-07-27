package dev.tty.core.text

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * La alineación en celdas de carácter y el sangrado colgante.
 *
 * El sangrado existe porque en una pantalla de móvil una fila de dos columnas se parte, y la
 * continuación volvía al margen izquierdo: la descripción de un comando acababa pegada debajo de la
 * sintaxis del siguiente y `help` se leía fatal. Es **solo de presentación**: el texto que se copia
 * y el que se persiste no cambian.
 */
class ColumnsTest {

    @Test
    fun `dos columnas se alinean rellenando con espacios`() {
        val filas = listOf("whatsapp" to "com.whatsapp", "whatsapp-bsns" to "com.whatsapp.w4b")
        val salida = Columns.twoColumns(filas)

        // El ancho lo marca el más largo, así que las segundas columnas empiezan en la misma celda.
        val inicio = salida.map { it.indexOf("com.") }
        assertEquals(inicio.first(), inicio.last())
    }

    @Test
    fun `la salida sigue siendo texto plano copiable`() {
        // Se alinea con espacios y no con un grid de layout: es lo que permite copiar la salida y
        // que siga cuadrando en cualquier sitio.
        val salida = Columns.twoColumns(listOf("a" to "b"))
        assertEquals("a    b", salida.single())
    }

    @Test
    fun `una fila desproporcionada desborda en vez de truncarse`() {
        // Perder el nombre de un paquete es peor que perder la alineación de una fila.
        val largo = "x".repeat(40)
        val salida = Columns.twoColumns(listOf(largo to "valor"), maxFirst = 10)
        assertEquals("$largo    valor", salida.single())
    }

    @Test
    fun `la continuacion se alinea con la segunda columna`() {
        // 8 de handle + 4 de hueco = la segunda columna empieza en la celda 12.
        assertEquals(12, Columns.hangingIndent("whatsapp    com.whatsapp"))
    }

    @Test
    fun `una linea de prosa no se sangra`() {
        // Sin hueco de dos espacios no hay segunda columna que respetar.
        assertEquals(0, Columns.hangingIndent("rm: fotos: permission denied"))
        assertEquals(0, Columns.hangingIndent(""))
        assertEquals(0, Columns.hangingIndent("una sola palabra"))
    }

    @Test
    fun `una linea que acaba en espacios no cuenta como dos columnas`() {
        // Es una línea rellena, no una fila: sangrar la continuación no tendría sentido.
        assertEquals(0, Columns.hangingIndent("texto     "))
    }

    @Test
    fun `el sangrado tiene tope`() {
        // Una primera columna desproporcionada dejaría la continuación tan a la derecha que no
        // cabría nada, y el remedio sería peor que la enfermedad.
        val monstruo = "x".repeat(60) + "    valor"
        assertEquals(Columns.MAX_HANGING, Columns.hangingIndent(monstruo))
    }

    @Test
    fun `manda el primer hueco, no el ultimo`() {
        // En `info` hay varias columnas de espacios; la que define la sangría es la primera, que es
        // donde empieza el valor.
        assertEquals(9, Columns.hangingIndent("package  com.whatsapp  extra"))
    }
}
