# tty — CLAUDE.md

Launcher Android **en forma de terminal**: sustituye la pantalla de inicio por un prompt. Sin
iconos, sin cuadrícula, sin widgets, sin cajón de aplicaciones. Se escribe `open spotify` y se abre
Spotify. La interacción es un **lenguaje**, no una selección.
Stack: Kotlin + Jetpack Compose / sin DI / sin ORM / ficheros planos / Termux por RUN_COMMAND.

Documentación: [funcional](docs/functional.md) · [arquitectura](docs/architecture.md) · [design system](docs/design/DESIGN-SYSTEM.md) · [roadmap](docs/planning/ROADMAP.md).

> Principio rector del producto: **el texto es la interfaz**. Si algo se puede expresar como una
> línea de texto, se expresa así. Un icono figurativo, un badge o un color semántico son una derrota.

---

> **Este fichero manda sobre cualquier `CLAUDE.md` de un directorio superior.** El proyecto es
> **Kotlin + Gradle**, y punto: aquí no hay Solidity, ni Foundry, ni Fastify, ni Drizzle, ni React,
> ni pnpm, ni monorepo. Si un CLAUDE.md heredado describe ese stack, no aplica a este repo.

---

## Workflow de desarrollo — OBLIGATORIO

**Antes de escribir cualquier línea de código**, ejecuta el skill `/tty-plan`:

1. Lee `docs/planning/ROADMAP.md` — fase actual, decisiones abiertas, bloqueos.
2. Lee el plan de la fase en `docs/planning/plans/NN-*.md` — si no existe, créalo con el formato de
   los que ya hay.
3. Marca las tareas que vas a abordar como `🔄 En curso`.
4. Implementa siguiendo TODAS las convenciones de este archivo.
5. Al terminar, marca `✅ Hecho`, desbloquea las tareas que dependían y añade una fila al registro
   de avance con la fecha.
6. Reporta: qué se completó y cuáles son las siguientes tareas listas.

Aplica a cualquier petición de desarrollo: feature, comando, pantalla, refactor o test.

**Reglas de fase, no negociables:**

- No se empieza una fase sin haber usado la anterior como launcher por defecto unos días.
- Una tarea está `⬜ Listo` solo si todas sus dependencias están `✅ Hecho`.
- Si una decisión abierta del ROADMAP bloquea lo que te piden, **pregunta**; no la cierres tú.

---

## Estrategia de ramas y pull requests — OBLIGATORIO

`main` es la rama estable. **Nunca desarrollar features directamente en `main`.**

1. Una rama por tarea del plan, desde `main` actualizada: `feat/0.11-prompt-foco`, `fix/<slug>`,
   `chore/<slug>`, `docs/<slug>`.
2. La rama incluye todo lo de esa tarea: código, tests y la actualización de su plan.
3. Antes de abrir la PR, calidad en verde: `./gradlew test` y `./gradlew assembleDebug`.
4. Push a `origin` y **pull request a `main`** con `gh pr create`. Título en imperativo; cuerpo con
   qué se hace, qué tarea cierra y cómo se verificó. Sin atribución a la IA.
5. **No mergear la PR**: el usuario revisa y mergea.

Excepción: ediciones de solo documentación/planificación pueden ir directas a `main`.

---

## Estado del repositorio

Las seis fases están implementadas, **compilan y sus 307 tests pasan**. El toolchain está instalado
en esta máquina bajo `$HOME` (sin `sudo`) y exportado en `~/.zshrc`:

```
JAVA_HOME      ~/.local/opt/jdk17          Temurin 17.0.20
ANDROID_HOME   ~/Android/Sdk               platform-tools · platforms;android-37.0 · build-tools;37.0.0
Gradle          por wrapper (9.6.1)        ~/.local/opt/gradle-9.6.1 solo se usó para generarlo
emulador       ~/Android/Sdk/emulator      AVD `ttytest` · system-images;android-36;google_apis;x86_64
```

`local.properties` (con `sdk.dir`) es local de la máquina y está en `.gitignore`: no se versiona.

**Cómo arrancar el emulador y ver qué pasa de verdad:**

```bash
$ANDROID_HOME/emulator/emulator -avd ttytest -no-window -no-audio -gpu swiftshader_indirect &
adb wait-for-device && adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.tty.debug/dev.tty.MainActivity
adb logcat -b crash -d              # el trace, si murió
adb exec-out screencap -p > /tmp/x.png
```

