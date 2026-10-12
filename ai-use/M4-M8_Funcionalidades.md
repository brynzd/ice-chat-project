# AI-use log: M4–M8, rooms, files, voice and final docs

- **Date:** 2026-10-12
- **Tool:** Claude Code (Claude Opus 5.5), desktop app
- **Branches:** `feature/rooms`, `feature/file-transfer`, `feature/voice` (each off `develop`)
- **Role:** the AI **writes the code** and the student reviews it before each commit, the same mode as M2/M3. The AI never commits; at every checkpoint it stops with a commit message and a student makes the commit.

## Context

- The teammate who was going to own audio/UDP (M6/M7) stopped answering.
- The rest of the project (M4–M8) had to be finished by one student on the delivery day (deadline 23:59).
- The student first chose "I write, Claude tutors", then asked the AI whether that was viable in the time left. The AI estimated about 1,500–2,000 new lines across server, client, UDP and audio, and recommended going back to "Claude writes, team revises". The student agreed.
- The student asked for code "as friendly and simple as possible while keeping it safe, stable, efficient and modern". That rules out optional extras: ring timeouts, a cancel command, talker names.

## Main prompts

1. *"My teammate still hasn't answered, so I'll have to assume he won't answer anymore. Help me plan out the remaining aspects needed to complete this task."*
2. Answers to the AI's planning questions:
   - CLI only, no JavaFX.
   - The student has a mic and headphones for testing.
   - *"It's 15:51 and this is due today at 23:59. Let's consider if it's viable that you tutor and I write, or if we stick to the previous structure of you write, I revise."*
3. *"Let's try to make the code as friendly and simple as possible. We must try to lower complexity as much as possible while keeping the code safe, stable, efficient and modern. Let's also cut down on the times a little bit so that I can go to sleep by 21:00 and have dinner at 18:30-20:00."*
4. *"Great, plan approved, let's begin, remember to keep a log of the AI use."*

## Planning

- The AI ran a read-only design workflow:
  - one designer agent per milestone;
  - adversarial reviewer agents to check each design against the code and the Ice 3.7.11 sources.
- Only the M4 design finished before the session was interrupted. Two of its findings changed the M4 code (see below).
- Schedule agreed with the student:
  - M4 and M5 together until 18:30;
  - the AI builds voice (M6/M7) and drafts the docs during dinner, tested with a generated tone instead of a microphone;
  - the student reviews, tests with headphones and commits from 20:00.

## M4: rooms (RF-03)

### Design decisions

