package org.fogofwarlabs.sc4j.screnhanced.translation;

import org.fogofwarlabs.sc4j.translate.Translation;
import org.fogofwarlabs.sc4j.translate.TranslationRequest;
import org.fogofwarlabs.sc4j.translate.TranslationStatus;
import org.fogofwarlabs.sc4j.translate.Translator;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Records the translation requests and answers each one when a test answers it, on another thread as a translator's worker does.
 */
public final class RecordingTranslator implements Translator {
    private final @NotNull List<@NotNull TranslationRequest> requests;
    private final @NotNull List<@NotNull CompletableFuture<@NotNull Translation>> answers;

    /**
     * Creates a {@link RecordingTranslator} instance without requests.
     */
    public RecordingTranslator() {
        this.requests = new ArrayList<>();
        this.answers  = new ArrayList<>();
    }

    /**
     * @return the requests in the order they came
     */
    public @NotNull List<@NotNull TranslationRequest> getRequests() {
        return this.requests;
    }

    /**
     * Answers a request with a translated text on another thread and waits until the answer is in.
     *
     * @param index          the index of the request
     * @param translatedText the translated text
     */
    public void answer(int index, @NotNull String translatedText) {
        String text = this.requests.get(index).getText();
        CompletableFuture<Translation> answer = this.answers.get(index);

        CompletableFuture.runAsync(() -> answer.complete(new Translation(text, translatedText, Locale.KOREAN, TranslationStatus.TRANSLATED))).join();
    }

    /**
     * Translates a text.
     *
     * @param request the request
     * @return the future of the translation, completed on a worker thread or at once
     * @implSpec Records the request and returns a future that {@link #answer} completes.
     */
    @Override
    public @NotNull CompletableFuture<@NotNull Translation> translate(@NotNull TranslationRequest request) {
        CompletableFuture<Translation> answer = new CompletableFuture<>();

        this.requests.add(request);
        this.answers.add(answer);

        return answer;
    }
}
