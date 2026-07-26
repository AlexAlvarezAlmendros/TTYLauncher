# Plan 06 — Pulido

> Fase: 6 de 6 | Estado: 🔄 En curso | Iniciado: 2026-07-26 | Cerrado: —
> Hito del roadmap: historial de entradas, banner real y sugerencia por distancia de edición.

Lo que queda cuando el producto ya se usa a diario. Nada de esta fase es imprescindible para vivir
con el launcher; toda ella sale de fricciones observadas en uso real, no de una lista de deseos.

Regla de la fase: **si una tarea no responde a una fricción que se ha sentido de verdad, se
descarta**. Es la fase donde es más fácil traicionar la §1.2.

---

## Dependencia con otras fases

- **Requiere:** Fase 5 (el historial de entradas necesita un glifo atenuado como control).
- **Habilita:** nada. Es el final del plan escrito.

---

## Tareas

### Historial de entradas

| # | Tarea | Estado | Notas |
|---|-------|--------|-------|
| 6.1 | Recordar las últimas ~50 entradas escritas | ✅ Hecho | §5.4. Distinto del scrollback: son solo las entradas |
| 6.2 | Un único control táctil (glifo atenuado a la derecha del campo) que recorre de más reciente a más antiguo y vuelve a empezar | ✅ Hecho | §5.4. Uno, no dos: no hay "atrás y adelante" |
| 6.3 | Decidir si el historial de entradas persiste entre arranques y documentar la consecuencia de privacidad | 🔄 En curso | El scrollback ya persiste (§5.5); esto es una decisión aparte, no un automatismo |

### Banner

| # | Tarea | Estado | Notas |
|---|-------|--------|-------|
| 6.4 | Banner con los números reales del dispositivo (modelo, versión de Android, apps instaladas…) | ✅ Hecho | §11. Etiqueta en mayúsculas con tracking amplio, en `decode` |
| 6.5 | La segunda línea sigue diciendo `TYPE HELP` y nada más | ✅ Hecho | §11. Ni tutorial, ni tour, ni tarjetas |

### Sugerencia y autocompletado

| # | Tarea | Estado | Notas |
|---|-------|--------|-------|
| 6.6 | Extraer la sugerencia por distancia de edición que ya usan los errores a un servicio reutilizable | ✅ Hecho | Viene de la tarea 1.12 |
| 6.7 | **Decisión abierta:** resolver el gesto de autocompletado en teclado virtual antes de implementarlo | 🔒 Bloqueado | §15. No hay tecla de tabulador natural. Si no hay gesto convincente, la tarea se cancela |
| 6.8 | Autocompletado sobre comandos, scripts y handles de apps, si y solo si 5.7 se cierra | 🔒 Bloqueado | Depende de 5.7 |

### Cierre del producto

| # | Tarea | Estado | Notas |
|---|-------|--------|-------|
| 6.9 | Repaso de arranque: el criterio 10 se mide con 2000 líneas reales en disco, no con el scrollback vacío | 🔄 En curso | Criterio 10 |
| 6.10 | Revisar `help` una última vez: sigue cabiendo en una pantalla con todos los comandos | 🔄 En curso | Criterio 4 |
| 6.11 | Repaso de las decisiones abiertas de la §15 con dos semanas de uso: cerrar el orden del historial | 🔄 En curso | Invertido vs clásico. Es un cambio de una sola propiedad de layout |
| 6.12 | Documentar los límites de fabricante observados en el dispositivo real (gestos, restricciones de launcher) | 🔄 En curso | §12 y §16. Se documenta, no se persigue como bug propio |

---

## Entregable

El producto terminado según la definición de la §0: **el autor lo usa como launcher por defecto
durante dos semanas sin volver al anterior**.

## Criterio de aceptación

Los catorce criterios de [functional.md §13](../../functional.md#13-criterios-de-aceptación) se
cumplen a la vez, en el dispositivo real, con el scrollback lleno.

---

## Registro de avance

| Fecha | Tarea | Notas |
|-------|-------|-------|
| 2026-07-26 | — | Plan creado. Bloqueado por la Fase 5. |
| 2026-07-26 | 6.1 · 6.2 · 6.4-6.6 | **El pulido.** `core/InputHistory`: las últimas 50 entradas, **volátil a propósito** —el scrollback ya es un registro en disco de lo que haces con el móvil, y duplicarlo traería la misma consecuencia de privacidad por mucha menos comodidad—, sin duplicar repeticiones consecutivas y dando la vuelta por el prompt vacío. El control es **uno solo**, un glifo atenuado a la derecha del campo, y su respuesta a la pulsación es un cambio de opacidad y nada más: ni color, ni escala, ni ripple. No es uno de los seis glifos de estado —no informa de nada, es una afordancia— y por eso está exento de la regla de «un solo glifo animado»: no se anima nunca. El banner ya llevaba los números reales desde la Fase 0. 9 tests nuevos: **235 en total, 0 fallos**. |
| 2026-07-26 | 6.7 · 6.8 | **Autocompletado: no se implementa.** La 6.7 dice literalmente que hay que resolver el gesto en teclado virtual **antes**, y no hay gesto convincente: no existe una tecla de tabulador natural, y el único control táctil del producto ya está ocupado por el historial. La maquinaria de sugerencia por distancia de edición está lista desde la Fase 1 (`core/text/Suggest`), así que el día que aparezca el gesto es cablearlo. Queda como decisión abierta, que es lo que la §15 pedía. |
