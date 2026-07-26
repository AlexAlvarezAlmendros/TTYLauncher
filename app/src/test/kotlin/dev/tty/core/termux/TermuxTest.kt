package dev.tty.core.termux

import dev.tty.core.command.builtin.ShCommand
import dev.tty.core.command.builtin.TmuxCommand
import dev.tty.core.command.Command
import dev.tty.core.command.CommandContext
import dev.tty.core.command.DeviceInfo
import dev.tty.core.command.Session
import dev.tty.core.output.Output
import dev.tty.core.output.Role
import dev.tty.core.parse.CommandLine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `sh` y `tmux`.
 *
 * Lo que se protege aquí son las dos cosas que se pueden verificar sin Termux delante: **que los
 * tres mensajes de error son distintos** —criterio 8: el mensaje de error es el onboarding— y que
 * los argumentos de tmux se construyen exactamente como tmux los espera, porque cada elemento es un
 * argv independiente y no hay parseo de shell que arregle un espacio mal puesto.
 */
class TermuxTest {

    private class FakeTermux(
        private val error: TermuxError? = null,
        private val responses: MutableList<Result<TermuxResult>> = mutableListOf(),
    ) : TermuxClient {
        val calls = mutableListOf<Pair<String, List<String>>>()
        override suspend fun check(): TermuxError? = error
        override suspend fun run(path: String, args: List<String>): Result<TermuxResult> {
            calls += path to args
            return if (responses.isEmpty()) {
                Result.success(TermuxResult(emptyList(), emptyList(), 0))
            } else {
                responses.removeAt(0)
            }
        }
    }

    private fun ctx(termux: TermuxClient) = object : CommandContext {
        override val catalog = object : dev.tty.core.apps.AppCatalog {
            override fun all() = emptyList<dev.tty.core.apps.AppEntry>()
        }
        override val actions = object : dev.tty.core.apps.AppActions {
            override fun open(app: dev.tty.core.apps.AppEntry) = true
            override fun requestUninstall(app: dev.tty.core.apps.AppEntry) = true
            override fun openAppSettings(app: dev.tty.core.apps.AppEntry) = true
            override fun openSystemSettings() = true
        }
        override val killer = object : dev.tty.core.apps.AppKiller {
            override val mode = dev.tty.core.apps.KillMode.SYSTEM_DIALOG
            override fun requestStop(app: dev.tty.core.apps.AppEntry) = true
        }
        override val files = object : dev.tty.core.command.builtin.FileSystemAccess {
            override val cage = dev.tty.core.fs.Cage(java.nio.file.Files.createTempDirectory("tty-tmux"))
            override fun hasStorageAccess() = true
            override fun requestStorageAccess() = true
        }
        override val scripts = object : dev.tty.core.script.ScriptStore {
            override suspend fun list() = emptyList<dev.tty.core.script.Script>()
            override suspend fun read(name: String): dev.tty.core.script.Script? = null
            override suspend fun write(script: dev.tty.core.script.Script) = true
            override suspend fun delete(name: String) = false
            override suspend fun seedExamples() = Unit
        }
        override val termux: TermuxClient = termux
        override val session = object : Session {
            override suspend fun clearScrollback() = Unit
        }
        override val device = object : DeviceInfo {
            override val androidRelease = "16"
            override val model = "pixel"
            override fun appCount() = 0
            override fun scrollbackLines() = 0
        }
        override val commands: List<Command> = emptyList()
        override fun isReservedName(name: String) = false
        override fun startRecording(name: String) = Unit
    }

    private fun run(command: Command, input: String, c: CommandContext): Output = runBlocking {
        command.run(CommandLine.parse(input) ?: error("vacío"), c)
    }

    // ------------------------------------------------------------------ las tres puertas

    @Test
    fun `las tres puertas dan tres mensajes distintos`() {
        // Criterio 8. Si los tres dijeran «termux error», el usuario no sabría cuál cerrar.
        val ausente = run(ShCommand, "sh uptime", ctx(FakeTermux(TermuxError.NotInstalled)))
        val permiso = run(ShCommand, "sh uptime", ctx(FakeTermux(TermuxError.PermissionDenied)))
        val externo = run(ShCommand, "sh uptime", ctx(FakeTermux(TermuxError.ExternalAppsBlocked)))

        assertEquals("sh: termux not installed (use the F-Droid or GitHub build)", ausente.lines.first().first)
        assertEquals("sh: termux: RUN_COMMAND permission not granted", permiso.lines.first().first)
        assertEquals(
            "sh: termux: could not start RunCommandService — is allow-external-apps set?",
            externo.lines.first().first,
        )
        assertTrue(listOf(ausente, permiso, externo).all { it.lines.first().second == Role.ERROR })
    }

    @Test
    fun `el mismo error dice el verbo que lo produjo`() {
        val out = run(TmuxCommand, "tmux build", ctx(FakeTermux(TermuxError.NotInstalled)))
        assertTrue(out.lines.first().first.startsWith("tmux:"))
        // El comando no cambia de nombre entre sintaxis, ayuda y error (§10).
    }

    @Test
    fun `un binario que falta se distingue del resto`() {
        val fake = FakeTermux(
            responses = mutableListOf(Result.failure(TermuxFailure(TermuxError.MissingBinary("tmux")))),
        )
        val out = run(TmuxCommand, "tmux build", ctx(fake))
        assertTrue(out.lines.first().first.contains("pkg install tmux"))
        // tmux no viene instalado en Termux: decirlo es la diferencia entre útil y opaco.
    }

