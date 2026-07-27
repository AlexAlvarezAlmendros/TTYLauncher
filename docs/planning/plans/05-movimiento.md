# Plan 05 — Sistema de movimiento

> Fase: 5 de 6 | Estado: 🔄 En curso | Iniciado: 2026-07-26 | Cerrado: —
> Hito del roadmap: los seis glifos, `settle`/`decode` y las cinco microanimaciones, con movimiento reducido soportado.

Es la fase que hace que el producto se reconozca de un vistazo, y va **al final a propósito**:
sobre una base que ya se usa a diario, el movimiento se calibra contra la salida real de `apps`, de
`sh` y de `tmux`, no contra una maqueta con tres líneas de ejemplo.

La regla que gobierna toda la fase: **si no se puede nombrar qué estado informa una animación, se
corta**. La lista de abajo está cerrada; añadir una sexta microanimación exige justificarla contra
el principio 7.

---

## Dependencia con otras fases

- **Requiere:** Fase 0 (superficie). Se calibra mucho mejor con las fases 1–4 en uso, porque la
  variedad de salida es lo que revela si el movimiento envejece bien.
- **Habilita:** Fase 6.

---

## Tareas

### Infraestructura de movimiento

| # | Tarea | Estado | Notas |
|---|-------|--------|-------|
| 5.1 | Detección de "movimiento reducido" del sistema, observada en vivo (no solo al arrancar) | ✅ Hecho | §4.7. Se hace **primero**: cada animación nace ya respetándolo |
| 5.2 | Presupuesto de duración centralizado en `Motion.kt`: techo de 500ms para las **transiciones**; los bucles de estado (glifos, cursor) y la deriva ambiental quedan exentos con su propio ciclo | ✅ Hecho | §4.6-4.7. Ya está escrito: aquí se cablea y se comprueba que nadie escribe una duración a mano |
| 5.3 | La entrada nunca se bloquea por una animación: se escribe el siguiente comando mientras se revela el anterior | ✅ Hecho | Criterio 12. Sin perder ni una pulsación |
| 5.4 | Las animaciones no se encolan: salida nueva salta la anterior a su estado final | ✅ Hecho | §4.7 |

### Glifos de matriz de puntos

| # | Tarea | Estado | Notas |
|---|-------|--------|-------|
| 5.5 | Celda de glifo 5×5 con el ancho exacto de un carácter de la fuente monoespaciada, **con la rejilla entera dibujada**: apagados al 18%, encendidos al 100% | ✅ Hecho | §4.4 y el [design system](../../design/DESIGN-SYSTEM.md). El suelo del 18% es lo que hace que `READY` se lea como glifo y no como una mota, y lo que da estela a `BUSY` y `SHELL` |
| 5.6 | `READY` — punto central respirando, 2.4s, opacidad 40%→100% | ✅ Hecho | Prompt en reposo |
| 5.7 | `BUSY` — columna barriendo de izquierda a derecha, bucle 600ms | ✅ Hecho | Sustituye a cualquier spinner (§4.8) |
| 5.8 | `SHELL` — cascada de filas de arriba abajo, bucle 900ms | ✅ Hecho | Solo durante ejecución en Termux |
| 5.9 | `REC` — punto central pulsando, 800ms | ✅ Hecho | Modo grabación |
| 5.10 | `OK` — convergencia a diagonal ascendente y atenuado, 400ms, una vez | ✅ Hecho | Solo en comandos completados **con salida** |
| 5.11 | `FAIL` — X con vibración única de 300ms, luego estático | ✅ Hecho | Sin rojo. El color es el de la línea |
| 5.12 | **Como máximo un glifo animado en pantalla**: los del historial quedan congelados en su fotograma final | ✅ Hecho | Criterio 14. Sin esta regla, el historial es una discoteca |
| 5.13 | El glifo **sustituye** a los prefijos `>` y `!` que la 0.16b ya imprime; el `…` de grabación se mantiene como carácter | ✅ Hecho | §4.4. `REC` ya informa del modo en el prompt |
| 5.13b | Glifo del control del historial de entradas: atenuado, **estático**, exento de la regla de la 5.12 por no informar de un estado | 🔄 En curso | §5.4. Es el séptimo glifo y no está en la tabla cerrada de la §4.4: si no convence, la 6.2 lo resuelve con un carácter de la retícula |

### Aparición de texto

| # | Tarea | Estado | Notas |
|---|-------|--------|-------|
| 5.14 | `settle` — opacidad 0→100% y +4dp, escalonado 25ms, solo las 12 primeras líneas, techo 300ms para el bloque | ✅ Hecho | §4.5. Por defecto para toda salida de comandos |
| 5.15 | `decode` — resolución carácter a carácter desde `▚▞░▒▓/\|-_=+*`, ~14ms por carácter, techo 500ms | 🔒 Bloqueado | §4.5. Solo líneas ≤48 caracteres. Sus dos consumidores son el banner y el `scrollback cleared` de la 0.25 |
| 5.16 | `decode` **prohibido** en la salida de `apps`, `sh` y `tmux`, por regla del motor y no por criterio del que escribe el comando | ✅ Hecho | §4.5. El rol de la línea lo decide el comando (contrato de la tarea 0.18) |
| 5.17 | El historial persistido se muestra sin animación de entrada al arrancar | ✅ Hecho | §5.2. Solo se anima lo que llega durante la sesión |

