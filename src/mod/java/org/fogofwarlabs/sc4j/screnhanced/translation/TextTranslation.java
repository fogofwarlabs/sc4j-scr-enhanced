package org.fogofwarlabs.sc4j.screnhanced.translation;

import org.fogofwarlabs.sc4j.scr.client.extension.ScrMessageAreaPresenter;
import org.fogofwarlabs.sc4j.scr.client.extension.ScrShownText;
import org.fogofwarlabs.sc4j.scr.client.extension.ScrTextPresenter;
import org.fogofwarlabs.sc4j.scr.game.presentation.MessageLines;
import org.fogofwarlabs.sc4j.translate.Translation;
import org.fogofwarlabs.sc4j.translate.TranslationPriority;
import org.fogofwarlabs.sc4j.translate.TranslationRequest;
import org.fogofwarlabs.sc4j.translate.Translator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntPredicate;

/**
 * Asks for the translations of the texts the client shows: other players' chat texts while the mod setting {@code translateChat} turns chat
 * translation on, and the texts of the map while the mod setting {@code translateMapText} turns map-text translation on. The color and
 * control characters of a text (below {@code U+0020}) are protected spans, so they stay as they are and in their places between the
 * translated runs. Every request carries the {@link TranslationContexts translation context} of its kind of text ({@link TextKind#CHAT} or
 * {@link TextKind#MAP_TEXT}) and target language, and is sent once that context is built. A translation is for display only: the callers
 * draw it in place of the text and keep the text itself.
 * <p>
 * A map text is asked for once: the answers of the most recent map texts are kept by their exact text, so a text shown on every frame costs
 * a map lookup. Before a map text is asked for, each run of digits becomes a placeholder number, so texts that differ only in their numbers,
 * such as a counter, share one translation; the digits are put back into the translation. When the placeholders do not all come back
 * exactly once, the exact text is asked for instead. Map texts that are mostly symbols or isolated letters, such as text art, are not
 * asked for ({@link #isTranslatable}).
 * <p>
 * The translator answers on a worker thread; every method of this class and of its answers runs on the client's UI thread, which reads a
 * future only once it is done. A request made before its context is built is sent from the thread that builds the context.
 */
public final class TextTranslation implements ScrTextPresenter {
    /**
     * The first character that is not a control character.
     */
    private static final char FIRST_PRINTABLE_CHARACTER = ' ';
    /**
     * The most map texts whose answers are kept, and the most asked texts whose futures are kept.
     */
    private static final int CACHED_TEXT_LIMIT = 256;
    private static final float CACHE_LOAD_FACTOR = 0.75f;
    /**
     * The number that stands for the first digit run of a map text; the next runs take the next numbers. Three-digit numbers stay as
     * they are in the translations and get no digit grouping.
     */
    private static final int FIRST_DIGIT_PLACEHOLDER = 101;
    /**
     * The most digit runs a map text replaces with placeholders; a text with more is asked for as it is.
     */
    private static final int DIGIT_PLACEHOLDER_LIMIT = 9;
    /**
     * The number of digits of every placeholder number.
     */
    private static final int PLACEHOLDER_LENGTH = 3;
    private static final int DECIMAL_RADIX = 10;
    /**
     * The least share of letters among the counted characters of a map text that is asked for ({@link #isTranslatable}), in percent.
     */
    private static final int MINIMUM_LETTER_PERCENT = 50;
    private static final int PERCENT = 100;

    /**
     * The answer to one asked text, read on the UI thread.
     */
    public static final class Answer implements ScrShownText {
        private final @NotNull TextTranslation owner;
        private final @NotNull String text;
        private final @NotNull Locale target;

        /**
         * The digit runs the placeholders of the asked template stand for, or {@code null} when the exact text was asked for.
         */
        private @NotNull String @Nullable [] digitRuns;
        private @Nullable CompletableFuture<@NotNull Translation> pending;
        private @Nullable String translatedText;

