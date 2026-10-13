package server;

import chat.ChunkSize;
import chat.FileChunk;
import chat.FileInfo;
import chat.InvalidNameException;
import chat.NotInRoomException;
import chat.RoomNotFoundException;
import chat.TransferException;
import chat.UserNotFoundException;
import com.zeroc.Ice.ObjectNotExistException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Transferencia de archivos por bloques (RF-04). El servidor no guarda el archivo: reenvia cada
 * bloque a los destinatarios y espera a que lo reciban antes de responderle al emisor. Asi el emisor
 * nunca va mas rapido que los receptores y un archivo grande no se acumula en la memoria del servidor.
 */
public final class FileTransfers {

    /** Tamano maximo aceptado: 1 GB. */
    public static final long MAX_SIZE = 1L << 30;

    // Separadores de ruta y caracteres de control: el nombre debe ser solo un nombre
    private static final Pattern BAD_NAME_CHARS = Pattern.compile("[\\p{Cntrl}/\\\\]");
    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    /**
     * Transferencia en curso. recipients es CopyOnWriteArrayList porque se recorre al reenviar
     * mientras otros hilos quitan destinatarios caidos. next es el proximo bloque esperado: el
     * emisor envia los bloques en orden, uno a la vez.
     */
    private record Transfer(String id, UserSession sender, FileInfo info,
                            List<UserSession> recipients, AtomicInteger next) {

        /** Largo que debe tener el bloque index (el ultimo puede ser mas corto), o -1 si no existe. */
        long expectedLength(int index) {
            if (index < 0 || index >= info.totalChunks) {
                return -1;
            }
            return Math.min(info.chunkSize, info.totalSize - (long) index * info.chunkSize);
        }
    }

    private final ConcurrentMap<String, Transfer> transfers = new ConcurrentHashMap<>();
    private final SessionManager sessions;
    private final RoomManager rooms;

    public FileTransfers(SessionManager sessions, RoomManager rooms) {
        this.sessions = sessions;
        this.rooms = rooms;
    }

    public String startToUser(UserSession sender, String to, FileInfo info)
            throws UserNotFoundException, InvalidNameException, TransferException {
        UserSession target = sessions.find(to);
        if (target == null) {
            throw new UserNotFoundException("El usuario '" + to + "' no existe o no esta conectado");
        }
        if (target == sender) {
            throw new UserNotFoundException("No puedes enviarte archivos a ti mismo");
        }
        return start(sender, "", info, List.of(target), target.nickname());
    }

    public String startToRoom(UserSession sender, String roomName, FileInfo info)
            throws RoomNotFoundException, NotInRoomException, InvalidNameException, TransferException {
        Room room = rooms.find(roomName);
        if (!room.members().contains(sender)) {
            throw new NotInRoomException("No estas en la sala '" + room.name() + "'");
        }
        // Reciben quienes estan en la sala al empezar; quien entre despues no recibe este archivo
        List<UserSession> others = room.members().stream().filter(member -> member != sender).toList();
        if (others.isEmpty()) {
            throw new TransferException("No hay nadie mas en #" + room.name());
        }
        return start(sender, room.name(), info, others, "#" + room.name());
    }

    private String start(UserSession sender, String room, FileInfo info, List<UserSession> recipients, String target)
            throws InvalidNameException, TransferException {
        validate(info);
        String id = UUID.randomUUID().toString();
        if (info.totalChunks > 0) { // un archivo vacio queda completo con onFileStart
            transfers.put(id, new Transfer(id, sender, info, new CopyOnWriteArrayList<>(recipients), new AtomicInteger()));
            // Igual que en las salas: si la sesion se cerro mientras tanto, endedSession pudo no verla
            if (sender.isClosed()) {
                transfers.remove(id);
                throw new ObjectNotExistException();
            }
        }
        System.out.println("[server] " + sender.nickname() + " envia " + info.fileName + " (" + info.totalSize
                + " bytes, " + info.totalChunks + " bloques) a " + target);
        for (UserSession recipient : recipients) {
            sessions.send(recipient, cb -> cb.onFileStartAsync(id, sender.nickname(), room, info));
        }
        return id;
    }

