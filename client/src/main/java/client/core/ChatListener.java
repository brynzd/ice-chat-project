package client.core;

import chat.TextMessage;

/**
 * Eventos que client.core reporta a la interfaz (CLI hoy, JavaFX en el futuro).
 * Se invocan en orden desde un unico hilo de eventos de client.core (no desde hilos de Ice),
 * asi que la implementacion puede tardar, por ejemplo al imprimir, sin frenar a Ice.
 */
public interface ChatListener {

    void userJoined(String nickname);

    void userLeft(String nickname);

    void privateMessage(TextMessage msg);

    /** Se perdio la conexion con el servidor sin haber hecho logout. */
    void disconnected(String reason);
}
