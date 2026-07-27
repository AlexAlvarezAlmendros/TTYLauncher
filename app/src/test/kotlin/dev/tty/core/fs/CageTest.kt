package dev.tty.core.fs

import java.nio.file.Files
import java.nio.file.Path
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * La jaula de rutas.
 *
 * **Estos tests se escribieron antes que ningún comando de fichero**, a propósito: con la raíz en
 * `/sdcard`, un escape aquí borra las fotos del usuario y no hay papelera. Es el criterio 15 del
 * funcional, y no se demuestra mirando la pantalla — se demuestra aquí.
 *
 * Contra un directorio temporal **real**, no contra dobles: lo que se está probando es cómo resuelve
 * el kernel los symlinks y los `..`, y eso un doble no lo puede simular.
 */
class CageTest {

    private lateinit var root: Path
    private lateinit var outside: Path
    private lateinit var cage: Cage

    @Before
    fun setUp() {
        val base = Files.createTempDirectory("tty-cage")
        root = Files.createDirectory(base.resolve("root"))
        outside = Files.createDirectory(base.resolve("outside"))
        Files.createFile(outside.resolve("secreto.txt"))
        Files.createDirectory(root.resolve("fotos"))
        Files.createFile(root.resolve("fotos/a.jpg"))
        cage = Cage(root)
    }

    @After
    fun tearDown() {
        root.parent.toFile().deleteRecursively()
    }

    // ------------------------------------------------------------------ lo básico

    @Test
    fun `arranca en la raiz y la ensena como barra`() {
        assertEquals(root.toRealPath(), cage.cwd)
        assertEquals("/", cage.display())
    }

    @Test
    fun `una ruta absoluta del usuario es absoluta dentro de la jaula`() {
        // Escribir /fotos lleva a la raíz de tty, no a la del sistema — que además ni se puede
        // listar. Es la diferencia entre un shell enjaulado y uno roto.
        val r = cage.check(cage.resolve("/fotos"))
        assertEquals(root.resolve("fotos").toRealPath(), r.getOrNull())
    }

    @Test
    fun `la virgulilla es la raiz`() {
        assertEquals(root.toRealPath(), cage.check(cage.resolve("~")).getOrNull())
        assertEquals(
            root.resolve("fotos").toRealPath(),
            cage.check(cage.resolve("~/fotos")).getOrNull(),
        )
    }

    // ------------------------------------------------------------------ escapes

    @Test
    fun `dos puntos encadenados no sacan de la jaula`() {
        cage.cd("fotos")
        val r = cage.check(cage.resolve("../../../../../etc"))
        // No es un error: se queda en la raíz. No hay nada por encima, y decir «permission denied»
        // por subir de más mentiría sobre dónde está el límite.
        assertEquals(root.toRealPath(), r.getOrNull())
    }

    @Test
    fun `subir desde la raiz se queda en la raiz`() {
        val r = cage.cd("..")
        assertEquals(root.toRealPath(), r.getOrNull())
        assertEquals("/", cage.display())
    }

    @Test
    fun `un symlink que apunta fuera no se sigue`() {
        val link = root.resolve("puerta")
        Files.createSymbolicLink(link, outside)

        // La trampa: léxicamente `puerta/secreto.txt` está dentro de la raíz. Solo toRealPath()
        // ve que el kernel lo resuelve fuera. normalize() habría dicho que sí.
        val r = cage.check(cage.resolve("puerta/secreto.txt"))
        assertEquals(root.toRealPath(), r.getOrNull())
    }

    @Test
    fun `no se puede entrar en un symlink que apunta fuera`() {
        Files.createSymbolicLink(root.resolve("puerta"), outside)
        cage.cd("puerta")
        // Acaba en la raíz, no en `outside`.
        assertEquals(root.toRealPath(), cage.cwd)
    }

    @Test
    fun `una ruta absoluta del sistema no llega al sistema`() {
        val r = cage.check(cage.resolve("/etc/passwd"))
        // Se interpreta como <raíz>/etc/passwd, que no existe.
        assertTrue(r is FsResult.Failure)
        assertEquals(FsError.NoSuchFile, r.errorOrNull())
    }

    @Test
    fun `el prefijo textual enganoso no cuela`() {
        // `/x-evil` empieza por `/x` como cadena, pero no como ruta. Comparar por String aquí es
        // exactamente el agujero que este test existe para cerrar.
        val hermano = root.resolveSibling("root-evil")
        Files.createDirectory(hermano)
        Files.createFile(hermano.resolve("botin.txt"))

        assertFalse(cage.contains(hermano.toRealPath()))
        assertFalse(cage.contains(hermano.resolve("botin.txt").toRealPath()))
        assertTrue(cage.contains(root.resolve("fotos").toRealPath()))
    }

    @Test
    fun `un cwd manipulado no amplia la jaula`() {
        // Aunque alguien consiguiera dejar el cwd fuera, resolver sigue comprobando contra la raíz.
        cage.cd("fotos")
        Files.createSymbolicLink(root.resolve("fotos/salida"), outside)
        cage.cd("salida")
        assertTrue(cage.contains(cage.cwd))
    }

