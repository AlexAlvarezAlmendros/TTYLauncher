package dev.tty.platform.store

import android.content.Context
import android.util.AtomicFile
import androidx.core.util.writeText
import dev.tty.core.Limits
import dev.tty.core.output.Line
import dev.tty.core.scrollback.ScrollbackFormat
import dev.tty.core.output.Role
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

/**
 * El scrollback en disco (functional.md §5.5, architecture.md §6.1).
 *
 * **Fichero de texto plano append-only en `noBackupFilesDir`.** Ni DataStore —reescribe el payload
 * entero en cada escritura— ni SQLite: no hay una sola consulta que justifique un motor.
 * `noBackupFilesDir` queda excluido **automáticamente** de la copia en la nube y de la transferencia
 * entre dispositivos, que es justo lo que pide la §5.5 para un registro de lo que el usuario hace
 * con su teléfono.
 *
 * Formato de línea: **el rol, un espacio y el texto crudo** — `OUTPUT hola`. El rol nunca lleva
 * espacios; el texto sí puede. El prefijo visible (`>`, `!`, `…`) **no** se persiste: sale del rol
 * al pintar, así que cambiar los prefijos no invalida el historial guardado.
 *
 * Todo lo que toca disco es `suspend` y va a `Dispatchers.IO`. Nunca en el hilo principal: un ANR en
 * la actividad HOME es indistinguible de un móvil bloqueado.
 */
