package dev.tty.core

import dev.tty.core.command.CommandContext
import dev.tty.core.command.CommandRegistry
import dev.tty.core.output.Line
import dev.tty.core.output.LineGlyph
import dev.tty.core.output.Output
import dev.tty.core.output.Role
import dev.tty.core.parse.CommandLine
import dev.tty.core.scrollback.Scrollback
import dev.tty.core.script.Budget
import dev.tty.core.script.Recording
import dev.tty.core.script.Script
import dev.tty.core.script.ScriptRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * El ciclo completo de una entrada: eco → despacho → salida.
 *
 * Es Kotlin puro y no sabe nada de Android ni de Compose: se puede ejecutar entero en un test de
 * JVM, que es justo lo que hace que el motor sea barato de verificar sin emulador.
 *
 * Aquí vive el **orden de resolución** (functional.md §8.4):
 *
 *     comando incorporado → script → error
 *
 * Un script nunca puede sombrear un comando. Si se permitiera, un script llamado `rm` sería la
 * forma trivial de hacer que un verbo de confianza haga otra cosa. Por eso el intérprete no resuelve
 * nombres: solo ejecuta lo que este motor le da ya resuelto.
 */
class TerminalEngine(
    private val registry: CommandRegistry,
    private val scrollback: Scrollback,
    private val context: CommandContext,
    private val scripts: ScriptRunner? = null,
) {

    /** Las últimas ~50 líneas escritas (§5.4). Volátil a propósito: ver [InputHistory]. */
    val history = InputHistory()

    /** El modo grabación, si está activo. Estado del motor, no del intérprete (§8.2). */
    var recording: Recording? = null
        private set

    /** El símbolo del prompt: `…` mientras se graba, `>` el resto del tiempo. */
    val promptSymbol: String get() = if (recording != null) "…" else ">"

    /** Arranca el modo grabación. Lo pide `script new`; el motor es quien lo sostiene. */
    fun startRecording(name: String) {
        recording = Recording(name)
    }

    /**
     * Ejecuta una línea escrita en el prompt y devuelve **las líneas nuevas**, de la más antigua a
     * la más reciente, para que la UI sepa cuáles animar (las persistidas no se animan, §5.2).
     *
     * Reglas de la §5.3 que se cumplen aquí y no en la UI:
     * - Entrada vacía: **no hace nada, no imprime, no da error**.
     * - La entrada **se ecoa siempre antes de la salida**.
     */
    suspend fun submit(input: String): List<Line> {
        val trimmed = input.trim()

        // El modo grabación se mira ANTES de parsear: mientras dura, una línea no es un comando,
        // es texto que se captura. Grabar `uninstall whatsapp` no puede desinstalar nada.
        recording?.let { return captureWhileRecording(trimmed, it) }

        val parsed = CommandLine.parse(input) ?: return emptyList()

        // Se recuerda lo que se escribió, no lo que se ejecutó: si el comando falla, el usuario
        // querrá recuperar exactamente su línea para corregirla.
        history.remember(trimmed)

        val added = mutableListOf<Line>()
        val echo = scrollback.add(trimmed, Role.ECHO)
        added += echo

        val output = runSafely(parsed.verb) { dispatch(parsed, Budget()) }

        // El glifo del eco se decide DESPUÉS, porque es el resultado de su propio comando: `OK` si
        // dejó salida, `FAIL` si falló, y ninguno si el éxito fue silencioso —`open` no imprime
        // nada, y adornarlo con un glifo sería ruido (§10).
        val glyph = when {
            output.failed -> LineGlyph.FAIL
            output.lines.isNotEmpty() -> LineGlyph.OK
            else -> null
        }
        if (glyph != null) {
            scrollback.setGlyph(echo.id, glyph)
            // Y también en la lista que se devuelve: es la que `AppContainer` persiste. Actualizar
            // solo el scrollback dejaba el glifo fuera del disco, y al reiniciar el historial
            // parecía que todo había ido bien.
            added[0] = echo.copy(glyph = glyph)
        }

        for ((text, role) in output.lines) {
            // Cada línea de error lleva el suyo: al recorrer el historial se ve dónde falló sin
            // tener que leer el texto.
            added += scrollback.add(text, role, if (role == Role.ERROR) LineGlyph.FAIL else null)
        }
        return added
    }

    /**
     * Resuelve y ejecuta: primero el vocabulario cerrado, después los scripts, y si no, error.
     *
     * El [Budget] viaja por toda la invocación —no se crea uno nuevo por script— para que cuatro
     * scripts anidados no puedan ejecutar 800 líneas entre todos respetando el límite cada uno.
     */
    private suspend fun dispatch(parsed: CommandLine, budget: Budget): Output {
        // **Todo comando sale del hilo principal.** Un `find` o un `du` sobre un árbol grande de
        // /sdcard bloquearía la actividad HOME durante segundos, y un ANR ahí es indistinguible de
        // un móvil bloqueado (§16). Va aquí y no en cada verbo para que no dependa de que quien
        // escriba el siguiente se acuerde.
        //
        // `kotlinx.coroutines` no rompe la pureza de `core/`: no es `android.*`, y el test de la
        // frontera lo comprueba.
        registry[parsed.verb]?.let { command ->
            return withContext(Dispatchers.IO) { command.run(parsed, context) }.truncated()
        }

        val runner = scripts
        if (runner != null) {
            val script = runner.find(parsed.verb)
            if (script != null) return runScript(runner, script, parsed.tokens, budget)
        }
        return registry.unknownVerb(parsed.verb)
    }

    private suspend fun runScript(
        runner: ScriptRunner,
        script: Script,
        args: List<String>,
        budget: Budget,
    ): Output = runner.run(script, args, budget) { line, innerBudget ->
        val parsed = CommandLine.parse(line)
            ?: return@run Output.silent
        runSafely(parsed.verb) { dispatch(parsed, innerBudget) }
    }.truncated()

    /**
     * Captura una línea en modo grabación.
     *
     * `end` guarda, `abort` descarta, y **nada más se ejecuta**. Las dos palabras no son comandos:
     * solo significan esto aquí dentro, que es lo que permite grabar un script llamado `end` sin
     * que el modo se vuelva imposible de cerrar… y también por qué no se puede grabar una línea que
     * sea literalmente `end`. Es el precio de no tener un esquema de escapado, y está asumido.
     */
    private suspend fun captureWhileRecording(line: String, current: Recording): List<Line> {
        val added = mutableListOf<Line>()

        when (line.lowercase()) {
            Recording.END -> {
                recording = null
                added += scrollback.add(line, Role.RECORDING)
                val saved = context.scripts.write(Script(current.name, current.lines))
                added += if (saved) {
                    scrollback.add(current.summary, Role.STATUS)
                } else {
                    scrollback.add("script: could not save '${current.name}'", Role.ERROR)
                }
            }

            Recording.ABORT -> {
                recording = null
                added += scrollback.add(line, Role.RECORDING)
                added += scrollback.add(Recording.ABORTED, Role.STATUS)
            }

            else -> {
                // Una línea vacía dentro de la grabación no se guarda ni se ecoa: sigue valiendo
                // la §5.3.
                if (line.isEmpty()) return emptyList()
                recording = current.capture(line)
                added += scrollback.add(line, Role.RECORDING)
            }
        }
        return added
    }

    /**
     * Ningún comando debe poder dejar la actividad en un estado no recuperable (§16).
     *
     * Un fallo se cuenta, no se traga: el scrollback es el único sitio donde el usuario puede verlo,
     * porque no hay logcat en un móvil de diario.
     */
    private suspend fun runSafely(verb: String, body: suspend () -> Output): Output = try {
        body()
    } catch (e: Throwable) {
        Output.error("$verb: failed — ${e::class.simpleName}: ${e.message ?: "no message"}")
    }
}
