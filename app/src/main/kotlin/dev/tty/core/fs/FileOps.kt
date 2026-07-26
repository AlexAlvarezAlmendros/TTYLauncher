package dev.tty.core.fs

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermission

/** Una entrada de directorio, ya leída. Kotlin puro: la UI no toca `java.nio`. */
data class Entry(
    val name: String,
    val isDirectory: Boolean,
    val isLink: Boolean,
    val size: Long,
    val modified: Long,
    /** Modo POSIX estilo `drwxr-xr-x`, o `null` si el sistema de ficheros no lo expone. */
    val mode: String?,
) {
    /** Los directorios se marcan con `/` al final, como en cualquier `ls`. */
    val display: String get() = if (isDirectory) "$name/" else name
}

/**
 * Las operaciones de fichero, sobre la jaula y en Kotlin puro.
 *
 * Está en `core/` a propósito: `java.nio.file` está en el API público de Android desde el nivel 26
 * —justo el `minSdk`— y también existe en la JVM, así que **todo esto se testea sin emulador**
 * contra un directorio temporal real. `platform/` solo aporta dos cosas que sí son de Android: cuál
 * es la raíz y si hay permiso para leerla.
 *
 * Ninguna función lanza: todas devuelven [FsResult]. Una excepción cruzando la frontera acaba en un
 * crash de la actividad HOME, que es funcionalmente un móvil bloqueado.
 */
object FileOps {

    /** Techo de lectura para `cat`, coherente con el límite de salida de un comando (§5.8). */
    const val MAX_READ_LINES = 2000

    /** Bytes que se miran para decidir si un fichero es binario. */
    private const val BINARY_PROBE = 8_000

    // ---------------------------------------------------------------- listar

    /**
     * Lista un directorio.
     *
     * `Files.newDirectoryStream`, **nunca** `File.listFiles()`: `listFiles()` devuelve `null` igual
     * para «no existe», «no es un directorio» y «sin permiso», y un shell tiene que distinguirlos.
     *
     * Orden: directorios primero, luego alfabético. Es lo que hace que un `ls` de una carpeta con
     * cien fotos siga siendo legible.
     */
    fun list(dir: Path, includeHidden: Boolean = false, long: Boolean = false): FsResult<List<Entry>> = try {
        val entries = Files.newDirectoryStream(dir).use { stream ->
            stream.mapNotNull { path ->
                val name = path.fileName?.toString() ?: return@mapNotNull null
                if (!includeHidden && name.startsWith(".")) return@mapNotNull null
                entryOf(path, name, long)
            }
        }
        FsResult.Success(entries.sortedWith(compareByDescending<Entry> { it.isDirectory }.thenBy { it.name }))
    } catch (e: IOException) {
        FsResult.Failure(FsError.from(e, dir))
    }