class ScrollbackStore(
    context: Context,
    private val scope: CoroutineScope,
) {

    private val file = File(context.applicationContext.noBackupFilesDir, FILE_NAME)

    /** Líneas ya codificadas esperando a la escritura diferida. Protegida por su propio cerrojo. */
    private val pending = ArrayDeque<String>()

    /** Serializa todo acceso al fichero: append, compactación, lectura y borrado. */
    private val fileLock = Mutex()

    /** La escritura diferida en curso. Cada línea nueva la cancela y arma otra. */
    @Volatile
    private var flushJob: Job? = null

    /** Cuenta aproximada de líneas en disco. Solo decide cuándo compactar. */
    private var linesOnDisk = 0

    // --- Lectura -----------------------------------------------------------------------------

    /**
     * Carga el historial persistido **en orden cronológico** (la más antigua primero), listo para
     * `Scrollback.restore`.
     *
     * Es tolerante por diseño: un `append` interrumpido por la muerte del proceso deja la última
     * línea a medias, y una línea sin rol reconocible se lee como [Role.OUTPUT] en vez de tirarse.
     * Perder texto del usuario para castigar un fichero imperfecto sería el peor negocio posible.
     *
     * Si el fichero venía pasado de tamaño o con una cola truncada, se aprovecha para compactarlo:
     * es el momento barato de hacerlo, porque los datos ya están leídos.
     */
    suspend fun load(): List<Pair<String, Role>> = withContext(Dispatchers.IO) {
        fileLock.withLock {
            val read = readRaw()
            if (read.truncatedTail || read.total > Limits.SCROLLBACK_LINES) writeAll(read.lines)
            linesOnDisk = read.lines.size
            read.lines.mapNotNull { ScrollbackFormat.decode(it) }
        }
    }

    // --- Escritura ---------------------------------------------------------------------------

    /**
     * Encola líneas y arma la escritura diferida (~1s, [Limits.SCROLLBACK_FLUSH_DELAY_MS]).
     *
     * **No toca disco y vuelve inmediatamente**: se puede llamar desde el hilo principal justo
     * después de ejecutar un comando.
     */
    fun append(lines: List<Line>) {
        if (lines.isEmpty()) return
        synchronized(pending) {
            for (line in lines) pending.addLast(ScrollbackFormat.encode(line))
        }
        schedule()
    }

    /**
     * Vuelca lo pendiente. Termina en `flush()` y no en `fsync`: frente a un kill del sistema basta,
     * porque los bytes ya entregados al kernel sobreviven a la muerte del proceso (architecture.md
     * §6.1). `fsync` solo protege de un corte de corriente.
     */
    suspend fun flush() {
        write(fsync = false)
    }

    /**
     * Vuelca **y** fuerza a disco. Su sitio es `onStop()` —no `onPause()`, que no ofrece tiempo
     * suficiente— y la compactación. Ni `onStop` ni `onDestroy` están garantizados: la garantía real
     * es la escritura diferida, esto es el refuerzo.
     */
    suspend fun sync() {
        write(fsync = true)
    }

    /**
     * Borra memoria pendiente y disco. Es la mitad de `clear`, **el único borrado real del
     * producto** (§6.2): la otra mitad la hace `Scrollback.clear()`.
     */
    suspend fun clear() {
        flushJob?.cancel()
        flushJob = null
        synchronized(pending) { pending.clear() }
        withContext(Dispatchers.IO + NonCancellable) {
            fileLock.withLock {
                // AtomicFile.delete() se lleva también el `.new`/`.bak` que pueda haber dejado una
                // compactación: borrar solo el fichero base dejaría un respaldo resucitable.
                try {
                    AtomicFile(file).delete()
                } catch (e: RuntimeException) {
                    // Nada que borrar, o el sistema de ficheros se queja. `clear` no puede fallar
                    // de forma ruidosa: la línea de confirmación ya se imprimió.
                }
                if (file.exists()) file.delete()
                linesOnDisk = 0
            }
        }
    }

    private fun schedule() {
        // La escritura es diferida, no por línea: escribir en cada línea multiplicaría por diez las
        // aperturas de fichero mientras se teclea, y no compra ninguna garantía extra.
        flushJob?.cancel()
        flushJob = scope.launch {
            delay(Limits.SCROLLBACK_FLUSH_DELAY_MS)
            flush()
        }
    }

    /**
     * El camino caliente: un `FileOutputStream` en modo append.
     *
     * `NonCancellable` porque el lote ya se ha sacado de [pending]: si la cancelación abortara a
     * mitad, esas líneas no estarían ni en la cola ni en el fichero.
     */
    private suspend fun write(fsync: Boolean) = withContext(Dispatchers.IO + NonCancellable) {
        fileLock.withLock {
            val batch: List<String> = synchronized(pending) {
                if (pending.isEmpty()) emptyList() else pending.toList().also { pending.clear() }
            }
            if (batch.isEmpty()) return@withLock

            try {
                val parent = file.parentFile
                if (parent != null && !parent.exists()) parent.mkdirs()
                // El argumento con nombre no compila: el constructor es de Java (architecture §6.1).
                FileOutputStream(file, true).use { out ->
                    out.write(encodeBlock(batch).toByteArray())
                    out.flush()
                    if (fsync) out.fd.sync()
                }
                linesOnDisk += batch.size
            } catch (e: IOException) {
                // El scrollback en memoria no se ve afectado y la HOME sigue viva. Un disco lleno no
                // puede convertirse en un crash de la pantalla de inicio.
            }

            // La compactación va aquí, fuera del camino de append y siempre después de escribir.
            if (linesOnDisk > Limits.SCROLLBACK_LINES) compactLocked()
        }
    }

    /**
     * Recorte a [Limits.SCROLLBACK_LINES] reescribiendo el fichero entero con `AtomicFile`.
     *
     * **`AtomicFile` no se usa en el camino caliente** justamente porque reescribe todo: aquí, que
     * es donde hay que sustituir el fichero de golpe, es exactamente la herramienta correcta.
     */
    private fun compactLocked() {
        val read = readRaw()
        writeAll(read.lines)
        linesOnDisk = read.lines.size
    }

    private fun writeAll(lines: List<String>) {
        try {
            // `AtomicFile.writeText` es la extensión de core-ktx (androidx.core.util). Equivale a
            // startWrite/finishWrite con failWrite en el catch, sin escribir el ceremonial.
            AtomicFile(file).writeText(encodeBlock(lines))
        } catch (e: IOException) {
            // El fichero anterior sigue intacto: AtomicFile es todo o nada. Se reintentará en la
            // siguiente compactación.
        }
    }

    // --- Formato -----------------------------------------------------------------------------

    /**
     * Una línea del scrollback es **una línea del fichero**: un salto de línea colado en el texto
     * rompería el formato al releer, así que se aplana a espacio.
     */

    private fun encodeBlock(lines: List<String>): String =
        if (lines.isEmpty()) "" else lines.joinToString(separator = "\n", postfix = "\n")

    /** Lectura tolerante de una línea: sin rol conocido, es [Role.OUTPUT] con el texto entero. */

    // --- Lectura cruda -----------------------------------------------------------------------

    private class RawRead(
        /** Las últimas [Limits.SCROLLBACK_LINES] líneas completas, en orden cronológico. */
        val lines: List<String>,
        /** Cuántas líneas completas tenía el fichero. Decide si toca compactar. */
        val total: Int,
        /** Si había un fragmento final sin `\n`, es decir un append interrumpido. */
        val truncatedTail: Boolean,
    )

    private fun readRaw(): RawRead {
        // `AtomicFile.openRead()` es también quien restaura el respaldo heredado de una compactación
        // interrumpida en API < 30. Se abre y se cierra solo por ese efecto, antes de leer a pelo.
        try {
            AtomicFile(file).openRead().close()
        } catch (e: IOException) {
            // No existe todavía: no hay nada que restaurar ni que leer.
            return RawRead(emptyList(), 0, false)
        }

        val complete = endsWithNewline()
        val kept = ArrayDeque<String>()
        var total = 0

        try {
            file.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    kept.addLast(line)
                    total++
                    // Se guarda una línea de más para poder descartar la cola truncada sin perder
                    // una línea buena.
                    while (kept.size > Limits.SCROLLBACK_LINES + 1) kept.removeFirst()
                }
            }
        } catch (e: IOException) {
            // Se devuelve lo leído hasta el fallo: medio historial es mejor que ninguno.
        }

        val truncatedTail = !complete && kept.isNotEmpty()
        if (truncatedTail) {
            kept.removeLast()
            total--
        }
        while (kept.size > Limits.SCROLLBACK_LINES) kept.removeFirst()
        return RawRead(kept.toList(), total, truncatedTail)
    }

    /**
     * Si el fichero termina en `\n`. Lo que no termina en `\n` es un append que se quedó a medias, y
     * ese fragmento se descarta al leer.
     */
    private fun endsWithNewline(): Boolean {
        val length = file.length()
        if (length == 0L) return true
        return try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(length - 1)
                raf.read() == NEWLINE
            }
        } catch (e: IOException) {
            // Si no se puede comprobar, no se descarta nada: quedarse corto pierde datos del
            // usuario, y pasarse solo enseña una línea a medias.
            true
        }
    }

    private companion object {
        const val FILE_NAME = "scrollback.log"
        val NEWLINE = '\n'.code
        val ROLES: Map<String, Role> = Role.entries.associateBy { it.name }
    }
}