    // ------------------------------------------------------------------ sh

    @Test
    fun `sh manda la linea entera a bash sin tocarla`() {
        val fake = FakeTermux()
        run(ShCommand, "sh echo \"hola mundo\"", ctx(fake))

        val (path, args) = fake.calls.single()
        assertEquals(TermuxCommands.BASH, path)
        // Las comillas llegan puestas: el parser se las quita, pero bash las necesita ver.
        assertEquals(listOf("-c", "echo \"hola mundo\""), args)
    }

    @Test
    fun `sh sin linea no llama a nadie`() {
        val fake = FakeTermux()
        val out = run(ShCommand, "sh", ctx(fake))
        assertTrue(fake.calls.isEmpty())
        assertEquals(Role.ERROR, out.lines.first().second)
    }

    @Test
    fun `stdout y stderr no se mezclan`() {
        val fake = FakeTermux(
            responses = mutableListOf(
                Result.success(TermuxResult(listOf("todo bien"), listOf("algo mal"), 1)),
            ),
        )
        val out = run(ShCommand, "sh algo", ctx(fake))

        assertEquals(Role.OUTPUT, out.lines[0].second)
        assertEquals(Role.ERROR, out.lines[1].second)
        // Y el código de salida se dice porque no es 0.
        assertEquals("exit 1", out.lines[2].first)
    }

    @Test
    fun `un exit 0 no imprime codigo`() {
        val fake = FakeTermux(responses = mutableListOf(Result.success(TermuxResult(listOf("ok"), emptyList(), 0))))
        val out = run(ShCommand, "sh algo", ctx(fake))
        assertEquals(1, out.lines.size)
    }

    // ------------------------------------------------------------------ tmux

    @Test
    fun `tmux crea la sesion si no existe y luego la fotografia`() {
        val fake = FakeTermux(
            responses = mutableListOf(
                Result.success(TermuxResult(emptyList(), emptyList(), 1)), // has-session: no existe
                Result.success(TermuxResult(emptyList(), emptyList(), 0)), // new-session
                Result.success(TermuxResult(listOf("$ npm run build"), emptyList(), 0)), // capture
            ),
        )
        run(TmuxCommand, "tmux build", ctx(fake))

        assertEquals(3, fake.calls.size)
        assertTrue(fake.calls[0].second.contains("has-session"))
        assertTrue(fake.calls[1].second.containsAll(listOf("new-session", "-d", "-s", "build")))
        assertTrue(fake.calls[2].second.contains("capture-pane"))
    }

    @Test
    fun `si la sesion existe no se recrea`() {
        val fake = FakeTermux(
            responses = mutableListOf(
                Result.success(TermuxResult(emptyList(), emptyList(), 0)), // has-session: sí existe
                Result.success(TermuxResult(listOf("linea"), emptyList(), 0)),
            ),
        )
        run(TmuxCommand, "tmux build", ctx(fake))

        assertEquals(2, fake.calls.size)
        assertTrue(fake.calls.none { it.second.contains("new-session") })
    }

    @Test
    fun `las teclas van como un solo argv y Enter aparte`() {
        val args = TermuxCommands.Tmux.sendKeys("build", "npm run build")
        // tmux distingue el texto de la pulsación así: si «npm run build» fueran tres argumentos,
        // enviaría tres pulsaciones distintas.
        assertEquals(listOf("send-keys", "-t", "build", "npm run build", "Enter"), args.drop(2))
    }

    @Test
    fun `la captura pide las lineas que se pidieron`() {
        val fake = FakeTermux(
            responses = mutableListOf(
                Result.success(TermuxResult(emptyList(), emptyList(), 0)),
                Result.success(TermuxResult(listOf("x"), emptyList(), 0)),
            ),
        )
        run(TmuxCommand, "tmux build -n 40", ctx(fake))
        assertTrue(fake.calls.last().second.contains("-40"))
    }

    @Test
    fun `-n con basura se rechaza antes de llamar`() {
        val fake = FakeTermux()
        val out = run(TmuxCommand, "tmux build -n abc", ctx(fake))
        assertTrue(fake.calls.isEmpty())
        assertTrue(out.lines.first().first.contains("positive number"))
    }

    @Test
    fun `el socket se fija siempre`() {
        // El servidor de un app-shell y el de una sesión de terminal solo se encuentran si comparten
        // socket. Sin fijarlo, `tmux build` podría hablar con un tmux distinto del que ves.
        listOf(
            TermuxCommands.Tmux.hasSession("x"),
            TermuxCommands.Tmux.newSession("x"),
            TermuxCommands.Tmux.sendKeys("x", "y"),
            TermuxCommands.Tmux.capturePane("x", 10),
        ).forEach { args ->
            assertEquals(listOf("-S", TermuxCommands.Tmux.SOCKET), args.take(2))
        }
    }

    @Test
    fun `capture-pane une las lineas partidas`() {
        // Sin -J, una línea larga llega cortada por donde el terminal la partió, no por donde toca.
        assertTrue(TermuxCommands.Tmux.capturePane("x", 10).contains("-J"))
    }

    @Test
    fun `una sesion vacia se dice, no se calla`() {
        val fake = FakeTermux(
            responses = mutableListOf(
                Result.success(TermuxResult(emptyList(), emptyList(), 0)),
                Result.success(TermuxResult(emptyList(), emptyList(), 0)),
            ),
        )
        val out = run(TmuxCommand, "tmux build", ctx(fake))
        assertEquals("build is empty", out.lines.first().first)
    }
}
