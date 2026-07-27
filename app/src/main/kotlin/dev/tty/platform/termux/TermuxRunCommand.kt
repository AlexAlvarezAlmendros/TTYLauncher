package dev.tty.platform.termux

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.core.content.ContextCompat
import dev.tty.core.Limits
import dev.tty.core.termux.RawTermuxResult
import dev.tty.core.termux.TermuxClient
import dev.tty.core.termux.TermuxError
import dev.tty.core.termux.TermuxFailure
import dev.tty.core.termux.TermuxParsing
import dev.tty.core.termux.TermuxResult
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * El cliente de Termux por `RUN_COMMAND`.
 *
 * **Es el punto de mayor riesgo del proyecto** (functional.md §16): esta API no es estable, Termux
 * no publica release desde mayo de 2025 y su `targetSdk` sigue en 28. Todos los literales están
 * verificados contra el código de `termux-app`; al subir de versión hay que reverificarlos y
 * **degradar con un mensaje claro, nunca en silencio**.
 *
 * Este fichero solo habla con la plataforma: extrae campos del `Bundle` y los pasa a
 * [TermuxParsing], que es quien decide qué error es cuál — y vive en `core/` para poder probarlo.
 *
 * Las trampas evitadas por construcción:
 *
 * 1. La clave del bundle es **`"result"`**, no `"result_bundle"`. Leer la otra devuelve siempre
 *    `null` y el launcher parece colgado hasta el timeout.
 * 2. El `PendingIntent` necesita **`FLAG_MUTABLE`** en API 31+, porque es Termux quien le añade el
 *    extra. Con `FLAG_IMMUTABLE` el bundle llega vacío.
 * 3. **`allow-external-apps=false` no lanza ninguna excepción.** Solo se distingue de un comando
 *    lento porque en toda la sesión no ha contestado nada — ver [everAnswered].
 * 4. La firma se lee con la API que corresponde al nivel de Android: `signingInfo` es de API 28 y
 *    este proyecto declara `minSdk 26`. Leerla sin más lanzaría un `LinkageError`, que **no** es
 *    una `RuntimeException` y se llevaría por delante la actividad HOME.
 */
class TermuxRunCommand(context: Context) : TermuxClient {

    private val appContext: Context = context.applicationContext

    /**
     * Los registros de `PendingIntent` sobreviven al proceso, así que la secuencia no puede empezar
     * en 1 cada arranque: casaría con lo que dejó la sesión anterior.
     */
    private val requestCodes = AtomicInteger((SystemClock.elapsedRealtime().toInt() and 0xFFFF) + 1)

    private val pending = HashMap<Int, (Reply) -> Unit>()

    /** Qué le llega a quien espera. Distinguir las causas es lo que evita culpar a la puerta 3. */
    private sealed interface Reply {
        data class Answer(val bundle: Bundle?) : Reply
        data class Boom(val cause: Throwable) : Reply
        data object Aborted : Reply
    }

