# Plan 02 — Ficheros

> Fase: 2 de 6 | Estado: 🔄 En curso | Iniciado: 2026-07-26 | Cerrado: —
> Hito del roadmap: `cd Download` y `ls` devuelven lo que hay en el teléfono, y ningún comando puede tocar nada fuera de la raíz.

Añade el segundo bloque del vocabulario: los quince verbos de fichero de la
[§6.3](../../functional.md#63-ficheros). Es lo que convierte el launcher en algo con lo que se
trabaja, no solo con lo que se abre.

**El orden de esta fase no es negociable: la jaula de rutas va primero.** Los comandos se escriben
después, sobre una resolución de rutas que ya no puede salirse de la raíz. Al revés —comandos
primero, seguridad después— es como se acaba borrando la carpeta `DCIM` de alguien.

---

## Dependencia con otras fases

- **Requiere:** Fase 1. El motor de comandos, el parser y el contrato de líneas ya están; esta fase
  solo añade verbos.
- **Habilita:** Fase 3 — un script que encadena `cd`, `mv` y `open` es mucho más útil que uno que
  solo abre apps.

---

## Decisiones que llegan cerradas

| Decisión | Resuelto |
|---|---|
| Colisión de nombres | **Unix gana**: `ls`, `cat` y `rm` son de ficheros. `apps` pierde el alias `ls`; desinstalar se llama `uninstall` |
| Nativo o Termux | **Nativo.** Un listado nativo cuesta 1-5 ms; delegado a Termux, 100-250 ms en caliente y hasta 2 s en frío — y **no da ni un byte más de superficie** sobre `/sdcard` |
| Alcance | **`/sdcard` desde la primera ejecución**, con `MANAGE_EXTERNAL_STORAGE` pedido tras el banner |

---

## Tareas

### La jaula — se escribe antes que cualquier comando

| # | Tarea | Estado | Notas |
|---|-------|--------|-------|
| 2.1 | Resolución de rutas contra la raíz: `toRealPath()` y comparación `startsWith` **por `Path`, nunca por `String`** | ✅ Hecho | El prefijo textual deja pasar `/sdcard/fotos-evil` contra `/sdcard/fotos`. `normalize()` no basta: es léxico y un symlink lo desmiente |
| 2.2 | Para `mkdir` y `touch`, donde el destino aún no existe, se canonicaliza **el padre** | ✅ Hecho | `toRealPath()` exige que el fichero exista |
| 2.3 | Revalidación de la ruta **justo antes** de borrar o escribir, no solo al parsear | ✅ Hecho | TOCTOU: un symlink puede cambiar entre la validación y la operación |
| 2.4 | Recorrido con `Files.walkFileTree` **sin** `FOLLOW_LINKS` | ✅ Hecho | Así un symlink se borra como entrada y no se desciende por él. Y `walkFileTree` entrega los errores en `visitFileFailed` en vez de abortar el recorrido entero como hace `Files.walk` |
| 2.5 | `rm` se niega sobre la raíz, sobre el directorio de trabajo y sobre cualquier ancestro suyo | ✅ Hecho | Criterio 15 |
| 2.6 | **Tests JVM de la jaula, escritos antes que los comandos**: `..` encadenado, symlink que apunta fuera, ruta absoluta ajena, cwd manipulado, prefijo textual engañoso | ✅ Hecho | Es la red que hace asumible el resto de la fase |

### Permiso y raíz

| # | Tarea | Estado | Notas |
|---|-------|--------|-------|
| 2.7 | `MANAGE_EXTERNAL_STORAGE` en el manifest + `Environment.isExternalStorageManager()` | 🔄 En curso | No es un runtime permission: no se pide con `requestPermissions()` |
| 2.8 | Petición en la primera ejecución, tras el banner: `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` con `Uri` `package:dev.tty`, y respaldo a la acción global | 🔄 En curso | §11.4. El `Uri` es obligatorio; algunos fabricantes no resuelven la acción dirigida — envolver en `try/catch ActivityNotFoundException` |
| 2.9 | Comprobar el estado en `onResume`, no en un callback de resultado | 🔄 En curso | El usuario sale a Ajustes y vuelve; no hay `ActivityResult` que valga |
| 2.10 | `mount` — reintento explícito. Si ya está concedido, lo dice y no abre nada | 🔄 En curso | §6.3. Es el onboarding cuando se denegó el permiso |
| 2.11 | Raíz en `/sdcard`; `cd /` lleva a la raíz y `cd ..` desde la raíz se queda | 🔄 En curso | No es error: no hay nada por encima |
| 2.12 | `/sdcard/Android/data` y `/obb`: mensaje **específico**, no un «permission denied» genérico | 🔄 En curso | Cerrados a todas las apps desde Android 11, con permiso o sin él. Parecería un bug propio |

### Motor de ficheros

| # | Tarea | Estado | Notas |
|---|-------|--------|-------|
| 2.13 | Directorio de trabajo como estado de la app | ✅ Hecho | `user.dir` está cacheado en un campo final: `System.setProperty` no hace nada y no hay `chdir` en la API. No es una limitación a sortear, es el diseño |
| 2.14 | `Files.newDirectoryStream` en lugar de `File.listFiles()` | ✅ Hecho | `listFiles()` devuelve `null` igual para «no existe», «no es un directorio» y «sin permiso». `nio` traduce el errno: `NoSuchFileException`, `AccessDeniedException`, `NotDirectoryException` |
| 2.15 | Mensajes de error derivados del errno, con la forma de un shell | ✅ Hecho | `ls: /foo: no such file or directory` y `ls: /foo: permission denied` son cosas distintas. §10 |
| 2.16 | Todo comando de fichero corre en `Dispatchers.IO`, es cancelable y **nunca** bloquea el prompt | 🔄 En curso | §5.8. Un `find` sobre `/sdcard` tarda segundos |
| 2.17 | **Sin globbing**: `*` es un carácter literal en un nombre | ✅ Hecho | §6.3. Un glob que no se puede ver expandido antes de ejecutarlo es la forma más rápida de borrar lo que no querías |

### Comandos

| # | Tarea | Estado | Notas |
|---|-------|--------|-------|
| 2.18 | `pwd` y `cd [ruta]` — absolutas, relativas, `.`, `..` y `~` | ✅ Hecho | `cd` valida en el momento: nada de cd optimista que falla en el comando siguiente |
| 2.19 | `ls [ruta]` — directorios primero con `/` al final, alfabético, línea de total | ✅ Hecho | §6.3 |
| 2.20 | `ls -l` con modo, tamaño y fecha vía `Os.lstat` → `StructStat`; `ls -a` incluye ocultos | ✅ Hecho | Una sola llamada por entrada. Renuncias asumidas: sin xattr, sin contexto SELinux, y en `/sdcard` los metadatos POSIX son sintéticos (FUSE) |
| 2.21 | `cat <fichero>` — y si es binario, dice qué es y cuánto ocupa en vez de vomitarlo | ✅ Hecho | §6.3 |
| 2.22 | `head [-n N]` y `tail [-n N]`, 10 líneas por defecto | ✅ Hecho | `tail` con `SeekableByteChannel` leyendo hacia atrás: leer 400 MB para sacar 10 líneas no vale |
| 2.23 | `mkdir [-p]`, `rmdir`, `touch` | ✅ Hecho | |
| 2.24 | `rm <fichero>`; sobre un directorio **falla** y pide `-r`; `rm -r` imprime cuántas entradas borró | ✅ Hecho | Criterio 16. Sin confirmación: un prompt de confirmación en una terminal es una traición (§6.2, `clear`) |
| 2.25 | `mv` con `Files.move()` **sin** `ATOMIC_MOVE`, avisando de que entre volúmenes no es atómico | ✅ Hecho | Cruzar de `/data` a `/sdcard` da `EXDEV`; `renameTo()` devolvería `false` sin decir por qué |
| 2.26 | `cp [-r]` | ✅ Hecho | |
| 2.27 | `df` — volúmenes conocidos vía `Os.statvfs`, no parseando `/proc/mounts` | ✅ Hecho | `getFreeSpace()` miente: incluye los bloques reservados para root. Usar `f_bavail` |
| 2.28 | `du [ruta]` — tamaño **ocupado**, con `st_blocks × 512` | ✅ Hecho | `java.nio` no expone bloques asignados; el tamaño aparente no es lo que ocupa |
| 2.29 | `find [ruta] <patrón>` — por nombre, cancelable | ✅ Hecho | Sin expresiones ni predicados: es un `find -name` y nada más |

### Cierre

| # | Tarea | Estado | Notas |
|---|-------|--------|-------|
| 2.30 | Recorte de salida a 500 líneas diciendo cuántas se recortaron | ✅ Hecho | §5.8. Un `cat` de 10.000 líneas no vuelca el scrollback entero |
| 2.31 | `help` con la ficha de los quince verbos, y `help ls` como cualquier otro | 🔄 En curso | Criterio 4: sigue cabiendo en una pantalla. Si no cabe, `help` se parte en dos secciones, no se recorta |
| 2.32 | Auditoría de la §10 sobre las cadenas nuevas | 🔄 En curso | Misma revisión que la 0.32b. Los errores de fichero son los que más se parecen a un shell: hay que sonar como uno |
| 2.33 | Verificar el criterio 17 con el permiso **denegado**: `ls` explica qué falta y los comandos de app siguen funcionando | 🔄 En curso | El launcher no se rompe porque el usuario diga que no |

---

## Entregable

Se navega el teléfono desde el prompt: `cd Download`, `ls`, `cat notas.txt`, `mv factura.pdf docs/`,
`df`. Y ningún comando puede tocar nada fuera de `/sdcard`.

## Criterio de aceptación

Criterios 15, 16 y 17 de [functional.md §13](../../functional.md#13-criterios-de-aceptación). El 15
se demuestra con los tests de la 2.6, no mirando la pantalla.

---

## Registro de avance

| Fecha | Tarea | Notas |
|-------|-------|-------|
| 2026-07-26 | — | Plan creado. Bloqueado por la Fase 1. Colisión de nombres y alcance decididos por el usuario; implementación nativa confirmada contra la investigación de acceso a almacenamiento. |
| 2026-07-26 | 2.1-2.6 | **La jaula, antes que ningún comando.** `core/fs/Cage` con `toRealPath()` (nunca `normalize()`), contención por `Path` (nunca por `String`), canonicalización del padre para lo que aún no existe, y negativa a borrar la raíz, el cwd o cualquier ancestro suyo. Recuperación si el cwd desaparece bajo los pies. **24 tests contra un directorio temporal real**, con symlinks de verdad: `..` encadenado, enlace que sale fuera, ruta absoluta ajena, cwd manipulado y el prefijo textual engañoso (`root-evil` contra `root`). |
| 2026-07-26 | 2.13-2.30 | **Los quince verbos.** `core/fs/FileOps` con `java.nio` —que existe igual en la JVM y en Android desde el nivel 26, así que el motor entero se testea **sin emulador**—: `Files.newDirectoryStream` en vez de `File.listFiles()` para poder distinguir «no existe» de «sin permiso», `walkFileTree` sin `FOLLOW_LINKS`, `Files.move` sin `ATOMIC_MOVE`, lectura de `tail` hacia atrás desde el final, y `cat` que describe los binarios en vez de vomitarlos. `platform/fs/AndroidStorage` aporta lo único que es de Android: la raíz y el permiso. 25 tests nuevos. **176 en total, 0 fallos**, y `assembleDebug` en verde. |
| 2026-07-26 | — | Un bug real que cazaron los tests: `tail` devolvía el **principio** del fichero. Recortaba dentro del bucle de lectura y descartaba justo las líneas del final, que son las que se piden. Ahora se recorta al terminar. |
