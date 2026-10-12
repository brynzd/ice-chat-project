# AI-use log: M1, Slice contract

- **Date:** 2026-10-12
- **Tool:** Claude Code (Claude Opus 5.5), desktop app
- **Branch:** `feature/slice-contract` (off `develop`)
- **Role change:** in `Plan_Inicial.md` the AI only wrote the build skeleton. For this milestone, because of time constraints, the team asked the AI to **generate the Slice contract** from the design decisions below. The team revises it afterwards (see "Revisions").

## Main prompts

1. *"Can you git pull the branch and see what's already done according to the project guidelines and we can start planning the next part of the project."*
2. Answers to the AI's design questions: *"The contract can be split into separate modules. The caller should return a per user session object. [...] Exceptions proposed are fine. Let's branch off of the develop."*
3. After the AI explained the file-transfer options: *"Option A, metadata sent once with FileInfo. Integrity check sounds fine with the hash. Chunk size is also fine as 64KB. Private vs room logic is sound."*
4. *"Can you check the current code and revise it thouroughly to find any room for improvement"*
5. *"apply the contract fixes from section 1"*
6. *"apply the build and docs fixes from section 2"*

## Design decisions made by the team

| Decision | Choice | Alternatives considered |
|---|---|---|
| Interface split | Small interfaces (`MessageOps`, `RoomOps`, `FileOps`, `CallOps`) combined through `Session extends ...` | One big interface; `Session` returning separate per-service proxies |
| Caller identity | `Lobby.login` returns a per-user `Session*`, so no operation takes a nickname | Passing the nickname in every call (easy to spoof, harder to clean up) |
| Source layout | 4 files: `Types`, `Exceptions`, `Callbacks`, `Server` | A single `Chat.ice` |
| File transfer | Server relays chunks as they arrive (push). `FileInfo` metadata is sent once and returns a `transferId`. 64 KB chunks. SHA-256 hash of the whole file | Server stores the file and recipients download it (pull); full metadata in every chunk |
| Exceptions | Base `ChatException` plus specific subclasses | One generic exception |
| Call signaling | `AudioEndpoint` struct with request / accept / reject / hang up. For group calls, the server relay returns a `token` to put in each datagram | Identifying senders by UDP source address |

## AI-generated code

Fixes for the contract gaps from "Revisions", section 1, applied by the AI (prompt 5). Locations point to the fixed code. Checked: `./gradlew clean build` passes and the new types and exceptions show up in the generated Java.

