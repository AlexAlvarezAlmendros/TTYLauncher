package dev.tty.core.command.builtin

import dev.tty.core.command.Command
import dev.tty.core.command.CommandContext
import dev.tty.core.fs.Cage
import dev.tty.core.fs.FileOps
import dev.tty.core.fs.FsError
import dev.tty.core.fs.FsResult
import dev.tty.core.output.Output
import dev.tty.core.output.Role
import dev.tty.core.parse.CommandLine
import dev.tty.core.parse.Flags
import dev.tty.core.text.Columns
import java.nio.file.Path

/**
 * Los quince verbos de fichero (functional.md §6.3).
 *
 * Siguen siendo vocabulario cerrado: sin `$PATH`, sin pipes, sin redirecciones, **sin globbing** y
 * sin ejecución de binarios. Para eso está `sh`, que es la única escotilla y es explícita.
 *
 * Toda ruta pasa por la jaula antes de tocar el disco. Ninguno de estos comandos resuelve una ruta
 * por su cuenta.
 */

/** Lo que un comando de fichero necesita del mundo: la jaula y el permiso. */
interface FileSystemAccess {
    val cage: Cage

    /** Si hay permiso para leer la raíz. Sin él, todo verbo de fichero falla explicando qué hacer. */
    fun hasStorageAccess(): Boolean

    /** Abre la pantalla de Ajustes donde se concede. Lo usa `mount`. */
    fun requestStorageAccess(): Boolean
}

/**
 * Comprueba el permiso y resuelve la ruta. Es el único camino: si un verbo resolviera por su
 * cuenta, la jaula dejaría de ser una jaula.
 */
private inline fun withPath(
    verb: String,
    ctx: CommandContext,
    arg: String,
    mustExist: Boolean = true,
    /** Solo `mkdir -p`: el destino puede colgar de una cadena de padres que aún no existe. */
    creatingParents: Boolean = false,
    body: (Path) -> Output,
): Output {
    val fs = ctx.files
    if (!fs.hasStorageAccess()) return Output.error("$verb: ${FsError.NoStorageAccess.message}")

    // El cwd puede haber desaparecido bajo los pies —otra app borró la carpeta, se desmontó el
    // almacenamiento—. Volver a la raíz en silencio es peor que decirlo.
    val recovered = !fs.cage.revalidateCwd()

    val resolved = fs.cage.resolve(arg)
    val checked = when {
        mustExist -> fs.cage.check(resolved)
        creatingParents -> fs.cage.checkParents(resolved)
        else -> fs.cage.checkParent(resolved)
    }
    val path = when (checked) {
        is FsResult.Failure -> return Output.error("$verb: ${display(fs.cage, arg)}: ${checked.error.message}")
        is FsResult.Success -> checked.value
    }

    val out = body(path)
    return if (recovered) {
        Output(listOf("cwd was gone — back at /" to Role.STATUS) + out.lines)
    } else {
        out
    }
}

private fun display(cage: Cage, arg: String): String = arg.ifEmpty { cage.display() }

private fun fail(verb: String, arg: String, error: FsError): Output =
    Output.error("$verb: $arg: ${error.message}")

// ---------------------------------------------------------------------- navegación

object PwdCommand : Command {
    override val name = "pwd"
    override val syntax = "pwd"
    override val summary = "working directory"

    override suspend fun run(line: CommandLine, ctx: CommandContext): Output {
        ctx.files.cage.revalidateCwd()
        return Output.of(ctx.files.cage.display())
    }
}

/**
 * `cd` — cambia el directorio de trabajo.
 *
 * Valida **en el momento**. Nada de un `cd` optimista que acepta cualquier cosa y hace que falle el
 * comando siguiente: `cd` es el gesto que más se repite y tiene que responder al instante y fallar
 * al instante.
 *
 * Éxito silencioso: si funcionó, `pwd` lo dice y la siguiente orden lo demuestra.
 */
object CdCommand : Command {
    override val name = "cd"
    override val syntax = "cd [path]"
    override val summary = "change directory"

    override suspend fun run(line: CommandLine, ctx: CommandContext): Output {
        val fs = ctx.files
        if (!fs.hasStorageAccess()) return Output.error("$name: ${FsError.NoStorageAccess.message}")
        val arg = line.tokens.joinToString(" ").trim()
        return when (val r = fs.cage.cd(arg)) {
            is FsResult.Success -> Output.silent
            is FsResult.Failure -> fail(name, arg.ifEmpty { "/" }, r.error)
        }
    }
}

