package org.fogofwarlabs.sc4j.screnhanced.translation;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.json.JsonReader;
import org.fogofwarlabs.sc4j.translate.GlossaryEntry;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Holds the terms and abbreviations players write in chat, with their meanings in other languages, read from the resource
 * {@value #RESOURCE_NAME} next to this class.
 * <p>
 * The resource is a JSON object with one member per language of the terms (a language tag such as {@code ko}); each is an object with one
 * member per term, whose value is an object of the meanings by the language tag of the meaning, such as
 * {@code {"ko": {"멀티": {"en": "expansion"}}}}.
 */
public final class SlangGlossary {
    /**
     * The name of the resource of the terms.
     */
    public static final @NotNull String RESOURCE_NAME = "slang.json";

    /**
     * One term with its meaning in one language.
     */
    private static final class Term {
        private final @NotNull Locale source;
        private final @NotNull String term;
        private final @NotNull Locale target;
        private final @NotNull String meaning;

        private Term(@NotNull Locale source, @NotNull String term, @NotNull Locale target, @NotNull String meaning) {
            this.source  = source;
            this.term    = term;
            this.target  = target;
            this.meaning = meaning;
        }
    }

    /**
     * Creates a {@link SlangGlossary} instance from the resource {@value #RESOURCE_NAME}.
     *
     * @return the created {@link SlangGlossary} instance
     * @throws UncheckedIOException if the resource is missing or malformed
     */
    public static @NotNull SlangGlossary load() {
        try (InputStream input = SlangGlossary.class.getResourceAsStream(RESOURCE_NAME)) {
            if (input == null) {
                throw Exceptions.newIOException("The resource {0} is missing.", RESOURCE_NAME);
            }

            return parse(input.readAllBytes());
        } catch (IOException exception) {
            throw Exceptions.newUncheckedIOException(exception);
        }
    }

    /**
     * Creates a {@link SlangGlossary} instance from the contents of a terms file.
     *
     * @param contents the file contents
     * @return the created {@link SlangGlossary} instance
     * @throws IOException if the contents are not an object of languages with objects of terms with objects of text meanings, or a term or
     *                     meaning is empty
     */
    public static @NotNull SlangGlossary parse(byte @NotNull [] contents) throws IOException {
        if (!(JsonReader.parse(contents) instanceof Map<?, ?> languages)) {
            throw Exceptions.newIOException("The terms must be an object of languages.");
        }

        List<Term> terms = new ArrayList<>();

        for (Map.Entry<?, ?> language : languages.entrySet()) {
            if (!(language.getValue() instanceof Map<?, ?> languageTerms)) {
                throw Exceptions.newIOException("The terms of {0} must be an object.", language.getKey());
            }

            Locale source = Locale.forLanguageTag(String.valueOf(language.getKey()));

            for (Map.Entry<?, ?> term : languageTerms.entrySet()) {
                String text = String.valueOf(term.getKey()).strip();

                if (!(term.getValue() instanceof Map<?, ?> meanings)) {
                    throw Exceptions.newIOException("The meanings of {0} must be an object.", term.getKey());
                }

                for (Map.Entry<?, ?> meaning : meanings.entrySet()) {
                    if (!(meaning.getValue() instanceof String value) || text.isEmpty() || value.isBlank()) {
                        throw Exceptions.newIOException("The term {0} has an empty term or meaning.", term.getKey());
                    }

                    terms.add(new Term(source, text, Locale.forLanguageTag(String.valueOf(meaning.getKey())), value.strip()));
                }
            }
        }

        return new SlangGlossary(terms);
    }


    private final @NotNull List<@NotNull Term> terms;

    private SlangGlossary(@NotNull List<@NotNull Term> terms) {
        this.terms = List.copyOf(terms);
    }

    /**
     * Creates the glossary entries of a target language: the terms of the other languages with a meaning in the target language.
     *
     * @param target the target language
     * @return the entries in the order of the terms, with the target language as their target
     */
    public @NotNull List<@NotNull GlossaryEntry> createEntries(@NotNull Locale target) {
        List<GlossaryEntry> entries = new ArrayList<>();

        for (Term term : this.terms) {
            if (term.target.getLanguage().equals(target.getLanguage()) && !term.source.getLanguage().equals(target.getLanguage())) {
                entries.add(new GlossaryEntry(term.term, target, term.meaning));
            }
        }

        return entries;
    }
}