### Microanimaciones (cinco, cerradas)

| # | Tarea | Estado | Notas |
|---|-------|--------|-------|
| 5.18 | Cursor de bloque con parpadeo de curva suave, 1.06s | ✅ Hecho | Ya existe desde 0.13; aquí se refina la curva |
| 5.19 | Eco de entrada: destello de 80ms al 60% antes de asentarse en atenuado | ✅ Hecho | Confirma el envío sin imprimir nada |
| 5.20 | Barrido de luz en la línea del prompt, una vez por ejecución | ✅ Hecho | Es el "enter" hecho visible |
| 5.21 | Deriva del degradado: paradas ±2% cíclicas cada 20s | ✅ Hecho | Única animación ambiental; exenta del techo de 500ms |
| 5.22 | Caída al limpiar: `clear` deja caer y desvanecer el historial en 120ms | ✅ Hecho | No desaparece de golpe |

### Cierre de la fase

| # | Tarea | Estado | Notas |
|---|-------|--------|-------|
| 5.23 | Con movimiento reducido: sin `decode`, sin `settle`, sin barrido, sin deriva; cursor y glifos sin bucle | ✅ Hecho | Criterio 13. La app sigue siendo **completamente** usable |
| 5.24 | Medición de fotogramas con el scrollback lleno; si baja de 60fps se recorta movimiento, nunca legibilidad | 🔄 En curso | §4.7 |
| 5.25 | Auditoría final contra §4.8: ningún spinner, ninguna barra de progreso, ningún efecto de máquina de escribir en salida larga | 🔄 En curso | La lista de prohibiciones se repasa entera |

---

## Entregable

El producto con su identidad completa: el prompt respira, los comandos barren, los errores vibran
una vez y el degradado deriva casi imperceptiblemente.

## Criterio de aceptación

Criterios 12, 13 y 14 de [functional.md §13](../../functional.md#13-criterios-de-aceptación).
Y la prueba que de verdad importa: **a la cuarta semana el movimiento sigue sin molestar**. Si
molesta, se recorta aquí, no se añade un ajuste para desactivarlo (§1.2: no es un launcher
configurable).

---

## Registro de avance

| Fecha | Tarea | Notas |
|-------|-------|-------|
| 2026-07-26 | — | Plan creado. Bloqueado por la Fase 0 (y se recomienda tener 1–4 en uso antes de calibrar). |
| 2026-07-26 | 5.1-5.9 · 5.12 · 5.14-5.18 · 5.21 · 5.23 | **El sistema de movimiento.** `ui/glyph/Glyph` dibuja la rejilla 5×5 entera —apagados al 18%, encendidos al 100%— con los seis estados; la gramática (`intensity`) se separó del dibujo para poder razonarla sin Compose delante. `ui/motion/`: `settle` y `decode`, y `rememberReducedMotion`, que lee **`ValueAnimator.areAnimatorsEnabled()` además del `ContentObserver`** porque el Ahorro de batería desactiva los animadores sin tocar el ajuste. La elección entre `settle` y `decode` vive en `core/output/RevealPolicy`, a partir del **rol** de la línea: es lo que impide que alguien decida un día que `apps` quedaría bonito descifrándose. Deriva del degradado cuantizada a ~10 Hz para no reasignar un `Shader` por frame. 7 tests nuevos. |
| 2026-07-26 | 5.10 · 5.11 · 5.13 · 5.19 · 5.20 · 5.22 | **Cerradas de verdad.** `Line` lleva glifo, el scrollback lo pinta congelado en la celda del prefijo, y las tres microanimaciones que faltaban están implementadas. Antes se habían dado por hechas sin estarlo: **Devueltas a «en curso»: estaban marcadas hechas y no lo estaban.** Lo pilló la revisión final del proyecto. `lineGlyph()` está escrita pero **no la llama nadie**: en el scrollback siguen saliendo los prefijos `>` y `!` como caracteres, así que `OK` y `FAIL` no se dibujan jamás y la sustitución de prefijos de la §4.4 no ha ocurrido. Y `Motion.CLEAR_MS` no lo consume nadie: `clear` vacía la pantalla de golpe en un frame, que es justo lo que la §4.6.5 quiere evitar porque no distingue «se borró» de «falló al cargar». Cablearlas toca el contrato de salida (el rol tendría que dejar de aportar el prefijo), así que no se hace de cualquier manera al final de una sesión. |
| 2026-07-27 | — | **La caída de `clear` dejó un crash de arranque.** `_falling` se declaró junto a `fallAndClear()`, al final de `TerminalState`, y el `init { sync() }` de más arriba la lee: Kotlin inicializa en orden de declaración, así que valía `null` durante la construcción y `MainActivity.onCreate` moría con un NPE **en el 100% de los arranques**, antes del primer fotograma. Ni los 297 tests ni `lint` lo vieron, porque no había un solo test sobre `ui/`. Reproducido en un emulador Android 36, arreglado moviendo la declaración por encima del `init`, y cubierto con `TerminalStateTest` (10 casos), que se verificó fallando contra el bug reintroducido. |
