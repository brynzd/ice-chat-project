package client.core;

import chat.AudioEndpoint;
import chat.ClientCallback;
import chat.FileChunk;
import chat.FileInfo;
import chat.TextMessage;
import com.zeroc.Ice.Current;

import java.util.concurrent.Executor;

/**
 * Servant de callback que el servidor invoca; traduce cada llamada a un evento de ChatListener.
 * Solo encola el evento en el hilo de eventos y retorna: una interfaz lenta no frena a Ice.
 * La excepcion son los archivos, que se escriben en disco aqui (ver FileTransfers).
 */
final class CallbackI implements ClientCallback {

    private final ChatListener listener;
    private final Executor events;
    private final FileTransfers files;
    private final Calls calls;

    CallbackI(ChatListener listener, Executor events, FileTransfers files, Calls calls) {
        this.listener = listener;
        this.events = events;
        this.files = files;
        this.calls = calls;
    }

    // ---- PresenceListener (RF-01) ----

    @Override
    public void onUserJoined(String nickname, Current current) {
        events.execute(() -> listener.userJoined(nickname));
    }

    @Override
    public void onUserLeft(String nickname, Current current) {
        events.execute(() -> listener.userLeft(nickname));
    }

    // ---- MessageListener (RF-02 / RF-03) ----

    @Override
    public void onPrivateMessage(TextMessage msg, Current current) {
        events.execute(() -> listener.privateMessage(msg));
    }

    @Override
    public void onRoomMessage(TextMessage msg, Current current) {
        events.execute(() -> listener.roomMessage(msg));
    }

    // ---- RoomListener (RF-03) ----

    @Override
    public void onMemberJoinedRoom(String room, String nickname, Current current) {
        events.execute(() -> listener.memberJoinedRoom(room, nickname));
    }

    @Override
    public void onMemberLeftRoom(String room, String nickname, Current current) {
        events.execute(() -> listener.memberLeftRoom(room, nickname));
    }

    // ---- FileListener (RF-04) ----

    @Override
    public void onFileStart(String transferId, String from, String room, FileInfo info, Current current) {
        files.started(transferId, from, room, info);
    }

    // Se escribe en disco en este hilo de Ice: el servidor espera esta respuesta antes de enviar
    // el siguiente bloque, asi los bloques nunca se acumulan en memoria
    @Override
    public void onFileChunk(FileChunk chunk, Current current) {
        files.chunk(chunk);
    }

    @Override
    public void onFileAborted(String transferId, String reason, Current current) {
        files.aborted(transferId, reason);
    }

    // ---- CallListener (RF-05 / RF-06) ----

    @Override
    public void onIncomingCall(String callId, String from, AudioEndpoint callerEndpoint, Current current) {
        events.execute(() -> calls.incoming(callId, from, callerEndpoint));
    }

    @Override
    public void onCallAccepted(String callId, AudioEndpoint calleeEndpoint, Current current) {
        events.execute(() -> calls.accepted(callId, calleeEndpoint));
    }

    @Override
    public void onCallRejected(String callId, Current current) {
        events.execute(() -> calls.ended(callId, "rechazo la llamada"));
    }

    @Override
    public void onCallEnded(String callId, Current current) {
        events.execute(() -> calls.ended(callId, "termino la llamada"));
    }

    @Override
    public void onGroupCallMemberJoined(String room, String nickname, int token, Current current) {
        events.execute(() -> listener.memberJoinedCall(room, nickname));
    }

    @Override
    public void onGroupCallMemberLeft(String room, String nickname, Current current) {
        events.execute(() -> listener.memberLeftCall(room, nickname));
    }
}
