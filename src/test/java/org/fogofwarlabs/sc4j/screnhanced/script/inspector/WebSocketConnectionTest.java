package org.fogofwarlabs.sc4j.screnhanced.script.inspector;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Tests the RFC 6455 framing of {@link WebSocketConnection}: the accept key of the handshake, masked and fragmented client messages, the
 * control frames, the closes for broken frames, and the length fields of the server's frames.
 */
final class WebSocketConnectionTest {
    private static final byte[] MASK = {0x37, (byte) 0xFA, 0x21, 0x3D};
    private static final int LIMIT = 1024;

    /**
     * Creates a client frame with a mask, as RFC 6455 requires of a client.
     */
    private static byte @NotNull [] createClientFrame(boolean last, int opcode, byte @NotNull [] payload) {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();

        frame.write((last ? 0x80 : 0) | opcode);

        if (payload.length < 126) {
            frame.write(0x80 | payload.length);
        } else {
            frame.write(0x80 | 126);
            frame.write(payload.length >>> 8);
            frame.write(payload.length & 0xFF);
        }

        frame.writeBytes(MASK);

        for (int index = 0; index < payload.length; index++) {
            frame.write(payload[index] ^ MASK[index % MASK.length]);
        }

        return frame.toByteArray();
    }

    private static byte @NotNull [] join(byte @NotNull [] @NotNull ... frames) {
        ByteArrayOutputStream joined = new ByteArrayOutputStream();

        for (byte[] frame : frames) {
            joined.writeBytes(frame);
        }

        return joined.toByteArray();
    }

    private static byte @NotNull [] text(@NotNull String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Reads one message from client frames and returns what the server wrote meanwhile.
     */
    private static byte @NotNull [] readAndCollect(byte @NotNull [] input, String expectedMessage) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        WebSocketConnection connection = new WebSocketConnection(new ByteArrayInputStream(input), output, LIMIT);

        Assertions.assertEquals(expectedMessage, connection.readMessage());

        return output.toByteArray();
    }

    private static int readCloseCode(byte @NotNull [] written) {
        Assertions.assertEquals(0x88, written[0] & 0xFF, "a close frame");
        Assertions.assertEquals(2, written[1]);

        return (written[2] & 0xFF) << 8 | written[3] & 0xFF;
    }

    @Test
    void create_accept_key_should_match_the_example_of_rfc_6455() {
        Assertions.assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", WebSocketConnection.createAcceptKey("dGhlIHNhbXBsZSBub25jZQ=="));
    }

    @Test
    void read_message_should_unmask_and_join_fragments_and_answer_a_ping_between_them() throws IOException {
        byte[] input = join(createClientFrame(false, WebSocketConnection.OPCODE_TEXT, text("Hel")),
                            createClientFrame(true, WebSocketConnection.OPCODE_PING, text("p")),
                            createClientFrame(false, WebSocketConnection.OPCODE_CONTINUATION, text("lo ")),
                            createClientFrame(true, WebSocketConnection.OPCODE_PONG, text("q")),
                            createClientFrame(true, WebSocketConnection.OPCODE_CONTINUATION, text("\uC2A4\uD0C0")));
        byte[] written = readAndCollect(input, "Hello \uC2A4\uD0C0");

        Assertions.assertArrayEquals(new byte[]{(byte) 0x8A, 1, 'p'}, written, "a pong with the ping's payload");
    }

    @Test
    void read_message_should_answer_a_close_and_end_at_the_end_of_the_stream() throws IOException {
        byte[] written = readAndCollect(createClientFrame(true, WebSocketConnection.OPCODE_CLOSE, new byte[]{0x03, (byte) 0xE8}), null);

        Assertions.assertEquals(WebSocketConnection.CLOSE_NORMAL, readCloseCode(written));
        Assertions.assertEquals(0, readAndCollect(new byte[0], null).length, "the end of the stream ends reading without a frame");
    }

    @Test
    void read_message_should_close_on_frames_that_break_the_protocol() throws IOException {
        byte[] unmasked = {(byte) 0x81, 0x01, 'a'};
        byte[] reserved = createClientFrame(true, WebSocketConnection.OPCODE_TEXT, text("a"));
        byte[] fragmentedPing = createClientFrame(false, WebSocketConnection.OPCODE_PING, text("a"));
        byte[] lonelyContinuation = createClientFrame(true, WebSocketConnection.OPCODE_CONTINUATION, text("a"));
        byte[] unknownOpcode = createClientFrame(true, 0x3, text("a"));

        reserved[0] |= 0x40;

        for (byte[] input : new byte[][]{unmasked, reserved, fragmentedPing, lonelyContinuation, unknownOpcode}) {
            Assertions.assertEquals(WebSocketConnection.CLOSE_PROTOCOL_ERROR, readCloseCode(readAndCollect(input, null)));
        }

        Assertions.assertEquals(WebSocketConnection.CLOSE_UNSUPPORTED_DATA,
                                readCloseCode(readAndCollect(createClientFrame(true, WebSocketConnection.OPCODE_BINARY, text("a")), null)));
        Assertions.assertEquals(WebSocketConnection.CLOSE_INVALID_DATA,
                                readCloseCode(readAndCollect(createClientFrame(true, WebSocketConnection.OPCODE_TEXT, new byte[]{(byte) 0xC3}), null)));
        Assertions.assertEquals(WebSocketConnection.CLOSE_TOO_BIG,
                                readCloseCode(readAndCollect(createClientFrame(true, WebSocketConnection.OPCODE_TEXT, new byte[LIMIT + 1]), null)));
    }

    @Test
    void write_text_should_use_the_short_medium_and_long_length_fields() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        WebSocketConnection connection = new WebSocketConnection(new ByteArrayInputStream(new byte[0]), output, LIMIT);

        connection.writeText("abc");
        connection.writeText("x".repeat(300));
        connection.writeText("y".repeat(70_000));

        byte[] written = output.toByteArray();

        Assertions.assertArrayEquals(new byte[]{(byte) 0x81, 3, 'a', 'b', 'c'}, Arrays.copyOf(written, 5));
        Assertions.assertArrayEquals(new byte[]{(byte) 0x81, 126, 0x01, 0x2C}, Arrays.copyOfRange(written, 5, 9));
        Assertions.assertArrayEquals(new byte[]{(byte) 0x81, 127, 0, 0, 0, 0, 0, 0x01, 0x11, 0x70}, Arrays.copyOfRange(written, 309, 319));
        Assertions.assertEquals(319 + 70_000, written.length);

        connection.close(WebSocketConnection.CLOSE_GOING_AWAY);

        Assertions.assertThrows(IOException.class, () -> connection.writeText("after close"));
    }
}
