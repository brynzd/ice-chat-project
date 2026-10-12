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

## Lessons

- An undeclared user exception thrown by a servant does not reach the client as itself. The client only sees `UnknownUserException`, so the server must map errors to the exceptions each operation declares.
- "Add, then re-check" is enough to close a race against a cleanup that runs once:
  - if the cleanup already ran, the re-check sees the session closed and undoes the add;
  - if it hasn't run yet, it will see the new member and remove it.
