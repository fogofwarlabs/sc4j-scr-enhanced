package org.fogofwarlabs.sc4j.screnhanced.script.inspector;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Reads and writes the messages of the server side of a WebSocket connection (RFC 6455) after its opening handshake.
 * <p>
 * {@link #readMessage()} returns the next text message: it joins the fragments of a message, answers a ping with a pong, ignores a pong, and
 * answers a close frame with a close frame. A frame that breaks the protocol (an unmasked client frame, reserved bits, an unknown opcode, a
 * fragmented or long control frame, a continuation without a message), a binary message, a text message that is not UTF-8 and a message longer
 * than the limit close the connection with the matching status code. The server's frames are not masked and each holds a whole message.
 * <p>
 * One thread reads; any thread writes, one frame at a time.
 */
final class WebSocketConnection {
    /**
     * The GUID that the accept key of the opening handshake appends to the client's key.
     */
    static final @NotNull String HANDSHAKE_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    /**
     * The WebSocket version of the opening handshake.
     */
    static final @NotNull String VERSION = "13";
    /**
     * The status code of a normal close.
     */
    static final int CLOSE_NORMAL = 1000;
    /**
     * The status code of a close because the server goes away.
     */
    static final int CLOSE_GOING_AWAY = 1001;
    /**
     * The status code of a close because of a protocol error.
     */
    static final int CLOSE_PROTOCOL_ERROR = 1002;
    /**
     * The status code of a close because of a message of a type that is not accepted.
     */
    static final int CLOSE_UNSUPPORTED_DATA = 1003;
    /**
     * The status code of a close because of a text message that is not UTF-8.
     */
    static final int CLOSE_INVALID_DATA = 1007;
    /**
     * The status code of a close because of a message longer than the limit.
     */
    static final int CLOSE_TOO_BIG = 1009;

    static final int OPCODE_CONTINUATION = 0x0;
    static final int OPCODE_TEXT = 0x1;
    static final int OPCODE_BINARY = 0x2;
    static final int OPCODE_CLOSE = 0x8;
    static final int OPCODE_PING = 0x9;
    static final int OPCODE_PONG = 0xA;

    private static final @NotNull String DIGEST_ALGORITHM = "SHA-1";
    private static final int FINAL_BIT = 0x80;
    private static final int RESERVED_BITS = 0x70;
    private static final int OPCODE_MASK = 0x0F;
    private static final int MASK_BIT = 0x80;
    private static final int LENGTH_MASK = 0x7F;
    private static final int FIRST_CONTROL_OPCODE = 0x8;
    /**
     * The largest payload length of a control frame and of the one-byte length field.
     */
    private static final int LARGEST_SHORT_LENGTH = 125;
    /**
     * The length field values that announce a 16-bit and a 64-bit length.
     */
    private static final int MEDIUM_LENGTH_MARKER = 126;
    private static final int LONG_LENGTH_MARKER = 127;
    private static final int LARGEST_MEDIUM_LENGTH = 0xFFFF;
    private static final int MASKING_KEY_LENGTH = 4;
    private static final int BYTE_MASK = 0xFF;
    private static final int BYTE_BITS = 8;
    private static final int LONG_LENGTH_BYTES = 8;
    private static final int LONG_HEADER_LENGTH = 2 + LONG_LENGTH_BYTES;
    private static final int CLOSE_CODE_LENGTH = 2;

    /**
     * Thrown when the peer breaks the protocol; the connection closes with the status code.
     */
    private static final class ProtocolException extends IOException {
        @Serial
        private static final long serialVersionUID = 1L;

        private final int closeCode;

        private ProtocolException(int closeCode, @NotNull String message) {
            super(message);

            this.closeCode = closeCode;
        }
    }


    /**
     * Creates the accept key of the opening handshake: the Base64 form of the SHA-1 digest of the client's key and {@link #HANDSHAKE_GUID}.
     *
     * @param key the value of the client's {@code Sec-WebSocket-Key} header
     * @return the value of the server's {@code Sec-WebSocket-Accept} header
     */
    static @NotNull String createAcceptKey(@NotNull String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance(DIGEST_ALGORITHM);

            return Base64.getEncoder().encodeToString(digest.digest((key.strip() + HANDSHAKE_GUID).getBytes(StandardCharsets.ISO_8859_1)));
        } catch (NoSuchAlgorithmException exception) {
            throw Exceptions.newIllegalStateException(exception, "The Java runtime has no {0} digest.", DIGEST_ALGORITHM);
        }
    }


    private final @NotNull InputStream input;
    private final @NotNull OutputStream output;
    private final int maximumMessageLength;

    private boolean closeSent;

    /**
     * Creates a {@link WebSocketConnection} instance over the streams of a connection whose opening handshake is done.
     *
     * @param input                the stream of the client's frames
     * @param output               the stream of the server's frames
     * @param maximumMessageLength the largest length of a message in bytes
     */
    WebSocketConnection(@NotNull InputStream input, @NotNull OutputStream output, int maximumMessageLength) {
        this.input                = input;
        this.output               = output;
        this.maximumMessageLength = maximumMessageLength;

        this.closeSent = false;
    }

    /**
     * Reads the next text message.
     *
     * @return the message, or {@code null} once the peer closed the connection or broke the protocol, after the close frame was sent
     * @throws IOException if the connection fails
     */
    @Nullable String readMessage() throws IOException {
        try {
            return this.readText();
        } catch (ProtocolException exception) {
            this.close(exception.closeCode);

            return null;
        }
    }

    private @Nullable String readText() throws IOException {
        ByteArrayOutputStream message = null;
        boolean binary = false;

        while (true) {
            int first = this.input.read();

            if (first < 0) {
                return null;
            }

            int second = this.readByte();
            int opcode = first & OPCODE_MASK;
            boolean last = (first & FINAL_BIT) != 0;

            if ((first & RESERVED_BITS) != 0) {
                throw new ProtocolException(CLOSE_PROTOCOL_ERROR, "A frame has reserved bits.");
            }

            if ((second & MASK_BIT) == 0) {
                throw new ProtocolException(CLOSE_PROTOCOL_ERROR, "A client frame is not masked.");
            }

            long length = this.readLength(second & LENGTH_MASK);

            if (opcode >= FIRST_CONTROL_OPCODE && (!last || length > LARGEST_SHORT_LENGTH)) {
                throw new ProtocolException(CLOSE_PROTOCOL_ERROR, "A control frame is fragmented or too long.");
            }

            int bufferedLength = message == null ? 0 : message.size();

            if (length > this.maximumMessageLength - bufferedLength) {
                throw new ProtocolException(CLOSE_TOO_BIG, "A message is too long.");
            }

            byte[] payload = this.readPayload((int) length);

            switch (opcode) {
                case OPCODE_CLOSE -> {
                    this.close(payload.length >= CLOSE_CODE_LENGTH ? (payload[0] & BYTE_MASK) << BYTE_BITS | payload[1] & BYTE_MASK : CLOSE_NORMAL);

                    return null;
                }
                case OPCODE_PING -> this.writeFrame(OPCODE_PONG, payload);
                case OPCODE_PONG -> {
                    // A pong answers nothing the server sent.
                }
                case OPCODE_TEXT, OPCODE_BINARY -> {
                    if (message != null) {
                        throw new ProtocolException(CLOSE_PROTOCOL_ERROR, "A message starts inside another message.");
                    }

                    message = new ByteArrayOutputStream();
                    binary  = opcode == OPCODE_BINARY;

                    message.write(payload);
                }
                case OPCODE_CONTINUATION -> {
                    if (message == null) {
                        throw new ProtocolException(CLOSE_PROTOCOL_ERROR, "A continuation frame has no message.");
                    }

                    message.write(payload);
                }
                default -> throw new ProtocolException(CLOSE_PROTOCOL_ERROR, "A frame has an unknown opcode.");
            }

            if (message == null || opcode >= FIRST_CONTROL_OPCODE || !last) {
                continue;
            }

            if (binary) {
                throw new ProtocolException(CLOSE_UNSUPPORTED_DATA, "A binary message is not accepted.");
            }

            try {
                return StandardCharsets.UTF_8.newDecoder()
                                             .onMalformedInput(CodingErrorAction.REPORT)
                                             .onUnmappableCharacter(CodingErrorAction.REPORT)
                                             .decode(ByteBuffer.wrap(message.toByteArray()))
                                             .toString();
            } catch (CharacterCodingException exception) {
                throw new ProtocolException(CLOSE_INVALID_DATA, "A text message is not UTF-8.");
            }
        }
    }

    private long readLength(int lengthField) throws IOException {
        if (lengthField == MEDIUM_LENGTH_MARKER) {
            return (long) this.readByte() << BYTE_BITS | this.readByte();
        }

        if (lengthField != LONG_LENGTH_MARKER) {
            return lengthField;
        }

        long length = 0;

        for (int index = 0; index < LONG_LENGTH_BYTES; index++) {
            length = length << BYTE_BITS | this.readByte();
        }

        if (length < 0) {
            throw new ProtocolException(CLOSE_PROTOCOL_ERROR, "A frame length is negative.");
        }

        return length;
    }

    /**
     * Reads the masking key and the masked payload of a frame and unmasks the payload.
     */
    private byte @NotNull [] readPayload(int length) throws IOException {
        byte[] mask = this.readBytes(MASKING_KEY_LENGTH);
        byte[] payload = this.readBytes(length);

        for (int index = 0; index < payload.length; index++) {
            payload[index] ^= mask[index % MASKING_KEY_LENGTH];
        }

        return payload;
    }

    private int readByte() throws IOException {
        int value = this.input.read();

        if (value < 0) {
            throw new EOFException("The connection ended inside a frame.");
        }

        return value;
    }

    private byte @NotNull [] readBytes(int length) throws IOException {
        byte[] bytes = this.input.readNBytes(length);

        if (bytes.length != length) {
            throw new EOFException("The connection ended inside a frame.");
        }

        return bytes;
    }

    /**
     * Writes a text message in one frame.
     *
     * @param message the message
     * @throws IOException if the connection fails or is closed
     */
    void writeText(@NotNull String message) throws IOException {
        this.writeFrame(OPCODE_TEXT, message.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Writes one unmasked frame that holds a whole payload.
     *
     * @param opcode  the opcode
     * @param payload the payload
     * @throws IOException if the connection fails or a close frame was sent
     */
    synchronized void writeFrame(int opcode, byte @NotNull [] payload) throws IOException {
        if (this.closeSent) {
            throw Exceptions.newIOException("The WebSocket connection is closed.");
        }

        byte[] header;

        if (payload.length <= LARGEST_SHORT_LENGTH) {
            header = new byte[]{(byte) (FINAL_BIT | opcode), (byte) payload.length};
        } else if (payload.length <= LARGEST_MEDIUM_LENGTH) {
            header = new byte[]{(byte) (FINAL_BIT | opcode), (byte) MEDIUM_LENGTH_MARKER, (byte) (payload.length >>> BYTE_BITS), (byte) payload.length};
        } else {
            header    = new byte[LONG_HEADER_LENGTH];
            header[0] = (byte) (FINAL_BIT | opcode);
            header[1] = (byte) LONG_LENGTH_MARKER;

            for (int index = 0; index < LONG_LENGTH_BYTES; index++) {
                header[header.length - 1 - index] = (byte) ((long) payload.length >>> BYTE_BITS * index);
            }
        }

        this.output.write(header);
        this.output.write(payload);
        this.output.flush();

        if (opcode == OPCODE_CLOSE) {
            this.closeSent = true;
        }
    }

    /**
     * Sends a close frame with a status code, once; a failing connection is ignored.
     *
     * @param code the status code
     */
    synchronized void close(int code) {
        if (this.closeSent) {
            return;
        }

        try {
            this.writeFrame(OPCODE_CLOSE, new byte[]{(byte) (code >>> BYTE_BITS), (byte) code});
        } catch (IOException exception) {
            this.closeSent = true;
        }
    }
}