        private Answer(@NotNull TextTranslation owner, @NotNull String text, @NotNull Locale target, @NotNull String @Nullable [] digitRuns,
                       @Nullable CompletableFuture<@NotNull Translation> pending) {
            this.owner  = owner;
            this.text   = text;
            this.target = target;

            this.digitRuns      = digitRuns;
            this.pending        = pending;
            this.translatedText = null;
        }

        /**
         * Returns the text shown in place of the asked text. A template whose placeholders do not all come back exactly once asks for the
         * exact text on this call.
         *
         * @return the translated text once the translation came and the text was translated, or {@code null} otherwise
         */
        public @Nullable String findTranslatedText() {
            CompletableFuture<Translation> future = this.pending;

            if (future == null) {
                return this.translatedText;
            }

            if (!future.isDone()) {
                return null;
            }

            this.pending = null;

            String translated = readTranslatedText(future);
            String[] runs = this.digitRuns;

            if (runs != null && translated != null) {
                translated = restoreDigits(translated, runs);

                if (translated == null) {
                    this.digitRuns = null;
                    this.pending   = this.owner.requestCached(this.text, this.target);

                    return null;
                }
            }

            this.translatedText = translated;

            return translated;
        }

        /**
         * @return the translated text once the translation came and the text was translated, or {@code null} otherwise ({@link #findTranslatedText})
         */
        @Override
        public @Nullable String findShownText() {
            return this.findTranslatedText();
        }
    }

    /**
     * Returns the translated text of a done future.
     *
     * @param future the future of the translation
     * @return the translated text when the text was translated, or {@code null} otherwise
     */
    private static @Nullable String readTranslatedText(@NotNull CompletableFuture<@NotNull Translation> future) {
        if (future.isCompletedExceptionally()) {
            return null;
        }

        Translation translation = future.join();

        return translation.isTranslated() ? translation.getTranslatedText() : null;
    }

    /**
     * Tests whether a map text is worth a translation, so text art and symbol rows are left as they are. Digits are left out, as they
     * become placeholders, and so are white space and control characters; of the other characters a run of one repeated character
     * counts once. The text is worth a translation when at least {@value #MINIMUM_LETTER_PERCENT} percent of the counted characters are
     * letters and it has words: runs of at least two letters that are not all the same letter, at least as many as the runs of a single
     * letter. A text that is one letter by itself is also worth a translation.
     *
     * @param text the text
     * @return {@code true} if the text is worth a translation, {@code false} otherwise
     */
    public static boolean isTranslatable(@NotNull String text) {
        int counted = 0;
        int letters = 0;
        int words = 0;
        int singleLetters = 0;
        int previous = -1;
        int runFirst = -1;
        int runLength = 0;
        boolean runMixed = false;

        for (int index = 0; index <= text.length(); ) {
            int codePoint = index < text.length() ? text.codePointAt(index) : -1;

            index += codePoint < 0 ? 1 : Character.charCount(codePoint);

            boolean letter = codePoint >= 0 && Character.isLetter(codePoint);

            if (letter && runLength > 0) {
                runLength++;
                runMixed |= codePoint != runFirst;
            } else if (letter) {
                runFirst  = codePoint;
                runLength = 1;
                runMixed  = false;
            } else if (runLength > 0) {
                if (runLength == 1) {
                    singleLetters++;
                } else if (runMixed) {
                    words++;
                }

                runLength = 0;
            }

            if (codePoint <= FIRST_PRINTABLE_CHARACTER || Character.isWhitespace(codePoint) || Character.isDigit(codePoint)) {
                previous = -1;

                continue;
            }

            if (codePoint != previous) {
                counted++;

                if (letter) {
                    letters++;
                }
            }

            previous = codePoint;
        }

        boolean worded = words > 0 ? words >= singleLetters : singleLetters == 1 && counted == 1;

        return worded && letters * PERCENT >= counted * MINIMUM_LETTER_PERCENT;
    }

