package dev.tty.core.command

import dev.tty.core.command.builtin.CatCommand
import dev.tty.core.command.builtin.CdCommand
import dev.tty.core.command.builtin.CpCommand
import dev.tty.core.command.builtin.DfCommand
import dev.tty.core.command.builtin.DuCommand
import dev.tty.core.command.builtin.FileSystemAccess
import dev.tty.core.command.builtin.FindCommand
import dev.tty.core.command.builtin.HeadCommand
import dev.tty.core.command.builtin.LsCommand
import dev.tty.core.command.builtin.MkdirCommand
import dev.tty.core.command.builtin.MountCommand
import dev.tty.core.command.builtin.MvCommand
import dev.tty.core.command.builtin.PwdCommand
import dev.tty.core.command.builtin.RmCommand
import dev.tty.core.command.builtin.TailCommand
import dev.tty.core.command.builtin.TouchCommand
import dev.tty.core.fs.Cage
import dev.tty.core.output.Output
import dev.tty.core.output.Role
import dev.tty.core.parse.CommandLine
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Los quince verbos de fichero, **por la costura**.
 *
 * `CageTest` demuestra que la jaula sabe decir que no y `FileOpsTest` que las operaciones hacen lo
 * que dicen. Ninguno de los dos demuestra lo único que de verdad protege los datos del usuario: que
 * **los comandos pasan por la jaula antes de tocar el disco**. Una jaula que nadie llama no protege
 * nada, y ese olvido no se ve en la pantalla — se ve cuando ya no están las fotos.
 *
 * Por eso aquí todo se ejecuta como se ejecuta en el móvil —línea escrita, comando resuelto, salida
 * de vuelta— y todo se comprueba dos veces: el **mensaje** que sale y el **estado del disco** que
 * queda. Un test que solo mirara el mensaje pasaría igual con un `rm` que borra y luego se queja.
 *
 * Contra un directorio temporal real, porque lo que se prueba es cómo resuelve el kernel los `..` y
 * los symlinks, y eso un doble no lo puede simular.
 */
class FileCommandsTest {

    /**
     * `fuera` es hermano de la raíz a propósito: así `../fuera` es un escape **real** y alcanzable
     * desde el prompt, no una ruta inventada que ya falla por no existir.
     */
    private lateinit var base: Path
    private lateinit var root: Path
    private lateinit var fuera: Path
    private lateinit var ctx: Ctx

    @Before
    fun setUp() {
        base = Files.createTempDirectory("tty-files")
        root = Files.createDirectory(base.resolve("root"))
        fuera = Files.createDirectory(base.resolve("fuera"))
        ctx = Ctx(Access(Cage(root)))
    }

    @After
    fun tearDown() {
        base.toFile().deleteRecursively()
    }

    private fun run(command: Command, input: String): Output = runBlocking {
        command.run(CommandLine.parse(input) ?: error("entrada vacía"), ctx)
    }

    private fun texto(out: Output): String = out.lines.first().first

    private fun rol(out: Output): Role = out.lines.first().second

    private fun file(name: String, content: String = "x"): Path =
        Files.writeString(root.resolve(name), content)

    // ------------------------------------------------------------------ rm y la jaula

    @Test
    fun `rm -r del directorio en el que estas se niega y el directorio sigue ahi`() {
        Files.createDirectory(root.resolve("viaje"))
        Files.writeString(root.resolve("viaje/foto.jpg"), "x")
        assertTrue(run(CdCommand, "cd viaje").lines.isEmpty())

        val out = run(RmCommand, "rm -r .")

        // Borrar el suelo que estás pisando deja el prompt apuntando a la nada, y esta es la
        // pantalla de inicio: a partir de ahí todo comando falla de formas que no se explican.
        assertEquals("rm: .: refusing to remove the working directory", texto(out))
        assertEquals(Role.ERROR, rol(out))
        assertTrue(Files.isDirectory(root.resolve("viaje")))
        assertTrue(Files.exists(root.resolve("viaje/foto.jpg")))
    }

    @Test
    fun `rm -r de un ancestro del cwd tampoco pasa`() {
        Files.createDirectories(root.resolve("viaje/2026"))
        Files.writeString(root.resolve("viaje/foto.jpg"), "x")
        run(CdCommand, "cd viaje/2026")

        val out = run(RmCommand, "rm -r ..")

        assertEquals(Role.ERROR, rol(out))
        assertTrue(Files.exists(root.resolve("viaje/foto.jpg")))
        // Y el prompt sigue donde estaba: nada se ha movido bajo los pies.
        assertEquals("/viaje/2026", texto(run(PwdCommand, "pwd")))
    }

