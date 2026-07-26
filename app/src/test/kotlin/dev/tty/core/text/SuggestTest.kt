package dev.tty.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * La sugerencia por distancia de edición.
 *
 * Lo que se protege aquí no es el algoritmo —Levenshtein es Levenshtein— sino la **política**: una
 * sugerencia equivocada es peor que ninguna, porque manda al usuario a escribir otro comando en
 * falso.
 */
class SuggestTest {

    @Test
    fun `la distancia cuenta inserciones, borrados y sustituciones`() {
        assertEquals(0, Suggest.distance("open", "open"))
        assertEquals(1, Suggest.distance("open", "opens"))  // inserción
        assertEquals(1, Suggest.distance("kill", "kil"))    // borrado
        assertEquals(1, Suggest.distance("kill", "bill"))   // sustitución
        assertEquals(3, Suggest.distance("kitten", "sitting"))
    }

    @Test
    fun `con una cadena vacia la distancia es la longitud de la otra`() {
        assertEquals(4, Suggest.distance("", "open"))
        assertEquals(4, Suggest.distance("open", ""))
    }

    @Test
    fun `el corte por maximo no cambia el resultado cuando cabe`() {
        assertEquals(1, Suggest.distance("kill", "kil", max = 3))
        // Por encima del máximo solo se garantiza que el valor lo supere, no cuál es.
        assert(Suggest.distance("kill", "settings", max = 2) > 2)
    }

    @Test
    fun `sugiere el comando obvio ante una errata`() {
        val verbos = listOf("help", "apps", "open", "clear", "kill", "uninstall", "info", "settings")
        assertEquals("open", Suggest.closest("opn", verbos))
        assertEquals("apps", Suggest.closest("aps", verbos))
        assertEquals("settings", Suggest.closest("setings", verbos))
    }

    @Test
    fun `no sugiere nada cuando no se parece a nada`() {
        val verbos = listOf("help", "apps", "open", "clear")
        assertNull(Suggest.closest("xyzzy", verbos))
        assertNull(Suggest.closest("tmux", verbos))
    }

    @Test
    fun `en palabras cortas el umbral es estricto`() {
        // Con tres letras casi nada es una errata: `rm` y `cp` son verbos distintos, no un desliz.
        assertNull(Suggest.closest("rm", listOf("cp", "mv", "ls")))
        // Pero una sola letra de diferencia sí pasa.
        assertEquals("ls", Suggest.closest("la", listOf("ls", "cp")))
    }

    @Test
    fun `ante empate gana siempre el mismo`() {
        // Determinismo: un error que cambia de opinión entre ejecuciones no se puede confiar.
        val a = Suggest.closest("cat", listOf("cut", "bat", "car"))
        val b = Suggest.closest("cat", listOf("car", "bat", "cut"))
        assertEquals(a, b)
        assertEquals("bat", a)
    }

    @Test
    fun `la pista se formatea o desaparece`() {
        assertEquals(" — did you mean open?", Suggest.hint("opn", listOf("open", "apps")))
        assertEquals("", Suggest.hint("xyzzy", listOf("open", "apps")))
        assertEquals("", Suggest.hint("", listOf("open")))
    }

    @Test
    fun `mayusculas y minusculas no cuentan al comparar`() {
        assertEquals("open", Suggest.closest("OPN", listOf("open")))
        // Compara en minúsculas pero devuelve el candidato **tal y como estaba en la lista**: quien
        // llama pasa handles reales y espera que la sugerencia se pueda copiar y escribir tal cual.
        assertEquals("OPEN", Suggest.closest("opn", listOf("OPEN")))
    }
}