| Decision | Choice | Why |
|---|---|---|
| Empty rooms | Kept until the server stops | The room map only grows, so a room found with `get()` can't be deleted under a user who is joining it. No join-vs-delete race to handle |
| Membership | `ConcurrentHashMap.newKeySet()` per room | Several server threads add, remove and iterate members at the same time |
| Room names | Same rule as nicknames (`Names.validate`), only on `createRoom`. Case-insensitive key | The CLI splits commands on spaces. `General` and `general` are one room |
| Invalid name on join/leave/members/send | Reported as `RoomNotFoundException` | Those operations only declare that exception. An undeclared user exception reaches the client as `UnknownUserException` (found by the design review) |
| Join while the session is closing | `addMember` re-checks `user.isClosed()` after adding and undoes the add | Without it, a `joinRoom` dispatched while the connection drops could add the user after the cleanup ran, leaving a ghost member forever (found by the design review) |
| Broadcast | Every member of that room except the sender, through `SessionManager.send` (async, outside locks) | No echo, strict isolation (recipients come only from that room's set), and a dead member only ends its own session |
| Session cleanup | `SessionManager.addEndListener`, which `end()` runs before the "left" notice | Rooms (and later files and calls) clean up without `SessionManager` depending on them. Members see "x salio de #sala" and then "x se desconecto" |
| Building `SessionI` | `LobbyI` receives a factory (`user -> new SessionI(user, sessions, rooms)`) built in `Server.main` | Avoids a cycle between `SessionManager` and the feature managers. `Server.main` is the only place that wires them |
| Joining twice | Does nothing and notifies nobody | As the contract comment says |
| Client calls | Short commands are synchronous through one helper (`query`/`run`), which also handles a session the server dropped. `/r` is asynchronous with `[ok]`, sharing `confirmed()` with `/msg` | Removes the duplicated try/catch and async code |
| Sending to a room | `/r <sala> <texto>`, accepting `#sala` too | Simplest option; no "current room" state in the CLI |

### AI-generated code

| File | Content |
|---|---|
| `server/.../Room.java` (new) | Record: name plus a concurrent member set |
| `server/.../RoomManager.java` (new) | create / join / leave / leaveAll / list / members / send |
| `server/.../SessionManager.java` | `Callback` type, `addEndListener`, `send` returns the invocation, `login` takes the servant factory |
| `server/.../LobbyI.java`, `Server.java`, `SessionI.java`, `UserSession.java` | Factory wiring, room operations, `isClosed()` |
| `client/.../ChatClient.java` | Room methods; `query`/`run`/`confirmed` helpers |
| `client/.../ChatListener.java`, `CallbackI.java` | Room events |
| `client/.../Cli.java` | `/rooms`, `/create`, `/join`, `/leave`, `/members`, `/r`; room message and membership output |
| `README.md` | Room commands and example |

### How it was verified

- `./gradlew build` passes.
- Scripted run: server plus 3 CLI clients (alice, bob, carol) with piped commands. All checks passed:
  - isolation: bob, only in #general, never got the #dev message; carol, only in #dev, never got the #general one;
  - no echo: the sender never receives its own room message;
  - join and leave notices go only to the other members, and joining twice notifies nobody;
  - errors: duplicate room with different capitals, invalid name, unknown room, writing to a room you aren't in, leaving twice;
  - carol's process killed: alice saw "carol salio de #dev" and then "carol se desconecto";
  - logout removes the user from every room.

### Revisions

*(Student review of M4; to be filled in by the student.)*

### Follow-up: message order (review finding)

- One real issue came back (low severity): two `/r` or `/msg` lines pasted at once could arrive swapped.
  - Cause: the server dispatches requests from one connection on several threads. In `ConnectionI.message`, Ice frees the connection for the next read before dispatching.
- **Rejected fix:** `Ice.ThreadPool.Server.Serialize=1`. Uploads in M5 wait for the receivers, so two users sending each other files would block each other.
- **Applied fix:** `ChatClient.inOrder` starts each send only after the previous one gets its reply. About 6 lines, client only.
- Prompt: *"committed and merged, do the fix and then we'll move on to M5."*
- Verified: 40 room messages and 40 private messages pasted in one burst arrived in order, each with its `[ok]`.

## M5: file transfer (RF-04)

### Design decisions

| Decision | Choice | Why |
|---|---|---|
| Who stores the file | Nobody in the middle: the server relays each chunk and keeps only the transfer metadata | An 80 MB (or 600 MB) file never sits in server memory |
| Flow control | `uploadChunk` sends the chunk to every recipient at once (`*Async`) and waits for all of them (`allOf().join()`) before returning. The client sends chunks one at a time | The sender can't go faster than the slowest live receiver, so nothing piles up anywhere. One slow receiver doesn't delay the others' delivery of the same chunk |
| Where the receiver writes | In the Ice thread that delivers `onFileChunk`, at `index * chunkSize`, into a `.part` file | The server waits for that reply, so the backpressure reaches the disk. Queueing chunks on the events thread would remove the backpressure and let chunks pile up in client memory |
| Integrity | SHA-256 of the whole file in `FileInfo`. The receiver checks size and hash before renaming `.part` to the final name | RF-04 asks for reconstruction without corruption or truncation. A bad file is discarded, never shown as received |
| Validation on the server | File name without path or control characters (`InvalidNameException`). Size ≤ 1 GB, `chunkSize == ChunkSize`, `totalChunks == ceil(size / chunkSize)`, hex SHA-256. Each chunk must come from the sender, in order and with the exact length (`TransferException`) | Never trust what the client sends. Also catches a client bug at the first bad chunk |
| Room recipients | Members at the moment the transfer starts, excluding the sender | Someone joining halfway would get a file without its start. An empty room is an error |
| A recipient fails | Dropped from the transfer (and its session ends via `SessionManager.send`). The others keep receiving. If nobody is left, `TransferException` | One dead client can't stop the transfer for everyone |
| The sender disconnects | Session-end hook: its transfers are aborted, recipients get `onFileAborted` and delete the `.part`. The sender's client also calls `cancelTransfer` if reading the file fails | No half-written files left behind as if they were complete |
| Where files go | `client/downloads/<my nick>/`, with `FileNames.safeName` and a free name (`foto (1).png`) | Two clients on one PC share `client/` as working directory. Received names can't escape the folder or overwrite files |
| Sending in the background | One `file-upload` thread; several `/sendfile` commands queue up in order | The CLI and chat keep working during an upload (verified) |
| Empty file | Not registered on the server; complete as soon as `onFileStart` arrives | As the contract comment says (`totalChunks = 0`) |
| Not done (simplicity) | No `/cancel` command, no resuming | Agreed cuts. `cancelTransfer` is used internally when reading the file fails |

### AI-generated code

| File | Content |
|---|---|
| `server/.../FileTransfers.java` (new) | Transfer registry, validation, chunk relay with waiting, cancel, session-end hook |
| `server/.../SessionI.java`, `Server.java`, `IceSupport.java` | `FileOps` operations, wiring and hook registration (files before rooms), comment update |
| `client/.../FileTransfers.java` (new) | Upload thread (SHA-256, start, chunks, progress) and receiving (`.part`, positional writes, verify, rename, discard) |
| `client/.../Hashes.java` (new) | Streaming SHA-256 |
| `client/.../ChatClient.java` | `sendFile`. Logout and lost session discard partial files |
| `client/.../CallbackI.java`, `ChatListener.java`, `Cli.java` | File callbacks and events; `/sendfile <nick\|#sala> <ruta>` |
| `README.md` | `/sendfile` in the manual, with examples and failure behavior |

### How it was verified

- `./gradlew build` passes.
- Scripted run with 3 clients (alice sends; bob and carol in `#general`):

| File | Sent to |
|---|---|
| .png with spaces in its name (300 KB) | bob |
| .pdf (the assignment, 187 KB) | bob |
| .wav (3 s tone) | `#general` |
| empty file | `#general` |
| exact multiple of 64 KB | bob |
| 80 MB of random data | bob, then `#general` |

- Every received file has the same SHA-256 as the original (checked with `sha256sum`), and a repeated name was saved as `big (1).bin`.
- A room message sent during the 80 MB uploads arrived normally.
- Errors checked: unknown user, sending to yourself, unknown room, missing local file.
- Killing the sender while bob and carol each had about 78 MB of a 600 MB file:
  - both saw "envio cancelado (alice se desconecto)";
  - their `.part` files were deleted;
  - the server logged the abort.
- Killing one receiver mid-transfer: bob still got the full 600 MB with a matching hash, and alice got `[ok]`.
- Speed on localhost: about 600 MB in 1.5 s.

### Revisions

*(Student review of M5; to be filled in by the student.)*

## Lessons

- An undeclared user exception thrown by a servant does not reach the client as itself. The client only sees `UnknownUserException`, so the server must map errors to the exceptions each operation declares.
- "Add, then re-check" is enough to close a race against a cleanup that runs once:
  - if the cleanup already ran, the re-check sees the session closed and undoes the add;
  - if it hasn't run yet, it will see the new member and remove it.
- Ice dispatches requests from one connection in parallel when the server pool has more than one thread. Order between two calls is only guaranteed if the client waits for the first reply.
- Backpressure only works if every step waits:
  - the sender waits for `uploadChunk`;
  - the server waits for the receivers;
  - the receiver writes to disk before replying.
  - If any step only queues the work, memory fills up instead.
- A test that kills a process "in the middle of a transfer" must wait for evidence that the transfer started, such as the `.part` file appearing, and not for a fixed time. On localhost an 80 MB file finished before the fixed-time kill.
