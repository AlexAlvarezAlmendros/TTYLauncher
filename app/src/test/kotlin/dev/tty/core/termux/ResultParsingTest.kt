package dev.tty.core.termux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La clasificación del resultado de Termux.
 *
 * Vive en `core/` justo para poder probarla: es la parte que decide **qué ha pasado**, y hasta la
 * revisión adversarial estaba metida en la capa de plataforma, donde no la tocaba ningún test. Estos
 * casos son los que salieron de ahí.
 */
class ResultParsingTest {

    private val bash = TermuxCommands.BASH
    private val tmux = TermuxCommands.TMUX

    @Test
    fun `una ejecucion normal devuelve sus lineas`() {
        val r = TermuxParsing.classify(
            bash,
            RawTermuxResult(stdout = "uno\ndos\n", exitCode = 0),
        ).getOrNull()

        assertEquals(listOf("uno", "dos"), r?.stdout)
        assertEquals(0, r?.exitCode)
        assertTrue(r?.truncated == false)
    }

    @Test
    fun `el binario que falta se nombra por su ruta, no por un flag`() {
        // El bug: `args` para tmux empieza por "-S" y para sh por "-c", así que el mensaje decía
        // «run 'pkg install -S'». El nombre sale del PATH, que es donde está el binario.
        val r = TermuxParsing.classify(
            tmux,
            RawTermuxResult(errmsg = "The executable file at path \"$tmux\" does not exist."),
        )
        val error = (r.exceptionOrNull() as? TermuxFailure)?.error
        assertEquals(TermuxError.MissingBinary("tmux"), error)
        assertTrue(error?.message("tmux")?.contains("pkg install tmux") == true)
    }

    @Test
    fun `termux avisa del binario ausente por errmsg, no por stderr`() {
        // Termux valida el ejecutable ANTES de lanzarlo: no hay exitCode ni stderr en ese caso.
        // La detección antigua miraba stderr y por eso nunca se disparaba.
        val r = TermuxParsing.classify(
            tmux,
            RawTermuxResult(errmsg = "The executable file at path \"$tmux\" does not exist.", exitCode = null),
        )
        assertTrue(r.isFailure)
    }

    @Test
    fun `un binario ausente dentro de bash se ve por el exit 127`() {
        // bash dice «command not found» con 127, no «No such file or directory».
        val r = TermuxParsing.classify(
            bash,
            RawTermuxResult(stderr = "bash: line 1: htop: command not found\n", exitCode = 127),
        )
        assertEquals(TermuxError.MissingBinary("htop"), (r.exceptionOrNull() as? TermuxFailure)?.error)
    }

    @Test
    fun `un fichero que no existe NO se confunde con un binario ausente`() {
        // `sh cat /sdcard/nope` sale con 1 y dice «No such file or directory». Antes eso producía
        // «run 'pkg install -c'»: inventaba una causa y se tragaba el stderr real.
        val r = TermuxParsing.classify(
            bash,
            RawTermuxResult(stderr = "cat: /sdcard/nope: No such file or directory\n", exitCode = 1),
        )
        assertTrue(r.isSuccess)
        assertEquals(listOf("cat: /sdcard/nope: No such file or directory"), r.getOrNull()?.stderr)
        assertEquals(1, r.getOrNull()?.exitCode)
    }

    @Test
    fun `allow-external-apps se reconoce por su nombre en el mensaje`() {
        val r = TermuxParsing.classify(
            bash,
            RawTermuxResult(errmsg = "tty requires `allow-external-apps` property to be set to `true`"),
        )
        assertEquals(TermuxError.ExternalAppsBlocked, (r.exceptionOrNull() as? TermuxFailure)?.error)
    }

    @Test
    fun `una salida recortada se dice`() {
        // Termux recorta a 100 KB. Presentarla como completa sería mentir (§16).
        val r = TermuxParsing.classify(
            bash,
            RawTermuxResult(stdout = "trozo", exitCode = 0, stdoutOriginalLength = 999_999),
        ).getOrNull()

        assertTrue(r?.truncated == true)
    }

    @Test
    fun `una salida completa no dice nada`() {
        val r = TermuxParsing.classify(
            bash,
            RawTermuxResult(stdout = "hola", exitCode = 0, stdoutOriginalLength = 4),
        ).getOrNull()
        assertTrue(r?.truncated == false)
    }

    @Test
    fun `un errmsg ajeno se adapta al idioma del producto`() {
        // Es la única cadena del terminal cuyo estilo no controla el producto: llega con mayúscula
        // inicial y punto final, y la §10 no admite ninguna de las dos cosas.
        val r = TermuxParsing.classify(
            bash,
            RawTermuxResult(errmsg = "Failed to execute the command. Check logcat."),
        )
        val detail = ((r.exceptionOrNull() as? TermuxFailure)?.error as? TermuxError.Failed)?.detail
        assertEquals("failed to execute the command", detail)
    }

    @Test
    fun `el mensaje de timeout saca el limite de Limits`() {
        // Un 15 escrito a mano en el texto se desincroniza del límite real en cuanto alguien lo toca.
        assertTrue(TermuxError.Timeout.message("sh").contains("15s"))
        assertTrue(TermuxError.Timeout.message("sh").startsWith("sh: termux:"))
    }

    @Test
    fun `sin exit code no se finge un cero`() {
        // Termux no siempre lo manda. Fingir un 0 sería mentir sobre si el comando funcionó.
        val r = TermuxParsing.classify(bash, RawTermuxResult(stdout = "algo")).getOrNull()
        assertNull(r?.exitCode)
        assertTrue(r?.ok == true)
    }
}
