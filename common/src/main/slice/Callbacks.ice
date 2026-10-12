#pragma once

#include <Types.ice>

// Interfaces que implementa el CLIENTE. El servidor las invoca para notificar
// eventos de forma asincrona (push), sin que el cliente tenga que preguntar.
module chat
{
    // RF-01: presencia en tiempo real. No se notifica al propio usuario que entra o sale.
    interface PresenceListener
    {
        void onUserJoined(string nickname);
        void onUserLeft(string nickname);
    }

    // RF-02 y RF-03: mensajes entrantes
    interface MessageListener
    {
        void onPrivateMessage(TextMessage msg);
        void onRoomMessage(TextMessage msg);
    }

    // RF-03: cambios de membresia en las salas a las que pertenece el cliente
    interface RoomListener
    {
        void onMemberJoinedRoom(string room, string nickname);
        void onMemberLeftRoom(string room, string nickname);
    }

    // RF-04: recepcion de archivos. room vacio = archivo privado.
    interface FileListener
    {
        // Si info.totalChunks es 0 el archivo esta completo (vacio) y no llegan bloques
        void onFileStart(string transferId, string from, string room, FileInfo info);
        void onFileChunk(FileChunk chunk);

        // El emisor cancelo o se desconecto: el receptor descarta el archivo parcial
        void onFileAborted(string transferId, string reason);
    }

    // RF-05 y RF-06: senalizacion de llamadas (el audio viaja por UDP, no por aqui)
    interface CallListener
    {
        void onIncomingCall(string callId, string from, AudioEndpoint callerEndpoint);
        void onCallAccepted(string callId, AudioEndpoint calleeEndpoint);
        void onCallRejected(string callId);
        // Tambien llega al receptor si el emisor cuelga antes de que conteste
        void onCallEnded(string callId);

        // token: identifica los datagramas de este participante que reenvia el relay
        void onGroupCallMemberJoined(string room, string nickname, int token);
        void onGroupCallMemberLeft(string room, string nickname);
    }

    // Objeto de retorno que el cliente registra en login(): un solo servant
    // que implementa todos los listeners.
    interface ClientCallback extends PresenceListener, MessageListener, RoomListener,
                                     FileListener, CallListener
    {
    }
}
