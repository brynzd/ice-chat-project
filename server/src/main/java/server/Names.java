package server;

import chat.InvalidNameException;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Reglas para nicknames (y salas en M4). Sin espacios: la CLI separa "/msg <nick> <texto>"
 * por el primer espacio.
 */
public final class Names {

    public static final int MAX_LENGTH = 20;

    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9_.-]{1," + MAX_LENGTH + "}");

    private Names() {
    }

    public static void validate(String name, String what) throws InvalidNameException {
        if (name == null || !VALID.matcher(name).matches()) {
            throw new InvalidNameException(what + " invalido: '" + name + "'. Use de 1 a " + MAX_LENGTH
                    + " letras, numeros, '.', '_' o '-', sin espacios");
        }
    }

    /** Clave para los mapas: "Bob" y "bob" son el mismo nombre. */
    public static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
