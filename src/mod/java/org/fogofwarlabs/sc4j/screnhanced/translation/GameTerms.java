package org.fogofwarlabs.sc4j.screnhanced.translation;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.client.ui.text.StatText;
import org.fogofwarlabs.sc4j.scr.data.format.casc.CascStorage;
import org.fogofwarlabs.sc4j.scr.data.format.casc.GameLocaleUtils;
import org.fogofwarlabs.sc4j.scr.data.format.dat.TechdataDat;
import org.fogofwarlabs.sc4j.scr.data.format.dat.UnitsDat;
import org.fogofwarlabs.sc4j.scr.data.format.dat.UpgradesDat;
import org.fogofwarlabs.sc4j.scr.data.type.ScrTechTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUpgradeTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitGroupFlags;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.fogofwarlabs.sc4j.translate.GlossaryEntry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Holds the names of the units, buildings, upgrades and technologies of the game in each game language the installation has, the source
 * of the glossary of {@link TranslationContexts}.
 * <p>
 * A name is the {@code stat_txt} string up to its first NUL character, without control characters (color and hotkey codes), with its white
 * space runs as single spaces and stripped; a string without a letter is no name. In each language, the race word that most names of the
 * units of a race start with, followed by a space, is removed from those names, so {@code Terran Marine} is {@code Marine}, and the word is
 * the name of the race; a word they end with is used the same way when there is no such first word. The race word is the first word or
 * the last word that at least half of the names of the race, and at least two, share.
 */
public final class GameTerms {
    /**
     * The terms without a race: hero units, upgrades and technologies.
     */
    public static final int NO_RACE = -1;

    /**
     * The terms of no language.
     */
    public static final @NotNull GameTerms EMPTY = new GameTerms(Map.of());

    /**
     * The races whose unit names lose their race word, in race index order.
     */
    private static final int @NotNull [] RACE_GROUP_FLAGS = {UnitGroupFlags.ZERG, UnitGroupFlags.TERRAN, UnitGroupFlags.PROTOSS};
    /**
     * The stat_txt identifier of unit type {@code 0}'s name; type {@code n} uses {@code n + 1}.
     */
    private static final int FIRST_UNIT_NAME = 1;
    /**
     * The least number of names of a race that share its race word.
     */
    private static final int MINIMUM_SHARED_NAMES = 2;
    /**
     * The spelling of a game locale of a language: two lowercase letters of the language and two uppercase letters of the country, such as
     * {@code koKR}.
     */
    private static final @NotNull Pattern GAME_LOCALE = Pattern.compile("[a-z]{2}[A-Z]{2}");
    private static final @NotNull Pattern WHITE_SPACE = Pattern.compile("\\s+");
    private static final char FIRST_PRINTABLE_CHARACTER = ' ';
    private static final char TERMINATOR = '\0';
    private static final char SPACE = ' ';
    private static final @NotNull String UNITS_FILE = "arr/units.dat";
    private static final @NotNull String UPGRADES_FILE = "arr/upgrades.dat";
    private static final @NotNull String TECHNOLOGIES_FILE = "arr/techdata.dat";

    /**
     * Reads the terms of every game language whose {@code stat_txt} the installation has locally: the names of the unit types of the three
     * races that are men or buildings and available in the editor, of the upgrades and of the technologies.
     *
     * @param storage the game storage
     * @return the created {@link GameTerms} instance
     * @throws IOException if a data file or a string table cannot be read
     */
    public static @NotNull GameTerms read(@NotNull CascStorage storage) throws IOException {
        ScrUnitTypeView units = UnitsDat.createTypeTable(UnitsDat.createSchema().parse(storage.readFile(UNITS_FILE)));
        ScrUpgradeTypeView upgrades = UpgradesDat.createTypeTable(UpgradesDat.createSchema().parse(storage.readFile(UPGRADES_FILE)));
        ScrTechTypeView technologies = TechdataDat.createTypeTable(TechdataDat.createSchema().parse(storage.readFile(TECHNOLOGIES_FILE)));
        List<Integer> unitTypes = new ArrayList<>();
        List<Integer> races = new ArrayList<>();

        for (int unitType = 0; unitType < units.getCount(); unitType++) {
            int groups = units.getGroupFlags(unitType);
            int race = findRace(groups);

            if (race != NO_RACE && (groups & (UnitGroupFlags.MEN | UnitGroupFlags.BUILDING)) != 0 && units.getAvailabilityFlags(unitType) != 0) {
                unitTypes.add(unitType);
                races.add((units.getFlags(unitType) & UnitTypeFlags.HERO) == 0 ? race : NO_RACE);
            }
        }

        Map<Locale, List<String>> names = new LinkedHashMap<>();

        for (String locale : GameLocaleUtils.getAvailableLocales(storage)) {
            if (!GAME_LOCALE.matcher(locale).matches()) {
                continue;
            }

            StatText text = StatText.load(storage, locale);
            List<String> strings = new ArrayList<>();

            for (int unitType : unitTypes) {
                strings.add(text.getString(unitType + FIRST_UNIT_NAME));
            }

            for (int upgrade = 0; upgrade < upgrades.getCount(); upgrade++) {
                strings.add(text.getString(upgrades.getLabel(upgrade)));
            }

            for (int technology = 0; technology < technologies.getCount(); technology++) {
                strings.add(text.getString(technologies.getLabel(technology)));
            }

            names.put(GameLocaleUtils.toLocale(locale), strings);
        }

        int[] termRaces = new int[unitTypes.size() + upgrades.getCount() + technologies.getCount()];

        Arrays.fill(termRaces, NO_RACE);

        for (int index = 0; index < races.size(); index++) {
            termRaces[index] = races.get(index);
        }

        return create(names, termRaces);
    }