    // ------------------------------------------------------------------ destinos que aún no existen

    @Test
    fun `para lo que no existe se canonicaliza el padre`() {
        // `toRealPath()` exige que el fichero exista, así que sobre el destino no se puede llamar:
        // se canonicaliza el padre y el nombre final se añade después, sin resolver.
        val r = cage.checkParent(cage.resolve("fotos/nueva"))
        assertEquals(root.resolve("fotos").toRealPath().resolve("nueva"), r.getOrNull())
    }

    @Test
    fun `crear a traves de un symlink que sale fuera se rechaza`() {
        Files.createSymbolicLink(root.resolve("puerta"), outside)
        val r = cage.checkParent(cage.resolve("puerta/nuevo.txt"))
        assertEquals(FsError.OutsideRoot, r.errorOrNull())
    }

    @Test
    fun `si el padre no existe se dice, no se crea`() {
        val r = cage.checkParent(cage.resolve("no-existe/nuevo.txt"))
        assertEquals(FsError.NoSuchFile, r.errorOrNull())
    }

    // ------------------------------------------------------------------ borrados suicidas

    @Test
    fun `se niega a borrar la raiz`() {
        assertEquals(FsError.RefusedRoot, cage.refusesToDelete(cage.root))
    }

    @Test
    fun `se niega a borrar el directorio de trabajo`() {
        cage.cd("fotos")
        assertEquals(FsError.RefusedCwd, cage.refusesToDelete(cage.cwd))
    }

    @Test
    fun `se niega a borrar un ancestro del directorio de trabajo`() {
        Files.createDirectory(root.resolve("fotos/2026"))
        cage.cd("fotos/2026")
        // Borrar `fotos` dejaría el prompt apuntando a la nada.
        assertEquals(FsError.RefusedAncestor, cage.refusesToDelete(root.resolve("fotos").toRealPath()))
    }

    @Test
    fun `borrar algo que no es la raiz ni el cwd si se permite`() {
        assertNull(cage.refusesToDelete(root.resolve("fotos/a.jpg").toRealPath()))
    }

    // ------------------------------------------------------------------ recuperación

    @Test
    fun `si el cwd desaparece se vuelve a la raiz`() {
        cage.cd("fotos")
        root.resolve("fotos").toFile().deleteRecursively()

        assertFalse(cage.revalidateCwd())
        assertEquals(root.toRealPath(), cage.cwd)
        // Un prompt apuntando a la nada no se arregla escribiendo, y esta es la pantalla de inicio.
        assertEquals("/", cage.display())
    }

    @Test
    fun `con el cwd vivo revalidar no cambia nada`() {
        cage.cd("fotos")
        assertTrue(cage.revalidateCwd())
        assertEquals("/fotos", cage.display())
    }

    // ------------------------------------------------------------------ cd

    @Test
    fun `cd a un fichero es un error, no un cambio silencioso`() {
        val r = cage.cd("fotos/a.jpg")
        assertEquals(FsError.NotADirectory, r.errorOrNull())
        assertEquals("/", cage.display())
    }

    @Test
    fun `cd sin argumento vuelve a la raiz`() {
        cage.cd("fotos")
        cage.cd("")
        assertEquals("/", cage.display())
    }

    @Test
    fun `cd a lo que no existe lo dice y no se mueve`() {
        val r = cage.cd("no-existe")
        assertEquals(FsError.NoSuchFile, r.errorOrNull())
        assertEquals("/", cage.display())
        assertNotNull(cage.cwd)
    }

    // ------------------------------------------------------------------ sin argumento

    @Test
    fun `sin argumento se resuelve el directorio de trabajo, no la raiz`() {
        // El bug que hacía que `cd Download` seguido de `ls` enseñara la raíz: el cwd existía y no
        // lo miraba nadie. Es lo que hace que un shell se sienta roto.
        cage.cd("fotos")
        assertEquals(root.resolve("fotos").toRealPath(), cage.check(cage.resolve("")).getOrNull())
    }

    @Test
    fun `un punto tambien es el directorio de trabajo`() {
        cage.cd("fotos")
        assertEquals(root.resolve("fotos").toRealPath(), cage.check(cage.resolve(".")).getOrNull())
    }

    @Test
    fun `una ruta relativa cuelga del directorio de trabajo`() {
        Files.createDirectory(root.resolve("fotos/2026"))
        cage.cd("fotos")
        assertEquals(
            root.resolve("fotos/2026").toRealPath(),
            cage.check(cage.resolve("2026")).getOrNull(),
        )
    }

    @Test
    fun `la virgulilla sigue siendo la raiz aunque estes en otro sitio`() {
        cage.cd("fotos")
        assertEquals(root.toRealPath(), cage.check(cage.resolve("~")).getOrNull())
    }
}
