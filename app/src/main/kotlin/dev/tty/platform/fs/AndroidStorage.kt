package dev.tty.platform.fs

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import dev.tty.core.command.builtin.FileSystemAccess
import dev.tty.core.fs.Cage
import java.io.IOException
import java.nio.file.Path

/**
 * Lo único de los comandos de fichero que es de Android: **cuál es la raíz y si hay permiso**.
 *
 * Todo lo demás —la jaula, las operaciones, los quince verbos— vive en `core/` con `java.nio.file`,
 * que está en el API público desde el nivel 26. Por eso el motor de ficheros se testea entero sin
 * emulador, contra un directorio temporal real.
 *
 * La raíz es `/storage/emulated/0` (`/sdcard`). Fuera de ahí no hay nada que ofrecer: `/` y `/sys`
 * están denegados por SELinux, `/data` por permisos de directorio, y `/proc` va con `hidepid=2` —
 * que es la razón de que no exista un comando `ps`.
 */
class AndroidStorage(context: Context) : FileSystemAccess {

    private val appContext: Context = context.applicationContext

    /**
     * La jaula sobre el almacenamiento compartido.
     *
     * Si el almacenamiento no está montado —tarjeta expulsada, arranque raro— se cae al directorio
     * privado de la app, que siempre existe y nunca necesita permisos. Un launcher que crashea
     * porque no encuentra `/sdcard` es un móvil bloqueado; uno que arranca con un shell casi vacío
     * es un mal día.
     */
    override val cage: Cage = Cage(resolveRoot())

    private fun resolveRoot(): Path {
        val shared = Environment.getExternalStorageDirectory()
        return try {
            if (shared != null && Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
                shared.toPath()
            } else {
                appContext.filesDir.toPath()
            }
        } catch (e: IOException) {
            appContext.filesDir.toPath()
        }
    }

    /**
     * `MANAGE_EXTERNAL_STORAGE`. **No es un runtime permission**: no se pide con
     * `requestPermissions()`, se concede en una pantalla de Ajustes y se comprueba al volver.
     */
    override fun hasStorageAccess(): Boolean = Environment.isExternalStorageManager()

    /**
     * Abre la pantalla donde se concede.
     *
     * El `Uri` `package:` es **obligatorio** en la acción dirigida. Algunos fabricantes no la
     * resuelven, y por eso hay respaldo a la lista global: quedarse sin abrir nada dejaría al
     * usuario con un comando que dice «run 'mount'» y un `mount` que no hace nada.
     */
    override fun requestStorageAccess(): Boolean {
        val targeted = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.fromParts("package", appContext.packageName, null),
        )
        return launch(targeted) || launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
    }

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
