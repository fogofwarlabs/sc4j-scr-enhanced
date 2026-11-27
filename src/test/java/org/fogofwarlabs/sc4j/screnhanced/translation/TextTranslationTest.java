package org.fogofwarlabs.sc4j.screnhanced.translation;

import org.fogofwarlabs.sc4j.translate.TranslationRequest;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

/**
 * Checks which map texts are asked for, how their answers are kept and polled, and how digit runs share one template translation.
 */
final class TextTranslationTest {
    private static final @NotNull Locale TARGET = Locale.KOREAN;

    @Test
    void translate_map_text_should_ask_once_per_text_and_answer_once_the_translation_came() {
        RecordingTranslator translator = new RecordingTranslator();
        TextTranslation translation = new TextTranslation(translator, false, true);

        TextTranslation.Answer first = translation.translateMapText("Defend the base", TARGET);
        TextTranslation.Answer second = translation.translateMapText("Defend the base", TARGET);

        Assertions.assertNotNull(first);
        Assertions.assertSame(first, second, "A text shown again finds its answer.");
        Assertions.assertEquals(1, translator.getRequests().size());
        Assertions.assertNull(first.findTranslatedText(), "No text before the translation came.");
        Assertions.assertEquals("Defend the base", translation.presentMapText("Defend the base", TARGET));

        translator.answer(0, "기지를 지켜라");

        Assertions.assertEquals("기지를 지켜라", first.findTranslatedText());
        Assertions.assertEquals("기지를 지켜라", translation.presentMapText("Defend the base", TARGET));
        Assertions.assertEquals(1, translator.getRequests().size());
    }

    @Test
    void digit_runs_share_one_template_translation() {
        RecordingTranslator translator = new RecordingTranslator();
        TextTranslation translation = new TextTranslation(translator, false, true);

        TextTranslation.Answer third = translation.translateMapText("Wave 3 - 120 seconds left", TARGET);

        Assertions.assertNotNull(third);
        Assertions.assertEquals("Wave 101 - 102 seconds left", translator.getRequests().getFirst().getText());

        translator.answer(0, "웨이브 101 - 102초 남음");

        Assertions.assertEquals("웨이브 3 - 120초 남음", third.findTranslatedText());
        Assertions.assertEquals("웨이브 4 - 119초 남음", translation.presentMapText("Wave 4 - 119 seconds left", TARGET),
                                "A text with other numbers takes the translated template at once.");
        Assertions.assertEquals(1, translator.getRequests().size());
    }

    @Test
    void a_template_whose_placeholders_do_not_come_back_asks_for_the_exact_text() {
        RecordingTranslator translator = new RecordingTranslator();
        TextTranslation translation = new TextTranslation(translator, false, true);
        TextTranslation.Answer answer = translation.translateMapText("Wave 3 of 9 incoming", TARGET);

        Assertions.assertNotNull(answer);

        translator.answer(0, "102번째 웨이브가 온다");

        Assertions.assertNull(answer.findTranslatedText());
        Assertions.assertEquals(List.of("Wave 101 of 102 incoming", "Wave 3 of 9 incoming"),
                                translator.getRequests().stream().map(TranslationRequest::getText).toList());

        translator.answer(1, "9개 중 3번째 웨이브가 온다");

        Assertions.assertEquals("9개 중 3번째 웨이브가 온다", answer.findTranslatedText());
    }

