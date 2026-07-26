package dev.tty.core.command

import dev.tty.core.command.builtin.AppsCommand
import dev.tty.core.command.builtin.CatCommand
import dev.tty.core.command.builtin.CdCommand
import dev.tty.core.command.builtin.ClearCommand
import dev.tty.core.command.builtin.CpCommand
import dev.tty.core.command.builtin.DfCommand
import dev.tty.core.command.builtin.DuCommand
import dev.tty.core.command.builtin.FindCommand
import dev.tty.core.command.builtin.HeadCommand
import dev.tty.core.command.builtin.HelpCommand
import dev.tty.core.command.builtin.InfoCommand
import dev.tty.core.command.builtin.KillCommand
import dev.tty.core.command.builtin.LsCommand
import dev.tty.core.command.builtin.MkdirCommand
import dev.tty.core.command.builtin.MountCommand
import dev.tty.core.command.builtin.MvCommand
import dev.tty.core.command.builtin.OpenCommand
import dev.tty.core.command.builtin.PwdCommand
import dev.tty.core.command.builtin.RmCommand
import dev.tty.core.command.builtin.ScriptCommand
import dev.tty.core.command.builtin.SettingsCommand
import dev.tty.core.command.builtin.ShCommand
import dev.tty.core.command.builtin.TailCommand
import dev.tty.core.command.builtin.TmuxCommand
import dev.tty.core.command.builtin.TouchCommand
import dev.tty.core.command.builtin.UninstallCommand
import dev.tty.core.text.Columns
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El criterio de aceptación 4: **`help` cabe en una pantalla sin scroll horizontal**
 * (functional.md §13).
 *
 * Es el único criterio de la lista que se puede comprobar sin un dispositivo, y hasta ahora no lo
 * comprobaba nadie: se escribía un `summary` y se confiaba en que cupiera. Con 26 verbos eso ya
 * había dejado de ser verdad —la fila más ancha rondaba las 78 celdas—, y una tabla que desborda no
 * se lee: en un terminal la línea larga se corta o se parte, y `help` es *la única documentación del
 * producto*.
 *
 * Por eso este test mide la salida real —los mismos comandos, el mismo `Columns.twoColumns`— y no
 * una muestra. Su razón de ser es futura: el día que alguien añada un verbo con un `summary`
 * cómodo de escribir, esto falla antes de que llegue a una pantalla.
 *
 * Lo que **no** cubre: la ficha individual (`help kill`), que imprime sintaxis y descripción en
 * líneas sueltas y no forma tabla; y el número de filas, que sí necesita una pantalla para juzgarse.
 */
class HelpWidthTest {

    private companion object {

        /**
         * Ancho máximo de una fila de `help`, en **celdas de carácter**.
         *
         * De dónde sale el número: 412dp de ancho lógico (el móvil corriente de hoy), menos los dos
         * márgenes de 20dp de `Spacing.Gutter`, dejan 372dp de texto. Una monoespaciada avanza unos
         * 0,6em por carácter, que a los 13sp del cuerpo son ~7,8dp, más el tracking de 0,01em: ~7,9dp
         * por celda. 372 / 7,9 ≈ 47 celdas, y se deja una de margen.
         *
         * Es deliberadamente el ancho **cómodo**, no el máximo teórico: en un móvil estrecho de
         * 360dp caben menos, y la fila que aquí pasa raspando allí se parte. Bajar este número es
         * legítimo; subirlo para que quepa un `summary` nuevo es exactamente lo que el test impide.
         */
        const val ANCHO_MAXIMO = 46
    }

    /**
     * El vocabulario entero, tal y como lo registra `AppContainer`.
     *
     * Se puede construir aquí porque todos los comandos viven en `core/` y no reciben `Context`: el
     * registro real cabe en un test de JVM sin emulador. Se pasa por [CommandRegistry] y no por una
     * lista suelta para que el orden sea el mismo que ve el usuario — `all` ordena por nombre.
     */
    private val registro = CommandRegistry(
        listOf(
            HelpCommand,
            AppsCommand,
            OpenCommand,
            ClearCommand,
            KillCommand,
            UninstallCommand,
            InfoCommand,
            SettingsCommand,
            PwdCommand,
            CdCommand,
            LsCommand,
            CatCommand,
            HeadCommand,
            TailCommand,
            MkdirCommand,
            TouchCommand,
            RmCommand,
            MvCommand,
            CpCommand,
            DfCommand,
            DuCommand,
            FindCommand,
            MountCommand,
            ScriptCommand,
            ShCommand,
            TmuxCommand,
        ),
    )

    /** Las filas exactamente como las arma `HelpCommand`: sintaxis a la izquierda, `summary` a la derecha. */
    private fun filas(): List<Pair<String, String>> = registro.all.map { it.syntax to it.summary }

