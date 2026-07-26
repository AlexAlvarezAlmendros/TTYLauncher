package dev.tty.core.script

import dev.tty.core.Limits
import dev.tty.core.output.Output
import dev.tty.core.output.Role
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El motor de scripts.
 *
 * El test que de verdad importa es el de la recursión: **un script que se llama a sí mismo tiene que
 * terminar con un error, no colgar la pantalla de inicio** (criterio 7). Un launcher colgado es un
 * móvil inutilizable, y eso no se puede comprobar mirando.
 */
class ScriptTest {

    // ------------------------------------------------------------------ sustitución

    @Test
    fun `los posicionales se sustituyen por su argumento`() {
        assertEquals("open obsidian", Substitution.apply("open \$1", listOf("obsidian")))
        assertEquals("mv a b", Substitution.apply("mv \$1 \$2", listOf("a", "b")))
        assertEquals("x 9", Substitution.apply("x \$9", List(9) { "${it + 1}" }))
    }

    @Test
    fun `arroba los sustituye todos`() {
        assertEquals("open a b c", Substitution.apply("open \$@", listOf("a", "b", "c")))
        assertEquals("open ", Substitution.apply("open \$@", emptyList()))
    }

    @Test
    fun `un posicional sin argumento se queda vacio, no deja el literal`() {
        // Dejar `$1` haría que `open $1` buscara una app llamada «$1» y el error hablaría de algo
        // que el usuario no escribió.
        assertEquals("open ", Substitution.apply("open \$1", emptyList()))
    }

    @Test
    fun `no hay ninguna otra sustitucion`() {
        // Ni entorno, ni aritmética, ni llaves. El dólar suelto se queda tal cual.
        assertEquals("echo \$HOME", Substitution.apply("echo \$HOME", listOf("x")))
        assertEquals("cost \$", Substitution.apply("cost \$", listOf("x")))
        assertEquals("a\$0b", Substitution.apply("a\$0b", listOf("x")))
    }

    // ------------------------------------------------------------------ nombres

    @Test
    fun `los nombres validos son los de la especificacion`() {
        val libre = { _: String -> false }
        assertNull(ScriptName.validate("focus", libre))
        assertNull(ScriptName.validate("morning-2", libre))
        assertNull(ScriptName.validate("a_b9", libre))
    }

    @Test
    fun `cada nombre invalido tiene su motivo`() {
        val libre = { _: String -> false }
        assertEquals(NameProblem.Empty, ScriptName.validate("", libre))
        assertEquals(NameProblem.BadStart, ScriptName.validate("9lives", libre))
        assertEquals(NameProblem.BadStart, ScriptName.validate("Focus", libre))
        assertEquals(NameProblem.BadChars, ScriptName.validate("mi script", libre))
        assertEquals(NameProblem.BadChars, ScriptName.validate("a/b", libre))
        assertEquals(NameProblem.TooLong, ScriptName.validate("a".repeat(33), libre))
        // `invalid name` no serviría: el mensaje tiene que decir qué arreglar.
    }

    @Test
    fun `un nombre de comando se rechaza siempre`() {
        val reservado = { n: String -> n == "rm" }
        assertEquals(NameProblem.Reserved, ScriptName.validate("rm", reservado))
        // Es lo que impide que un script llamado `rm` secuestre un verbo de confianza (§8.4).
    }

    @Test
    fun `los nombres peligrosos como fichero se rechazan`() {
        assertTrue(ScriptName.isSafeFileName("focus"))
        assertTrue(!ScriptName.isSafeFileName(".."))
        assertTrue(!ScriptName.isSafeFileName("../../etc/passwd"))
        assertTrue(!ScriptName.isSafeFileName("a/b"))
        assertTrue(!ScriptName.isSafeFileName(".oculto"))
    }

    // ------------------------------------------------------------------ ejecución

    private class FakeStore(private val scripts: MutableMap<String, Script> = mutableMapOf()) : ScriptStore {
        override suspend fun list() = scripts.values.sortedBy { it.name }
        override suspend fun read(name: String) = scripts[name]
        override suspend fun write(script: Script): Boolean {
            scripts[script.name] = script
            return true
        }

        override suspend fun delete(name: String) = scripts.remove(name) != null
        override suspend fun seedExamples() = Unit
    }

    /** Ejecuta líneas registrando lo que se pidió; una línea que empiece por `boom` falla. */
    private class Recorder {
        val executed = mutableListOf<String>()
        suspend fun execute(line: String, budget: Budget): Output {
            executed += line
            return if (line.startsWith("boom")) Output.error("boom: failed") else Output.of("ok: $line")
        }
    }