    /**
     * Creates a {@link GameTerms} instance from the strings of each language.
     *
     * @param strings the strings of each game language, one per term in the same term order in every language
     * @param races   the race index of each term, {@code 0} Zerg, {@code 1} Terran and {@code 2} Protoss, or {@link #NO_RACE}
     * @return the created {@link GameTerms} instance
     * @throws IllegalArgumentException if a language has another number of strings than there are races
     */
    public static @NotNull GameTerms create(@NotNull Map<@NotNull Locale, @NotNull List<@NotNull String>> strings, int @NotNull [] races) {
        Map<Locale, List<String>> names = new LinkedHashMap<>();

        for (Map.Entry<Locale, List<String>> language : strings.entrySet()) {
            if (language.getValue().size() != races.length) {
                throw Exceptions.newIllegalArgumentException("The language {0} has {1} strings for {2} terms", language.getKey(),
                                                             language.getValue().size(), races.length);
            }

            List<String> cleaned = new ArrayList<>(races.length);

            for (String string : language.getValue()) {
                cleaned.add(cleanName(string));
            }

            names.put(language.getKey(), createLanguageNames(cleaned, races));
        }

        return new GameTerms(names);
    }

    /**
     * Cleans a string into a name.
     *
     * @param string the string
     * @return the name, or an empty text when the string is no name
     */
    static @NotNull String cleanName(@NotNull String string) {
        int terminator = string.indexOf(TERMINATOR);
        String text = terminator < 0 ? string : string.substring(0, terminator);
        StringBuilder visible = new StringBuilder(text.length());

        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);