    @Test
    fun `ninguna fila de help supera el ancho de la pantalla`() {
        // Se renderiza con el mismo formateador que usa el comando, no con una suma de longitudes:
        // el relleno de la primera columna depende del comando más ancho de todos, así que un verbo
        // nuevo con una sintaxis larga empuja a las otras 25 filas. Medir la fila ya formateada es
        // lo único que captura ese efecto.
        val desbordadas = Columns.twoColumns(filas())
            .filter { it.length > ANCHO_MAXIMO }
            .map { "${it.length} celdas: $it" }

        assertTrue(
            "estas filas de `help` no caben en una pantalla (máximo $ANCHO_MAXIMO celdas). " +
                "Acorta el `summary`, no subas el límite:\n" + desbordadas.joinToString("\n"),
            desbordadas.isEmpty(),
        )
    }

    @Test
    fun `la sintaxis mas ancha deja sitio para la descripcion`() {
        // `twoColumns` topa la primera columna en 24 celdas, pero **no trunca**: una sintaxis más
        // larga desborda esa columna y se come el sitio de su propia descripción. Sin este test, un
        // verbo con una sintaxis kilométrica pasaría el test de arriba a base de dejar el `summary`
        // en dos palabras ilegibles, o directamente no cabría con ningún texto detrás.
        val masAncha = Columns.widthOf(filas())
        val disponible = ANCHO_MAXIMO - masAncha - Columns.GAP

        assertTrue(
            "la sintaxis más larga ocupa $masAncha celdas y con el hueco de ${Columns.GAP} deja " +
                "$disponible para la descripción: no da para una frase. Simplifica la sintaxis.",
            disponible >= 10,
        )
    }

    @Test
    fun `el vocabulario de este test es el que registra AppContainer`() {
        // El agujero obvio de un test que se construye su propio registro: alguien añade un verbo en
        // `AppContainer`, no lo añade aquí, y el ancho de `help` deja de estar medido sin que nada
        // se ponga rojo. `AppContainer` vive en `platform/` y necesita un `Context`, así que se lee
        // como texto —igual que hace `ArchitectureTest` con los imports prohibidos— y se comparan
        // los verbos.
        val enAppContainer = verbosDeAppContainer()
        val enElTest = registro.all.map { it.name }.toSet()

        assertTrue(
            "verbos registrados en AppContainer que este test no mide: " +
                (enAppContainer - enElTest).sorted().joinToString(", ") +
                " — añádelos a la lista de arriba",
            (enAppContainer - enElTest).isEmpty(),
        )
        assertTrue(
            "verbos que este test mide y que ya no existen en AppContainer: " +
                (enElTest - enAppContainer).sorted().joinToString(", "),
            (enElTest - enAppContainer).isEmpty(),
        )
    }

    /**
     * Los verbos que `AppContainer` mete en el registro, leyendo el fuente.
     *
     * Se apoya en la convención de nombres del proyecto: cada entrada de la lista es un
     * `<Verbo>Command`, y el verbo en minúsculas es el nombre del comando. Si algún día se registra
     * algo que no siga esa forma, esta función no lo verá — por eso el fallo que importa es el que
     * compara conjuntos, y no un recuento.
     */
    private fun verbosDeAppContainer(): Set<String> {
        val lineas = ficheroDeAppContainer().readLines()
        val inicio = lineas.indexOfFirst { it.contains("CommandRegistry(") }
        assertTrue(
            "no encuentro la construcción del CommandRegistry en AppContainer.kt: " +
                "si el registro se ha movido, este test hay que reapuntarlo",
            inicio >= 0,
        )

        val entrada = Regex("^([A-Z][A-Za-z0-9]*)Command,$")
        val verbos = mutableSetOf<String>()
        for (linea in lineas.drop(inicio + 1)) {
            val texto = linea.trim()
            // El cierre del propio `CommandRegistry(`; el `),` de antes cierra el `listOf(`.
            if (texto == ")") break
            entrada.find(texto)?.let { verbos += it.groupValues[1].lowercase() }
        }

        assertTrue(
            "no he leído ni un verbo de AppContainer.kt: el test estaría comparando con el vacío",
            verbos.isNotEmpty(),
        )
        return verbos
    }

    /**
     * Gradle ejecuta los tests con el directorio de trabajo en el del módulo (`app/`); se prueba
     * también desde la raíz por si se lanzan desde un IDE mal configurado, y si no aparece se falla
     * diciéndolo, en vez de dar por bueno lo que no se ha mirado.
     */
    private fun ficheroDeAppContainer(): File {
        val candidatos = listOf(
            File("src/main/kotlin/dev/tty/platform/AppContainer.kt"),
            File("app/src/main/kotlin/dev/tty/platform/AppContainer.kt"),
        )
        return candidatos.firstOrNull { it.isFile }
            ?: throw AssertionError(
                "no encuentro AppContainer.kt desde ${File(".").absolutePath} — probado: " +
                    candidatos.joinToString(", ") { it.path },
            )
    }
}