    @Test
    fun `rm a traves de un symlink no toca lo que hay fuera de la raiz`() {
        Files.writeString(fuera.resolve("importante.txt"), "no me borres")
        Files.createSymbolicLink(root.resolve("puerta"), fuera)

        // Léxicamente `puerta/importante.txt` está dentro de la raíz: solo toRealPath() ve que el
        // kernel lo resuelve fuera. Con normalize() esto habría borrado el fichero de fuera.
        val porDentro = run(RmCommand, "rm puerta/importante.txt")
        val elEnlace = run(RmCommand, "rm -r puerta")

        assertTrue(Files.exists(fuera.resolve("importante.txt")))
        assertEquals(Role.ERROR, rol(porDentro))
        assertEquals(Role.ERROR, rol(elEnlace))
        // La ruta que se escapa se recorta a la raíz, y sobre la raíz `rm` se niega: son dos
        // cinturones, y este test falla si se quita cualquiera de los dos.
        assertTrue(texto(porDentro).contains("refusing to remove the root"))
        assertTrue(texto(elEnlace).contains("refusing to remove the root"))
    }

    @Test
    fun `rm sin -r se niega sobre un directorio y no borra nada`() {
        Files.createDirectory(root.resolve("carpeta"))
        Files.writeString(root.resolve("carpeta/a.txt"), "x")

        val out = run(RmCommand, "rm carpeta")

        // Pedir `-r` explícitamente es el primero de los tres cinturones de `rm`: sin él, un
        // directorio entero se iría por un dedo de más.
        assertEquals("rm: carpeta: is a directory", texto(out))
        assertEquals(Role.ERROR, rol(out))
        assertTrue(Files.exists(root.resolve("carpeta/a.txt")))
    }

    @Test
    fun `rm -r dice cuantas entradas borro y no lo hace en silencio`() {
        Files.createDirectories(root.resolve("descargas/sub"))
        Files.writeString(root.resolve("descargas/a.txt"), "1")
        Files.writeString(root.resolve("descargas/sub/b.txt"), "2")

        val out = run(RmCommand, "rm -r descargas")

        // Nada silencioso que sea destructivo: dos ficheros y dos directorios, y se dice.
        assertEquals(1, out.lines.size)
        assertEquals("removed 4 entries", texto(out))
        assertEquals(Role.STATUS, rol(out))
        assertFalse(Files.exists(root.resolve("descargas")))

        Files.createDirectory(root.resolve("vacia"))
        assertEquals("removed 1 entry", texto(run(RmCommand, "rm -r vacia")))

        // Un fichero suelto sí es silencioso: el resultado se ve con `ls`, y el éxito silencioso es
        // el valor por defecto (§10).
        file("suelto.txt")
        assertTrue(run(RmCommand, "rm suelto.txt").lines.isEmpty())
        assertFalse(Files.exists(root.resolve("suelto.txt")))
    }

    // ------------------------------------------------------------------ cd

    @Test
    fun `cd a un fichero da not a directory y el cwd no se mueve`() {
        file("notas.txt")

        val out = run(CdCommand, "cd notas.txt")

        // Un `cd` optimista aceptaría esto y haría fallar al comando siguiente, que es donde ya no
        // se entiende qué pasó.
        assertEquals("cd: notas.txt: not a directory", texto(out))
        assertEquals(Role.ERROR, rol(out))
        assertEquals("/", texto(run(PwdCommand, "pwd")))
    }

    // ------------------------------------------------------------------ ls

    @Test
    fun `ls distingue lo que no existe de lo que no es un directorio`() {
        file("notas.txt")

        val noExiste = run(LsCommand, "ls nope")
        val noEsDir = run(LsCommand, "ls notas.txt")

        // Son dos mensajes distintos porque mandan al usuario a sitios distintos, y es la razón
        // entera de usar Files.newDirectoryStream: File.listFiles() devuelve null para los dos.
        assertEquals("ls: nope: no such file or directory", texto(noExiste))
        assertEquals("ls: notas.txt: not a directory", texto(noEsDir))
        assertNotEquals(texto(noExiste), texto(noEsDir))
        assertEquals(Role.ERROR, rol(noExiste))
        assertEquals(Role.ERROR, rol(noEsDir))
    }

    @Test
    fun `ls lista lo que hay y cierra con el total`() {
        Files.createDirectory(root.resolve("fotos"))
        file("a.txt")
        file(".oculto")

        val out = run(LsCommand, "ls")

        assertEquals(listOf("fotos/", "a.txt"), out.lines.filter { it.first.isNotEmpty() }.dropLast(1).map { it.first })
        assertEquals("2 entries", out.lines.last().first)
        assertEquals(Role.STATUS, out.lines.last().second)
        // El oculto solo aparece con -a.
        assertEquals("3 entries", run(LsCommand, "ls -a").lines.last().first)
    }

    // ------------------------------------------------------------------ mv y cp: los dos extremos