    /**
     * Si Termux ha contestado **alguna vez** en esta sesión.
     *
     * Es lo que separa «no hay permiso de apps externas» de «el comando tarda»: si ya contestó una
     * vez, `allow-external-apps` está demostrablemente puesto, y un silencio posterior es un
     * timeout de verdad. Sin esto, un `sh ./gradlew build` acusaría a la configuración de Termux.
     */
    @Volatile
    private var everAnswered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            everAnswered = true
            val id = intent?.getIntExtra(EXTRA_CORRELATION, -1) ?: -1
            val bundle = intent?.getBundleExtra(EXTRA_RESULT_BUNDLE)
            val callback = synchronized(pending) { pending.remove(id) }
            callback?.invoke(Reply.Answer(bundle))
        }
    }

    private var registered = false

    @Volatile
    private var closed = false

    /**
     * Cómo pedir el permiso. Lo inyecta la actividad: un permiso en runtime **exige** una Activity y
     * el contenedor solo tiene el contexto de aplicación.
     */
    var permissionRequester: (() -> Unit)? = null

    /** Solo se pide una vez por sesión: quien ya dijo que no, no quiere el diálogo cada vez. */
    @Volatile
    private var permissionAsked = false

    /** El digest de la firma, cacheado: consultarlo cuesta una IPC y no cambia en toda la sesión. */
    @Volatile
    private var cachedDigest: String? = null

    @Volatile
    private var digestChecked = false

    /**
     * Alta del receptor. `RECEIVER_NOT_EXPORTED` porque quien lo dispara es un `PendingIntent`
     * nuestro: Termux lo envía, pero se ejecuta con **nuestra** identidad.
     */
    @Synchronized
    fun register() {
        if (registered || closed) return
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(ACTION_RESULT),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        registered = true
    }

    /**
     * Cierre definitivo. **No se llama en `onStop`**, y eso es deliberado: en un launcher, `onStop`
     * ocurre al apagar la pantalla o al abrir cualquier app, y dar de baja el receptor ahí abortaría
     * el `sh` que el usuario acaba de lanzar justo antes de irse a mirar el resultado.
     */
    @Synchronized
    fun close() {
        closed = true
        permissionRequester = null
        if (registered) {
            runCatching { appContext.unregisterReceiver(receiver) }
            registered = false
        }
        val waiting = synchronized(pending) { pending.values.toList().also { pending.clear() } }
        waiting.forEach { it(Reply.Aborted) }
    }

    // ------------------------------------------------------------------ las tres puertas

    /**
     * Comprueba las dos primeras puertas sin ejecutar nada.
     *
     * La tercera —`allow-external-apps`— **no se puede comprobar de antemano**: no hay API, no hay
     * excepción, y el fichero de Termux no es legible desde aquí. Solo se detecta ejecutando.
     */
    override suspend fun check(): TermuxError? {
        val signature = withContext(Dispatchers.IO) { signatureDigest() }
            ?: return TermuxError.NotInstalled

        // Allowlist, no denylist: solo los builds de F-Droid, GitHub y los desarrolladores traen el
        // soporte de plugins. Un Termux reempaquetado por cualquiera no pasa (§9.4).
        if (signature !in ACCEPTED_DIGESTS) return TermuxError.NotInstalled

        val granted = ContextCompat.checkSelfPermission(appContext, PERMISSION)
        if (granted != PackageManager.PERMISSION_GRANTED) {
            if (!permissionAsked) {
                permissionAsked = true
                // El diálogo se lanza desde el hilo principal: es una Activity la que lo abre.
                withContext(Dispatchers.Main) { permissionRequester?.invoke() }
            }
            return TermuxError.PermissionDenied
        }
        return null
    }

    /**
     * El digest SHA-256 del firmante, en hexadecimal y mayúsculas.
     *
     * **La lectura se bifurca por versión.** `PackageInfo.signingInfo` y `GET_SIGNING_CERTIFICATES`
     * son de API 28, y el `minSdk` de este proyecto es 26: en un Android 8 la lectura lanzaría un
     * `LinkageError` —que no es `RuntimeException`— y se llevaría por delante la pantalla de inicio.
     * El `catch (e: LinkageError)` es la red por si algo más se cuela.
     *
     * `null` si no está instalado **o si no es visible**: sin el `<queries>` del manifest, Android
     * responde `NameNotFoundException` aunque Termux esté ahí. Por eso el `<queries>` no es opcional.
     */
    private fun signatureDigest(): String? {
        if (digestChecked) return cachedDigest

        val pm = appContext.packageManager
        val bytes: ByteArray? = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageInfo(TERMUX_PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES)
                    .signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(TERMUX_PACKAGE, PackageManager.GET_SIGNATURES)
                    .signatures?.firstOrNull()?.toByteArray()
            }
        } catch (e: PackageManager.NameNotFoundException) {
            null
        } catch (e: RuntimeException) {
            null
        } catch (e: LinkageError) {
            null
        }

        cachedDigest = bytes?.let {
            MessageDigest.getInstance("SHA-256").digest(it).joinToString("") { b -> "%02X".format(b) }
        }
        digestChecked = true
        return cachedDigest
    }

    // ------------------------------------------------------------------ ejecución

    override suspend fun run(path: String, args: List<String>): Result<TermuxResult> {
        register()

        val id = requestCodes.incrementAndGet()
        var pendingIntent: PendingIntent? = null

        try {
            val reply = withTimeoutOrNull(Limits.TERMUX_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    // Lo PRIMERO: si algo de abajo lanza, la entrada del mapa ya tiene quien la
                    // limpie. Registrarlo al final dejaría la continuación retenida para siempre.
                    cont.invokeOnCancellation {
                        synchronized(pending) { pending.remove(id) }
                    }
                    synchronized(pending) {
                        // Solo reanuda quien consigue sacar la entrada: es la única operación
                        // atómica que hay, y evita el doble `resume`.
                        pending[id] = { reply -> cont.resume(reply) }
                    }

                    try {
                        val callback = Intent(ACTION_RESULT).apply {
                            setPackage(appContext.packageName)
                            // El `data` participa en `filterEquals`: hace único el PendingIntent sin
                            // depender solo del requestCode.
                            data = Uri.parse("tty://termux/$id")
                            putExtra(EXTRA_CORRELATION, id)
                        }
                        val flags = PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_UPDATE_CURRENT or
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                PendingIntent.FLAG_MUTABLE
                            } else {
                                0
                            }
                        pendingIntent = PendingIntent.getBroadcast(appContext, id, callback, flags)

                        val intent = Intent(ACTION_RUN_COMMAND).apply {
                            component = ComponentName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
                            putExtra(EXTRA_COMMAND_PATH, path)
                            putExtra(EXTRA_ARGUMENTS, args.toTypedArray())
                            putExtra(EXTRA_WORKDIR, TERMUX_HOME)
                            // Siempre en segundo plano: en modo terminal-session `stdout` trae el
                            // transcript de la sesión y `stderr` va mezclado. No se pasa RUNNER a
                            // la vez: si no coinciden, gana RUNNER en silencio.
                            putExtra(EXTRA_BACKGROUND, true)
                            putExtra(EXTRA_COMMAND_LABEL, "tty")
                            putExtra(EXTRA_PENDING_INTENT, pendingIntent)
                        }

                        // `startService` devuelve null sin lanzar si el componente no existe: es lo
                        // que pasaría si una versión futura de Termux renombrara el servicio.
                        val started = appContext.startService(intent)
                        if (started == null) {
                            resumeOnce(id, Reply.Boom(IllegalStateException("service not found")))
                        }
                    } catch (e: Throwable) {
                        resumeOnce(id, Reply.Boom(e))
                    }
                }
            }

            return when (reply) {
                is Reply.Answer ->
                    if (reply.bundle == null) {
                        Result.failure(TermuxFailure(TermuxError.Failed("empty result")))
                    } else {
                        TermuxParsing.classify(path, extract(reply.bundle))
                    }

                is Reply.Boom -> Result.failure(TermuxFailure(bootError(reply.cause)))
                Reply.Aborted -> Result.failure(TermuxFailure(TermuxError.Failed("cancelled")))

                // `withTimeoutOrNull` expiró. Si Termux contestó alguna vez en esta sesión,
                // `allow-external-apps` está puesto y esto es un comando lento de verdad.
                null -> Result.failure(
                    TermuxFailure(
                        if (everAnswered) TermuxError.Timeout else TermuxError.ExternalAppsBlocked,
                    ),
                )
            }
        } finally {
            synchronized(pending) { pending.remove(id) }
            // Se cancela en TODOS los caminos, no solo en el de cancelación: si no, cada fallo de
            // arranque dejaría un registro mutable vivo en system_server.
            runCatching { pendingIntent?.cancel() }
        }
    }

    private fun resumeOnce(id: Int, reply: Reply) {
        val callback = synchronized(pending) { pending.remove(id) }
        callback?.invoke(reply)
    }

    /**
     * Separa lo que architecture.md §7.4 dice que no hay que confundir.
     *
     * `ForegroundServiceStartNotAllowedException` extiende `IllegalStateException`, así que un
     * `catch` genérico enmascararía los dos casos. Y si aparece, indica una llamada desde segundo
     * plano —un bug propio— no un problema de configuración de Termux.
     */
    private fun bootError(cause: Throwable): TermuxError = when (cause) {
        is SecurityException -> TermuxError.PermissionDenied
        is IllegalStateException -> TermuxError.Failed("cannot start RunCommandService: ${cause.message ?: "denied"}")
        else -> TermuxError.Failed("could not start termux: ${cause::class.simpleName}")
    }

    /**
     * Saca los campos del `Bundle` y nada más. **Aquí no se decide nada**: clasificar es cosa de
     * `core/`, que es donde se puede testear.
     */
    private fun extract(bundle: Bundle): RawTermuxResult = RawTermuxResult(
        stdout = bundle.getString(KEY_STDOUT).orEmpty(),
        stderr = bundle.getString(KEY_STDERR).orEmpty(),
        exitCode = if (bundle.containsKey(KEY_EXIT_CODE)) bundle.getInt(KEY_EXIT_CODE) else null,
        errmsg = bundle.getString(KEY_ERRMSG),
        // `putString` de un número, no `putInt`: leerlo con getInt daría 0 en silencio.
        stdoutOriginalLength = bundle.getString(KEY_STDOUT_LEN)?.toIntOrNull(),
        stderrOriginalLength = bundle.getString(KEY_STDERR_LEN)?.toIntOrNull(),
    )

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
        const val KEY_STDOUT_LEN = "stdout_original_length"
        const val KEY_STDERR_LEN = "stderr_original_length"

        /** Nuestro, no de Termux: viaja en el `PendingIntent` para correlacionar la respuesta. */
        const val ACTION_RESULT = "dev.tty.TERMUX_RESULT"
        const val EXTRA_CORRELATION = "dev.tty.correlation"

        /** Los builds con soporte de plugins. El de Google Play está congelado y no entra. */
        val ACCEPTED_DIGESTS = setOf(
            // F-Droid
            "228FB2CFE90831C1499EC3CCAF61E96E8E1CE70766B9474672CE427334D41C42",
            // GitHub
            "B6DA01480EEFD5FBF2CD3771B8D1021EC791304BDD6C4BF41D3FAABAD48EE5E1",
            // Termux Devs
            "F7A038EB551F1BE8FDF388686B784ABAB4552A5D82DF423E3D8F1B5CBE1C69AE",
        )
    }
}