// ---------------------------------------------------------------------- leer

object LsCommand : Command {
    override val name = "ls"
    override val syntax = "ls [-l] [-a] [path]"
    override val summary = "list a directory"

    override suspend fun run(line: CommandLine, ctx: CommandContext): Output {
        val flags = Flags.parse(line.tokens, known = setOf('l', 'a'))
        if (flags.unknown.isNotEmpty()) return unknownFlags(name, flags.unknown)
        val arg = flags.operands.joinToString(" ").trim()

        return withPath(name, ctx, arg) { path ->
            when (val r = FileOps.list(path, includeHidden = 'a' in flags, long = 'l' in flags)) {
                is FsResult.Failure -> fail(name, display(ctx.files.cage, arg), r.error)
                is FsResult.Success -> {
                    val entries = r.value
                    if (entries.isEmpty()) return@withPath Output.status("empty")

                    val lines = if ('l' in flags) {
                        Columns.twoColumns(
                            entries.map { e ->
                                "${e.mode ?: "?"} ${FileOps.humanBytes(e.size)}" to e.display
                            },
                            maxFirst = 16,
                        )
                    } else {
                        entries.map { it.display }
                    }
                    val total = "${entries.size} ${if (entries.size == 1) "entry" else "entries"}"
                    Output(
                        lines.map { it to Role.OUTPUT } +
                            ("" to Role.OUTPUT) +
                            (total to Role.STATUS),
                    )
                }
            }
        }
    }
}

object CatCommand : Command {
    override val name = "cat"
    override val syntax = "cat <file>"
    override val summary = "print a text file"

    override suspend fun run(line: CommandLine, ctx: CommandContext): Output {
        val arg = line.tokens.joinToString(" ").trim()
        if (arg.isEmpty()) return Output.error("$name: needs a file")
        return withPath(name, ctx, arg) { path ->
            when (val r = FileOps.read(path)) {
                is FsResult.Failure -> fail(name, arg, r.error)
                is FsResult.Success -> Output.output(r.value)
            }
        }
    }
}

/** `head` y `tail` comparten todo salvo por qué punta del fichero empiezan. */
private class EndsCommand(
    override val name: String,
    private val fromTail: Boolean,
) : Command {
    override val syntax = "$name [-n N] <file>"
    override val summary = if (fromTail) "last lines" else "first lines"

    override suspend fun run(line: CommandLine, ctx: CommandContext): Output {
        val flags = Flags.parse(line.tokens, withValue = setOf('n'))
        if (flags.unknown.isNotEmpty()) return unknownFlags(name, flags.unknown)

        val n = flags.value('n')?.toIntOrNull()
        if (flags.value('n') != null && (n == null || n <= 0)) {
            return Output.error("$name: -n needs a positive number")
        }
        val count = n ?: 10

        val arg = flags.operands.joinToString(" ").trim()
        if (arg.isEmpty()) return Output.error("$name: needs a file")

        return withPath(name, ctx, arg) { path ->
            val r = if (fromTail) FileOps.tail(path, count) else FileOps.head(path, count)
            when (r) {
                is FsResult.Failure -> fail(name, arg, r.error)
                is FsResult.Success -> Output.output(r.value)
            }
        }
    }
}

val HeadCommand: Command = EndsCommand("head", fromTail = false)
val TailCommand: Command = EndsCommand("tail", fromTail = true)

// ---------------------------------------------------------------------- escribir

object MkdirCommand : Command {
    override val name = "mkdir"
    override val syntax = "mkdir [-p] <path>"
    override val summary = "make a directory"

    override suspend fun run(line: CommandLine, ctx: CommandContext): Output {
        val flags = Flags.parse(line.tokens, known = setOf('p'))
        if (flags.unknown.isNotEmpty()) return unknownFlags(name, flags.unknown)
        val arg = flags.operands.joinToString(" ").trim()
        if (arg.isEmpty()) return Output.error("$name: needs a name")

        // Con `-p` el destino puede colgar de padres que aún no existen, así que la jaula tiene que
        // canonicalizar el ancestro más profundo que sí exista en vez de exigir el padre inmediato.
        return withPath(name, ctx, arg, mustExist = false, creatingParents = 'p' in flags) { path ->
            when (val r = FileOps.mkdir(path, parents = 'p' in flags)) {
                is FsResult.Failure -> fail(name, arg, r.error)
                is FsResult.Success -> Output.silent
            }
        }
    }
}

