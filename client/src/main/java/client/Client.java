package client;

import java.util.Scanner;

public class Client {

    public static void main(String[] args) {
        System.out.println("[client] skeleton OK - escribe algo (o /quit):");

        // Solo verifica que runClient recibe la entrada de la consola.
        // TODO (M2): reemplazar por la CLI real (paquete client.cli),
        //  que delega la logica en client.core.
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
