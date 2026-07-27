package dev.tty.core.command

import dev.tty.core.apps.AppActions
import dev.tty.core.apps.AppCatalog
import dev.tty.core.apps.AppEntry
import dev.tty.core.apps.AppKiller
import dev.tty.core.apps.KillMode
import dev.tty.core.command.builtin.InfoCommand
import dev.tty.core.command.builtin.KillCommand
import dev.tty.core.command.builtin.SettingsCommand
import dev.tty.core.command.builtin.UninstallCommand
import dev.tty.core.output.Output
import dev.tty.core.output.Role
import dev.tty.core.parse.CommandLine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Los cuatro verbos de la Fase 1.
 *
 * El tema de la fase es el **principio 4**: nada silencioso que sea destructivo. Cada test de aquí
 * comprueba una de las dos mitades — que la acción se pide de verdad, y que queda constancia en
 * pantalla de que se pidió.
 */
class ManageCommandsTest {

    private fun app(label: String, pkg: String, system: Boolean = false) =
        AppEntry(label = label, packageName = pkg, component = "$pkg/.Main", isSystem = system)

    private val whatsapp = app("WhatsApp", "com.whatsapp")
    private val whatsappBsns = app("WhatsApp Bsns", "com.whatsapp.w4b")
    private val ajustes = app("Settings", "com.android.settings", system = true)

    private class Catalog(val apps: List<AppEntry>) : AppCatalog {
        override fun all() = apps
    }

    private class Actions(private val result: Boolean = true) : AppActions {
        var uninstalled: AppEntry? = null
        var settingsFor: AppEntry? = null
        var systemSettings = 0
        override fun open(app: AppEntry) = result
        override fun requestUninstall(app: AppEntry): Boolean { uninstalled = app; return result }
        override fun openAppSettings(app: AppEntry): Boolean { settingsFor = app; return result }
        override fun openSystemSettings(): Boolean { systemSettings++; return result }
    }

    private class Killer(private val result: Boolean = true) : AppKiller {
        override val mode = KillMode.SYSTEM_DIALOG
        var stopped: AppEntry? = null
        override fun requestStop(app: AppEntry): Boolean { stopped = app; return result }
    }

    private class Ctx(
        override val catalog: AppCatalog,
        override val actions: AppActions,
        override val killer: AppKiller,
    ) : CommandContext {
        override val files = object : dev.tty.core.command.builtin.FileSystemAccess {
            override val cage = dev.tty.core.fs.Cage(java.nio.file.Files.createTempDirectory("tty-manage"))
            override fun hasStorageAccess() = true
            override fun requestStorageAccess() = true
        }
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
        override val scripts = object : dev.tty.core.script.ScriptStore {
            override suspend fun list() = emptyList<dev.tty.core.script.Script>()
            override suspend fun read(name: String): dev.tty.core.script.Script? = null
            override suspend fun write(script: dev.tty.core.script.Script) = true
            override suspend fun delete(name: String) = false
            override suspend fun seedExamples() = Unit
        }
        override val termux = object : dev.tty.core.termux.TermuxClient {
            override suspend fun check() = dev.tty.core.termux.TermuxError.NotInstalled
            override suspend fun run(path: String, args: List<String>) =
                Result.success(dev.tty.core.termux.TermuxResult(emptyList(), emptyList(), 0))
        }
        override fun isReservedName(name: String) = false
        override fun startRecording(name: String) = Unit
    }

    private fun ctx(
        apps: List<AppEntry> = listOf(whatsapp, whatsappBsns, ajustes),
        actions: Actions = Actions(),
        killer: Killer = Killer(),
    ) = Ctx(Catalog(apps), actions, killer)

    private fun run(command: Command, input: String, c: CommandContext): Output = runBlocking {
        command.run(CommandLine.parse(input) ?: error("entrada vacía"), c)
    }

    // ------------------------------------------------------------------ kill

    @Test
    fun `kill pide la detencion y dice quien tiene que pulsar el boton`() {
        val killer = Killer()
        val c = ctx(killer = killer)
        val out = run(KillCommand, "kill whatsapp-bsns", c)

        assertEquals(whatsappBsns, killer.stopped)
        assertEquals(1, out.lines.size)
        assertEquals(Role.STATUS, out.lines.first().second)
        // El límite se dice en el propio mensaje, no en la documentación (§10).
        assertTrue(out.lines.first().first.contains("android 14+"))
        assertTrue(out.lines.first().first.contains("whatsapp-bsns"))
    }

