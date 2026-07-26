package dev.tty.core.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El tokenizador y los flags.
 *
 * Es la primera pieza que toca todo lo que el usuario escribe: si aquí se cuela un error, ningún
 * comando puede recuperarse. Se testea el contrato entero, incluidos los casos que la
 * especificación decide **no** tratar como error (la comilla sin cerrar) y el que decide tratar
 * como no-op (la línea vacía).
 */
class CommandLineTest {

    // ---------------------------------------------------------------- tokenización

    @Test
    fun `trocea por espacios`() {
        assertEquals(listOf("open", "spotify"), CommandLine.tokenize("open spotify"))
    }

    @Test
    fun `los espacios de mas no producen tokens vacios`() {
        assertEquals(
            listOf("open", "spotify"),
            CommandLine.tokenize("   open    spotify   "),
        )
    }

    @Test
    fun `las comillas dobles agrupan y desaparecen`() {
        assertEquals(
            listOf("open", "nova launcher"),
            CommandLine.tokenize("open \"nova launcher\""),
        )
    }

    @Test
    fun `las comillas simples agrupan igual que las dobles`() {
        assertEquals(
            listOf("open", "nova launcher"),
            CommandLine.tokenize("open 'nova launcher'"),
        )
    }

    @Test
    fun `una comilla dentro de la otra es un caracter mas`() {
        // No hay expansión de ningún tipo dentro de las comillas: agrupar es su única función.
        assertEquals(listOf("echo", "it's fine"), CommandLine.tokenize("echo \"it's fine\""))
        assertEquals(listOf("echo", "say \"hi\""), CommandLine.tokenize("echo 'say \"hi\"'"))
    }

    @Test
    fun `la barra invertida escapa el caracter siguiente`() {
        // En el prompt se escribe:  cat mi\ fichero.txt
        assertEquals(
            listOf("cat", "mi fichero.txt"),
            CommandLine.tokenize("cat mi\\ fichero.txt"),
        )
    }

    @Test
    fun `la barra invertida escapa tambien las comillas`() {
        // En el prompt se escribe:  echo \"literal\"
        assertEquals(
            listOf("echo", "\"literal\""),
            CommandLine.tokenize("echo \\\"literal\\\""),
        )
    }

    @Test
    fun `una comilla sin cerrar agrupa hasta el final de la linea`() {
        // Decisión explícita del parser: en un prompt de una sola línea no hay continuación
        // posible, así que rechazarla solo sería fricción.
        assertEquals(
            listOf("open", "nova launcher"),
            CommandLine.tokenize("open \"nova launcher"),
        )
    }

    @Test
    fun `un par de comillas vacio produce un token vacio`() {
        // Las comillas son el único modo de escribir un argumento vacío a propósito.
        assertEquals(listOf("echo", ""), CommandLine.tokenize("echo \"\""))
    }

    @Test
    fun `la entrada vacia y la de solo espacios no producen tokens`() {
        assertEquals(emptyList<String>(), CommandLine.tokenize(""))
        assertEquals(emptyList<String>(), CommandLine.tokenize("     "))
        assertEquals(emptyList<String>(), CommandLine.tokenize("\t \n "))
    }

    // ---------------------------------------------------------------- parse

    @Test
    fun `la entrada vacia devuelve null y no hace nada`() {
        // §5.3: entrada vacía no hace nada, no imprime y no da error. El null es cómo se dice.
        assertNull(CommandLine.parse(""))
    }

    @Test
    fun `la entrada de solo espacios devuelve null`() {
        assertNull(CommandLine.parse("      "))
        assertNull(CommandLine.parse("\t\t"))
    }

    @Test
    fun `el verbo se pasa a minusculas y los argumentos no`() {
        val line = CommandLine.parse("OPEN Nova Launcher")
        assertEquals("open", line?.verb)
        assertEquals(listOf("Nova", "Launcher"), line?.tokens)
    }

    @Test
    fun `un verbo solo deja la lista de tokens vacia`() {
        val line = CommandLine.parse("  help  ")
        assertEquals("help", line?.verb)
        assertEquals(emptyList<String>(), line?.tokens)
    }

