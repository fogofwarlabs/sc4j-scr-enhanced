package org.fogofwarlabs.sc4j.screnhanced.translation;

import org.fogofwarlabs.sc4j.translate.GlossaryEntry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Checks the players' terms of the resource and their glossary entries per target language.
 */
final class SlangGlossaryTest {
    @Test
    void load_should_read_the_terms_of_the_resource() {
        SlangGlossary slang = SlangGlossary.load();
        List<GlossaryEntry> english = slang.createEntries(Locale.ENGLISH);
        List<GlossaryEntry> korean = slang.createEntries(Locale.KOREA);

        Assertions.assertTrue(english.contains(new GlossaryEntry("멀티", Locale.ENGLISH, "expansion")));
        Assertions.assertTrue(english.contains(new GlossaryEntry("ㅎㅇ", Locale.ENGLISH, "hi")));
        Assertions.assertTrue(korean.contains(new GlossaryEntry("natural", Locale.KOREA, "앞마당")));
        Assertions.assertTrue(korean.contains(new GlossaryEntry("偷矿", Locale.KOREA, "몰래 멀티")));
        Assertions.assertTrue(korean.contains(new GlossaryEntry("ラグい", Locale.KOREA, "렉")));
        Assertions.assertTrue(korean.contains(new GlossaryEntry("лаги", Locale.KOREA, "렉")));
    }

    @Test
    void create_entries_should_leave_out_the_terms_of_the_target_language() throws IOException {
        SlangGlossary slang = SlangGlossary.parse("""
                                                  {"ko": {"렉": {"en": "lag", "zh": "卡顿"}}, "en": {"lag": {"ko": "렉"}}}
                                                  """.getBytes(StandardCharsets.UTF_8));

        Assertions.assertEquals(List.of(new GlossaryEntry("렉", Locale.ENGLISH, "lag")), slang.createEntries(Locale.ENGLISH));
        Assertions.assertEquals(List.of(new GlossaryEntry("lag", Locale.KOREAN, "렉")), slang.createEntries(Locale.KOREAN));
        Assertions.assertEquals(List.of(new GlossaryEntry("렉", Locale.TRADITIONAL_CHINESE, "卡顿")), slang.createEntries(Locale.TRADITIONAL_CHINESE));
        Assertions.assertEquals(List.of(), slang.createEntries(Locale.GERMAN));
    }

    @Test
    void parse_should_reject_malformed_terms() {
        Assertions.assertThrows(IOException.class, () -> SlangGlossary.parse("[]".getBytes(StandardCharsets.UTF_8)));
        Assertions.assertThrows(IOException.class, () -> SlangGlossary.parse("{\"ko\": [\"렉\"]}".getBytes(StandardCharsets.UTF_8)));
        Assertions.assertThrows(IOException.class, () -> SlangGlossary.parse("{\"ko\": {\"렉\": \"lag\"}}".getBytes(StandardCharsets.UTF_8)));
        Assertions.assertThrows(IOException.class, () -> SlangGlossary.parse("{\"ko\": {\"렉\": {\"en\": \" \"}}}".getBytes(StandardCharsets.UTF_8)));
    }
}