    @Test
    fun `kill ante ambiguedad no toca nada`() {
        val killer = Killer()
        val out = run(KillCommand, "kill whatsapp", ctx(killer = killer))

        // 'whatsapp' casa exactamente con un handle y por prefijo con dos: gana el rango 2.
        assertEquals(whatsapp, killer.stopped)

        val killer2 = Killer()
        val out2 = run(KillCommand, "kill what", ctx(killer = killer2))
        assertNull(killer2.stopped)
        assertEquals(Role.ERROR, out2.lines.first().second)
        assertTrue(out2.lines.first().first.contains("is ambiguous"))
        assertTrue(out.lines.first().second == Role.STATUS)
    }

    @Test
    fun `kill sin argumento no adivina`() {
        val killer = Killer()
        val out = run(KillCommand, "kill", ctx(killer = killer))
        assertNull(killer.stopped)
        assertEquals(Role.ERROR, out.lines.first().second)
    }

    // ------------------------------------------------------------------ uninstall

    @Test
    fun `uninstall abre el dialogo y deja constancia`() {
        val actions = Actions()
        val out = run(UninstallCommand, "uninstall whatsapp-bsns", ctx(actions = actions))

        assertEquals(whatsappBsns, actions.uninstalled)
        assertEquals("confirm in the system dialog", out.lines.first().first)
    }

    @Test
    fun `uninstall rechaza una app de sistema antes de abrir nada`() {
        val actions = Actions()
        val out = run(UninstallCommand, "uninstall settings", ctx(actions = actions))

        // Rechazo propio, no dejar que el diálogo del sistema falle (§6.2).
        assertNull(actions.uninstalled)
        assertEquals(Role.ERROR, out.lines.first().second)
        assertTrue(out.lines.first().first.contains("system app"))
    }

    @Test
    fun `uninstall no desinstala ante ambiguedad`() {
        val actions = Actions()
        val out = run(UninstallCommand, "uninstall what", ctx(actions = actions))

        // El criterio 5 entero: ningún comando destructivo actúa sobre una coincidencia ambigua.
        assertNull(actions.uninstalled)
        assertTrue(out.lines.first().first.contains("is ambiguous"))
    }

    @Test
    fun `si la plataforma no abre el dialogo, se dice`() {
        val actions = Actions(result = false)
        val out = run(UninstallCommand, "uninstall whatsapp-bsns", ctx(actions = actions))
        assertEquals(Role.ERROR, out.lines.first().second)
    }

    // ------------------------------------------------------------------ info

    @Test
    fun `info imprime las cinco filas en columnas`() {
        val out = run(InfoCommand, "info whatsapp-bsns", ctx())
        assertEquals(5, out.lines.size)

        val texto = out.lines.joinToString("\n") { it.first }
        assertTrue(texto.contains("WhatsApp Bsns"))
        assertTrue(texto.contains("whatsapp-bsns"))
        assertTrue(texto.contains("com.whatsapp.w4b"))
        assertTrue(texto.contains("system"))
        assertTrue(out.lines.all { it.second == Role.OUTPUT })
    }

    @Test
    fun `info -o abre los ajustes y no imprime nada`() {
        val actions = Actions()
        val out = run(InfoCommand, "info -o whatsapp-bsns", ctx(actions = actions))

        // Éxito silencioso: la pantalla ya está delante (§10).
        assertEquals(whatsappBsns, actions.settingsFor)
        assertTrue(out.lines.isEmpty())
    }

    @Test
    fun `info con un flag que no existe lo dice`() {
        val out = run(InfoCommand, "info -z whatsapp", ctx())
        assertEquals(Role.ERROR, out.lines.first().second)
        assertTrue(out.lines.first().first.contains("-z"))
    }

    // ------------------------------------------------------------------ settings

    @Test
    fun `settings abre los ajustes en silencio`() {
        val actions = Actions()
        val out = run(SettingsCommand, "settings", ctx(actions = actions))
        assertEquals(1, actions.systemSettings)
        assertTrue(out.lines.isEmpty())
    }

    @Test
    fun `si no hay ajustes que abrir, se dice en vez de callar`() {
        val out = run(SettingsCommand, "settings", ctx(actions = Actions(result = false)))
        assertEquals(Role.ERROR, out.lines.first().second)
    }

    // ------------------------------------------------------------------ sugerencias

    @Test
    fun `un handle mal escrito sugiere el bueno`() {
        val out = run(KillCommand, "kill whatsap-bsns", ctx())
        val mensaje = out.lines.first().first
        assertTrue(mensaje.contains("not found"))
        assertTrue(mensaje.contains("did you mean whatsapp-bsns?"))
    }
}