| Gap | Location | Change applied |
|---|---|---|
| a. Undeclared error cases | [`Exceptions.ice:13`](../common/src/main/slice/Exceptions.ice#L13) `InvalidNameException`; [`Server.ice:26`](../common/src/main/slice/Server.ice#L26) `createRoom`; [`Server.ice:41-44`](../common/src/main/slice/Server.ice#L41-L44) `startFileToUser` / `startFileToRoom`; [`Server.ice:76-77`](../common/src/main/slice/Server.ice#L76-L77) `login` | `InvalidNicknameException` renamed to `InvalidNameException` (nicknames, rooms and file names) and declared on `login`, `createRoom` and `startFile*` |
| b. No way to abort a transfer | [`Callbacks.ice:33-38`](../common/src/main/slice/Callbacks.ice#L33-L38) `onFileStart` / `onFileAborted`; [`Server.ice:38-46`](../common/src/main/slice/Server.ice#L38-L46) `cancelTransfer`; [`Types.ice:43-46`](../common/src/main/slice/Types.ice#L43-L46) `fileName` / `totalChunks` | Added `onFileAborted(transferId, reason)` and `cancelTransfer(transferId)`; documented that `totalChunks = 0` means an empty file that is complete on `onFileStart`, and that receivers drop any path from `fileName` |
| c. No participant list on joining a group call | [`Types.ice:66-82`](../common/src/main/slice/Types.ice#L66-L82) `GroupCallMember` / `GroupCallInfo`; [`Callbacks.ice:50-51`](../common/src/main/slice/Callbacks.ice#L50-L51) `onGroupCallMemberJoined` | New `GroupCallMember { nickname, token }` struct; `GroupCallInfo.participants` lists the members already in the call; `onGroupCallMemberJoined` now also sends the `token`, so clients can map relayed datagrams to a nickname |
| d. Audio format not in the contract | [`Types.ice:10-18`](../common/src/main/slice/Types.ice#L10-L18) | Constants `AudioSampleRate` (16000), `AudioSampleSizeBits` (16), `AudioChannels` (1), `AudioBigEndian` (false), `AudioPacketBytes` (640 = 20 ms) |
| e. Catch-all exceptions | [`Exceptions.ice:30-31`](../common/src/main/slice/Exceptions.ice#L30-L31) `UserBusyException`; [`Server.ice:53-54`](../common/src/main/slice/Server.ice#L53-L54) `requestCall` | `UserBusyException extends CallException`, declared on `requestCall` |
| f. Smaller points | [`Types.ice:8`](../common/src/main/slice/Types.ice#L8) `ChunkSize`; [`Types.ice:32-37`](../common/src/main/slice/Types.ice#L32-L37) `RoomInfo`; [`Server.ice:25-29`](../common/src/main/slice/Server.ice#L25-L29) `createRoom` / `joinRoom`; [`Server.ice:57-58`](../common/src/main/slice/Server.ice#L57-L58) `hangup`; [`Callbacks.ice:9`](../common/src/main/slice/Callbacks.ice#L9) presence; [`Callbacks.ice:47-48`](../common/src/main/slice/Callbacks.ice#L47-L48) `onCallEnded` | `RoomInfo.owner` replaced by `bool hasActiveCall`; `CHUNKSIZE` renamed to `ChunkSize` (not `CHUNK_SIZE`: Slice rejects underscores in identifiers); behavior rules written as comments: the creator joins the room automatically, joining a room twice does nothing, users are not notified about themselves, and `hangup` also cancels a call that is still ringing |

Also updated: the "Contrato Slice" table in `README.md` (new constant and exception names).

Build and docs fixes from "Revisions", section 2, applied by the AI (prompt 6). Checked: `./gradlew clean build` passes.

| Item | Location | Change applied |
|---|---|---|
| Stale `Chat.ice` references | [`README.md:27`](../README.md#L27), [`README.md:42`](../README.md#L42) | Now refer to the `.ice` files in `src/main/slice/` |
| Include comment | [`common/build.gradle:13`](../common/build.gradle#L13) | Matches the `#include <Types.ice>` form the files use |
| Duplicate PDF | `Taller_Evaluativo_Chat_Ice.pdf` (root) | Deleted; the identical copy stays in `docs/` |
| Empty version catalog | [`gradle/libs.versions.toml`](../gradle/libs.versions.toml); [`common/build.gradle:4`](../common/build.gradle#L4), [`:20`](../common/build.gradle#L20); [`README.md:13`](../README.md#L13) | The Ice runtime (3.7.11) and ice-builder plugin (1.5.2) versions now live in the catalog; `common` uses `alias(libs.plugins.ice.builder)` and `api libs.ice`; the README points to the catalog |
| Stale Gradle docs link | [`settings.gradle:5`](../settings.gradle#L5) | Links to the 8.14.3 docs |
| Progress bar mixed into the CLI | [`gradle.properties`](../gradle.properties); [`README.md:36-37`](../README.md#L36-L37) | `org.gradle.console=plain`; README explains it and the `-q` option |
| Two clients at once | — | Tested: two `./gradlew runClient -q` started from the same folder ran at the same time without blocking each other |

## Revisions

I reviewed the four `.ice` files against the assignment PDF, along with the Gradle setup and the docs. `./gradlew clean build` passes and the generated Java looks right. The contract is in good shape: the session-per-user design, the split interfaces and the exception hierarchy all fit the rubric well. Below is what I'd change, most important first. I haven't edited anything.

### 1. Contract gaps worth fixing now (cheap now, a breaking change later)

**a. Some errors have no declared exception.**
- [Server.ice:25](../common/src/main/slice/Server.ice#L25): `createRoom` only declares `RoomAlreadyExistsException`. An empty or invalid room name has no exception to throw.
- In Ice, if the server throws a user exception the operation didn't declare, the client gets a generic `UnknownUserException` instead of your error.
- **Fix:** rename `InvalidNicknameException` to something general like `InvalidNameException` and declare it on `createRoom`, and on `startFile*` for bad file names.

**b. File transfers can't end early.**
- [Callbacks.ice:31-35](../common/src/main/slice/Callbacks.ice#L31-L35) has `onFileStart` and `onFileChunk` but nothing for "stop".
- If the sender disconnects partway through, receivers keep a half-written file and never find out.
- **Fix:** add `onFileAborted(string transferId, string reason)`. Optionally add a `cancelTransfer(transferId)` the sender can call.
- A 0-byte file (`totalChunks = 0`) needs a rule too: the receiver should treat it as complete as soon as `onFileStart` arrives.

**c. A user joining a group call can't see who's already in it.**
- [Types.ice:58](../common/src/main/slice/Types.ice#L58): `GroupCallInfo` only has `relay` and `token`.
- **Fix:** add `StringSeq participants`.
- If the relay forwards audio with the sender's token in front, clients also need a token → nickname map, either in that struct or in `onGroupCallMemberJoined`.

**d. The audio format isn't in the contract.**
- Caller, callee and relay must all use the same `AudioFormat` (sample rate, bits, channels, endianness) and packet size.
- **Fix:** put them in `Types.ice` as constants (e.g. `AUDIO_SAMPLE_RATE = 16000`) so both sides read the same values instead of hard-coding them separately.

**e. Two error types are catch-alls.** The rubric marks down "tipos genéricos o escaso manejo de excepciones". `CallException` and `TransferException` each cover several cases. "User is busy" is the one users will actually see, so I'd add at least `UserBusyException extends CallException`.

**f. Smaller points:**
- `RoomInfo.owner` isn't used by any operation. Either drop it or keep it for display only. `bool hasActiveCall` would be more useful in that struct.
- `CHUNKSIZE` would read better as `CHUNK_SIZE`; in Java it shows up as `CHUNKSIZE.value`.
- Write down a few rules as comments: does `createRoom` also join the creator? Is joining a room you're already in a no-op? Does a user get their own `onUserJoined`? Does `hangup` also cancel a call that's still ringing?

### 2. Build and docs

- **The README still describes the old contract.**
  - [README.md:28](../README.md#L28) says the build "compila Chat.ice", and the Estructura section ([README.md:39](../README.md#L39)) points to `src/main/slice/Chat.ice`.
  - That file was deleted, so both lines are wrong now.
- **A comment doesn't match the code.** The comment at [common/build.gradle:13](../common/build.gradle#L13) mentions `#include "Types.ice"`, but the `.ice` files use `<Types.ice>`. Both work; just make the comment match.
- **The course PDF is committed twice**, in the repo root and in `docs/`, and the two files are identical. I'd delete the root copy.
- **Leftover files:**
  - [gradle/libs.versions.toml](../gradle/libs.versions.toml) is empty. Either delete it or move the Ice version `3.7.11` into it so the version lives in one place.
  - [settings.gradle:5](../settings.gradle#L5) still links to the Gradle 9.7.1 docs.
- **Gradle's progress bar will mess up the CLI.** It gets drawn into the interactive client's output. Add `org.gradle.console=plain` to `gradle.properties`, or document `./gradlew runClient -q --console=plain`.
- **Untested:** starting two `runClient` at once from the same project folder. The demo needs this, so try it before M2.

### 3. Points for M2 (server side, no contract change needed)

- **Dead-client detection (RF-01):** nothing in the contract covers it. Two options:
  - Ice's built-in connection heartbeats (`Ice.ACM.*`), plus a close callback on the connection the user logged in on.
  - A `Session.ping()` operation the client calls periodically.
  - Pick one now; the rubric checks this.
- **Deadlock risk:** the server calls a client callback and waits, the callback calls the server and waits, and with 1 server thread there's no thread left to answer. Avoid it with:
  - Asynchronous callback calls (`onPrivateMessageAsync`).
  - `Ice.ThreadPool.Server.SizeMax > 1`.
  - Never calling callbacks while holding a lock.
- **Message order:** with more than one dispatch thread on the client, chat messages can show up out of order. `Ice.ThreadPool.Server.Serialize=1` on the client fixes that.
- **Hung clients:** give callback proxies a timeout (`ice_invocationTimeout`) so one frozen client can't block the server.
- **Large files:** pushing chunks asynchronously without limiting how many are in flight lets an 80 MB file pile up in server memory. Forwarding synchronously slows the sender down automatically.
- **Wrong IP on lab machines:** clients with several network cards (VirtualBox, WSL) can report an IP the other side can't reach in `AudioEndpoint.host`. Have the server use the address it actually sees on the connection.
- **Security:**
  - Session identities should be random UUIDs; otherwise anyone who guesses one can act as that user.
  - Receivers should strip any path from `fileName` before saving (e.g. `../../x`).

## Lessons

- Interface inheritance in Slice (`Session extends MessageOps, ...`) gives a modular contract while the client only handles one proxy.
- Order of chunks isn't guaranteed when the server dispatches with a thread pool, so the receiver writes each chunk at `index * chunkSize` instead of appending.
- Slice identifiers can't contain underscores (`illegal underscore in identifier`) unless `slice2java` runs with `--underscore`. The AI's suggested `CHUNK_SIZE` failed to compile, so constants use PascalCase (`ChunkSize`, `AudioSampleRate`).
