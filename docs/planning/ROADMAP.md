# tty — Roadmap del proyecto

> Última actualización: 2026-07-26

Launcher Android en forma de terminal: sustituye la pantalla de inicio por un prompt. Sin iconos,
sin cuadrícula, sin cajón de aplicaciones. Vocabulario cerrado y auditable, con una única puerta
explícita hacia Termux.

Documentación: [funcional](../functional.md) · [arquitectura](../architecture.md) · [design system](../design/DESIGN-SYSTEM.md).

## Estrategia

**Usable en cada fase.** Cada fase deja un launcher que se puede poner por defecto y vivir con él.
La regla de avance no es "está implementado", es **"lo he usado como launcher por defecto unos
días y no he vuelto al anterior"**. Esa es la puerta entre fase y fase.

Dos consecuencias que hay que respetar:

- **La estética no es pulido posterior.** El degradado y la tipografía van en la Fase 0, porque
  son el producto. Lo que se aplaza a la Fase 4 es el *sistema de movimiento*, no el aspecto.
- **El movimiento va al final a propósito.** Sobre una base que ya se usa a diario, las
  animaciones se calibran contra uso real en vez de contra una maqueta.

## Fases

| # | Fase | Estado | Plan | Hito |
|---|------|--------|------|------|
| 0 | Sustituye a tu launcher | 🔄 En curso | [00-launcher-base.md](plans/00-launcher-base.md) | El móvil arranca en `tty` y se abre cualquier app escribiendo 3–4 letras |
| 1 | Gestión de apps | 🔒 Bloqueado | [01-gestion-apps.md](plans/01-gestion-apps.md) | `kill`, `uninstall` e `info` funcionan y ningún comando destructivo adivina ante ambigüedad |
| 2 | Ficheros | 🔒 Bloqueado | [02-ficheros.md](plans/02-ficheros.md) | `cd Download` y `ls` devuelven lo que hay en el teléfono, y nada puede tocar fuera de la raíz |
| 3 | Scripts | 🔒 Bloqueado | [03-scripts.md](plans/03-scripts.md) | `focus obsidian` ejecuta tres comandos con argumento posicional |
| 4 | Termux | 🔒 Bloqueado | [04-termux.md](plans/04-termux.md) | `tmux build -k "…"` devuelve la foto del pane con la tipografía de `tty` |
| 5 | Sistema de movimiento | 🔒 Bloqueado | [05-movimiento.md](plans/05-movimiento.md) | Los seis glifos, `settle`/`decode` y las cinco microanimaciones, con movimiento reducido soportado |
| 6 | Pulido | 🔒 Bloqueado | [06-pulido.md](plans/06-pulido.md) | Historial de entradas, banner real y sugerencia por distancia de edición |

## Foco actual

**Fase 0 — Sustituye a tu launcher.** El andamiaje está escrito (Gradle, manifest de la actividad
HOME, tema, paleta y una `MainActivity` que pinta el degradado) pero **no se ha compilado nunca**.
El siguiente paso es cerrar la 0.0 (toolchain) y la 0.1 (que el proyecto sincronice de verdad); a
partir de ahí, el prompt y el motor de comandos.

**Bloqueo de entorno:** esta máquina no tiene JDK, Gradle ni Android SDK instalados
(`java`, `gradle` y `adb` no existen en el `PATH`). Nada del andamiaje se ha compilado nunca. La
tarea 0.0 es instalar el toolchain y verificar que `./gradlew assembleDebug` pasa; hasta entonces
todo el código escrito es **no verificado**.

## El problema de `kill` — cerrado

`ActivityManager.killBackgroundProcesses()` dejó de afectar a otras apps en **Android 14 (API 34)**:
solo mata los procesos de la propia app, con independencia del targetSdk, y **falla en silencio**.

**Decidido el 2026-07-26: `kill` abre el diálogo del sistema** (la pantalla de ajustes de la app,
donde vive «Forzar detención») e imprime el límite en el propio mensaje. Conserva el verbo, cumple
el principio 4 y deja el hueco de Shizuku detrás de una interfaz `AppKiller` en `core/`.

**`restart` se retira del catálogo:** con `kill` convertido en «abre el diálogo», sería
indistinguible de él. Vuelve el día que exista un `kill` de verdad.