    /**
     * Collects the runs of ASCII digits of a text.
     *
     * @param text the text
     * @return the digit runs in text order
     */
    private static @NotNull List<@NotNull String> findDigitRuns(@NotNull String text) {
        List<String> runs = new ArrayList<>();
        int index = 0;

        while (index < text.length()) {
            if (!isDigit(text.charAt(index))) {
                index++;

                continue;
            }

            int start = index;

            while (index < text.length() && isDigit(text.charAt(index))) {
                index++;
            }

            runs.add(text.substring(start, index));
        }

        return runs;
    }

    /**
     * Replaces each digit run of a text with its placeholder number.
     *
     * @param text the text
     * @return the template
     */
    private static @NotNull String createTemplate(@NotNull String text) {
        StringBuilder template = new StringBuilder(text.length());
        int placeholder = FIRST_DIGIT_PLACEHOLDER;
        int index = 0;

        while (index < text.length()) {
            if (!isDigit(text.charAt(index))) {
                template.append(text.charAt(index++));

                continue;
            }

            while (index < text.length() && isDigit(text.charAt(index))) {
                index++;
            }

            template.append(placeholder++);
        }

        return template.toString();
    }

    /**
     * Puts the digit runs back in place of their placeholder numbers in a translated template.
     *
     * @param translated the translated template
     * @param digitRuns  the digit runs, the first for {@value #FIRST_DIGIT_PLACEHOLDER}
     * @return the translated text, or {@code null} when a placeholder is missing or repeated or the translation has other digits
     */
    private static @Nullable String restoreDigits(@NotNull String translated, @NotNull String @NotNull [] digitRuns) {
        StringBuilder restored = new StringBuilder(translated.length());
        boolean[] placed = new boolean[digitRuns.length];
        int index = 0;

        while (index < translated.length()) {
            if (!isDigit(translated.charAt(index))) {
                restored.append(translated.charAt(index++));

                continue;
            }

            int start = index;

            while (index < translated.length() && isDigit(translated.charAt(index))) {
                index++;
            }

            int run = parsePlaceholder(translated, start, index) - FIRST_DIGIT_PLACEHOLDER;

            if (run < 0 || run >= digitRuns.length || placed[run]) {
                return null;
            }

            placed[run] = true;
            restored.append(digitRuns[run]);
        }

        for (boolean runPlaced : placed) {
            if (!runPlaced) {
                return null;
            }
        }

        return restored.toString();
    }

    /**
     * Reads a digit run of a translated template as a placeholder number.
     *
     * @param translated the translated template
     * @param start      the index of the run's first digit
     * @param end        the index after the run's last digit
     * @return the number, or {@code -1} when the run cannot be a placeholder
     */
    private static int parsePlaceholder(@NotNull String translated, int start, int end) {
        return end - start == PLACEHOLDER_LENGTH && translated.charAt(start) != '0' ? Integer.parseInt(translated, start, end, DECIMAL_RADIX) : -1;
    }

