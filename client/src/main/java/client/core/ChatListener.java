package client.core;

import chat.TextMessage;

import java.nio.file.Path;

/**
 * Eventos que client.core reporta a la interfaz (CLI hoy, JavaFX en el futuro).
 * Se invocan en orden desde un unico hilo de eventos de client.core (no desde hilos de Ice),
 * asi que la implementacion puede tardar, por ejemplo al imprimir, sin frenar a Ice.
 */
public interface ChatListener {

    void userJoined(String nickname);

    void userLeft(String nickname);

    void privateMessage(TextMessage msg);

    /** Mensaje de una sala de la que el usuario es miembro (msg.room). */
    void roomMessage(TextMessage msg);

    void memberJoinedRoom(String room, String nickname);

    void memberLeftRoom(String room, String nickname);

    /** Alguien empezo a enviarte un archivo (room vacio = privado). */
    void fileIncoming(String from, String room, String fileName, long size);

    /** Avance de un archivo que estas enviando (25, 50 o 75 %). */
    void fileProgress(String fileName, int percent);

    /** Archivo recibido completo y verificado con su SHA-256. */
    void fileReceived(String from, String room, Path file);

    /** Un archivo que estabas recibiendo se cancelo, llego danado o no se pudo guardar. */
    void fileFailed(String fileName, String reason);

    /** Se perdio la conexion con el servidor sin haber hecho logout. */
    void disconnected(String reason);
}