`functional.md` §6.1, §6.2, §12 y §14 ya están corregidos. Detalle en
[architecture.md §4.4](../architecture.md#44-kill).

## Grafo de dependencias

```
Fase 0 (HOME + prompt + apps/open/help/clear + persistencia)
  ├──► Fase 1 (kill · uninstall · info · settings)
  │      └──► Fase 2 (ficheros: cd · ls · cat · rm …)   ← la jaula de rutas va primero
  │             └──► Fase 3 (scripts)   ← un script es útil cuando hay verbos que encadenar
  │                    └──► Fase 4 (Termux: sh · tmux)
  └──────────────────► Fase 5 (sistema de movimiento)   ← necesita superficie real que animar
                          └──► Fase 6 (pulido)
```

La Fase 5 depende de la 0 para tener superficie, pero se calibra mejor con las fases 1–4 ya en
uso: cuanto más variada es la salida, mejor se juzga si el movimiento envejece bien.

## Leyenda de estados

| Icono | Significado |
|-------|-------------|
| ⬜ Listo / Pendiente | Sin bloqueos, se puede empezar |
| 🔄 En curso | Se está trabajando ahora |
| ✅ Hecho | Completado |
| 🔒 Bloqueado | Espera a otra tarea o a una fase anterior |
| ❌ Cancelado | Fuera de alcance |

## Decisiones tomadas

- 2026-07-26 — **Entran los comandos de fichero, y `ls`/`cat`/`rm` son de ficheros.** Quince verbos
  (§6.3): `pwd`, `cd`, `ls`, `cat`, `head`, `tail`, `mkdir`, `rm`, `mv`, `cp`, `touch`, `df`, `du`,
  `find`, `mount`. Consecuencias: `apps` pierde el alias `ls`, desinstalar pasa a llamarse
  `uninstall`, y el modelo conceptual admite un **sexto concepto**, el directorio de trabajo, con
  la justificación escrita en la §3.
- 2026-07-26 — **Implementación nativa, nunca delegada a Termux.** Un listado nativo cuesta 1-5 ms;
  por `RUN_COMMAND` cuesta 100-250 ms en caliente y hasta 2 s en frío, tiene cuatro modos de fallo
  y **no da ni un byte más de superficie**: sobre `/sdcard`, que es donde el usuario teclea, el
  launcher ve exactamente lo mismo que Termux. `sh` sigue siendo la única escotilla, explícita.
- 2026-07-26 — **La raíz es `/sdcard` y el permiso se pide en la primera ejecución.** Es la única
  concesión del producto a un onboarding, y está anotada como tal en la §11.
- 2026-07-26 — **Stack: Kotlin + Jetpack Compose, sin Hilt y sin Room.** El producto es de un solo
  usuario y un puñado de conceptos; un contenedor de DI y un ORM son ceremonia que no paga. Inyección
  por constructor a mano desde un único `AppContainer`, persistencia en ficheros de texto del
  almacenamiento privado.
- 2026-07-26 — **Compose sobre Vistas/XML.** El coste de arranque se mitiga (perfil baseline,
  nada de trabajo en `Application.onCreate`), y el sistema de glifos y de animaciones de texto
  es sustancialmente menos código en Compose. Se revisa si el arranque no cumple el criterio 10.
- 2026-07-26 — **Sin Material3 como sistema visual.** Se construye sobre `foundation` + `ui`. La
  §4.8 prohíbe ripple, esquinas redondeadas, sombras y color con tono, que es justo lo que aporta
  Material. Si se acaba dependiendo de `material3`, es por una pieza concreta y documentada, no
  por tema.
- 2026-07-26 — **Scrollback en fichero de texto plano append-only** con recorte a 2000 líneas y
  escritura diferida ~1s, en almacenamiento privado y excluido de copias. Ni DataStore (pensado
  para claves-valor pequeños) ni SQLite (no hay consultas que justifiquen un motor).
- 2026-07-26 — **`kill` abre el diálogo del sistema y `restart` se retira.** La plataforma dejó de
  permitir detener otra app en Android 14. El verbo sobrevive porque sigue siendo el correcto y
  porque el límite se dice en el mensaje; la interfaz `AppKiller` queda en `core/` esperando a un
  posible backend con privilegios. Ver arriba.
- 2026-07-26 — **El design system manda en los valores visuales.** Importado desde Claude Design
  (proyecto «tty Design System»), autoría desde esta misma especificación. `functional.md` manda en
  las reglas, el design system en los números, y `ui/theme/*.kt` los espeja. Ver
  [design/DESIGN-SYSTEM.md](../design/DESIGN-SYSTEM.md).
- 2026-07-26 — **El motor de comandos no conoce Android.** `core/` es Kotlin puro y testeable en
  JVM; los efectos (abrir, matar, desinstalar, Termux) entran por interfaces implementadas en la
  capa `platform/`. Es lo que permite testear la resolución de apps y los scripts sin emulador.

## Decisiones abiertas

Heredadas de [functional.md §15](../functional.md#15-decisiones-abiertas). Las que afectan a
código y hay que cerrar en algún momento:

- **La §8 no especifica `script ls`, `cat` ni `rm`.** La ficha de la §6.2 dice «Cuatro subcomandos
  … Ver sección 8», pero la §8 solo detalla `new`. Falta el formato de salida de los otros tres:
  hay que escribirlo antes de la Fase 2, o el plan 02 lo inventará.
- **Los cinco hexadecimales del degradado** — el design system los marca como interpretación de la
  especificación. Se confirman calibrando en pantalla real (tarea 0.7) y se actualizan **allí
  primero**.
- **Modo seguro tras crash** — propuesta de `architecture.md §3.5` que la especificación no pide y
  que roza la §1.2. No se implementa sin subirla antes al funcional.
- **Supresión de sugerencias del teclado** — ni `autoCorrectEnabled=false` ni `KeyboardType.Ascii`
  apagan el diccionario de Gboard. Se decide contra el dispositivo real en la tarea 0.12.
- **Orden del historial** (invertido vs clásico) — se prueba el invertido dos semanas. Es una
  propiedad de layout.
- **Autocompletado por tabulador** — depende de resolver el gesto en teclado virtual. Fase 5.
- **Notificaciones** (`notify`) — aplazado a después de la Fase 4, y solo si el uso lo pide.
- **Alias de apps**, **reloj permanente**, **gestos**, **`grep` del scrollback** — recomendación
  de partida: no. Reabrirlas exige justificarlas contra los principios de la §1.3.