    private fun entryOf(path: Path, name: String, long: Boolean): Entry {
        // Sin seguir enlaces: un symlink roto tiene que aparecer en el listado, no hacerlo fallar.
        val attrs = try {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (e: IOException) {
            return Entry(name, isDirectory = false, isLink = false, size = 0, modified = 0, mode = null)
        }
        return Entry(
            name = name,
            isDirectory = attrs.isDirectory,
            isLink = attrs.isSymbolicLink,
            size = attrs.size(),
            modified = attrs.lastModifiedTime().toMillis(),
            mode = if (long) modeOf(path, attrs) else null,
        )
    }

    /**
     * El modo estilo `drwxr-xr-x`.
     *
     * En `/sdcard` los metadatos POSIX son **sintéticos**: desde Android 11 el almacenamiento
     * emulado va por FUSE, así que todo aparecerá con el mismo modo. No es un bug, y por eso `-l`
     * no es el modo por defecto.
     */
    private fun modeOf(path: Path, attrs: BasicFileAttributes): String? = try {
        val posix = Files.readAttributes(path, PosixFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        val p = posix.permissions()
        buildString {
            append(if (attrs.isSymbolicLink) 'l' else if (attrs.isDirectory) 'd' else '-')
            append(if (PosixFilePermission.OWNER_READ in p) 'r' else '-')
            append(if (PosixFilePermission.OWNER_WRITE in p) 'w' else '-')
            append(if (PosixFilePermission.OWNER_EXECUTE in p) 'x' else '-')
            append(if (PosixFilePermission.GROUP_READ in p) 'r' else '-')
            append(if (PosixFilePermission.GROUP_WRITE in p) 'w' else '-')
            append(if (PosixFilePermission.GROUP_EXECUTE in p) 'x' else '-')
            append(if (PosixFilePermission.OTHERS_READ in p) 'r' else '-')
            append(if (PosixFilePermission.OTHERS_WRITE in p) 'w' else '-')
            append(if (PosixFilePermission.OTHERS_EXECUTE in p) 'x' else '-')
        }
    } catch (e: IOException) {
        null
    } catch (e: UnsupportedOperationException) {
        null
    }

    // ---------------------------------------------------------------- leer

    /**
     * Lee un fichero de texto.
     *
     * Si es binario **no lo vomita**: dice qué es y cuánto ocupa (functional.md §6.3). Un `cat` de
     * un JPEG llenaría el scrollback de basura irrecuperable, y el scrollback es persistente.
     */
    fun read(path: Path, limit: Int = MAX_READ_LINES): FsResult<List<String>> {
        if (Files.isDirectory(path)) return FsResult.Failure(FsError.IsADirectory)
        val binary = looksBinary(path)
        if (binary is FsResult.Failure) return binary
        if ((binary as FsResult.Success).value) {
            val size = try {
                Files.size(path)
            } catch (e: IOException) {
                return FsResult.Failure(FsError.from(e, path))
            }
            return FsResult.Success(listOf("binary file, ${humanBytes(size)}"))
        }

        return try {
            Files.newBufferedReader(path, StandardCharsets.UTF_8).use { reader ->
                val lines = ArrayList<String>()
                while (lines.size < limit) {
                    val line = reader.readLine() ?: break
                    lines += line
                }
                FsResult.Success(lines)
            }
        } catch (e: CharacterCodingException) {
            FsResult.Success(listOf("binary file"))
        } catch (e: IOException) {
            FsResult.Failure(FsError.from(e, path))
        }
    }

    /** Las primeras `n` líneas. */
    fun head(path: Path, n: Int): FsResult<List<String>> = read(path, limit = n)

    /**
     * Las últimas `n` líneas, leyendo **hacia atrás** desde el final.
     *
     * Leer 400 MB enteros para sacar diez líneas no vale, y en un móvil con un log dentro es
     * exactamente el caso que se va a dar.
     */
    fun tail(path: Path, n: Int): FsResult<List<String>> {
        if (Files.isDirectory(path)) return FsResult.Failure(FsError.IsADirectory)
        val binary = looksBinary(path)
        if (binary is FsResult.Failure) return binary
        if ((binary as FsResult.Success).value) return FsResult.Success(listOf("binary file"))

        return try {
            Files.newByteChannel(path).use { channel ->
                val size = channel.size()
                val chunk = 8_192L
                val lines = ArrayDeque<String>()
                var pos = size
                var pending = ""
                var firstChunk = true

                while (pos > 0 && lines.size <= n) {
                    val read = minOf(chunk, pos)
                    pos -= read
                    val buffer = ByteBuffer.allocate(read.toInt())
                    channel.position(pos)
                    channel.read(buffer)
                    buffer.flip()

                    val text = decode(buffer) + pending
                    val parts = text.split("\n")

                    // La primera parte puede estar cortada por la mitad: se arrastra al trozo
                    // anterior, que es el que tiene su principio. Las demás sí son líneas enteras.
                    pending = parts.first()
                    var complete = parts.drop(1)

                    if (firstChunk) {
                        firstChunk = false
                        // Un fichero que termina en \n produce una última parte vacía que no es una
                        // línea. Solo puede pasar en el trozo final, que es el primero que se lee.
                        if (complete.isNotEmpty() && complete.last().isEmpty()) {
                            complete = complete.dropLast(1)
                        }
                    }
                    for (i in complete.indices.reversed()) lines.addFirst(complete[i])
                }
                // Al llegar al principio del fichero, lo que quedaba pendiente ya es una línea
                // entera: es la primera del fichero.
                if (pos == 0L && pending.isNotEmpty()) lines.addFirst(pending)

                // Se recorta al final y no mientras se lee: recortar dentro del bucle descartaba
                // justo las líneas del final, que son las que se piden.
                FsResult.Success(lines.toList().takeLast(n))
            }
        } catch (e: IOException) {
            FsResult.Failure(FsError.from(e, path))
        }
    }

    private fun decode(buffer: ByteBuffer): String {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        return decoder.decode(buffer).toString()
    }

    /** Heurística: un byte cero en los primeros KB es binario seguro. */
    private fun looksBinary(path: Path): FsResult<Boolean> = try {
        Files.newInputStream(path).use { input ->
            val probe = ByteArray(BINARY_PROBE)
            val read = input.read(probe)
            var binary = false
            for (i in 0 until maxOf(read, 0)) {
                if (probe[i] == 0.toByte()) {
                    binary = true
                    break
                }
            }
            FsResult.Success(binary)
        }
    } catch (e: IOException) {
        FsResult.Failure(FsError.from(e, path))
    }

    // ---------------------------------------------------------------- escribir

    fun mkdir(path: Path, parents: Boolean): FsResult<Unit> = try {
        if (parents) Files.createDirectories(path) else Files.createDirectory(path)
        FsResult.Success(Unit)
    } catch (e: IOException) {
        FsResult.Failure(FsError.from(e, path))
    }

    fun touch(path: Path): FsResult<Unit> = try {
        if (Files.exists(path)) {
            Files.setLastModifiedTime(path, FileTime.fromMillis(System.currentTimeMillis()))
        } else {
            Files.createFile(path)
        }
        FsResult.Success(Unit)
    } catch (e: IOException) {
        FsResult.Failure(FsError.from(e, path))
    }

    fun rmdir(path: Path): FsResult<Unit> = try {
        Files.delete(path)
        FsResult.Success(Unit)
    } catch (e: IOException) {
        FsResult.Failure(FsError.from(e, path))
    }

    /**
     * Borra un fichero, o un árbol entero con [recursive].
     *
     * `walkFileTree` **sin** `FOLLOW_LINKS` (el defecto): un symlink se borra como entrada y no se
     * desciende por él. Y entrega los errores en `visitFileFailed` en vez de abortar el recorrido
     * entero como haría `Files.walk`, que lanza a mitad de iteración al topar con un directorio sin
     * permiso.
     *
     * Devuelve **cuántas entradas se borraron**: nada silencioso que sea destructivo (principio 4).
     */
    fun remove(path: Path, recursive: Boolean): FsResult<Int> {
        val isDir = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
        if (isDir && !recursive) return FsResult.Failure(FsError.IsADirectory)

        return try {
            if (!isDir) {
                Files.delete(path)
                return FsResult.Success(1)
            }
            var count = 0
            Files.walkFileTree(
                path,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        Files.delete(file)
                        count++
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                        if (exc != null) throw exc
                        Files.delete(dir)
                        count++
                        return FileVisitResult.CONTINUE
                    }
                },
            )
            FsResult.Success(count)
        } catch (e: IOException) {
            FsResult.Failure(FsError.from(e, path))
        }
    }

