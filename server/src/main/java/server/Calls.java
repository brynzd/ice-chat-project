package server;

import chat.AudioEndpoint;
import chat.CallException;
import chat.UserBusyException;
import chat.UserNotFoundException;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Llamadas de voz 1 a 1 (RF-05): senalizacion por Ice; el audio viaja directo entre los clientes por UDP. */
public final class Calls {

    /**
     * Llamada 1 a 1.
     *
     * @param id       identificador de la llamada
     * @param caller   quien llama
     * @param callee   a quien llaman
     * @param answered true cuando callee ya acepto o rechazo
     */
    private record Call(String id, UserSession caller, UserSession callee, AtomicBoolean answered) {

        /**
         * El otro participante de la llamada.
         *
         * @param user uno de los dos participantes
         * @return el otro
         */
        UserSession other(UserSession user) {
            return user == caller ? callee : caller;
        }
    }

    private final ConcurrentMap<String, Call> calls = new ConcurrentHashMap<>();
    /** Llamada en la que esta cada usuario: solo puede estar en una a la vez. */
    private final ConcurrentMap<UserSession, Call> lines = new ConcurrentHashMap<>();
    private final SessionManager sessions;

    /**
     * Crea el registro de llamadas.
     *
     * @param sessions usuarios conectados
     */
    public Calls(SessionManager sessions) {
        this.sessions = sessions;
    }

    /**
     * Inicia una llamada: el receptor recibe onIncomingCall con el endpoint de quien llama.
     *
     * @param caller   quien llama
     * @param to       nickname del receptor
     * @param endpoint endpoint UDP donde caller recibe el audio
     * @return identificador de la llamada
     */
    public String request(UserSession caller, String to, AudioEndpoint endpoint)
            throws UserNotFoundException, UserBusyException, CallException {
        int port = port(endpoint);
        UserSession callee = sessions.find(to);
        if (callee == null) {
            throw new UserNotFoundException("El usuario '" + to + "' no existe o no esta conectado");
        }
        if (callee == caller) {
            throw new UserNotFoundException("No puedes llamarte a ti mismo");
        }
        Call call = new Call(UUID.randomUUID().toString(), caller, callee, new AtomicBoolean());
        if (lines.putIfAbsent(caller, call) != null) {
            throw new CallException("Ya estas en una llamada. Usa /hangup para salir");
        }
        if (lines.putIfAbsent(callee, call) != null) {
            lines.remove(caller, call);
            throw new UserBusyException(callee.nickname() + " esta en otra llamada");
        }
        calls.put(call.id(), call);
        if (caller.isClosed() || callee.isClosed()) {
            release(call);
            throw new UserNotFoundException("El usuario '" + to + "' no existe o no esta conectado");
        }
        AudioEndpoint callerEndpoint = IceSupport.endpointFor(caller, port, callee);
        System.out.println("[server] " + caller.nickname() + " llama a " + callee.nickname());
        sessions.send(callee, cb -> cb.onIncomingCallAsync(call.id(), caller.nickname(), callerEndpoint));
        return call.id();
    }

    /**
     * El receptor contesta: quien llama recibe onCallAccepted con el endpoint del receptor.
     *
     * @param callee   quien contesta
     * @param callId   identificador de la llamada
     * @param endpoint endpoint UDP donde callee recibe el audio
     */
    public void accept(UserSession callee, String callId, AudioEndpoint endpoint) throws CallException {
        int port = port(endpoint);
        Call call = answer(callee, callId);
        AudioEndpoint calleeEndpoint = IceSupport.endpointFor(callee, port, call.caller());
        System.out.println("[server] " + callee.nickname() + " contesto a " + call.caller().nickname());
        sessions.send(call.caller(), cb -> cb.onCallAcceptedAsync(callId, calleeEndpoint));
    }

    /**
     * El receptor rechaza: quien llama recibe onCallRejected.
     *
     * @param callee quien rechaza
     * @param callId identificador de la llamada
     */
    public void reject(UserSession callee, String callId) throws CallException {
        Call call = answer(callee, callId);
        finish(call, call.caller(), cb -> cb.onCallRejectedAsync(callId));
    }

    /**
     * Cualquiera de los dos cuelga, tambien mientras timbra: el otro recibe onCallEnded.
     *
     * @param user   quien cuelga
     * @param callId identificador de la llamada
     */
    public void hangup(UserSession user, String callId) throws CallException {
        Call call = calls.get(callId);
        if (call == null || (call.caller() != user && call.callee() != user)
                || !finish(call, call.other(user), cb -> cb.onCallEndedAsync(callId))) {
            throw new CallException("La llamada no existe o ya termino");
        }
    }

    /**
     * Al cerrarse una sesion se cuelga su llamada.
     *
     * @param user usuario que se desconecto
     */
    public void endedSession(UserSession user) {
        Call call = lines.get(user);
        if (call != null) {
            finish(call, call.other(user), cb -> cb.onCallEndedAsync(call.id()));
        }
    }

    /**
     * Marca la llamada como contestada: solo el receptor puede hacerlo, una vez.
     *
     * @param callee quien contesta
     * @param callId identificador de la llamada
     * @return la llamada
     */
    private Call answer(UserSession callee, String callId) throws CallException {
        Call call = calls.get(callId);
        if (call == null || call.callee() != callee || !call.answered().compareAndSet(false, true)) {
            throw new CallException("La llamada no existe o ya fue contestada");
        }
        return call;
    }

    /**
     * Termina la llamada, avisa al otro y despues libera a los dos, asi el aviso llega antes que otra llamada.
     *
     * @param call   llamada a terminar
     * @param other  usuario al que se avisa
     * @param notice aviso para other
     * @return false si la llamada ya habia terminado
     */
    private boolean finish(Call call, UserSession other, SessionManager.Callback notice) {
        if (!calls.remove(call.id(), call)) {
            return false;
        }
        System.out.println("[server] llamada de " + call.caller().nickname() + " a " + call.callee().nickname()
                + " terminada");
        sessions.send(other, notice);
        release(call);
        return true;
    }

    /**
     * Quita la llamada y libera la linea de los dos usuarios.
     *
     * @param call llamada a quitar
     */
    private void release(Call call) {
        calls.remove(call.id(), call);
        lines.remove(call.caller(), call);
        lines.remove(call.callee(), call);
    }

    /**
     * Puerto UDP reportado por el cliente.
     *
     * @param endpoint endpoint recibido
     * @return el puerto, si es valido
     */
    private static int port(AudioEndpoint endpoint) throws CallException {
        if (endpoint == null || endpoint.port < 1 || endpoint.port > 65535) {
            throw new CallException("Puerto UDP invalido");
        }
        return endpoint.port;
    }
}
