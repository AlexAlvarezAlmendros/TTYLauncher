# Plan 01 — Gestión de apps

> Fase: 1 de 6 | Estado: 🔄 En curso | Iniciado: 2026-07-26 | Cerrado: —
> Hito del roadmap: `kill`, `uninstall` e `info` funcionan y ningún comando destructivo adivina ante ambigüedad.

> **`kill` está cerrado (2026-07-26).** `killBackgroundProcesses()` dejó de afectar a otras apps en
> Android 14 y falla en silencio, así que `kill` abre el diálogo del sistema e imprime el límite en
> el mensaje. **`restart` se retira del catálogo.** El funcional ya está corregido; detalle en
> [architecture.md §4.4](../../architecture.md#44-kill).

Convierte el launcher en algo con lo que se *administra* el teléfono, no solo se abre. Los cuatro
verbos que faltan (`kill`, `uninstall`, `info`, `settings`) y el endurecimiento de la resolución de apps
ahora que hay comandos que destruyen.

El tema de la fase es el **principio 4**: nada silencioso que sea destructivo. Cada comando de esta
lista o deja rastro en pantalla o pasa por un diálogo del sistema.

---

## Dependencia con otras fases

- **Requiere:** Fase 0 completa (catálogo de apps, resolución por rangos, motor de comandos).
- **Habilita:** Fase 2 (ficheros) y, a través de ella, todo lo demás.

---

## Tareas

### Comandos

| # | Tarea | Estado | Notas |
|---|-------|--------|-------|
| 1.0 | ~~Cerrar la decisión sobre `kill`~~ | ✅ Hecho | 2026-07-26: abre el diálogo del sistema; `restart` retirado. Funcional corregido |
| 1.1 | `kill` / `stop` — abre los ajustes de la app + `force stop <handle> in the system dialog (android 14+ blocks it from an app)` | 🔄 En curso | Escrito y con tests. Detrás de `AppKiller`; `SystemDialogKiller` es la única implementación. **Falta ver el diálogo en un móvil** |
| ~~1.2~~ | ~~`restart`~~ | ❌ Cancelado | Sería indistinguible de `kill`. Vuelve si algún día hay un `kill` de verdad |
| 1.3 | `uninstall` — abre el diálogo del sistema e imprime `confirm in the system dialog` | 🔄 En curso | `ACTION_DELETE` + `REQUEST_DELETE_PACKAGES`. Con tests. Falta verlo en un móvil |
| 1.4 | `uninstall` rechaza apps de sistema con un error propio, en vez de dejar que el diálogo falle | ✅ Hecho | `FLAG_SYSTEM` comprobado **antes** de abrir nada. Con test |
| 1.5 | `info` — etiqueta, handle, paquete, actividad y si es del sistema, en columnas alineadas | ✅ Hecho | Cinco filas en celdas de carácter. Con test |
| 1.6 | `info -o` — abre la pantalla de ajustes de esa app en vez de imprimir | 🔄 En curso | Éxito silencioso, con test. Falta verlo en un móvil |
| 1.7 | `settings` — abre los ajustes generales de Android | 🔄 En curso | Cascada `ACTION_HOME_SETTINGS` → apps por defecto → ajustes. Falta verlo en un móvil |

### Permisos y límites de plataforma

| # | Tarea | Estado | Notas |
|---|-------|--------|-------|
| 1.8 | `REQUEST_DELETE_PACKAGES` para `uninstall`, y comprobar en el dispositivo real que no es solo teórico. **`KILL_BACKGROUND_PROCESSES` ya no se declara**: no sirve para nada | 🔄 En curso | Declarado en el manifest. Lo de «no es solo teórico» necesita un móvil |
| 1.9 | Degradación honesta si la plataforma no permite la acción: mensaje que dice qué hizo y qué no | ✅ Hecho | Cada acción devuelve `Boolean` y ninguna lanza; el comando convierte el `false` en una línea de error. Con tests |
| 1.10 | Interfaz `AppKiller` declarada en `core/` con la implementación del diálogo; el hueco de Shizuku queda abierto sin implementarlo | ✅ Hecho | `AppKiller` + `KillMode`. `kill` ya lee el modo para decidir qué mensaje imprime |

### Resolución endurecida

| # | Tarea | Estado | Notas |
|---|-------|--------|-------|
| 1.11 | Revisar que el error de ambigüedad enumera los candidatos en todos los verbos, no solo en los destructivos | ✅ Hecho | Los cuatro verbos comparten `withApp`: resolver distinto en cada uno era la vía de que uno adivinara |
| 1.12 | Mensajes de "no encontrado" con sugerencia por distancia de edición | ✅ Hecho | `core/text/Suggest`: Levenshtein con umbral por longitud y desempate determinista. También en verbos desconocidos |
| 1.13 | Tests JVM: cada rango de resolución, ambigüedad dentro del rango acertado, y que un rango posterior no rescata a uno ambiguo | ✅ Hecho | 22 tests nuevos (129 en total, 0 fallos) |

### Ayuda

| # | Tarea | Estado | Notas |
|---|-------|--------|-------|
| 1.14 | `help` incluye los cuatro comandos nuevos con sus alias, su ficha individual (`help kill`) y sigue cabiendo en una pantalla | 🔄 En curso | Registrados: `help` los enumera solo. Que quepan ocho verbos en una pantalla se mide mirándola |
| 1.15 | Auditoría de la §10 sobre las cadenas nuevas de la fase | 🔄 En curso | Minúsculas, sin punto final, límite en el mensaje. Falta el repaso final con todo en pantalla |

---

## Entregable

El launcher administra: se mata lo que molesta, se desinstala lo que sobra, se consulta lo que hay
y se llega a los ajustes sin salir del prompt.

## Criterio de aceptación

Criterio 5 de [functional.md §13](../../functional.md#13-criterios-de-aceptación) (`uninstall wh` con dos
candidatos nunca desinstala nada), más los límites de la §12 dichos en el propio mensaje. Puerta de
fase: unos días de uso real sin volver al launcher anterior.

---

## Registro de avance

| Fecha | Tarea | Notas |
|-------|-------|-------|
| 2026-07-26 | — | Plan creado. Bloqueado por la Fase 0. |
| 2026-07-26 | 1.4 · 1.5 · 1.9 · 1.10 · 1.11 · 1.12 · 1.13 | **Los cuatro verbos implementados y verdes.** `AppKiller`+`KillMode` en `core/` con `SystemDialogKiller` como única implementación; `kill`, `uninstall`, `info` (+`-o`) y `settings`; `AppActions` ampliada con las tres acciones que pasan por un diálogo del sistema; `core/text/Suggest` (Levenshtein con umbral por longitud) enchufado a los errores de app **y** a los verbos desconocidos. Los cuatro comparten un único `withApp`, para que ninguno pueda resolver distinto. 22 tests nuevos: 129 en total, 0 fallos, y `assembleDebug` en verde. Lo que falta es un móvil: los cuatro terminan abriendo una pantalla del sistema que nadie ha visto abrirse. |
