package org.fogofwarlabs.sc4j.screnhanced.translation;

import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.scr.data.format.casc.CascStorage;
import org.fogofwarlabs.sc4j.translate.GlossaryEntry;
import org.fogofwarlabs.sc4j.translate.TranslationContext;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Gives the {@link TranslationContext} of each kind of text and target language: the text kind and description of the {@link TextKind}
 * and a glossary of the game's names ({@link GameTerms}) and of the terms players write ({@link SlangGlossary}). The context of a kind and
 * target language is built once, when the game terms are read, and the same instance is given to every later request.
 */
public final class TranslationContexts {
    private static final @NotNull String READER_THREAD_NAME = "Translation glossary reader";

    /**
     * Creates a {@link TranslationContexts} instance that reads the game terms of an installation on a thread of its own; a failure to
     * read them is logged and leaves the glossary with the players' terms only.
     *
     * @param gameFolder the StarCraft: Remastered folder
     * @param logger     the logger of the mod
     * @return the created {@link TranslationContexts} instance
     */
    public static @NotNull TranslationContexts load(@NotNull Path gameFolder, @NotNull Logger logger) {
        Executor reader = task -> Thread.ofPlatform().daemon().name(READER_THREAD_NAME).start(task);

        return new TranslationContexts(CompletableFuture.supplyAsync(() -> readTerms(gameFolder, logger), reader), SlangGlossary.load());
    }

    /**
     * Creates a {@link TranslationContexts} instance with game terms that are already read.
     *
     * @param terms the game terms
     * @param slang the terms players write
     * @return the created {@link TranslationContexts} instance
     */
    public static @NotNull TranslationContexts create(@NotNull GameTerms terms, @NotNull SlangGlossary slang) {
        return new TranslationContexts(CompletableFuture.completedFuture(terms), slang);
    }

    /**
     * Creates the context of a kind of text and a target language: the text kind and description of the kind, the entries of the game
     * terms ({@link GameTerms#createEntries}) and the entries of the players' terms ({@link SlangGlossary#createEntries}) whose source
     * term, ignoring case, is not a source term of the game terms.
     *
     * @param kind   the kind of text
     * @param target the target language
     * @param terms  the game terms
     * @param slang  the terms players write
     * @return the created context
     */
    public static @NotNull TranslationContext createContext(@NotNull TextKind kind, @NotNull Locale target, @NotNull GameTerms terms,
                                                            @NotNull SlangGlossary slang) {
        TranslationContext.Builder context = new TranslationContext.Builder().setTextKind(kind.getLabel()).setDescription(kind.getDescription());
        Set<String> sourceTerms = new HashSet<>();

        for (GlossaryEntry entry : terms.createEntries(target)) {
            context.addGlossaryEntry(entry.getSourceTerm(), entry.getTarget(), entry.getTargetTerm());
            sourceTerms.add(entry.getSourceTerm().toLowerCase(Locale.ROOT));
        }

        for (GlossaryEntry entry : slang.createEntries(target)) {
            if (!sourceTerms.contains(entry.getSourceTerm().toLowerCase(Locale.ROOT))) {
                context.addGlossaryEntry(entry.getSourceTerm(), entry.getTarget(), entry.getTargetTerm());
            }
        }

        return context.build();
    }

    private static @NotNull GameTerms readTerms(@NotNull Path gameFolder, @NotNull Logger logger) {
        try (CascStorage storage = CascStorage.open(gameFolder)) {
            GameTerms terms = GameTerms.read(storage);

            logger.info("TRANSLATION GLOSSARY: game terms in {0}", terms.getLanguages());

            return terms;
        } catch (IOException | RuntimeException exception) {
            logger.warning("TRANSLATION GLOSSARY: the game terms of {0} cannot be read: {1}", gameFolder, exception.getMessage());

            return GameTerms.EMPTY;
        }
    }


    private final @NotNull CompletableFuture<@NotNull GameTerms> terms;
    private final @NotNull SlangGlossary slang;

    private final @NotNull Map<@NotNull TextKind, @NotNull Map<@NotNull Locale, @NotNull CompletableFuture<@NotNull TranslationContext>>> contexts;

    private TranslationContexts(@NotNull CompletableFuture<@NotNull GameTerms> terms, @NotNull SlangGlossary slang) {
        this.terms = terms;
        this.slang = slang;

        this.contexts = new EnumMap<>(TextKind.class);

        for (TextKind kind : TextKind.values()) {
            this.contexts.put(kind, new ConcurrentHashMap<>());
        }
    }

    /**
     * Returns the context of a kind of text and a target language, built on the first call for them once the game terms are read.
     *
     * @param kind   the kind of text
     * @param target the target language
     * @return the future of the context, the same future on every call for the kind and target language; it never fails
     */
    public @NotNull CompletableFuture<@NotNull TranslationContext> getContext(@NotNull TextKind kind, @NotNull Locale target) {
        return this.contexts.get(kind).computeIfAbsent(target, language -> this.terms.thenApply(terms -> createContext(kind, language, terms, this.slang)));
    }
}
