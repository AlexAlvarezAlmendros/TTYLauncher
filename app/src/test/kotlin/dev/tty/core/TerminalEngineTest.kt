package dev.tty.core

import dev.tty.core.apps.AppActions
import dev.tty.core.apps.AppCatalog
import dev.tty.core.apps.AppKiller
import dev.tty.core.apps.KillMode
import dev.tty.core.apps.AppEntry
import dev.tty.core.command.Command
import dev.tty.core.command.CommandContext
import dev.tty.core.command.CommandRegistry
import dev.tty.core.command.DeviceInfo
import dev.tty.core.command.Session
import dev.tty.core.command.builtin.ClearCommand
import dev.tty.core.output.Line
import dev.tty.core.output.Output
import dev.tty.core.output.Role
import dev.tty.core.parse.CommandLine
import dev.tty.core.scrollback.Scrollback
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// -------------------------------------------------------------------- dobles de prueba
//
// Escritos a mano: no hay mockito ni ninguna librería de dobles, y no debería haberla — las
// interfaces de core/ tienen uno o dos métodos precisamente para que implementarlas sea trivial.

private class FakeCatalog(private val apps: List<AppEntry> = emptyList()) : AppCatalog {
    override fun all(): List<AppEntry> = apps
}

private class FakeActions(private val result: Boolean = true) : AppActions {
    var opened: AppEntry? = null
    var uninstalled: AppEntry? = null
    var settingsFor: AppEntry? = null
    var systemSettings = 0

    override fun open(app: AppEntry): Boolean {
        opened = app
        return result
    }

    override fun requestUninstall(app: AppEntry): Boolean {
        uninstalled = app
        return result
    }

    override fun openAppSettings(app: AppEntry): Boolean {
        settingsFor = app
        return result
    }

    override fun openSystemSettings(): Boolean {
        systemSettings++
        return result
    }
}

private class FakeKiller(
    override val mode: KillMode = KillMode.SYSTEM_DIALOG,
    private val result: Boolean = true,
) : AppKiller {
    var stopped: AppEntry? = null
    override fun requestStop(app: AppEntry): Boolean {
        stopped = app
        return result
    }
}

private class FakeSession : Session {
    var clears = 0
    override suspend fun clearScrollback() {
        clears++
    }
}

private object FakeDevice : DeviceInfo {
    override val androidRelease = "16"
    override val model = "pixel 9a"
    override fun appCount() = 0
    override fun scrollbackLines() = 0
}


/** La jaula sobre un directorio temporal: los tests de comandos no tocan el disco de verdad. */
private class FakeFiles(
    root: java.nio.file.Path = java.nio.file.Files.createTempDirectory("tty-fake"),
    private val granted: Boolean = true,
) : dev.tty.core.command.builtin.FileSystemAccess {
    override val cage = dev.tty.core.fs.Cage(root)
    var requests = 0
    override fun hasStorageAccess() = granted
    override fun requestStorageAccess(): Boolean { requests++; return true }
}


/** Un almacén de scripts en memoria: los tests del motor no tocan el disco. */
private class FakeScripts(private val items: MutableMap<String, dev.tty.core.script.Script> = mutableMapOf()) :
    dev.tty.core.script.ScriptStore {
    override suspend fun list() = items.values.sortedBy { it.name }
    override suspend fun read(name: String) = items[name]
    override suspend fun write(script: dev.tty.core.script.Script): Boolean { items[script.name] = script; return true }
    override suspend fun delete(name: String) = items.remove(name) != null
    override suspend fun seedExamples() = Unit
}


/** Termux ausente: es el estado por defecto de un móvil, y el que más hay que probar. */
private class FakeTermuxClient(private val error: dev.tty.core.termux.TermuxError? = null) :
    dev.tty.core.termux.TermuxClient {
    override suspend fun check() = error
    override suspend fun run(path: String, args: List<String>) =
        Result.success(dev.tty.core.termux.TermuxResult(emptyList(), emptyList(), 0))
}

