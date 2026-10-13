#pragma once

#include <Types.ice>
#include <Exceptions.ice>
#include <Callbacks.ice>

// Interfaces que implementa el SERVIDOR.
// Flujo: el cliente obtiene el proxy bien conocido "Lobby", llama a login() y recibe
// un Session* propio. Todas las demas operaciones se hacen sobre esa sesion, asi que
// el servidor siempre sabe quien llama y no hace falta enviar el nickname.
module chat
{
    // RF-01 / RF-02: usuarios y mensajeria privada
    interface MessageOps
    {
        idempotent StringSeq listUsers();

        // Retornar sin excepcion = el servidor recibio y despacho el mensaje.
        void sendPrivate(string to, string text) throws UserNotFoundException;
    }

    // RF-03: salas de chat
    interface RoomOps
    {
        // El creador queda unido a la sala automaticamente
        void createRoom(string name) throws RoomAlreadyExistsException, InvalidNameException;
        idempotent RoomInfoSeq listRooms();
        // Unirse a una sala de la que ya se es miembro no hace nada
        void joinRoom(string name) throws RoomNotFoundException;
        void leaveRoom(string name) throws RoomNotFoundException, NotInRoomException;
        idempotent StringSeq listMembers(string name) throws RoomNotFoundException;
        void sendToRoom(string name, string text) throws RoomNotFoundException, NotInRoomException;
    }

    // RF-04: transferencia de archivos por bloques.
    // 1) startFileTo* con los metadatos -> el servidor devuelve un transferId
    // 2) uploadChunk por cada bloque -> el servidor lo reenvia a los destinatarios
    // 3) si el emisor no puede terminar, cancelTransfer -> onFileAborted a los destinatarios
    interface FileOps
    {
        string startFileToUser(string to, FileInfo info)
            throws UserNotFoundException, InvalidNameException, TransferException;
        string startFileToRoom(string room, FileInfo info)
            throws RoomNotFoundException, NotInRoomException, InvalidNameException, TransferException;
        void uploadChunk(FileChunk chunk) throws TransferException;
        void cancelTransfer(string transferId) throws TransferException;
    }

    // RF-05 / RF-06: senalizacion de llamadas. Mute es local al cliente (envia silencio en vez del microfono).
    interface CallOps
    {
        // 1 a 1: cada lado publica su propio endpoint UDP
        string requestCall(string to, AudioEndpoint myEndpoint)
            throws UserNotFoundException, UserBusyException, CallException;
        void acceptCall(string callId, AudioEndpoint myEndpoint) throws CallException;
        void rejectCall(string callId) throws CallException;
        // Sirve tambien para cancelar una llamada que aun esta timbrando
        void hangup(string callId) throws CallException;

        // Grupal: el audio pasa por el relay UDP del servidor
        GroupCallInfo joinGroupCall(string room, AudioEndpoint myEndpoint)
            throws RoomNotFoundException, NotInRoomException, CallException;
        void leaveGroupCall(string room) throws CallException;
    }

    // Sesion por usuario, creada en login()
    interface Session extends MessageOps, RoomOps, FileOps, CallOps
    {
        // Cierre ordenado: el servidor libera la sesion, el callback, salas y llamadas
        void logout();
    }

    // Unico objeto con identidad conocida en el adaptador del servidor
    interface Lobby
    {
        Session* login(string nickname, ClientCallback* callback)
            throws NicknameTakenException, InvalidNameException;
    }
}
