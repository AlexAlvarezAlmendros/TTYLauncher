package dev.tty.platform.termux

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dev.tty.core.Limits
import dev.tty.core.command.builtin.TermuxException
import dev.tty.core.termux.TermuxClient
import dev.tty.core.termux.TermuxError
import dev.tty.core.termux.TermuxResult
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * El cliente de Termux por `RUN_COMMAND`.
 *
 * **Es el punto de mayor riesgo del proyecto** (functional.md §16): esta API no es estable, Termux
 * no publica release desde mayo de 2025 y su `targetSdk` sigue en 28. Todos los literales de aquí
 * están verificados contra el código de `termux-app`; al subir de versión hay que reverificarlos y
 * **degradar con un mensaje claro, nunca en silencio**.
 *
 * Las tres trampas que este fichero existe para no pisar:
 *
 * 1. La clave del bundle es **`"result"`**, no `"result_bundle"`. Leer la otra devuelve siempre
 *    `null` y el launcher parece colgado hasta el timeout.
 * 2. El `PendingIntent` necesita **`FLAG_MUTABLE`** en API 31+, porque es Termux quien le añade el
 *    extra. Con `FLAG_IMMUTABLE` el bundle llega vacío.
 * 3. **`allow-external-apps=false` no lanza ninguna excepción.** `startService` devuelve con éxito
 *    y Termux solo enseña una notificación. Por eso se envía siempre un `PendingIntent` y por eso
 *    un timeout se interpreta como esa puerta cerrada.
 */
class TermuxRunCommand(context: Context) : TermuxClient {

    private val appContext: Context = context.applicationContext

    private val requestCodes = AtomicInteger(1)

