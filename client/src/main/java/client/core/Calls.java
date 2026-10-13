package client.core;

import chat.AudioEndpoint;
import chat.CallException;
import chat.ChatException;
import chat.SessionPrx;
import com.zeroc.Ice.ConnectionInfo;
import com.zeroc.Ice.IPConnectionInfo;

import java.net.InetSocketAddress;

/** Llamadas de voz 1 a 1 (RF-05): estado de la llamada actual y su audio. Solo hay una llamada a la vez. */
final class Calls {

    private static final byte[] NO_HEADER = new byte[0];

    private final ChatListener listener;
    private Voice voice;
    private String callId;
    private String peer;
    private AudioEndpoint ringing;
    private boolean muted;

    /**
     * Crea el estado de llamadas.
     *
     * @param listener interfaz que recibe los eventos de llamada
     */
    Calls(ChatListener listener) {
        this.listener = listener;
    }

    /**
     * Llama a un usuario; el audio empieza cuando contesta.
     *
     * @param session sesion actual
     * @param nick    usuario al que se llama
     */
    synchronized void call(SessionPrx session, String nick) throws ChatException {
        requireIdle();
        Voice v = open();
        try {
            callId = session.requestCall(nick, endpoint(session, v));
        } catch (ChatException | RuntimeException e) {
            v.close();
            throw e;
        }
        voice = v;
        peer = nick;
    }

    /**
     * Contesta la llamada entrante y empieza el audio.
     *
     * @param session sesion actual
     * @return nickname de quien llamo
     */
    synchronized String accept(SessionPrx session) throws ChatException {
        if (ringing == null) {
            throw new IllegalStateException("No tienes llamadas entrantes");
        }
        Voice v = open();
        try {
            session.acceptCall(callId, endpoint(session, v));
        } catch (ChatException | RuntimeException e) {
            v.close();
            clear();
            throw e;
        }
        v.start(address(ringing), NO_HEADER);
        voice = v;
        ringing = null;
        return peer;
    }

    /**
     * Rechaza la llamada entrante.
     *
     * @param session sesion actual
     */
    synchronized void reject(SessionPrx session) throws ChatException {
        if (ringing == null) {
            throw new IllegalStateException("No tienes llamadas entrantes");
        }
        String id = callId;
        clear();
        session.rejectCall(id);
    }

    /**
     * Cuelga la llamada actual, tambien si aun timbra.
     *
     * @param session sesion actual
     */
    synchronized void hangup(SessionPrx session) throws ChatException {
        String id = callId;
        if (id == null) {
            throw new IllegalStateException("No estas en una llamada");
        }
        clear();
        try {
            session.hangup(id);
        } catch (CallException ignored) {
        }
    }

    /**
     * Silencia o activa el microfono; el estado sigue en las llamadas siguientes.
     *
     * @return true si quedo silenciado
     */
    synchronized boolean toggleMute() {
        muted = !muted;
        if (voice != null) {
            voice.mute(muted);
        }
        return muted;
    }

    /**
     * Llega una llamada; se ignora si ya hay otra (el servidor no deberia permitirlo).
     *
     * @param id       identificador de la llamada
     * @param from     quien llama
     * @param endpoint endpoint UDP de quien llama
     */
    void incoming(String id, String from, AudioEndpoint endpoint) {
        synchronized (this) {
            if (callId != null) {
                return;
            }
            callId = id;
            peer = from;
            ringing = endpoint;
        }
        listener.incomingCall(from);
    }

    /**
     * El usuario al que se llamo contesto: empieza el audio.
     *
     * @param id       identificador de la llamada
     * @param endpoint endpoint UDP del que contesto
     */
    void accepted(String id, AudioEndpoint endpoint) {
        String who;
        synchronized (this) {
            if (!id.equals(callId) || voice == null) {
                return;
            }
            voice.start(address(endpoint), NO_HEADER);
            who = peer;
        }
        listener.callAccepted(who);
    }

    /**
     * El otro usuario rechazo, colgo o se desconecto.
     *
     * @param id     identificador de la llamada
     * @param reason motivo para mostrar
     */
    void ended(String id, String reason) {
        String who;
        synchronized (this) {
            if (!id.equals(callId)) {
                return;
            }
            who = peer;
            clear();
        }
        listener.callEnded(who, reason);
    }

    /** Termina la llamada sin avisar al servidor (logout o sesion perdida). */
    synchronized void discard() {
        clear();
    }

    private void requireIdle() {
        if (callId != null) {
            throw new IllegalStateException("Ya estas en una llamada. Usa /hangup primero");
        }
    }

    /**
     * Abre el audio con el estado de silencio actual.
     *
     * @return audio listo para empezar
     */
    private Voice open() {
        Voice v = new Voice();
        v.mute(muted);
        return v;
    }

    /** Cierra el audio y olvida la llamada. */
    private void clear() {
        if (voice != null) {
            voice.close();
        }
        voice = null;
        callId = null;
        peer = null;
        ringing = null;
    }

    /**
     * Endpoint UDP propio con la IP local de la conexion; el servidor la corrige con la que observa.
     *
     * @param session sesion actual
     * @param voice   audio con su puerto UDP
     * @return endpoint para publicar
     */
    private static AudioEndpoint endpoint(SessionPrx session, Voice voice) {
        ConnectionInfo info = session.ice_getConnection().getInfo();
        return new AudioEndpoint(info instanceof IPConnectionInfo ip ? ip.localAddress : "", voice.port());
    }

    /**
     * Direccion UDP de un endpoint recibido del servidor.
     *
     * @param endpoint host y puerto
     * @return direccion de socket
     */
    private static InetSocketAddress address(AudioEndpoint endpoint) {
        return new InetSocketAddress(endpoint.host, endpoint.port);
    }
}
