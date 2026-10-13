package server;

import chat.AudioEndpoint;
import chat.CallException;
import chat.FileChunk;
import chat.FileInfo;
import chat.GroupCallInfo;
import chat.InvalidNameException;
import chat.NotInRoomException;
import chat.RoomAlreadyExistsException;
import chat.RoomInfo;
import chat.RoomNotFoundException;
import chat.Session;
import chat.TextMessage;
import chat.TransferException;
import chat.UserBusyException;
import chat.UserNotFoundException;
import com.zeroc.Ice.Current;
import com.zeroc.Ice.OperationNotExistException;

/** Servant Session de un usuario: todas sus operaciones saben quien llama. */
public final class SessionI implements Session {

    private final UserSession user;
    private final SessionManager sessions;
    private final RoomManager rooms;
    private final FileTransfers files;
    private final Calls calls;

    public SessionI(UserSession user, SessionManager sessions, RoomManager rooms, FileTransfers files, Calls calls) {
        this.user = user;
        this.sessions = sessions;
        this.rooms = rooms;
        this.files = files;
        this.calls = calls;
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

    // ---- RoomOps (RF-03) ----

    @Override
    public void createRoom(String name, Current current) throws RoomAlreadyExistsException, InvalidNameException {
        rooms.create(user, name);
    }

    @Override
    public RoomInfo[] listRooms(Current current) {
        return rooms.list();
    }

    @Override
    public void joinRoom(String name, Current current) throws RoomNotFoundException {
        rooms.join(user, name);
    }

    @Override
    public void leaveRoom(String name, Current current) throws RoomNotFoundException, NotInRoomException {
        rooms.leave(user, name);
    }

    @Override
    public String[] listMembers(String name, Current current) throws RoomNotFoundException {
        return rooms.members(name);
    }

    @Override
    public void sendToRoom(String name, String text, Current current) throws RoomNotFoundException, NotInRoomException {
        // Retornar sin excepcion confirma al emisor que el servidor recibio y difundio el mensaje
        rooms.send(user, name, text);
    }

    // ---- FileOps (RF-04) ----

    @Override
    public String startFileToUser(String to, FileInfo info, Current current)
            throws UserNotFoundException, InvalidNameException, TransferException {
        return files.startToUser(user, to, info);
    }

    @Override
    public String startFileToRoom(String room, FileInfo info, Current current)
            throws RoomNotFoundException, NotInRoomException, InvalidNameException, TransferException {
        return files.startToRoom(user, room, info);
    }

    @Override
    public void uploadChunk(FileChunk chunk, Current current) throws TransferException {
        files.upload(user, chunk);
    }

    @Override
    public void cancelTransfer(String transferId, Current current) throws TransferException {
        files.cancel(user, transferId);
    }

    // ---- CallOps (RF-05 / RF-06) ----

    @Override
    public String requestCall(String to, AudioEndpoint myEndpoint, Current current)
            throws UserNotFoundException, UserBusyException, CallException {
        return calls.request(user, to, myEndpoint);
    }

    @Override
    public void acceptCall(String callId, AudioEndpoint myEndpoint, Current current) throws CallException {
        calls.accept(user, callId, myEndpoint);
    }

    @Override
    public void rejectCall(String callId, Current current) throws CallException {
        calls.reject(user, callId);
    }

    @Override
    public void hangup(String callId, Current current) throws CallException {
        calls.hangup(user, callId);
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