    @Test
    fun `el argumento entrecomillado llega entero como un unico token`() {
        val line = CommandLine.parse("open \"nova launcher\"")
        assertEquals("open", line?.verb)
        assertEquals(listOf("nova launcher"), line?.tokens)
    }

    // ---------------------------------------------------------------- flags

    @Test
    fun `los booleanos se pueden agrupar en un solo token`() {
        val flags = Flags.parse(listOf("-sl"), known = setOf('s', 'l'))
        assertTrue('s' in flags)
        assertTrue('l' in flags)
        assertTrue(flags.unknown.isEmpty())
        assertEquals(emptyList<String>(), flags.operands)
    }

    @Test
    fun `los booleanos tambien se pueden escribir sueltos`() {
        val flags = Flags.parse(listOf("-s", "-l"), known = setOf('s', 'l'))
        assertTrue('s' in flags)
        assertTrue('l' in flags)
        assertTrue(flags.unknown.isEmpty())
    }

    @Test
    fun `un flag con valor consume el token siguiente`() {
        val flags = Flags.parse(listOf("-n", "40", "fichero"), withValue = setOf('n'))
        assertTrue('n' in flags)
        assertEquals("40", flags.value('n'))
        // El valor no puede acabar además como operando: sería un argumento fantasma.
        assertEquals(listOf("fichero"), flags.operands)
    }

    @Test
    fun `un flag con valor agrupado con un booleano tambien lo consume`() {
        val flags = Flags.parse(
            listOf("-rn", "40", "fichero"),
            known = setOf('r'),
            withValue = setOf('n'),
        )
        assertTrue('r' in flags)
        assertEquals("40", flags.value('n'))
        assertEquals(listOf("fichero"), flags.operands)
    }

    @Test
    fun `un flag con valor sin nada detras esta presente pero sin valor`() {
        // El comando decide qué hacer: aquí solo se garantiza que no revienta ni inventa un valor.
        val flags = Flags.parse(listOf("-n"), withValue = setOf('n'))
        assertTrue('n' in flags)
        assertNull(flags.value('n'))
    }

    @Test
    fun `el valor de un flag que no se escribio es null`() {
        val flags = Flags.parse(listOf("fichero"), withValue = setOf('n'))
        assertFalse('n' in flags)
        assertNull(flags.value('n'))
    }

    @Test
    fun `los dos guiones terminan el procesado de flags`() {
        // Es lo que permite escribir  rm -- -fichero-raro
        val flags = Flags.parse(listOf("--", "-fichero-raro"), known = setOf('f', 'r'))
        assertEquals(listOf("-fichero-raro"), flags.operands)
        assertFalse('f' in flags)
        assertTrue(flags.unknown.isEmpty())
    }

    @Test
    fun `los flags anteriores a los dos guiones si se procesan`() {
        val flags = Flags.parse(listOf("-s", "--", "-raro"), known = setOf('s'))
        assertTrue('s' in flags)
        assertEquals(listOf("-raro"), flags.operands)
    }

    @Test
    fun `un flag que el comando no conoce va a unknown y no se ignora`() {
        val flags = Flags.parse(listOf("-x"), known = setOf('s'))
        assertEquals(setOf('x'), flags.unknown)
        assertFalse('x' in flags)
    }

    @Test
    fun `en un grupo se separa lo conocido de lo desconocido`() {
        val flags = Flags.parse(listOf("-sxl"), known = setOf('s', 'l'))
        assertTrue('s' in flags)
        assertTrue('l' in flags)
        assertEquals(setOf('x'), flags.unknown)
    }

    @Test
    fun `lo que no es un flag es un operando`() {
        val flags = Flags.parse(listOf("-s", "spotify", "extra"), known = setOf('s'))
        assertTrue('s' in flags)
        assertEquals(listOf("spotify", "extra"), flags.operands)
    }

    @Test
    fun `sin tokens no hay ni flags ni operandos`() {
        val flags = Flags.parse(emptyList(), known = setOf('s'))
        assertFalse('s' in flags)
        assertTrue(flags.operands.isEmpty())
        assertTrue(flags.unknown.isEmpty())
    }
}
