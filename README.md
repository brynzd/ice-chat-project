# ice-chat-project

Chat multimedia con ZeroC Ice (mensajeria, salas, archivos, senalizacion) y voz sobre UDP.
Taller Evaluativo Unidad 2 - Computacion en Internet I (Icesi, 2026-2).

## Setup

### Requisitos

| Herramienta | Version | Notas |
|---|---|---|
| JDK | **17 a 24** (recomendado Temurin 21) | `JAVA_HOME` debe apuntar a este JDK. Gradle 8.14 no corre sobre JDK 25+ |
| ZeroC Ice | **3.7.11** | Provee `slice2java`. Debe coincidir con `com.zeroc:ice:3.7.11` en `common/build.gradle` |
| Gradle | 8.14.3 (via wrapper) | No hace falta instalarlo; usar `./gradlew` |

### Instalacion en Windows

1. JDK 21: `winget install EclipseAdoptium.Temurin.21.JDK` (configura `JAVA_HOME`; abrir una terminal nueva despues).
2. Ice 3.7.11: descargar e instalar [Ice-3.7.11.msi](https://download.zeroc.com/ice/3.7/Ice-3.7.11.msi).
   El plugin de Gradle encuentra `slice2java` via el registro de Windows; si no, definir `ICE_HOME=C:\Program Files\ZeroC\Ice-3.7.11`.

En Linux/macOS: instalar Ice 3.7.11 segun https://zeroc.com/downloads/ice/3.7 (o definir `ICE_HOME`).

### Compilar y ejecutar

```bash
./gradlew build        # compila Chat.ice -> common/build/generated-src y todos los modulos
./gradlew runServer
./gradlew runClient
```

> **Por que Gradle 8 y no 9:** el plugin oficial `com.zeroc.gradle.ice-builder.slice` (1.5.2) usa
> `groovy.util.XmlSlurper`, que ya no existe en Gradle 9 (Groovy 4). Por la misma razon el
> configuration cache esta desactivado en `gradle.properties`.

## Estructura

```
common/   contrato Slice (src/main/slice/Chat.ice) -> clases Java generadas
server/   servidor Ice                       (./gradlew runServer)
client/   cliente CLI                        (./gradlew runClient)
  client.core  logica independiente de la UI (Ice, callbacks, UDP)
  client.cli   interfaz de consola
ai-use/   bitacora de uso de IA generativa
```
