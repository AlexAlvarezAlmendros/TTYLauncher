# tty

**Launcher Android en forma de terminal.** Sustituye la pantalla de inicio por un prompt: sin
iconos, sin cuadrícula, sin widgets, sin cajón de aplicaciones.

```
> open spotify
> apps whats
whatsapp        com.whatsapp
whatsapp-bsns   com.whatsapp.w4b

2 apps
> cd Download
> ls
Telegram/
factura-2026.pdf
notas.txt

3 entries
> focus obsidian
```

La diferencia con un launcher minimalista de lista es que aquí la interacción es un **lenguaje**,
no una selección. La diferencia con una terminal de verdad es que el vocabulario es cerrado y
auditable, salvo una única puerta explícita hacia Termux.

El producto se considera terminado cuando el autor lo usa como launcher por defecto durante dos
semanas sin volver al anterior.

---

## Estado

**Greenfield.** Hay documentación y el andamiaje de Gradle; **nada compilado todavía**. El trabajo
empieza por la Fase 0.

## Documentación

| Documento | Qué contiene |
|---|---|
| [docs/functional.md](docs/functional.md) | Qué hace el producto y cómo se comporta. La especificación |
| [docs/architecture.md](docs/architecture.md) | Cómo se construye. Decisiones de implementación verificadas |
| [docs/design/DESIGN-SYSTEM.md](docs/design/DESIGN-SYSTEM.md) | Los valores: color, tipografía, espaciado, movimiento y los ocho componentes |
| [docs/planning/ROADMAP.md](docs/planning/ROADMAP.md) | Las seis fases, su estado y las decisiones abiertas |
| [CLAUDE.md](CLAUDE.md) | Convenciones de desarrollo |

## Empezar

Requisitos: JDK 17 y el Android SDK (compileSdk 37, Build Tools 36.0.0).

El wrapper todavía no está generado —faltan `gradlew`, `gradlew.bat` y `gradle-wrapper.jar`—, así
que la primera vez hay que crearlo. Lo más simple es abrir el proyecto en Android Studio, que trae
su propia distribución de Gradle. A mano:

```bash
curl -L -o /tmp/gradle.zip https://services.gradle.org/distributions/gradle-9.6.1-bin.zip
unzip -q /tmp/gradle.zip -d /tmp
/tmp/gradle-9.6.1/bin/gradle wrapper --gradle-version 9.6.1

./gradlew assembleDebug
./gradlew installDebug
```

Después, en Ajustes de Android, elegir `tty` como aplicación de inicio.

> **Mantén instalado tu launcher anterior.** Ser el launcher por defecto convierte cualquier crash
> en un móvil inutilizable, y `tty` todavía no es estable.

## Licencia

GPL-3.0. Ver [LICENSE](LICENSE).
