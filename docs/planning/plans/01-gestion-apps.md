# Plan 01 — Gestión de apps

> Fase: 1 de 6 | Estado: 🔒 Bloqueado | Iniciado: — | Cerrado: —
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
| 1.1 | `kill` / `stop` — `startAppDetailsActivity` + `force stop <handle> in the system dialog (android 14+ blocks it from an app)` | 🔒 Bloqueado | Detrás de una interfaz `AppKiller` en `core/`. **Ni un indicador de progreso esperando a que el proceso muera**: no va a morir |
| ~~1.2~~ | ~~`restart`~~ | ❌ Cancelado | Sería indistinguible de `kill`. Vuelve si algún día hay un `kill` de verdad |
| 1.3 | `uninstall` — abre el diálogo del sistema e imprime `confirm in the system dialog` | 🔒 Bloqueado | §6.2. La constancia queda en el scrollback aunque el usuario cancele |
| 1.4 | `uninstall` rechaza apps de sistema con un error propio, en vez de dejar que el diálogo falle | 🔒 Bloqueado | §6.2. Detección explícita de app de sistema |
| 1.5 | `info` — etiqueta, handle, paquete, actividad y si es del sistema, en columnas alineadas | 🔒 Bloqueado | §6.2 |
| 1.6 | `info -o` — abre la pantalla de ajustes de esa app en vez de imprimir | 🔒 Bloqueado | §6.2 |
| 1.7 | `settings` — abre los ajustes generales de Android | 🔒 Bloqueado | Sin él no hay forma de volver al launcher anterior sin instalar otra cosa |

### Permisos y límites de plataforma

| # | Tarea | Estado | Notas |
|---|-------|--------|-------|
| 1.8 | `REQUEST_DELETE_PACKAGES` para `uninstall`, y comprobar en el dispositivo real que no es solo teórico. **`KILL_BACKGROUND_PROCESSES` ya no se declara**: no sirve para nada | 🔒 Bloqueado | Ver [architecture.md §4](../../architecture.md) |
| 1.9 | Degradación honesta si la plataforma no permite la acción: mensaje que dice qué hizo y qué no | 🔒 Bloqueado | §12. Un límite de Android no es un bug propio, pero tampoco se oculta |
| 1.10 | Interfaz `AppKiller` declarada en `core/` con la implementación del diálogo; el hueco de Shizuku queda abierto sin implementarlo | 🔒 Bloqueado | §12. Si algún día llega, ningún comando cambia de nombre |

### Resolución endurecida

| # | Tarea | Estado | Notas |
|---|-------|--------|-------|
| 1.11 | Revisar que el error de ambigüedad enumera los candidatos en todos los verbos, no solo en los destructivos | 🔒 Bloqueado | §7.2. Coherencia: el usuario confía en que el sistema **jamás** adivina |
| 1.12 | Mensajes de "no encontrado" con sugerencia por distancia de edición | 🔒 Bloqueado | La misma sugerencia que reaprovecha el autocompletado de la Fase 6 |
| 1.13 | Tests JVM: cada rango de resolución, ambigüedad dentro del rango acertado, y que un rango posterior no rescata a uno ambiguo | 🔒 Bloqueado | Criterio 5 |

### Ayuda

| # | Tarea | Estado | Notas |
|---|-------|--------|-------|
| 1.14 | `help` incluye los cuatro comandos nuevos con sus alias, su ficha individual (`help kill`) y sigue cabiendo en una pantalla | 🔒 Bloqueado | Criterio 4. Si deja de caber, el problema es el diseño del comando |
| 1.15 | Auditoría de la §10 sobre las cadenas nuevas de la fase | 🔒 Bloqueado | Misma revisión que la 0.32b |

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
