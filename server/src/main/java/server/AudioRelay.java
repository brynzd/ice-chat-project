package server;

import chat.AudioPacketBytes;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;

/** Relay UDP de las llamadas grupales (RF-06): reenvia el audio de cada participante a los demas de su sala. */
public final class AudioRelay {

    /**
     * Participante de una llamada grupal.
     *
     * @param user    sesion del participante
     * @param room    sala de la llamada
     * @param token   numero que va al inicio de cada datagrama suyo
     * @param address direccion UDP donde recibe el audio
     */
    public record Member(UserSession user, String room, int token, InetSocketAddress address) {
    }

    private final DatagramSocket socket;
    private final ConcurrentMap<Integer, Member> byToken = new ConcurrentHashMap<>();
    /** Participantes por sala (clave: Names.key). */
    private final ConcurrentMap<String, Set<Member>> calls = new ConcurrentHashMap<>();

    /**
     * Abre el socket del relay y empieza a reenviar en un hilo propio.
     *
     * @param port puerto UDP (0 = cualquiera libre)
     */
    public AudioRelay(int port) throws SocketException {
        socket = new DatagramSocket(port);
        Thread thread = new Thread(this::forward, "audio-relay");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Puerto UDP al que los clientes envian el audio.
     *
     * @return puerto local del socket
     */
    public int port() {
        return socket.getLocalPort();
    }

    /**
     * Agrega un participante a la llamada de la sala con un token libre.
     *
     * @param user    usuario que entra
     * @param room    sala de la llamada
     * @param address direccion UDP donde recibe el audio
     * @return el participante creado
     */
    public Member join(UserSession user, String room, InetSocketAddress address) {
        Member member;
        do {
            member = new Member(user, room, ThreadLocalRandom.current().nextInt(), address);
        } while (byToken.putIfAbsent(member.token(), member) != null);
        calls.computeIfAbsent(Names.key(room), key -> ConcurrentHashMap.newKeySet()).add(member);
        return member;
    }

    /**
     * Saca al participante de su llamada.
     *
     * @param member participante que sale
     */
    public void leave(Member member) {
        byToken.remove(member.token(), member);
        Set<Member> call = calls.get(Names.key(member.room()));
        if (call != null) {
            call.remove(member);
        }
    }

    /**
     * Participantes de la llamada de una sala.
     *
     * @param room nombre de la sala
     * @return participantes actuales, vacio si no hay llamada
     */
    public List<Member> members(String room) {
        Set<Member> call = calls.get(Names.key(room));
        return call == null ? List.of() : List.copyOf(call);
    }

    /**
     * Indica si hay una llamada en curso en la sala.
     *
     * @param room nombre de la sala
     * @return true si alguien esta en la llamada
     */
    public boolean active(String room) {
        return !members(room).isEmpty();
    }

    /** Reenvia cada datagrama valido a los demas participantes de la sala de quien lo envio. */
    private void forward() {
        byte[] buffer = new byte[2048];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        while (!socket.isClosed()) {
            Member sender = receive(packet);
            if (sender == null) {
                continue;
            }
            for (Member member : members(sender.room())) {
                if (member != sender) {
                    send(new DatagramPacket(buffer, packet.getLength(), member.address()));
                }
            }
        }
    }

    /**
     * Recibe un datagrama y busca a su emisor por el token; el token debe venir de la direccion de ese participante.
     *
     * @param packet paquete donde se recibe
     * @return el emisor, o null si el datagrama no es valido
     */
    private Member receive(DatagramPacket packet) {
        try {
            packet.setLength(packet.getData().length);
            socket.receive(packet);
        } catch (IOException e) {
            return null;
        }
        if (packet.getLength() != Integer.BYTES + AudioPacketBytes.value) {
            return null;
        }
        Member sender = byToken.get(ByteBuffer.wrap(packet.getData()).getInt());
        return sender != null && sender.address().equals(packet.getSocketAddress()) ? sender : null;
    }

    /**
     * Envia un datagrama; si falla solo se pierde ese paquete y los demas participantes siguen recibiendo.
     *
     * @param packet datagrama con su destino
     */
    private void send(DatagramPacket packet) {
        try {
            socket.send(packet);
        } catch (IOException ignored) {
        }
    }
}