private class FakeContext(
    override val catalog: AppCatalog = FakeCatalog(),
    override val actions: AppActions = FakeActions(),
    override val killer: AppKiller = FakeKiller(),
    override val files: dev.tty.core.command.builtin.FileSystemAccess = FakeFiles(),
    override val scripts: dev.tty.core.script.ScriptStore = FakeScripts(),
    override val termux: dev.tty.core.termux.TermuxClient = FakeTermuxClient(),
    override val session: Session = FakeSession(),
    override val device: DeviceInfo = FakeDevice,
    override val commands: List<Command> = emptyList(),
) : CommandContext {
    var recordingStarted: String? = null
    override fun isReservedName(name: String) = false
    override fun startRecording(name: String) { recordingStarted = name }
}

/** Un comando que devuelve lo que se le da, para comprobar que la salida llega entera. */
private object EchoCommand : Command {
    override val name = "say"
    override val syntax = "say <text>"
    override val summary = "print its arguments"
    override suspend fun run(line: CommandLine, ctx: CommandContext): Output =
        Output.of(line.tokens.joinToString(" "))
}

/** Un comando silencioso: el caso normal, no una excepción. */
private object QuietCommand : Command {
    override val name = "quiet"
    override val syntax = "quiet"
    override val summary = "do nothing visible"
    override suspend fun run(line: CommandLine, ctx: CommandContext): Output = Output.silent
}

/** Un comando que revienta con mensaje. */
private object BoomCommand : Command {
    override val name = "boom"
    override val syntax = "boom"
    override val summary = "throw on purpose"
    override suspend fun run(line: CommandLine, ctx: CommandContext): Output =
        throw IllegalStateException("kaboom")
}

/** Un comando que revienta sin mensaje: el caso que produce un error ilegible si no se trata. */
private class Silent : RuntimeException()

private object MuteBoomCommand : Command {
    override val name = "mute"
    override val syntax = "mute"
    override val summary = "throw without a message"
    override suspend fun run(line: CommandLine, ctx: CommandContext): Output = throw Silent()
}

/** Un comando que se pasa del límite de salida, para ver el recorte a través del motor. */
private object FloodCommand : Command {
    override val name = "flood"
    override val syntax = "flood"
    override val summary = "print too much"
    override suspend fun run(line: CommandLine, ctx: CommandContext): Output =
        Output.output((0 until Limits.COMMAND_OUTPUT_LINES + 40).map { "line $it" })
}

/** El motor montado con sus dobles, que es todo lo que necesita para funcionar. */
private class Harness(commands: List<Command>) {
    val session = FakeSession()
    val scrollback = Scrollback()
    val context = FakeContext(session = session, commands = commands)
    val engine = TerminalEngine(CommandRegistry(commands), scrollback, context)

    fun submit(input: String): List<Line> = runBlocking { engine.submit(input) }
}

/**
 * El ciclo completo de una entrada: eco → despacho → salida.
 *
 * Sin corrutinas de test —`kotlinx.coroutines.test` no es una dependencia declarada— y sin dobles
 * generados: `runBlocking` y cinco clases escritas a mano bastan, porque el motor es Kotlin puro.
 */
class TerminalEngineTest {

    private fun harness() = Harness(
        listOf(EchoCommand, QuietCommand, BoomCommand, MuteBoomCommand, FloodCommand, ClearCommand),
    )

    // ------------------------------------------------- entrada vacía (§5.3)

    @Test
    fun `la entrada vacia no produce ninguna linea`() {
        val h = harness()
        assertEquals(emptyList<Line>(), h.submit(""))
        assertEquals(0, h.scrollback.size)
    }

    @Test
    fun `la entrada de solo espacios tampoco`() {
        val h = harness()
        assertEquals(emptyList<Line>(), h.submit("     "))
        assertEquals(emptyList<Line>(), h.submit("\t"))
        assertEquals(0, h.scrollback.size)
    }

    // ------------------------------------------------- eco (§5.3)