object TouchCommand : Command {
    override val name = "touch"
    override val syntax = "touch <file>"
    override val summary = "make an empty file"

    override suspend fun run(line: CommandLine, ctx: CommandContext): Output {
        val arg = line.tokens.joinToString(" ").trim()
        if (arg.isEmpty()) return Output.error("$name: needs a name")
        return withPath(name, ctx, arg, mustExist = false) { path ->
            when (val r = FileOps.touch(path)) {
                is FsResult.Failure -> fail(name, arg, r.error)
                is FsResult.Success -> Output.silent
            }
        }
    }
}

/**
 * `rm` — borra sin preguntar, con tres cinturones (functional.md §6.3).
 *
 * Un prompt de confirmación en una terminal es una traición, igual que en `clear`. Lo que sí hay:
 *
 * 1. Sobre un directorio **falla** y pide `-r` explícitamente.
 * 2. `rm -r` dice **cuántas entradas** borró — nada silencioso que sea destructivo.
 * 3. Se niega sobre la raíz, sobre el cwd y sobre cualquier ancestro suyo.
 *
 * Y la revalidación de la ruta ocurre **justo antes** de borrar, no solo al parsear: el symlink
 * puede cambiar entre una cosa y la otra.
 */
object RmCommand : Command {
    override val name = "rm"
    override val syntax = "rm [-r] <path>"
    override val summary = "remove for good"

    override suspend fun run(line: CommandLine, ctx: CommandContext): Output {
        val flags = Flags.parse(line.tokens, known = setOf('r'))
        if (flags.unknown.isNotEmpty()) return unknownFlags(name, flags.unknown)
        val arg = flags.operands.joinToString(" ").trim()
        if (arg.isEmpty()) return Output.error("$name: needs a path")

        return withPath(name, ctx, arg) { path ->
            val cage = ctx.files.cage

            cage.refusesToDelete(path)?.let { return@withPath fail(name, arg, it) }

            // TOCTOU: se revalida contra la jaula inmediatamente antes de borrar.
            if (!cage.contains(path)) return@withPath fail(name, arg, FsError.OutsideRoot)

            when (val r = FileOps.remove(path, recursive = 'r' in flags)) {
                is FsResult.Failure -> fail(name, arg, r.error)
                is FsResult.Success ->
                    if ('r' in flags) {
                        val n = r.value
                        Output.status("removed $n ${if (n == 1) "entry" else "entries"}")
                    } else {
                        Output.silent
                    }
            }
        }
    }
}

/** `mv` y `cp` comparten la forma: dos rutas, la primera existe y la segunda puede no existir. */
private class TwoPathCommand(
    override val name: String,
    private val recursiveFlag: Boolean,
) : Command {
    override val syntax = if (recursiveFlag) "$name [-r] <source> <destination>" else "$name <source> <destination>"
    override val summary = if (recursiveFlag) "copy a file" else "move or rename"

    override suspend fun run(line: CommandLine, ctx: CommandContext): Output {
        val flags = Flags.parse(line.tokens, known = if (recursiveFlag) setOf('r') else emptySet())
        if (flags.unknown.isNotEmpty()) return unknownFlags(name, flags.unknown)
        if (flags.operands.size != 2) {
            return Output.error("$name: needs a source and a destination")
        }

        val fs = ctx.files
        if (!fs.hasStorageAccess()) return Output.error("$name: ${FsError.NoStorageAccess.message}")
        fs.cage.revalidateCwd()

        val (fromArg, toArg) = flags.operands
        val from = when (val r = fs.cage.check(fs.cage.resolve(fromArg))) {
            is FsResult.Failure -> return fail(name, fromArg, r.error)
            is FsResult.Success -> r.value
        }
        // El destino puede no existir todavía; si existe y es un directorio, se entra en él.
        val toResolved = fs.cage.resolve(toArg)
        val to = when (val r = fs.cage.check(toResolved)) {
            is FsResult.Success -> r.value
            is FsResult.Failure -> when (val p = fs.cage.checkParent(toResolved)) {
                is FsResult.Failure -> return fail(name, toArg, p.error)
                is FsResult.Success -> p.value
            }
        }

        if (name == "mv") {
            fs.cage.refusesToDelete(from)?.let { return fail(name, fromArg, it) }
        }

        val r = if (recursiveFlag) {
            FileOps.copy(from, to, recursive = 'r' in flags)
        } else {
            when (val m = FileOps.move(from, to)) {
                is FsResult.Failure -> m
                is FsResult.Success -> FsResult.Success(1)
            }
        }
        return when (r) {
            is FsResult.Failure -> fail(name, fromArg, r.error)
            is FsResult.Success -> Output.silent
        }
    }
}

