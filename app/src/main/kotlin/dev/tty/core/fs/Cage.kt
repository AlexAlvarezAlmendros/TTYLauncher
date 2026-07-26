package dev.tty.core.fs

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * La jaula de rutas.
 *
 * **Es la única parte del producto donde un fallo destruye datos del usuario.** Con la raíz en
 * `/sdcard`, un `rm -r` mal resuelto se lleva las fotos y no hay papelera. Por eso se escribe antes
 * que cualquier comando de fichero y se testea antes de que exista ninguno.
 *
 * Las cuatro reglas, que no son higiene sino requisito (architecture.md §4.8):
 *
 * 1. **`toRealPath()`, nunca `normalize()`.** `normalize()` es puramente léxico: colapsa `..` sin
 *    mirar el disco, y con un symlink por medio el resultado no es lo que resuelve el kernel.
 * 2. **Comparar por `Path`, nunca por `String`.** El prefijo textual deja pasar `/sdcard/fotos-evil`
 *    contra `/sdcard/fotos`.
 * 3. **Revalidar justo antes de escribir o borrar.** TOCTOU: el symlink puede cambiar entre la
 *    comprobación y la operación.
 * 4. Para lo que aún no existe (`mkdir`, `touch`) se canonicaliza el **padre**, porque
 *    `toRealPath()` exige que el fichero exista.
 *
 * El directorio de trabajo es estado de este objeto y no del proceso: `user.dir` está cacheado en un
 * campo final de `UnixFileSystem`, `System.setProperty` no hace nada y no existe `chdir` en la API.
 * No es una limitación que sortear — es el sexto concepto del modelo (functional.md §3), volátil y
 * acotado por decisión de producto.
 */
class Cage(root: Path) {

    /** La raíz real, ya canonicalizada. Nada puede salir de aquí. */
    val root: Path = root.toRealPath()

    /** El directorio de trabajo. Siempre dentro de [root], siempre canonicalizado. */
    var cwd: Path = this.root
        private set

    /** La ruta que se enseña al usuario: relativa a la raíz, con `/` delante. */
    fun display(path: Path = cwd): String {
        val rel = root.relativize(path).toString()
        return if (rel.isEmpty()) "/" else "/$rel"
    }

    /**
     * Resuelve lo que el usuario escribió contra el directorio de trabajo.
     *
     * Acepta rutas absolutas (relativas a la raíz de la jaula, **no** a la del sistema), relativas,
     * `.`, `..` y `~` como sinónimo de la raíz.
     *
     * Devuelve la ruta **sin canonicalizar**: es solo la resolución léxica. Quien vaya a tocar el
     * disco tiene que pasar después por [check] o [checkParent], que son los que miran de verdad.
     */
    fun resolve(arg: String): Path {
        val a = arg.trim()
        return when {
            a.isEmpty() || a == "~" -> root
            a.startsWith("~/") -> root.resolve(a.removePrefix("~/"))
            // Una ruta «absoluta» del usuario es absoluta dentro de la jaula: escribir /DCIM no
            // debe llevar a la raíz del sistema, que además no se puede ni listar.
            a.startsWith("/") -> root.resolve(a.removePrefix("/"))
            else -> cwd.resolve(a)
        }
    }

    /**
     * Valida una ruta que **debe existir** y devuelve su forma canónica.
     *
     * `cd ..` desde la raíz no es un error: se queda en la raíz. No hay nada por encima, y decir
     * «permission denied» por subir de más sería mentir sobre dónde está el límite.
     */
    fun check(path: Path): FsResult<Path> {
        val real = try {
            path.toRealPath()
        } catch (e: IOException) {
            return FsResult.Failure(FsError.from(e, path))
        }
        return if (contains(real)) FsResult.Success(real) else FsResult.Success(root)
    }

    /**
     * Valida el destino de algo que **todavía no existe** (`mkdir`, `touch`, el destino de `mv`).
     *
     * Se canonicaliza el padre y se comprueba que el resultado sigue dentro. El nombre final se
     * añade después, sin resolver: un symlink que aún no existe no puede engañar a nadie.
     */
    fun checkParent(path: Path): FsResult<Path> {
        val parent = path.parent ?: return FsResult.Failure(FsError.OutsideRoot)
        val name = path.fileName ?: return FsResult.Failure(FsError.OutsideRoot)

        val realParent = try {
            parent.toRealPath()
        } catch (e: IOException) {
            return FsResult.Failure(FsError.from(e, parent))
        }
        if (!contains(realParent)) return FsResult.Failure(FsError.OutsideRoot)
        return FsResult.Success(realParent.resolve(name))
    }

