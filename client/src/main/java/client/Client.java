package client;

import chat.LobbyPrx;
import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.ObjectAdapter;
import com.zeroc.Ice.Util;

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class Client {

    public static void main(String[] args) {
        List<String> extraArgs = new ArrayList<>();
        // config.client: proxy del Lobby, adaptador de callbacks, hilos y heartbeats (ACM)
        try (Communicator communicator = Util.initialize(args, "config.client", extraArgs)) {
            // Permite sobrescribir cualquier propiedad con --Nombre=valor (p. ej. --Lobby.Proxy=...)
            communicator.getProperties().parseCommandLineOptions("", extraArgs.toArray(new String[0]));
            // Ctrl+C cierra el communicator de forma ordenada
            Runtime.getRuntime().addShutdownHook(new Thread(communicator::destroy));

            // Adaptador donde el servidor invoca los callbacks
            ObjectAdapter callbackAdapter = communicator.createObjectAdapter("Callback");
            callbackAdapter.activate();

            LobbyPrx lobby = LobbyPrx.uncheckedCast(communicator.propertyToProxy("Lobby.Proxy"));
            System.out.println("[client] Lobby: " + lobby);

            // TODO (M2): registrar el servant de ClientCallback en callbackAdapter
            //  y llamar lobby.login(nickname, callbackPrx) para obtener el Session*.
            echoLoop();
        }
    }

    // Solo verifica que runClient recibe la entrada de la consola.
    // TODO (M2): reemplazar por la CLI real (paquete client.cli),
    //  que delega la logica en client.core.
    private static void echoLoop() {
        System.out.println("[client] skeleton OK - escribe algo (o /quit):");
        try (Scanner in = new Scanner(System.in)) {
            while (in.hasNextLine()) {
                String line = in.nextLine();
                if (line.equals("/quit")) {
                    break;
                }
                System.out.println("[echo] " + line);
            }
        }
    }
}
