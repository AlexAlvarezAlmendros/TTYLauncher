# Plan 00 — Sustituye a tu launcher

> Fase: 0 de 6 | Estado: 🔄 En curso | Iniciado: 2026-07-26 | Cerrado: —
> Hito del roadmap: el móvil arranca en `tty` y se abre cualquier app escribiendo 3–4 letras.

Levanta el producto entero en su forma mínima: actividad HOME, prompt fijo arriba con foco, cuatro
comandos (`help`, `apps`, `open`, `clear`) y scrollback persistente. **El degradado y la tipografía
van aquí**, no en una fase de pulido: son el producto, no su acabado.

Lo que NO entra: glifos animados, `settle`/`decode`, microanimaciones. En esta fase el texto
aparece sin animación de entrada y el prompt lleva un cursor de bloque simple. La Fase 5 sustituye
esa capa sin tocar el motor.

---

## Dependencia con otras fases

- **Requiere:** nada. Es la primera.
- **Habilita:** todas las demás. Sin superficie no hay nada que animar ni verbo que encadenar.

---

## Tareas

### Entorno y andamiaje

| # | Tarea | Estado | Notas |
|---|-------|--------|-------|
| 0.0 | Toolchain local: JDK 17, Android SDK (+ `adb`), variables de entorno. Verificar con `./gradlew assembleDebug` | ✅ Hecho | Temurin 17.0.20 + SDK (android-37.0, build-tools 37.0.0, platform-tools) bajo `$HOME`, sin `sudo`, exportado en `~/.zshrc`. Wrapper 9.6.1 generado y versionado |
| 0.1 | Proyecto Gradle: version catalog, `settings.gradle.kts`, módulo `app`, Compose habilitado, `assembleDebug` en verde | ✅ Hecho | Toda la combinación del catálogo sincroniza y compila. APK de 12 MB. `src/test/kotlin` sí está en el source set: los 107 tests se ejecutan |
| 0.2 | Firma de depuración reproducible + `adb install` en el dispositivo real | ⬜ Listo | Desbloqueada: el APK ya se genera. **Falta conectar un móvil** — `adb devices` no ve ninguno |

### Actividad HOME