    /**
     * Como [checkParent], pero para `mkdir -p`, donde **la cadena entera de padres puede faltar**.
     *
     * [checkParent] exige que el padre inmediato exista, que es justo lo que `-p` viene a resolver:
     * con él, `mkdir -p a/b/c` moría en la jaula antes de llegar a crear nada y el flag no servía
     * para más de un nivel.
     *
     * Se canonicaliza el ancestro **más profundo que sí existe** y el resto se añade sin resolver.
     * Es seguro por construcción: lo que no existe no puede ser un symlink, así que no hay nada que
     * `toRealPath()` viera y la resolución léxica no; y lo que sí existe pasa por `toRealPath()`
     * exactamente igual que en el resto de la jaula.
     *
     * El [normalize] final **no** es una excepción a la regla 1, es su consecuencia: sobre una ruta
     * ya canónica más una cola sin symlinks, colapsar `..` es lo mismo que haría el kernel. Y es
     * imprescindible: sin él, `mkdir -p a/../../evil` empieza por la raíz como `Path` y se colaría.
     */
    fun checkParents(path: Path): FsResult<Path> {
        val real = when (val r = deepestReal(path)) {
            is FsResult.Failure -> return r
            is FsResult.Success -> r.value.normalize()
        }
        return if (contains(real)) FsResult.Success(real) else FsResult.Failure(FsError.OutsideRoot)
    }

    /** El ancestro más profundo que existe, canonicalizado, con el resto del nombre añadido detrás. */
    private fun deepestReal(path: Path): FsResult<Path> {
        try {
            return FsResult.Success(path.toRealPath())
        } catch (e: IOException) {
            // Todavía no existe —o no se puede mirar—: se sigue subiendo.
        }
        val parent = path.parent ?: return FsResult.Failure(FsError.OutsideRoot)
        val name = path.fileName ?: return FsResult.Failure(FsError.OutsideRoot)
        return when (val r = deepestReal(parent)) {
            is FsResult.Failure -> r
            is FsResult.Success -> FsResult.Success(r.value.resolve(name))
        }
    }

    /**
     * Contención **por `Path`**, componente a componente. Nunca por `String`: el prefijo textual
     * deja pasar `/sdcard/fotos-evil` contra `/sdcard/fotos`, que es la forma clásica de escaparse.
     */
    fun contains(real: Path): Boolean = real == root || real.startsWith(root)

    /**
     * Cambia el directorio de trabajo. Valida **en el momento**: nada de un `cd` optimista que
     * acepta cualquier cosa y hace que falle el comando siguiente, que es lo que pasaría si esto
     * se delegara a otro proceso.
     */
    fun cd(arg: String): FsResult<Path> {
        val target = when (val r = check(resolve(arg))) {
            is FsResult.Failure -> return r
            is FsResult.Success -> r.value
        }
        if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(target)) {
            return FsResult.Failure(FsError.NotADirectory)
        }
        cwd = target
        return FsResult.Success(target)
    }

    /**
     * Si borrar [target] sería suicida.
     *
     * Se niega sobre la raíz, sobre el directorio de trabajo y sobre **cualquier ancestro suyo**:
     * borrar el suelo que estás pisando deja el prompt apuntando a un directorio que ya no existe,
     * y a partir de ahí todo comando falla de formas que no se pueden explicar.
     */
    fun refusesToDelete(target: Path): FsError? = when {
        target == root -> FsError.RefusedRoot
        target == cwd -> FsError.RefusedCwd
        cwd.startsWith(target) -> FsError.RefusedAncestor
        else -> null
    }

    /** Vuelve a la raíz. Lo usa la recuperación cuando el cwd deja de existir bajo los pies. */
    fun resetToRoot() {
        cwd = root
    }

    /**
     * Comprueba que el cwd sigue vivo y, si no, vuelve a la raíz.
     *
     * Pasa de verdad: se borra la carpeta desde otra app, o se desmonta el almacenamiento. Un
     * prompt que apunta a la nada no es recuperable escribiendo, y esta pantalla es la de inicio.
     */
    fun revalidateCwd(): Boolean {
        if (Files.isDirectory(cwd)) return true
        resetToRoot()
        return false
    }
}
