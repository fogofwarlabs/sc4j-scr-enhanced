package org.fogofwarlabs.sc4j.screnhanced.translation;

import org.fogofwarlabs.sc4j.scr.client.extension.ScrMessageSubstitute;
import org.fogofwarlabs.sc4j.scr.game.presentation.MessageLines;
import org.fogofwarlabs.sc4j.translate.ProtectedSpan;
import org.fogofwarlabs.sc4j.translate.TranslationRequest;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.function.IntPredicate;

/**
 * Checks that the message area draws the translations of other players' texts and of map texts in place of their lines while the lines
 * keep their bytes, styles and deadlines, and which texts are asked for.
 */
final class MessageLineTranslationsTest {
    /**
     * The translation contexts of the requests, with the players' terms only.
     */
    private static final @NotNull TranslationContexts CONTEXTS = TranslationContexts.create(GameTerms.EMPTY, SlangGlossary.load());
    /**
     * The layout that keeps every text on one line.
     */
    private static final MessageLines.@NotNull TextLayout SINGLE_LINE = text -> new int[]{text.length};
    /**
     * The piece length of the layout that breaks a text every {@value} bytes.
     */
    private static final int PIECE_LENGTH = 10;
    /**
     * The layout that breaks a text every {@link #PIECE_LENGTH} bytes.
     */
    private static final MessageLines.@NotNull TextLayout FIXED_PIECES = text -> {
        int[] ends = new int[(text.length + PIECE_LENGTH - 1) / PIECE_LENGTH];

        for (int index = 0; index < ends.length; index++) {
            ends[index] = Math.min((index + 1) * PIECE_LENGTH, text.length);
        }

        return ends;
    };

    /**
     * The layout that ends a piece after each line feed.
     */
    private static final MessageLines.@NotNull TextLayout LINE_FEED_PIECES = text -> {
        int count = 0;

        for (int index = 0; index < text.length; index++) {
            if (text[index] == '\n' || index == text.length - 1) {
                count++;
            }
        }

        int[] ends = new int[count];
        int piece = 0;

        for (int index = 0; index < text.length; index++) {
            if (text[index] == '\n' || index == text.length - 1) {
                ends[piece++] = index + 1;
            }
        }

        return ends;
    };
    /**
     * A menu a map lays out in eleven centered rows with blank rows between them and a color code at the start of each row.
     */
    private static final @NotNull String SPEED_MENU = "\u0013\u00071배속\r\n\r\n\r\n\r\n\u0013\u0003게임 배속을 선택하세요!\r\n" +
                                                      "\u0013\u00074배속" + " ".repeat(86) + "\u0007    2배속\r\n\r\n" +
                                                      "\u0013\u00044배속은 렉 때문에 적용이 잘 안될 수 있습니다.\r\n\r\n\r\n\u0013\u00073배속";
    /**
     * The rows of {@link #SPEED_MENU} counted from its first row.
     */
    private static final int FIRST_SPEED_ROW = 0;
    private static final int TITLE_ROW = 4;
    private static final int SPEED_PAIR_ROW = 5;
    private static final int WARNING_ROW = 7;
    private static final int LAST_SPEED_ROW = 10;
    private static final int MESSAGE_ROWS = 13;

    /**
     * The first message line of a game, then the line the next piece takes.
     */
    private static final int FIRST_LINE = 10;
    private static final int SECOND_LINE = 0;
    /**
     * A rotating line no message has written yet.
     */
    private static final int MEMORY_LINE = 3;
    private static final int FIRST_PLAYER = 0;
    private static final int SECOND_PLAYER = 1;
    private static final int NAME_COLOR = 0x11223344;
    private static final int HOST_CLOCK = 500;
    private static final int FRAME_MILLISECONDS = 42;
    private static final int CHAT_TEXT_STYLE = 2;
    private static final int TRIGGER_TEXT_STYLE = 2;
    private static final @NotNull Locale TARGET = Locale.ENGLISH;
    private static final @NotNull IntPredicate EVERY_SENDER = sender -> true;

    private static @NotNull MessageLines createLines(MessageLines.@NotNull TextLayout layout) {
        MessageLines lines = new MessageLines();

        lines.setLayout(layout);
        lines.setHostClock(() -> HOST_CLOCK);
        lines.setPlayerNames(new byte[][]{"Alpha".getBytes(StandardCharsets.UTF_8), "Bravo".getBytes(StandardCharsets.UTF_8)});
        lines.setNameColors(sender -> NAME_COLOR);
        lines.setLocalPlayer(SECOND_PLAYER);

        return lines;
    }

