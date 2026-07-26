package dev.tty.platform.apps

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import dev.tty.core.apps.AppActions
import dev.tty.core.apps.AppCatalog
import dev.tty.core.apps.AppEntry
import dev.tty.core.apps.AppKiller
import dev.tty.core.apps.KillMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * El catálogo de apps del dispositivo sobre `LauncherApps`, y las acciones que se ejercen sobre
 * ellas. Implementa las dos interfaces que `core/` declara ([AppCatalog] y [AppActions]).
 *
 * Por qué `LauncherApps` y no `PackageManager.queryIntentActivities` (architecture.md §4.1):
 * devuelve etiqueta, `ComponentName`, `ApplicationInfo` y perfil en una sola llamada, y es la API
 * pensada para launchers. Sigue necesitando el `<queries>` con MAIN+LAUNCHER del manifest: sin él
 * `getActivityList` devuelve una lista vacía o recortada, y ese es el fallo que no se ve en
 * desarrollo y aparece en el dispositivo real.
 *
 * **El `UserHandle` no cabe en [AppEntry]**, que es Kotlin puro a propósito. Por eso aquí se guarda
 * un índice `AppEntry -> LauncherActivityInfo`: es lo que permite reconstruir componente y perfil al
 * lanzar sin que `core/` sepa que existen los perfiles de trabajo.
 */
