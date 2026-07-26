package dev.tty.core.script

/**
 * El modo grabación (functional.md §8.2).
 *
 * Escribir multilínea en un prompt de una línea necesita un **modo**, no un esquema de escapado.
 * Mientras dura, el símbolo del prompt pasa a `…` y las líneas **no se ejecutan**: solo se capturan.
 * Grabar `uninstall whatsapp` no puede desinstalar nada.
 *
 * Es estado del motor, no del intérprete: el intérprete ejecuta scripts ya guardados y no sabe que
 * exista una forma de crearlos.
 */
data class Recording(
    val name: String,
    val lines: List<String> = emptyList(),
) {
    fun capture(line: String): Recording = copy(lines = lines + line)

    val summary: String
        get() = "saved $name (${lines.size} ${if (lines.size == 1) "line" else "lines"})"

    companion object {
        /** La orden que guarda. No es un comando: solo significa esto dentro del modo. */
        const val END = "end"

        /** La que descarta. */
        const val ABORT = "abort"

        fun startedMessage(name: String) =
            "recording '$name' — one command per line, 'end' to save"

        const val ABORTED = "aborted"
    }
}