    @Test
    fun `ejecuta las lineas en orden y con los argumentos puestos`() = runBlocking {
        val store = FakeStore(mutableMapOf("focus" to Script("focus", listOf("kill a", "open \$1"))))
        val rec = Recorder()

        ScriptRunner(store).run(Script("focus", store.read("focus")!!.lines), listOf("obsidian"), Budget(), rec::execute)

        assertEquals(listOf("kill a", "open obsidian"), rec.executed)
    }

    @Test
    fun `los comentarios y las lineas en blanco no se ejecutan`() = runBlocking {
        val script = Script("x", listOf("# esto es un comentario", "", "  # con sangría", "open a"))
        val rec = Recorder()

        ScriptRunner(FakeStore()).run(script, emptyList(), Budget(), rec::execute)

        assertEquals(listOf("open a"), rec.executed)
    }

    @Test
    fun `se detiene en la primera linea que falla y muestra lo acumulado`() = runBlocking {
        val script = Script("x", listOf("open a", "boom", "open c"))
        val rec = Recorder()

        val out = ScriptRunner(FakeStore()).run(script, emptyList(), Budget(), rec::execute)

        // `open c` no llega a ejecutarse: fallar rápido y mostrar el trabajo (§8.5).
        assertEquals(listOf("open a", "boom"), rec.executed)
        assertTrue(out.lines.any { it.first == "ok: open a" })
        assertTrue(out.lines.last().second == Role.ERROR)
    }

    @Test
    fun `un script que se llama a si mismo termina con un error`() = runBlocking {
        // EL test de la fase. Criterio 7: termina, no cuelga.
        val store = FakeStore(mutableMapOf("loop" to Script("loop", listOf("loop"))))
        val runner = ScriptRunner(store)
        val budget = Budget()
        val ejecutadas = mutableListOf<String>()

        suspend fun exec(line: String, b: Budget): Output {
            ejecutadas += line
            val s = store.read(line.trim()) ?: return Output.error("no")
            return runner.run(s, emptyList(), b, ::exec)
        }

        val out = runner.run(store.read("loop")!!, emptyList(), budget, ::exec)

        assertTrue(out.lines.any { it.second == Role.ERROR })
        assertTrue(out.lines.any { it.first.contains("too deep") })
        // Y no ha ejecutado 200 líneas antes de rendirse.
        assertTrue(ejecutadas.size <= Limits.SCRIPT_MAX_DEPTH + 1)
    }

    @Test
    fun `el limite de lineas corta una invocacion desbocada`() = runBlocking {
        val largo = Script("largo", List(Limits.SCRIPT_MAX_LINES + 50) { "open a" })
        val rec = Recorder()

        val out = ScriptRunner(FakeStore()).run(largo, emptyList(), Budget(), rec::execute)

        assertEquals(Limits.SCRIPT_MAX_LINES, rec.executed.size)
        assertTrue(out.lines.last().first.contains("too many lines"))
    }

    @Test
    fun `el presupuesto de lineas es de la invocacion, no de cada script`() = runBlocking {
        // Cuatro scripts anidados no pueden ejecutar 800 líneas entre todos respetando 200 cada uno.
        val store = FakeStore(
            mutableMapOf(
                "a" to Script("a", List(150) { "open x" } + "b"),
                "b" to Script("b", List(150) { "open y" }),
            ),
        )
        val runner = ScriptRunner(store)
        val budget = Budget()
        var total = 0

        suspend fun exec(line: String, b: Budget): Output {
            val s = store.read(line.trim())
            if (s != null) return runner.run(s, emptyList(), b, ::exec)
            total++
            return Output.silent
        }

        runner.run(store.read("a")!!, emptyList(), budget, ::exec)
        assertTrue("ejecutó $total líneas", total <= Limits.SCRIPT_MAX_LINES)
    }

    // ------------------------------------------------------------------ grabación

    @Test
    fun `la grabacion acumula lineas y las resume al guardar`() {
        var r = Recording("focus")
        r = r.capture("kill instagram")
        r = r.capture("open obsidian")

        assertEquals(listOf("kill instagram", "open obsidian"), r.lines)
        assertEquals("saved focus (2 lines)", r.summary)
    }

    @Test
    fun `una sola linea se dice en singular`() {
        assertEquals("saved x (1 line)", Recording("x").capture("open a").summary)
    }

    @Test
    fun `el mensaje de entrada dice como salir`() {
        // El literal es parte del contrato: es lo único que explica el modo.
        assertEquals(
            "recording 'focus' — one command per line, 'end' to save",
            Recording.startedMessage("focus"),
        )
    }
}