    /** Las respuestas pendientes, por id de correlación. */
    private val pending = HashMap<Int, (android.os.Bundle?) -> Unit>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val id = intent?.getIntExtra(EXTRA_CORRELATION, -1) ?: -1
            val bundle = intent?.getBundleExtra(EXTRA_RESULT_BUNDLE)
            val callback = synchronized(pending) { pending.remove(id) }
            callback?.invoke(bundle)
        }
    }

    @Volatile
    private var registered = false

    /**
     * Cómo pedir el permiso. Lo inyecta la actividad, porque un permiso en runtime **exige** una
     * Activity y el contenedor solo tiene el contexto de aplicación.
     */
    var permissionRequester: (() -> Unit)? = null

    /**
     * Solo se pide una vez por sesión.
     *
     * Sin esto, cada `sh` que fallara volvería a abrir el diálogo, y un usuario que ya dijo que no
     * se encontraría el mismo diálogo cada vez que escribe. El mensaje de error sigue estando: es
     * el onboarding (§9.4), y el diálogo es la ayuda, no la explicación.
     */
    @Volatile
    private var permissionAsked = false

    /**
     * Alta del receptor. `RECEIVER_NOT_EXPORTED` porque quien lo dispara es un `PendingIntent`
     * nuestro: Termux lo envía, pero se ejecuta con **nuestra** identidad.
     */
    fun register() {
        if (registered) return
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(ACTION_RESULT),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        registered = true
    }

    fun unregister() {
        if (!registered) return
        runCatching { appContext.unregisterReceiver(receiver) }
        registered = false
        // Nadie va a responder ya: se despierta a los que esperan en vez de dejarlos hasta el
        // timeout.
        val waiting = synchronized(pending) { pending.values.toList().also { pending.clear() } }
        waiting.forEach { it(null) }
    }

    // ------------------------------------------------------------------ las tres puertas

    /**
     * Comprueba las dos primeras puertas sin ejecutar nada.
     *
     * La tercera —`allow-external-apps`— **no se puede comprobar de antemano**: no hay API, no hay
     * excepción, y el fichero de Termux no es legible desde aquí. Solo se detecta ejecutando.
     */
    override suspend fun check(): TermuxError? {
        val signature = signatureDigest() ?: return TermuxError.NotInstalled

        // El build de Google Play está congelado y sin soporte de plugins: aunque el usuario
        // conceda todo, RUN_COMMAND no va a funcionar. Decirlo es mejor que un error genérico.
        if (signature == PLAY_DIGEST) return TermuxError.NotInstalled

        val granted = ContextCompat.checkSelfPermission(appContext, PERMISSION)
        if (granted != PackageManager.PERMISSION_GRANTED) {
            if (!permissionAsked) {
                permissionAsked = true
                permissionRequester?.invoke()
            }
            return TermuxError.PermissionDenied
        }

        return null
    }

    /**
     * El digest SHA-256 del firmante de Termux, en hexadecimal y mayúsculas.
     *
     * `null` si no está instalado **o si no es visible**: sin el `<queries>` del manifest, Android
     * responde con `NameNotFoundException` aunque Termux esté ahí, y los dos casos son
     * indistinguibles desde aquí. Por eso el `<queries>` no es opcional.
     */
    private fun signatureDigest(): String? = try {
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val info = appContext.packageManager.getPackageInfo(TERMUX_PACKAGE, flags)
        val signers = info.signingInfo?.apkContentsSigners
        val first = signers?.firstOrNull()
        if (first == null) {
            null
        } else {
            MessageDigest.getInstance("SHA-256")
                .digest(first.toByteArray())
                .joinToString("") { "%02X".format(it) }
        }
    } catch (e: PackageManager.NameNotFoundException) {
        null
    } catch (e: RuntimeException) {
        null
    }

    // ------------------------------------------------------------------ ejecución

    override suspend fun run(path: String, args: List<String>): Result<TermuxResult> {
        check()?.let { return Result.failure(TermuxException(it)) }
        register()

        val id = requestCodes.incrementAndGet()
        val bundle = withTimeoutOrNull(Limits.TERMUX_TIMEOUT_MS) {
            awaitResult(id, path, args)
        }

        // Descarte del pendiente: si llega tarde, no hay a quién dárselo y no debe quedarse.
        synchronized(pending) { pending.remove(id) }

        if (bundle == null) {
            // Ni excepción ni resultado. Es, casi siempre, la tercera puerta.
            return Result.failure(TermuxException(TermuxError.ExternalAppsBlocked))
        }
        return parse(bundle, args)
    }

    private suspend fun awaitResult(
        id: Int,
        path: String,
        args: List<String>,
    ): android.os.Bundle? = suspendCancellableCoroutine { cont ->
        synchronized(pending) {
            pending[id] = { bundle -> if (cont.isActive) cont.resume(bundle) }
        }

        val callback = Intent(ACTION_RESULT).apply {
            setPackage(appContext.packageName)
            putExtra(EXTRA_CORRELATION, id)
        }
        val flags = PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pendingIntent = PendingIntent.getBroadcast(appContext, id, callback, flags)

        val intent = Intent(ACTION_RUN_COMMAND).apply {
            component = ComponentName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
            putExtra(EXTRA_COMMAND_PATH, path)
            putExtra(EXTRA_ARGUMENTS, args.toTypedArray())
            putExtra(EXTRA_WORKDIR, TERMUX_HOME)
            // Siempre en segundo plano: en modo terminal-session `stdout` trae el transcript de la
            // sesión, no la salida real, y `stderr` va mezclado. Y no se pasa RUNNER a la vez:
            // si no coinciden, gana RUNNER en silencio.
            putExtra(EXTRA_BACKGROUND, true)
            putExtra(EXTRA_COMMAND_LABEL, "tty")
            putExtra(EXTRA_PENDING_INTENT, pendingIntent)
        }

        try {
            // El launcher está en primer plano cuando el usuario escribe, así que está exento de
            // las restricciones de arranque de servicios en segundo plano. Si esto falla, es un bug
            // de arquitectura —una llamada desde background— no un problema de Termux.
            appContext.startService(intent)
        } catch (e: RuntimeException) {
            synchronized(pending) { pending.remove(id) }
            if (cont.isActive) cont.resume(null)
        }

        cont.invokeOnCancellation {
            synchronized(pending) { pending.remove(id) }
            runCatching { pendingIntent.cancel() }
        }
    }

    /**
     * Lee el bundle de resultado.
     *
     * Trampa de tipos: `exitCode` y `err` son `Int`, pero `stdout_original_length` viene con
     * `putString` — es un número guardado como texto. Leerlo con `getInt` devolvería 0 en silencio.
     */
    private fun parse(bundle: android.os.Bundle, args: List<String>): Result<TermuxResult> {
        val errmsg = bundle.getString(KEY_ERRMSG)
        if (!errmsg.isNullOrBlank()) {
            // El literal de Termux para la tercera puerta menciona la propiedad por su nombre.
            if (errmsg.contains("allow-external-apps", ignoreCase = true)) {
                return Result.failure(TermuxException(TermuxError.ExternalAppsBlocked))
            }
            return Result.failure(TermuxException(TermuxError.Failed(errmsg.lines().first())))
        }

        val stdout = bundle.getString(KEY_STDOUT).orEmpty()
        val stderr = bundle.getString(KEY_STDERR).orEmpty()
        val exitCode = if (bundle.containsKey(KEY_EXIT_CODE)) bundle.getInt(KEY_EXIT_CODE) else null

        // Un binario que no existe se distingue del resto: `tmux` no viene instalado en Termux, y
        // decir «pkg install tmux» es la diferencia entre un error útil y uno opaco.
        if (exitCode != null && exitCode != 0 && stderr.contains("No such file or directory")) {
            val binary = args.firstOrNull()?.substringAfterLast('/') ?: "the binary"
            return Result.failure(TermuxException(TermuxError.MissingBinary(binary)))
        }

        return Result.success(
            TermuxResult(
                stdout = stdout.lines().dropLastWhile { it.isEmpty() },
                stderr = stderr.lines().dropLastWhile { it.isEmpty() },
                exitCode = exitCode,
            ),
        )
    }

    private companion object {
        const val TERMUX_PACKAGE = "com.termux"
        const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
        const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
        const val PERMISSION = "com.termux.permission.RUN_COMMAND"
        const val TERMUX_HOME = "/data/data/com.termux/files/home"

        const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
        const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        const val EXTRA_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
        const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"

        /** **`"result"`**, no `"result_bundle"`. Es el error más probable de toda la integración. */
        const val EXTRA_RESULT_BUNDLE = "result"

        const val KEY_STDOUT = "stdout"
        const val KEY_STDERR = "stderr"
        const val KEY_EXIT_CODE = "exitCode"
        const val KEY_ERRMSG = "errmsg"

        /** Nuestro, no de Termux: viaja en el `PendingIntent` para correlacionar la respuesta. */
        const val ACTION_RESULT = "dev.tty.TERMUX_RESULT"
        const val EXTRA_CORRELATION = "dev.tty.correlation"

        /** El build de Google Play: congelado y sin soporte de plugins. */
        const val PLAY_DIGEST = "738F0A30A04D3C8A1BE304AF18D0779BCF3EA88FB60808F657A3521861C2EBF9"
    }
}
