package client.core;

import chat.ChatException;
import chat.ChunkSize;
import chat.FileChunk;
import chat.FileInfo;
import chat.SessionPrx;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Envio y recepcion de archivos por bloques (RF-04).
 *
 * Envio: en un hilo propio ("file-upload"), asi la CLI sigue respondiendo. Calcula el SHA-256,
 * llama a startFileTo* y luego a uploadChunk por cada bloque, uno a la vez: cada llamada retorna
 * cuando los destinatarios ya recibieron el bloque.
 *
 * Recepcion: cada bloque se escribe en disco dentro del hilo de Ice que entrega el callback.
 * Asi el servidor no envia el siguiente bloque hasta que este quedo en disco, y no se acumulan
 * bloques en memoria. Al terminar se verifica el tamano y el SHA-256 en el hilo de eventos.
 */
final class FileTransfers {

    /** Carpeta de descargas, relativa a la carpeta de trabajo (client/ con gradlew runClient). */
    static final Path DOWNLOADS = Path.of("downloads");

    /** Archivo que se esta recibiendo; part es el archivo temporal hasta verificarlo. */
    private record Incoming(String from, String room, FileInfo info, Path part, FileChannel channel,
                            AtomicInteger received) {
    }

    private final ConcurrentMap<String, Incoming> incoming = new ConcurrentHashMap<>();
    private final ChatListener listener;
    private final Executor events;
    private final Supplier<String> nickname;
    private final ExecutorService uploads = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "file-upload");
        t.setDaemon(true);
        return t;
    });

    FileTransfers(ChatListener listener, Executor events, Supplier<String> nickname) {
        this.listener = listener;
        this.events = events;
        this.nickname = nickname;
    }

    // ---- Envio ----

    /** Encola el envio; target es un nickname o "#sala". Los envios salen de a uno, en orden. */
    CompletableFuture<Void> send(SessionPrx session, String target, Path file) {
        return CompletableFuture.runAsync(() -> {
            try {
                upload(session, target, file);
            } catch (ChatException | IOException e) {
                throw new CompletionException(e);
            }
        }, uploads);
    }

    private void upload(SessionPrx session, String target, Path file) throws ChatException, IOException {
        String name = file.getFileName().toString();
        long size = Files.size(file);
        int totalChunks = (int) ((size + ChunkSize.value - 1) / ChunkSize.value);
        FileInfo info = new FileInfo(name, size, ChunkSize.value, totalChunks, Hashes.sha256(file));

        String id = target.startsWith("#")
                ? session.startFileToRoom(target.substring(1), info)
                : session.startFileToUser(target, info);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            int reportedQuarter = 0;
            for (int index = 0; index < totalChunks; index++) {
                long offset = (long) index * ChunkSize.value;
                ByteBuffer data = ByteBuffer.allocate((int) Math.min(ChunkSize.value, size - offset));
                while (data.hasRemaining()) {
                    if (channel.read(data, offset + data.position()) < 0) {
                        throw new IOException("el archivo cambio mientras se enviaba");
                    }
                }
                session.uploadChunk(new FileChunk(id, index, data.array()));

                // Avisa el avance al 25, 50 y 75 %, solo en archivos de 1 MB o mas
                int quarter = (index + 1) * 4 / totalChunks;
                if (totalChunks >= 16 && quarter > reportedQuarter && quarter < 4) {
                    reportedQuarter = quarter;
                    events.execute(() -> listener.fileProgress(name, quarter * 25));
                }
            }
        } catch (IOException | RuntimeException e) {
            // Fallo local (lectura del archivo, sesion perdida, ...): los receptores descartan lo recibido
            cancelQuietly(session, id);
            throw e;
        }
    }

    private static void cancelQuietly(SessionPrx session, String id) {
        try {
            session.cancelTransfer(id);
        } catch (ChatException | RuntimeException ignored) {
            // el servidor ya la cerro o no se puede contactar
        }
    }

    // ---- Recepcion (hilo de Ice) ----

    void started(String id, String from, String room, FileInfo info) {
        String me = nickname.get();
        if (me == null) {
            return; // llego despues de cerrar la sesion
        }
        String name = FileNames.safeName(info.fileName);
        events.execute(() -> listener.fileIncoming(from, room, name, info.totalSize));
        try {
            Path folder = Files.createDirectories(DOWNLOADS.resolve(FileNames.safeName(me)));
            Path part = folder.resolve(name + "." + id.substring(0, 8) + ".part");
            FileChannel channel = FileChannel.open(part, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            incoming.put(id, new Incoming(from, room, info, part, channel, new AtomicInteger()));
            if (info.totalChunks == 0) {
                complete(id); // archivo vacio: no llegan bloques
            }
        } catch (IOException e) {
            events.execute(() -> listener.fileFailed(name, "no se pudo crear el archivo: " + e.getMessage()));
        }
    }

    void chunk(FileChunk chunk) {
        Incoming file = incoming.get(chunk.transferId);
        if (file == null) {
            return; // abortado o desconocido
        }
        try {
            // Cada bloque va en index * chunkSize: el orden de llegada no importa
            ByteBuffer data = ByteBuffer.wrap(chunk.data);
            long position = (long) chunk.index * file.info().chunkSize;
            while (data.hasRemaining()) {
                position += file.channel().write(data, position);
            }
            if (file.received().incrementAndGet() == file.info().totalChunks) {
                complete(chunk.transferId);
            }
        } catch (IOException e) {
            discard(chunk.transferId, "error al escribir en disco: " + e.getMessage());
        }
    }

    void aborted(String id, String reason) {
        discard(id, "envio cancelado (" + reason + ")");
    }

    /** Descarta los archivos a medio recibir (logout o sesion perdida). */
    void discardAll() {
        for (String id : incoming.keySet()) {
            discard(id, "se cerro la sesion");
        }
    }

    private void complete(String id) throws IOException {
        Incoming file = incoming.remove(id);
        if (file == null) {
            return;
        }
        file.channel().close();
        events.execute(() -> verifyAndSave(file)); // leer el archivo para el hash puede tardar
    }

    private void discard(String id, String reason) {
        Incoming file = incoming.remove(id);
        if (file == null) {
            return; // ya se completo o descarto
        }
        try {
            file.channel().close();
            Files.deleteIfExists(file.part());
        } catch (IOException ignored) {
            // solo queda un .part sin borrar
        }
        String name = FileNames.safeName(file.info().fileName);
        events.execute(() -> listener.fileFailed(name, reason));
    }

    // ---- Verificacion (hilo de eventos) ----

    private void verifyAndSave(Incoming file) {
        String name = FileNames.safeName(file.info().fileName);
        try {
            boolean intact = Files.size(file.part()) == file.info().totalSize
                    && Hashes.sha256(file.part()).equals(file.info().sha256);
            if (!intact) {
                Files.deleteIfExists(file.part());
                listener.fileFailed(name, "llego danado (el SHA-256 no coincide) y se descarto");
                return;
            }
            Path saved = Files.move(file.part(), freeName(file.part().getParent(), name));
            listener.fileReceived(file.from(), file.room(), saved);
        } catch (IOException e) {
            listener.fileFailed(name, "no se pudo guardar: " + e.getMessage());
        }
    }

    /** foto.png, o foto (1).png, foto (2).png, ... si ya existe: nunca se sobrescribe un archivo. */
    private static Path freeName(Path folder, String name) {
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        Path candidate = folder.resolve(name);
        for (int i = 1; Files.exists(candidate); i++) {
            candidate = folder.resolve(base + " (" + i + ")" + extension);
        }
        return candidate;
    }
}
