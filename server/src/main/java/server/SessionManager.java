package server;

import chat.ClientCallbackPrx;
import chat.InvalidNameException;
import chat.NicknameTakenException;
import chat.Session;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Usuarios conectados. Lo usan todos los hilos del servidor, por eso es un ConcurrentHashMap
 * y ninguna operacion invoca callbacks dentro de un lock.
 */
public final class SessionManager {

    /** Invocacion asincrona de un callback, p. ej. cb -> cb.onUserJoinedAsync(nick). */
    @FunctionalInterface
    public interface Callback {
        CompletableFuture<?> invoke(ClientCallbackPrx cb);
    }

    // clave: Names.key(nickname)
    private final ConcurrentMap<String, UserSession> users = new ConcurrentHashMap<>();
    // Limpieza de salas, archivos y llamadas al cerrarse una sesion (se registran en Server.main)
    private final List<Consumer<UserSession>> endListeners = new CopyOnWriteArrayList<>();
    private final ObjectAdapter adapter;

    public SessionManager(ObjectAdapter adapter) {
        this.adapter = adapter;
    }

    /** Ejecuta listener con el usuario cada vez que se cierra una sesion. */
    public void addEndListener(Consumer<UserSession> listener) {
        endListeners.add(listener);
    }

    /** servantFor crea el servant Session del usuario (lo arma Server.main con todos los servicios). */
    public SessionPrx login(String nickname, ClientCallbackPrx callback, Current current,
                            Function<UserSession, Session> servantFor)
            throws NicknameTakenException, InvalidNameException {
        Names.validate(nickname, "Nickname");
        if (callback == null) {
            throw new InvalidNameException("Falta el proxy de callback");
        }

        UserSession user = new UserSession(nickname, IceSupport.callbackOn(callback, current),
                IceSupport.newSessionIdentity(), current.con);
        // putIfAbsent es atomico: si dos clientes piden el mismo nickname a la vez, solo uno lo obtiene
        if (users.putIfAbsent(Names.key(nickname), user) != null) {
            throw new NicknameTakenException("El nickname '" + nickname + "' ya esta en uso");
        }

        SessionPrx session;
        try {
            session = SessionPrx.uncheckedCast(adapter.add(servantFor.apply(user), user.identity()));
        } catch (RuntimeException e) {
            // p. ej. el servidor se esta cerrando: no dejar el nickname ocupado por un usuario fantasma
            users.remove(Names.key(nickname), user);
            throw e;
        }

        System.out.println("[server] " + nickname + " conectado");
        notifyOthers(user, cb -> cb.onUserJoinedAsync(nickname));

        // Cierre de la conexion (salida del cliente o perdida de heartbeats) = fin de la sesion.
        // Se registra despues del aviso "se conecto": si la conexion ya se cerro, Ice ejecuta
        // el callback enseguida y los demas ven "se desconecto" despues, no antes.
        Connection connection = current.con;
        IceSupport.onConnectionClosed(current, () -> closeAllOn(connection, "conexion perdida"));
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
        // Primero se libera lo del usuario (llamadas, archivos, salas) y luego se avisa que se fue
        for (Consumer<UserSession> listener : endListeners) {
            try {
                listener.accept(user);
            } catch (RuntimeException e) {
                System.out.println("[server] error limpiando la sesion de " + user.nickname() + ": " + e);
            }
        }
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
     * se cierra su sesion. Devuelve la invocacion por si hay que esperarla.
     */
    public CompletableFuture<?> send(UserSession target, Callback call) {
        CompletableFuture<?> invocation = call.invoke(target.callback());
        IceSupport.whenFails(invocation, () -> end(target, "no responde"));
        return invocation;
    }

    /** Envia el callback a todos los usuarios conectados menos a except. */
    public void notifyOthers(UserSession except, Callback call) {
        for (UserSession user : users.values()) {
            if (user != except) {
                send(user, call);
            }
        }
    }
}
