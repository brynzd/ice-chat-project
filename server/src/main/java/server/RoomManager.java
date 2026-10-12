package server;

import chat.InvalidNameException;
import chat.NotInRoomException;
import chat.RoomAlreadyExistsException;
import chat.RoomInfo;
import chat.RoomNotFoundException;
import chat.TextMessage;
import com.zeroc.Ice.ObjectNotExistException;

import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Salas de chat (RF-03). Las salas no se borran al quedar vacias: el mapa solo crece, asi una
 * sala obtenida con get() nunca queda obsoleta y no hay carrera entre "unirse" y "borrar".
 */
public final class RoomManager {

    // clave: Names.key(nombre de la sala)
    private final ConcurrentMap<String, Room> rooms = new ConcurrentHashMap<>();
    private final SessionManager sessions;

    public RoomManager(SessionManager sessions) {
        this.sessions = sessions;
    }

    /** Crea la sala y une al creador. */
    public void create(UserSession user, String name) throws RoomAlreadyExistsException, InvalidNameException {
        Names.validate(name, "Nombre de sala");
        Room room = new Room(name);
        // putIfAbsent es atomico: si dos usuarios crean "General" y "general" a la vez, solo uno gana
        Room existing = rooms.putIfAbsent(Names.key(name), room);
        if (existing != null) {
            throw new RoomAlreadyExistsException("La sala '" + existing.name() + "' ya existe");
        }
        addMember(room, user);
        System.out.println("[server] " + user.nickname() + " creo la sala " + name);
    }

    /** Une al usuario a la sala; si ya era miembro no hace nada. */
    public void join(UserSession user, String name) throws RoomNotFoundException {
        Room room = find(name);
        if (addMember(room, user)) {
            System.out.println("[server] " + user.nickname() + " se unio a " + room.name());
            notifyMembers(room, user, cb -> cb.onMemberJoinedRoomAsync(room.name(), user.nickname()));
        }
    }

    public void leave(UserSession user, String name) throws RoomNotFoundException, NotInRoomException {
        Room room = find(name);
        if (!room.members().remove(user)) {
            throw new NotInRoomException("No estas en la sala '" + room.name() + "'");
        }
        memberLeft(room, user);
    }

    /** Saca al usuario de todas sus salas. Se ejecuta al cerrarse su sesion. */
    public void leaveAll(UserSession user) {
        for (Room room : rooms.values()) {
            if (room.members().remove(user)) {
                memberLeft(room, user);
            }
        }
    }

    /** Catalogo de salas en orden alfabetico. */
    public RoomInfo[] list() {
        return rooms.values().stream()
                .map(room -> new RoomInfo(room.name(), room.members().size(), false)) // M7: llamada activa
                .sorted(Comparator.comparing(info -> Names.key(info.name)))
                .toArray(RoomInfo[]::new);
    }

    /** Nicknames de los miembros en orden alfabetico (el catalogo es publico: no hace falta ser miembro). */
    public String[] members(String name) throws RoomNotFoundException {
        return find(name).members().stream()
                .map(UserSession::nickname)
                .sorted(Comparator.comparing(Names::key))
                .toArray(String[]::new);
    }

    /** Difunde el mensaje a todos los miembros menos al emisor (sin eco). */
    public void send(UserSession from, String name, String text) throws RoomNotFoundException, NotInRoomException {
        Room room = find(name);
        if (!room.members().contains(from)) {
            throw new NotInRoomException("No estas en la sala '" + room.name() + "'. Usa /join " + room.name());
        }
        TextMessage msg = new TextMessage(from.nickname(), room.name(), text, System.currentTimeMillis());
        // Solo los miembros de esta sala reciben el mensaje: las salas quedan aisladas entre si
        notifyMembers(room, from, cb -> cb.onRoomMessageAsync(msg));
    }

    /**
     * Sala con ese nombre. Un nombre invalido tampoco existe: joinRoom, leaveRoom, listMembers y
     * sendToRoom solo declaran RoomNotFoundException (otra excepcion le llegaria al cliente como
     * UnknownUserException).
     */
    public Room find(String name) throws RoomNotFoundException {
        Room room = name == null ? null : rooms.get(Names.key(name));
        if (room == null) {
            throw new RoomNotFoundException("La sala '" + name + "' no existe. Usa /rooms para ver las salas");
        }
        return room;
    }

    /**
     * Agrega al usuario; devuelve false si ya era miembro. Si su sesion se cerro mientras tanto,
     * deshace el cambio: leaveAll pudo haber corrido antes de este add y no lo veria.
     */
    private boolean addMember(Room room, UserSession user) {
        if (!room.members().add(user)) {
            return false;
        }
        if (user.isClosed()) {
            room.members().remove(user);
            throw new ObjectNotExistException(); // el cliente lo muestra como "el servidor cerro tu sesion"
        }
        return true;
    }

    private void memberLeft(Room room, UserSession user) {
        System.out.println("[server] " + user.nickname() + " salio de " + room.name());
        notifyMembers(room, user, cb -> cb.onMemberLeftRoomAsync(room.name(), user.nickname()));
    }

    /** Envia el callback a los miembros de la sala menos a except. */
    private void notifyMembers(Room room, UserSession except, SessionManager.Callback call) {
        for (UserSession member : room.members()) {
            if (member != except) {
                sessions.send(member, call);
            }
        }
    }
}
