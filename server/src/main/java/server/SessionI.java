package server;

import chat.AudioEndpoint;
import chat.FileChunk;
import chat.FileInfo;
import chat.GroupCallInfo;
import chat.RoomInfo;
import chat.Session;
import chat.TextMessage;
import chat.UserNotFoundException;
import com.zeroc.Ice.Current;
import com.zeroc.Ice.OperationNotExistException;

/** Servant Session de un usuario: todas sus operaciones saben quien llama. */
public final class SessionI implements Session {

    private final UserSession user;
    private final SessionManager sessions;

    public SessionI(UserSession user, SessionManager sessions) {
        this.user = user;
        this.sessions = sessions;
    }

    @Override
    public void logout(Current current) {
        sessions.end(user, "logout");
    }

    // ---- MessageOps (RF-01 / RF-02) ----

    @Override
    public String[] listUsers(Current current) {
        return sessions.nicknames().toArray(new String[0]);
    }

    @Override
    public void sendPrivate(String to, String text, Current current) throws UserNotFoundException {
        UserSession target = sessions.find(to);
        if (target == null) {
            throw new UserNotFoundException("El usuario '" + to + "' no existe o no esta conectado");
        }
        if (target == user) {
            throw new UserNotFoundException("No puedes enviarte mensajes a ti mismo");
        }
        TextMessage msg = new TextMessage(user.nickname(), "", text, System.currentTimeMillis());
        sessions.send(target, cb -> cb.onPrivateMessageAsync(msg));
        // Retornar sin excepcion confirma al emisor que el servidor recibio y despacho el mensaje
    }

    // ---- RoomOps (M4) ----

    @Override
    public void createRoom(String name, Current current) {
        throw notYet(current);
    }

    @Override
    public RoomInfo[] listRooms(Current current) {
        throw notYet(current);
    }

    @Override
    public void joinRoom(String name, Current current) {
        throw notYet(current);
    }

    @Override
    public void leaveRoom(String name, Current current) {
        throw notYet(current);
    }

    @Override
    public String[] listMembers(String name, Current current) {
        throw notYet(current);
    }

    @Override
    public void sendToRoom(String name, String text, Current current) {
        throw notYet(current);
    }

    // ---- FileOps (M5) ----

    @Override
    public String startFileToUser(String to, FileInfo info, Current current) {
        throw notYet(current);
    }

    @Override
    public String startFileToRoom(String room, FileInfo info, Current current) {
        throw notYet(current);
    }

    @Override
    public void uploadChunk(FileChunk chunk, Current current) {
        throw notYet(current);
    }

    @Override
    public void cancelTransfer(String transferId, Current current) {
        throw notYet(current);
    }

    // ---- CallOps (M6 / M7) ----

    @Override
    public String requestCall(String to, AudioEndpoint myEndpoint, Current current) {
        throw notYet(current);
    }

    @Override
    public void acceptCall(String callId, AudioEndpoint myEndpoint, Current current) {
        throw notYet(current);
    }

    @Override
    public void rejectCall(String callId, Current current) {
        throw notYet(current);
    }

    @Override
    public void hangup(String callId, Current current) {
        throw notYet(current);
    }

    @Override
    public GroupCallInfo joinGroupCall(String room, AudioEndpoint myEndpoint, Current current) {
        throw notYet(current);
    }

    @Override
    public void leaveGroupCall(String room, Current current) {
        throw notYet(current);
    }

    // Operacion declarada en el contrato pero aun no implementada
    private static OperationNotExistException notYet(Current current) {
        return new OperationNotExistException(current.id, current.facet, current.operation);
    }
}