val MvCommand: Command = TwoPathCommand("mv", recursiveFlag = false)
val CpCommand: Command = TwoPathCommand("cp", recursiveFlag = true)

// ---------------------------------------------------------------------- medir y buscar

object DfCommand : Command {
    override val name = "df"
    override val syntax = "df"
    override val summary = "free space"

    override suspend fun run(line: CommandLine, ctx: CommandContext): Output {
        val fs = ctx.files
        if (!fs.hasStorageAccess()) return Output.error("$name: ${FsError.NoStorageAccess.message}")

        return when (val r = FileOps.space(fs.cage.root)) {
            is FsResult.Failure -> Output.error("$name: ${r.error.message}")
            is FsResult.Success -> {
                val (total, free) = r.value
                val used = total - free
                val percent = if (total > 0) (used * 100 / total) else 0
                Output.output(
                    Columns.twoColumns(
                        listOf(
                            "size" to FileOps.humanBytes(total),
                            "used" to "${FileOps.humanBytes(used)} ($percent%)",
                            "free" to FileOps.humanBytes(free),
                        ),
                        maxFirst = 6,
                    ),
                )
            }
        }
    }
}

object DuCommand : Command {
    override val name = "du"
    override val syntax = "du [path]"
    override val summary = "size of a tree"

    override suspend fun run(line: CommandLine, ctx: CommandContext): Output {
        val arg = line.tokens.joinToString(" ").trim()
        return withPath(name, ctx, arg) { path ->
            when (val r = FileOps.diskUsage(path)) {
                is FsResult.Failure -> fail(name, display(ctx.files.cage, arg), r.error)
                is FsResult.Success -> Output.of("${FileOps.humanBytes(r.value)}\t${ctx.files.cage.display(path)}")
            }
        }
    }
}

object FindCommand : Command {
    override val name = "find"
    override val syntax = "find [path] <pattern>"
    override val summary = "find by name"

    override suspend fun run(line: CommandLine, ctx: CommandContext): Output {
        if (line.tokens.isEmpty()) return Output.error("$name: needs a pattern")

        // Con un solo argumento se busca desde el cwd; con dos, el primero es dónde.
        val (whereArg, pattern) = if (line.tokens.size == 1) {
            "" to line.tokens[0]
        } else {
            line.tokens[0] to line.tokens.drop(1).joinToString(" ")
        }

        return withPath(name, ctx, whereArg) { path ->
            when (val r = FileOps.find(path, pattern, limit = dev.tty.core.Limits.COMMAND_OUTPUT_LINES)) {
                is FsResult.Failure -> fail(name, display(ctx.files.cage, whereArg), r.error)
                is FsResult.Success -> {
                    if (r.value.isEmpty()) return@withPath Output.status("no matches")
                    Output.output(r.value.map { ctx.files.cage.display(it) })
                }
            }
        }
    }
}

/**
 * `mount` — abre los Ajustes donde se concede el acceso a todos los ficheros.
 *
 * Existe solo como reintento: el permiso se pide en la primera ejecución (§11). El mensaje de error
 * de cualquier verbo de fichero es lo que dirige aquí, igual que con Termux — **el mensaje de error
 * es el onboarding**, no hay asistente.
 */
object MountCommand : Command {
    override val name = "mount"
    override val syntax = "mount"
    override val summary = "get storage access"

    override suspend fun run(line: CommandLine, ctx: CommandContext): Output {
        val fs = ctx.files
        if (fs.hasStorageAccess()) return Output.status("storage already mounted")
        return if (fs.requestStorageAccess()) {
            Output.status("opening all-files access in settings")
        } else {
            Output.error("$name: could not open the settings screen")
        }
    }
}
