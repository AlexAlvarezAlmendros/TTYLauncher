package dev.tty.platform

import android.content.Context
import dev.tty.core.Banner
import dev.tty.core.TerminalEngine
import dev.tty.core.apps.AppActions
import dev.tty.core.apps.AppCatalog
import dev.tty.core.command.Command
import dev.tty.core.command.CommandContext
import dev.tty.core.command.CommandRegistry
import dev.tty.core.command.DeviceInfo
import dev.tty.core.command.Session
import dev.tty.core.apps.AppKiller
import dev.tty.core.command.builtin.AppsCommand
import dev.tty.core.command.builtin.CatCommand
import dev.tty.core.command.builtin.CdCommand
import dev.tty.core.command.builtin.CpCommand
import dev.tty.core.command.builtin.DfCommand
import dev.tty.core.command.builtin.DuCommand
import dev.tty.core.command.builtin.FileSystemAccess
import dev.tty.core.command.builtin.FindCommand
import dev.tty.core.command.builtin.HeadCommand
import dev.tty.core.command.builtin.LsCommand
import dev.tty.core.command.builtin.MkdirCommand
import dev.tty.core.command.builtin.MountCommand
import dev.tty.core.command.builtin.MvCommand
import dev.tty.core.command.builtin.PwdCommand
import dev.tty.core.command.builtin.RmCommand
import dev.tty.core.command.builtin.TailCommand
import dev.tty.core.command.builtin.TouchCommand
import dev.tty.core.command.builtin.ClearCommand
import dev.tty.core.command.builtin.HelpCommand
import dev.tty.core.command.builtin.InfoCommand
import dev.tty.core.command.builtin.KillCommand
import dev.tty.core.command.builtin.OpenCommand
import dev.tty.core.command.builtin.ScriptCommand
import dev.tty.core.command.builtin.ShCommand
import dev.tty.core.command.builtin.SettingsCommand
import dev.tty.core.command.builtin.TmuxCommand
import dev.tty.core.command.builtin.UninstallCommand
import dev.tty.core.output.Line
import dev.tty.core.output.Role
import dev.tty.core.scrollback.Scrollback
import dev.tty.core.script.ScriptRunner
import dev.tty.core.script.ScriptStore
import dev.tty.core.termux.TermuxClient
import dev.tty.platform.apps.LauncherAppsCatalog
import dev.tty.platform.apps.SystemDialogKiller
import dev.tty.platform.fs.AndroidStorage
import dev.tty.platform.termux.TermuxRunCommand
import dev.tty.platform.store.FileScriptStore
import dev.tty.platform.store.ScrollbackStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * El grafo de dependencias entero, construido a mano y por constructor.
 *
 * **Sin Hilt, sin service locator, sin un solo singleton global** (architecture.md §1): cinco
 * conceptos y un usuario no pagan un contenedor de DI. Todo lo que hay aquí son `val` en orden de
 * dependencia, y quien mira este fichero ve el producto completo de un vistazo.
 *
 * La actividad crea uno y le ata el ciclo de vida: [onStart], [onStop] y [close].
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    /**
     * El ámbito de todo lo que el contenedor lanza por su cuenta: escritura diferida del scrollback
     * y recargas del catálogo.
     *
     * `Dispatchers.Default` y no `Dispatchers.Main` a propósito: el dispatcher principal vive en
     * `kotlinx-coroutines-android`, que no está declarado en el catálogo de versiones, y nada de lo
     * que se lanza aquí necesita el hilo de UI. `SupervisorJob` para que un fallo escribiendo no se
     * lleve por delante las recargas del catálogo.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** El scrollback en memoria: lo más reciente primero. Lo lee la UI. */
    val scrollback = Scrollback()

    private val store = ScrollbackStore(appContext, scope)

    /** El mismo objeto es catálogo y acciones: las dos interfaces las sirve `LauncherApps`. */
    private val launcherApps = LauncherAppsCatalog(appContext, scope)

    /**
     * Detener una app. Hoy solo abre el diálogo del sistema; el hueco de un backend con
     * privilegios (Shizuku) está aquí y en ningún otro sitio (architecture.md §4.4).
     */
    private val killer: AppKiller = SystemDialogKiller(launcherApps)

    /**
     * La jaula de rutas y el permiso de almacenamiento. Es lo único de los quince verbos de fichero
     * que es de Android: el resto vive en `core/` con `java.nio` y se testea sin emulador.
     */
    private val storage: FileSystemAccess = AndroidStorage(appContext)

    /**
     * Los scripts del usuario. Van en `filesDir`, no en `noBackupFilesDir`: son configuración
     * escrita a mano, y perderla al cambiar de móvil sí sería una pérdida.
     */
    private val scriptStore: ScriptStore = FileScriptStore(appContext)

    private val scriptRunner = ScriptRunner(scriptStore)

    /**
     * La única escotilla del vocabulario, y explícita. Es el punto de mayor riesgo del proyecto:
     * la API de RUN_COMMAND no es estable (architecture.md §7).
     */
    private val termuxClient = TermuxRunCommand(appContext)

    /** Los números del banner (functional.md §11). */
    val device: DeviceInfo = DeviceInfoImpl(launcherApps, scrollback)

    /**
     * Cada `clear` incrementa esto. Es lo que permite saber, después de ejecutar un comando, si el
     * scrollback se vació **a mitad** de la ejecución — ver [submit].
     */
    @Volatile
    private var generation = 0L

    private val session = object : Session {
        /** El único borrado real del producto (§6.2): memoria **y** disco. */
        override suspend fun clearScrollback() {
            scrollback.clear()
            store.clear()
            generation++
        }
    }

    /**
     * El vocabulario de la Fase 0. Añadir un verbo es una decisión de producto: esta lista es el
     * vocabulario cerrado, y lo que no está aquí no existe.
     */
    private val registry = CommandRegistry(
        listOf(
            HelpCommand,
            AppsCommand,
            OpenCommand,
            ClearCommand,
            // Fase 1
            KillCommand,
            UninstallCommand,
            InfoCommand,
            SettingsCommand,
            // Fase 2 — ficheros
            PwdCommand,
            CdCommand,
            LsCommand,
            CatCommand,
            HeadCommand,
            TailCommand,
            MkdirCommand,
            TouchCommand,
            RmCommand,
            MvCommand,
            CpCommand,
            DfCommand,
            DuCommand,
            FindCommand,
            MountCommand,
            // Fase 3
            ScriptCommand,
            // Fase 4
            ShCommand,
            TmuxCommand,
        ),
    )

    private val commandContext = object : CommandContext {
        override val catalog: AppCatalog = launcherApps
        override val actions: AppActions = launcherApps
        override val killer: AppKiller = this@AppContainer.killer
        override val files: FileSystemAccess = storage
        override val scripts: ScriptStore = scriptStore
        override val termux: TermuxClient = termuxClient
        override fun isReservedName(name: String): Boolean = registry.isReserved(name)
        // El motor se declara después: aquí solo se referencia, y esto se invoca en tiempo de
        // ejecución, no al construir. El tipo de `engine` va anotado explícitamente porque si no
        // la inferencia entra en bucle — el contexto necesita el motor y el motor el contexto.
        override fun startRecording(name: String) = this@AppContainer.engine.startRecording(name)
        override val session: Session = this@AppContainer.session
        override val device: DeviceInfo = this@AppContainer.device
        override val commands: List<Command> get() = registry.all
    }

    private val engine: TerminalEngine =
        TerminalEngine(registry, scrollback, commandContext, scriptRunner)

    // --- Ciclo de vida de la actividad --------------------------------------------------------

    /**
     * Carga el historial persistido en el scrollback y devuelve una foto de cómo queda, **lo más
     * reciente primero**, que es el orden en que lo pinta la lista.
     *
     * Se llama una sola vez, desde una corrutina: **nunca desde `onCreate` en el hilo principal**.
     * La UI pinta el prompt vacío primero y el historial aparece cuando llega (criterio 10). Lo
     * persistido se muestra ya presente, sin animación de entrada (§5.2).
     *
     * Se devuelve una copia y no la lista viva del scrollback: quien la reciba no debe verla cambiar
     * bajo los pies.
     */
    /**
     * Cómo pedir el permiso de Termux. Lo cablea la actividad: un permiso en runtime exige una
     * Activity, y aquí solo hay contexto de aplicación.
     */
    var termuxPermissionRequester: (() -> Unit)?
        get() = termuxClient.permissionRequester
        set(value) { termuxClient.permissionRequester = value }

    /** `…` mientras se graba un script, `>` el resto del tiempo (functional.md §8.2). */
    val promptSymbol: String get() = engine.promptSymbol

    suspend fun restore(): List<Line> {
        scrollback.restore(store.load())
        // Los dos ejemplos de la primera ejecución. No pisan nada y se pueden borrar.
        scriptStore.seedExamples()

        // Primera ejecución: no hay historial que enseñar, así que se imprime el banner con los
        // números reales del dispositivo (functional.md §11). La condición es «el scrollback está
        // vacío», no una marca en preferencias: después de un `clear` el usuario también se
        // encuentra una pantalla en blanco, y volver a ver qué es esto y `TYPE HELP` es
        // exactamente lo que hace falta ahí.
        if (scrollback.size == 0) {
            // El banner cuenta apps, así que el catálogo tiene que estar cargado antes.
            launcherApps.refresh()
            val banner = Banner.render(device).lines.map { (text, role) -> scrollback.add(text, role) }
            store.append(banner)
            firstRun = true
        }
        return scrollback.lines.toList()
    }

    @Volatile
    private var firstRun = false

    /**
     * El permiso de almacenamiento, en la primera ejecución y solo entonces (functional.md §11.4).
     *
     * Es la **única** concesión del producto a un onboarding, y está aceptada como tal: sin ella la
     * mitad del vocabulario no funciona. Si el usuario dice que no, `mount` la recupera y nada más
     * se rompe — los comandos de app siguen funcionando igual.
     */
    suspend fun requestStorageAccessOnFirstRun(): List<Line> {
        if (!firstRun) return emptyList()
        firstRun = false
        if (storage.hasStorageAccess()) return emptyList()

        val added = listOf(
            scrollback.add("file commands need access to storage", Role.STATUS),
            scrollback.add("opening all-files access in settings — or run 'mount' later", Role.STATUS),
        )
        store.append(added)
        storage.requestStorageAccess()
        return added
    }

    /**
     * `onStart` de la actividad: alta del callback de paquetes y una recarga del catálogo.
     *
     * La recarga de aquí es la que hace que [AppCatalog.all] no tenga que construirse a la primera
     * llamada, que ocurriría en el hilo principal.
     */
    fun onStart() {
        launcherApps.register()
        termuxClient.register()
        scope.launch { launcherApps.refresh() }
    }

    /**
     * `onStop` de la actividad: baja del callback y flush definitivo **con** `fsync`.
     *
     * Va en `onStop` y no en `onPause`, que no ofrece tiempo suficiente. Y es un refuerzo, no la
     * garantía: la garantía es la escritura diferida, porque el sistema mata el proceso, no la
     * actividad.
     */
    fun onStop() {
        launcherApps.unregister()
        termuxClient.unregister()
        scope.launch { store.sync() }
    }

    /** Cierre definitivo. Después de esto el contenedor no sirve: se construye otro. */
    fun close() {
        launcherApps.unregister()
        termuxClient.unregister()
        scope.cancel()
    }

    // --- Ejecución ----------------------------------------------------------------------------

    /**
     * Ejecuta una línea del prompt y persiste lo que produzca. Devuelve **las líneas nuevas**, de la
     * más antigua a la más reciente, para que la UI sepa cuáles animar.
     *
     * El caso raro que justifica el contador de generación: si el comando fue `clear`, el motor
     * añadió el eco **antes** de que el scrollback se vaciara, así que la lista devuelta contiene
     * líneas que ya no existen en memoria. Persistirlas resucitaría en disco justo lo que el usuario
     * acaba de borrar. Cuando eso pasa se persiste lo que quedó vivo, que son una o dos líneas.
     */
    suspend fun submit(input: String): List<Line> {
        val before = generation
        val added = engine.submit(input)
        if (added.isEmpty()) return added

        if (generation != before) {
            store.append(scrollback.chronological())
        } else {
            store.append(added)
        }
        return added
    }
}