    @Test
    fun `mv y cp rechazan un destino que sale de la raiz`() {
        file("secreto.txt", "contenido")

        val movido = run(MvCommand, "mv secreto.txt ../fuera/robado.txt")
        val copiado = run(CpCommand, "cp secreto.txt ../fuera/robado.txt")

        // El destino de `mv` y `cp` no existe todavía, así que se canonicaliza el **padre**: es el
        // hueco por el que se saca un fichero de la jaula si el comando no lo comprueba.
        assertEquals("mv: ../fuera/robado.txt: outside the root", texto(movido))
        assertEquals("cp: ../fuera/robado.txt: outside the root", texto(copiado))
        assertFalse(Files.exists(fuera.resolve("robado.txt")))
        assertTrue(Files.exists(root.resolve("secreto.txt")))
    }

    @Test
    fun `el origen de cp tambien pasa por la jaula`() {
        Files.writeString(fuera.resolve("ajeno.txt"), "no soy tuyo")

        val out = run(CpCommand, "cp ../fuera/ajeno.txt aqui.txt")

        // Meter dentro lo de fuera es tan escape como sacar lo de dentro: la ruta se recorta a la
        // raíz, y copiar la raíz sobre un fichero no es una copia válida.
        assertEquals(Role.ERROR, rol(out))
        assertFalse(Files.exists(root.resolve("aqui.txt")))
    }

    @Test
    fun `mv dentro de la jaula si funciona`() {
        file("viejo.txt", "contenido")
        Files.createDirectory(root.resolve("destino"))

        assertTrue(run(MvCommand, "mv viejo.txt destino").lines.isEmpty())
        assertEquals("contenido", Files.readString(root.resolve("destino/viejo.txt")))
        assertFalse(Files.exists(root.resolve("viejo.txt")))
    }

    // ------------------------------------------------------------------ el permiso

    @Test
    fun `sin permiso de almacenamiento todo verbo dirige a mount en vez de reventar`() {
        ctx.files.mounted = false

        // Un verbo que estallara aquí dejaría la actividad HOME muerta, que es funcionalmente un
        // móvil bloqueado. Y el mensaje es el onboarding: dice qué escribir, `mount`.
        val entradas = listOf(
            CdCommand to "cd fotos",
            LsCommand to "ls",
            CatCommand to "cat notas.txt",
            HeadCommand to "head notas.txt",
            TailCommand to "tail notas.txt",
            MkdirCommand to "mkdir nueva",
            TouchCommand to "touch nuevo.txt",
            RmCommand to "rm notas.txt",
            MvCommand to "mv a b",
            CpCommand to "cp a b",
            DfCommand to "df",
            DuCommand to "du",
            FindCommand to "find *.txt",
        )
        for ((command, entrada) in entradas) {
            val out = run(command, entrada)
            assertEquals(entrada, 1, out.lines.size)
            assertEquals(entrada, Role.ERROR, rol(out))
            assertEquals(
                entrada,
                "${command.name}: no access to storage — run 'mount' to grant it",
                texto(out),
            )
        }

        // Y ninguno ha llegado al disco: no hay medio trabajo hecho antes de comprobar el permiso.
        assertFalse(Files.exists(root.resolve("nueva")))
        assertFalse(Files.exists(root.resolve("nuevo.txt")))

        // La salida que el mensaje señala existe de verdad.
        val montar = run(MountCommand, "mount")
        assertEquals(1, ctx.files.abiertos)
        assertEquals(Role.STATUS, rol(montar))
    }

    // ------------------------------------------------------------------ cat

    @Test
    fun `cat de un binario lo describe en vez de vomitarlo`() {
        Files.write(root.resolve("foto.jpg"), byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0, 0, 1, 2, 3))
        file("notas.txt", "uno\ndos\n")

        val binario = run(CatCommand, "cat foto.jpg")
        val plano = run(CatCommand, "cat notas.txt")

