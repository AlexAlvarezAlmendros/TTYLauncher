package dev.tty.core.termux

/**
 * El resultado de Termux **tal y como viene**, ya sacado del `Bundle`.
 *
 * Existe para que la clasificación —la parte que decide qué error es cuál— viva en `core/` y se
 * pueda testear sin un `Bundle` de Android delante. `platform/` solo extrae campos; no decide nada.
 *
 * Trampa de tipos que este `data class` hace explícita: `exitCode` y `err` son enteros, pero
 * `stdoutOriginalLength` viene con `putString` — es un número guardado como texto. Leerlo con
 * `getInt` devolvería 0 en silencio.
 */
data class RawTermuxResult(
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int? = null,
    val errmsg: String? = null,
    /** Longitud original de `stdout` antes de que Termux la recortara. Viene como texto. */
    val stdoutOriginalLength: Int? = null,
    val stderrOriginalLength: Int? = null,
)

/**
 * Convierte el resultado crudo en algo que el producto pueda imprimir, o en el error que toque.
 *
 * Es Kotlin puro a propósito: aquí está toda la lógica de «qué ha pasado», que es justo lo que hay
 * que poder probar sin Termux instalado.
 */
object TermuxParsing {

    /**
     * Termux recorta `stdout` y `stderr` a 100 KB combinados. Cuando lo hace, **se dice**: una
     * salida recortada presentada como completa es una mentira, y la regla es degradar con un
     * mensaje claro, nunca en silencio (functional.md §16).
     */
    const val TRUNCATION_NOTICE = "output truncated by termux (100k)"

    private const val NOT_FOUND = ": command not found"

    fun classify(path: String, raw: RawTermuxResult): Result<TermuxResult> {
        val binary = path.substringAfterLast('/')

        val errmsg = raw.errmsg
        if (!errmsg.isNullOrBlank()) {
            if (errmsg.contains("allow-external-apps", ignoreCase = true)) {
                return Result.failure(TermuxFailure(TermuxError.ExternalAppsBlocked))
            }
            // Termux valida el ejecutable **antes** de lanzarlo: si no está, lo dice aquí, con la
            // ruta dentro del mensaje — no en `stderr`, que ni existe en ese caso.
            if (errmsg.contains(path)) {
                return Result.failure(TermuxFailure(TermuxError.MissingBinary(binary)))
            }
            return Result.failure(TermuxFailure(TermuxError.Failed(tidy(errmsg))))
        }

        // Un binario que falta **dentro de una línea de bash** se ve distinto: bash sale con 127 y
        // dice «command not found», no «No such file or directory».
        if (raw.exitCode == 127 && raw.stderr.contains(NOT_FOUND)) {
            // `bash: line 1: htop: command not found` — el nombre es lo que va justo ANTES del
            // «command not found», no lo que va después del primer «: », que es «line 1».
            val missing = raw.stderr.substringBefore(NOT_FOUND).substringAfterLast(": ").trim()
            return Result.failure(TermuxFailure(TermuxError.MissingBinary(missing.ifEmpty { binary })))
        }

        val truncated = (raw.stdoutOriginalLength ?: 0) > raw.stdout.length ||
            (raw.stderrOriginalLength ?: 0) > raw.stderr.length

        return Result.success(
            TermuxResult(
                stdout = raw.stdout.lines().dropLastWhile { it.isEmpty() },
                stderr = raw.stderr.lines().dropLastWhile { it.isEmpty() },
                exitCode = raw.exitCode,
                truncated = truncated,
            ),
        )
    }

    /**
     * Adapta una cadena de otra app al idioma del producto (§10): minúsculas, sin punto final.
     *
     * Los `errmsg` de Termux son frases de log en inglés con mayúscula inicial y punto — la única
     * cadena del terminal cuyo estilo no controla el producto. Se normaliza en vez de reenviarla
     * tal cual.
     */
    private fun tidy(errmsg: String): String =
        errmsg.lines().first()
            // Solo la primera frase: la segunda suele ser «Check logcat», que en un móvil de
            // diario no es una instrucción, es una burla.
            .substringBefore(". ")
            .trim()
            .trimEnd('.')
            .replaceFirstChar { it.lowercaseChar() }
            .take(120)
}

/** El envoltorio con el que un [TermuxError] viaja dentro de un `Result`. */
class TermuxFailure(val error: TermuxError) : Exception(error.message("termux"))