            if (character >= FIRST_PRINTABLE_CHARACTER || Character.isWhitespace(character)) {
                visible.append(character);
            }
        }

        String name = WHITE_SPACE.matcher(visible).replaceAll(String.valueOf(SPACE)).strip();

        return name.codePoints().anyMatch(Character::isLetter) ? name : "";
    }

    /**
     * Creates the names of a language: the race names, then the terms without their race words.
     *
     * @param names the cleaned names of the terms
     * @param races the race index of each term, or {@link #NO_RACE}
     * @return the race names in race index order, each an empty text when the language has no race word, followed by the term names
     */
    private static @NotNull List<@NotNull String> createLanguageNames(@NotNull List<@NotNull String> names, int @NotNull [] races) {
        List<String> result = new ArrayList<>(RACE_GROUP_FLAGS.length + names.size());
        List<String> terms = new ArrayList<>(names);

        for (int race = 0; race < RACE_GROUP_FLAGS.length; race++) {
            List<String> raceNames = new ArrayList<>();

            for (int index = 0; index < names.size(); index++) {
                if (races[index] == race && !names.get(index).isEmpty()) {
                    raceNames.add(names.get(index));
                }
            }

            String firstWord = findSharedWord(raceNames, true);
            String word = firstWord != null ? firstWord : findSharedWord(raceNames, false);

            result.add(word == null ? "" : word);

            if (word == null) {
                continue;
            }

            for (int index = 0; index < terms.size(); index++) {
                if (races[index] == race) {
                    terms.set(index, removeWord(terms.get(index), word, firstWord != null));
                }
            }
        }

        result.addAll(terms);

        return result;
    }

    /**
     * Finds the word that at least half of the names, and at least {@value #MINIMUM_SHARED_NAMES}, start or end with, followed or preceded
     * by a space.
     *
     * @param names the names
     * @param first whether the first word is looked for, else the last word
     * @return the most frequent such word, or {@code null} when no word is shared by at least half of the names
     */
    private static @Nullable String findSharedWord(@NotNull List<@NotNull String> names, boolean first) {
        Map<String, Integer> counts = new HashMap<>();
        String sharedWord = null;
        int sharedCount = 0;

        for (String name : names) {
            int space = first ? name.indexOf(SPACE) : name.lastIndexOf(SPACE);

            if (space < 0) {
                continue;
            }

            String word = first ? name.substring(0, space) : name.substring(space + 1);
            int count = counts.merge(word, 1, Integer::sum);

            if (count > sharedCount) {
                sharedWord  = word;
                sharedCount = count;
            }
        }

        return sharedCount >= MINIMUM_SHARED_NAMES && sharedCount * 2 >= names.size() ? sharedWord : null;
    }

    /**
     * Removes a race word from a name that starts or ends with it.
     *
     * @param name  the name
     * @param word  the race word
     * @param first whether the word is a first word, else a last word
     * @return the name without the word, or the name itself when it does not have the word
     */
    private static @NotNull String removeWord(@NotNull String name, @NotNull String word, boolean first) {
        if (first && name.startsWith(word + SPACE)) {
            return name.substring(word.length() + 1);
        }

        if (!first && name.endsWith(SPACE + word)) {
            return name.substring(0, name.length() - word.length() - 1);
        }

        return name;
    }

    private static int findRace(int groupFlags) {
        for (int race = 0; race < RACE_GROUP_FLAGS.length; race++) {
            if ((groupFlags & RACE_GROUP_FLAGS[race]) != 0) {
                return race;
            }
        }

        return NO_RACE;
    }


    /**
     * The names of each language: the race names, then the term names, in the same order in every language; an empty text where the
     * language has no name.
     */
    private final @NotNull Map<@NotNull Locale, @NotNull List<@NotNull String>> names;

    private GameTerms(@NotNull Map<@NotNull Locale, @NotNull List<@NotNull String>> names) {
        this.names = Collections.unmodifiableMap(new LinkedHashMap<>(names));
    }

    /**
     * @return the languages of the terms, in the order they were read; unmodifiable
     */
    public @NotNull Set<@NotNull Locale> getLanguages() {
        return this.names.keySet();
    }

    /**
     * Creates the glossary entries of a target language: each name of every language other than the target language, with the name of the
     * same term in the target language. The target names are those of the game language equal to the target, else of a game language of
     * the same language. A name that equals its target name, ignoring case, is left out, and so is a name that is already a source name of
     * the entries, ignoring case: race names come first, then units, upgrades and technologies, each in the order of the languages.
     *
     * @param target the target language
     * @return the entries, with the target language as their target; empty when no game language has the target language
     */
    public @NotNull List<@NotNull GlossaryEntry> createEntries(@NotNull Locale target) {
        List<String> targetNames = this.findNames(target);

        if (targetNames == null) {
            return List.of();
        }

        Map<String, String> targetsBySource = new HashMap<>();
        List<GlossaryEntry> entries = new ArrayList<>();

        for (int term = 0; term < targetNames.size(); term++) {
            String targetName = targetNames.get(term);

            if (targetName.isEmpty()) {
                continue;
            }

            for (Map.Entry<Locale, List<String>> language : this.names.entrySet()) {
                String sourceName = language.getValue().get(term);

                if (language.getKey().getLanguage().equals(target.getLanguage()) || sourceName.isEmpty() || sourceName.equalsIgnoreCase(targetName)) {
                    continue;
                }

                String known = targetsBySource.putIfAbsent(sourceName.toLowerCase(Locale.ROOT), targetName);

                if (known == null) {
                    entries.add(new GlossaryEntry(sourceName, target, targetName));
                }
            }
        }

        return entries;
    }

    private @Nullable List<@NotNull String> findNames(@NotNull Locale target) {
        List<String> exact = this.names.get(target);

        if (exact != null) {
            return exact;
        }

        for (Map.Entry<Locale, List<String>> language : this.names.entrySet()) {
            if (language.getKey().getLanguage().equals(target.getLanguage())) {
                return language.getValue();
            }
        }

        return null;
    }
}