    /**
     * Mueve o renombra.
     *
     * **Sin `ATOMIC_MOVE`**: cruzar de volumen da `EXDEV`, y con la opción puesta eso sería una
     * `AtomicMoveNotSupportedException` en vez de un movimiento. Sin ella, `nio` cae a copiar+borrar
     * por su cuenta. `File.renameTo()` habría devuelto `false` sin decir por qué.
     */
    fun move(from: Path, to: Path): FsResult<Unit> = try {
        val target = if (Files.isDirectory(to)) to.resolve(from.fileName) else to
        Files.move(from, target)
        FsResult.Success(Unit)
    } catch (e: IOException) {
        FsResult.Failure(FsError.from(e, from))
    }

    fun copy(from: Path, to: Path, recursive: Boolean): FsResult<Int> {
        val isDir = Files.isDirectory(from)
        if (isDir && !recursive) return FsResult.Failure(FsError.IsADirectory)
        val target = if (Files.isDirectory(to)) to.resolve(from.fileName) else to

        // `cp -r . copia` copiaría dentro de sí mismo: `walkFileTree` iría encontrando lo que la
        // propia copia va creando y no terminaría nunca — y colgar la pantalla de inicio es un
        // móvil inutilizable. Se rechaza antes de empezar, como hace cualquier `cp`.
        if (isDir && target.normalize().startsWith(from.normalize())) {
            return FsResult.Failure(FsError.Other("cannot copy a directory into itself"))
        }

        return try {
            if (!isDir) {
                Files.copy(from, target, StandardCopyOption.REPLACE_EXISTING)
                return FsResult.Success(1)
            }
            var count = 0
            Files.walkFileTree(
                from,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        Files.createDirectories(target.resolve(from.relativize(dir).toString()))
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        Files.copy(
                            file,
                            target.resolve(from.relativize(file).toString()),
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                        count++
                        return FileVisitResult.CONTINUE
                    }
                },
            )
            FsResult.Success(count)
        } catch (e: IOException) {
            FsResult.Failure(FsError.from(e, from))
        }
    }

    // ---------------------------------------------------------------- medir y buscar

    /**
     * Tamaño ocupado por un árbol.
     *
     * Es el tamaño **aparente**, no los bloques asignados: los bloques exigirían `Os.lstat`, que es
     * de Android y sacaría todo esto de `core/` —y con él la posibilidad de testearlo sin emulador—
     * a cambio de una diferencia que en `/sdcard` casi nadie va a notar. Queda anotado.
     */
    fun diskUsage(path: Path): FsResult<Long> = try {
        var total = 0L
        Files.walkFileTree(
            path,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    total += attrs.size()
                    return FileVisitResult.CONTINUE
                }

                // Un directorio sin permiso no aborta el recorrido: se cuenta lo que se pueda.
                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                    FileVisitResult.CONTINUE
            },
        )
        FsResult.Success(total)
    } catch (e: IOException) {
        FsResult.Failure(FsError.from(e, path))
    }

    /** Espacio total y disponible del volumen que contiene [path]. */
    fun space(path: Path): FsResult<Pair<Long, Long>> = try {
        val store = Files.getFileStore(path)
        // getUsableSpace, no getUnallocatedSpace: el segundo cuenta los bloques reservados para
        // root, que esta app no va a poder usar jamás.
        FsResult.Success(store.totalSpace to store.usableSpace)
    } catch (e: IOException) {
        FsResult.Failure(FsError.from(e, path))
    }

    /**
     * Busca por nombre, con `*` y `?` como comodines.
     *
     * Solo por nombre: no hay predicados ni expresiones. Es un `find -name` y nada más, coherente
     * con un vocabulario cerrado.
     */
    fun find(root: Path, pattern: String, limit: Int): FsResult<List<Path>> {
        val regex = globToRegex(pattern)
        return try {
            val hits = ArrayList<Path>()
            Files.walkFileTree(
                root,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        val name = file.fileName?.toString() ?: return FileVisitResult.CONTINUE
                        if (regex.matches(name)) hits.add(file)
                        return if (hits.size >= limit) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
                    }

                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        val name = dir.fileName?.toString()
                        if (name != null && dir != root && regex.matches(name)) hits.add(dir)
                        return if (hits.size >= limit) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                        FileVisitResult.CONTINUE
                },
            )
            FsResult.Success(hits)
        } catch (e: IOException) {
            FsResult.Failure(FsError.from(e, root))
        }
    }

    /**
     * Traduce el patrón de `find` a una expresión regular.
     *
     * Ojo con el matiz: esto **no** es globbing de la línea de comandos. `rm *.jpg` no expande nada
     * —`*` es un carácter literal en un nombre (functional.md §6.3)—; el comodín solo existe donde
     * el comando lo declara, que es en `find`, y ahí es un argumento explícito.
     */
    internal fun globToRegex(pattern: String): Regex = buildString {
        append('^')
        for (c in pattern) {
            when (c) {
                '*' -> append(".*")
                '?' -> append('.')
                else -> append(Regex.escape(c.toString()))
            }
        }
        append('$')
    }.toRegex(RegexOption.IGNORE_CASE)

    // ---------------------------------------------------------------- formato

    /** Tamaños legibles. Sin decimales por encima de 10 unidades: `1.4M` y `340M`, no `340.0M`. */
    fun humanBytes(bytes: Long): String {
        if (bytes < 1024) return "${bytes}B"
        val units = listOf("K", "M", "G", "T")
        var value = bytes.toDouble() / 1024
        var unit = 0
        while (value >= 1024 && unit < units.size - 1) {
            value /= 1024
            unit++
        }
        return if (value < 10) {
            "${(kotlin.math.round(value * 10) / 10)}${units[unit]}"
        } else {
            "${value.toLong()}${units[unit]}"
        }
    }
}
