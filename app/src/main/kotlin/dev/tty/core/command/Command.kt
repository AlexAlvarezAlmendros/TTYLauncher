package dev.tty.core.command

import dev.tty.core.apps.AppActions
import dev.tty.core.apps.AppCatalog
import dev.tty.core.apps.AppKiller
import dev.tty.core.output.Output
import dev.tty.core.parse.CommandLine

/**
 * Un verbo del vocabulario cerrado (functional.md §3).
 *
 * Todo lo que el launcher puede ejecutar implementa esta interfaz y está registrado en
 * [CommandRegistry]. Si no está aquí, no existe: eso es lo que significa «vocabulario cerrado».
 */
interface Command {

    /** El nombre canónico. **El mismo en la sintaxis, en `help` y en los errores** (§10). */
    val name: String

    /** Alias equivalentes. `help` los imprime; los errores nunca los usan. */
    val aliases: Set<String> get() = emptySet()

    /** La sintaxis tal y como la imprime `help`: `apps [-s] [filtro]`. */
    val syntax: String

    /**
     * Qué hace, en **una línea**. Si un comando necesita más explicación de la que cabe aquí, el
     * comando está mal diseñado (§6.2).
     */
    val summary: String

    /**
     * Ejecuta. Devolver [Output.silent] es lo normal: el éxito silencioso es el valor por defecto
     * (§10).
     *
     * No lanza excepciones: un fallo se devuelve como [Output.error]. Una excepción sin capturar
     * aquí crashea la actividad HOME, que es funcionalmente un móvil bloqueado.
     */
    suspend fun run(line: CommandLine, ctx: CommandContext): Output
}

/**
 * Lo que un comando puede tocar del mundo exterior. Todo son interfaces declaradas en `core/` e
 * implementadas en `platform/`: es lo que permite ejecutar el motor entero en un test de JVM.
 */
interface CommandContext {
    val catalog: AppCatalog
    val actions: AppActions

    /**
     * Detener una app, detrás de su propia interfaz. Ver `AppKiller`: hoy solo se puede abrir el
     * diálogo del sistema, y el día que haya un backend con privilegios el comando no cambia.
     */
    val killer: AppKiller
    val session: Session
    val device: DeviceInfo

    /** Los comandos registrados, para que `help` pueda enumerarlos sin conocerlos. */
    val commands: List<Command>
}

/** Lo que un comando puede hacerle a la sesión. */
interface Session {
    /** Vacía el scrollback en memoria **y en disco**. El único borrado real del producto (§6.2). */
    suspend fun clearScrollback()
}

/** Los números reales del dispositivo, para el banner (§11). */
interface DeviceInfo {
    val androidRelease: String
    val model: String
    fun appCount(): Int
    fun scrollbackLines(): Int
}

/**
 * El registro del vocabulario.
 *
 * El orden de resolución es **comando incorporado → script → error** (§8.4). Un script nunca puede
 * sombrear un comando: si se permitiera, un script llamado `rm` sería la forma trivial de hacer que
 * un verbo de confianza haga otra cosa. Los scripts llegan en la Fase 3; el hueco está aquí para
 * que el orden quede fijado desde el principio.
 */
class CommandRegistry(commands: List<Command>) {

    val all: List<Command> = commands.sortedBy { it.name }

    private val byName: Map<String, Command> = buildMap {
        for (c in commands) {
            put(c.name, c)
            for (a in c.aliases) put(a, c)
        }
    }

    operator fun get(verb: String): Command? = byName[verb.lowercase()]

    /** Si el nombre ya está cogido por un comando. Lo usa `script new` para rechazarlo (§8.2). */
    fun isReserved(name: String): Boolean = name.lowercase() in byName

    /**
     * Resuelve y ejecuta. Un verbo desconocido no es una excepción, es una línea de error con la
     * forma que espera quien viene de una terminal.
     */
    suspend fun run(line: CommandLine, ctx: CommandContext): Output {
        val command = this[line.verb]
            ?: return Output.error(
                "${line.verb}: command not found" +
                    dev.tty.core.text.Suggest.hint(line.verb, byName.keys)
                        .ifEmpty { " — type help" },
            )
        return command.run(line, ctx).truncated()
    }
}
