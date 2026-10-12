package server;

import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.ObjectAdapter;
import com.zeroc.Ice.Util;

import java.util.ArrayList;
import java.util.List;

public class Server {

    public static void main(String[] args) {
        List<String> extraArgs = new ArrayList<>();
        // config.server: endpoints, hilos, MessageSizeMax y heartbeats (ACM)
        try (Communicator communicator = Util.initialize(args, "config.server", extraArgs)) {
            // Permite sobrescribir cualquier propiedad con --Nombre=valor (p. ej. --ChatAdapter.Endpoints=...)
            communicator.getProperties().parseCommandLineOptions("", extraArgs.toArray(new String[0]));
            // Ctrl+C cierra el communicator de forma ordenada
            Runtime.getRuntime().addShutdownHook(new Thread(communicator::destroy));

            ObjectAdapter adapter = communicator.createObjectAdapter("ChatAdapter");

            SessionManager sessions = new SessionManager(adapter);
            RoomManager rooms = new RoomManager(sessions);
            FileTransfers files = new FileTransfers(sessions, rooms);
            // Al cerrarse una sesion (logout, conexion perdida o callback fallido) se limpia lo del usuario
            sessions.addEndListener(files::endedSession);
            sessions.addEndListener(rooms::leaveAll);

            // Objeto bien conocido "Lobby": los clientes llegan a el con Lobby.Proxy (config.client)
            adapter.add(new LobbyI(sessions, user -> new SessionI(user, sessions, rooms, files)),
                    Util.stringToIdentity("Lobby"));

            adapter.activate();
            System.out.println("[server] escuchando en "
                    + communicator.getProperties().getProperty("ChatAdapter.Endpoints"));
            communicator.waitForShutdown();
        }
    }
}
