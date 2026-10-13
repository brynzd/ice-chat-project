package client.core;

import chat.ChunkSize;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 de un archivo: el emisor lo envia en FileInfo y el receptor lo compara al terminar. */
final class Hashes {

    private Hashes() {
    }

    /** Hash en hexadecimal (minusculas), leyendo el archivo por partes para no cargarlo entero en memoria. */
    static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // todo JDK incluye SHA-256
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[ChunkSize.value];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