> El emulador cubre lo que se puede simular: que arranque sin morirse, que los comandos respondan,
> que la pantalla se pinte. **No cubre** que el catálogo se refresque al instalar una app de verdad,
> ni Termux, ni cómo se siente el movimiento en un panel real. Para eso sigue haciendo falta el
> teléfono. No afirmes que algo funciona en el móvil sin haberlo visto en el móvil.
>
> **Un crash de arranque en la actividad HOME deja el teléfono sin pantalla de inicio.** Antes de
> dar por buena una PR que toque `MainActivity`, `AppContainer`, `TerminalState` o la UI, arráncala
> en el emulador. Pasó exactamente eso: seis fases en verde y la app muriendo en el 100% de los
> arranques por una propiedad declarada después del `init` que la leía.

```
app/src/main/kotlin/dev/tty/
  core/        Kotlin puro, sin un solo import de android.*
    apps/      handles y resolución por rangos
    fs/        jaula de rutas, cwd y verbos de fichero
    script/    intérprete
  platform/    Implementaciones Android de las interfaces de core/
  ui/theme/    Palette · Type · Spacing · Motion — espejo del design system
  ui/          Compose
docs/          functional.md · architecture.md · design/ · planning/
```

**El vocabulario, entero.** Apps y sistema: `help` `apps` `open` `kill` `uninstall` `info` `script`
`sh` `tmux` `clear` `settings`. Ficheros: `pwd` `cd` `ls` `cat` `head` `tail` `mkdir` `rm` `mv` `cp`
`touch` `df` `du` `find` `mount`. **`ls`, `cat` y `rm` son de ficheros**; `apps` no tiene alias `ls`
y desinstalar es `uninstall`. Añadir un verbo es una decisión de producto: pregunta.

---

## Comandos esenciales

```bash
# Build
./gradlew assembleDebug
./gradlew assembleRelease

# Tests (JVM, sin emulador)
./gradlew test
./gradlew :app:testDebugUnitTest --tests "dev.tty.core.apps.*"

# Instalar en el dispositivo
./gradlew installDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Limpiar
./gradlew clean
```

El wrapper ya está generado y versionado (`gradlew`, `gradlew.bat` y `gradle-wrapper.jar`).

---

## Versiones fijas

Viven **solo** en `gradle/libs.versions.toml`. No hardcodear versiones en ningún `build.gradle.kts`.

```
AGP            9.3.1     (exige Gradle >= 9.5.0, JDK 17, Build Tools 36.0.0)
Gradle         9.6.1
JDK            17
Kotlin (KGP)   2.2.10    la que declara el POM de AGP 9.3.1 — confirmada compilando
Compose plugin = Kotlin  org.jetbrains.kotlin.plugin.compose — DEBE coincidir con Kotlin
Compose BOM    2026.06.01
activity-compose 1.13.0
compileSdk     37        → platforms;android-37.0
targetSdk      36
minSdk         26
Build Tools    37.0.0    (36.0.0 también existe; AGP resuelve la que necesita)
```

**Verificado compilando el 26/07/2026**: `assembleDebug` y `test` en verde con esta combinación
exacta. Si algo cambia, se corrige el catálogo y se anota en `docs/architecture.md §9`.

**AGP 9 trae Kotlin integrado:** no se aplica `org.jetbrains.kotlin.android` en ningún sitio, y se
usa `kotlin { compilerOptions { } }`, no `kotlinOptions{}`. `buildConfig` y `resValues` vienen
desactivados por defecto.

### Dependencias — la lista entera

```
runtime:    androidx.compose:compose-bom · compose.foundation · compose.ui:ui-graphics
            androidx.activity:activity-compose · androidx.core:core-ktx
tooling:    compose.ui:ui-tooling-preview · compose.ui:ui-tooling (solo debug)
test:       junit
```

**Añadir una dependencia es una decisión de producto, no de implementación.** En runtime: sin
material3, sin Hilt, sin Room, sin DataStore y sin ninguna librería de terceros. Si crees que hace
falta una, pregunta.

---

## Arquitectura — separación obligatoria

```
core/       Kotlin puro. Parser, comandos, resolución de apps, intérprete de scripts, recorte
            del scrollback. NI UN SOLO import de android.* — es lo que permite testear en JVM
platform/   LauncherApps, ficheros, Termux. Implementa interfaces declaradas en core/
ui/         Compose. No contiene lógica de negocio
```

`core/` no importa `platform/` ni `ui/`. `platform/` no importa `ui/`. Sin ciclos.
Dependencias por constructor desde `AppContainer`. Nunca un singleton global, nunca un contenedor
de DI.

