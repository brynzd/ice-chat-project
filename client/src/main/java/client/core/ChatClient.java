package client.core;

import chat.ClientCallbackPrx;
import chat.InvalidNameException;
import chat.LobbyPrx;
import chat.NicknameTakenException;
import chat.SessionPrx;
import com.zeroc.Ice.Connection;
import com.zeroc.Ice.LocalException;
import com.zeroc.Ice.ObjectAdapter;

import java.util.concurrent.CompletableFuture;

/**
 * Logica del cliente sin interfaz: login, presencia y mensajes privados. No imprime nada;
 * todo lo que llega del servidor se reporta al ChatListener.
 */
public final class ChatClient {

    private final LobbyPrx lobby;
    private final ClientCallbackPrx callback;
    private final ChatListener listener;

    // null si no hay sesion. volatile: lo leen tambien los hilos de Ice (close callback)
    private volatile SessionPrx session;
    private volatile String nickname;

    public ChatClient(ObjectAdapter callbackAdapter, LobbyPrx lobby, ChatListener listener) {
        this.lobby = lobby;
        this.listener = listener;
        this.callback = ClientCallbackPrx.uncheckedCast(callbackAdapter.addWithUUID(new CallbackI(listener)));
    }

    public synchronized void login(String nick) throws NicknameTakenException, InvalidNameException {
        if (session != null) {
            throw new IllegalStateException("Ya iniciaste sesion como " + nickname + "; usa /logout primero");
        }
        Connection connection = lobby.ice_getConnection();
        // ice_fixed: la sesion usa la misma conexion del login, que es la que el servidor vigila
        // para detectar clientes caidos (y no otra hacia los endpoints publicados del servidor)
        SessionPrx s = lobby.login(nick, callback).ice_fixed(connection);
        session = s;
        nickname = nick;
        connection.setCloseCallback(c -> {
            synchronized (this) {
                if (session != s) {
                    return; // logout normal o una sesion posterior
                }
                session = null;
                nickname = null;
            }
            listener.disconnected("Se perdio la conexion con el servidor");
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
                // servidor caido: no hay nada que cerrar alla
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
        return requireSession().listUsers();
    }

    /** Asincrono: la interfaz no se bloquea mientras el servidor confirma el envio. */
    public CompletableFuture<Void> sendPrivate(String to, String text) {
        return requireSession().sendPrivateAsync(to, text);
    }

    private SessionPrx requireSession() {
        SessionPrx s = session;
        if (s == null) {
            throw new IllegalStateException("Primero inicia sesion con /login <nick>");
        }
        return s;
    }
}