        // El scrollback es persistente: un JPEG volcado ahí no se puede deshacer más que con
        // `clear`, que borra también todo lo demás.
        assertEquals(1, binario.lines.size)
        assertTrue(texto(binario).startsWith("binary file"))
        assertEquals(Role.OUTPUT, rol(binario))
        assertEquals(listOf("uno", "dos"), plano.lines.map { it.first })
    }

    @Test
    fun `cat de un directorio lo dice`() {
        Files.createDirectory(root.resolve("carpeta"))
        assertEquals("cat: carpeta: is a directory", texto(run(CatCommand, "cat carpeta")))
    }

    // ------------------------------------------------------------------ mkdir y touch

    @Test
    fun `mkdir sin -p no inventa los padres y con -p si`() {
        val sinP = run(MkdirCommand, "mkdir a/b/c")

        assertEquals("mkdir: a/b/c: no such file or directory", texto(sinP))
        assertEquals(Role.ERROR, rol(sinP))
        assertFalse(Files.exists(root.resolve("a")))

        val conP = run(MkdirCommand, "mkdir -p a/b/c")

        // `-p` es lo único que distingue «no existe el padre» de «créalo»: si la jaula rechazara el
        // destino por que el padre no existe, `-p` no serviría para nada.
        assertTrue(conP.lines.isEmpty())
        assertTrue(Files.isDirectory(root.resolve("a/b/c")))
    }

    @Test
    fun `mkdir y touch no crean nada fuera de la raiz`() {
        val dir = run(MkdirCommand, "mkdir ../fuera/nueva")
        val fich = run(TouchCommand, "touch ../fuera/nuevo.txt")

        assertEquals("mkdir: ../fuera/nueva: outside the root", texto(dir))
        assertEquals("touch: ../fuera/nuevo.txt: outside the root", texto(fich))
        assertFalse(Files.exists(fuera.resolve("nueva")))
        assertFalse(Files.exists(fuera.resolve("nuevo.txt")))
    }

    @Test
    fun `mkdir -p no es una puerta trasera de la jaula`() {
        Files.createSymbolicLink(root.resolve("puerta"), fuera)

        // `-p` obliga a mirar el ancestro más profundo que existe en vez del padre inmediato, que
        // es el punto exacto por donde se podría colar una ruta: aquí se prueban las tres formas.
        val porArriba = run(MkdirCommand, "mkdir -p ../fuera/nueva")
        val porSymlink = run(MkdirCommand, "mkdir -p puerta/nueva/otra")
        val entreMedias = run(MkdirCommand, "mkdir -p a/../../colada")

        assertEquals("mkdir: ../fuera/nueva: outside the root", texto(porArriba))
        assertEquals("mkdir: puerta/nueva/otra: outside the root", texto(porSymlink))
        // `a` no existe, así que `a/..` es puramente léxico y solo se ve al normalizar la cola.
        assertEquals("mkdir: a/../../colada: outside the root", texto(entreMedias))

        assertFalse(Files.exists(fuera.resolve("nueva")))
        assertFalse(Files.exists(base.resolve("colada")))
        assertFalse(Files.exists(root.resolve("a")))
    }

    @Test
    fun `mkdir -p con dos puntos dentro de la jaula si crea`() {
        assertTrue(run(MkdirCommand, "mkdir -p a/b/../c").lines.isEmpty())
        assertTrue(Files.isDirectory(root.resolve("a/c")))
    }

    // ------------------------------------------------------------------ el mundo exterior

    /** Lo único que un verbo de fichero toca del mundo: la jaula y el permiso. */
    private class Access(override val cage: Cage) : FileSystemAccess {
        var mounted = true
        var abiertos = 0
        override fun hasStorageAccess() = mounted
        override fun requestStorageAccess(): Boolean {
            abiertos++
            return true
        }
    }

    private class Ctx(override val files: Access) : CommandContext {
        override val catalog = object : dev.tty.core.apps.AppCatalog {
            override fun all() = emptyList<dev.tty.core.apps.AppEntry>()
        }
        override val actions = object : dev.tty.core.apps.AppActions {
            override fun open(app: dev.tty.core.apps.AppEntry) = true
            override fun requestUninstall(app: dev.tty.core.apps.AppEntry) = true
            override fun openAppSettings(app: dev.tty.core.apps.AppEntry) = true
            override fun openSystemSettings() = true
        }
        override val killer = object : dev.tty.core.apps.AppKiller {
            override val mode = dev.tty.core.apps.KillMode.SYSTEM_DIALOG
            override fun requestStop(app: dev.tty.core.apps.AppEntry) = true
        }
        override val scripts = object : dev.tty.core.script.ScriptStore {
            override suspend fun list() = emptyList<dev.tty.core.script.Script>()
            override suspend fun read(name: String): dev.tty.core.script.Script? = null
            override suspend fun write(script: dev.tty.core.script.Script) = true
            override suspend fun delete(name: String) = false
            override suspend fun seedExamples() = Unit
        }
        override val termux = object : dev.tty.core.termux.TermuxClient {
            override suspend fun check() = dev.tty.core.termux.TermuxError.NotInstalled
            override suspend fun run(path: String, args: List<String>) =
                Result.success(dev.tty.core.termux.TermuxResult(emptyList(), emptyList(), 0))
        }
        override val session = object : Session {
            override suspend fun clearScrollback() = Unit
        }
        override val device = object : DeviceInfo {
            override val androidRelease = "16"
            override val model = "pixel"
            override fun appCount() = 0
            override fun scrollbackLines() = 0
        }
        override val commands: List<Command> = emptyList()
        override fun isReservedName(name: String) = false
        override fun startRecording(name: String) = Unit
    }
}
