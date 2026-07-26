# Plan 04 — Termux

> Fase: 4 de 6 | Estado: 🔒 Bloqueado | Iniciado: — | Cerrado: —
> Hito del roadmap: `tmux build -k "…"` devuelve la foto del pane con la tipografía de `tty`.

Es la fase que separa esto de un launcher de texto bonito: sin ella el vocabulario es fijo; con ella
cualquier cosa que se pueda escribir en un script de shell se convierte en un comando del launcher.

También es la fase de mayor riesgo técnico del proyecto. La API de RUN_COMMAND **no es estable** y
Termux no publica release estable desde mayo de 2025. Los literales exactos (claves del bundle,
extras, digests de firma) están en [architecture.md §7](../../architecture.md); hay que reverificarlos
contra el repo de Termux al subir de versión, y degradar con un mensaje claro, nunca en silencio.

---

## Dependencia con otras fases

- **Requiere:** Fase 3 — `sh` dentro de un script es lo que hace útil la integración.
- **Habilita:** Fase 5 (el glifo `SHELL` necesita algo que informe).

---

## Tareas

### Cliente RUN_COMMAND

| # | Tarea | Estado | Notas |
|---|-------|--------|-------|
| 4.1 | Manifest: `<queries><package com.termux>` y `uses-permission com.termux.permission.RUN_COMMAND` | 🔒 Bloqueado | Sin el `<queries>`, «instalado» es indistinguible de «no visible» |
| 4.2 | Solicitud del permiso en runtime (es `dangerous` y lo define otra app) | 🔒 Bloqueado | Solo concedible si Termux ya está instalado; se pierde si se reinstala Termux |
| 4.3 | Envío del intent al servicio con `RUN_COMMAND_BACKGROUND=true` (runner `app-shell`) | 🔒 Bloqueado | En modo terminal-session `stdout` es el transcript, no la salida real |
| 4.4 | `PendingIntent` con `FLAG_MUTABLE` + `FLAG_ONE_SHOT` y `requestCode` único por comando | 🔒 Bloqueado | Con `FLAG_IMMUTABLE` el bundle llega **vacío** |
| 4.5 | Lectura del bundle: extra contenedor `"result"`, claves `stdout`/`stderr`/`exitCode`/`err`/`errmsg` | 🔒 Bloqueado | **No** es `"result_bundle"`. Es el error más probable de toda la integración |
| 4.6 | Timeout de 15s del lado del launcher + descarte de resultados tardíos por id de correlación | 🔒 Bloqueado | RUN_COMMAND no tiene extra de timeout |

### Los tres errores diferenciados (§9.4)

| # | Tarea | Estado | Notas |
|---|-------|--------|-------|
| 4.7 | Puerta 1 — Termux ausente: `NameNotFoundException` → `termux not installed (use the F-Droid or GitHub build)` | 🔒 Bloqueado | Criterio 8 |
| 4.8 | Puerta 1b — build de Google Play: digest SHA-256 del firmante → mismo mensaje, que ya dirige a F-Droid/GitHub | 🔒 Bloqueado | El build de Play está congelado y sin plugins: aunque se conceda todo, no funciona |
| 4.9 | Puerta 2 — permiso: `checkSelfPermission` **antes** de llamar → `termux: RUN_COMMAND permission not granted` | 🔒 Bloqueado | Comprobar antes evita la `SecurityException` |
| 4.10 | Puerta 3 — `allow-external-apps`: por `errmsg` del bundle o por timeout → `termux: could not start RunCommandService — is allow-external-apps set?` | 🔒 Bloqueado | **No lanza excepción**: `startService` devuelve OK. Solo se detecta así |
| 4.11 | Distinguir `ForegroundServiceStartNotAllowedException` de un `IllegalStateException` genérico | 🔒 Bloqueado | Extiende `IllegalStateException`. Si aparece con la HOME visible es un bug propio, no configuración de Termux |

### Comandos

| # | Tarea | Estado | Notas |
|---|-------|--------|-------|
| 4.12 | `sh` / `!` — ejecuta una línea y devuelve la salida como líneas de texto | 🔒 Bloqueado | §9.2 |
| 4.13 | Recorte de salida a 500 líneas, coherente con el límite general | 🔒 Bloqueado | §5.8. Además Termux trunca a 100 KB combinados stdout+stderr |
| 4.14 | `tmux` / `t` con `[sesión] [-n N] [-k teclas]` | 🔒 Bloqueado | §9.3 |
| 4.15 | Crear la sesión si no existe (`has-session` → `new-session -d`), enviar teclas, capturar el pane | 🔒 Bloqueado | Cada argumento es un argv: no hay parseo de shell |
| 4.16 | Socket de tmux fijado explícitamente para hablar siempre con el mismo servidor | 🔒 Bloqueado | El servidor de un `app-shell` y el de una terminal solo comparten socket si comparten `TMUX_TMPDIR` |
| 4.17 | Mensaje claro si `tmux` no está instalado en Termux | 🔒 Bloqueado | No viene de serie (`pkg install tmux`) |
| 4.18 | La salida de `sh` y `tmux` se emite con rol `OUTPUT`, que nunca se descodifica | 🔒 Bloqueado | §4.5. Prepara la Fase 5 |

### Calidad

| # | Tarea | Estado | Notas |
|---|-------|--------|-------|
| 4.19 | Tests JVM del parseo del bundle y de la clasificación de errores, con bundles sintéticos | 🔒 Bloqueado | El cliente se testea sin Termux mockeando la capa de envío |
| 4.20 | Prueba manual de las tres puertas en el dispositivo real, cerrándolas una a una | 🔒 Bloqueado | Es la única forma de validar el criterio 8 de verdad |
| 4.21 | Anotar en este plan la versión de Termux contra la que se verificó | 🔒 Bloqueado | §16: al subir de versión, reverificar contra el wiki de RUN_COMMAND |

---

## Entregable

`sh uptime` devuelve la salida en el scrollback, y `tmux build -k "npm run build" -n 40` crea la
sesión, lanza la compilación y la fotografía. Repetir `tmux build` refresca la foto.

## Criterio de aceptación

Criterio 8 de [functional.md §13](../../functional.md#13-criterios-de-aceptación): con Termux
ausente, `sh` explica exactamente qué falta. Y las otras dos puertas dan su mensaje propio, no uno
genérico. **El mensaje de error es el onboarding**: no se construye ningún asistente.

---

## Registro de avance

| Fecha | Tarea | Notas |
|-------|-------|-------|
| 2026-07-26 | — | Plan creado. Bloqueado por la Fase 3. Constantes de la API verificadas contra `termux-app` el 26/07/2026 (ver architecture.md §7). |
