package client.core;

import chat.AudioEndpoint;
import chat.ClientCallback;
import chat.FileChunk;
import chat.FileInfo;
import chat.TextMessage;
import com.zeroc.Ice.Current;

/** Servant de callback que el servidor invoca; traduce cada llamada a un evento de ChatListener. */
final class CallbackI implements ClientCallback {

    private final ChatListener listener;

    CallbackI(ChatListener listener) {
        this.listener = listener;
    }

    // ---- PresenceListener (RF-01) ----

    @Override
    public void onUserJoined(String nickname, Current current) {
        listener.userJoined(nickname);
    }

    @Override
    public void onUserLeft(String nickname, Current current) {
        listener.userLeft(nickname);
    }

    // ---- MessageListener (RF-02 / RF-03) ----

    @Override
    public void onPrivateMessage(TextMessage msg, Current current) {
        listener.privateMessage(msg);
    }

    @Override
    public void onRoomMessage(TextMessage msg, Current current) {
        // M4
    }

    // ---- RoomListener (M4) ----

    @Override
    public void onMemberJoinedRoom(String room, String nickname, Current current) {
    }

    @Override
    public void onMemberLeftRoom(String room, String nickname, Current current) {
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
