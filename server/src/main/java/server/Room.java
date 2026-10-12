package server;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sala de chat: nombre visible (con las mayusculas de quien la creo) y miembros.
 * El conjunto de miembros es concurrente: lo modifican y recorren varios hilos a la vez.
 */
public record Room(String name, Set<UserSession> members) {

    public Room(String name) {
        this(name, ConcurrentHashMap.newKeySet());
    }
}
