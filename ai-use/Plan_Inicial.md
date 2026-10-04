# AI-use log: initial planning session

- **Date:** 2026-10-04
- **Tool:** Claude Code (Claude Opus 5.5), desktop app
- **AI policy level:** Level 3, Assisted Collaboration (see the assignment PDF, delivery instructions §5)
- **Role agreed with the AI:** tutor and collaborator. The AI explains concepts, reviews designs and code, and helps debug. **It does not write the solution logic.** The only code the AI writes is the environment setup and build skeleton (Gradle modules, Slice plugin, run tasks, empty entry points).

## Main prompts

1. *"I need you to help me develop the project outlined in the Taller_Evaluativo_Chat_Ice.pdf file. Please read it and teach me how to do this project. Don't go and solve it because I need to code it myself, as outlined in the document, your role is to collaborate with me."*
2. *"You are allowed to create the skeleton, please also check my gradle versions and jdk versions and install and configure the project for me. Our team is only 2 people. Group audio we can do relay, let's chat more about the UI in terms of complexity and the reward that the more complex can give."*
3. UI decision (after the AI compared CLI and JavaFX): **"CLI now, JavaFX later."**
4. *"For purposes of AI usage tracker, create a folder called ai-use/ … Plan_Inicial.md which contains this initial planning."* Also: *"configure the gitignore (if needed)."*

## Decisions made

| Decision | Choice | Reasoning |
|---|---|---|
| Team size | 2 people | Suggested split: one person owns the Ice side (session, chat, rooms, files), the other owns audio and UDP. Both design the Slice contract together and review each other's code, so both can defend all of it. |
| Group-audio topology (RF-06) | **Centralized relay (lightweight SFU)** | Each client sends 1 stream instead of N−1, so the client stays simpler. The server carries the forwarding load. Easier to explain in conceptual question 3. |
| UI | **CLI now, JavaFX possible later** | The rubric weights a solid CLI the same as JavaFX. JavaFX adds setup risk (the `openjfx` plugin on Gradle 9 / JDK 26), requires `Platform.runLater` for every Ice callback, and needs roughly 2–3× more UI code. Voice (1.2 pts) is where the time should go. The client logic is kept separate from the UI so JavaFX can be added later without a rewrite. |

## Environment findings (developer machine)

| Item | Found | Action |
|---|---|---|
| JDK | 26.0.1 only; `JAVA_HOME` not set | Try it with Gradle 9.7.1; fall back to Temurin 21 and Gradle 8.x if the Ice plugin fails |
| Gradle wrapper | 9.7.1 | The ice-builder plugin predates Gradle 9, so compatibility must be checked |
| Configuration cache | Enabled in `gradle.properties` | Disable it if the Ice plugin rejects it |
| ZeroC Ice / `slice2java` | Not installed | Install Ice 3.7 for Windows |
| Generated Java in `common/src/main/java` | Still tracked in git, generated with Ice 3.7.11 (runtime is 3.7.10) | Remove from git; generate into `build/` via the plugin; align versions |
| `server/`, `client/` modules | Listed in `settings.gradle`, but the folders don't exist | Create skeleton modules |

## Setup and skeleton steps (done with AI assistance)

1. Install ZeroC Ice 3.7 (provides `slice2java`), and align the `com.zeroc:ice` Maven version with it.
2. Check that JDK 26, Gradle 9.7.1 and the ice-builder plugin work together; fall back if they don't. Compile for Java 17 (`options.release = 17`).
3. `common/`: use the official `com.zeroc.gradle.ice-builder.slice` plugin instead of the `Exec` task, and expose Ice through `api`.
4. Root build: clean up the shared configuration.
5. Create `server/` (`runServer`) and `client/` (`runClient`, interactive stdin), with placeholder mains only. The client gets separate packages for the core logic and the CLI.
6. Update `.gitignore`.
7. README: add a Setup section.

### What actually happened during setup

- **Ice 3.7.11:** installed from the official MSI (signature checked: ZeroC). The ice-builder plugin finds it through the Windows registry, so no PATH changes were needed.
- **Gradle 9.7.1 failed:**
  - First, configuration cache error: the plugin uses `Task.project` while tasks run.
  - Then, with the cache disabled: `NoClassDefFoundError: groovy/util/XmlSlurper`. Gradle 9 bundles Groovy 4, which no longer has that class.
  - **Lesson:** the official plugin (1.5.2, Feb 2025) has not been updated for Gradle 9.
- **Fix:** Gradle wrapper set to **8.14.3** and **Temurin 21** installed (the MSI also set `JAVA_HOME` machine-wide). Gradle 8 can't run on JDK 26. Code compiles with `--release 17`.
- Configuration cache disabled in `gradle.properties` because the plugin is incompatible with it.
- Ice runtime aligned to `com.zeroc:ice:3.7.11` (`api` in `common`), and the generated `.java` files removed from git (now in `common/build/generated-src`).
- Checked: `./gradlew clean build` passes, `runServer` and `runClient` start, and the client reads console input.

## Learning roadmap (the team codes it; the AI reviews and explains)

| Milestone | Topic | Rubric |
|---|---|---|
| M1 | Slice contract: server interface, client callback interface, structs, custom exceptions | 0.7 |
| M2 | Session, unique nickname, callbacks, real-time presence, logout and dead-client detection | 0.8 (with M3) |
| M3 | Private messages with acknowledgements and errors | |
| M4 | Rooms: create, list, join, leave; broadcast without echo; isolation between rooms | 0.8 |
| M5 | Chunked file transfer (`sequence<byte>`, `Ice.MessageSizeMax`), byte-identical reassembly | 0.9 |
| M6 | 1-to-1 voice: Ice signaling (request, ring, accept, reject, hang up) and audio over UDP | 1.2 (with M7) |
| M7 | Group voice through the server's UDP relay, mute/unmute, leaving without breaking the call | |
| M8 | README (architecture diagram, user manual), answers to Q1–Q4, this AI log, demo rehearsal on the lab LAN | 0.6 |

## Lessons and notes
*(To be completed as the project progresses: what the AI suggested, what the team adapted, and what was learned while debugging.)*
