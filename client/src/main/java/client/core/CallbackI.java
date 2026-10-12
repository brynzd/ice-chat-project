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
 */
final class CallbackI implements ClientCallback {

    private final ChatListener listener;
    private final Executor events;

    CallbackI(ChatListener listener, Executor events) {
        this.listener = listener;
        this.events = events;
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

    // ---- FileListener (M5) ----

    @Override
    public void onFileStart(String transferId, String from, String room, FileInfo info, Current current) {
    }

    @Override
    public void onFileChunk(FileChunk chunk, Current current) {
    }

    @Override
    public void onFileAborted(String transferId, String reason, Current current) {
    }

    // ---- CallListener (M6 / M7) ----

    @Override
    public void onIncomingCall(String callId, String from, AudioEndpoint callerEndpoint, Current current) {
    }

    @Override
    public void onCallAccepted(String callId, AudioEndpoint calleeEndpoint, Current current) {
    }

    @Override
    public void onCallRejected(String callId, Current current) {
    }

    @Override
    public void onCallEnded(String callId, Current current) {
    }

    @Override
    public void onGroupCallMemberJoined(String room, String nickname, int token, Current current) {
    }

    @Override
    public void onGroupCallMemberLeft(String room, String nickname, Current current) {
    }
}
