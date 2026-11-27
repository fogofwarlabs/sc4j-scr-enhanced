package org.fogofwarlabs.sc4j.screnhanced.translation;

import org.fogofwarlabs.sc4j.scr.client.extension.ScrMessageAreaPresenter;
import org.fogofwarlabs.sc4j.scr.client.extension.ScrMessageSubstitute;
import org.fogofwarlabs.sc4j.scr.data.format.text.LegacyTextUtils;
import org.fogofwarlabs.sc4j.scr.game.presentation.MessageLines;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.IntPredicate;

/**
 * Draws the texts of other players and of the map in the message area in another language. While chat translation is on, a line that shows
 * another player's text asks for the translation of its text at once; while map-text translation is on, so does a line that shows trigger
 * text ({@link MessageLines.TextListener}). Each paragraph of a message, its text up to and including a line feed, is asked for by itself,
 * so a text laid out in lines keeps its rows, its blank lines and the alignment and color codes each line starts with. A rotating line shows
 * its own bytes until the translation of its paragraph comes; then each rotating line of the paragraph shows the matching piece of the
 * sender prefix and the translated paragraph, wrapped and colored as the message area wraps and colors a written message. Pieces beyond the
 * paragraph's rotating lines are not shown, and a rotating line of the paragraph without a piece shows nothing. Of a message that takes more
 * pieces than the rotating lines, the paragraphs of the pieces still shown are translated.
 * <p>
 * While map-text translation is on, a rotating line or the system line whose bytes a memory write changed is translated by itself once its
 * bytes stayed the same for {@value #MEMORY_TEXT_SETTLE_MILLISECONDS} milliseconds, and shows its translation only while its bytes stay the
 * same; changed bytes whose translation is already known, such as a row whose numbers changed, show it at once. A text the game writes to
 * the system line shows its bytes, and the system line stays watched, so a map that has the game write the line and then writes its own
 * bytes over it in each frame shows the translation of its bytes. A rotating line or the system line that already shows a text when this
 * instance is created, such as a text a map wrote in the first frames before the console existed, is watched the same way. Lines that are
 * mostly symbols, such as text art, keep their bytes ({@link TextTranslation#isTranslatable}).
 * <p>
 * The substitution is for drawing only: the message lines keep their bytes, styles and deadlines. A rotating line whose bytes or deadline
 * differ from the ones written with the message, such as after a later message or a map's write to the line, shows its bytes from then on.
 * <p>
 * Every method runs on the thread that owns the message lines; a translation's future is read there once it is done.
 */
public final class MessageLineTranslations implements ScrMessageAreaPresenter {
    /**
     * The time in milliseconds that the bytes of a line changed by a memory write stay the same before the line is translated.
     */
    public static final int MEMORY_TEXT_SETTLE_MILLISECONDS = 250;

    /**
     * The most text bytes a rotating line keeps, its terminator excluded.
     */
    private static final int LINE_TEXT_LIMIT = MessageLines.LINE_SIZE - 1;
    private static final char TERMINATOR = '\0';
    private static final byte LINE_FEED = '\n';

    /**
     * One translated paragraph of a message: the part of the sender prefix in it, the answer for its text and the style and color of its
     * first shown piece.
     */
    private static final class Paragraph {
        private final @NotNull String prefix;
        private final TextTranslation.@NotNull Answer answer;
        private final int style;
        private final int color;

        private @Nullable List<@NotNull ScrMessageSubstitute> pieces;

        private Paragraph(@NotNull String prefix, TextTranslation.@NotNull Answer answer, int style, int color) {
            this.prefix = prefix;
            this.answer = answer;
            this.style  = style;
            this.color  = color;

            this.pieces = null;
        }

