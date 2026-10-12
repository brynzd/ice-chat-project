package client.cli;

import chat.ChatException;
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
            "  /login <nick>        iniciar sesion",
            "  /users               ver usuarios conectados",
            "  /msg <nick> <texto>  mensaje privado",
            "  /logout              cerrar sesion",
            "  /help                esta ayuda",
            "  /quit                salir");

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
            print("Usa /msg <nick> <texto> para enviar un mensaje (/help para ver los comandos)");
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

    private void users() {
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
        if (error instanceof OperationNotExistException) {
            return "Esta operacion aun no esta disponible en el servidor";
        }
        if (error instanceof com.zeroc.Ice.ConnectFailedException) {
            return "No se pudo conectar con el servidor (revisa Lobby.Proxy y que el servidor este corriendo)";
        }
        return "Error de comunicacion: " + error.getClass().getSimpleName();
    }
}