class LauncherAppsCatalog(
    context: Context,
    private val scope: CoroutineScope,
) : AppCatalog, AppActions {

    private val appContext: Context = context.applicationContext

    /**
     * `getSystemService` devuelve `Object`: se usa `as?` y no `as` para que un dispositivo sin el
     * servicio dé un catálogo vacío —y un error legible en el terminal— en vez de una
     * `ClassCastException` que tumbe la pantalla de inicio.
     */
    private val launcherApps: LauncherApps? =
        appContext.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps

    /** El callback se entrega en el hilo principal; el trabajo se saca de ahí en [refreshAsync]. */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** La lista y su índice **cambian juntos**: van en el mismo objeto para que nunca se desparejen. */
    private class Snapshot(
        val apps: List<AppEntry>,
        val index: Map<AppEntry, LauncherActivityInfo>,
    )

    @Volatile
    private var snapshot: Snapshot? = null

    @Volatile
    private var registered = false

    /**
     * Frescura (functional.md §7.3, architecture.md §4.2): `LauncherApps.registerCallback` y **nada
     * más**. Cubre todos los perfiles y entrega una actualización como un único `onPackageChanged`.
     *
     * No se añade un `BroadcastReceiver` de `ACTION_PACKAGE_*`: llegarían eventos por duplicado, y
     * declarado en el manifest ya ni funcionaría —son broadcasts implícitos no exentos.
     */
    private val callback = object : LauncherApps.Callback() {

        override fun onPackageAdded(packageName: String?, user: UserHandle?) {
            refreshAsync()
        }

        override fun onPackageRemoved(packageName: String?, user: UserHandle?) {
            refreshAsync()
        }

        override fun onPackageChanged(packageName: String?, user: UserHandle?) {
            refreshAsync()
        }

        override fun onPackagesAvailable(
            packageNames: Array<out String>?,
            user: UserHandle?,
            replacing: Boolean,
        ) {
            refreshAsync()
        }

        override fun onPackagesUnavailable(
            packageNames: Array<out String>?,
            user: UserHandle?,
            replacing: Boolean,
        ) {
            refreshAsync()
        }
    }

    // --- Ciclo de vida -----------------------------------------------------------------------

    /**
     * Alta del callback. La ata la actividad a su ciclo de vida (`onStart`), no el constructor: un
     * callback vivo con la HOME parada es trabajo que nadie va a mirar.
     */
    fun register() {
        val la = launcherApps ?: return
        if (registered) return
        la.registerCallback(callback, mainHandler)
        registered = true
    }

    /** Baja del callback (`onStop`). Idempotente: llamarla dos veces no es un error. */
    fun unregister() {
        val la = launcherApps ?: return
        if (!registered) return
        try {
            la.unregisterCallback(callback)
        } catch (e: IllegalArgumentException) {
            // Ya estaba dado de baja. No hay nada que hacer y desde luego no hay que crashear.
        }
        registered = false
    }

    // --- Catálogo ----------------------------------------------------------------------------

    /**
     * Relee el catálogo entero fuera del hilo principal. Se llama al arrancar y desde el callback.
     */
    suspend fun refresh() {
        withContext(Dispatchers.IO) { reload() }
    }

    override fun all(): List<AppEntry> {
        // Red de seguridad: si nadie ha llamado todavía a refresh(), se construye aquí mismo. Es
        // una llamada IPC (no I/O de disco) de unas decenas de ms; devolver una lista vacía sería
        // peor, porque `apps` diría "no apps" y `open` diría "not found" con el móvil lleno de
        // aplicaciones. El arranque llama a refresh() precisamente para que este camino no se use.
        return (snapshot ?: reload()).apps
    }

    private fun refreshAsync() {
        scope.launch { refresh() }
    }

    /**
     * Reconstruye lista e índice. `@Synchronized` porque el callback puede disparar varias recargas
     * seguidas al instalar un paquete: sin el cerrojo se harían N recorridos IPC simultáneos para
     * llegar al mismo resultado.
     */
    @Synchronized
    private fun reload(): Snapshot {
        val la = launcherApps
        if (la == null) {
            val empty = Snapshot(emptyList(), emptyMap())
            snapshot = empty
            return empty
        }

        val index = LinkedHashMap<AppEntry, LauncherActivityInfo>()
        val seen = HashSet<String>()

        val profiles: List<UserHandle> = try {
            la.profiles
        } catch (e: RuntimeException) {
            emptyList()
        }

        for (profile in profiles) {
            val activities: List<LauncherActivityInfo> = try {
                // El primer argumento es el paquete a filtrar: null = todos.
                la.getActivityList(null, profile)
            } catch (e: RuntimeException) {
                // Un perfil bloqueado o en modo silencioso puede protestar. Se salta ese perfil,
                // no el catálogo entero.
                emptyList()
            }
            for (info in activities) {
                val entry = entryOf(info) ?: continue
                // Un mismo componente puede venir de varios perfiles (trabajo, clonado). Se queda
                // el primero —`profiles` empieza por el perfil personal— porque `AppEntry` no
                // puede distinguirlos y dos filas idénticas en `apps` serían ambigüedad gratuita.
                // Los perfiles de trabajo/privado están fuera de alcance (architecture.md §4.1).
                if (!seen.add(entry.component)) continue
                index[entry] = info
            }
        }

        // El orden del catálogo es el del handle, que es lo que el usuario escribe y lee.
        val fresh = Snapshot(index.keys.sortedBy { it.handle }, index)
        snapshot = fresh
        return fresh
    }

    /** Traduce una actividad de lanzamiento a la entrada que entiende `core/`. */
    private fun entryOf(info: LauncherActivityInfo): AppEntry? {
        // Tipos de plataforma: se comprueban de verdad en vez de con `!!`. Si el sistema devuelve
        // una entrada rota, se descarta esa app; el catálogo sigue.
        val component = info.componentName ?: return null
        val applicationInfo = info.applicationInfo ?: return null
        val label = info.label?.toString()?.trim().orEmpty()

        return AppEntry(
            // Sin etiqueta legible se cae al paquete: una fila sin nombre no se puede escribir ni
            // leer. `AppEntry.handle` hace la misma caída por su cuenta.
            label = label.ifEmpty { component.packageName },
            packageName = component.packageName,
            component = component.flattenToString(),
            isSystem = (applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
        )
    }

    // --- Acciones ----------------------------------------------------------------------------

    /**
     * Lanza la app (architecture.md §4.3). `startMainActivity` soporta perfiles y añade
     * `FLAG_ACTIVITY_NEW_TASK` por su cuenta: no hay que componer el intent a mano, y por eso no se
     * usa `getLaunchIntentForPackage`, que además puede devolver `null` para apps instaladas.
     *
     * **Nunca lanza excepción**: devuelve `false` y el comando imprime el error. Una excepción sin
     * capturar aquí crashea la actividad HOME, que es funcionalmente un móvil bloqueado.
     */
    override fun open(app: AppEntry): Boolean {
        val la = launcherApps ?: return false
        val snap = snapshot ?: reload()

        // Si la entrada viene de un catálogo anterior (se recargó entre resolver y lanzar), el
        // índice ya no la tiene: se reintenta por componente, que es lo único estable entre
        // recargas. El repaso lineal solo ocurre en ese caso raro.
        val info = snap.index[app]
            ?: snap.index.entries.firstOrNull { it.key.component == app.component }?.value
            ?: return false

        return try {
            la.startMainActivity(info.componentName, info.user, null, null)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        } catch (e: RuntimeException) {
            // Red de seguridad. Apps archivadas (Android 15+) y fabricantes con restricciones
            // propias fallan de formas que no están documentadas. Distinguirlas es Fase 1
            // (`ApplicationInfo.isArchived`, API 35+), no Fase 0.
            false
        }
    }

    // --- Fase 1: los efectos que pasan por un diálogo del sistema -----------------------------

    /**
     * `uninstall`. No existe la desinstalación silenciosa sin ser device owner: el diálogo del
     * sistema **es** el límite aceptado de la §12, no una carencia de la implementación.
     *
     * `ACTION_DELETE` en vez de `PackageInstaller.uninstall`: no devuelve resultado, pero tampoco
     * hace falta —el fin de la desinstalación se detecta por `LauncherApps.Callback.onPackageRemoved`,
     * que además ya refresca el catálogo—. `ACTION_UNINSTALL_PACKAGE` está deprecado desde API 29.
     */
    override fun requestUninstall(app: AppEntry): Boolean =
        launch(
            Intent(Intent.ACTION_DELETE, Uri.fromParts("package", app.packageName, null)),
        )

    /**
     * `info -o` y `kill`. Multiperfil de verdad sería `startAppDetailsActivity`, pero eso exige el
     * `LauncherActivityInfo` vivo; aquí basta el intent, que funciona igual para el perfil
     * principal — el único que el catálogo expone hoy.
     */
    override fun openAppSettings(app: AppEntry): Boolean =
        launch(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", app.packageName, null),
            ),
        )

    /**
     * `settings`. `ACTION_HOME_SETTINGS` primero porque lleva directo a la pantalla que importa
     * —elegir launcher—, que es la razón de existir del comando: sin él no hay forma de volver al
     * launcher anterior. Si el fabricante no la resuelve, se cae a la lista de apps por defecto y,
     * en último término, a los ajustes generales.
     */
    override fun openSystemSettings(): Boolean =
        launch(Intent(Settings.ACTION_HOME_SETTINGS)) ||
            launch(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) ||
            launch(Intent(Settings.ACTION_SETTINGS))

    /**
     * Lanza un intent del sistema. `FLAG_ACTIVITY_NEW_TASK` es obligatorio: el contexto es el de
     * aplicación, y además el launcher declara `taskAffinity=""`.
     *
     * Devuelve `false` en lugar de propagar. Una excepción aquí crashea la actividad HOME, que es
     * funcionalmente un móvil bloqueado (§16).
     */
    private fun launch(intent: Intent): Boolean = try {
        appContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    } catch (e: RuntimeException) {
        false
    }
}

/**
 * La única implementación de `AppKiller` que la plataforma permite hoy.
 *
 * `ActivityManager.killBackgroundProcesses()` dejó de afectar a otras apps en Android 14 y **falla
 * en silencio**, así que lo honesto es no llamarlo: se abre la pantalla donde está el botón y el
 * comando dice en su mensaje quién tiene que pulsarlo.
 *
 * Está separada del catálogo para que un futuro backend con privilegios (Shizuku) entre aquí, y
 * solo aquí, sin tocar `kill` (architecture.md §4.4).
 */
class SystemDialogKiller(private val actions: AppActions) : AppKiller {
    override val mode = KillMode.SYSTEM_DIALOG
    override fun requestStop(app: AppEntry): Boolean = actions.openAppSettings(app)
}