| # | Tarea | Estado | Notas |
|---|-------|--------|-------|
| 0.3 | `AndroidManifest.xml`: intent-filter `MAIN` + `HOME` + `DEFAULT`, `launchMode`, `stateNotNeeded`, `configChanges`, `adjustResize` | 🔄 En curso | Escrito, sin verificar en dispositivo. Ver [architecture.md §3.1](../../architecture.md#31-manifest) |
| 0.4 | Pulsar HOME estando dentro no reinicia estado ni animaciones (`onNewIntent`, sin recrear la actividad) | 🔄 En curso | `onNewIntent` ya es un no-op. Falta comprobarlo con estado real que perder (§5.6) |
| 0.5 | Botón atrás inerte con `BackHandler` (no `onBackPressed`, que ya no se ejecuta con targetSdk 36+) | 🔄 En curso | Escrito, sin verificar (§5.7) |
| 0.6 | Edge-to-edge e insets: degradado a los bordes, contenido con `safeDrawing`, `imePadding()` solo al historial | 🔄 En curso | La mitad del degradado está; el `imePadding()` llega con la lista (0.14) |
| 0.6b | Ningún comando puede dejar la actividad en estado no recuperable; `settings` (Fase 1) es la vía de rescate | ⬜ Listo | §16. El **modo seguro** con render degradado que propone [architecture.md §3.5](../../architecture.md#35-que-un-crash-no-inutilice-el-móvil-16) **no entra aquí**: la especificación no lo pide y roza la §1.2. Decisión abierta |

### Estética (no es pulido: es el producto)

| # | Tarea | Estado | Notas |
|---|-------|--------|-------|
| 0.7 | Tokens del design system espejados en `ui/theme/`: color, tipografía, espaciado y movimiento | 🔄 En curso | `Palette.kt`, `Type.kt`, `Spacing.kt` y `Motion.kt` escritos con los valores del [design system](../../design/DESIGN-SYSTEM.md). **Los cinco hexadecimales del degradado están pendientes de confirmar en pantalla real**: el propio design system los marca como interpretación |
| 0.8 | Tipografía: JetBrains Mono empaquetada con fallback a la monoespaciada del sistema; dos tamaños, un solo peso | ⬜ Listo | §4.3. Hoy se usa `FontFamily.Monospace`: falta empaquetar el `.ttf` (OFL-1.1, ~250 KB) en `res/font` y apuntar `Type.Mono` |
| 0.9 | Fondo: degradado vertical **fijo al viewport**, dibujado sin recomponer el árbol | 🔄 En curso | Pintado con `drawBehind`. La deriva animada es Fase 5 |
| 0.10 | Desvanecimiento por antigüedad: opacidad decreciente con la distancia al prompt, mínimo 35% | 🔄 En curso | `ScrollbackList.fadeAlpha` derivado del índice. Compila; falta verlo en pantalla |

### Prompt y scrollback

| # | Tarea | Estado | Notas |
|---|-------|--------|-------|
| 0.11 | Campo de entrada fijo arriba: foco automático y teclado visible sin tocar la pantalla | 🔄 En curso | `PromptRow` espera a `isWindowFocused` antes de `requestFocus`. **Es el criterio 2 y solo se valida en el dispositivo real** |
| 0.12 | Teclado sin autocorrección, sin autocapitalización, acción "Ir"; enviar limpia el campo y mantiene foco | 🔄 En curso | Escrito. **Ni `autoCorrectEnabled` ni `KeyboardType.Ascii` apagan el diccionario de Gboard**: decisión abierta que se cierra probando |
| 0.13 | Cursor de bloque parpadeante propio (curva suave), sin el cursor del sistema | 🔄 En curso | Dibujado con `getBoundingBox` solo si hay carácter debajo y `getCursorRect` + ancho de celda al final. `Palette.CURSOR_ALPHA_MIN` |
| 0.14 | Lista de scrollback invertida: lo nuevo arriba, el historial empujado hacia abajo | 🔄 En curso | `reverseLayout=false` con la lista ya invertida, `key = id`. El prompt está fuera de la lista |
| 0.15 | Scroll manual hacia el historial que no rebota solo; cualquier entrada nueva vuelve arriba | 🔄 En curso | `requestScrollToItem(0)` al enviar y al cambiar la cabeza |
| 0.16 | Límites: 2000 líneas en memoria, 500 líneas por comando | ✅ Hecho | `core/Limits.kt`, un solo sitio. `truncated()` es idempotente y el aviso entra dentro del cupo. Con tests |
| 0.16b | Contrato de líneas: eco de la entrada con `>` **antes de toda salida**, `!` en errores, y entrada vacía como no-op silencioso | 🔄 En curso | El prefijo lo da `Role`; el eco lo garantiza `TerminalEngine`, no la UI. Con test |
| 0.16c | El prompt está en la **misma coordenada vertical** con 0 líneas y con 2000: fuera del árbol de la lista, `imePadding()` solo al historial | 🔄 En curso | Column con el prompt fijo y la lista con `weight(1f)`. Verificable solo en pantalla |

### Motor de comandos (Kotlin puro, sin Android)

| # | Tarea | Estado | Notas |
|---|-------|--------|-------|
| 0.17 | Parser de la línea de entrada: verbo, flags, argumento, comillas | ✅ Hecho | `CommandLine` (tokenizador) + `Flags`. 27 tests en verde |
| 0.18 | Registro de comandos y despacho; contrato de salida (líneas + rol de cada línea) | ✅ Hecho | `Command`/`CommandRegistry`/`Output`. El orden incorporado→script→error fijado. Con tests |
| 0.19 | Catálogo de apps: enumerar las que exponen actividad de lanzamiento, generar handles | 🔄 En curso | `LauncherAppsCatalog` con `<queries>` MAIN+LAUNCHER. Multiperfil: se queda con el primer perfil (renuncia anotada) |
| 0.20 | Resolución por rangos (paquete exacto → handle exacto → prefijo → subcadena) y **error ante ambigüedad** | ✅ Hecho | `AppResolver`, Kotlin puro. 21 tests en verde, incluido que un rango posterior no rescata a uno ambiguo |
| 0.21 | Frescura del catálogo: instalar/desinstalar/actualizar se refleja sin reiniciar | 🔄 En curso | Solo `LauncherApps.Callback`, atado a onStart/onStop. Sin BroadcastReceiver |

### Comandos de la fase

| # | Tarea | Estado | Notas |
|---|-------|--------|-------|
| 0.22 | `help` / `?` / `h` sin argumento — dos columnas, cabe en una pantalla sin scroll horizontal | 🔄 En curso | Escrito. Que quepa se mide en pantalla, no aquí |
| 0.22b | `help <comando>` — sintaxis, descripción y **alias** de ese comando | 🔄 En curso | Los alias solo se imprimen aquí, en ningún otro sitio |
| 0.23 | `apps` con `-s` y filtro por subcadena, dos columnas + total | 🔄 En curso | Sin alias `ls`: es de ficheros. Columnas en celdas de carácter |
| 0.24 | `open` / `o` — éxito **silencioso** | 🔄 En curso | Devuelve `Output.silent`. Solo habla si la plataforma falla |
| 0.25 | `clear` / `cls` / `clean` — vacía memoria **y** disco, imprime `scrollback cleared` | 🔒 Bloqueado | §6.2. Único borrado real del producto. La línea se emite con el rol que la Fase 5 renderiza como `decode` (contrato de la 0.18) |

### Persistencia

| # | Tarea | Estado | Notas |
|---|-------|--------|-------|
| 0.26 | Scrollback en almacenamiento privado: append, escritura diferida ~1s, recorte a 2000 líneas | 🔄 En curso | `noBackupFilesDir/scrollback.log`, append + debounce; compactación con `AtomicFile` |
| 0.27 | Escritura resistente a que el sistema mate el proceso: `flush()` al vencer el debounce, flush definitivo en `onStop()` (no en `onPause`) y lectura tolerante a una última línea truncada | 🔒 Bloqueado | Criterio 9. **`AtomicFile` no se usa en el camino caliente** — reescribe el fichero entero; su sitio es la compactación de la 0.26 ([architecture.md §6.1](../../architecture.md#61-scrollback)) |
| 0.28 | Excluir de copia en la nube y de transferencia entre dispositivos | 🔄 En curso | `data_extraction_rules.xml` + `backup_rules.xml` escritos y `noBackupFilesDir` elegido. Falta verificarlo con `bmgr` (§5.5) |
| 0.29 | Carga al arrancar sin bloquear el primer frame | 🔄 En curso | `setContent` primero, `restore()` en corrutina después |

### Primera ejecución y calidad

| # | Tarea | Estado | Notas |
|---|-------|--------|-------|
| 0.30 | Banner de primera ejecución con `TYPE HELP`. Sin tutorial, sin tour, sin tarjetas | 🔄 En curso | Condición: scrollback vacío al arrancar. Ya lleva modelo, versión, apps y líneas |
| 0.31 | Tests JVM del core: parser, generación de handles, resolución por rangos, ambigüedad, recorte del scrollback | ✅ Hecho | **107 tests, 0 fallos** (`./gradlew test`), incluido el de la frontera `core/` sin `android.*` |
| 0.32 | Repaso contra §4.8: ni un icono figurativo, ni un color con tono, ni un ripple, ni una esquina redondeada | 🔒 Bloqueado | Criterio 3. Se hace **antes** de cerrar la fase |
| 0.32b | Auditoría de la §10 sobre cada cadena del producto: minúsculas, sin punto final, errores que no se disculpan, inglés, y un solo nombre por comando entre sintaxis, `help` y error | 🔒 Bloqueado | §10. Se repite al cierre de **cada** fase que añada verbos, igual que la 0.32 con la §4.8 |
| 0.32c | Verificar el criterio 1 extremo a extremo: desbloquear, escribir 3–4 caracteres, enviar, app abierta. Sin ningún toque adicional | 🔒 Bloqueado | Criterio 1. Es el criterio más importante y el único que se mide con el móvil en la mano |

---

## Entregable

Un APK instalable que se puede fijar como launcher por defecto y con el que se vive: se desbloquea
el móvil, aparece el prompt con el teclado, se escriben tres letras y se abre la app. El historial
sobrevive a reiniciar el teléfono.

## Criterio de aceptación

Los criterios 1, 2, 3, 4, 6, 9, 10 y 11 de [functional.md §13](../../functional.md#13-criterios-de-aceptación),
más la puerta de fase: **unos días como launcher por defecto sin volver al anterior**.

---

## Registro de avance

| Fecha | Tarea | Notas |
|-------|-------|-------|
| 2026-07-26 | — | Plan creado. Bloqueado por 0.0: falta el toolchain de Android en la máquina de desarrollo. |
| 2026-07-26 | 0.0 · 0.1 | **Toolchain instalado y primera compilación real.** Temurin 17.0.20, Android SDK (android-37.0, build-tools 37.0.0, platform-tools) y el wrapper de Gradle 9.6.1, todo bajo `$HOME` sin `sudo`. `assembleDebug` produce un APK de 12 MB y `test` pasa **107 de 107**. Un solo error de compilación en 3.900 líneas: `KeyboardActionHandler` estaba importado de `androidx.compose.foundation.text` cuando vive en `...text.input`. Los 5 tests que fallaron eran de `truncated()`, que había cambiado de semántica después de escribirlos: se corrigieron los tests, no la implementación — el aviso de recorte entra **dentro** del cupo, así que el total nunca pasa de 500 y la operación es idempotente. |
| 2026-07-26 | 0.10-0.31 | **Fase 0 implementada.** ~3.900 líneas de Kotlin. `core/` (13 ficheros, Kotlin puro): `Limits`, `Line`/`Role`/`Output`, `CommandLine`+`Flags`, `AppEntry`+`AppResolver`, `Command`/`CommandRegistry`, los cuatro verbos, `Scrollback`, `Columns`, `Banner` y `TerminalEngine`. `platform/` (4): `LauncherAppsCatalog` con `LauncherApps.Callback`, `ScrollbackStore` con append + debounce + compactación atómica, `DeviceInfoImpl` y `AppContainer` (DI a mano por constructor). `ui/` (5): `TerminalSurface`, `PromptRow`, `ScrollbackList`, `TerminalScreen` y `TerminalState`. `MainActivity` atada al ciclo de vida. 107 tests JVM en 7 ficheros. **Nada ejecutado ni compilado**: sigue faltando la 0.0. Se declaró `kotlinx-coroutines-android`, que hasta ahora llegaba solo por transitividad de Compose. |
| 2026-07-26 | 0.7 | **Design system importado** desde Claude Design («tty Design System», autoría desde esta misma especificación). Los tokens se espejan en `ui/theme/`: `Palette.kt` (cinco paradas, tres niveles de texto, línea del prompt, desvanecimiento, suelo del 18% de la matriz de puntos), `Type.kt`, `Spacing.kt` y `Motion.kt` (todas las duraciones y las dos curvas). Los valores de `Palette.kt` se corrigieron a los del design system: los que había eran una estimación propia. Ver [docs/design/DESIGN-SYSTEM.md](../../design/DESIGN-SYSTEM.md). |
| 2026-07-26 | 0.1 · 0.3 · 0.4 · 0.5 · 0.6 · 0.7 · 0.9 · 0.28 | **Andamiaje escrito, sin compilar.** Gradle (settings + raíz + `app` + catálogo de versiones + `gradle.properties`), manifest de la actividad HOME con el intent-filter y los atributos definitivos, reglas de exclusión de copia, tema con `windowBackground` de tinta (starting window sin flash blanco), icono adaptativo sin iconografía figurativa, `Palette.kt` con las cinco paradas y los tres niveles de texto, y `MainActivity` mínima: edge-to-edge, `BackHandler` inerte, `onNewIntent` no-op y el degradado a pantalla completa con una etiqueta. **Falta el `gradle-wrapper.jar`** (no se puede generar sin un Gradle instalado) y **ninguna versión del catálogo se ha podido resolver**. Nada de esto cuenta como hecho hasta que 0.0 esté cerrada. |
