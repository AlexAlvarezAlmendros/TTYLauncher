# tty — Arquitectura

**Documento:** decisiones de implementación, v1.0 · 2026-07-26
**Alcance:** cómo se construye lo que describe [functional.md](functional.md). Si los dos documentos
se contradicen, manda el funcional y este se corrige.

> **Estado de verificación.** Todo lo técnico de este documento se investigó contra documentación
> oficial y código de AOSP el 26/07/2026, y las afirmaciones de riesgo alto pasaron por una ronda
> de refutación. **Nada se ha compilado todavía**: la máquina de desarrollo no tiene JDK, Gradle ni
> Android SDK. Trátese como diseño verificado documentalmente, no como código probado.

---

## 1. Principios de implementación

Tres, derivados de los principios de producto:

1. **El motor no conoce Android.** El parser, el catálogo de comandos, la resolución de apps por
   rangos, el intérprete de scripts y el recorte del scrollback son Kotlin puro. Los efectos entran
   por interfaces. Es lo que permite testear en JVM sin emulador — decisivo cuando el criterio 7
   («un script recursivo termina con un error») se comprueba mejor con un test que con un móvil.
2. **Una sola actividad, un solo módulo, sin contenedor de DI.** Cinco conceptos y un usuario. Las
   dependencias se construyen a mano en un `AppContainer` y se pasan por constructor.
3. **Ninguna dependencia que no pague su peso.** Sin Hilt, sin Room, sin DataStore, sin Material3.
   El listado completo está en §9.

---

## 2. Estructura

Un único módulo `app`, con la frontera real dentro del código:

```
app/src/main/kotlin/dev/tty/
  core/                     ← Kotlin puro. Sin un solo import de android.*
    parse/                  Parser de la línea de entrada (verbo, flags, argumento, comillas)
    command/                Registro de comandos, despacho, contrato de salida
    apps/                   Handles y resolución por rangos (§4.2)
    fs/                     Jaula de rutas, cwd, verbos de fichero (§4.8-4.10)
    script/                 Intérprete: sustitución posicional, límites, anidamiento
    output/                 Line(text, role) — el rol decide color y animación
  platform/                 ← Implementaciones Android de las interfaces de core/
    apps/                   LauncherApps: catálogo, lanzar, info, desinstalar
    fs/                     java.nio + android.system.Os, permiso de almacenamiento
    store/                  Scrollback, historial de entradas, scripts
    termux/                 Cliente RUN_COMMAND
  ui/                       ← Compose
    theme/                  Paleta, tipografía, tokens de movimiento
    terminal/               Prompt, lista de scrollback, línea
    glyph/                  Matriz 5×5 (Fase 4)
  MainActivity.kt
  AppContainer.kt

app/src/test/kotlin/        ← JVM. Todo lo de core/
```

`core/` no importa `platform/` ni `ui/`. `platform/` no importa `ui/`. Sin ciclos.

Un test que falle al añadir `import android.` en `core/` es barato de escribir y protege la
frontera mejor que la disciplina.

---

## 3. La actividad HOME

### 3.1 Manifest

Base: el manifest de AOSP Launcher3, con dos desviaciones deliberadas.

```xml
<activity
    android:name=".MainActivity"
    android:exported="true"
    android:launchMode="singleTask"
    android:clearTaskOnLaunch="true"
    android:stateNotNeeded="true"
    android:taskAffinity=""
    android:resumeWhilePausing="true"
    android:screenOrientation="unspecified"
    android:windowSoftInputMode="adjustResize"
    android:configChanges="keyboard|keyboardHidden|mcc|mnc|navigation|orientation|screenSize|screenLayout|smallestScreenSize|density|uiMode|fontScale">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.HOME" />
        <category android:name="android.intent.category.DEFAULT" />
    </intent-filter>
</activity>
```

- `CATEGORY_DEFAULT` es **obligatoria**: sin ella el launcher no aparece en el selector.
- `exported="true"` es obligatorio desde API 31 en cualquier componente con `intent-filter`.
- `launchMode="singleTask"` — garantiza que volver al launcher entre por `onNewIntent()` sin
  recrear. `singleInstancePerTask` (API 31+) sería equivalente; `singleInstance` **no**: prohíbe
  que ninguna otra actividad entre en la tarea del launcher.
- **Desviación 1: `adjustResize`, no `adjustPan`.** Launcher3 usa `adjustPan` porque no usa insets
  de Compose. Aquí el IME tiene que reportar altura para que el historial la respete.
- **Desviación 2: `configChanges` ampliado** con `density|uiMode|fontScale`. Rotar o cambiar el
  tamaño de fuente no debe recrear la actividad ni reiniciar la deriva del degradado.
- **No** se declara `excludeFromRecents`: una tarea HOME ya queda fuera de Recientes, y Launcher3
  no lo declara.

### 3.2 Volver al launcher (§5.6)

`onNewIntent()` es un no-op: `super` + `setIntent(intent)` y nada más. Cualquier reinicialización
ahí hace que pulsar HOME parpadee. Si en el futuro hace falta reaccionar (volver el scroll arriba),
se emite por un `SharedFlow` que la UI colecta; nunca se vuelve a llamar a `setContent()`.

Nota, no receta: algunas versiones añaden un extra interno `android.intent.extra.FROM_HOME_KEY` al
intent de HOME, pero **no es API pública** ni está garantizado con navegación por gestos. Si algún
día hace falta distinguir el reingreso, el camino es el chequeo estándar de Launcher3
(`hasWindowFocus()` más la ausencia de `FLAG_ACTIVITY_BROUGHT_TO_FRONT`), no ese extra.

### 3.3 Botón atrás (§5.7)

```kotlin
BackHandler(enabled = true) { /* no-op: la HOME no navega hacia atrás */ }
```

`Activity.onBackPressed()` está deprecado desde API 33 y con targetSdk 36+ **ya no se llama**. Lo
único que se ve al compilar es el aviso de deprecación, que no dice lo que de verdad ha pasado: que
el botón atrás dejó de estar bloqueado. Existe un opt-out por actividad
(`android:enableOnBackInvokedCallback="false"`) y aquí se decide **no** usarlo.

