package client.core;

import chat.TextMessage;

/**
 * Eventos que client.core reporta a la interfaz (CLI hoy, JavaFX en el futuro).
 * Se invocan desde hilos de Ice: la implementacion no debe bloquear.
 */
public interface ChatListener {

    void userJoined(String nickname);

    void userLeft(String nickname);

    void privateMessage(TextMessage msg);

    /** Se perdio la conexion con el servidor sin haber hecho logout. */
    void disconnected(String reason);
}