    /**
     * Creates a map that keeps its {@value #CACHED_TEXT_LIMIT} most recently used entries.
     *
     * @param <V> the value type
     * @return the created map
     */
    private static <V> @NotNull LinkedHashMap<@NotNull String, V> createRecentMap() {
        return new LinkedHashMap<>(CACHED_TEXT_LIMIT, CACHE_LOAD_FACTOR, true) {
            /**
             * @param eldest the least recently used entry
             * @return {@code true} if the map holds more than {@value #CACHED_TEXT_LIMIT} entries, {@code false} otherwise
             */
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return this.size() > CACHED_TEXT_LIMIT;
            }
        };
    }

    private static boolean isDigit(char character) {
        return character >= '0' && character <= '9';
    }


    private final @NotNull Translator translator;
    private final @NotNull TranslationContexts contexts;
    private final boolean chatEnabled;
    private final boolean mapTextEnabled;

    /**
     * The answers of the most recent map texts by their exact text, least recently used first.
     */
    private final @NotNull LinkedHashMap<@NotNull String, @NotNull Answer> answers;
    /**
     * The futures of the most recently asked map texts and templates by the asked text, least recently used first.
     */
    private final @NotNull LinkedHashMap<@NotNull String, @NotNull CompletableFuture<@NotNull Translation>> requests;
    /**
     * The language the cached answers are in, or {@code null} before the first map text.
     */
    private @Nullable Locale cachedTarget;

    /**
     * Creates a {@link TextTranslation} instance.
     *
     * @param translator     the translator of the run
     * @param contexts       the translation contexts of the requests
     * @param chatEnabled    whether chat translation is on
     * @param mapTextEnabled whether map-text translation is on
     */
    public TextTranslation(@NotNull Translator translator, @NotNull TranslationContexts contexts, boolean chatEnabled, boolean mapTextEnabled) {
        this.translator     = translator;
        this.contexts       = contexts;
        this.chatEnabled    = chatEnabled;
        this.mapTextEnabled = mapTextEnabled;

        this.answers      = createRecentMap();
        this.requests     = createRecentMap();
        this.cachedTarget = null;
    }

    /**
     * @return {@code true} if chat translation is on, {@code false} otherwise
     */
    public boolean isChatEnabled() {
        return this.chatEnabled;
    }

    /**
     * @return {@code true} if map-text translation is on, {@code false} otherwise
     */
    public boolean isMapTextEnabled() {
        return this.mapTextEnabled;
    }

    /**
     * @return {@code true} if map-text translation is on, {@code false} otherwise
     */
    @Override
    public boolean presentsMapTexts() {
        return this.mapTextEnabled;
    }

    /**
     * Returns how a chat text of another player in a chat log is shown: its translation once it came ({@link #translateChat}).
     *
     * @param text            the text
     * @param protectedLength the length of the start of the text that stays as it is, such as the name an emote starts with, or {@code 0}
     * @param target          the language to translate into
     * @return the answer, or {@code null} while chat translation is off
     */
    @Override
    public @Nullable ScrShownText presentChat(@NotNull String text, int protectedLength, @NotNull Locale target) {
        return this.translateChat(text, protectedLength, target);
    }

    /**
     * Creates the translations of a game's message area ({@link MessageLineTranslations}).
     *
     * @param lines           the message lines
     * @param layout          the message area's layout
     * @param target          the language to translate into
     * @param otherHumanTexts tells whether the chat texts of a sender slot are those of another human player, which are translated
     * @return the created translations, or {@code null} while chat and map-text translation are off
     */
    @Override
    public @Nullable ScrMessageAreaPresenter createMessageArea(@NotNull MessageLines lines, MessageLines.@NotNull TextLayout layout,
                                                               @NotNull Locale target, @NotNull IntPredicate otherHumanTexts) {
        return this.chatEnabled || this.mapTextEnabled ? new MessageLineTranslations(lines, layout, this, target, otherHumanTexts) : null;
    }

    /**
     * Asks for the translation of a chat text; its start and its control characters stay as they are.
     *
     * @param text            the text
     * @param protectedLength the length of the start of the text that stays as it is, such as the name an emote starts with, or {@code 0}
     * @param target          the language to translate into
     * @return the answer, or {@code null} while chat translation is off
     */
    public @Nullable Answer translateChat(@NotNull String text, int protectedLength, @NotNull Locale target) {
        if (!this.chatEnabled) {
            return null;
        }

        return new Answer(this, text, target, null, this.request(text, protectedLength, target, TextKind.CHAT));
    }

    /**
     * Returns the answer for a map text that reaches the screen, asking for its translation on the first call for the text.
     *
     * @param text   the text
     * @param target the language to translate into
     * @return the answer, which never translates a text that is not worth a translation ({@link #isTranslatable}), or {@code null} while map-text translation is off
     */
    public @Nullable Answer translateMapText(@NotNull String text, @NotNull Locale target) {
        if (!this.mapTextEnabled) {
            return null;
        }

        if (!target.equals(this.cachedTarget)) {
            this.answers.clear();
            this.requests.clear();

            this.cachedTarget = target;
        }

        Answer answer = this.answers.get(text);

        if (answer != null) {
            return answer;
        }

        List<String> runs = findDigitRuns(text);

        if (!isTranslatable(text)) {
            answer = new Answer(this, text, target, null, null);
        } else if (runs.isEmpty() || runs.size() > DIGIT_PLACEHOLDER_LIMIT) {
            answer = new Answer(this, text, target, null, this.requestCached(text, target));
        } else {
            answer = new Answer(this, text, target, runs.toArray(String[]::new), this.requestCached(createTemplate(text), target));
        }

        this.answers.put(text, answer);

        return answer;
    }

    /**
     * Returns the translation of a map text when it is already known, without asking for it: the text was answered before, or it differs
     * from an answered text only in its digits.
     *
     * @param text   the text
     * @param target the language to translate into
     * @return the translated text, or {@code null} when it is not known yet, the text is not translated or map-text translation is off
     */
    public @Nullable String findKnownMapText(@NotNull String text, @NotNull Locale target) {
        if (!this.mapTextEnabled || !target.equals(this.cachedTarget)) {
            return null;
        }

        Answer answer = this.answers.get(text);

        if (answer == null) {
            List<String> runs = findDigitRuns(text);
            String asked = runs.isEmpty() || runs.size() > DIGIT_PLACEHOLDER_LIMIT ? text : createTemplate(text);
            CompletableFuture<Translation> future = this.requests.get(asked);

            if (future == null || !future.isDone() || !isTranslatable(text)) {
                return null;
            }

            answer = this.translateMapText(text, target);
        }

        return answer == null ? null : answer.findTranslatedText();
    }

    /**
     * Returns the text a map text shows: its translation once it came, the text itself until then and while map-text translation is off.
     *
     * @param text   the text
     * @param target the language to translate into
     * @return the shown text
     */
    @Override
    public @NotNull String presentMapText(@NotNull String text, @NotNull Locale target) {
        Answer answer = this.translateMapText(text, target);
        String translated = answer == null ? null : answer.findTranslatedText();

        return translated == null ? text : translated;
    }

    /**
     * Returns the future of an asked map text or template, asking for it when it is not among the recent ones.
     *
     * @param text   the asked text
     * @param target the language to translate into
     * @return the future of the translation
     */
    private @NotNull CompletableFuture<@NotNull Translation> requestCached(@NotNull String text, @NotNull Locale target) {
        CompletableFuture<Translation> future = this.requests.get(text);

        if (future == null) {
            future = this.request(text, 0, target, TextKind.MAP_TEXT);

            this.requests.put(text, future);
        }

        return future;
    }

    /**
     * Asks the translator for a text with its start and its control characters protected, with the context of its kind and target
     * language once that context is built.
     *
     * @param text            the text
     * @param protectedLength the length of the start of the text that stays as it is, or {@code 0}
     * @param target          the language to translate into
     * @param kind            the kind of the text
     * @return the future of the translation
     */
    private @NotNull CompletableFuture<@NotNull Translation> request(@NotNull String text, int protectedLength, @NotNull Locale target,
                                                                     @NotNull TextKind kind) {
        TranslationRequest.Builder request = new TranslationRequest.Builder(text, target).setPriority(TranslationPriority.CHAT);

        if (protectedLength > 0) {
            request.addProtectedSpan(0, protectedLength);
        }

        int index = protectedLength;

        while (index < text.length()) {
            if (text.charAt(index) >= FIRST_PRINTABLE_CHARACTER) {
                index++;

                continue;
            }

            int start = index;

            while (index < text.length() && text.charAt(index) < FIRST_PRINTABLE_CHARACTER) {
                index++;
            }

            request.addProtectedSpan(start, index);
        }

        return this.contexts.getContext(kind, target).thenCompose(context -> this.translator.translate(request.setContext(context).build()));
    }
}