**Contrato de salida:** un comando devuelve `List<Line>`, donde `Line = (id, text, role)` y
`role ∈ {OUTPUT, ECHO, ERROR, STATUS, RECORDING}`. El rol decide color, prefijo y animación. El `id`
monótono es la key de la lista de Compose — **nunca el índice**.

---

## Reglas estéticas — NO NEGOCIABLES

Son la razón de ser del producto y lo primero que se erosiona. Ante la duda, se rechaza.

**Prohibido, sin excepciones:**

- Ningún icono figurativo. Los glifos de matriz de puntos 5×5 son la única iconografía.
- **Ningún color con tono.** Ni verde de éxito, ni rojo de error, ni ámbar de aviso. Los errores
  son un gris más brillante.
- Ningún borde salvo la línea del prompt. Ninguna esquina redondeada, ninguna sombra, ningún
  desenfoque, ningún efecto de vidrio.
- Ningún ripple ni feedback táctil de Material (`indication = null` explícito).
- Ningún emoji, ninguna imagen, ningún wallpaper.
- Ningún spinner, ninguna barra de progreso, ningún skeleton — para eso está el glifo `BUSY`.
- Ningún efecto de máquina de escribir en salida larga.
- Ninguna pantalla de ajustes. Lo que se puede cambiar se cambia escribiendo.
- Ningún tema claro.

**Obligatorio:**

- Una sola familia monoespaciada, **un solo peso**, dos tamaños (cuerpo 13sp, etiqueta 10sp).
  La jerarquía se hace con color, opacidad y tracking. Nunca con peso ni con tamaños nuevos.
- Cinco paradas de degradado, fijas al viewport, que **nunca** llegan al blanco.
- El prompt está fijo arriba y no se mueve jamás: ni con el teclado, ni con el scroll, ni con la
  cantidad de historial.
- Toda animación comunica un estado nombrable. Si no se puede nombrar qué informa, se corta.
- **Techo de 500ms para las animaciones de transición** (un solo disparo, disparadas por una
  acción). Los bucles de estado —los seis glifos y el cursor— y la deriva ambiental del degradado
  están exentos por definición: no son transiciones, son estados sostenidos. Todos sus ciclos están
  en `Motion.kt` y ninguno se inventa en el sitio de uso.
- Como máximo **un glifo animado en pantalla**. Los del historial van congelados en su fotograma.
- La rejilla 5×5 se dibuja **entera**: apagados al 18%, encendidos al 100%.

**Los valores salen del design system, no del criterio del momento.** Color, tipografía, espaciado
y duraciones viven en `ui/theme/` (`Palette.kt`, `Type.kt`, `Spacing.kt`, `Motion.kt`), que espeja
[el design system](docs/design/DESIGN-SYSTEM.md). Si un composable necesita un número que no está
ahí, el número está mal o falta en el design system: no se escribe a mano en el composable.

