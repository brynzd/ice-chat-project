package client.core;

import chat.ClientCallbackPrx;
import chat.InvalidNameException;
import chat.LobbyPrx;
import chat.NicknameTakenException;
import chat.SessionPrx;
import com.zeroc.Ice.Connection;
import com.zeroc.Ice.LocalException;
import com.zeroc.Ice.ObjectAdapter;
import com.zeroc.Ice.ObjectNotExistException;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Logica del cliente sin interfaz: login, presencia y mensajes privados. No imprime nada;
 * todo lo que llega del servidor se reporta al ChatListener desde un unico hilo de eventos.
 */
public final class ChatClient {

    private static final String SESSION_CLOSED = "El servidor cerro tu sesion. Usa /login <nick> para volver a entrar";

    private final LobbyPrx lobby;
    private final ObjectAdapter callbackAdapter;
    private final ClientCallbackPrx callback;
    private final ChatListener listener;

    // Hilo de eventos: los callbacks de Ice solo encolan aqui y retornan enseguida,
    // asi la interfaz puede tardar en imprimir sin frenar a Ice ni al servidor
    private final ExecutorService events = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "chat-events");
        t.setDaemon(true);
        return t;
    });

    // null si no hay sesion. volatile: lo leen tambien los hilos de Ice (close callback)
    private volatile SessionPrx session;
    private volatile String nickname;

    public ChatClient(ObjectAdapter callbackAdapter, LobbyPrx lobby, ChatListener listener) {
        this.lobby = lobby;
        this.callbackAdapter = callbackAdapter;
        this.listener = listener;
        this.callback = ClientCallbackPrx.uncheckedCast(
                callbackAdapter.addWithUUID(new CallbackI(listener, events)));
    }

    public synchronized void login(String nick) throws NicknameTakenException, InvalidNameException {
        if (session != null) {
            throw new IllegalStateException("Ya iniciaste sesion como " + nickname + "; usa /logout primero");
        }
        Connection connection = lobby.ice_getConnection();
        // Conexion bidireccional: el servidor envia los callbacks por esta misma conexion,
        // asi el cliente no abre puertos (firewall) ni publica IPs que el servidor no alcance
        connection.setAdapter(callbackAdapter);
        // ice_fixed: la sesion usa la misma conexion del login, que es la que el servidor vigila
        // para detectar clientes caidos (y no otra hacia los endpoints publicados del servidor)
        SessionPrx s = lobby.login(nick, callback).ice_fixed(connection);
        session = s;
        nickname = nick;
        connection.setCloseCallback(c -> {
            if (dropSession(s)) {
                events.execute(() -> listener.disconnected("Se perdio la conexion con el servidor"));
            }
        });
    }

    /** Cierre ordenado; no hace nada si no hay sesion. */
    public void logout() {
        SessionPrx s;
        synchronized (this) {
            s = session;
            session = null;
            nickname = null;
        }
        if (s != null) {
            try {
                s.logout();
            } catch (LocalException ignored) {
                // servidor caido o sesion ya cerrada: no hay nada que cerrar alla
            }
        }
    }

    public boolean isLoggedIn() {
        return session != null;
    }

    public String nickname() {
        return nickname;
    }

    public String[] listUsers() {
        SessionPrx s = requireSession();
        try {
            return s.listUsers();
        } catch (ObjectNotExistException e) {
            throw sessionLost(s);
        }
    }

    /**
     * Asincrono: la interfaz no se bloquea mientras el servidor confirma el envio.
     * El resultado se completa en el hilo de eventos.
     */
    public CompletableFuture<Void> sendPrivate(String to, String text) {
        SessionPrx s = requireSession();
        CompletableFuture<Void> result = new CompletableFuture<>();
        s.sendPrivateAsync(to, text).whenCompleteAsync((ok, error) -> {
            if (error == null) {
                result.complete(null);
            } else {
                Throwable cause = error instanceof CompletionException && error.getCause() != null
                        ? error.getCause() : error;
                result.completeExceptionally(cause instanceof ObjectNotExistException ? sessionLost(s) : cause);
            }
        }, events);
        return result;
    }

    private SessionPrx requireSession() {
        SessionPrx s = session;
        if (s == null) {
            throw new IllegalStateException("Primero inicia sesion con /login <nick>");
        }
        return s;
    }

    /**
     * El servidor ya no tiene la sesion (la cerro porque el cliente no respondia a tiempo) aunque
     * la conexion siga abierta. Se olvida la sesion para poder hacer /login otra vez.
     */
    private IllegalStateException sessionLost(SessionPrx s) {
        dropSession(s);
        return new IllegalStateException(SESSION_CLOSED);
    }

    /** Olvida la sesion s si sigue siendo la actual; devuelve true si la olvido. */
    private synchronized boolean dropSession(SessionPrx s) {
        if (session != s) {
            return false; // logout normal o una sesion posterior
        }
        session = null;
        nickname = null;
        return true;
    }
}
