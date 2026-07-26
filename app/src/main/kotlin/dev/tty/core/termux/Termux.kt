package dev.tty.core.termux

/**
 * Por qué falló una ejecución en Termux.
 *
 * **Los tres primeros son las tres puertas del onboarding** (functional.md §9.4), y el producto no
 * construye ningún asistente: **el mensaje de error es el onboarding**. Por eso cada puerta tiene su
 * propio mensaje y no uno genérico — el usuario tiene que saber cuál de las tres está cerrada.
 */
sealed interface TermuxError {

    /** El mensaje tal y como se imprime, ya con el verbo delante. */
    fun message(verb: String): String

    /** Puerta 1: no está, o está la versión de Google Play, que no sirve. */
    data object NotInstalled : TermuxError {
        override fun message(verb: String) =
            "$verb: termux not installed (use the F-Droid or GitHub build)"
    }

    /** Puerta 2: el permiso `RUN_COMMAND` no está concedido. */
    data object PermissionDenied : TermuxError {
        override fun message(verb: String) =
            "$verb: termux: RUN_COMMAND permission not granted"
    }

    /**
     * Puerta 3: `allow-external-apps` no está a `true`.
     *
     * **No lanza ninguna excepción**: `startService` devuelve con éxito y Termux solo enseña una
     * notificación. Solo se detecta por el `errmsg` del resultado o porque no llega nada antes del
     * timeout. Es la razón de que siempre se envíe un `PendingIntent`.
     */
    data object ExternalAppsBlocked : TermuxError {
        override fun message(verb: String) =
            "$verb: termux: could not start RunCommandService — is allow-external-apps set?"
    }

    /** Pasaron los 15 segundos sin respuesta. */
    data object Timeout : TermuxError {
        // El límite sale de Limits, no de un literal: dos sitios se desincronizan.
        override fun message(verb: String) =
            "$verb: termux: no response in ${dev.tty.core.Limits.TERMUX_TIMEOUT_MS / 1000}s — still running there"
    }

    /** El binario no existe en Termux. `tmux` no viene instalado: hay que `pkg install tmux`. */
    data class MissingBinary(val binary: String) : TermuxError {
        override fun message(verb: String) =
            "$verb: $binary not found in termux — run 'pkg install $binary' there"
    }

    data class Failed(val detail: String) : TermuxError {
        override fun message(verb: String) = "$verb: termux: $detail"
    }
}

/**
 * Lo que devuelve una ejecución.
 *
 * `exitCode` puede ser `null`: Termux no siempre lo manda, y fingir un 0 sería mentir sobre si el
 * comando funcionó.
 */
data class TermuxResult(
    val stdout: List<String>,
    val stderr: List<String>,
    val exitCode: Int?,
    /** Termux recortó la salida a 100 KB. Se dice: recortar en silencio sería mentir. */
    val truncated: Boolean = false,
) {
    val ok: Boolean get() = exitCode == null || exitCode == 0
}

/**
 * La puerta a Termux. Es la **única** escotilla del vocabulario cerrado, y es explícita
 * (functional.md §9.1).
 *
 * La implementación vive en `platform/` porque toda ella es Android: un servicio de otra app, un
 * `PendingIntent` y un permiso que define un tercero. Lo que se queda en `core/` es la construcción
 * de los argumentos y la clasificación de los errores, que es lo que de verdad hay que testear.
 */
interface TermuxClient {

    /** Comprueba las tres puertas sin ejecutar nada. `null` si están las tres abiertas. */
    suspend fun check(): TermuxError?

    /**
     * Ejecuta un binario con sus argumentos.
     *
     * Cada elemento de [args] es **un argv independiente**: no hay parseo de shell. Pasar
     * `"ls -la"` como un solo argumento sería un fichero llamado «ls -la».
     */
    suspend fun run(path: String, args: List<String>): Result<TermuxResult>
}

/**
 * Las rutas y los argumentos, en Kotlin puro para poder testearlos sin Termux delante.
 *
 * Los literales de esta API **no son estables**: Termux no publica release desde mayo de 2025 y su
 * `targetSdk` sigue en 28. Al subir de versión hay que reverificarlos contra el repo y degradar con
 * un mensaje claro, nunca en silencio (functional.md §16).
 */
object TermuxCommands {

    const val PREFIX = "/data/data/com.termux/files/usr/bin"

    const val BASH = "$PREFIX/bash"
    const val TMUX = "$PREFIX/tmux"

    /** Una línea suelta para `sh`. `-c` porque es lo que convierte la cadena en un comando. */
    fun shell(line: String): List<String> = listOf("-c", line)

    /**
     * Las cuatro órdenes de tmux que el producto usa (functional.md §9.3).
     *
     * `-S` fija el socket: el servidor que arranca un `app-shell` en segundo plano y el que arranca
     * una sesión de terminal solo comparten servidor si comparten socket. Sin fijarlo, `tmux build`
     * desde el launcher podría hablar con un tmux distinto del que el usuario ve en Termux.
     */
    object Tmux {
        const val SOCKET = "/data/data/com.termux/files/usr/tmp/tty"

        private fun base(): List<String> = listOf("-S", SOCKET)

        fun hasSession(session: String): List<String> =
            base() + listOf("has-session", "-t", session)

        fun newSession(session: String): List<String> =
            base() + listOf("new-session", "-d", "-s", session)

        /**
         * Envía teclas. El comando entero es **un solo argv**, y `Enter` va aparte: así es como tmux
         * distingue el texto de la pulsación.
         */
        fun sendKeys(session: String, keys: String): List<String> =
            base() + listOf("send-keys", "-t", session, keys, "Enter")

        /**
         * Fotografía el pane.
         *
         * `-p` imprime a stdout, `-J` une las líneas que el terminal partió —sin él, una línea larga
         * llega cortada por donde no toca— y `-S -N` pide las últimas N.
         */
        fun capturePane(session: String, lines: Int): List<String> =
            base() + listOf("capture-pane", "-p", "-J", "-t", session, "-S", "-$lines")
    }
}