Detalle completo en [functional.md §4](docs/functional.md#4-especificación-estética) y en el
[design system](docs/design/DESIGN-SYSTEM.md). Si una petición choca con esta sección, dilo antes
de implementarla.

---

## Lenguaje de la interfaz

La interfaz es texto: escribirlo mal es un bug de UI.

- **Idioma del producto: inglés.** La documentación va en castellano; el producto no.
- Todo en minúsculas salvo el banner y las etiquetas. Sin puntos finales.
- **Los errores no se disculpan.** Nada de "sorry", "oops", "something went wrong".
- Un error dice **qué pasó y qué hacer**: `'wh' is ambiguous — whatsapp, whatsapp-bsns` cumple;
  `invalid input` no.
- **El éxito silencioso es el valor por defecto.** Si el resultado se ve (la app se abrió), no se
  imprime nada. Se confirma solo lo que no se ve.
- **Los límites se dicen en el mensaje**, no en la documentación: `(background only)` va ahí porque
  el usuario necesita saberlo en el momento en que actúa.
- Prefijos: `>` eco de entrada, `…` línea grabada, `!` error.
- El comando no cambia de nombre entre sintaxis, ayuda y error. Si es `rm`, es `rm` en todas partes.

---

## Convenciones Kotlin

- Sin `!!`. Los nullables de la plataforma (`getLaunchIntentForPackage`, `applicationInfo`) se
  tratan explícitamente: devuelven un error legible en el terminal, nunca una excepción.
- Corrutinas: I/O en `Dispatchers.IO`, **nunca** en el hilo principal. Un ANR en la actividad HOME
  es indistinguible de un móvil bloqueado.
- Las clases de `core/` no reciben `Context`. Si una necesita algo del sistema, se declara una
  interfaz en `core/` y se implementa en `platform/`.
- Los límites (2000 líneas, 500 líneas, profundidad 4, 200 líneas por script, 15s de timeout) son
  constantes con nombre en `core/`, en un solo sitio.
- `enum class` para estados cerrados. Nada de `String` como estado.

## Convenciones Compose

- **Sin `MaterialTheme`.** Color y tipografía por `CompositionLocal` propios. `BasicText` y
  `BasicTextField` (con `TextFieldState`) viven en `foundation`.
- El estado de una animación se lee **dentro** del lambda de `drawBehind`/`drawWithContent`, nunca
  en el cuerpo del composable. Leerlo fuera recompone el árbol 60-120 veces por segundo.
- Lista del scrollback: `reverseLayout = false` con la lista ya invertida, `key = { it.id }`, y
  `requestScrollToItem(0)` tras insertar en cabeza (no `scrollToItem`, que es `suspend` y produce
  saltos).
- Foco del prompt: esperar a `LocalWindowInfo.current.isWindowFocused` antes de `requestFocus()`.
  Pedirlo antes funciona en el emulador y falla en el dispositivo real.
- El degradado ocupa la ventana entera sin insets; los insets se aplican solo al contenido;
  `imePadding()` solo al historial.
- `BackHandler(enabled = true) {}` para inutilizar el botón atrás. `onBackPressed()` ya no se
  ejecuta con targetSdk 36+ y **no avisa en compilación**.

---

## Seguridad y privacidad — reglas no negociables

- **El scrollback es un registro en disco de lo que el usuario hace con su teléfono.** Vive en
  `noBackupFilesDir`, excluido de copia en la nube y de transferencia entre dispositivos por
  `dataExtractionRules` **y** `fullBackupContent`. `clear` es el único borrado real del producto.
- Nunca `QUERY_ALL_PACKAGES`. `<queries>` con MAIN+LAUNCHER cubre el 100% del caso de un launcher.
- **La jaula de rutas es la única parte del producto donde un fallo destruye datos del usuario.**
  Se escribe antes que cualquier comando de fichero y se testea antes de existir. `toRealPath()`,
  nunca `normalize()`; comparar por `Path`, **nunca por `String`** (`/x-evil` casa con `/x`);
  revalidar justo antes de borrar (TOCTOU); `walkFileTree` sin `FOLLOW_LINKS`; y `rm` se niega sobre
  la raíz, el cwd y sus ancestros.
- `Files.newDirectoryStream`, nunca `File.listFiles()`: `listFiles()` devuelve `null` igual para «no
  existe», «no es un directorio» y «sin permiso», y un shell tiene que distinguirlos.
- Nombres de script: allowlist por regex **y** verificación de contención por ruta canónica con
  separador final (`dir.path + File.separator`). Sin el separador, `/scripts` casa con
  `/scripts_evil`.
- Ningún comando puede dejar la actividad en un estado no recuperable. Ser el launcher por defecto
  convierte cualquier crash en un móvil inutilizable.
- Nunca I/O del scrollback en el hilo principal dentro de `onCreate`.
- Los literales de la API de Termux (claves del bundle, digests de firma) no son API estable: al
  subir de versión de Termux hay que reverificarlos y degradar con mensaje claro, nunca en silencio.

---

## Testing

**JVM (`app/src/test/`), sin emulador — es todo lo que hay:**

- **La jaula de rutas, antes que ningún comando de fichero**: `..` encadenado, symlink que apunta
  fuera, ruta absoluta ajena, cwd manipulado, prefijo textual engañoso.
- Parser, generación de handles, los cuatro rangos de resolución.
- Ambigüedad, incluido que **un rango posterior no rescata a uno ambiguo**.
- Sustitución posicional, límites de script, recursión que termina en error.
- Recorte del scrollback, lectura tolerante a la última línea truncada, saneado de nombres.
- Un test que falle si aparece un `import android.` dentro de `core/`.
- **`TerminalState`, aunque viva en `ui/`.** No es un composable: es una clase de Kotlin normal que
  la actividad construye en `onCreate`. «No se testean composables» **no** la exime, y creerlo costó
  un crash de arranque al 100% de los lanzamientos. Si una clase de `ui/` no lleva `@Composable`,
  se testea.

No se testean composables uno a uno: el valor está en el motor. Lo visual se juzga mirándolo en el
dispositivo real, que es también donde se prueba lo que no se puede simular (que el teclado salga
solo, que el catálogo se refresque al instalar una app, que el scrollback sobreviva a un reinicio).