    @Test
    fun `toda entrada se ecoa antes de la salida`() {
        val h = harness()
        val added = h.submit("say hola")

        assertEquals(2, added.size)
        assertEquals(Role.ECHO, added[0].role)
        assertEquals("say hola", added[0].text)
        assertEquals(Role.OUTPUT, added[1].role)
        assertEquals("hola", added[1].text)
    }

    @Test
    fun `el eco es la entrada tal cual, solo recortada por los extremos`() {
        val h = harness()
        val added = h.submit("   say   hola   ")

        assertEquals("say   hola", added[0].text)
    }

    @Test
    fun `un comando silencioso ecoa y no imprime nada mas`() {
        // El éxito silencioso es el valor por defecto (§10).
        val h = harness()
        val added = h.submit("quiet")

        assertEquals(1, added.size)
        assertEquals(Role.ECHO, added[0].role)
    }

    @Test
    fun `en el scrollback lo ultimo en salir queda primero`() {
        val h = harness()
        h.submit("say hola")

        assertEquals(listOf("hola", "say hola"), h.scrollback.lines.map { it.text })
    }

    @Test
    fun `las lineas devueltas son las mismas que se anadieron al scrollback`() {
        val h = harness()
        val added = h.submit("say hola")

        // Devueltas de la más antigua a la más reciente; el scrollback las guarda al revés.
        assertEquals(added.reversed(), h.scrollback.lines.toList())
    }

    // ------------------------------------------------- verbo desconocido

    @Test
    fun `un comando desconocido da un error con la forma correcta`() {
        val h = harness()
        val added = h.submit("frobnicate")

        assertEquals(2, added.size)
        assertEquals(Role.ERROR, added[1].role)
        assertEquals("frobnicate: command not found — type help", added[1].text)
    }

    @Test
    fun `el verbo del error va en minusculas, como se despacha`() {
        val h = harness()
        val added = h.submit("FROBNICATE")

        assertEquals("frobnicate: command not found — type help", added[1].text)
    }

    // ------------------------------------------------- excepciones (§16)

    @Test
    fun `un comando que revienta produce una linea de error y no propaga`() {
        // Si esto propagase, la actividad HOME se caería, que es funcionalmente un móvil bloqueado.
        val h = harness()
        val added = h.submit("boom")

        assertEquals(2, added.size)
        assertEquals(Role.ERROR, added[1].role)
        assertEquals("boom: failed — IllegalStateException: kaboom", added[1].text)
    }

    @Test
    fun `una excepcion sin mensaje sigue dando una linea legible`() {
        val h = harness()
        val added = h.submit("mute")

        assertEquals("mute: failed — Silent: no message", added[1].text)
    }

    @Test
    fun `despues de un fallo el motor sigue aceptando entradas`() {
        val h = harness()
        h.submit("boom")
        val added = h.submit("say sigo vivo")

        assertEquals("sigo vivo", added[1].text)
        assertEquals(4, h.scrollback.size)
    }

    // ------------------------------------------------- límites y dependencias

    @Test
    fun `la salida de un comando se recorta al pasar por el motor`() {
        val h = harness()
        val added = h.submit("flood")

        // eco + el cupo entero, con la línea de aviso ya contada dentro
        assertEquals(1 + Limits.COMMAND_OUTPUT_LINES, added.size)
        assertEquals("41 more lines not shown", added.last().text)
        assertEquals(Role.STATUS, added.last().role)
    }

    @Test
    fun `un comando llega a la sesion que se le inyecta`() {
        val h = harness()
        val added = h.submit("clear")

        assertEquals(1, h.session.clears)
        assertEquals("scrollback cleared", added[1].text)
        assertEquals(Role.STATUS, added[1].role)
    }

    @Test
    fun `los alias despachan al mismo comando`() {
        val h = harness()
        assertEquals("scrollback cleared", h.submit("cls")[1].text)
        assertEquals("scrollback cleared", h.submit("clean")[1].text)
        assertEquals(2, h.session.clears)
    }

    @Test
    fun `los identificadores no se repiten entre entradas`() {
        val h = harness()
        h.submit("say una")
        h.submit("say dos")

        val ids = h.scrollback.lines.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.isNotEmpty())
    }
}
