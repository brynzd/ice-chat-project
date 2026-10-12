# AI-use log: M2 + M3, session, presence and private chat

- **Date:** 2026-10-12
- **Tool:** Claude Code (Claude Opus 5.5), desktop app
- **Branch:** `feature/session-presence` (off `develop`)
- **Role:** for time reasons the team asked the AI to **write the code** for this milestone. The team revises it afterwards (see "Revisions"). The AI never commits: it stopped at three checkpoints (server, client, docs) with a commit message, and a team member made each commit.

## Main prompts

1. *"Let's start planning M2."*
2. Answers to the AI's planning questions:
   - Authorship: **"Claude writes, team revises"**.
   - Scope: **"M2 + M3 together"** (session, presence, logout, dead-client detection and private messages, plus a minimal CLI).
   - Branch: *"PR has been made, go and branch off of develop."*
3. Process change: *"Between server and client classes, stop and we commit. We want to have several checkpoints."*
4. *"Committed, continue with the client"*, then *"done"* (docs checkpoint).
5. *"apply the fixes from section 1-3"*

## Design decisions

| Decision | Choice | Why |
|---|---|---|
| Scope | M2 and M3 in one milestone | Rubric criterion 2 (0.8) grades session, presence and 1-to-1 chat together, and they are only demoable together |
| Unique nickname | `ConcurrentHashMap.putIfAbsent` on the lowercase nickname | Atomic: two clients racing for the same name can't both get it. `Bob` and `bob` count as the same user |
| Nickname rules | 1–20 chars from `[A-Za-z0-9_.-]`, no spaces | The CLI splits `/msg <nick> <text>` on the first space |
| Session cleanup | One `end()` that runs once (`AtomicBoolean`) and is triggered by `logout`, by the login connection closing (ACM heartbeats), or by a failed callback | Every way a client can leave goes through the same path; `remove(key, session)` never removes a newer session that reused the nickname |
| Callbacks to clients | Always `*Async`, never while holding a lock, with a 5 s invocation timeout; a failure ends that client's session | A slow or dead client can't block the server or the other clients |
| Ack for private messages | Returning normally from `sendPrivate` = the server received and dispatched it; errors are `UserNotFoundException` with a readable reason | What RF-02 asks for, without waiting for the recipient |
| Not-yet-implemented operations | `OperationNotExistException` | The client gets a clear error instead of a hang |
| Session proxy on the client | `ice_fixed` to the login connection | Otherwise the client may open a second connection to the server's published IPs (problem on lab machines with VirtualBox/WSL adapters), and the server would watch the wrong connection for dead clients |
| UI events | `client.core` reports to a `ChatListener`; the CLI implements it and prints through one synchronized method | Keeps the Ice logic separate from the UI (JavaFX could replace the CLI), and input reading is decoupled from async printing |
| Callback transport (revision, prompt 5) | Bidirectional connection: the server calls back over the client's login connection (`ice_fixed(current.con)`); the client adapter has no endpoints | Clients open no ports, so lab firewalls and VirtualBox/WSL IPs can't break callbacks; one connection to watch for dead clients |
| Event delivery on the client (revision, prompt 5) | Ice callbacks only queue the event on one `chat-events` thread in `client.core` | A slow UI can't stall Ice or make the server drop a live client; events keep their order |
| Console encoding (revision, prompt 5) | With a console, its own charset; without one (`gradlew`, pipes), UTF-8 | Measured: Gradle forwards client I/O as UTF-8 and loses non-ASCII on a cp850 console, so Windows users run `chcp 65001` before `gradlew runClient` |

## AI-generated code

Fixes for the findings in "Revisions", sections 1–3, applied by the AI (prompt 5). Locations point to the fixed code.