Nunca llamar a `finish()` en la HOME: el sistema la relanza al instante y se ve un parpadeo.

### 3.4 Edge-to-edge

Obligatorio y sin escapatoria desde targetSdk 35; con 36+ el atributo de opt-out está deprecado y
desactivado. `Window.setStatusBarColor()`, `setNavigationBarColor()` y `setDecorFitsSystemWindows()`
son **no-ops silenciosos**: el color de las barras sale del propio degradado.

```kotlin
enableEdgeToEdge()   // androidx.activity, antes de setContent
```

Patrón de insets: el degradado ocupa la ventana entera **sin** padding; los insets se aplican solo
al contenido.

```kotlin
Box(Modifier.fillMaxSize().drawBehind { /* degradado */ }) {
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Prompt()                       // fijo arriba
        Scrollback(Modifier.imePadding())   // el IME solo empuja al historial
    }
}
```

Como el prompt está arriba, el teclado nunca lo tapa: `imePadding()` solo afecta al historial. Es
la propiedad que hace que el criterio 11 (misma coordenada vertical siempre) sea trivial.

### 3.5 Que un crash no inutilice el móvil (§16)

AOSP tiene red de seguridad: si el proceso home es de terceros y crashea, el sistema le quita las
preferred activities de home y vuelve al launcher del sistema. Aun así el usuario ve un bucle de
diálogos antes de que salte la mitigación. Lo que sí está bajo nuestro control:

- **Modo seguro propio.** `Thread.setDefaultUncaughtExceptionHandler` escribe una marca de
  «arranque fallido»; el arranque siguiente lee la marca y monta la versión mínima: sin degradado
  animado, sin glifos en Canvas, prompt de texto plano. La marca se limpia cuando un arranque llega
  a estable. Es la única mitigación real que puede aplicar la app.
- **Nunca I/O en el hilo principal en `onCreate`.** Un ANR en la HOME es indistinguible de un móvil
  bloqueado.
- **Regla de trabajo:** mantener siempre otro launcher instalado y no fijar `tty` como
  predeterminado permanente hasta que sea estable.

---

## 4. Apps y ficheros

### 4.1 Visibilidad y catálogo

Un launcher **no** necesita `QUERY_ALL_PACKAGES`, y no debe pedirlo: no hay exención automática de
visibilidad por ostentar `ROLE_HOME`, pero sí basta declarar la consulta:

```xml
<queries>
    <intent>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent>
</queries>
```

`LauncherApps` **no** es una puerta trasera a la visibilidad: sin ese `<queries>`, `getActivityList`
devuelve una lista vacía o recortada. Es el fallo que no se ve en desarrollo y aparece en el
dispositivo real.

```kotlin
val la = ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
la.profiles.flatMap { la.getActivityList(null, it) }   // multiperfil
```

`getActivityList` devuelve etiqueta, `ComponentName`, `ApplicationInfo` y perfil en una llamada, sin
tener que componer `queryIntentActivities` + `loadLabel`. Es la API pensada para launchers.

**Perfil privado (Android 15+):** requiere `ACCESS_HIDDEN_PROFILES` **y** el rol `ROLE_HOME`. Fuera
de alcance por ahora; si se añade, sus apps no deben acabar en el scrollback persistido, o se
filtran al desbloquear el terminal.

### 4.2 Frescura (§7.3)

`LauncherApps.registerCallback` y **nada más**. Cubre todos los perfiles y entrega una actualización
como un único `onPackageChanged`.

No registrar además un `BroadcastReceiver` de `ACTION_PACKAGE_*`: llegarían eventos por duplicado.
(Y en manifest ya no funcionarían: son broadcasts implícitos no exentos desde targetSdk 26.)

### 4.3 Lanzar (`open`)

```kotlin
launcherApps.startMainActivity(info.componentName, info.user, null, null)
```

Soporta perfiles y no exige añadir flags a mano. `getLaunchIntentForPackage` puede devolver `null`
para apps instaladas y visibles: todo `open` maneja `null` y `ActivityNotFoundException` e imprime
un error legible. Una excepción no capturada aquí crashea la pantalla de inicio.

Apps archivadas (Android 15+) siguen apareciendo en el catálogo pero no se lanzan igual: comprobar
`ApplicationInfo.isArchived()` para dar un mensaje correcto en vez de un fallo opaco.

### 4.4 `kill`

**`ActivityManager.killBackgroundProcesses()` ya no sirve para matar otras apps.** Desde Android 14
(API 34) solo afecta a los procesos de la propia app, para todas las apps de terceros y con
independencia del targetSdk. **No lanza excepción: falla en silencio** y registra
`Invalid packageName: …` en el log. El permiso `KILL_BACKGROUND_PROCESSES` sigue existiendo y sigue
siendo necesario, pero ya no basta.

**Decidido el 2026-07-26 — salida A.** `kill` no muere: se convierte en la puerta al diálogo del
sistema.

```kotlin
// platform/apps — implementación de la interfaz AppKiller declarada en core/
launcherApps.startAppDetailsActivity(component, user, null, null)
// y el comando devuelve una única línea de salida:
//   force stop <handle> in the system dialog (android 14+ blocks it from an app)
```

Por qué esta y no otra:

- Conserva el verbo, que es el correcto y el que un usuario de terminal va a escribir.
- Cumple el principio 4 —lo destructivo pasa por un diálogo del sistema— y la regla de la §10 de
  decir el límite **en el mensaje**, no en la documentación.
- Deja el hueco de Shizuku exactamente donde la §12 lo quería: **la interfaz `AppKiller` se declara
  en `core/`**, y el día que exista un backend con privilegios se añade una segunda implementación
  sin que el comando cambie de nombre ni de sintaxis.

**Consecuencia: `restart` se retira del catálogo.** Era `kill` + `open`; con `kill` convertido en
«abre el diálogo», sería indistinguible de `kill`. Dos verbos para la misma acción es justo lo que
un vocabulario cerrado no puede permitirse. Vuelve el día que haya un `kill` de verdad.

