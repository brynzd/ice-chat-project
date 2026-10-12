#pragma once

// Tipos de datos compartidos por todo el contrato (sin operaciones).
module chat
{
    // Tamano de bloque para la transferencia de archivos: 64 KB, muy por debajo
    // del limite por defecto de Ice.MessageSizeMax (1 MB).
    const int ChunkSize = 65536;

    // Formato de audio comun a cliente, servidor y relay (javax.sound.sampled.AudioFormat):
    // PCM con signo, 16 kHz, 16 bits, mono, little-endian.
    const int AudioSampleRate = 16000;
    const int AudioSampleSizeBits = 16;
    const int AudioChannels = 1;
    const bool AudioBigEndian = false;
    // Bytes de audio por datagrama: 20 ms = 16000 * 2 bytes * 0.02 s.
    // En llamadas grupales cada datagrama lleva ademas el token (int, 4 bytes) al inicio.
    const int AudioPacketBytes = 640;

    sequence<string> StringSeq;
    sequence<byte> ByteSeq;

    // Mensaje de texto. room vacio = mensaje privado (1 a 1).
    struct TextMessage
    {
        string from;
        string room;
        string text;
        long timestamp; // epoch en milisegundos, asignado por el servidor
    }

    struct RoomInfo
    {
        string name;
        int memberCount;
        bool hasActiveCall; // hay una llamada grupal en curso en la sala
    }
    sequence<RoomInfo> RoomInfoSeq;

    // Metadatos de un archivo; se envian una sola vez antes de los bloques.
    struct FileInfo
    {
        string fileName;  // solo el nombre; el receptor descarta cualquier ruta
        long totalSize;   // bytes
        int chunkSize;    // bytes por bloque (normalmente ChunkSize)
        int totalChunks;  // 0 para un archivo vacio: queda completo al recibir onFileStart
        string sha256;    // hash del archivo completo en hexadecimal, para verificar la reconstruccion
    }

    // Un bloque del archivo. El receptor lo escribe en index * chunkSize,
    // asi que el orden de llegada no importa.
    struct FileChunk
    {
        string transferId;
        int index;
        ByteSeq data;
    }

    // Direccion UDP donde un cliente (o el relay del servidor) recibe audio.
    struct AudioEndpoint
    {
        string host;
        int port;
    }

    // Participante de una llamada grupal. El relay antepone el token del emisor a cada
    // datagrama reenviado, asi el receptor sabe de quien es cada paquete.
    struct GroupCallMember
    {
        string nickname;
        int token;
    }
    sequence<GroupCallMember> GroupCallMemberSeq;

    // Respuesta al unirse a una llamada grupal: a donde enviar el audio, el token que
    // el cliente antepone a cada datagrama y quienes estaban ya en la llamada.
    struct GroupCallInfo
    {
        AudioEndpoint relay;
        int token;
        GroupCallMemberSeq participants; // sin incluir al que se une
    }
}
