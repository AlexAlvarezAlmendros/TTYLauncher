# tty

**Launcher Android en forma de terminal.** Sustituye la pantalla de inicio por un prompt: sin
iconos, sin cuadrícula, sin widgets, sin cajón de aplicaciones.

```
> open spotify
> apps whats
whatsapp          com.whatsapp
whatsapp-business com.whatsapp.w4b

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

---

## Estado

**Versión 1.0.0, firmada.** Las seis fases están implementadas: 49 ficheros de Kotlin, **318 tests
en JVM sin fallos**, `assembleRelease` y `lintVitalRelease` en verde, y el APK de release —
minificado con R8 — arranca y responde tanto en un móvil real como en el emulador. Qué trae, entero:
[release notes](docs/RELEASE-NOTES.md).

Lo que **no** está cerrado, y no es poco:

- **Las puertas de fase.** La regla del proyecto no es «está implementado», es *«lo he usado como
  launcher por defecto unos días y no he vuelto al anterior»*. Ninguna fase ha pasado esa puerta:
  esto es un 1.0.0 de alcance completo, no de kilometraje.
- **Termux nunca se ha ejecutado contra el de verdad.** `sh` y `tmux` están escritos y sus errores
  cubiertos con dobles, pero jamás han hablado con un Termux instalado.
- Detalles que solo se juzgan mirándolos: si el catálogo se refresca al instalar una app, si Gboard
  sigue sugiriendo pese a `KeyboardType.Ascii`, y cómo se siente el movimiento en un panel real.

El detalle por tarea está en el [roadmap](docs/planning/ROADMAP.md).

## El vocabulario, entero

Veintiséis verbos. **Lo que no está aquí no existe**: el vocabulario es cerrado por diseño, y
añadir uno es una decisión de producto, no de implementación.

| | |
|---|---|
| **Apps y sistema** | `help` `apps` `open` `kill` `uninstall` `info` `script` `sh` `tmux` `clear` `settings` |
| **Ficheros** | `pwd` `cd` `ls` `cat` `head` `tail` `mkdir` `rm` `mv` `cp` `touch` `df` `du` `find` `mount` |

`ls`, `cat` y `rm` son de **ficheros**: `apps` no tiene alias `ls`, y desinstalar es `uninstall`.

## Los glifos

No hay iconos. La única iconografía del producto es una matriz de puntos de 5×5 que ocupa dos
celdas de carácter y vive donde iría el prefijo de la línea. Seis estados, y cada uno informa de
algo que se puede nombrar:

```
 .....      ..x..      ..x..      x...x
 .x.x.      ..x..      ...x.      .x.x.
 .....      ..x..      xxxxx      ..x..
 .xxx.      ..x..      ...x.      .x.x.
 .....      ..x..      ..x..      x...x

 READY       BUSY        OK         FAIL