`functional.md` §6.1, §6.2, §12 y §14 ya están corregidos.

**Nunca** implementar un indicador de progreso esperando a que el proceso muera: no va a morir, y
además la §4.8 prohíbe los spinners.

### 4.5 `rm`, `info`, `settings`

```xml
<uses-permission android:name="android.permission.REQUEST_DELETE_PACKAGES" />
```

- **Desinstalar:** `PackageInstaller.uninstall(pkg, statusReceiver)` (no deprecado) o
  `Intent(ACTION_DELETE, "package:$pkg")`. `ACTION_UNINSTALL_PACKAGE` está deprecado desde API 29.
  No existe desinstalación silenciosa sin ser device owner: el diálogo del sistema es la §12
  aceptada, no una carencia.
- **App de sistema (§6.2, `rm` las rechaza):**
  `isSystem = flags and FLAG_SYSTEM != 0`, `isUpdatedSystem = flags and FLAG_UPDATED_SYSTEM_APP != 0`.
  Desinstalable si `!isSystem || isUpdatedSystem` — y en el segundo caso lo que se desinstala son
  las actualizaciones, cosa que el mensaje debe decir.
- **Fin de la desinstalación:** no se detecta por resultado de actividad; se escucha
  `LauncherApps.Callback.onPackageRemoved`, que además ya refresca el catálogo.
- **`info -o`:** `launcherApps.startAppDetailsActivity(component, user, null, null)`, que es la
  variante multiperfil de `ACTION_APPLICATION_DETAILS_SETTINGS`.
- **`settings`:** `Settings.ACTION_HOME_SETTINGS` (`android.settings.HOME_SETTINGS`), que el CDD
  obliga a soportar, con cascada de respaldo a `ACTION_MANAGE_DEFAULT_APPS_SETTINGS` y
  `ACTION_SETTINGS`. `FLAG_ACTIVITY_NEW_TASK` es necesario porque el launcher usa `taskAffinity=""`.
  `RoleManager.ROLE_HOME` **no** es solicitable por apps de terceros: no perder tiempo ahí.

### 4.6 Resolución por rangos (§7.1)

Kotlin puro, en `core/apps`. Cuatro rangos evaluados en orden; se devuelve el **primero que produzca
resultados**; si ese rango produce más de uno, es error de ambigüedad. Un rango posterior **nunca**
rescata a uno ambiguo — es la propiedad que hay que testear explícitamente, porque es la que hace
que el sistema jamás adivine.

### 4.7 Ficheros: nativo, no delegado

