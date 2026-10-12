# ice-chat-project

Chat multimedia con ZeroC Ice (mensajeria, salas, archivos, senalizacion) y voz sobre UDP.
Taller Evaluativo Unidad 2 - Computacion en Internet I (Icesi, 2026-2).

## Setup

### Requisitos

| Herramienta | Version | Notas |
|---|---|---|
| JDK | **17 a 24** (recomendado Temurin 21) | `JAVA_HOME` debe apuntar a este JDK. Gradle 8.14 no corre sobre JDK 25+ |
| ZeroC Ice | **3.7.11** | Provee `slice2java`. Debe coincidir con la version `ice` de `gradle/libs.versions.toml` |
| Gradle | 8.14.3 (via wrapper) | No hace falta instalarlo; usar `./gradlew` |

### Instalacion en Windows

1. JDK 21: `winget install EclipseAdoptium.Temurin.21.JDK` (configura `JAVA_HOME`; abrir una terminal nueva despues).
2. Ice 3.7.11: descargar e instalar [Ice-3.7.11.msi](https://download.zeroc.com/ice/3.7/Ice-3.7.11.msi).
   El plugin de Gradle encuentra `slice2java` via el registro de Windows; si no, definir `ICE_HOME=C:\Program Files\ZeroC\Ice-3.7.11`.

En Linux/macOS: instalar Ice 3.7.11 segun https://zeroc.com/downloads/ice/3.7 (o definir `ICE_HOME`).

### Compilar y ejecutar

```bash
./gradlew build        # compila los .ice -> common/build/generated-src y todos los modulos
./gradlew runServer
./gradlew runClient
```

> **Por que Gradle 8 y no 9:** el plugin oficial `com.zeroc.gradle.ice-builder.slice` (1.5.2) usa
> `groovy.util.XmlSlurper`, que ya no existe en Gradle 9 (Groovy 4). Por la misma razon el
> configuration cache esta desactivado en `gradle.properties`.
>
> **Consola:** `gradle.properties` fija `org.gradle.console=plain` para que la barra de progreso de
> Gradle no se mezcle con la salida de la CLI. Para ocultar tambien las lineas de tareas: `./gradlew runClient -q`.

### Configuracion y red local

La configuracion de Ice esta en `server/config.server` y `client/config.client` (endpoints, hilos,
`Ice.MessageSizeMax` y heartbeats para detectar clientes caidos). Cualquier propiedad se sobrescribe
con `--Nombre=valor`. Para conectar un cliente a un servidor en otra maquina:

```bash
./gradlew runClient --args="--Lobby.Proxy='Lobby:tcp -h 192.168.1.10 -p 10000'"
```

Solo el servidor abre un puerto (TCP 10000), asi que solo en su maquina el firewall de Windows debe
permitir Java en la red local. Los clientes reciben los callbacks por la misma conexion del login
(conexion bidireccional de Ice) y no abren puertos.

## Manual de usuario (CLI)

Iniciar el servidor una vez y un cliente por usuario, cada uno en su propia terminal:

```bash
./gradlew runServer
./gradlew runClient -q
```

**Acentos en Windows:** Gradle reenvia la entrada y la salida del cliente en UTF-8. Antes de
`./gradlew runClient`, poner la terminal en UTF-8 con `chcp 65001`; si no, Gradle pierde las letras
con tilde y la `ñ` (llegan como `?`). Ejecutando el cliente con `java` directamente no hace falta.

| Comando | Descripcion |
|---|---|
| `/login <nick>` | Inicia sesion. El nickname es unico (sin distinguir mayusculas): 1 a 20 letras, numeros, `.`, `_` o `-`, sin espacios |
| `/users` | Lista los usuarios conectados |
| `/msg <nick> <texto>` | Envia un mensaje privado. `[ok]` confirma que el servidor lo recibio; `[error]` explica por que no (usuario inexistente, desconectado, ...) |
| `/logout` | Cierra la sesion; el nickname queda libre |
| `/help` | Muestra los comandos |
| `/quit` | Cierra la sesion y sale (Ctrl+C hace lo mismo) |

Antes de `/login` solo funcionan `/login`, `/help` y `/quit`. Los eventos del servidor aparecen en cuanto
llegan, sin bloquear la escritura de comandos:

```
Chat Ice. Escribe /help para ver los comandos.
/login alice
Sesion iniciada como alice. Conectados (2): alice, bob
* carol se conecto
/msg bob hola bob
[ok] enviado a bob
[11:15] (privado) bob: hola alice
/msg nadie hola
[error] El usuario 'nadie' no existe o no esta conectado
* carol se desconecto
```

Si un cliente se cierra de golpe (proceso terminado o red caida), el servidor lo detecta por la conexion
cerrada o por la falta de heartbeats (unos 10 s) y avisa a los demas. Si se cae el servidor, el cliente
muestra `[error] Se perdio la conexion con el servidor` y permite volver a hacer `/login`. Si el servidor
cierra la sesion de un cliente que no respondio a tiempo, el cliente lo informa en el siguiente comando
(`El servidor cerro tu sesion`) y tambien permite volver a hacer `/login`.

## Estructura

```
common/   contrato Slice (src/main/slice/*.ice) -> clases Java generadas
server/   servidor Ice                       (./gradlew runServer)
client/   cliente CLI                        (./gradlew runClient)
  client.core  logica independiente de la UI (Ice, callbacks, UDP)
  client.cli   interfaz de consola
ai-use/   bitacora de uso de IA generativa
```

## Contrato Slice

Modulo `chat` (paquete Java `chat`), dividido en `common/src/main/slice/`:

| Archivo | Contenido |
|---|---|
| `Types.ice` | Structs y secuencias: `TextMessage`, `RoomInfo`, `FileInfo`, `FileChunk`, `AudioEndpoint`, `GroupCallMember`, `GroupCallInfo`; constantes `ChunkSize` (64 KB) y formato de audio (`AudioSampleRate`, `AudioPacketBytes`, ...) |
| `Exceptions.ice` | `ChatException` y sus subclases (`NicknameTakenException`, `InvalidNameException`, `UserNotFoundException`, `RoomNotFoundException`, `UserBusyException`, ...) |
| `Callbacks.ice` | Lo que implementa el cliente: `PresenceListener`, `MessageListener`, `RoomListener`, `FileListener`, `CallListener`, unidas en `ClientCallback` |
| `Server.ice` | Lo que implementa el servidor: `Lobby` (login) y `Session`, que hereda de `MessageOps`, `RoomOps`, `FileOps` y `CallOps` |

Flujo: el cliente obtiene el proxy `Lobby`, llama `login(nickname, callback)` y recibe un `Session*` propio;
todas las operaciones posteriores se hacen sobre esa sesion. Los archivos se envian con `startFileToUser/Room`
(metadatos + SHA-256, devuelve un `transferId`) seguido de `uploadChunk` por cada bloque de 64 KB.
