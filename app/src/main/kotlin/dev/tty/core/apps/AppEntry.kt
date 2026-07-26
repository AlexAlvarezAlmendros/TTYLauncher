package dev.tty.core.apps

/**
 * Una app instalada que expone una actividad de lanzamiento.
 *
 * Es Kotlin puro a propósito: `platform/` la construye desde `LauncherActivityInfo`, y `core/` la
 * resuelve y la ordena sin saber nada de Android.
 */
data class AppEntry(
    /** El nombre legible: `Nova Launcher`. */
    val label: String,
    /** El nombre de paquete: `com.teslacoilsw.launcher`. */
    val packageName: String,
    /** El componente que se lanza. Opaco para `core/`. */
    val component: String,
    val isSystem: Boolean = false,
) {
    /**
     * El identificador que se escribe en el prompt: `nova-launcher`.
     *
     * Si la etiqueta no tiene un solo carácter alfanumérico —pasa con apps cuyo nombre es un
     * logotipo o un ideograma— se cae al paquete, para que la app siga siendo alcanzable en vez de
     * quedarse con un handle vacío que no se puede escribir.
     */
    val handle: String = handleOf(label).ifEmpty { handleOf(packageName) }

    companion object {

        /**
         * El nombre de la app en minúsculas con los caracteres no alfanuméricos convertidos en
         * guiones (functional.md §7): `"Nova Launcher"` → `nova-launcher`.
         *
         * Detalles que la especificación no dice y hay que fijar en algún sitio:
         * - Los guiones consecutivos se colapsan y no quedan sueltos en los extremos, porque
         *   `"WhatsApp  (beta)"` no debe dar `whatsapp---beta-`.
         * - Se conservan las letras acentuadas: `Cámara` → `cámara`. Convertirlas en guiones haría
         *   ilegible el handle de media pantalla de inicio en español.
         *
         * Puede devolver una cadena vacía si no hay ningún carácter alfanumérico; quien llama
         * decide qué hacer con eso (ver [AppEntry.handle]).
         */
        fun handleOf(label: String): String {
            val slug = buildString {
                var pendingHyphen = false
                for (c in label.lowercase()) {
                    if (c.isLetterOrDigit()) {
                        if (pendingHyphen && isNotEmpty()) append('-')
                        pendingHyphen = false
                        append(c)
                    } else {
                        pendingHyphen = true
                    }
                }
            }
            return slug
        }
    }
}

/** El catálogo de apps del dispositivo. Lo implementa `platform/` con `LauncherApps`. */
interface AppCatalog {
    /** Todas las apps con actividad de lanzamiento, ya ordenadas alfabéticamente por handle. */
    fun all(): List<AppEntry>
}

/**
 * Los efectos sobre una app. Cada uno lo implementa `platform/`; `core/` solo los invoca.
 *
 * Todos devuelven `Boolean` y **ninguno lanza**: un fallo de la plataforma se convierte en una línea
 * de error legible, nunca en una excepción que crashee la actividad HOME.
 */
interface AppActions {
    /** Lanza la app. */
    fun open(app: AppEntry): Boolean

    /** Abre el diálogo de desinstalación del sistema. No desinstala por su cuenta: no puede. */
    fun requestUninstall(app: AppEntry): Boolean

    /** Abre la pantalla de ajustes de esa app. */
    fun openAppSettings(app: AppEntry): Boolean

    /** Abre los ajustes generales de Android. */
    fun openSystemSettings(): Boolean
}

/**
 * Detener una app.
 *
 * Vive en su propia interfaz **a propósito** (architecture.md §4.4). Desde Android 14
 * `killBackgroundProcesses()` solo afecta a los procesos de la propia app y sobre cualquier otra
 * falla en silencio, así que la única implementación posible hoy abre el diálogo del sistema. El
 * día que exista un backend con privilegios (Shizuku) se añade una segunda implementación **sin que
 * el comando cambie de nombre ni de sintaxis** — que es justo lo que la §12 del funcional pide
 * mantener abierto.
 */
interface AppKiller {

    /** Cómo se detiene una app con esta implementación. Lo usa `kill` para decir la verdad. */
    val mode: KillMode

    /**
     * Pide la detención. Con [KillMode.SYSTEM_DIALOG] eso significa abrir la pantalla donde está el
     * botón, no detener nada: quien pulsa es el usuario.
     */
    fun requestStop(app: AppEntry): Boolean
}

enum class KillMode {
    /** Lo único posible sin privilegios desde Android 14. */
    SYSTEM_DIALOG,

    /** Reservado para un backend con privilegios. Hoy no lo implementa nadie. */
    DIRECT,
}
