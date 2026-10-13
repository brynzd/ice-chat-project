package client.cli;

import chat.ChatException;
import chat.RoomInfo;
import chat.TextMessage;
import client.core.ChatClient;
import client.core.ChatListener;
import com.zeroc.Ice.LocalException;
import com.zeroc.Ice.OperationNotExistException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletionException;
import java.util.regex.Pattern;

/**
 * Interfaz de consola. El hilo principal lee comandos; los eventos del servidor llegan
 * en el hilo de eventos de client.core. Ambos imprimen con print(), que esta sincronizado,
 * asi las lineas nunca se mezclan.
 */
public final class Cli implements ChatListener {

    // Con consola (java directo) se usa su codificacion, p. ej. cp850 en Windows. Sin consola
    // (gradlew runClient o tuberias) se usa UTF-8: es lo que Gradle reenvia sin perder
    // caracteres; en Windows la terminal debe estar en UTF-8 (chcp 65001) para ver los acentos.
    private static final Charset CHARSET =
            System.console() != null ? System.console().charset() : StandardCharsets.UTF_8;
    private static final PrintStream OUT = new PrintStream(System.out, true, CHARSET);

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    // Caracteres de control C0, DEL y C1 (incluye ESC, que inicia las secuencias de la terminal)
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}\\x{80}-\\x{9F}]");

    private static final String HELP = String.join(System.lineSeparator(),
            "Comandos:",
            "  /login <nick>                  iniciar sesion",
            "  /users                         ver usuarios conectados",
            "  /msg <nick> <texto>            mensaje privado",
            "  /rooms                         ver las salas",
            "  /create <sala>                 crear una sala (quedas dentro)",
            "  /join <sala>                   unirse a una sala",
            "  /leave <sala>                  salir de una sala",
            "  /members <sala>                ver los miembros de una sala",
            "  /r <sala> <texto>              mensaje a una sala",
            "  /sendfile <nick|#sala> <ruta>  enviar un archivo (se guarda en downloads/<nick>/)",
            "  /call <nick>                   llamar a un usuario",
            "  /accept, /reject               contestar o rechazar una llamada",
            "  /mute                          silenciar o activar el microfono",
            "  /hangup                        colgar",
            "  /logout                        cerrar sesion",
            "  /help                          esta ayuda",
            "  /quit                          salir");

    private static final String CALL_HINT = "/mute para silenciar, /hangup para colgar";

    private ChatClient chat;

    public void run(ChatClient chat) {
        this.chat = chat;
        print("Chat Ice. Escribe /help para ver los comandos.");
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, CHARSET));
        try {
            String line;
            while ((line = in.readLine()) != null) {
                if (!handle(line.trim())) {
                    break;
                }
            }
        } catch (IOException e) {
            print("[error] No se pudo leer la consola: " + e.getMessage());
        }
        chat.logout();
    }

    /** Ejecuta un comando; devuelve false para salir. */
    private boolean handle(String line) {
        if (line.isEmpty()) {
            return true;
        }
        if (!line.startsWith("/")) {
            print("Usa /msg <nick> <texto> o /r <sala> <texto> para enviar un mensaje (/help para ver los comandos)");
            return true;
        }
        String[] parts = line.split("\\s+", 3);
        String command = parts[0].toLowerCase();
        try {
            switch (command) {
                case "/help" -> print(HELP);
                case "/quit" -> {
                    return false;
                }
                case "/login" -> login(parts);
                case "/users" -> users();
                case "/msg" -> msg(parts);
                case "/rooms" -> rooms();
                case "/create" -> create(parts);
                case "/join" -> join(parts);
                case "/leave" -> leave(parts);
                case "/members" -> members(parts);
                case "/r" -> roomMsg(parts);
                case "/sendfile" -> sendFile(parts);
                case "/call" -> call(parts);
                case "/accept" -> accept();
                case "/reject" -> reject();
                case "/mute" -> mute();
                case "/hangup" -> hangup();
                case "/logout" -> logout();
                default -> print("Comando desconocido: " + parts[0] + " (/help para ver los comandos)");
            }
        } catch (ChatException e) {
            print("[error] " + e.reason);
        } catch (IllegalStateException e) {
            print("[error] " + e.getMessage());
        } catch (LocalException e) {
            print("[error] " + describe(e));
        }
        return true;
    }

    private void login(String[] parts) throws ChatException {
        if (parts.length != 2) {
            print("Uso: /login <nick>");
            return;
        }
        chat.login(parts[1]);
        String[] online = chat.listUsers();
        print("Sesion iniciada como " + chat.nickname() + ". Conectados (" + online.length + "): "
                + String.join(", ", online));
    }

    private void users() throws ChatException {
        String[] online = chat.listUsers();
        print("Conectados (" + online.length + "): " + String.join(", ", online));
    }

    private void msg(String[] parts) {
        if (parts.length < 3) {
            print("Uso: /msg <nick> <texto>");
            return;
        }
        String to = parts[1];
        chat.sendPrivate(to, parts[2]).whenComplete((ok, error) -> {
            if (error == null) {
                print("[ok] enviado a " + to);
            } else {
                print("[error] " + describe(error));
            }
        });
    }

    // ---- Salas ----

    private void rooms() throws ChatException {
        RoomInfo[] rooms = chat.listRooms();
        if (rooms.length == 0) {
            print("No hay salas. Crea una con /create <sala>");
            return;
        }
        StringBuilder text = new StringBuilder("Salas (" + rooms.length + "):");
        for (RoomInfo room : rooms) {
            text.append(System.lineSeparator()).append("  #").append(room.name)
                    .append(" (").append(room.memberCount).append(room.memberCount == 1 ? " miembro)" : " miembros)");
            if (room.hasActiveCall) {
                text.append(" - llamada activa");
            }
        }
        print(text.toString()); // un solo print: los eventos no se mezclan dentro del listado
    }

    private void create(String[] parts) throws ChatException {
        if (parts.length != 2) {
            print("Uso: /create <sala>");
            return;
        }
        String room = roomName(parts[1]);
        chat.createRoom(room);
        print("Sala #" + room + " creada. Escribe en ella con /r " + room + " <texto>");
    }

    private void join(String[] parts) throws ChatException {
        if (parts.length != 2) {
            print("Uso: /join <sala>");
            return;
        }
        String room = roomName(parts[1]);
        chat.joinRoom(room);
        String[] members = chat.listMembers(room);
        print("Estas en #" + room + ". Miembros (" + members.length + "): " + String.join(", ", members)
                + ". Escribe con /r " + room + " <texto>");
    }

    private void leave(String[] parts) throws ChatException {
        if (parts.length != 2) {
            print("Uso: /leave <sala>");
            return;
        }
        String room = roomName(parts[1]);
        chat.leaveRoom(room);
        print("Saliste de #" + room);
    }

    private void members(String[] parts) throws ChatException {
        if (parts.length != 2) {
            print("Uso: /members <sala>");
            return;
        }
        String room = roomName(parts[1]);
        String[] members = chat.listMembers(room);
        print("Miembros de #" + room + " (" + members.length + "): " + String.join(", ", members));
    }

    private void roomMsg(String[] parts) {
        if (parts.length < 3) {
            print("Uso: /r <sala> <texto>");
            return;
        }
        String room = roomName(parts[1]);
        chat.sendToRoom(room, parts[2]).whenComplete((ok, error) -> {
            if (error == null) {
                print("[ok] enviado a #" + room);
            } else {
                print("[error] " + describe(error));
            }
        });
    }

    /** "#general" y "general" son la misma sala (las salas se muestran con #). */
    private static String roomName(String arg) {
        return arg.startsWith("#") ? arg.substring(1) : arg;
    }

    // ---- Archivos ----

    private void sendFile(String[] parts) {
        if (parts.length < 3) {
            print("Uso: /sendfile <nick|#sala> <ruta>");
            return;
        }
        String target = parts[1];
        Path file;
        try {
            file = Path.of(unquote(parts[2])).toAbsolutePath(); // la ruta es el resto de la linea
        } catch (InvalidPathException e) {
            print("[error] Ruta invalida: " + parts[2]);
            return;
        }
        String name = file.getFileName().toString();
        chat.sendFile(target, file).whenComplete((ok, error) -> {
            if (error == null) {
                print("[ok] " + name + " enviado a " + target);
            } else {
                print("[error] " + name + ": " + describe(error));
            }
        });
        print("[archivo] enviando " + name + " a " + target + "...");
    }

    /** Permite rutas entre comillas, por ejemplo con espacios. */
    private static String unquote(String text) {
        return text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")
                ? text.substring(1, text.length() - 1) : text;
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        return bytes < 1024 * 1024
                ? String.format("%.1f KB", bytes / 1024.0)
                : String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    // ---- Llamadas ----

    private void call(String[] parts) throws ChatException {
        if (parts.length != 2) {
            print("Uso: /call <nick>");
            return;
        }
        chat.call(parts[1]);
        print("[llamada] Llamando a " + parts[1] + "... (/hangup para cancelar)");
    }

    private void accept() throws ChatException {
        print("[llamada] En llamada con " + chat.acceptCall() + ". " + CALL_HINT);
    }

    private void reject() throws ChatException {
        chat.rejectCall();
        print("[llamada] Llamada rechazada");
    }

    private void mute() {
        print(chat.toggleMute() ? "[llamada] Microfono silenciado" : "[llamada] Microfono activado");
    }

    private void hangup() throws ChatException {
        chat.hangup();
        print("[llamada] Llamada terminada");
    }

    private void logout() {
        if (!chat.isLoggedIn()) {
            print("No hay una sesion abierta");
            return;
        }
        chat.logout();
        print("Sesion cerrada");
    }

    // ---- Eventos del servidor (hilos de Ice) ----

    @Override
    public void userJoined(String nickname) {
        print("* " + nickname + " se conecto");
    }

    @Override
    public void userLeft(String nickname) {
        print("* " + nickname + " se desconecto");
    }

    @Override
    public void privateMessage(TextMessage msg) {
        print("[" + TIME.format(Instant.ofEpochMilli(msg.timestamp)) + "] (privado) " + clean(msg.from) + ": "
                + clean(msg.text));
    }

    @Override
    public void roomMessage(TextMessage msg) {
        print("[" + TIME.format(Instant.ofEpochMilli(msg.timestamp)) + "] #" + clean(msg.room) + " "
                + clean(msg.from) + ": " + clean(msg.text));
    }

    @Override
    public void memberJoinedRoom(String room, String nickname) {
        print("* " + clean(nickname) + " se unio a #" + clean(room));
    }

    @Override
    public void memberLeftRoom(String room, String nickname) {
        print("* " + clean(nickname) + " salio de #" + clean(room));
    }

    @Override
    public void fileIncoming(String from, String room, String fileName, long size) {
        String to = room.isEmpty() ? "te envia " : "envia a #" + clean(room) + " ";
        print("[archivo] " + clean(from) + " " + to + clean(fileName) + " (" + formatSize(size) + ")");
    }

    @Override
    public void fileProgress(String fileName, int percent) {
        print("[archivo] " + clean(fileName) + ": " + percent + "%");
    }

    @Override
    public void fileReceived(String from, String room, Path file) {
        print("[archivo] " + clean(file.getFileName().toString()) + " de " + clean(from) + " guardado en "
                + file.toAbsolutePath() + " (SHA-256 verificado)");
    }

    @Override
    public void fileFailed(String fileName, String reason) {
        print("[archivo] " + clean(fileName) + ": " + reason);
    }

    @Override
    public void incomingCall(String from) {
        print("[llamada] " + clean(from) + " te esta llamando. /accept para contestar, /reject para rechazar");
    }

    @Override
    public void callAccepted(String peer) {
        print("[llamada] " + clean(peer) + " contesto. " + CALL_HINT);
    }

    @Override
    public void callEnded(String peer, String reason) {
        print("[llamada] " + clean(peer) + " " + reason);
    }

    @Override
    public void disconnected(String reason) {
        print("[error] " + reason + ". Usa /login <nick> para volver a entrar.");
    }

    private static synchronized void print(String text) {
        OUT.println(text);
    }

    /**
     * Texto recibido de otro usuario, sin caracteres de control: un salto de linea o una
     * secuencia de escape podria falsificar lineas como "[ok] ..." o "* x se desconecto".
     */
    private static String clean(String text) {
        return CONTROL.matcher(text).replaceAll(" ");
    }

    private static String describe(Throwable error) {
        if (error instanceof CompletionException && error.getCause() != null) {
            error = error.getCause();
        }
        if (error instanceof ChatException e) {
            return e.reason;
        }
        if (error instanceof IllegalStateException) {
            return error.getMessage();
        }
        if (error instanceof IOException) {
            return "no se pudo leer el archivo (" + error.getMessage() + ")";
        }
        if (error instanceof OperationNotExistException) {
            return "Esta operacion aun no esta disponible en el servidor";
        }
        if (error instanceof com.zeroc.Ice.ConnectFailedException) {
            return "No se pudo conectar con el servidor (revisa Lobby.Proxy y que el servidor este corriendo)";
        }
        return "Error de comunicacion: " + error.getClass().getSimpleName();
    }
}
