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
 * - uploadChunk reenvia cada bloque de forma sincronica: el emisor no envia el siguiente
 *   bloque hasta que los destinatarios recibieron el anterior, asi un archivo grande no
 *   se acumula en la memoria del servidor.
 */
public final class IceSupport {

    /** Tiempo maximo que el servidor espera a un callback antes de darlo por fallido. */
    public static final int CALLBACK_TIMEOUT_MS = 5000;

    private IceSupport() {
    }

    /** Proxy de callback con timeout: un cliente colgado no bloquea al servidor. */
    public static ClientCallbackPrx withTimeout(ClientCallbackPrx callback) {
        return callback.ice_invocationTimeout(CALLBACK_TIMEOUT_MS);
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
        ConnectionInfo info = current.con.getInfo();
        while (info != null && !(info instanceof IPConnectionInfo)) {
            info = info.underlying;
        }
        if (info == null) {
            throw new IllegalStateException("La conexion no es TCP/UDP");
        }
        String host = ((IPConnectionInfo) info).remoteAddress;
        // IPv4 vista desde un socket IPv6: ::ffff:192.168.1.5
        return host.startsWith("::ffff:") ? host.substring("::ffff:".length()) : host;
    }

    /**
     * Endpoint UDP reportado por el cliente, pero con la IP que observa el servidor. En
     * maquinas con varias tarjetas de red el cliente puede reportar una IP inalcanzable.
     * Si el cliente se conecto por loopback (misma maquina que el servidor) se conserva
     * la IP reportada, porque 127.0.0.1 no le sirve a los demas.
     */
    public static AudioEndpoint observedEndpoint(AudioEndpoint reported, Current current) {
        String observed = remoteHost(current);
        return isLoopback(observed) ? reported : new AudioEndpoint(observed, reported.port);
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

    private static boolean isLoopback(String host) {
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
