package server;

import chat.ClientCallbackPrx;
import com.zeroc.Ice.Connection;
import com.zeroc.Ice.Identity;

import java.util.concurrent.atomic.AtomicBoolean;

/** Estado de un usuario conectado. Inmutable salvo el indicador de cierre. */
public final class UserSession {

    private final String nickname;
    private final ClientCallbackPrx callback;
    private final Identity identity;
    private final Connection connection;
    private final String host;
    private final String serverHost;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public UserSession(String nickname, ClientCallbackPrx callback, Identity identity, Connection connection,
                       String host, String serverHost) {
        this.nickname = nickname;
        this.callback = callback;
        this.identity = identity;
        this.connection = connection;
        this.host = host;
        this.serverHost = serverHost;
    }

    public String nickname() {
        return nickname;
    }

    /** Proxy de callback fijado a la conexion del login y con timeout (ver IceSupport.callbackOn). */
    public ClientCallbackPrx callback() {
        return callback;
    }

    /** Identidad del servant Session de este usuario en el adaptador. */
    public Identity identity() {
        return identity;
    }

    /** Conexion por la que el usuario hizo login. */
    public Connection connection() {
        return connection;
    }

    /**
     * IP del cliente tal como la ve el servidor.
     *
     * @return IP remota de la conexion del login
     */
    public String host() {
        return host;
    }

    /**
     * IP del servidor tal como la usa el cliente.
     *
     * @return IP local de la conexion del login
     */
    public String serverHost() {
        return serverHost;
    }

    /** true si la sesion ya se cerro (logout, conexion perdida o callback fallido). */
    public boolean isClosed() {
        return closed.get();
    }

    /** Marca la sesion como cerrada; devuelve true solo la primera vez. */
    boolean markClosed() {
        return closed.compareAndSet(false, true);
    }
}
