package server;

import chat.AudioEndpoint;
import chat.ClientCallbackPrx;
import com.zeroc.Ice.ConnectionInfo;
import com.zeroc.Ice.Current;
import com.zeroc.Ice.IPConnectionInfo;
import com.zeroc.Ice.Identity;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Utilidades de Ice para los servants del servidor.
 *
 * Reglas para todo el servidor:
 * - Invocar los callbacks con los metodos *Async (p. ej. onPrivateMessageAsync) y nunca
 *   mientras se tiene un lock: un cliente lento no debe frenar a los demas.
 * - uploadChunk espera a que los destinatarios reciban cada bloque antes de responder: el
 *   emisor no envia el siguiente bloque hasta entonces, asi un archivo grande no se acumula
 *   en la memoria del servidor (ver FileTransfers).
 */
public final class IceSupport {

    /** Tiempo maximo que el servidor espera a un callback antes de darlo por fallido. */
    public static final int CALLBACK_TIMEOUT_MS = 5000;

    private IceSupport() {
    }

    /**
     * Proxy de callback que usa la conexion por la que llego la llamada (conexion bidireccional:
     * el servidor no abre otra conexion hacia el cliente, asi que no importan su firewall ni sus IPs)
     * y con timeout, para que un cliente colgado no bloquee al servidor.
     */
    public static ClientCallbackPrx callbackOn(ClientCallbackPrx callback, Current current) {
        return callback.ice_fixed(current.con).ice_invocationTimeout(CALLBACK_TIMEOUT_MS);
    }

    /** Identidad aleatoria para el Session de cada usuario, para que nadie pueda adivinarla. */
    public static Identity newSessionIdentity() {
        return new Identity(UUID.randomUUID().toString(), "session");
    }

    /**
     * Ejecuta cleanup cuando se cierra la conexion por la que llego la llamada: cierre del
     * cliente o perdida de heartbeats (Ice.ACM.* en config.server). Llamarlo en login().
     * Hay un solo callback por conexion, y tambien corre despues de un logout,
     * asi que cleanup debe poder ejecutarse mas de una vez sin efecto.
     */
    public static void onConnectionClosed(Current current, Runnable cleanup) {
        current.con.setCloseCallback(connection -> cleanup.run());
    }

    /** IP desde la que el cliente se conecto al servidor. */
    public static String remoteHost(Current current) {
        return plainIp(ipInfo(current).remoteAddress);
    }

    /**
     * IP del servidor a la que se conecto el cliente.
     *
     * @param current llamada recibida por la conexion del cliente
     * @return IP local de esa conexion
     */
    public static String localHost(Current current) {
        return plainIp(ipInfo(current).localAddress);
    }

    /**
     * Endpoint UDP de owner tal como lo alcanza peer. Usa la IP que ve el servidor, no la reportada
     * (con varias tarjetas de red puede ser inalcanzable); si owner esta en la maquina del servidor,
     * usa la IP del servidor que conoce peer.
     *
     * @param owner cliente que recibe el audio
     * @param port  puerto UDP donde recibe owner
     * @param peer  cliente que le envia el audio
     * @return direccion a la que peer debe enviar
     */
    public static AudioEndpoint endpointFor(UserSession owner, int port, UserSession peer) {
        return new AudioEndpoint(isLoopback(owner.host()) ? peer.serverHost() : owner.host(), port);
    }

    /**
     * Ejecuta onFailure si un callback asincrono falla (cliente caido o timeout).
     * Corre en un hilo de Ice: onFailure no debe bloquear.
     */
    public static void whenFails(CompletableFuture<?> invocation, Runnable onFailure) {
        invocation.whenComplete((ignored, error) -> {
            if (error != null) {
                onFailure.run();
            }
        });
    }

    /**
     * Datos IP de la conexion por la que llego la llamada.
     *
     * @param current llamada recibida
     * @return informacion TCP/UDP de la conexion
     */
    private static IPConnectionInfo ipInfo(Current current) {
        ConnectionInfo info = current.con.getInfo();
        while (info != null && !(info instanceof IPConnectionInfo)) {
            info = info.underlying;
        }
        if (info == null) {
            throw new IllegalStateException("La conexion no es TCP/UDP");
        }
        return (IPConnectionInfo) info;
    }

    /**
     * Quita el prefijo de una IPv4 vista desde un socket IPv6 (::ffff:192.168.1.5).
     *
     * @param host IP de la conexion
     * @return IP sin prefijo
     */
    private static String plainIp(String host) {
        return host.startsWith("::ffff:") ? host.substring("::ffff:".length()) : host;
    }

    private static boolean isLoopback(String host) {
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