en reposo  ejecutando  con salida   error
```

La rejilla se dibuja **entera**: los apagados reposan al 18% y los encendidos al 100%, como un
píxel apagado sigue viéndose en una matriz real. Y como máximo hay **un glifo animado en pantalla**
—el del prompt—; los del historial van congelados en su fotograma.

**El icono de la aplicación ya no es uno de ellos.** Lo fue —era `READY`— hasta el 2026-07-30, y el
criterio no ha cambiado: lo primero que ves en el cajón tiene que ser lo primero que ves al abrirla.
Lo que cambió es la respuesta. Una matriz de 25 puntos se lee a 13sp en su renglón, pero a 24dp
entre otros cincuenta iconos vuelve a ser el problema de superficie de la §4.4. Así que el icono es
ahora **el prompt entero**: el chevron y el cursor de bloque sobre el degradado. Sigue sin haber
nada figurativo, siguen siendo dos caracteres de la retícula. El dibujo sale de
[`public/favicon.svg`](public/favicon.svg), del que se derivan también el favicon y el
apple-touch-icon.

## Documentación

| Documento | Qué contiene |
|---|---|
| [docs/functional.md](docs/functional.md) | Qué hace el producto y cómo se comporta. La especificación |
| [docs/architecture.md](docs/architecture.md) | Cómo se construye. Decisiones de implementación verificadas |
| [docs/design/DESIGN-SYSTEM.md](docs/design/DESIGN-SYSTEM.md) | Los valores: color, tipografía, espaciado, movimiento y los ocho componentes |
| [docs/planning/ROADMAP.md](docs/planning/ROADMAP.md) | Las seis fases, su estado y las decisiones abiertas |
| [docs/RELEASE-NOTES.md](docs/RELEASE-NOTES.md) | Qué trae cada versión, sus limitaciones conocidas y cómo se verificó |
| [CLAUDE.md](CLAUDE.md) | Convenciones de desarrollo |

Ante una contradicción mandan en este orden: el funcional, el design system, la arquitectura y por
último las convenciones.

## Compilar

Requisitos: **JDK 17** y el Android SDK con `platforms;android-37.0` y `build-tools;37.0.0`. El
wrapper de Gradle está versionado, así que no hace falta instalar Gradle.

```bash
./gradlew test            # 318 tests en JVM, sin emulador
./gradlew assembleDebug
./gradlew installDebug
```

Después, en Ajustes de Android, elegir `tty` como aplicación de inicio.

Las versiones viven **solo** en [`gradle/libs.versions.toml`](gradle/libs.versions.toml) y no se
escriben a mano en ningún `build.gradle.kts` —tampoco `versionName` ni `versionCode`—: AGP 9.3.1 ·
Gradle 9.6.1 · Kotlin 2.2.10 · Compose BOM 2026.06.01 · compileSdk 37 · targetSdk 36 · minSdk 26.

Sin Material3, sin DI, sin ORM: ficheros planos y `foundation` a secas.

### Release

```bash
./gradlew assembleRelease   # → app/build/outputs/apk/release/
```

R8 y `shrinkResources` van activados, así que **el APK de release no es el de debug con otro
nombre**: arráncalo en el emulador antes de darlo por bueno.

Sale **firmado** si existe `keystore.properties` en la raíz del repo, que es local de cada máquina
y está en `.gitignore` igual que `local.properties`:

```properties
storeFile=/ruta/absoluta/al/tty-release.jks
storePassword=…
keyAlias=tty
keyPassword=…
```

Si ese fichero no está, **el build no falla**: produce `app-release-unsigned.apk`. El modo de fallo
correcto es un APK que no instala, no un proyecto que no compila — `assembleDebug` no necesita nada
de esto. Firma v2 + v3; AGP omite el JAR signing v1 con `minSdk >= 24`.

El keystore vive **fuera del repo** a propósito. **Si se pierde, `dev.tty` no se puede volver a
actualizar jamás**: Android identifica una app por paquete + firma, y la única salida sería publicar
otro paquete y que todo el mundo desinstale y reinstale.

Para publicar una versión: subir `versionName` y **siempre** `versionCode` —un entero monótono,
independiente del nombre— en el catálogo, y añadir su sección a las
[release notes](docs/RELEASE-NOTES.md).

### En el emulador

Casi todo se puede probar sin móvil, y conviene hacerlo antes de instalar: **un crash de arranque
en la actividad HOME deja el teléfono sin pantalla de inicio.**

```bash
sdkmanager --install "emulator" "system-images;android-36;google_apis;x86_64"
avdmanager create avd -n ttytest -k "system-images;android-36;google_apis;x86_64" -d pixel_6

emulator -avd ttytest -no-window -gpu swiftshader_indirect &
adb wait-for-device && ./gradlew installDebug
adb shell am start -n dev.tty.debug/dev.tty.MainActivity
adb logcat -b crash -d          # el trace, si murió
```

Cubre que arranque, que los comandos respondan y que la pantalla se pinte. **No cubre** Termux, la
frescura del catálogo ni cómo se siente el movimiento.

> Si cambias el icono y sigues viendo el anterior, no está roto: la caché de iconos de Android tiene
> como clave paquete+versión y sobrevive incluso a desinstalar. Se suelta al reiniciar.

## Usarlo

> **Mantén instalado tu launcher anterior.** Ser el launcher por defecto convierte cualquier crash
> en un móvil inutilizable, y `tty` no ha pasado todavía ninguna puerta de fase.

`sh` y `tmux` necesitan tres cosas, y ninguna la puede abrir el launcher por su cuenta: Termux
instalado **de F-Droid o GitHub** (el de Google Play está abandonado y firmado con otra clave), el
permiso `RUN_COMMAND` concedido, y `allow-external-apps = true` en `~/.termux/termux.properties`.
No hay asistente de configuración: **el mensaje de error es el onboarding**, y dice cuál de las tres
puertas está cerrada.

## Licencia

GPL-3.0. Ver [LICENSE](LICENSE).
