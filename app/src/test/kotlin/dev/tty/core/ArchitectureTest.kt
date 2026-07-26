package dev.tty.core

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La frontera de `core/`.
 *
 * `core/` es Kotlin puro: **ni un solo import de `android.*` ni de `androidx.*`**. No es una
 * preferencia de estilo — es lo que permite ejecutar el motor entero en la JVM, sin emulador, que
 * es todo el testing que hay. El día que se cuele un import de plataforma, este test es lo único
 * que lo dice.
 *
 * Se lee el código fuente como texto en vez de mirar el classpath porque no hay ninguna librería
 * de análisis declarada, y porque un import es exactamente lo que se quiere prohibir.
 */
class ArchitectureTest {

    /**
     * Gradle ejecuta los tests con el directorio de trabajo puesto en el del módulo (`app/`). Se
     * prueba también desde la raíz del repo por si se lanzan desde un IDE mal configurado, y si no
     * aparece por ningún lado **se falla diciéndolo**: un test de frontera que pasa porque no ha
     * mirado nada es peor que no tenerlo.
     */
    private fun coreDir(): File {
        val candidatos = listOf(
            File("src/main/kotlin/dev/tty/core"),
            File("app/src/main/kotlin/dev/tty/core"),
        )
        return candidatos.firstOrNull { it.isDirectory }
            ?: throw AssertionError(
                "no encuentro el directorio de core/ desde ${File(".").absolutePath} — " +
                    "probado: " + candidatos.joinToString(", ") { it.path },
            )
    }

    private fun ficherosKt(): List<File> {
        val dir = coreDir()
        val ficheros = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(
            "no hay ningún .kt bajo ${dir.absolutePath}: el test estaría pasando en vacío",
            ficheros.isNotEmpty(),
        )
        return ficheros
    }

    /**
     * Los tokens de una línea de import, o `null` si la línea no es un import.
     *
     * Se compara token a token en vez de buscar la cadena en crudo para no saltar por un comentario
     * que hable de `import android.util.AtomicFile` —que es justo el tipo de nota que hay en la
     * documentación— y para no dejar escapar un `import   android.x` con espacios de más.
     */
    private fun importadoEn(linea: String): String? {
        val partes = linea.trim().split(Regex("\\s+"))
        if (partes.size < 2 || partes[0] != "import") return null
        return partes[1]
    }

    @Test
    fun `core no importa android ni androidx`() {
        val infractores = mutableListOf<String>()

        for (fichero in ficherosKt()) {
            fichero.readLines().forEachIndexed { indice, linea ->
                val importado = importadoEn(linea) ?: return@forEachIndexed
                if (importado.startsWith("android.") || importado.startsWith("androidx.")) {
                    infractores += "${fichero.path}:${indice + 1}  $importado"
                }
            }
        }

        assertTrue(
            "core/ tiene que poder compilarse y ejecutarse sin Android. Imports prohibidos:\n" +
                infractores.joinToString("\n"),
            infractores.isEmpty(),
        )
    }

    @Test
    fun `core no importa platform ni ui`() {
        // La otra mitad de la misma regla: core/ no conoce a nadie, y platform/ y ui/ implementan
        // sus interfaces. Sin ciclos.
        val infractores = mutableListOf<String>()

        for (fichero in ficherosKt()) {
            fichero.readLines().forEachIndexed { indice, linea ->
                val importado = importadoEn(linea) ?: return@forEachIndexed
                if (importado.startsWith("dev.tty.platform.") || importado.startsWith("dev.tty.ui.")) {
                    infractores += "${fichero.path}:${indice + 1}  $importado"
                }
            }
        }

        assertTrue(
            "core/ no puede depender de platform/ ni de ui/. Imports prohibidos:\n" +
                infractores.joinToString("\n"),
            infractores.isEmpty(),
        )
    }

    @Test
    fun `todos los ficheros de core declaran un paquete dentro de dev tty core`() {
        // Barato, y evita el fallo tonto de un fichero movido de sitio al que se le olvidó el
        // paquete: quedaría fuera del alcance de los dos tests de arriba sin que nadie lo note.
        val infractores = mutableListOf<String>()

        for (fichero in ficherosKt()) {
            val paquete = fichero.readLines()
                .firstOrNull { it.trim().startsWith("package ") }
                ?.trim()
                ?.removePrefix("package ")
                ?.trim()
            if (paquete == null || !(paquete == "dev.tty.core" || paquete.startsWith("dev.tty.core."))) {
                infractores += "${fichero.path}  →  ${paquete ?: "sin package"}"
            }
        }

        assertTrue(
            "ficheros bajo core/ con un paquete que no es dev.tty.core:\n" +
                infractores.joinToString("\n"),
            infractores.isEmpty(),
        )
    }
}