    private static @NotNull MessageLineTranslations install(@NotNull MessageLines lines, MessageLines.@NotNull TextLayout layout,
                                                            @NotNull TextTranslation translation, @NotNull IntPredicate translatedSenders) {
        MessageLineTranslations translations = new MessageLineTranslations(lines, layout, translation, TARGET, translatedSenders);

        lines.setTextListener(translations);

        return translations;
    }

    private static int getRowLine(int row) {
        return (FIRST_LINE + row) % MessageLines.ROTATING_LINES;
    }

    private static @Nullable String findRowText(@NotNull MessageLineTranslations translations, @NotNull MessageLines lines, int row) {
        int line = getRowLine(row);
        ScrMessageSubstitute substitute = translations.findSubstitute(line, lines.getLine(line), HOST_CLOCK);

        return substitute == null ? null : substitute.getText();
    }

    private static void writeMemory(@NotNull MessageLines lines, int line, @NotNull String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);

        for (int index = 0; index < bytes.length; index++) {
            lines.writeByte(line * MessageLines.LINE_SIZE + index, bytes[index]);
        }
    }

    @Test
    void another_players_chat_shows_its_translation_while_the_line_keeps_its_bytes() {
        MessageLines lines = createLines(SINGLE_LINE);
        RecordingTranslator translator = new RecordingTranslator();
        MessageLineTranslations translations = install(lines, SINGLE_LINE, new TextTranslation(translator, CONTEXTS, true, false), EVERY_SENDER);

        lines.writeChat(FIRST_PLAYER, "안녕".getBytes(StandardCharsets.UTF_8));

        byte[] written = lines.getLine(FIRST_LINE);
        int deadline = lines.getDeadline(FIRST_LINE);
        TranslationRequest request = translator.getRequests().getFirst();

        Assertions.assertEquals(1, translator.getRequests().size());
        Assertions.assertEquals("안녕", request.getText(), "Only the text is asked for, without the sender prefix.");
        Assertions.assertEquals(TARGET, request.getTarget());
        Assertions.assertNull(translations.findSubstitute(FIRST_LINE, written, HOST_CLOCK), "The line shows its bytes until the translation comes.");

        translator.answer(0, "hello");

        ScrMessageSubstitute substitute = translations.findSubstitute(FIRST_LINE, lines.getLine(FIRST_LINE), HOST_CLOCK);

        Assertions.assertNotNull(substitute);
        Assertions.assertEquals("Alpha:\u0002 hello", substitute.getText());
        Assertions.assertEquals(CHAT_TEXT_STYLE, substitute.getStyle());
        Assertions.assertEquals(NAME_COLOR, substitute.getColor());
        Assertions.assertArrayEquals(written, lines.getLine(FIRST_LINE), "The line keeps its bytes.");
        Assertions.assertEquals(deadline, lines.getDeadline(FIRST_LINE), "The line keeps its deadline.");
        Assertions.assertEquals(CHAT_TEXT_STYLE, lines.getStyle(FIRST_LINE));
        Assertions.assertEquals(NAME_COLOR, lines.getColor(FIRST_LINE));
    }

    @Test
    void color_codes_of_a_text_are_protected_spans() {
        MessageLines lines = createLines(SINGLE_LINE);
        RecordingTranslator translator = new RecordingTranslator();

        install(lines, SINGLE_LINE, new TextTranslation(translator, CONTEXTS, true, false), EVERY_SENDER);

        lines.writeChat(FIRST_PLAYER, "\u0003go \u0004\u0005now".getBytes(StandardCharsets.UTF_8));

        List<ProtectedSpan> spans = translator.getRequests().getFirst().getProtectedSpans();

        Assertions.assertEquals(2, spans.size());
        Assertions.assertEquals(0, spans.get(0).getStart());
        Assertions.assertEquals(1, spans.get(0).getEnd());
        Assertions.assertEquals(4, spans.get(1).getStart());
        Assertions.assertEquals(6, spans.get(1).getEnd());
    }

    @Test
    void overwritten_bytes_stop_the_substitution() {
        MessageLines lines = createLines(SINGLE_LINE);
        RecordingTranslator translator = new RecordingTranslator();
        MessageLineTranslations translations = install(lines, SINGLE_LINE, new TextTranslation(translator, CONTEXTS, true, false), EVERY_SENDER);

        lines.writeChat(FIRST_PLAYER, "gg".getBytes(StandardCharsets.UTF_8));
        translator.answer(0, "good game");

        Assertions.assertNotNull(translations.findSubstitute(FIRST_LINE, lines.getLine(FIRST_LINE), HOST_CLOCK));

        int offset = FIRST_LINE * MessageLines.LINE_SIZE;
        int original = lines.readByte(offset);

        lines.writeByte(offset, 'X');

        Assertions.assertNull(translations.findSubstitute(FIRST_LINE, lines.getLine(FIRST_LINE), HOST_CLOCK), "A line written over shows its bytes.");

        lines.writeByte(offset, original);

        Assertions.assertNull(translations.findSubstitute(FIRST_LINE, lines.getLine(FIRST_LINE), HOST_CLOCK), "The substitution stays stopped.");
    }

    @Test
    void a_translation_takes_the_rotating_lines_of_its_message() {
        MessageLines lines = createLines(FIXED_PIECES);
        RecordingTranslator translator = new RecordingTranslator();
        MessageLineTranslations translations = install(lines, FIXED_PIECES, new TextTranslation(translator, CONTEXTS, true, false), EVERY_SENDER);

        lines.writeChat(FIRST_PLAYER, "abcdefghijkl".getBytes(StandardCharsets.UTF_8));
        translator.answer(0, "xy");

        ScrMessageSubstitute first = translations.findSubstitute(FIRST_LINE, lines.getLine(FIRST_LINE), HOST_CLOCK);
        ScrMessageSubstitute second = translations.findSubstitute(SECOND_LINE, lines.getLine(SECOND_LINE), HOST_CLOCK);

        Assertions.assertNotNull(first);
        Assertions.assertNotNull(second);
        Assertions.assertEquals("Alpha:\u0002 xy", first.getText());
        Assertions.assertEquals("", second.getText(), "A line without a piece of the translation shows nothing.");
    }

    @Test
    void received_whispers_are_translated() {
        MessageLines lines = createLines(SINGLE_LINE);
        RecordingTranslator translator = new RecordingTranslator();
        MessageLineTranslations translations = install(lines, SINGLE_LINE, new TextTranslation(translator, CONTEXTS, true, false), EVERY_SENDER);

        lines.writeWhisper("<From: %s>", "Zeratul#abcd12", "en taro".getBytes(StandardCharsets.UTF_8), true);
        translator.answer(0, "honor");

        ScrMessageSubstitute substitute = translations.findSubstitute(FIRST_LINE, lines.getLine(FIRST_LINE), HOST_CLOCK);

        Assertions.assertEquals("en taro", translator.getRequests().getFirst().getText());
        Assertions.assertNotNull(substitute);
        Assertions.assertEquals("<From: Zeratul#abcd12> honor", substitute.getText());
    }

    @Test
    void own_lines_and_sent_whispers_are_not_translated() {
        MessageLines lines = createLines(SINGLE_LINE);
        RecordingTranslator translator = new RecordingTranslator();
        MessageLineTranslations translations = install(lines, SINGLE_LINE, new TextTranslation(translator, CONTEXTS, true, true), EVERY_SENDER);

        lines.writeChat(SECOND_PLAYER, "mine".getBytes(StandardCharsets.UTF_8));
        lines.writeWhisper("<To: %s>", "Zeratul#abcd12", "sent".getBytes(StandardCharsets.UTF_8), false);

        Assertions.assertEquals(List.of(), translator.getRequests());
        Assertions.assertNull(translations.findSubstitute(FIRST_LINE, lines.getLine(FIRST_LINE), HOST_CLOCK));
    }

    @Test
    void texts_of_senders_outside_the_translated_slots_are_not_asked_for() {
        MessageLines lines = createLines(SINGLE_LINE);
        RecordingTranslator translator = new RecordingTranslator();

        install(lines, SINGLE_LINE, new TextTranslation(translator, CONTEXTS, true, false), sender -> sender != FIRST_PLAYER);

        lines.writeChat(FIRST_PLAYER, "attack".getBytes(StandardCharsets.UTF_8));

        Assertions.assertEquals(List.of(), translator.getRequests());
    }

    @Test
    void chat_translation_off_asks_for_no_chat() {
        MessageLines lines = createLines(SINGLE_LINE);
        RecordingTranslator translator = new RecordingTranslator();
        MessageLineTranslations translations = install(lines, SINGLE_LINE, new TextTranslation(translator, CONTEXTS, false, true), EVERY_SENDER);

        lines.writeChat(FIRST_PLAYER, "gg".getBytes(StandardCharsets.UTF_8));

        Assertions.assertEquals(List.of(), translator.getRequests());
        Assertions.assertNull(translations.findSubstitute(FIRST_LINE, lines.getLine(FIRST_LINE), HOST_CLOCK));
    }

    @Test
    void trigger_text_shows_its_translation_while_the_line_keeps_its_bytes() {
        MessageLines lines = createLines(SINGLE_LINE);
        RecordingTranslator translator = new RecordingTranslator();
        MessageLineTranslations translations = install(lines, SINGLE_LINE, new TextTranslation(translator, CONTEXTS, false, true), EVERY_SENDER);

        lines.writeTriggerText("\u0004Défendez la base".getBytes(StandardCharsets.UTF_8), true, 0);

        byte[] written = lines.getLine(FIRST_LINE);
        int deadline = lines.getDeadline(FIRST_LINE);

        Assertions.assertEquals("\u0004Défendez la base", translator.getRequests().getFirst().getText());
        Assertions.assertNull(translations.findSubstitute(FIRST_LINE, written, HOST_CLOCK));

        translator.answer(0, "\u0004Defend the base");

        ScrMessageSubstitute substitute = translations.findSubstitute(FIRST_LINE, lines.getLine(FIRST_LINE), HOST_CLOCK);

        Assertions.assertNotNull(substitute);
        Assertions.assertEquals("\u0004Defend the base", substitute.getText());
        Assertions.assertEquals(TRIGGER_TEXT_STYLE, substitute.getStyle());
        Assertions.assertEquals(0, substitute.getColor());
        Assertions.assertArrayEquals(written, lines.getLine(FIRST_LINE), "The line keeps its bytes.");
        Assertions.assertEquals(deadline, lines.getDeadline(FIRST_LINE), "The line keeps its deadline.");
        Assertions.assertTrue(lines.isTriggerText(FIRST_LINE), "The line keeps its trigger flag.");
    }

    @Test
    void map_text_translation_off_asks_for_no_trigger_text() {
        MessageLines lines = createLines(SINGLE_LINE);
        RecordingTranslator translator = new RecordingTranslator();
        MessageLineTranslations translations = install(lines, SINGLE_LINE, new TextTranslation(translator, CONTEXTS, true, false), EVERY_SENDER);

        lines.writeTriggerText("Défendez la base".getBytes(StandardCharsets.UTF_8), true, 0);
        writeMemory(lines, MEMORY_LINE, "Tenez bon");

        Assertions.assertNull(translations.findSubstitute(MEMORY_LINE, lines.getLine(MEMORY_LINE), HOST_CLOCK));
        Assertions.assertNull(translations.findSubstitute(MEMORY_LINE, lines.getLine(MEMORY_LINE),
                                                          HOST_CLOCK + MessageLineTranslations.MEMORY_TEXT_SETTLE_MILLISECONDS));
        Assertions.assertEquals(List.of(), translator.getRequests());
    }

    @Test
    void a_line_written_by_memory_is_translated_once_its_bytes_settle() {
        MessageLines lines = createLines(SINGLE_LINE);
        RecordingTranslator translator = new RecordingTranslator();
        MessageLineTranslations translations = install(lines, SINGLE_LINE, new TextTranslation(translator, CONTEXTS, false, true), EVERY_SENDER);
        int settled = HOST_CLOCK + MessageLineTranslations.MEMORY_TEXT_SETTLE_MILLISECONDS;

        writeMemory(lines, MEMORY_LINE, "Tenez bon");

        Assertions.assertNull(translations.findSubstitute(MEMORY_LINE, lines.getLine(MEMORY_LINE), HOST_CLOCK));
        Assertions.assertNull(translations.findSubstitute(MEMORY_LINE, lines.getLine(MEMORY_LINE), settled - 1));
        Assertions.assertEquals(List.of(), translator.getRequests(), "Bytes that have not settled are not asked for.");
        Assertions.assertNull(translations.findSubstitute(MEMORY_LINE, lines.getLine(MEMORY_LINE), settled));
        Assertions.assertEquals("Tenez bon", translator.getRequests().getFirst().getText());

        translator.answer(0, "Hold on");

        ScrMessageSubstitute substitute = translations.findSubstitute(MEMORY_LINE, lines.getLine(MEMORY_LINE), settled);

        Assertions.assertNotNull(substitute);
        Assertions.assertEquals("Hold on", substitute.getText());
        Assertions.assertArrayEquals("Tenez bon".getBytes(StandardCharsets.UTF_8), lines.getLine(MEMORY_LINE), "The line keeps its bytes.");

        writeMemory(lines, MEMORY_LINE, "Tenez fort");

        Assertions.assertNull(translations.findSubstitute(MEMORY_LINE, lines.getLine(MEMORY_LINE), settled), "Changed bytes show as they are.");
        Assertions.assertNull(translations.findSubstitute(MEMORY_LINE, lines.getLine(MEMORY_LINE), settled + 1));
        Assertions.assertEquals(1, translator.getRequests().size(), "The changed bytes wait until they settle again.");
    }

    @Test
    void a_message_written_over_a_memory_line_is_not_translated() {
        MessageLines lines = createLines(SINGLE_LINE);
        RecordingTranslator translator = new RecordingTranslator();
        MessageLineTranslations translations = install(lines, SINGLE_LINE, new TextTranslation(translator, CONTEXTS, false, true), EVERY_SENDER);

        writeMemory(lines, FIRST_LINE, "Tenez bon");
        Assertions.assertNull(translations.findSubstitute(FIRST_LINE, lines.getLine(FIRST_LINE), HOST_CLOCK));

        lines.writeGameMessage("Nuclear launch detected.".getBytes(StandardCharsets.UTF_8), 0);

        Assertions.assertNull(translations.findSubstitute(FIRST_LINE, lines.getLine(FIRST_LINE), HOST_CLOCK));
        Assertions.assertNull(translations.findSubstitute(FIRST_LINE, lines.getLine(FIRST_LINE),
                                                          HOST_CLOCK + MessageLineTranslations.MEMORY_TEXT_SETTLE_MILLISECONDS));
        Assertions.assertEquals(List.of(), translator.getRequests());
    }

    @Test
    void text_art_written_by_memory_is_not_asked_for() {
        MessageLines lines = createLines(SINGLE_LINE);
        RecordingTranslator translator = new RecordingTranslator();
        MessageLineTranslations translations = install(lines, SINGLE_LINE, new TextTranslation(translator, CONTEXTS, false, true), EVERY_SENDER);

        writeMemory(lines, MEMORY_LINE, "|==#==|  ##  |==#==| ok");

        Assertions.assertNull(translations.findSubstitute(MEMORY_LINE, lines.getLine(MEMORY_LINE), HOST_CLOCK));
        Assertions.assertNull(translations.findSubstitute(MEMORY_LINE, lines.getLine(MEMORY_LINE),
                                                          HOST_CLOCK + MessageLineTranslations.MEMORY_TEXT_SETTLE_MILLISECONDS));
        Assertions.assertEquals(List.of(), translator.getRequests());
    }

    @Test
    void a_status_row_written_by_memory_is_asked_for_as_a_template() {
        MessageLines lines = createLines(SINGLE_LINE);
        RecordingTranslator translator = new RecordingTranslator();
        MessageLineTranslations translations = install(lines, SINGLE_LINE, new TextTranslation(translator, CONTEXTS, false, true), EVERY_SENDER);
        int settled = HOST_CLOCK + MessageLineTranslations.MEMORY_TEXT_SETTLE_MILLISECONDS;

        writeMemory(lines, MEMORY_LINE, "\u0013\u0004:+: \u001FWave: \u00047 \u0002| \u0011시대: \u0004고대시대 \u0002| \u0008적군 수: \u000412/300 \u0004:+:");

        Assertions.assertNull(translations.findSubstitute(MEMORY_LINE, lines.getLine(MEMORY_LINE), HOST_CLOCK));
        Assertions.assertNull(translations.findSubstitute(MEMORY_LINE, lines.getLine(MEMORY_LINE), settled));
        Assertions.assertEquals("\u0013\u0004:+: \u001FWave: \u0004101 \u0002| \u0011시대: \u0004고대시대 \u0002| \u0008적군 수: \u0004102/103 \u0004:+:",
                                translator.getRequests().getFirst().getText());

        translator.answer(0, "\u0013\u0004:+: \u001FWave: \u0004101 \u0002| \u0011Era: \u0004Ancient \u0002| \u0008Enemies: \u0004102/103 \u0004:+:");

        ScrMessageSubstitute substitute = translations.findSubstitute(MEMORY_LINE, lines.getLine(MEMORY_LINE), settled);

        Assertions.assertNotNull(substitute);
        Assertions.assertEquals("\u0013\u0004:+: \u001FWave: \u00047 \u0002| \u0011Era: \u0004Ancient \u0002| \u0008Enemies: \u000412/300 \u0004:+:",
                                substitute.getText());
    }

    @Test
    void the_system_line_written_by_memory_is_translated_until_the_game_writes_it() {
        MessageLines lines = createLines(SINGLE_LINE);
        RecordingTranslator translator = new RecordingTranslator();
        MessageLineTranslations translations = install(lines, SINGLE_LINE, new TextTranslation(translator, CONTEXTS, false, true), EVERY_SENDER);
        int settled = HOST_CLOCK + MessageLineTranslations.MEMORY_TEXT_SETTLE_MILLISECONDS;

        writeMemory(lines, MessageLines.SYSTEM_LINE, "Tenez bon");

        Assertions.assertNull(translations.findSubstitute(MessageLines.SYSTEM_LINE, lines.getLine(MessageLines.SYSTEM_LINE), HOST_CLOCK));
        Assertions.assertNull(translations.findSubstitute(MessageLines.SYSTEM_LINE, lines.getLine(MessageLines.SYSTEM_LINE), settled));
        Assertions.assertEquals("Tenez bon", translator.getRequests().getFirst().getText());

        translator.answer(0, "Hold on");

        ScrMessageSubstitute substitute = translations.findSubstitute(MessageLines.SYSTEM_LINE,
                                                                      lines.getLine(MessageLines.SYSTEM_LINE),
                                                                      settled);

        Assertions.assertNotNull(substitute);
        Assertions.assertEquals("Hold on", substitute.getText());

        lines.writeSystemLine("Invalid location.".getBytes(StandardCharsets.UTF_8), HOST_CLOCK);

        Assertions.assertNull(translations.findSubstitute(MessageLines.SYSTEM_LINE, lines.getLine(MessageLines.SYSTEM_LINE), settled),
                              "A text the game writes shows its bytes.");
        Assertions.assertEquals(1, translator.getRequests().size());
    }

    @Test
    void a_memory_line_whose_numbers_changed_shows_the_known_translation_at_once() {
        MessageLines lines = createLines(SINGLE_LINE);
        RecordingTranslator translator = new RecordingTranslator();
        MessageLineTranslations translations = install(lines, SINGLE_LINE, new TextTranslation(translator, CONTEXTS, false, true), EVERY_SENDER);
        int settled = HOST_CLOCK + MessageLineTranslations.MEMORY_TEXT_SETTLE_MILLISECONDS;

        writeMemory(lines, MEMORY_LINE, "Vague 3 sur 10");
        Assertions.assertNull(translations.findSubstitute(MEMORY_LINE, lines.getLine(MEMORY_LINE), HOST_CLOCK));
        Assertions.assertNull(translations.findSubstitute(MEMORY_LINE, lines.getLine(MEMORY_LINE), settled));

        translator.answer(0, "Wave 101 of 102");

        ScrMessageSubstitute first = translations.findSubstitute(MEMORY_LINE, lines.getLine(MEMORY_LINE), settled);

        Assertions.assertNotNull(first);
        Assertions.assertEquals("Wave 3 of 10", first.getText());

        writeMemory(lines, MEMORY_LINE, "Vague 4 sur 10");

        ScrMessageSubstitute changed = translations.findSubstitute(MEMORY_LINE, lines.getLine(MEMORY_LINE), settled + 1);

        Assertions.assertNotNull(changed, "A row whose translation is known does not wait for its bytes to settle.");
        Assertions.assertEquals("Wave 4 of 10", changed.getText());
        Assertions.assertEquals(1, translator.getRequests().size());

        writeMemory(lines, MEMORY_LINE, "Vague finale");

        Assertions.assertNull(translations.findSubstitute(MEMORY_LINE, lines.getLine(MEMORY_LINE), settled + 2),
                              "A text not answered yet shows its bytes until it settles.");
        Assertions.assertEquals(1, translator.getRequests().size());
    }

    @Test
    void the_system_line_a_map_writes_over_the_games_text_in_each_frame_is_translated() {
        MessageLines lines = createLines(SINGLE_LINE);
        RecordingTranslator translator = new RecordingTranslator();
        MessageLineTranslations translations = install(lines, SINGLE_LINE, new TextTranslation(translator, CONTEXTS, false, true), EVERY_SENDER);
        int settled = HOST_CLOCK + MessageLineTranslations.MEMORY_TEXT_SETTLE_MILLISECONDS;

        for (int time = HOST_CLOCK; time < settled + FRAME_MILLISECONDS; time += FRAME_MILLISECONDS) {
            lines.writeSystemLine("Invalid location.".getBytes(StandardCharsets.UTF_8), time);
            writeMemory(lines, MessageLines.SYSTEM_LINE, "Tenez bon\0");

            Assertions.assertNull(translations.findSubstitute(MessageLines.SYSTEM_LINE, lines.getLine(MessageLines.SYSTEM_LINE), time));
        }

        Assertions.assertEquals(List.of("Tenez bon"), translator.getRequests().stream().map(TranslationRequest::getText).toList());

        translator.answer(0, "Hold on");
        lines.writeSystemLine("Invalid location.".getBytes(StandardCharsets.UTF_8), settled);
        writeMemory(lines, MessageLines.SYSTEM_LINE, "Tenez bon\0");

        ScrMessageSubstitute substitute = translations.findSubstitute(MessageLines.SYSTEM_LINE,
                                                                      lines.getLine(MessageLines.SYSTEM_LINE),
                                                                      settled);

        Assertions.assertNotNull(substitute);
        Assertions.assertEquals("Hold on", substitute.getText());
    }

    @Test
    void lines_written_before_the_translations_existed_are_translated_once_their_bytes_settle() {
        MessageLines lines = createLines(SINGLE_LINE);
        RecordingTranslator translator = new RecordingTranslator();
        int settled = HOST_CLOCK + MessageLineTranslations.MEMORY_TEXT_SETTLE_MILLISECONDS;

        writeMemory(lines, MessageLines.SYSTEM_LINE, "Tenez bon");
        lines.writeTriggerText("Gardez la base".getBytes(StandardCharsets.UTF_8), 0);

        MessageLineTranslations translations = install(lines, SINGLE_LINE, new TextTranslation(translator, CONTEXTS, false, true), EVERY_SENDER);

        Assertions.assertNull(translations.findSubstitute(MessageLines.SYSTEM_LINE, lines.getLine(MessageLines.SYSTEM_LINE), HOST_CLOCK));
        Assertions.assertNull(translations.findSubstitute(FIRST_LINE, lines.getLine(FIRST_LINE), HOST_CLOCK));
        Assertions.assertNull(translations.findSubstitute(MessageLines.SYSTEM_LINE, lines.getLine(MessageLines.SYSTEM_LINE), settled));
        Assertions.assertNull(translations.findSubstitute(FIRST_LINE, lines.getLine(FIRST_LINE), settled));
        Assertions.assertEquals(List.of("Tenez bon", "Gardez la base"), translator.getRequests().stream().map(TranslationRequest::getText).toList());

        translator.answer(0, "Hold on");
        translator.answer(1, "Guard the base");

        ScrMessageSubstitute status = translations.findSubstitute(MessageLines.SYSTEM_LINE,
                                                                  lines.getLine(MessageLines.SYSTEM_LINE),
                                                                  settled);
        ScrMessageSubstitute message = translations.findSubstitute(FIRST_LINE, lines.getLine(FIRST_LINE), settled);

        Assertions.assertNotNull(status);
        Assertions.assertNotNull(message);
        Assertions.assertEquals("Hold on", status.getText());
        Assertions.assertEquals("Guard the base", message.getText());
    }

    @Test
    void lines_written_before_the_translations_existed_keep_their_bytes_with_map_text_translation_off() {
        MessageLines lines = createLines(SINGLE_LINE);
        RecordingTranslator translator = new RecordingTranslator();

        writeMemory(lines, MessageLines.SYSTEM_LINE, "Tenez bon");

        MessageLineTranslations translations = install(lines, SINGLE_LINE, new TextTranslation(translator, CONTEXTS, true, false), EVERY_SENDER);

        Assertions.assertNull(translations.findSubstitute(MessageLines.SYSTEM_LINE, lines.getLine(MessageLines.SYSTEM_LINE),
                                                          HOST_CLOCK + MessageLineTranslations.MEMORY_TEXT_SETTLE_MILLISECONDS));
        Assertions.assertEquals(List.of(), translator.getRequests());
    }

    @Test
    void a_trigger_text_laid_out_in_lines_is_asked_for_line_by_line_and_keeps_its_rows() {
        MessageLines lines = createLines(LINE_FEED_PIECES);
        RecordingTranslator translator = new RecordingTranslator();
        MessageLineTranslations translations = install(lines, LINE_FEED_PIECES, new TextTranslation(translator, CONTEXTS, false, true), EVERY_SENDER);
        String pairSpaces = " ".repeat(86);

        lines.writeTriggerText(SPEED_MENU.getBytes(StandardCharsets.UTF_8), true, 0);

        Assertions.assertEquals(List.of("\u0013\u0007101배속\r\n",
                                        "\u0013\u0003게임 배속을 선택하세요!\r\n",
                                        "\u0013\u0007101배속" + pairSpaces + "\u0007    102배속\r\n",
                                        "\u0013\u0004101배속은 렉 때문에 적용이 잘 안될 수 있습니다.\r\n",
                                        "\u0013\u0007101배속"),
                                translator.getRequests().stream().map(TranslationRequest::getText).toList(),
                                "Each line with letters is asked for by itself with its own digit placeholders; blank lines are not asked for.");

        translator.answer(1, "\u0013\u0003Choose the game speed!\r\n");

        Assertions.assertEquals("\u0013\u0003Choose the game speed!\r\n", findRowText(translations, lines, TITLE_ROW));
        Assertions.assertNull(findRowText(translations, lines, FIRST_SPEED_ROW), "A line shows its bytes until its own translation comes.");

        translator.answer(0, "\u0013\u0007101x speed\r\n");
        translator.answer(2, "\u0013\u0007101x speed" + pairSpaces + "\u0007    102x speed\r\n");
        translator.answer(3, "\u0013\u0004101x speed may not apply well because of lag.\r\n");
        translator.answer(4, "\u0013\u0007101x speed");

        Assertions.assertEquals("\u0013\u00071x speed\r\n", findRowText(translations, lines, FIRST_SPEED_ROW));
        Assertions.assertEquals("\u0013\u00074x speed" + pairSpaces + "\u0007    2x speed\r\n", findRowText(translations, lines, SPEED_PAIR_ROW));
        Assertions.assertEquals("\u0013\u00044x speed may not apply well because of lag.\r\n", findRowText(translations, lines, WARNING_ROW));
        Assertions.assertEquals("\u0013\u00073x speed", findRowText(translations, lines, LAST_SPEED_ROW));

        for (int row = FIRST_SPEED_ROW + 1; row < TITLE_ROW; row++) {
            Assertions.assertNull(findRowText(translations, lines, row), "A blank row shows its bytes.");
        }

        Assertions.assertEquals(5, translator.getRequests().size(), "The answers ask for nothing more.");
    }

    @Test
    void a_trigger_text_longer_than_the_rotating_lines_translates_the_rows_still_shown() {
        MessageLines lines = createLines(LINE_FEED_PIECES);
        RecordingTranslator translator = new RecordingTranslator();
        MessageLineTranslations translations = install(lines, LINE_FEED_PIECES, new TextTranslation(translator, CONTEXTS, false, true), EVERY_SENDER);
        StringBuilder text = new StringBuilder();

        for (int row = 0; row < MESSAGE_ROWS; row++) {
            text.append("Ligne ").append((char) ('a' + row)).append(row + 1 < MESSAGE_ROWS ? "\n" : "");
        }

        lines.writeTriggerText(text.toString().getBytes(StandardCharsets.UTF_8), true, 0);

        int hiddenRows = MESSAGE_ROWS - MessageLines.ROTATING_LINES;

        Assertions.assertEquals(MessageLines.ROTATING_LINES, translator.getRequests().size(), "Only the rows still shown are asked for.");
        Assertions.assertEquals("Ligne " + (char) ('a' + hiddenRows) + "\n", translator.getRequests().getFirst().getText());

        for (int request = 0; request < MessageLines.ROTATING_LINES; request++) {
            translator.answer(request, translator.getRequests().get(request).getText().replace("Ligne", "Line"));
        }

        for (int row = hiddenRows; row < MESSAGE_ROWS; row++) {
            String expected = "Line " + (char) ('a' + row) + (row + 1 < MESSAGE_ROWS ? "\n" : "");

            Assertions.assertEquals(expected, findRowText(translations, lines, row));
        }
    }
}