        /**
         * Returns the pieces of the translated paragraph, wrapped on the first call after the translation came.
         *
         * @param layout the message area's layout
         * @return the pieces, or {@code null} while the translation has not come or when the text was not translated
         */
        private @Nullable List<@NotNull ScrMessageSubstitute> findPieces(MessageLines.@NotNull TextLayout layout) {
            if (this.pieces != null) {
                return this.pieces;
            }

            String translated = this.answer.findTranslatedText();

            if (translated == null) {
                return null;
            }

            String shown = this.prefix + translated;
            int terminator = shown.indexOf(TERMINATOR);
            byte[] bytes = (terminator < 0 ? shown : shown.substring(0, terminator)).getBytes(StandardCharsets.UTF_8);
            List<ScrMessageSubstitute> wrapped = new ArrayList<>();

            if (bytes.length > 0) {
                int pieceStyle = this.style;
                int pieceColor = this.color;
                int start = 0;

                for (int end : layout.breakLines(bytes)) {
                    wrapped.add(new ScrMessageSubstitute(LegacyTextUtils.decode(bytes, start, Math.min(end - start, LINE_TEXT_LIMIT)), pieceStyle, pieceColor));

                    int nextStyle = MessageLines.findNextPieceStyle(bytes, start, end, pieceStyle);

                    if (nextStyle != pieceStyle) {
                        pieceColor = 0;
                    }

                    pieceStyle = nextStyle;
                    start      = end;
                }
            }

            this.pieces = wrapped;

            return wrapped;
        }
    }

    /**
     * A rotating line that shows a piece of a translated paragraph: the paragraph, the piece's index in it and the line's bytes and deadline
     * as the message wrote them.
     */
    private static final class Line {
        private final @NotNull Paragraph paragraph;
        private final int piece;
        private final byte @NotNull [] bytes;
        private final int deadline;

        private Line(@NotNull Paragraph paragraph, int piece, byte @NotNull [] bytes, int deadline) {
            this.paragraph = paragraph;
            this.piece     = piece;
            this.bytes     = bytes;
            this.deadline  = deadline;
        }
    }

    /**
     * A rotating line or the system line whose bytes a memory write changed: its bytes as last drawn, the time they were first drawn, the answer for them once
     * they settled and the substitute once the translation came.
     */
    private static final class MemoryLine {
        private byte @Nullable [] bytes;
        private int changeTime;
        private TextTranslation.@Nullable Answer answer;
        private @Nullable ScrMessageSubstitute substitute;

        private MemoryLine() {
            this.bytes      = null;
            this.changeTime = 0;
            this.answer     = null;
            this.substitute = null;
        }
    }


    private final @NotNull MessageLines lines;
    private final MessageLines.@NotNull TextLayout layout;
    private final @NotNull TextTranslation translation;
    private final @NotNull Locale target;
    private final @NotNull IntPredicate translatedSenders;

    private final @Nullable Line @NotNull [] shown;
    private final @Nullable MemoryLine @NotNull [] memoryLines;
    private byte @NotNull [] systemLineText;

    /**
     * Creates a {@link MessageLineTranslations} instance without translated lines.
     *
     * @param lines             the message lines
     * @param layout            the message area's layout, which wraps the translated messages
     * @param translation       the translation of other players' chat texts and of map texts
     * @param target            the language to translate into
     * @param translatedSenders tells whether the chat texts of a sender slot are translated, such as the slots of human players; the
     *                          whispers of players outside the game ({@link MessageLines#NO_SENDER_SLOT}) are always translated
     */
    public MessageLineTranslations(@NotNull MessageLines lines, MessageLines.@NotNull TextLayout layout, @NotNull TextTranslation translation,
                                   @NotNull Locale target, @NotNull IntPredicate translatedSenders) {
        this.lines             = lines;
        this.layout            = layout;
        this.translation       = translation;
        this.target            = target;
        this.translatedSenders = translatedSenders;

        this.shown          = new Line[MessageLines.LINE_COUNT];
        this.memoryLines    = new MemoryLine[MessageLines.LINE_COUNT];
        this.systemLineText = new byte[0];

        if (translation.isMapTextEnabled()) {
            for (int line = 0; line < MessageLines.LINE_COUNT; line++) {
                if ((line < MessageLines.ROTATING_LINES || line == MessageLines.SYSTEM_LINE) && lines.getLine(line).length > 0) {
                    this.memoryLines[line] = new MemoryLine();
                }
            }
        }
    }

