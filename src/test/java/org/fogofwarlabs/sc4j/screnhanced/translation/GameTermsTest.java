package org.fogofwarlabs.sc4j.screnhanced.translation;

import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.format.casc.CascStorage;
import org.fogofwarlabs.sc4j.translate.GlossaryEntry;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Checks how the names of the game's units, upgrades and technologies are cleaned, lose their race words and become the glossary entries of
 * each target language.
 */
final class GameTermsTest {
    private static final @NotNull Locale ENGLISH = Locale.US;
    private static final @NotNull Locale KOREAN = Locale.KOREA;
    private static final @NotNull Locale FRENCH = Locale.FRANCE;

    private static final int ZERG = 0;
    private static final int TERRAN = 1;
    private static final int PROTOSS = 2;

    private static final int @NotNull [] RACES = {
            TERRAN, PROTOSS, PROTOSS, ZERG, ZERG, ZERG, GameTerms.NO_RACE, TERRAN, GameTerms.NO_RACE, GameTerms.NO_RACE, GameTerms.NO_RACE,
            GameTerms.NO_RACE, TERRAN
    };

    private static @NotNull GameTerms createTerms() {
        Map<Locale, List<String>> strings = new LinkedHashMap<>();

        strings.put(ENGLISH, List.of("Terran Marine\0*\0Ground Units", "Protoss Zealot", "Protoss Probe", "Zerg Zergling", "Zerg Hydralisk",
                                     "Zerg Lurker", "Jim Raynor\0Marine\0Heroes", "\u0003Terran  Command\tCenter", "Terran Infantry Armor",
                                     "Stim Packs", "Lurker Aspect", "*", "Terran SCV"));
        strings.put(KOREAN, List.of("테란 마린\0*\0지상 유닛", "프로토스 질럿", "프로토스 프로브", "저그 저글링", "저그 히드라리스크", "저그 러커",
                                    "짐 레이너\0마린\0영웅", "테란 커맨드 센터", "테란 보병 방어력", "스팀팩", "러커", "", "테란 SCV"));
        strings.put(FRENCH, List.of("Marine terran", "Zélote protoss", "Sonde protoss", "Zergling zerg", "Hydralisk zerg", "Lurker zerg",
                                    "Jim Raynor", "Centre de commandement terran", "Armure d'infanterie terran", "Stimulants", "Aspect Lurker", "",
                                    "VCS terran"));

        return GameTerms.create(strings, RACES);
    }

    @Test
    void clean_name_should_keep_the_visible_name_before_the_first_nul() {
        Assertions.assertEquals("Terran Command Center", GameTerms.cleanName("\u0003Terran  Command\tCenter\u0001"));
        Assertions.assertEquals("Jim Raynor", GameTerms.cleanName("Jim Raynor\0Marine\0Heroes"));
        Assertions.assertEquals("", GameTerms.cleanName("*"));
        Assertions.assertEquals("", GameTerms.cleanName(" 12 "));
        Assertions.assertEquals("", GameTerms.cleanName(""));
    }

    @Test
    void create_entries_should_map_every_other_language_to_the_target_names() {
        List<GlossaryEntry> entries = createTerms().createEntries(KOREAN);

        Assertions.assertEquals(List.of(
                new GlossaryEntry("Zerg", KOREAN, "저그"),
                new GlossaryEntry("Terran", KOREAN, "테란"),
                new GlossaryEntry("Protoss", KOREAN, "프로토스"),
                new GlossaryEntry("Marine", KOREAN, "마린"),
                new GlossaryEntry("Zealot", KOREAN, "질럿"),
                new GlossaryEntry("Zélote", KOREAN, "질럿"),
                new GlossaryEntry("Probe", KOREAN, "프로브"),
                new GlossaryEntry("Sonde", KOREAN, "프로브"),
                new GlossaryEntry("Zergling", KOREAN, "저글링"),
                new GlossaryEntry("Hydralisk", KOREAN, "히드라리스크"),
                new GlossaryEntry("Lurker", KOREAN, "러커"),
                new GlossaryEntry("Jim Raynor", KOREAN, "짐 레이너"),
                new GlossaryEntry("Command Center", KOREAN, "커맨드 센터"),
                new GlossaryEntry("Centre de commandement", KOREAN, "커맨드 센터"),
                new GlossaryEntry("Terran Infantry Armor", KOREAN, "테란 보병 방어력"),
                new GlossaryEntry("Armure d'infanterie terran", KOREAN, "테란 보병 방어력"),
                new GlossaryEntry("Stim Packs", KOREAN, "스팀팩"),
                new GlossaryEntry("Stimulants", KOREAN, "스팀팩"),
                new GlossaryEntry("Lurker Aspect", KOREAN, "러커"),
                new GlossaryEntry("Aspect Lurker", KOREAN, "러커"),
                new GlossaryEntry("VCS", KOREAN, "SCV")
        ), entries);
    }

    @Test
    void create_entries_should_keep_the_first_target_of_a_source_name() {
        List<GlossaryEntry> entries = createTerms().createEntries(ENGLISH);

        Assertions.assertTrue(entries.contains(new GlossaryEntry("러커", ENGLISH, "Lurker")));
        Assertions.assertFalse(entries.contains(new GlossaryEntry("러커", ENGLISH, "Lurker Aspect")));
        Assertions.assertFalse(entries.contains(new GlossaryEntry("zerg", ENGLISH, "Zerg")), "A name equal to its target name is left out.");
        Assertions.assertTrue(entries.contains(new GlossaryEntry("Sonde", ENGLISH, "Probe")));
    }

    @Test
    void create_entries_should_take_the_target_names_of_the_same_language() {
        GameTerms terms = createTerms();

        Assertions.assertEquals(terms.createEntries(KOREAN).size(), terms.createEntries(Locale.KOREAN).size());
        Assertions.assertTrue(terms.createEntries(Locale.KOREAN).contains(new GlossaryEntry("Marine", Locale.KOREAN, "마린")));
        Assertions.assertEquals(List.of(), terms.createEntries(Locale.GERMAN));
        Assertions.assertEquals(List.of(), GameTerms.EMPTY.createEntries(KOREAN));
    }

    @Test
    void create_should_reject_a_language_with_another_number_of_strings() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> GameTerms.create(Map.of(ENGLISH, List.of("Terran Marine")), RACES));
    }

    @Test
    @RequiresInstallation
    void read_should_name_the_units_upgrades_and_technologies_of_the_installed_languages() throws IOException {
        GameTerms terms;

        try (CascStorage storage = CascStorage.open(ExternalInput.INSTALLATION.getPath())) {
            terms = GameTerms.read(storage);
        }

        Assertions.assertTrue(terms.getLanguages().contains(ENGLISH), terms.getLanguages().toString());

        List<GlossaryEntry> english = terms.createEntries(ENGLISH);

        Assertions.assertTrue(english.stream().noneMatch(entry -> entry.getSourceTerm().isBlank() || entry.getTargetTerm().isBlank()));

        if (terms.getLanguages().contains(KOREAN)) {
            List<GlossaryEntry> korean = terms.createEntries(KOREAN);

            Assertions.assertTrue(english.contains(new GlossaryEntry("질럿", ENGLISH, "Zealot")), english.toString());
            Assertions.assertTrue(english.contains(new GlossaryEntry("테란", ENGLISH, "Terran")), english.toString());
            Assertions.assertTrue(english.contains(new GlossaryEntry("사이오닉 스톰", ENGLISH, "Psionic Storm")), english.toString());
            Assertions.assertTrue(korean.contains(new GlossaryEntry("Zergling", KOREAN, "저글링")), korean.toString());
        }
    }
}
