#pragma once

// Jerarquia de excepciones del chat. Todas heredan de ChatException, de modo que
// el cliente puede capturar una especifica o todas a la vez.
module chat
{
    exception ChatException
    {
        string reason;
    }

    // Nombre vacio o con caracteres no permitidos (nickname, sala o archivo)
    exception InvalidNameException extends ChatException {}

    // Sesion (RF-01)
    exception NicknameTakenException extends ChatException {}

    // Mensajeria y salas (RF-02, RF-03)
    exception UserNotFoundException extends ChatException {}
    exception RoomNotFoundException extends ChatException {}
    exception RoomAlreadyExistsException extends ChatException {}
    exception NotInRoomException extends ChatException {}

    // Archivos (RF-04): transferId desconocido, indice fuera de rango o metadatos invalidos
    exception TransferException extends ChatException {}

    // Llamadas (RF-05, RF-06): llamada inexistente o no participante
    exception CallException extends ChatException {}

    // El destinatario ya esta en otra llamada
    exception UserBusyException extends CallException {}
}
