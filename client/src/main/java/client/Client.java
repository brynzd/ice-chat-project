package client;

import chat.LobbyPrx;
import client.cli.Cli;
import client.core.ChatClient;
import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.ObjectAdapter;
import com.zeroc.Ice.Util;

import java.util.ArrayList;
import java.util.List;

public class Client {

    public static void main(String[] args) {
        List<String> extraArgs = new ArrayList<>();
        // config.client: proxy del Lobby, adaptador de callbacks, hilos y heartbeats (ACM)
        try (Communicator communicator = Util.initialize(args, "config.client", extraArgs)) {
            // Permite sobrescribir cualquier propiedad con --Nombre=valor (p. ej. --Lobby.Proxy=...)
            communicator.getProperties().parseCommandLineOptions("", extraArgs.toArray(new String[0]));

            // Adaptador de los callbacks, sin endpoints: el servidor los invoca por la conexion
            // del login (conexion bidireccional, ver ChatClient.login)
            ObjectAdapter callbackAdapter = communicator.createObjectAdapter("");
            callbackAdapter.activate();

            LobbyPrx lobby = LobbyPrx.uncheckedCast(communicator.propertyToProxy("Lobby.Proxy"));
            Cli cli = new Cli();
            ChatClient chat = new ChatClient(callbackAdapter, lobby, cli);

            // Ctrl+C: logout ordenado y luego cierre del communicator
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                chat.logout();
                communicator.destroy();
            }));

            cli.run(chat);
        }
    }
}