    /** Reenvia un bloque a los destinatarios y retorna cuando todos lo recibieron. */
    public void upload(UserSession sender, FileChunk chunk) throws TransferException {
        Transfer transfer = transfers.get(chunk.transferId);
        if (transfer == null || transfer.sender() != sender) {
            throw new TransferException("La transferencia no existe o ya termino");
        }
        if (chunk.data.length != transfer.expectedLength(chunk.index)
                || !transfer.next().compareAndSet(chunk.index, chunk.index + 1)) {
            throw new TransferException("Bloque " + chunk.index + " invalido o fuera de orden");
        }

        // Se envia a todos a la vez y se espera a todos: el emisor no manda el siguiente bloque hasta
        // entonces. Un destinatario que falla se quita (y SessionManager.send cierra su sesion).
        List<CompletableFuture<?>> deliveries = new ArrayList<>();
        for (UserSession recipient : transfer.recipients()) {
            deliveries.add(sessions.send(recipient, cb -> cb.onFileChunkAsync(chunk)).handle((ok, error) -> {
                if (error != null) {
                    transfer.recipients().remove(recipient);
                }
                return null;
            }));
        }
        CompletableFuture.allOf(deliveries.toArray(CompletableFuture[]::new)).join();

        if (transfer.recipients().isEmpty()) {
            transfers.remove(transfer.id());
            throw new TransferException("Ningun destinatario sigue conectado");
        }
        if (chunk.index == transfer.info().totalChunks - 1) {
            transfers.remove(transfer.id());
            System.out.println("[server] envio de " + transfer.info().fileName + " completo");
        }
    }

    /** El emisor cancela: los destinatarios descartan el archivo parcial. */
    public void cancel(UserSession sender, String transferId) throws TransferException {
        Transfer transfer = transfers.get(transferId);
        if (transfer == null || transfer.sender() != sender) {
            throw new TransferException("La transferencia no existe o ya termino");
        }
        abort(transfer, sender.nickname() + " cancelo el envio");
    }

    /** Al cerrarse una sesion: se abortan sus envios y deja de recibir los de otros. */
    public void endedSession(UserSession user) {
        for (Transfer transfer : transfers.values()) {
            if (transfer.sender() == user) {
                abort(transfer, user.nickname() + " se desconecto");
            } else {
                transfer.recipients().remove(user);
            }
        }
    }

    private void abort(Transfer transfer, String reason) {
        if (transfers.remove(transfer.id()) == null) {
            return; // ya habia terminado o se aborto en otro hilo
        }
        System.out.println("[server] envio de " + transfer.info().fileName + " abortado: " + reason);
        for (UserSession recipient : transfer.recipients()) {
            sessions.send(recipient, cb -> cb.onFileAbortedAsync(transfer.id(), reason));
        }
    }

    private static void validate(FileInfo info) throws InvalidNameException, TransferException {
        String name = info.fileName;
        if (name == null || name.isBlank() || name.length() > 255 || BAD_NAME_CHARS.matcher(name).find()) {
            throw new InvalidNameException("Nombre de archivo invalido: '" + name + "'");
        }
        long expectedChunks = (info.totalSize + ChunkSize.value - 1) / ChunkSize.value;
        if (info.totalSize < 0 || info.totalSize > MAX_SIZE) {
            throw new TransferException("El archivo debe pesar como maximo " + (MAX_SIZE >> 20) + " MB");
        }
        if (info.chunkSize != ChunkSize.value || info.totalChunks != expectedChunks
                || info.sha256 == null || !SHA256_HEX.matcher(info.sha256).matches()) {
            throw new TransferException("Metadatos del archivo invalidos");
        }
    }
}
