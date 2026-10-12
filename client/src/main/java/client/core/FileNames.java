package client.core;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Nombres de archivo recibidos de otros usuarios. Se guardan solo como nombre dentro de la
 * carpeta de descargas, nunca como ruta: "../../x" o "C:\\Windows\\x" quedan en "x".
 */
public final class FileNames {

    private static final String FALLBACK = "archivo";

    // Nombres que Windows reserva para dispositivos, con o sin extension
    private static final Set<String> RESERVED = new HashSet<>(Set.of("CON", "PRN", "AUX", "NUL"));

    static {
        for (int i = 1; i <= 9; i++) {
            RESERVED.add("COM" + i);
            RESERVED.add("LPT" + i);
        }
    }

    private FileNames() {
    }

    /** Nombre seguro para guardar en disco a partir del fileName recibido en FileInfo. */
    public static String safeName(String received) {
        String name = received == null ? "" : received;
        // Quita cualquier ruta, con separadores de Windows o de Unix
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        // Caracteres que Windows no permite en nombres de archivo
        name = name.replaceAll("[<>:\"|?*\\x00-\\x1F]", "_");
        // Windows descarta puntos y espacios finales; esto tambien elimina "." y ".."
        name = name.replaceAll("[. ]+$", "");
        if (name.isEmpty()) {
            return FALLBACK;
        }
        int dot = name.indexOf('.');
        String base = dot < 0 ? name : name.substring(0, dot);
        if (RESERVED.contains(base.toUpperCase(Locale.ROOT))) {
            name = "_" + name;
        }
        return name;
    }
}