La primera decisión es dónde se ejecutan los quince verbos de la [§6.3 del funcional](functional.md#63-ficheros),
y está cerrada: **nativos, con `java.nio.file` y `android.system.Os`**. Nunca delegados a Termux.

| | Nativo | Delegado a Termux |
|---|---|---|
| Latencia de un `ls` | 1-5 ms | 100-250 ms en caliente, 0,5-2 s en frío |
| Modos de fallo | los del sistema de ficheros | + los cuatro de RUN_COMMAND |
| Superficie de disco | `/sdcard` completo | `/sdcard` completo, **la misma** |
| Disponibilidad | siempre | depende de que Termux esté instalado |

El dato que zanja la discusión: **delegar no compra ni un byte de superficie**. Sobre
`/storage/emulated/0`, que es donde el usuario teclea rutas, el launcher con
`MANAGE_EXTERNAL_STORAGE` ve exactamente lo mismo que Termux con `termux-setup-storage`, y
`Android/data` y `Android/obb` están vedados a los dos por igual. Lo único que Termux añade es su
propio sandbox — que el launcher, con targetSdk 36 y el aislamiento de datos de Android 11, ni
siquiera puede ver: `/data/data/com.termux` le da `ENOENT`, no `EACCES`.

Y un vocabulario cerrado cuyos verbos más básicos aparecen y desaparecen según si otra app está
instalada deja de ser cerrado. `sh` sigue siendo la única escotilla, y es explícita.

**Nunca un fallback silencioso de `ls` a Termux:** el mismo comando tendría dos latencias, dos
espacios de nombres y dos modos de fallo.

### 4.8 Ficheros: raíz, permiso y jaula

**Raíz:** `/storage/emulated/0` (`/sdcard`). Fuera de ahí no hay nada que ofrecer: `/` y `/sys`
están denegados por SELinux, `/data` por permisos de directorio, y `/proc` va con `hidepid=2` —
solo se ve el proceso propio, que es la razón de que no exista un comando `ps`.

**Permiso:** `MANAGE_EXTERNAL_STORAGE`. No es un runtime permission y no se pide con
`requestPermissions()`:

```kotlin
// manifest: <uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />
Environment.isExternalStorageManager()                      // estado

Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, "package:dev.tty".toUri())
// el Uri es OBLIGATORIO. Algunos fabricantes no resuelven esta acción:
// try/catch ActivityNotFoundException → Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
```

El usuario sale a Ajustes y vuelve, así que el estado se comprueba en `onResume`, **no** en un
callback de resultado. Ni siquiera con el permiso se abren `/sdcard/Android/data` ni
`/sdcard/Android/obb`: cerrados a todas las apps desde Android 11. Eso merece su propio mensaje, o
parecerá un bug del launcher.

**La jaula.** Es lo primero que se escribe, antes que ningún comando:

```kotlin
val rootReal = root.toRealPath()
val target = cwd.resolve(arg)
require(target.toRealPath().startsWith(rootReal))   // Path, NUNCA String
```

Cuatro reglas que no son higiene sino requisito, porque con la raíz en `/sdcard` un `rm -r` mal
resuelto borra las fotos del usuario y no hay papelera:

1. **`toRealPath()`, no `normalize()`.** `normalize()` es puramente léxico: colapsa `..` sin mirar
   el disco, y con un symlink por medio el resultado no es lo que resuelve el kernel. Para `mkdir` y
   `touch`, donde el destino aún no existe, se canonicaliza el **padre**.
2. **Comparar por `Path`, no por `String`.** El prefijo textual deja pasar `/sdcard/fotos-evil`
   contra `/sdcard/fotos`.
3. **Revalidar justo antes de borrar.** TOCTOU: el symlink puede cambiar entre la comprobación y la
   operación.
4. **`Files.walkFileTree` sin `FOLLOW_LINKS`** (el defecto): un symlink se borra como entrada y no
   se desciende por él. Y entrega los errores en `visitFileFailed`, mientras que `Files.walk` lanza
   `UncheckedIOException` a mitad de iteración y aborta el recorrido entero.

Más: `rm` se niega sobre la raíz, sobre el directorio de trabajo y sobre cualquier ancestro suyo.

### 4.9 Ficheros: el motor

**El directorio de trabajo es estado de la app, y no hay alternativa.** `user.dir` está cacheado en
un campo final de `UnixFileSystem`: `System.setProperty` no hace nada y no existe `chdir` en la API.
Todas las rutas relativas se resuelven contra ese estado, que es volátil por decisión de producto
(§3 del funcional).

**`Files.newDirectoryStream`, nunca `File.listFiles()`.** `listFiles()` devuelve `null` igual para
«no existe», «no es un directorio» y «no tengo permiso» — es imposible distinguirlos. `java.nio`
traduce el `errno` a excepciones tipadas, que es lo que permite imprimir lo que un usuario de
terminal espera:

| Excepción | Mensaje |
|---|---|
| `NoSuchFileException` | `ls: /foo: no such file or directory` |
| `AccessDeniedException` | `ls: /foo: permission denied` |
| `NotDirectoryException` | `ls: /foo: not a directory` |

El mismo argumento descarta `mkdirs()`, `delete()` y `renameTo()`: devuelven un `boolean` sin causa.

`java.nio.file` completo está en el API público desde el nivel 26, que es justo el `minSdk`: no hace
falta desugaring.

### 4.10 Ficheros: los comandos

| Comando | API | Nota |
|---|---|---|
| `pwd` · `cd` | estado de la app | `cd` valida en el momento; nada de `cd` optimista que falla en el comando siguiente |
| `ls` | `Files.newDirectoryStream` | |
| `ls -l` | `Os.lstat` → `StructStat` | Una syscall por entrada: modo, nlink, uid, gid, tamaño, mtime. `java.nio` no da `st_nlink` ni `st_blocks` |
| `cat` · `head` | `Files.newBufferedReader` | |
| `tail` | `Files.newByteChannel` leyendo hacia atrás | Leer 400 MB para sacar 10 líneas no vale |
| `mkdir` · `rmdir` · `touch` | `Files.createDirectory(ies)` · `Files.delete` | |
| `rm` · `rm -r` | `Files.delete` · `walkFileTree` con `postVisitDirectory` | |
| `mv` | `Files.move` **sin** `ATOMIC_MOVE` | Cruzar volúmenes da `EXDEV`; sin la opción, `nio` cae a copiar+borrar solo |
| `cp -r` | `Files.copy` + `walkFileTree` | |
| `df` | `Os.statvfs` | `f_bavail × f_frsize`. `getFreeSpace()` miente: cuenta los bloques reservados para root |
| `du` | `Os.lstat().st_blocks × 512` | El tamaño aparente no es lo que ocupa |
| `find` | `walkFileTree` | Solo por nombre. Cancelable |

Renuncias asumidas y que hay que decir en la documentación, no descubrir: sin `xattr`, sin contexto
SELinux, sin ACL, y en `/sdcard` los metadatos POSIX son **sintéticos** — desde Android 11 el
almacenamiento emulado va por FUSE, así que no hay symlinks, no hay `chmod` y `ls -l` mostrará el
mismo modo para todo.

Todo comando de fichero corre en `Dispatchers.IO`, es cancelable y no bloquea el prompt: un `find`
o un `du` sobre un árbol grande tarda segundos.

---

## 5. Motor de comandos y scripts

### 5.1 Contrato de salida

```kotlin
enum class Role { OUTPUT, ECHO, ERROR, STATUS, RECORDING }
data class Line(val id: Long, val text: String, val role: Role)
```

El **rol lo fija el comando**, y de él salen tres cosas: el color (§4.2), el prefijo (§10) y, en la
Fase 4, la animación de aparición. Que `decode` no se use nunca en `apps`, `sh` ni `tmux` (§4.5) es
una regla del motor, no criterio de quien escribe cada comando: esos comandos emiten `OUTPUT`, y
`OUTPUT` nunca se descodifica.

El `id` monótono es la clave de la lista de Compose (§8.1). Nunca el índice.

### 5.2 Ejecución

Un comando es una función suspendida que recibe argumentos y devuelve líneas. `open` devuelve lista
vacía: éxito silencioso (§10). Los efectos entran por interfaces de `platform/`.

La entrada no se bloquea nunca por una ejecución en curso (§4.7): se puede escribir el siguiente
comando mientras el anterior corre. El estado `BUSY`/`SHELL` del glifo del prompt sale de si hay una
ejecución viva, no de bloquear el campo.

### 5.3 Scripts

Intérprete en `core/script`, sin nada de Android:

- Resolución: **comando incorporado → script → error**. Un script nunca sombrea un comando (§8.4);
  se comprueba al **crear**, no al ejecutar, para que el rechazo sea inmediato.
- Sustitución: `$1`–`$9` y `$@`, y ninguna otra. Ni entorno, ni aritmética, ni condicionales.
- Límites: profundidad 4, 200 líneas ejecutadas por invocación. Ambos contadores viven en el
  contexto de ejecución, no en variables globales, para que sean testeables.
- Parada en la primera línea que falla, imprimiendo lo acumulado más el error (§8.5).

El modo grabación es estado de la UI (`Recording(name, lines)`), no del intérprete: durante la
grabación **no se ejecuta nada**.

---

## 6. Persistencia

### 6.1 Scrollback

**Fichero de texto plano append-only en `context.noBackupFilesDir`.** Ni DataStore (reescribe el
payload entero en cada escritura y su propia documentación lo desaconseja para datasets grandes) ni
SQLite (no hay ni una consulta que justifique un motor).

```
noBackupFilesDir/scrollback.log     ← una línea por entrada, append
filesDir/scripts/<nombre>           ← un fichero por script
```

- **Camino caliente:** `FileOutputStream(file, /* append = */ true)` — el argumento con nombre no
  compila, porque el constructor es de Java — con debounce de ~1s terminado en
  `flush()`. Frente a un kill del sistema, `flush()` basta: los bytes ya entregados al kernel
  sobreviven a la muerte del proceso. `fsync` solo protege de corte de corriente, y se reserva para
  `onStop()` y la compactación.
- **Compactación (recorte a 2000 líneas):** `android.util.AtomicFile`, que reescribe el fichero
  entero de forma atómica. **No sirve para el camino caliente** justamente por eso.
- **Lectura tolerante:** un append interrumpido puede dejar la última línea truncada. Si el fichero
  no termina en `\n`, se descarta el fragmento final.
- **Flush definitivo en `onStop()`**, no en `onPause()` — la documentación es explícita: `onPause`
  no ofrece tiempo suficiente. Y ni `onStop` ni `onDestroy` están garantizados (el sistema mata el
  proceso, no la actividad): la garantía real es el debounce; `onStop` es el refuerzo. Un launcher
  tiene prioridad de proceso alta, lo que ayuda, pero no exime.
- **Carga:** en `Dispatchers.IO`, emitida a un `StateFlow`. Se pinta el prompt vacío primero
  (criterio 10). 2000 líneas son ~160 KB y ~5-20 ms en frío; el coste dominante es construir las
  cadenas y el layout, no la I/O.

### 6.2 Privacidad (§5.5)

`noBackupFilesDir` queda excluido **automáticamente** de la copia en la nube y de la transferencia
entre dispositivos, y no se puede reincluir ni con un `<include>`. Aun así se declara la exclusión
explícita, porque `android:allowBackup="false"` por sí solo **no** desactiva la transferencia
dispositivo-a-dispositivo en los móviles de algunos fabricantes:

```xml
<application
    android:allowBackup="false"
    android:dataExtractionRules="@xml/data_extraction_rules"   <!-- API 31+ -->
    android:fullBackupContent="@xml/backup_rules">             <!-- API ≤30 -->
```

```xml
<!-- res/xml/data_extraction_rules.xml -->
<data-extraction-rules>
    <cloud-backup><exclude domain="root" path="." /></cloud-backup>
    <device-transfer><exclude domain="root" path="." /></device-transfer>
</data-extraction-rules>
```

Cifrado: **no** se usa `androidx.security:security-crypto` — está deprecado por completo desde julio
de 2025. El almacenamiento privado ya está cifrado en reposo desde Android 10 y es inaccesible a
otras apps. Para un registro de uso del propio teléfono, eso es la protección proporcionada.

### 6.3 Scripts y path traversal

Nombre validado con allowlist (§8.2) **y** verificación de contención por ruta canónica **con
separador final**: `dir.path + File.separator`. Sin el separador, `/scripts` casa con
`/scripts_evil` — el fallo está en el propio snippet de ejemplo de Google.

---

## 7. Termux

El punto de mayor riesgo del proyecto: la API de RUN_COMMAND no es estable y Termux no publica
release estable desde mayo de 2025. Todo lo de abajo son literales verificados contra el código de
`termux-app`; hay que reverificarlos al subir de versión de Termux (§16 del funcional).

### 7.1 Constantes

```
servicio  com.termux/com.termux.app.RunCommandService
acción    com.termux.RUN_COMMAND
permiso   com.termux.permission.RUN_COMMAND   (protectionLevel="dangerous" → runtime)
```

```xml
<queries><package android:name="com.termux" /></queries>
<uses-permission android:name="com.termux.permission.RUN_COMMAND" />
```

Sin ese `<queries>`, `getPackageInfo("com.termux")` lanza `NameNotFoundException` **aunque Termux
esté instalado**: se confundiría «no instalado» con «no visible», que es exactamente el error que
la §9.4 prohíbe.

### 7.2 Resultado

```
Bundle contenedor: extra "result"       ← NO "result_bundle"
  "stdout"                 String
  "stderr"                 String
  "stdout_original_length" String       ← putString de un número, no Int
  "stderr_original_length" String
  "exitCode"               Int          (solo si existe)
  "err"                    Int          (-1 = sin error interno de Termux)
  "errmsg"                 String
```

La clave es `"result"`. Leer `"result_bundle"` devuelve siempre `null` y el launcher parece colgado
hasta el timeout: es el error más probable de toda la integración.

El `PendingIntent` debe crearse con **`FLAG_MUTABLE`** (API 31+) porque Termux le añade el extra; con
`FLAG_IMMUTABLE` el bundle llega vacío. Y con `FLAG_ONE_SHOT`, `requestCode` distinto por comando.

`stdout` + `stderr` se truncan a 100 KB combinados. Un `capture-pane` grande puede superarlo: es una
razón más para el límite de 500 líneas de salida (§5.8).

### 7.3 Ejecución

`RUN_COMMAND_BACKGROUND = true` (runner `app-shell`) **siempre**: en modo terminal-session `stdout`
contiene el transcript de la sesión, no la salida real, y `stderr` va mezclado. Ojo: si se pasan
`RUN_COMMAND_RUNNER` y `RUN_COMMAND_BACKGROUND` a la vez y no coinciden, gana `RUNNER` en silencio.
Pasar solo uno.

Cada elemento de `RUN_COMMAND_ARGUMENTS` es un argv independiente: no hay parseo de shell.

```
tmux, ruta absoluta /data/data/com.termux/files/usr/bin/tmux
  existe:  ["has-session", "-t", "<sesión>"]
  crear:   ["new-session", "-d", "-s", "<sesión>"]
  teclas:  ["send-keys", "-t", "<sesión>", "<comando>", "Enter"]   ← el comando es UN argv
  foto:    ["capture-pane", "-p", "-J", "-t", "<sesión>", "-S", "-<N>"]
```

`tmux` no viene instalado en Termux (`pkg install tmux`). Y el servidor de tmux arrancado desde un
`app-shell` en background solo comparte socket con el de una sesión de terminal si comparten
`TMUX_TMPDIR`: conviene fijar el socket explícitamente (`tmux -S <ruta>`) para hablar siempre con el
mismo servidor.

**Timeout:** RUN_COMMAND no tiene extra de timeout. Los 15s de la §9.2 se implementan en el
launcher (`withTimeoutOrNull`), cancelando el `PendingIntent` y descartando resultados tardíos por
id de correlación.

### 7.4 Los tres mensajes de error (§9.4)

Se distinguen por mecanismos distintos, no por tres excepciones, y el orden importa:

| Puerta | Detección | Mensaje |
|---|---|---|
| 1. Termux ausente o build de Play | `getPackageInfo(GET_SIGNING_CERTIFICATES)` → `NameNotFoundException`; o digest SHA-256 del firmante = el de Play | `termux not installed (use the F-Droid or GitHub build)` |
| 2. Permiso | `checkSelfPermission(...) != GRANTED` **antes** de llamar | `termux: RUN_COMMAND permission not granted` |
| 3. `allow-external-apps` | **No hay excepción.** `startService` devuelve OK y Termux solo muestra una notificación. Se detecta por `errmsg` del bundle (contiene `allow-external-apps`) o por timeout | `termux: could not start RunCommandService — is allow-external-apps set?` |

Digests oficiales del firmante (mayúsculas hex, SHA-256):

```
F-Droid       228FB2CFE90831C1499EC3CCAF61E96E8E1CE70766B9474672CE427334D41C42
GitHub        B6DA01480EEFD5FBF2CD3771B8D1021EC791304BDD6C4BF41D3FAABAD48EE5E1
Google Play   738F0A30A04D3C8A1BE304AF18D0779BCF3EA88FB60808F657A3521861C2EBF9
Termux Devs   F7A038EB551F1BE8FDF388686B784ABAB4552A5D82DF423E3D8F1B5CBE1C69AE
```

El build de Play está congelado y sin soporte de plugins: aunque el usuario conceda todo, no
funcionará. Detectarlo por firma y decirlo, en vez de dar un error genérico, **es** el onboarding.

Notas que hay que asumir: el permiso lo define otra app, así que solo se puede conceder si Termux ya
está instalado, y desinstalar/reinstalar Termux pierde la concesión. El fichero es
`~/.termux/termux.properties` (dentro de `$HOME`, no de `$PREFIX`) y tras editarlo hace falta
`termux-reload-settings`.

`ForegroundServiceStartNotAllowedException` extiende `IllegalStateException`, así que un `catch`
genérico enmascara los dos casos. En el flujo normal de `tty` (actividad HOME visible) el launcher
está exento de la restricción: si aparece, indica una llamada desde background, es decir un bug
propio, no un problema de configuración de Termux.

---

## 8. UI en Compose

> **Ningún valor visual se decide aquí.** Color, tipografía, espaciado y duraciones vienen del
> [design system](design/DESIGN-SYSTEM.md) y viven espejados en `ui/theme/` (`Palette.kt`,
> `Type.kt`, `Spacing.kt`, `Motion.kt`). Esta sección resuelve **cómo** se pintan en Compose, no
> **cuánto** miden.

### 8.1 Historial invertido (§4.1)

**`reverseLayout = false`** — el valor por defecto — y la lista guardada ya invertida: índice 0 = la
línea más reciente. `reverseLayout = true` ancla el índice 0 al borde **inferior**, que es
exactamente lo contrario de lo que pide el prompt fijo arriba.

El prompt **no** es un elemento de la lista: es una fila fija encima de la `LazyColumn`.

```kotlin
lines.add(0, nueva)
listState.requestScrollToItem(0)     // NO scrollToItem / animateScrollToItem
```

Insertar en la cabeza **no** desplaza la vista: `LazyList` reancla por la *key* del primer ítem
visible y la línea nueva queda fuera de pantalla. `requestScrollToItem` rompe ese anclaje en el
mismo remeasure y no es `suspend`, así que no hay carrera ni salto visual. El bug es invisible con
tres líneas y aparece a las doscientas.

`key = { it.id }` en los items. Nunca el índice: con inserciones en cabeza cambia para todos.

### 8.2 Foco automático y teclado (§5.2, criterio 2)

El fallo clásico de «a veces no sale el teclado» **no es de `FocusRequester`**: es una carrera con el
foco de **ventana**. Y en un launcher se agrava, porque la actividad se reanuda cada vez que se
pulsa HOME.

```kotlin
val fr = remember { FocusRequester() }
val windowInfo = LocalWindowInfo.current
LaunchedEffect(fr) {
    snapshotFlow { windowInfo.isWindowFocused }.filter { it }.first()
    fr.requestFocus()
}
```

Con `KeyboardOptions.showKeyboardOnFocus = true` el IME se abre solo al enfocar, que es más fiable
que `LocalSoftwareKeyboardController.show()` (best-effort, falla en silencio si la vista aún no está
adjunta).

**`stateAlwaysVisible` no sirve** y no se declara: el sistema lo ignora desde targetSdk 28 cuando no
hay un editor enfocado en el momento del foco de ventana — y en un launcher ese es justamente el
caso en cada pulsación de HOME.

```kotlin
KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,          // 'autoCorrect' está deprecado
    keyboardType = KeyboardType.Ascii,   // fuerza un IME capaz de ASCII
    imeAction = ImeAction.Go,
    showKeyboardOnFocus = true,
)
```

**Ninguna de estas dos opciones apaga las sugerencias, y conviene no engañarse.**
`autoCorrectEnabled = false` se limita a no añadir `TYPE_TEXT_FLAG_AUTO_CORRECT`; y
`KeyboardType.Ascii` se traduce a `IME_FLAG_FORCE_ASCII`, que solo pide un teclado **capaz** de
introducir ASCII y no toca el diccionario. Lo que apaga el diccionario es
`TYPE_TEXT_FLAG_NO_SUGGESTIONS`, que ninguna combinación de `KeyboardOptions` de `foundation`
activa. Las salidas reales son tres: aceptar que Gboard siga sugiriendo, llegar al flag por
`PlatformImeOptions`/`EditorInfo`, o `KeyboardType.Password` — que sí lo apaga, pero oculta el
texto y por eso queda descartado. **Sin decidir; se prueba en el dispositivo real en la tarea
0.12.**

### 8.3 Campo y cursor

`BasicTextField` con `TextFieldState` (la antigua `BasicTextField2`, ya estabilizada), de
`androidx.compose.foundation` — **no hace falta material3 para nada**.

El cursor por defecto se apaga con `cursorBrush = SolidColor(Color.Transparent)` y el bloque propio
se dibuja con el `TextLayoutResult` capturado en `onTextLayout`. El offset sale de
`state.selection.start`.

**Cuidado con el rango de los dos métodos, porque el caso normal es el que revienta:**
`getBoundingBox(offset)` valida `[0, length)` — **excluye el final**— y en un prompt el cursor está
al final del texto prácticamente siempre (`selection.start == text.length`). Llamarlo ahí lanza
`IllegalArgumentException`, y el crash cae en la actividad HOME. `getCursorRect(offset)` sí admite
`[0, length]`, pero devuelve una caja de grosor ~0.

La receta correcta usa los dos:

```kotlin
val offset = state.selection.start
val box = if (offset < state.text.length) {
    layout.getBoundingBox(offset)                    // hay carácter debajo: su caja real
} else {
    layout.getCursorRect(offset)                     // final del texto: línea sin ancho…
        .let { Rect(it.left, it.top, it.left + advancePx, it.bottom) }  // …+ una celda
}
```

`advancePx` es el ancho de celda ya medido en §8.5, que en una monoespaciada es constante. El alpha de la animación se lee **dentro** del lambda de dibujo.

### 8.4 Degradado (§4.2, §4.6.4)

`Modifier.drawBehind` con el estado de animación leído **dentro** del lambda: solo se ejecuta la
fase de dibujo, cero recomposición y cero relayout.

```kotlin
Modifier.drawBehind {
    val t = phase.value                       // dentro, nunca fuera
    drawRect(Brush.verticalGradient(...))
}
```

Leer el estado en el cuerpo del composable y pasarlo como parámetro recompone el árbol entero 60-120
veces por segundo. `drawWithCache` tampoco sirve, pero no por lo que suele decirse: su bloque de
caché **sí** se reejecuta cuando cambia el estado que lee, así que leer la fase ahí dentro
reconstruiría la caché y reasignaría el `Shader` en cada frame; y leerla fuera no se actualizaría
nunca. `drawBehind` deja la lectura en la fase de dibujo, que es exactamente lo que se busca.

Con un ciclo de 20s y ±2%, cuantizar el tiempo a ~10 Hz es visualmente idéntico y elimina la
asignación de un `Shader` por frame.

### 8.5 Glifos 5×5 (Fase 4)

El ancho de celda se mide con `rememberTextMeasurer()` sobre una cadena **larga** dividida entre su
longitud: `TextLayoutResult.size` es `IntSize` en píxeles enteros, y medir un solo carácter acumula
error de subpíxel que desalinea toda la retícula.

Cada glifo va en su propio `Modifier.graphicsLayer{}` para que quede en un `RenderNode` aparte y sus
invalidaciones no toquen a los hermanos. La regla de «un solo glifo animado» (§4.4) es, además de
estética, lo que mantiene esto barato.

### 8.6 Movimiento reducido (§4.7, criterio 13)

**En Android no existe un `isReduceMotionEnabled`.** La API pública es
`ValueAnimator.areAnimatorsEnabled()` (API 26+, justo el `minSdk` del proyecto), respaldada por
`Settings.Global.ANIMATOR_DURATION_SCALE`, que el toggle «Quitar animaciones» de Accesibilidad pone
a 0.

Ojo con un caso que se escapa: el **Ahorro de batería** también desactiva los animadores **sin
tocar ese ajuste**. Un `ContentObserver` sobre el `Uri` de `ANIMATOR_DURATION_SCALE` no lo detecta.
Por eso la decisión de «congelar en el fotograma de estado» lee `areAnimatorsEnabled()` además de
observar el ajuste.

La buena noticia: las animaciones de `compose-animation` ya lo respetan **gratis**, porque leen
`MotionDurationScale` del contexto de la corrutina, que el `Recomposer` alimenta observando ese
ajuste. Con escala 0 la animación termina en el frame siguiente.

La mala: eso significa que el degradado se queda **clavado en su valor final**, y los bucles de los
glifos también. Como la §4.7 pide justo eso (glifos sin bucle, en su fotograma de estado), hay que
asegurarse de que ese fotograma final sea el correcto y no un estado intermedio feo. Y se observa
en vivo, no solo al arrancar.

### 8.7 Nada de Material

`BasicText` y `BasicTextField` viven en `foundation`. Lo único que se pierde sin `material3` es
`ripple()`, que la §4.8 prohíbe explícitamente. Aun así, `indication = null` explícito en cualquier
`clickable`: el defecto de `foundation` no es un ripple, pero sí un overlay translúcido en press.

Sin `MaterialTheme` hacen falta `CompositionLocal` propios para color y tipografía. Es una ventaja:
la paleta de la §4.2 se define entera y no hay forma de colar un color con tono por descuido.

### 8.8 Arranque (criterio 10)

Lo que mata el arranque de un launcher no es Compose: es el trabajo en `Application.onCreate` y la
I/O en el hilo principal.

- `Application.onCreate` vacío.
- Scrollback en `Dispatchers.IO` → `StateFlow`; se pinta el prompt vacío primero.
- **No usar `core-splashscreen`** en una actividad HOME: se relanzaría en cada pulsación de HOME.
  El «arranque instantáneo» se consigue con `android:windowBackground` puesto al color de tinta del
  degradado, que es la starting window del sistema.
- Baseline Profile: ~30% de mejora documentada, pero exige un módulo de macrobenchmark. Para una
  sola actividad es sobreingeniería en la v1; primero `minifyEnabled` + `shrinkResources` en release,
  que sale casi gratis. Se reevalúa si el criterio 10 no se cumple.

---

## 9. Toolchain y versiones

> **Verificado documentalmente el 26/07/2026, sin compilar.** La primera tarea real del proyecto
> (0.0/0.1) es instalar el toolchain y confirmar que esta combinación sincroniza. Si alguna versión
> no existe o no encaja, se corrige aquí y en `gradle/libs.versions.toml`, que es el único sitio
> donde viven los números.

| Pieza | Versión | Nota |
|---|---|---|
| AGP | 9.3.1 | Estable. Exige Gradle ≥ 9.5.0, JDK 17, Build Tools 36.0.0, compileSdk máx. 37 |
| Gradle | 9.6.1 | Mínimo real 9.5.0 |
| JDK | 17 | Mínimo y por defecto de AGP 9.3 |
| Kotlin (KGP) | 2.2.10 | **La que declara el POM de AGP 9.3.1**, y por tanto la que compila: el proyecto no sobreescribe el Kotlin integrado. Subir a 2.3.x/2.4.x exigiría meter KGP en el classpath del buildscript, y no se hace |
| Plugin de Compose | = Kotlin | `org.jetbrains.kotlin.plugin.compose`, versión **idéntica** a Kotlin. Aplicarlo en 2.3.x sobre un compilador 2.2.10 es el desajuste clásico |
| Compose BOM | 2026.06.01 | → foundation/ui 1.11.4 |
| activity-compose | 1.13.0 | `setContent`, `enableEdgeToEdge`, `BackHandler` |
| compileSdk | 37 | Android 17 |
| targetSdk | 36 | Android 16. Subir compileSdk sin subir targetSdk da los avisos de deprecación sin activar los cambios de comportamiento |
| minSdk | 26 | Suelo de las fuentes variables; muy por debajo de cualquier móvil real de destino |

**AGP 9 trae Kotlin integrado:** no se aplica `org.jetbrains.kotlin.android` en ningún sitio, y
`kotlinOptions{}` se sustituye por `kotlin { compilerOptions { } }`. El plugin de Compose es el único
plugin de Kotlin que se sigue aplicando a mano.

Otros cambios de AGP 9 que rompen plantillas antiguas: `buildConfig` y `resValues` vienen
**desactivados**; `proguard-android.txt` está prohibido (se usa `proguard-android-optimize.txt`);
`applicationVariants` ya no existe (todo por `androidComponents.onVariants`).

**Dependencias, la lista entera:**

```
runtime
  androidx.compose:compose-bom            (platform)
  androidx.compose.foundation:foundation
  androidx.compose.ui:ui-graphics
  androidx.activity:activity-compose
  androidx.core:core-ktx                  (AtomicFile.writeText)
tooling
  androidx.compose.ui:ui-tooling-preview
  androidx.compose.ui:ui-tooling          (solo debug)
test
  junit
```

En runtime: sin material3, sin Hilt, sin Room, sin DataStore y sin ninguna librería de terceros.

**Aviso de tamaño:** con targetSdk 36+ y pantallas de ≥600dp se ignoran `screenOrientation`,
`resizeableActivity` y las restricciones de aspecto. El terminal tiene que redimensionarse bien —
importante con el teclado abierto y el prompt anclado arriba.

**Fuente:** JetBrains Mono (OFL-1.1) en `res/font`, nombres en minúsculas. Un `.ttf` estático pesa
~200-300 KB por peso; como la §4.3 exige **un solo peso**, es un único fichero.

---

## 10. Testing

Sin emulador mientras se pueda:

- **JVM (`app/src/test`)** — todo `core/`: parser, generación de handles, los cuatro rangos de
  resolución, ambigüedad (incluido «un rango posterior no rescata a uno ambiguo»), sustitución
  posicional, límites de script, recursión, recorte del scrollback, saneado de nombres.
- **La jaula de rutas (§4.8) se testea antes de que exista ningún comando de fichero**, y contra un
  directorio temporal real: `..` encadenado, symlink que apunta fuera, ruta absoluta ajena, cwd
  manipulado y el prefijo textual engañoso (`/x-evil` contra `/x`). Es el criterio 15 y es la única
  parte del producto donde un fallo destruye datos del usuario.
- **Frontera** — un test que falle si aparece un `import android.` dentro de `core/`.
- **Dispositivo real, a mano** — lo que no se puede simular: que el teclado salga solo al desbloquear,
  que el catálogo se refresque al instalar una app, que el scrollback sobreviva a un reinicio, que el
  degradado no parpadee al volver de otra app, y las restricciones del fabricante (§16).

No se testean composables uno a uno: el valor está en el motor, y el aspecto se juzga mirándolo.

---

## 11. Decisiones de implementación abiertas

1. **Supresión de sugerencias del teclado** (§8.2). Ni `autoCorrectEnabled` ni `KeyboardType.Ascii`
   apagan el diccionario. Se decide en la tarea 0.12, contra Gboard en el dispositivo real.
2. **Versiones del toolchain** (§9). Nada se ha resuelto compilando: la combinación entera se
   confirma en la tarea 0.1. El primer candidato a fallar es la pareja Kotlin / plugin de Compose.
3. **Modo seguro tras crash** (§3.5). Propuesta de arquitectura **que la especificación no pide**, y
   que roza la §1.2 («el degradado es el producto, no una preferencia»). No se implementa sin
   subirla antes al funcional como decisión explícita.
4. **Baseline Profile** (§8.8). Solo si el criterio 10 no se cumple sin él.
5. **Perfil privado** (§4.1). Fuera de alcance; si entra, revisar qué se persiste.
