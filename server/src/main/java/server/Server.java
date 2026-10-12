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

            // TODO (M2): registrar el servant del Lobby con la identidad bien conocida "Lobby":
            //  adapter.add(new LobbyI(...), Util.stringToIdentity("Lobby"));
            //  Ver IceSupport para callbacks, sesiones y deteccion de clientes caidos.

            adapter.activate();
            System.out.println("[server] escuchando en "
                    + communicator.getProperties().getProperty("ChatAdapter.Endpoints"));
            communicator.waitForShutdown();
        }
    }
}