    /**
     * Finds the text a rotating line or the system line shows in place of its bytes. A line whose bytes or deadline changed since its message was written
     * shows its bytes from then on; a line changed by a memory write shows its translation only while its bytes stay the same.
     *
     * @param line     the rotating line or {@link MessageLines#SYSTEM_LINE}
     * @param bytes    the line's bytes up to the first zero ({@link MessageLines#getLine}), not empty
     * @param hostTime the host clock of the drawn frame in milliseconds
     * @return the substitute, or {@code null} when the line shows its bytes
     */
    @Override
    public @Nullable ScrMessageSubstitute findSubstitute(int line, byte @NotNull [] bytes, int hostTime) {
        Line entry = this.shown[line];

        if (entry != null) {
            if (!Arrays.equals(bytes, entry.bytes) || this.lines.getDeadline(line) != entry.deadline) {
                this.shown[line] = null;

                return null;
            }

            List<ScrMessageSubstitute> pieces = entry.paragraph.findPieces(this.layout);

            if (pieces == null) {
                return null;
            }

            return entry.piece < pieces.size() ? pieces.get(entry.piece) : new ScrMessageSubstitute("", entry.paragraph.style, 0);
        }

        MemoryLine memory = this.memoryLines[line];

        if (memory == null || (line == MessageLines.SYSTEM_LINE && Arrays.equals(bytes, this.systemLineText))) {
            return null;
        }

        if (!Arrays.equals(bytes, memory.bytes)) {
            String known = this.translation.findKnownMapText(LegacyTextUtils.decode(bytes, 0, bytes.length), this.target);

            memory.bytes      = bytes;
            memory.changeTime = hostTime;
            memory.answer     = null;
            memory.substitute = known == null ? null : new ScrMessageSubstitute(known, this.lines.getStyle(line), this.lines.getColor(line));

            return memory.substitute;
        }

        if (memory.substitute != null) {
            return memory.substitute;
        }

        if (memory.answer == null) {
            if (hostTime - memory.changeTime < MEMORY_TEXT_SETTLE_MILLISECONDS) {
                return null;
            }

            memory.answer = this.translation.translateMapText(LegacyTextUtils.decode(bytes, 0, bytes.length), this.target);

            if (memory.answer == null) {
                return null;
            }
        }

        if (memory.substitute == null) {
            String translated = memory.answer.findTranslatedText();

            memory.substitute = translated == null ? null : new ScrMessageSubstitute(translated, this.lines.getStyle(line), this.lines.getColor(line));
        }

        return memory.substitute;
    }

    /**
     * Called when a line with the text of another player or of the map is written.
     *
     * @param source    the kind of the text
     * @param sender    the sender slot, or {@link MessageLines#NO_SENDER_SLOT} for a whisper from a player outside the game and for trigger
     *                  text
     * @param line      the formatted line bytes
     * @param textStart the index of the text in the line, after the sender prefix
     * @param firstLine the rotating line of the first piece; each next piece is on the next rotating line
     * @param lineCount the number of pieces
     * @implSpec Asks for the translation of each paragraph with a piece still shown when the text's kind is translated, and remembers the
     *         paragraph's shown rotating lines with their bytes and deadlines. A paragraph ends after a piece that ends with a line feed.
     */
    @Override
    public void onTextWritten(MessageLines.@NotNull TextSource source, int sender, byte @NotNull [] line, int textStart, int firstLine,
                              int lineCount) {
        if (textStart >= line.length) {
            return;
        }

        if (source != MessageLines.TextSource.TRIGGER_TEXT && sender != MessageLines.NO_SENDER_SLOT && !this.translatedSenders.test(sender)) {
            return;
        }

        int[] ends = this.layout.breakLines(line);

        if (ends.length != lineCount) {
            return;
        }

        int firstShownPiece = Math.max(0, lineCount - MessageLines.ROTATING_LINES);
        int paragraphStart = 0;
        int paragraphPiece = 0;

        for (int piece = 0; piece < lineCount; piece++) {
            int end = ends[piece];

            if (piece + 1 < lineCount && (end == 0 || line[end - 1] != LINE_FEED)) {
                continue;
            }

            if (piece >= firstShownPiece) {
                int shownPiece = Math.max(paragraphPiece, firstShownPiece);

                this.showParagraph(source, line, textStart, paragraphStart, end, firstLine, paragraphPiece, shownPiece, piece + 1);
            }

            paragraphStart = end;
            paragraphPiece = piece + 1;
        }
    }

