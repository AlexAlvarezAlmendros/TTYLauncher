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

/** Los efectos sobre una app. Cada uno lo implementa `platform/`; `core/` solo los invoca. */
interface AppActions {
    /** Lanza la app. Devuelve `false` si la plataforma no pudo — nunca lanza excepción. */
    fun open(app: AppEntry): Boolean
}
