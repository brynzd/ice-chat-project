package server;

import chat.ClientCallbackPrx;
import chat.InvalidNameException;
import chat.NicknameTakenException;
import chat.SessionPrx;
import com.zeroc.Ice.Connection;
import com.zeroc.Ice.Current;
import com.zeroc.Ice.Identity;
import com.zeroc.Ice.NotRegisteredException;
import com.zeroc.Ice.ObjectAdapter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

/**
 * Usuarios conectados. Es el unico estado compartido de M2: lo usan todos los hilos del
 * servidor, por eso es un ConcurrentHashMap y ninguna operacion invoca callbacks dentro de un lock.
 */
public final class SessionManager {

    // clave: Names.key(nickname)
    private final ConcurrentMap<String, UserSession> users = new ConcurrentHashMap<>();
    private final ObjectAdapter adapter;

    public SessionManager(ObjectAdapter adapter) {
        this.adapter = adapter;
    }

    public SessionPrx login(String nickname, ClientCallbackPrx callback, Current current)
            throws NicknameTakenException, InvalidNameException {
        Names.validate(nickname, "Nickname");
        if (callback == null) {
            throw new InvalidNameException("Falta el proxy de callback");
        }

        UserSession user = new UserSession(nickname, IceSupport.withTimeout(callback),
                IceSupport.newSessionIdentity(), current.con);
        // putIfAbsent es atomico: si dos clientes piden el mismo nickname a la vez, solo uno lo obtiene
        if (users.putIfAbsent(Names.key(nickname), user) != null) {
            throw new NicknameTakenException("El nickname '" + nickname + "' ya esta en uso");
        }

        SessionPrx session = SessionPrx.uncheckedCast(adapter.add(new SessionI(user, this), user.identity()));
        // Cierre de la conexion (salida del cliente o perdida de heartbeats) = fin de la sesion
        Connection connection = current.con;
        IceSupport.onConnectionClosed(current, () -> closeAllOn(connection, "conexion perdida"));

        System.out.println("[server] " + nickname + " conectado");
        notifyOthers(user, cb -> cb.onUserJoinedAsync(nickname));
        return session;
    }

    /**
     * Cierra la sesion: la quita del mapa y del adaptador y avisa a los demas.
     * Se puede llamar varias veces (logout, conexion cerrada, callback fallido); solo la primera hace algo.
     */
    public void end(UserSession user, String reason) {
        if (!user.markClosed()) {
            return;
        }
        // remove(clave, valor): no borra una sesion nueva que ya reutilizo el mismo nickname
        users.remove(Names.key(user.nickname()), user);
        try {
            adapter.remove(user.identity());
        } catch (NotRegisteredException ignored) {
            // ya no estaba registrado
        }
        System.out.println("[server] " + user.nickname() + " desconectado (" + reason + ")");
        notifyOthers(user, cb -> cb.onUserLeftAsync(user.nickname()));
    }

    /** Cierra todas las sesiones que hicieron login por esta conexion. */
    void closeAllOn(Connection connection, String reason) {
        for (UserSession user : users.values()) {
            if (user.connection() == connection) {
                end(user, reason);
            }
        }
    }

    /** Usuario conectado con ese nickname, o null. */
    public UserSession find(String nickname) {
        return nickname == null ? null : users.get(Names.key(nickname));
    }

    /** Nicknames conectados, en orden alfabetico. */
    public List<String> nicknames() {
        List<String> names = new ArrayList<>();
        for (UserSession user : users.values()) {
            names.add(user.nickname());
        }
        names.sort(Comparator.comparing(Names::key));
        return names;
    }

    /**
     * Invoca un callback asincrono sobre un usuario. Si falla (cliente caido o timeout)
     * se cierra su sesion.
     */
    public void send(UserSession target, Function<ClientCallbackPrx, CompletableFuture<?>> call) {
        IceSupport.whenFails(call.apply(target.callback()), () -> end(target, "no responde"));
    }

    /** Envia el callback a todos los usuarios conectados menos a except. */
    public void notifyOthers(UserSession except, Function<ClientCallbackPrx, CompletableFuture<?>> call) {
        for (UserSession user : users.values()) {
            if (user != except) {
                send(user, call);
            }
        }
    }
}
