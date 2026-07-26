package dev.tty.core.script

/**
 * Un script: un nombre y una lista de líneas (functional.md §8.1).
 *
 * Cada línea es un comando del vocabulario, **exactamente igual** que si se hubiera escrito en el
 * prompt. No hay un lenguaje de scripting: hay las mismas órdenes, guardadas.
 */
data class Script(
    val name: String,
    val lines: List<String>,
) {
    /** Las líneas que se ejecutan de verdad: los comentarios no cuentan (§8.3). */
    val executable: List<String> get() = lines.filter { !isComment(it) }

    companion object {
        fun isComment(line: String): Boolean = line.trimStart().startsWith("#")
    }
}

/** Por qué un nombre de script no vale. Cada caso tiene su mensaje: `invalid name` no sirve. */
enum class NameProblem(val message: String) {
    Empty("needs a name"),
    TooLong("name is too long — 32 characters max"),
    BadStart("name must start with a letter"),
    BadChars("name allows lowercase letters, digits, - and _ only"),
    Reserved("name is already a command"),
}

/**
 * Validación de nombres de script (functional.md §8.2).
 *
 * Minúsculas, dígitos, guion y guion bajo, empezando por letra, máximo 32 caracteres. Se rechaza
 * **antes** de entrar en modo grabación: descubrir que el nombre no vale después de teclear cinco
 * líneas es la peor forma de enterarse.
 *
 * Y un nombre que coincida con un comando existente se rechaza siempre (§8.4). Si se permitiera, un
 * script llamado `rm` sería la forma trivial de hacer que un verbo de confianza haga otra cosa.
 */
object ScriptName {

    const val MAX_LENGTH = 32

    private val PATTERN = Regex("^[a-z][a-z0-9_-]*$")

    fun validate(name: String, isReserved: (String) -> Boolean): NameProblem? = when {
        name.isEmpty() -> NameProblem.Empty
        name.length > MAX_LENGTH -> NameProblem.TooLong
        !name.first().isLetter() || name.first() !in 'a'..'z' -> NameProblem.BadStart
        !PATTERN.matches(name) -> NameProblem.BadChars
        isReserved(name) -> NameProblem.Reserved
        else -> null
    }

    /**
     * Si el nombre es seguro para usarse como nombre de fichero.
     *
     * Es redundante con [validate] —la regex ya excluye `/`, `.` y todo lo demás— y eso es
     * deliberado: la contención por ruta canónica de `platform/` es el segundo cinturón, y este es
     * el primero. Un nombre llega desde el prompt del usuario y acaba en el sistema de ficheros.
     */
    fun isSafeFileName(name: String): Boolean =
        PATTERN.matches(name) && name.length <= MAX_LENGTH && name != "." && name != ".."
}

/**
 * El almacén de scripts. Lo implementa `platform/` con un fichero por script.
 *
 * `clear` **nunca** los toca: vacía el scrollback, no la configuración del usuario.
 */
interface ScriptStore {
    suspend fun list(): List<Script>
    suspend fun read(name: String): Script?
    suspend fun write(script: Script): Boolean
    suspend fun delete(name: String): Boolean

    /** Siembra los ejemplos de la primera ejecución. No pisa lo que ya exista. */
    suspend fun seedExamples()
}