    /**
     * Asks for the translation of one paragraph of a written message and remembers its shown rotating lines. A paragraph without text, or
     * with white space only, keeps its bytes.
     *
     * @param source         the kind of the text
     * @param line           the formatted line bytes
     * @param textStart      the index of the text in the line, after the sender prefix
     * @param start          the index of the paragraph's first byte
     * @param end            the index after the paragraph's last byte
     * @param firstLine      the rotating line of the message's first piece
     * @param paragraphPiece the index of the paragraph's first piece in the message
     * @param shownPiece     the index of the paragraph's first piece that is still shown
     * @param endPiece       the index after the paragraph's last piece
     */
    private void showParagraph(MessageLines.@NotNull TextSource source, byte @NotNull [] line, int textStart, int start, int end, int firstLine,
                               int paragraphPiece, int shownPiece, int endPiece) {
        int paragraphTextStart = Math.max(start, Math.min(end, textStart));

        if (paragraphTextStart >= end) {
            return;
        }

        String text = LegacyTextUtils.decode(line, paragraphTextStart, end - paragraphTextStart);

        if (text.isBlank()) {
            return;
        }

        TextTranslation.Answer answer = source == MessageLines.TextSource.TRIGGER_TEXT
                                        ? this.translation.translateMapText(text, this.target)
                                        : this.translation.translateChat(text, 0, this.target);

        if (answer == null) {
            return;
        }

        int firstShownLine = (firstLine + shownPiece) % MessageLines.ROTATING_LINES;
        String prefix = LegacyTextUtils.decode(line, start, paragraphTextStart - start);
        Paragraph paragraph = new Paragraph(prefix, answer, this.lines.getStyle(firstShownLine), this.lines.getColor(firstShownLine));

        for (int piece = shownPiece; piece < endPiece; piece++) {
            int shownLine = (firstLine + piece) % MessageLines.ROTATING_LINES;

            this.shown[shownLine] = new Line(paragraph, piece - paragraphPiece, this.lines.getLine(shownLine), this.lines.getDeadline(shownLine));
        }
    }

    /**
     * Called when a piece of any message is written to a rotating line, before {@link #onTextWritten} reports the message, and when a text
     * is written to the system line.
     *
     * @param line the rotating line or {@link MessageLines#SYSTEM_LINE}
     * @implSpec Forgets what a rotating line showed in place of its bytes; of the system line, keeps the text the game wrote, which shows
     *         its bytes, and keeps watching the line.
     */
    @Override
    public void onLineWritten(int line) {
        if (line == MessageLines.SYSTEM_LINE) {
            this.systemLineText = this.lines.getLine(line);

            return;
        }

        this.shown[line]       = null;
        this.memoryLines[line] = null;
    }

    /**
     * Called when a memory write changes a byte of a rotating line or of the system line.
     *
     * @param line the rotating line or {@link MessageLines#SYSTEM_LINE}
     * @implSpec Forgets the message the line showed and, while map-text translation is on, watches the line's bytes from then on.
     */
    @Override
    public void onLineMemoryWritten(int line) {
        this.shown[line] = null;

        if (this.memoryLines[line] == null && this.translation.isMapTextEnabled()) {
            this.memoryLines[line] = new MemoryLine();
        }
    }
}