| Finding | Location | Change applied |
|---|---|---|
| 1a. Callbacks need a new connection to each client | [`config.client:8-12`](../client/config.client#L8-L12); [`Client.java:22-24`](../client/src/main/java/client/Client.java#L22-L24); [`ChatClient.java:51-69`](../client/src/main/java/client/core/ChatClient.java#L51-L69) `login`; [`IceSupport.java:33-40`](../server/src/main/java/server/IceSupport.java#L33-L40) `callbackOn`; [`SessionManager.java:42`](../server/src/main/java/server/SessionManager.java#L42); [`README.md:49-51`](../README.md#L49-L51) | Bidirectional connection: the client creates its callback adapter without endpoints and attaches it to the login connection (`setAdapter`); the server pins the callback proxy to that connection (`ice_fixed(current.con)`, replaces `withTimeout`). `Callback.Endpoints` removed; the client thread pool (1 thread) now dispatches callbacks. README: only the server needs a firewall rule |
| 1b. Client never learns its session was dropped | [`ChatClient.java:96-122`](../client/src/main/java/client/core/ChatClient.java#L96-L122) `listUsers` / `sendPrivate`, [`:132-149`](../client/src/main/java/client/core/ChatClient.java#L132-L149) `sessionLost` / `dropSession`; [`Cli.java:185-187`](../client/src/main/java/client/cli/Cli.java#L185-L187); [`README.md:93-95`](../README.md#L93-L95) | An `ObjectNotExistException` from a session call clears the session and fails the call with "El servidor cerro tu sesion. Usa /login <nick> para volver a entrar"; the CLI prints it and `/login` works again. The close callback and this path share `dropSession` |
| 1c. Callback servant blocks the only dispatch thread | [`ChatClient.java:31-37`](../client/src/main/java/client/core/ChatClient.java#L31-L37) `events`; [`CallbackI.java:19-42`](../client/src/main/java/client/core/CallbackI.java#L19-L42); [`ChatListener.java:7-8`](../client/src/main/java/client/core/ChatListener.java#L7-L8) | Callbacks only queue the event on a single daemon thread (`chat-events`) and return; `sendPrivate` results and the disconnect event also complete there. `ChatListener` documents that the UI may block |
| 1d. Ghost user / "left" before "joined" at login | [`SessionManager.java:49-66`](../server/src/main/java/server/SessionManager.java#L49-L66) `login` | If `adapter.add` fails the nickname is released (`users.remove(key, user)`); the close callback is registered after the "joined" notification |
| 2. Ack wording | [`Cli.java:127`](../client/src/main/java/client/cli/Cli.java#L127); [`README.md:84`](../README.md#L84) | "[ok] enviado a ..." |
| 2. Control characters in messages | [`Cli.java:39-40`](../client/src/main/java/client/cli/Cli.java#L39-L40) `CONTROL`, [`:157-158`](../client/src/main/java/client/cli/Cli.java#L157-L158), [`:170-176`](../client/src/main/java/client/cli/Cli.java#L170-L176) `clean` | C0, DEL and C1 control characters (ESC included) in the sender's name and text are replaced with a space |
| 2. Dead code | [`UserSession.java:44-47`](../server/src/main/java/server/UserSession.java#L44-L47) | `isClosed()` removed |
| 3. Accents | [`Cli.java:29-34`](../client/src/main/java/client/cli/Cli.java#L29-L34) `CHARSET` / `OUT`, [`:56`](../client/src/main/java/client/cli/Cli.java#L56); [`README.md:62-64`](../README.md#L62-L64) | Broken before (stdin read as UTF-8, console sends cp850). Now: the console's charset when there is a console, UTF-8 otherwise, for both input and output. README: `chcp 65001` before `gradlew runClient` on Windows |
| 3. Ctrl+C under Gradle | [`README.md:73`](../README.md#L73) | Verified, no change: a real Ctrl+C on `gradlew runClient` ended the session with a normal logout on the server |
| 3. Two machines on the LAN | — | Still to test on the lab LAN. Locally: clients now have no listening ports, only the server does |

Checked:
- `./gradlew clean build` passes.
- End-to-end run with the server and CLI clients on `127.0.0.1`:
  - presence;
  - `/msg` with mixed-case names, `[ok]`, unknown user, to yourself;
  - an ESC in a message printed as a space;
  - `/logout` then `/login` again;
  - no client process listening on any port.
- jshell probe using `ChatClient`:
  - after the server ended the session behind its back, `listUsers` and `sendPrivate` reported "El servidor cerro tu sesion" and `/login` worked again;
  - a listener that takes 8 s per event was not dropped by the server, and events stayed in order.
- Terminal panel (real console):
  - code page 850: plain `java` gets the console's bytes, while `gradlew` already replaces accents with U+FFFD;
  - with `chcp 65001` both get UTF-8;
  - "ñandú ¿qué?" and "¡hola, señora!" went both ways between a `gradlew runClient` client and a piped one;
  - a real Ctrl+C on the `gradlew` client showed `ana desconectado (logout)` on the server.

## How it was verified

- **Server checkpoint:** a throwaway Ice probe client (not in the repo) made real calls against `runServer`. All 17 checks passed:
  - duplicate nickname with different capitals rejected, invalid name rejected;
  - presence events, with no notification about yourself;
  - `listUsers`;
  - private message delivered to the recipient only, errors for an unknown user and for yourself;
  - not-yet-implemented operations rejected;
  - logout, then the old session proxy no longer works and the nickname can log in again;
  - a closed connection removes the user.
- **Client checkpoint:** a scripted end-to-end run with the server and five CLI processes:
  - duplicate and invalid nicknames rejected;
  - presence events;
  - private messages with `[ok]` and `[error]`;
  - logout, then logging in again;
  - a client process killed by force, which the others saw leave immediately;
  - the server killed, which the client reported instead of hanging;
  - commands piped through `gradlew runClient -q`.
- **Not verified yet:** Ctrl+C logout, typing while a message arrives (the incoming line prints in the middle of a half-typed command), and two physical machines on the lab LAN.

## Revisions

Overall: the code meets RF-01/02 and the build passes.
- Nicknames: unique with `putIfAbsent`, case-insensitive, validated.
- Cleanup: one `end()` path for logout, connection loss and failed callbacks.
- Callbacks: always async, outside locks, with a timeout.
- Client: logic split from the UI.

What I'd change, most important first:

### 1. Bugs and demo risks (fix before the demo)

**a. Callbacks depend on the server opening a new connection to each client.**
- The client publishes `Callback.Endpoints=tcp` ([`config.client:11`](../client/config.client#L11)), and the server calls back through that proxy ([`SessionManager.java:42`](../server/src/main/java/server/SessionManager.java#L42)).
- On lab machines this breaks in two ways:
  - Windows Firewall blocks inbound connections to the client unless someone allows Java. Lab accounts often can't.
  - The client advertises every local IP, including VirtualBox and WSL ones. Connecting to a wrong one can take longer than the 5 s callback timeout.
- Either way, the first callback fails and the server ends that user's session with "no responde". It works on one PC and fails across machines.
- **Fix:** bidirectional connections, with no contract change.
  - Client: create the callback adapter without endpoints and call `lobby.ice_getConnection().setAdapter(adapter)` before `login`.
  - Server: use `callback.ice_fixed(current.con)`.
  - Result: one connection carries everything. Clients open no ports, so only the server needs a firewall rule. Dead-client detection watches exactly that connection.
- **Update:**
  - `config.client`: remove `Callback.Endpoints`.
  - [`Client.java:23-24`](../client/src/main/java/client/Client.java#L23-L24).
  - [`ChatClient.java:38-41`](../client/src/main/java/client/core/ChatClient.java#L38-L41).
  - [`SessionManager.java:42-43`](../server/src/main/java/server/SessionManager.java#L42-L43).
  - README firewall note ([`README.md:49-50`](../README.md#L49-L50)).
  - Good material for Q4 (client adapters, direct vs. bidirectional).

**b. When the server drops a session, the client never finds out.**
- A failed callback calls `end()` ([`SessionManager.java:106-108`](../server/src/main/java/server/SessionManager.java#L106-L108)). That removes the session but leaves the connection open, so the client's close callback never fires.
- The client still thinks it is logged in:
  - `/users` prints "Error de comunicacion: ObjectNotExistException".
  - `/login` refuses with "usa /logout primero".
- **Fix:** in `ChatClient`, treat `ObjectNotExistException` from a session call as "session lost". Clear the state and call `listener.disconnected("El servidor cerro tu sesion")`.
  - This covers sync calls (`listUsers`) and async ones (`sendPrivate` futures).
  - `Cli.describe` can then drop that case.

**c. The callback servant blocks Ice's only dispatch thread while printing.**
- `CallbackI` calls the listener directly, and `Cli.print` is a synchronized `System.out.println`.
- `config.client` sets dispatch to 1 thread, serialized. Any stall in console output (a paused console, a full pipe) blocks every callback.
- After 5 s the server times out and ends the session of a client that is actually alive (see 1b).
- **Fix:** `CallbackI` hands each event to a single-thread executor in `client.core` and returns at once.
  - Order is kept, and no UI can stall Ice.
  - `ChatListener`'s "must not block" note ([`ChatListener.java:7`](../client/src/main/java/client/core/ChatListener.java#L7)) becomes something the core guarantees instead of a rule each UI must follow.

**d. Login can leave a ghost user or send "left" before "joined".**
- In `SessionManager.login` ([`:42-56`](../server/src/main/java/server/SessionManager.java#L42-L56)), if `adapter.add` throws (e.g. during shutdown), the user stays in `users` forever.
- The close callback is registered *before* the "joined" notification. A connection that drops during login can therefore make the others see "X se desconecto" and then "X se conecto".
- **Fix:**
  - Wrap `adapter.add` and remove the map entry if it fails.
  - Notify "joined" first, then register the close callback. If the connection already closed, the callback runs right away, after the join.

### 2. Smaller fixes

- **Ack wording:** "[ok] entregado a bob" ([`Cli.java:114`](../client/src/main/java/client/cli/Cli.java#L114), [`README.md:79`](../README.md#L79) example) claims delivery to Bob. RF-02 only asks for confirmation that the server received the message, which is what the code does. Change it to "[ok] enviado a bob" and fix the README.
- **Control characters in messages:** message text is printed raw ([`Cli.java:143-145`](../client/src/main/java/client/cli/Cli.java#L143-L145)). A sender can include line breaks or terminal escape codes and forge lines like "[ok] ..." or "* x se desconecto". Replace control characters with a space before printing.
- **Dead code:** `UserSession.isClosed()` ([`UserSession.java:49-51`](../server/src/main/java/server/UserSession.java#L49-L51)) is never used. Remove it.

### 3. Not verified yet (test, fix if broken)

- **Accents:** the CLI reads stdin with `Charset.defaultCharset()` ([`Cli.java:43`](../client/src/main/java/client/cli/Cli.java#L43)). On JDK 18+ that is UTF-8, while the Windows console uses cp850/cp1252. "ñ", "á" and "¿" will probably arrive mangled.
  - Test with `gradlew runClient` and with plain `java`.
  - If broken, read stdin and print with the console charset (`System.console().charset()` when there is a console, otherwise the `stdout.encoding` property).
- **Ctrl+C:** under `gradlew` on Windows, Gradle probably terminates the client process without running shutdown hooks, so the logout in [`Client.java:30-34`](../client/src/main/java/client/Client.java#L30-L34) doesn't run. The server still sees the connection close, so other users see the same result.
  - Check it.
  - If confirmed, reword [README line 68](../README.md#L68) ("Ctrl+C hace lo mismo") to say what really happens.
- **Two machines on the lab LAN:** after 1a, test with only the server allowed in the firewall.

## Lessons

- A proxy returned by the server carries the server adapter's *published* endpoints, not the address the client used to reach it. Pinning it with `ice_fixed(connection)` keeps every call on the connection the server is watching.
- Ice runs a connection's close callback even when the connection was already closed, so registering it after `adapter.add` can't miss a client that drops during login.
- Async replies (`sendPrivateAsync`) can complete in a different order than the commands were typed, so `[ok]` / `[error]` lines may appear out of order after several quick `/msg` commands.
- With a bidirectional connection the callbacks arrive on the client's *outgoing* connection, so Ice dispatches them in the client thread pool (`Ice.ThreadPool.Client.*`), not the server one.
- When the server removes a session but the connection stays open, the client gets no close callback; the only signal is `ObjectNotExistException` on its next call.
- Gradle sits between the console and `runClient`: it forwards the program's I/O as UTF-8 and replaces non-ASCII typed on a cp850 console with U+FFFD before the program sees it. No code in the client can recover that, hence `chcp 65001`.
