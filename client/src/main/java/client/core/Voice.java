package client.core;

import chat.AudioBigEndian;
import chat.AudioChannels;
import chat.AudioPacketBytes;
import chat.AudioSampleRate;
import chat.AudioSampleSizeBits;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.Arrays;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Audio de una llamada por UDP: envia el microfono y reproduce lo que llega, mezclando a quienes hablan a la vez. */
final class Voice {

    private static final AudioFormat FORMAT = new AudioFormat(AudioSampleRate.value, AudioSampleSizeBits.value,
            AudioChannels.value, true, AudioBigEndian.value);
    private static final ByteOrder ORDER = AudioBigEndian.value ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
    private static final int PACKET = AudioPacketBytes.value;
    /** Paquetes de 20 ms en espera por persona; los que sobran se descartan para no acumular retraso. */
    private static final int MAX_QUEUED = 4;
    /** Buffer de las lineas de audio: 120 ms. */
    private static final int LINE_BUFFER = PACKET * 6;

    private final DatagramSocket socket;
    private final TargetDataLine mic;
    private final SourceDataLine speaker;
    private final Map<Integer, Queue<byte[]>> incoming = new ConcurrentHashMap<>();
    private volatile boolean muted;

    /** Abre un socket UDP en un puerto libre, el microfono y el altavoz. */
    Voice() {
        try {
            socket = new DatagramSocket();
            mic = AudioSystem.getTargetDataLine(FORMAT);
            speaker = AudioSystem.getSourceDataLine(FORMAT);
            mic.open(FORMAT, LINE_BUFFER);
            speaker.open(FORMAT, LINE_BUFFER);
        } catch (LineUnavailableException | IllegalArgumentException | SocketException e) {
            close();
            throw new IllegalStateException("No se pudo abrir el microfono o el altavoz (" + e.getMessage() + ")");
        }
    }

    /**
     * Puerto UDP donde llega el audio.
     *
     * @return puerto local del socket
     */
    int port() {
        return socket.getLocalPort();
    }

    /**
     * Empieza a enviar el microfono y a reproducir lo que llega.
     *
     * @param target a donde se envia el audio: el otro usuario o el relay
     * @param header bytes antes del audio en cada datagrama: el token en llamadas grupales, vacio en 1 a 1
     */
    void start(InetSocketAddress target, byte[] header) {
        mic.start();
        speaker.start();
        daemon("audio-send", () -> send(target, header));
        daemon("audio-receive", () -> receive(header.length));
        daemon("audio-play", this::play);
    }

    /**
     * Silencia el microfono; se sigue enviando silencio para que el flujo UDP no se corte.
     *
     * @param muted true para silenciar
     */
    void mute(boolean muted) {
        this.muted = muted;
    }

    /** Cierra el socket y las lineas de audio; los hilos terminan solos. */
    void close() {
        if (socket != null) {
            socket.close();
        }
        if (mic != null) {
            mic.close();
        }
        if (speaker != null) {
            speaker.close();
        }
    }

    /**
     * Lee el microfono de a un paquete (20 ms) y lo envia con el encabezado.
     *
     * @param target destino del audio
     * @param header encabezado de cada datagrama
     */
    private void send(InetSocketAddress target, byte[] header) {
        byte[] data = Arrays.copyOf(header, header.length + PACKET);
        DatagramPacket packet = new DatagramPacket(data, data.length, target);
        while (mic.isOpen()) {
            if (mic.read(data, header.length, PACKET) < PACKET) {
                continue;
            }
            if (muted) {
                Arrays.fill(data, header.length, data.length, (byte) 0);
            }
            try {
                socket.send(packet);
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * Recibe los datagramas y los encola por persona: el token del encabezado, o 0 en 1 a 1.
     *
     * @param headerLength largo del encabezado de cada datagrama
     */
    private void receive(int headerLength) {
        byte[] data = new byte[2048];
        DatagramPacket packet = new DatagramPacket(data, data.length);
        while (!socket.isClosed()) {
            try {
                packet.setLength(data.length);
                socket.receive(packet);
            } catch (IOException e) {
                continue;
            }
            if (packet.getLength() != headerLength + PACKET) {
                continue;
            }
            int from = headerLength == 0 ? 0 : ByteBuffer.wrap(data).getInt();
            Queue<byte[]> queue = incoming.computeIfAbsent(from, key -> new ConcurrentLinkedQueue<>());
            queue.add(Arrays.copyOfRange(data, headerLength, headerLength + PACKET));
            while (queue.size() > MAX_QUEUED) {
                queue.poll();
            }
        }
    }

    /** Suma un paquete de cada persona (con saturacion) y lo reproduce; write marca el ritmo de 20 ms. */
    private void play() {
        int[] mix = new int[PACKET / 2];
        byte[] out = new byte[PACKET];
        ShortBuffer outSamples = ByteBuffer.wrap(out).order(ORDER).asShortBuffer();
        while (speaker.isOpen()) {
            Arrays.fill(mix, 0);
            for (Queue<byte[]> queue : incoming.values()) {
                byte[] pcm = queue.poll();
                if (pcm != null) {
                    ShortBuffer samples = ByteBuffer.wrap(pcm).order(ORDER).asShortBuffer();
                    for (int i = 0; i < mix.length; i++) {
                        mix[i] += samples.get(i);
                    }
                }
            }
            for (int i = 0; i < mix.length; i++) {
                outSamples.put(i, (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, mix[i])));
            }
            speaker.write(out, 0, out.length);
        }
    }

    /**
     * Inicia un hilo daemon.
     *
     * @param name nombre del hilo
     * @param task trabajo del hilo
     */
    private static void daemon(String name, Runnable task) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.start();
    }
}
