package org.fogofwarlabs.sc4j.screnhanced.translation;

import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.translate.GlossaryEntry;
import org.fogofwarlabs.sc4j.translate.TranslationContext;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Checks the translation contexts of each kind of text and target language: their wording, their glossary and their reuse.
 */
final class TranslationContextsTest {
    private static @NotNull GameTerms createTerms() {
        return GameTerms.create(Map.of(Locale.US, List.of("Vespene Gas", "Protoss Zealot", "Protoss Probe"),
                                       Locale.KOREA, List.of("가스", "프로토스 질럿", "프로토스 프로브")),
                                new int[]{GameTerms.NO_RACE, 2, 2});
    }

    private static @NotNull SlangGlossary createSlang() throws IOException {
        return SlangGlossary.parse("""
                                   {"ko": {"가스": {"en": "gas"}, "렉": {"en": "lag"}}}
                                   """.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void create_context_should_give_the_wording_of_the_kind_and_the_game_terms_over_the_players_terms() throws IOException {
        TranslationContext chat = TranslationContexts.createContext(TextKind.CHAT, Locale.ENGLISH, createTerms(), createSlang());
        TranslationContext mapText = TranslationContexts.createContext(TextKind.MAP_TEXT, Locale.ENGLISH, createTerms(), createSlang());

        Assertions.assertEquals(TextKind.CHAT.getLabel(), chat.getTextKind());
        Assertions.assertEquals(TextKind.CHAT.getDescription(), chat.getDescription());
        Assertions.assertEquals(TextKind.MAP_TEXT.getLabel(), mapText.getTextKind());
        Assertions.assertEquals(TextKind.MAP_TEXT.getDescription(), mapText.getDescription());
        Assertions.assertEquals(chat.getGlossary(), mapText.getGlossary());
        Assertions.assertEquals(List.of(new GlossaryEntry("가스", Locale.ENGLISH, "Vespene Gas"),
                                        new GlossaryEntry("렉", Locale.ENGLISH, "lag"),
                                        new GlossaryEntry("질럿", Locale.ENGLISH, "Zealot"),
                                        new GlossaryEntry("프로브", Locale.ENGLISH, "Probe"),
                                        new GlossaryEntry("프로토스", Locale.ENGLISH, "Protoss")),
                                chat.getGlossary(), "The players' term 가스 gives way to the game term.");
    }

    @Test
    void get_context_should_build_each_kind_and_target_language_once() throws IOException {
        TranslationContexts contexts = TranslationContexts.create(createTerms(), createSlang());

        Assertions.assertSame(contexts.getContext(TextKind.CHAT, Locale.KOREA), contexts.getContext(TextKind.CHAT, Locale.KOREA));
        Assertions.assertNotSame(contexts.getContext(TextKind.CHAT, Locale.KOREA).join(), contexts.getContext(TextKind.MAP_TEXT, Locale.KOREA).join());
        Assertions.assertNotEquals(contexts.getContext(TextKind.CHAT, Locale.KOREA).join(), contexts.getContext(TextKind.CHAT, Locale.ENGLISH).join());
    }

    @Test
    void load_should_give_the_players_terms_when_the_game_terms_cannot_be_read(@TempDir Path folder) {
        TranslationContexts contexts = TranslationContexts.load(folder, Logger.of(TranslationContextsTest.class));
        TranslationContext context = contexts.getContext(TextKind.CHAT, Locale.ENGLISH).join();

        Assertions.assertEquals(SlangGlossary.load().createEntries(Locale.ENGLISH).size(), context.getGlossary().size());
        Assertions.assertTrue(context.getGlossary().contains(new GlossaryEntry("멀티", Locale.ENGLISH, "expansion")));
    }
}
