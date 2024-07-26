package com.coffeeclub.nebula;


import java.io.*;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Logger;


/**
 * A simple WebSocket server for testing purposes.
 * <a href="https://www.rfc-editor.org/rfc/rfc6455">rfc6455</a>
 *
 * @author maksim.hvatov
 */
public class WebsocketServer {
    private final static Logger LOGGER = Logger.getLogger(WebsocketServer.class.getName());

    private SocketChannel clientChannel;
    private final ServerSocketChannel serverSocketChanel;
    private final MessageConsumer messageConsumer;
    private final Decoder decoder;
    private final ByteBuffer receiveBuffer = ByteBuffer.allocate(1024);

    private Thread serverThread;
    private Thread clientThread;


    public WebsocketServer(int port, MessageConsumer messageConsumer) {
        try {
            serverSocketChanel = ServerSocketChannel.open();
            serverSocketChanel.bind(new InetSocketAddress(port));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        decoder = new Decoder();
        this.messageConsumer = messageConsumer;
    }

    public void start() {
        try {
            LOGGER.info("SERVER | Starting server %s".formatted(serverSocketChanel.getLocalAddress()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        serverThread = new Thread(this::accept, "server-thread");
        serverThread.start();
    }

    public void stop() {
        try {
            serverSocketChanel.close();
            serverThread.interrupt();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private final BlockingQueue<WriteEvent> sendQueue = new LinkedBlockingQueue<>();

    public CompletableFuture<?> sendText(String text) {
        CompletableFuture<?> future = new CompletableFuture<>();
        ByteBuffer buffer = ByteBuffer.allocate(2 + text.length());
        buffer.put((byte) 0x81);
        buffer.put((byte) (text.length() & 0x7F));
        buffer.put(text.getBytes(StandardCharsets.UTF_8));
        buffer.flip();
        sendQueue.add(new WriteEvent(buffer, future));
        return future;
    }

    public CompletableFuture<?> sendPing(){
        CompletableFuture<?> future = new CompletableFuture<>();
        ByteBuffer buffer = ByteBuffer.allocate(4);
        buffer.put((byte) 0x89);
        buffer.put((byte) 2);
        buffer.put(new byte[]{(byte) 0x00, (byte) 0x00});
        buffer.flip();
        sendQueue.add(new WriteEvent(buffer, future));
        return future;
    }

    public CompletableFuture<?> sendClose(){
        CompletableFuture<?> future = new CompletableFuture<>();
        ByteBuffer buffer = ByteBuffer.allocate(2);
        buffer.put((byte) 0x88);
        buffer.put((byte) 0);
        buffer.flip();
        sendQueue.add(new WriteEvent(buffer, future));
        return future;
    }
    private void onText(CharBuffer buffer, boolean last) {
        LOGGER.fine("SERVER | Received text: %s".formatted(buffer));
        messageConsumer.onText(this, buffer, last);
    }

    private void onBinary(ByteBuffer buffer, boolean last) {
        LOGGER.fine("SERVER | Received binary: %s".formatted(buffer));
        messageConsumer.onBinary(this, buffer, last);
    }

    private void onPing(ByteBuffer buffer) {
        LOGGER.fine("SERVER | Received ping: %s".formatted(buffer));
        messageConsumer.onPing(this, buffer);
    }

    private void onPong(ByteBuffer buffer) {
        LOGGER.fine("SERVER | Received pong: %s".formatted(buffer));
        messageConsumer.onPong(this, buffer);
    }

    private void handleClient() {
        Selector selector = null;
        try {
            selector = Selector.open();
            clientChannel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE);
            while (true) {
                selector.select();
                final Iterator<SelectionKey> selectedKeys = selector.selectedKeys().iterator();
                while (selectedKeys.hasNext()) {
                    SelectionKey key = selectedKeys.next();
                    selectedKeys.remove();
                    if (!key.isValid()) {
                        continue;
                    }
                    if (key.isReadable()) {
                        int read = clientChannel.read(receiveBuffer);
                        if(read == -1){
                            // TODO implement a closing routine
                            clientChannel.close();
                            LOGGER.severe("SERVER | Connection closed by client");
                            break;
                        }
                        receiveBuffer.flip();
                        decoder.decode(receiveBuffer);
                        receiveBuffer.clear();
                    }
                    if (key.isWritable()) {
                        WriteEvent event = sendQueue.poll();
                        if (event != null) {
                            int written = clientChannel.write(event.buffer.rewind());
                            event.future.complete(null);
                        }
                    }
                }

            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    private void accept() {
        try {
            LOGGER.fine("SERVER | accepting connections");
            while (true) {
                try{
                    clientChannel = serverSocketChanel.accept();
                } catch (AsynchronousCloseException e){
                    break;
                }

                LOGGER.info("SERVER | connected with %s".formatted(clientChannel.getRemoteAddress()));

                PrintWriter out = new PrintWriter(clientChannel.socket().getOutputStream(), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(clientChannel.socket().getInputStream()));

                final Map<String, String> headers = new HashMap<>();

                do {
                    String s = in.readLine();
                    if (s.contains(":")) {
                        String[] split = s.split(":");
                        headers.put(split[0].trim(), split[1].trim());
                    }
                } while (in.ready());

                String responseKey = calculateResponseKey(headers.get("Sec-WebSocket-Key"));
                out.println("HTTP/1.1 101 Switching Protocols");
                out.println("Upgrade: websocket");
                out.println("Connection: Upgrade");
                out.println("Sec-WebSocket-Accept: " + responseKey);
                out.println();
                out.flush();
                clientChannel.configureBlocking(false);
                clientChannel.socket().setTcpNoDelay(true);
                clientThread = new Thread(this::handleClient, "client-thread");
                clientThread.start();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private String calculateResponseKey(String key) {
        String secWebSocketKey = key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
        String accept;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            accept = Base64.getEncoder().encodeToString(digest.digest(secWebSocketKey.getBytes()));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
        return accept;
    }

    private static class WriteEvent {
        public final ByteBuffer buffer;
        public final CompletableFuture<?> future;

        public WriteEvent(ByteBuffer buffer, CompletableFuture<?> future) {
            this.buffer = buffer;
            this.future = future;
        }
    }

    enum Opcode {
        CONTINUATION(0), TEXT(1), BINARY(2), CLOSE(8), PING(9), PONG(10);

        private final int value;

        Opcode(int code) {
            this.value = code;
        }

        public boolean isControl() {
            return value >= 8;
        }

        public boolean isData() {
            return value < 8;
        }

        public int getValue() {
            return value;
        }

        private static Opcode of(int code) {
            for (Opcode opcode : values()) {
                if (opcode.value == code) {
                    return opcode;
                }
            }
            throw new IllegalArgumentException("No opcode with value " + code);
        }
    }

    /**
     * Consumes frame parts and notifies a message consumer, when there is
     * sufficient data to produce a message, or part thereof.
     * <p>
     * Data consumed but not yet translated is accumulated until it's sufficient to
     * form a message.
     */
    private class Decoder {
        private static final int MAX_CONTROL_FRAME_LENGTH = 125;

        private final Reader reader = new Reader();

        private boolean fin;
        private Opcode opcode;
        private Opcode originatatingOpcode;

        private boolean mask;
        private ByteBuffer payloadData;
        private int maskingKey;
        private long remainingPayloadLength;


        public void decode(ByteBuffer buffer) {
            LOGGER.fine("SERVER | Decoding frame: delivered=%d bytes".formatted(buffer.remaining()));
            reader.readFrame(buffer);
        }

        private void fin(boolean value) {
            this.fin = value;
        }

        private void opcode(Opcode opcode) {
            if (opcode.isControl()) {
                if (!fin) {
                    throw new IllegalStateException("Control frames must not be fragmented");
                }
            } else if (opcode == Opcode.TEXT || opcode == Opcode.BINARY) {
                if (originatatingOpcode != null) {
                    throw new IllegalStateException("Expected Continuation Frame but received Frame was %s".formatted(opcode));
                }
                if (!fin) {
                    originatatingOpcode = opcode;
                }
            } else if (opcode == Opcode.CONTINUATION) {
                if (originatatingOpcode == null) {
                    throw new IllegalStateException("Expected Continuation Frame but received Frame was %s".formatted(opcode));
                }
            }
            this.opcode = opcode;

        }

        private void mask(boolean value) {
            this.mask = value;
        }

        private void payLoadLength(long length) {
            if (opcode.isControl()) {
                if (length > MAX_CONTROL_FRAME_LENGTH) {
                    throw new IllegalStateException("Control frame length must be less than 126");
                }

                if (opcode == Opcode.CLOSE && length == 1) {
                    throw new IllegalStateException("Close frame must have a payload length of 2");
                }
            }
            remainingPayloadLength = length;
            // TODO kinda hacky but for a test server it's fine, buffering of incoming data should handled by user.
            //      Since i do not anticipate large frames, this can be handled here
            payloadData = ByteBuffer.allocate((int) length);
        }

        private void maskingKey(int maskingKey) {
            this.maskingKey = maskingKey;
        }

        private void payloadData(ByteBuffer buffer) {
            remainingPayloadLength -= buffer.remaining();
            if (mask) {
                unMask(buffer);
            }
            this.payloadData.put(buffer);
        }

        private void endOfFrame() {
            if (remainingPayloadLength != 0) {
                throw new IllegalStateException("Frame not fully read");
            }
            payloadData.flip();
            switch (opcode) {
                case TEXT:
                    onText(StandardCharsets.UTF_8.decode(payloadData), fin);
                    break;
                case BINARY:
                    onBinary(payloadData, fin);
                    break;
                case PING:
                    onPing(payloadData);
                    break;
                case PONG:
                    onPong(payloadData);
                    break;
                case CLOSE:
                    LOGGER.fine("SERVER | Received close frame");
                    sendClose();
                    break;
                case CONTINUATION:
                    if (originatatingOpcode == Opcode.TEXT) {
                        onText(StandardCharsets.UTF_8.decode(payloadData), fin);
                    } else if (originatatingOpcode == Opcode.BINARY) {
                        onBinary(payloadData, fin);
                    }
                    if (fin) {
                        originatatingOpcode = null;
                    }
                    break;
                default:
                    throw new IllegalStateException("Invalid opcode: " + opcode);
            }
            payloadData.clear();
        }


        private void unMask(ByteBuffer src) {
            int pos = src.position();
            int size = src.remaining();
            ByteBuffer temp = ByteBuffer.allocate(size);
            Masker.transferMasking(src, temp, maskingKey);
            temp.flip();
            src.position(pos);
            src.put(temp);
            src.position(pos).limit(pos + size);
        }

        /**
         * A reader of Frames.
         */
        private class Reader {
            private static final int AWAITING_FIRST_BYTE = 1;
            private static final int AWAITING_SECOND_BYTE = 2;
            private static final int READING_PAYLOAD_16_LENGTH = 4;
            private static final int READING_PAYLOAD_64_LENGTH = 8;
            private static final int READING_MASK = 16;
            private static final int READING_PAYLOAD_DATA = 32;

            private final ByteBuffer accumulator = ByteBuffer.allocate(8);

            private int state = AWAITING_FIRST_BYTE;


            private boolean mask;
            private long remainingPayloadLength;

            private void readFrame(ByteBuffer buffer) {
                loop:
                while (true) {
                    byte b;
                    switch (state) {
                        case AWAITING_FIRST_BYTE:
                            if (!buffer.hasRemaining()) {
                                break loop;
                            }
                            b = buffer.get();
                            fin((b & 0x80) == 0x80);
                            Opcode opcode = Opcode.of(b & 0x0F);
                            opcode(opcode);
                            state = AWAITING_SECOND_BYTE;
                            continue;
                        case AWAITING_SECOND_BYTE:
                            if (!buffer.hasRemaining()) {
                                break loop;
                            }
                            b = buffer.get();
                            mask = (b & 0x80) != 0;
                            mask(mask);
                            byte payload = (byte) (b & 0x7F);
                            if (payload < 126) {
                                payLoadLength(remainingPayloadLength = payload);
                                state = mask ? READING_MASK : READING_PAYLOAD_DATA;
                            } else if (payload == 126) {
                                state = READING_PAYLOAD_16_LENGTH;
                            } else {
                                state = READING_PAYLOAD_64_LENGTH;
                            }
                            continue;
                        case READING_PAYLOAD_16_LENGTH:
                            if (!buffer.hasRemaining()) {
                                break loop;
                            }
                            b = buffer.get();
                            if (accumulator.put(b).position() < 2) {
                                continue;
                            }
                            remainingPayloadLength = accumulator.flip().getChar();
                            if (remainingPayloadLength < 126) {
                                throw new IllegalStateException("Invalid payload length: " + remainingPayloadLength);
                            }
                            payLoadLength(remainingPayloadLength);
                            accumulator.clear();
                            state = mask ? READING_MASK : READING_PAYLOAD_DATA;
                            continue;
                        case READING_PAYLOAD_64_LENGTH:
                            if (!buffer.hasRemaining()) {
                                break loop;
                            }
                            b = buffer.get();
                            if (accumulator.put(b).position() < 8) {
                                continue;
                            }
                            remainingPayloadLength = buffer.flip().getLong();
                            if (remainingPayloadLength < 65536) {
                                throw new IllegalStateException("Invalid payload length: " + remainingPayloadLength);
                            }
                            payLoadLength(remainingPayloadLength);
                            accumulator.clear();
                            state = mask ? READING_MASK : READING_PAYLOAD_DATA;
                            continue;
                        case READING_MASK:
                            if (!buffer.hasRemaining()) {
                                break loop;
                            }
                            b = buffer.get();
                            if (accumulator.put(b).position() != 4) {
                                continue;
                            }
                            maskingKey(accumulator.flip().getInt());
                            accumulator.clear();
                            state = READING_PAYLOAD_DATA;
                            continue;
                        case READING_PAYLOAD_DATA:
                            int deliverable = (int) Math.min(remainingPayloadLength, buffer.remaining());
                            int oldLimit = buffer.limit();
                            buffer.limit(buffer.position() + deliverable);
                            if (deliverable != 0 || remainingPayloadLength == 0) {
                                payloadData(buffer);
                            }
                            int consumed = deliverable - buffer.remaining();
                            if (consumed < 0) {
                                throw new IllegalStateException("Invalid consumed: " + consumed);
                            }
                            buffer.limit(oldLimit);
                            remainingPayloadLength -= consumed;
                            if (remainingPayloadLength == 0) {
                                state = AWAITING_FIRST_BYTE;
                                endOfFrame();
                            }
                            if (buffer.remaining() > 0) {
                                continue;
                            }
                            break loop;
                        default:
                            throw new IllegalStateException("Invalid state: " + state);
                    }
                }
            }


        }
    }

    /*
     * A utility for masking frame payload data.
     */
    static final class Masker {

        // Exploiting ByteBuffer's ability to read/write multi-byte integers
        private final ByteBuffer acc = ByteBuffer.allocate(8);
        private final int[] maskBytes = new int[4];
        private int offset;
        private long maskLong;

        /*
         * Reads all remaining bytes from the given input buffer, masks them
         * with the supplied mask and writes the resulting bytes to the given
         * output buffer.
         *
         * The source and the destination buffers may be the same instance.
         */
        static void transferMasking(ByteBuffer src, ByteBuffer dst, int mask) {
            if (src.remaining() > dst.remaining()) {
                throw new IllegalArgumentException("Destination buffer is too small");
            }
            new Masker().mask(mask).transferMasking(src, dst);
        }

        /*
         * Clears this instance's state and sets the mask.
         *
         * The behaviour is as if the mask was set on a newly created instance.
         */
        Masker mask(int value) {
            acc.clear().putInt(value).putInt(value).flip();
            for (int i = 0; i < maskBytes.length; i++) {
                maskBytes[i] = acc.get(i);
            }
            offset = 0;
            maskLong = acc.getLong(0);
            return this;
        }

        /*
         * Reads as many remaining bytes as possible from the given input
         * buffer, masks them with the previously set mask and writes the
         * resulting bytes to the given output buffer.
         *
         * The source and the destination buffers may be the same instance. If
         * the mask hasn't been previously set it is assumed to be 0.
         */
        Masker transferMasking(ByteBuffer src, ByteBuffer dst) {
            begin(src, dst);
            loop(src, dst);
            end(src, dst);
            return this;
        }

        /*
         * Applies up to 3 remaining from the previous pass bytes of the mask.
         */
        private void begin(ByteBuffer src, ByteBuffer dst) {
            if (offset == 0) { // No partially applied mask from the previous invocation
                return;
            }
            int i = src.position(), j = dst.position();
            final int srcLim = src.limit(), dstLim = dst.limit();
            for (; offset < 4 && i < srcLim && j < dstLim; i++, j++, offset++) {
                dst.put(j, (byte) (src.get(i) ^ maskBytes[offset]));
            }
            offset &= 3; // Will become 0 if the mask has been fully applied
            src.position(i);
            dst.position(j);
        }

        /*
         * Gallops one long (mask + mask) at a time.
         */
        private void loop(ByteBuffer src, ByteBuffer dst) {
            int i = src.position();
            int j = dst.position();
            final int srcLongLim = src.limit() - 7, dstLongLim = dst.limit() - 7;
            for (; i < srcLongLim && j < dstLongLim; i += 8, j += 8) {
                dst.putLong(j, src.getLong(i) ^ maskLong);
            }
            if (i > src.limit()) {
                src.position(i - 8);
            } else {
                src.position(i);
            }
            if (j > dst.limit()) {
                dst.position(j - 8);
            } else {
                dst.position(j);
            }
        }

        /*
         * Applies up to 7 remaining from the "galloping" phase bytes of the
         * mask.
         */
        private void end(ByteBuffer src, ByteBuffer dst) {
            assert Math.min(src.remaining(), dst.remaining()) < 8;
            final int srcLim = src.limit(), dstLim = dst.limit();
            int i = src.position(), j = dst.position();
            for (; i < srcLim && j < dstLim; i++, j++, offset = (offset + 1) & 3) // offset cycles through 0..3
            {
                dst.put(j, (byte) (src.get(i) ^ maskBytes[offset]));
            }
            src.position(i);
            dst.position(j);
        }
    }
}