    @Test
    void text_art_is_not_asked_for() {
        RecordingTranslator translator = new RecordingTranslator();
        TextTranslation translation = new TextTranslation(translator, false, true);

        Assertions.assertFalse(TextTranslation.isTranslatable("|==#==|  ##  |==#==| ok"));
        Assertions.assertFalse(TextTranslation.isTranslatable("\u0004 \u0005"));
        Assertions.assertFalse(TextTranslation.isTranslatable("\u0013\u0004■■■■□□□□■■■■"), "A block drawing.");
        Assertions.assertFalse(TextTranslation.isTranslatable("\u0013━━━━━━━━━━━━━━━━━━━━"), "A rule.");
        Assertions.assertFalse(TextTranslation.isTranslatable("▣▤▣▤▣▤  ■ □ ■ □ ■ □"), "Spaced and alternating symbols.");
        Assertions.assertFalse(TextTranslation.isTranslatable("ㅡㅡㅡㅡㅡㅡㅡㅡ"), "A row of one repeated letter.");
        Assertions.assertFalse(TextTranslation.isTranslatable("A   B   C   D   E"), "Isolated letters.");
        Assertions.assertFalse(TextTranslation.isTranslatable("\u0007★★★☆☆ 3/5"), "Symbols and digits.");
        Assertions.assertTrue(TextTranslation.isTranslatable("\u0004Kill 25 Zerglings!"));
        Assertions.assertEquals("<<==>>", translation.presentMapText("<<==>>", TARGET));
        Assertions.assertEquals("■ □ ■ □", translation.presentMapText("■ □ ■ □", TARGET));
        Assertions.assertEquals(List.of(), translator.getRequests());
    }

    @Test
    void status_rows_and_labels_with_digits_and_separators_are_asked_for() {
        RecordingTranslator translator = new RecordingTranslator();
        TextTranslation translation = new TextTranslation(translator, false, true);
        String row = "\u0013\u0004:+: \u001FWave: \u00040 \u0002| \u0011시대: \u0004고대시대 \u0002| \u0007병력 총 가치: \u0004300 \u0002| " +
                     "\u0010문화력: \u00040 \u0002| \u0008적군 수: \u00040/300 \u0004:+:";
        String changed = row.replace("Wave: \u00040", "Wave: \u00041")
                            .replace("\u0004300 ", "\u0004450 ")
                            .replace("문화력: \u00040", "문화력: \u00042")
                            .replace("\u00040/300", "\u000412/300");

        Assertions.assertTrue(TextTranslation.isTranslatable(":: 병마용 ::"), "A name between separators.");
        Assertions.assertTrue(TextTranslation.isTranslatable("\u0013\u001F난이도: \u0004★☆☆☆☆ \u0015| \u001F적 유닛 쉴드 0%"), "A rating row.");
        Assertions.assertTrue(TextTranslation.isTranslatable("\u0011문화력: \u0004600"));
        Assertions.assertTrue(TextTranslation.isTranslatable("벽"), "A text of one letter.");

        TextTranslation.Answer answer = translation.translateMapText(row, TARGET);

        Assertions.assertNotNull(answer);
        Assertions.assertEquals(List.of("\u0013\u0004:+: \u001FWave: \u0004101 \u0002| \u0011시대: \u0004고대시대 \u0002| \u0007병력 총 가치: \u0004102 \u0002| " +
                                        "\u0010문화력: \u0004103 \u0002| \u0008적군 수: \u0004104/105 \u0004:+:"),
                                translator.getRequests().stream().map(TranslationRequest::getText).toList(),
                                "The status row is asked for as a template.");

        translator.answer(0, "\u0013\u0004:+: \u001FWave: \u0004101 \u0002| \u0011Era: \u0004Ancient \u0002| \u0007Total value: \u0004102 \u0002| " +
                             "\u0010Culture: \u0004103 \u0002| \u0008Enemies: \u0004104/105 \u0004:+:");

        Assertions.assertEquals("\u0013\u0004:+: \u001FWave: \u00041 \u0002| \u0011Era: \u0004Ancient \u0002| \u0007Total value: \u0004450 \u0002| " +
                                "\u0010Culture: \u00042 \u0002| \u0008Enemies: \u000412/300 \u0004:+:",
                                translation.presentMapText(changed, TARGET),
                                "A row with other values takes the translated template.");
        Assertions.assertEquals(1, translator.getRequests().size());
    }

    @Test
    void map_text_translation_off_asks_for_nothing() {
        RecordingTranslator translator = new RecordingTranslator();
        TextTranslation translation = new TextTranslation(translator, true, false);

        Assertions.assertNull(translation.translateMapText("Defend the base", TARGET));
        Assertions.assertEquals("Defend the base", translation.presentMapText("Defend the base", TARGET));
        Assertions.assertEquals(List.of(), translator.getRequests());
    }
}
