package dev.tty.core.fs

import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.Path

/**
 * Los fallos del sistema de ficheros, tipados.
 *
 * Existen para poder decir **qué pasó** con la precisión de un shell. `File.listFiles()` devuelve
 * `null` igual para «no existe», «no es un directorio» y «no tengo permiso», y un terminal que no
 * distingue esos tres casos miente: `ls: /foo: no such file or directory` y
 * `ls: /foo: permission denied` mandan al usuario a sitios distintos.
 *
 * Por eso todo el motor usa `java.nio`, que traduce el `errno` a excepciones tipadas, y no
 * `java.io`, que devuelve booleanos sin causa.
 */
sealed interface FsError {

    /** El mensaje tal y como se imprime, sin el verbo delante. */
    val message: String

    data object NoSuchFile : FsError {
        override val message = "no such file or directory"
    }

    data object PermissionDenied : FsError {
        override val message = "permission denied"
    }

    data object NotADirectory : FsError {
        override val message = "not a directory"
    }

    data object IsADirectory : FsError {
        override val message = "is a directory"
    }

    data object DirectoryNotEmpty : FsError {
        override val message = "directory not empty"
    }

    data object AlreadyExists : FsError {
        override val message = "already exists"
    }

    /** Intento de salir de la raíz. Es un error, no un recorte silencioso (functional.md §6.3). */
    data object OutsideRoot : FsError {
        override val message = "outside the root"
    }

    data object RefusedRoot : FsError {
        override val message = "refusing to remove the root"
    }

    data object RefusedCwd : FsError {
        override val message = "refusing to remove the working directory"
    }

    data object RefusedAncestor : FsError {
        override val message = "refusing to remove a parent of the working directory"
    }

    /**
     * `/sdcard/Android/data` y `/obb`. Cerrados a **todas** las apps desde Android 11, con permiso o
     * sin él. Merecen su propio mensaje o parecerá un bug del launcher.
     */
    data object AndroidDataDir : FsError {
        override val message = "closed to all apps since android 11"
    }

    /** Falta el permiso de acceso a todos los ficheros. El mensaje dice qué escribir. */
    data object NoStorageAccess : FsError {
        override val message = "no access to storage — run 'mount' to grant it"
    }

    data class Other(override val message: String) : FsError

    companion object {
        /**
         * Traduce una excepción de `java.nio` al error del producto.
         *
         * Es el punto donde el `errno` del kernel se convierte en algo que un usuario de terminal
         * reconoce. Lo que no encaje en las categorías conocidas se pasa tal cual en vez de
         * inventarle una: un mensaje raro es mejor que uno equivocado.
         */
        fun from(e: IOException, path: Path? = null): FsError = when (e) {
            is NoSuchFileException -> NoSuchFile
            is AccessDeniedException ->
                if (path != null && isAndroidDataDir(path)) AndroidDataDir else PermissionDenied

            is NotDirectoryException -> NotADirectory
            is DirectoryNotEmptyException -> DirectoryNotEmpty
            is FileAlreadyExistsException -> AlreadyExists
            else -> Other(e.message?.substringAfterLast(": ") ?: "i/o error")
        }

        private fun isAndroidDataDir(path: Path): Boolean {
            val s = path.toString()
            return s.contains("/Android/data") || s.contains("/Android/obb")
        }
    }
}

/** Éxito o fallo, sin excepciones cruzando la frontera de `core/`. */
sealed interface FsResult<out T> {
    data class Success<T>(val value: T) : FsResult<T>
    data class Failure(val error: FsError) : FsResult<Nothing>
}

/** El valor, o `null` si fue un fallo. Para cuando el error se maneja aparte. */
fun <T> FsResult<T>.getOrNull(): T? = (this as? FsResult.Success)?.value

/** El error, o `null` si fue un éxito. */
fun <T> FsResult<T>.errorOrNull(): FsError? = (this as? FsResult.Failure)?.error
