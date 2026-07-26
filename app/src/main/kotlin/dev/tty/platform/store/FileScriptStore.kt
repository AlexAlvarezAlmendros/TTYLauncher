package dev.tty.platform.store

import android.content.Context
import dev.tty.core.script.Script
import dev.tty.core.script.ScriptName
import dev.tty.core.script.ScriptStore
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Un fichero de texto por script, en `filesDir/scripts`.
 *
 * **No en `noBackupFilesDir`**, a diferencia del scrollback: un script es configuración que el
 * usuario ha escrito a mano, y perderla al cambiar de móvil sería una pérdida real. El scrollback
 * es un registro de uso y por eso se excluye de las copias; esto es lo contrario.
 *
 * `clear` no los toca. Vacía el scrollback, no lo que el usuario ha creado.
 */
class FileScriptStore(context: Context) : ScriptStore {

    private val dir: File = File(context.applicationContext.filesDir, "scripts")

    override suspend fun list(): List<Script> = withContext(Dispatchers.IO) {
        val files = dir.listFiles() ?: return@withContext emptyList()
        files.asSequence()
            .filter { it.isFile && ScriptName.isSafeFileName(it.name) }
            .mapNotNull { readFile(it) }
            .sortedBy { it.name }
            .toList()
    }

    override suspend fun read(name: String): Script? = withContext(Dispatchers.IO) {
        val file = fileFor(name) ?: return@withContext null
        if (!file.isFile) return@withContext null
        readFile(file)
    }

    override suspend fun write(script: Script): Boolean = withContext(Dispatchers.IO) {
        val file = fileFor(script.name) ?: return@withContext false
        try {
            dir.mkdirs()
            file.writeText(script.lines.joinToString("\n", postfix = "\n"))
            true
        } catch (e: IOException) {
            false
        }
    }

    override suspend fun delete(name: String): Boolean = withContext(Dispatchers.IO) {
        val file = fileFor(name) ?: return@withContext false
        try {
            file.delete()
        } catch (e: SecurityException) {
            false
        }
    }

    /**
     * Los dos ejemplos de la primera ejecución (functional.md §11).
     *
     * Son **ejemplos, no configuración**: se pueden borrar sin consecuencias, y no se vuelven a
     * sembrar. Existen para que `script ls` y `script cat` tengan algo que enseñar el primer día.
     */
    override suspend fun seedExamples() = withContext(Dispatchers.IO) {
        if (dir.exists()) return@withContext
        dir.mkdirs()
        write(
            Script(
                "focus",
                listOf(
                    "# silences what's noisy and opens what matters",
                    "kill instagram",
                    "kill tiktok",
                    "open \$1",
                ),
            ),
        )
        write(
            Script(
                "morning",
                listOf(
                    "# what the day starts with",
                    "open calendar",
                ),
            ),
        )
        Unit
    }

    private fun readFile(file: File): Script? = try {
        Script(file.name, file.readText().lines().dropLastWhile { it.isEmpty() })
    } catch (e: IOException) {
        null
    }

    /**
     * El fichero de un script, o `null` si el nombre no es seguro.
     *
     * Dos cinturones, y el segundo es el que importa: la validación por regex ya excluye `/` y `..`,
     * pero además se comprueba **contención por ruta canónica con separador final**. Sin el
     * separador, `/scripts` casaría con `/scripts_evil`. El nombre viene del prompt del usuario y
     * acaba en el sistema de ficheros: un cinturón solo no basta.
     */
    private fun fileFor(name: String): File? {
        if (!ScriptName.isSafeFileName(name)) return null
        return try {
            val base = dir.canonicalFile
            val file = File(base, name).canonicalFile
            if (file.path.startsWith(base.path + File.separator)) file else null
        } catch (e: IOException) {
            null
        }
    }
}
