package dev.tty.core.fs

import java.nio.file.Files
import java.nio.file.Path
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Las operaciones de fichero, contra un directorio temporal **real**.
 *
 * Se testean aquí y no en el emulador porque `java.nio.file` es igual en la JVM y en Android desde
 * el nivel 26. Lo que no se puede comprobar así es el permiso y la raíz, que son las dos únicas
 * cosas que viven en `platform/`.
 */
class FileOpsTest {

    private lateinit var dir: Path

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("tty-ops")
    }

    @After
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun file(name: String, content: String = ""): Path =
        Files.writeString(dir.resolve(name), content)

    // ---------------------------------------------------------------- listar

    @Test
    fun `lista con los directorios primero y luego alfabetico`() {
        Files.createDirectory(dir.resolve("zeta"))
        Files.createDirectory(dir.resolve("alfa"))
        file("b.txt")
        file("a.txt")

        val entries = FileOps.list(dir).getOrNull().orEmpty()
        assertEquals(listOf("alfa/", "zeta/", "a.txt", "b.txt"), entries.map { it.display })
    }

    @Test
    fun `los ocultos solo salen con -a`() {
        file(".secreto")
        file("visible.txt")

        assertEquals(listOf("visible.txt"), FileOps.list(dir).getOrNull()?.map { it.name })
        assertEquals(
            listOf(".secreto", "visible.txt"),
            FileOps.list(dir, includeHidden = true).getOrNull()?.map { it.name },
        )
    }

    @Test
    fun `distingue no existe de no es un directorio`() {
        val f = file("a.txt")
        assertEquals(FsError.NoSuchFile, FileOps.list(dir.resolve("nope")).errorOrNull())
        assertEquals(FsError.NotADirectory, FileOps.list(f).errorOrNull())
        // Es justo lo que `File.listFiles()` no permite: devuelve null para los dos.
    }

    @Test
    fun `un symlink roto aparece en el listado en vez de romperlo`() {
        Files.createSymbolicLink(dir.resolve("colgando"), dir.resolve("no-existe"))
        val entries = FileOps.list(dir).getOrNull().orEmpty()
        assertEquals(1, entries.size)
        assertTrue(entries.first().isLink)
    }

    // ---------------------------------------------------------------- leer

    @Test
    fun `lee un fichero de texto entero`() {
        file("notas.txt", "uno\ndos\ntres\n")
        assertEquals(listOf("uno", "dos", "tres"), FileOps.read(file("notas.txt", "uno\ndos\ntres\n")).getOrNull())
    }

    @Test
    fun `un binario no se vomita, se describe`() {
        val bin = dir.resolve("foto.jpg")
        Files.write(bin, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0, 0, 1, 2, 3))

        val out = FileOps.read(bin).getOrNull().orEmpty()
        assertEquals(1, out.size)
        assertTrue(out.first().startsWith("binary file"))
    }

    @Test
    fun `cat de un directorio lo dice`() {
        assertEquals(FsError.IsADirectory, FileOps.read(dir).errorOrNull())
    }

    @Test
    fun `head devuelve el principio y tail el final`() {
        val f = file("log.txt", (1..50).joinToString("\n") { "line $it" } + "\n")

        assertEquals(listOf("line 1", "line 2", "line 3"), FileOps.head(f, 3).getOrNull())
        assertEquals(listOf("line 48", "line 49", "line 50"), FileOps.tail(f, 3).getOrNull())
    }

    @Test
    fun `tail pide menos lineas de las que hay y las devuelve todas`() {
        val f = file("corto.txt", "a\nb\n")
        assertEquals(listOf("a", "b"), FileOps.tail(f, 10).getOrNull())
    }

    @Test
    fun `tail de un fichero grande no lo lee entero`() {
        // 200k líneas: si se leyera todo, este test tardaría lo suyo. Es la comprobación barata de
        // que se lee hacia atrás.
        val f = file("grande.txt", (1..200_000).joinToString("\n") { "line $it" } + "\n")
        assertEquals(listOf("line 199999", "line 200000"), FileOps.tail(f, 2).getOrNull())
    }

    @Test
    fun `un fichero sin salto final no pierde su ultima linea`() {
        val f = file("sin-salto.txt", "uno\ndos")
        assertEquals(listOf("uno", "dos"), FileOps.tail(f, 5).getOrNull())
    }

    // ---------------------------------------------------------------- escribir

    @Test
    fun `mkdir sin -p no crea padres`() {
        assertEquals(FsError.NoSuchFile, FileOps.mkdir(dir.resolve("a/b/c"), parents = false).errorOrNull())
        assertTrue(FileOps.mkdir(dir.resolve("a/b/c"), parents = true) is FsResult.Success)
        assertTrue(Files.isDirectory(dir.resolve("a/b/c")))
    }

    @Test
    fun `mkdir sobre algo que ya existe lo dice`() {
        Files.createDirectory(dir.resolve("x"))
        assertEquals(FsError.AlreadyExists, FileOps.mkdir(dir.resolve("x"), parents = false).errorOrNull())
    }

    @Test
    fun `touch crea vacio y luego solo toca la fecha`() {
        val f = dir.resolve("nuevo.txt")
        assertTrue(FileOps.touch(f) is FsResult.Success)
        assertTrue(Files.exists(f))
        assertEquals(0, Files.size(f))

        Files.writeString(f, "contenido")
        FileOps.touch(f)
        // No se ha vaciado: touch actualiza la fecha, no trunca.
        assertEquals("contenido", Files.readString(f))
    }

    @Test
    fun `rm sin -r se niega sobre un directorio`() {
        Files.createDirectory(dir.resolve("carpeta"))
        assertEquals(FsError.IsADirectory, FileOps.remove(dir.resolve("carpeta"), recursive = false).errorOrNull())
        assertTrue(Files.exists(dir.resolve("carpeta")))
    }

    @Test
    fun `rm -r borra el arbol y dice cuantas entradas`() {
        Files.createDirectories(dir.resolve("a/b"))
        file("a/uno.txt")
        Files.writeString(dir.resolve("a/b/dos.txt"), "x")

        // 2 ficheros + 2 directorios.
        assertEquals(4, FileOps.remove(dir.resolve("a"), recursive = true).getOrNull())
        assertFalse(Files.exists(dir.resolve("a")))
    }

    @Test
    fun `rm -r sobre un symlink borra el enlace, no lo que apunta`() {
        val fuera = Files.createTempDirectory("tty-fuera")
        Files.writeString(fuera.resolve("importante.txt"), "no me borres")
        Files.createSymbolicLink(dir.resolve("enlace"), fuera)

        FileOps.remove(dir.resolve("enlace"), recursive = true)

        assertFalse(Files.exists(dir.resolve("enlace"), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        // Lo de fuera sigue ahí: walkFileTree va sin FOLLOW_LINKS.
        assertTrue(Files.exists(fuera.resolve("importante.txt")))
        fuera.toFile().deleteRecursively()
    }

    @Test
    fun `mv renombra y mueve dentro de un directorio`() {
        val f = file("viejo.txt", "x")
        FileOps.move(f, dir.resolve("nuevo.txt"))
        assertTrue(Files.exists(dir.resolve("nuevo.txt")))

        Files.createDirectory(dir.resolve("destino"))
        FileOps.move(dir.resolve("nuevo.txt"), dir.resolve("destino"))
        // Mover a un directorio existente mete dentro, no lo sustituye.
        assertTrue(Files.exists(dir.resolve("destino/nuevo.txt")))
    }

    @Test
    fun `cp sin -r se niega sobre un directorio, con -r copia el arbol`() {
        Files.createDirectories(dir.resolve("origen/sub"))
        Files.writeString(dir.resolve("origen/a.txt"), "1")
        Files.writeString(dir.resolve("origen/sub/b.txt"), "2")

        assertEquals(
            FsError.IsADirectory,
            FileOps.copy(dir.resolve("origen"), dir.resolve("copia"), recursive = false).errorOrNull(),
        )
        assertEquals(2, FileOps.copy(dir.resolve("origen"), dir.resolve("copia"), recursive = true).getOrNull())
        assertEquals("2", Files.readString(dir.resolve("copia/sub/b.txt")))
        // El original sigue: cp copia, no mueve.
        assertTrue(Files.exists(dir.resolve("origen/a.txt")))
    }

    // ---------------------------------------------------------------- medir y buscar

    @Test
    fun `du suma el arbol`() {
        Files.createDirectory(dir.resolve("d"))
        Files.writeString(dir.resolve("d/a"), "12345")
        Files.writeString(dir.resolve("d/b"), "123")
        assertEquals(8L, FileOps.diskUsage(dir.resolve("d")).getOrNull())
    }

    @Test
    fun `df devuelve total y disponible`() {
        val (total, free) = FileOps.space(dir).getOrNull() ?: error("sin espacio")
        assertTrue(total > 0)
        assertTrue(free in 0..total)
    }

    @Test
    fun `find casa por nombre con comodines`() {
        Files.createDirectory(dir.resolve("sub"))
        file("foto.jpg")
        Files.writeString(dir.resolve("sub/otra.jpg"), "")
        file("notas.txt")

        val hits = FileOps.find(dir, "*.jpg", limit = 100).getOrNull().orEmpty()
        assertEquals(setOf("foto.jpg", "otra.jpg"), hits.map { it.fileName.toString() }.toSet())
    }

    @Test
    fun `find respeta el limite`() {
        repeat(20) { file("f$it.txt") }
        assertEquals(5, FileOps.find(dir, "*.txt", limit = 5).getOrNull()?.size)
    }

    @Test
    fun `el comodin solo existe en find, no en las rutas`() {
        // globToRegex es interno de find: `rm *.jpg` no expande nada, `*` es un carácter más de un
        // nombre de fichero (functional.md §6.3).
        assertTrue(FileOps.globToRegex("*.jpg").matches("a.jpg"))
        assertTrue(FileOps.globToRegex("a?c").matches("abc"))
        assertFalse(FileOps.globToRegex("a?c").matches("abbc"))
    }

    // ---------------------------------------------------------------- formato

    @Test
    fun `los tamanos se leen de un vistazo`() {
        assertEquals("512B", FileOps.humanBytes(512))
        assertEquals("1.0K", FileOps.humanBytes(1024))
        assertEquals("1.5K", FileOps.humanBytes(1536))
        assertEquals("10K", FileOps.humanBytes(10 * 1024))
        assertEquals("1.0M", FileOps.humanBytes(1024L * 1024))
        assertEquals("2.0G", FileOps.humanBytes(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun `copiar un directorio dentro de si mismo se rechaza en vez de colgarse`() {
        // `cp -r . copia` haría que walkFileTree fuera encontrando lo que la propia copia crea:
        // no termina nunca, y colgar la pantalla de inicio es un móvil inutilizable.
        Files.createDirectory(dir.resolve("origen"))
        Files.writeString(dir.resolve("origen/a.txt"), "x")

        val r = FileOps.copy(dir.resolve("origen"), dir.resolve("origen/copia"), recursive = true)

        assertTrue(r is FsResult.Failure)
        assertFalse(Files.exists(dir.resolve("origen/copia")))
    }

    @Test
    fun `copiar a un hermano si funciona`() {
        // La guarda anterior no puede pasarse de celosa: copiar al lado es el caso normal.
        Files.createDirectory(dir.resolve("origen"))
        Files.writeString(dir.resolve("origen/a.txt"), "x")

        assertEquals(1, FileOps.copy(dir.resolve("origen"), dir.resolve("destino"), recursive = true).getOrNull())
        assertTrue(Files.exists(dir.resolve("destino/a.txt")))
    }
}
