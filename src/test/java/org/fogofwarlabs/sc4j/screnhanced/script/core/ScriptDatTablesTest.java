package org.fogofwarlabs.sc4j.screnhanced.script.core;

import org.fogofwarlabs.sc4j.scr.data.format.dat.*;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.fogofwarlabs.sc4j.scr.data.type.constant.WeaponTargetFlags;
import org.fogofwarlabs.sc4j.screnhanced.script.ScriptProjectUtils;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Tests that {@link ScriptDatTables} has one column for every field of the SC:R DAT schemas, named after the field's constant, the named bits of
 * the flag columns, unique method names on every type object, and that {@code scr-enhanced.d.ts} and {@code core.d.ts} declare exactly the
 * generated DAT methods of the upper and the lower layer.
 */
final class ScriptDatTablesTest {
    private static final @NotNull String BLOCK_START = "// BEGIN GENERATED DAT";
    private static final @NotNull String BLOCK_END = "// END GENERATED DAT";
    private static final @NotNull String CORE_BLOCK_START = "// BEGIN GENERATED CORE DAT";
    private static final @NotNull String CORE_BLOCK_END = "// END GENERATED CORE DAT";
    private static final @NotNull String BLOCK_DESCRIPTION = ": the DAT columns of the game data tables.\n";
    /**
     * The files the expected blocks are written to when the declarations differ.
     */
    private static final @NotNull Path EXPECTED_BLOCK = Path.of("build", "script-declarations", "dat.d.ts");
    private static final @NotNull Path EXPECTED_CORE_BLOCK = Path.of("build", "script-declarations", "core-dat.d.ts");
    /**
     * The hand-written members of the type objects, which no DAT method may take.
     */
    private static final @NotNull Set<String> TYPE_MEMBERS = Set.of("getId", "getName", "toString", "getAnimImageType");
    /**
     * The schema and the definitions class of each table, by table name.
     */
    private static final @NotNull Map<String, Object[]> DEFINITIONS = createDefinitions();

    private static @NotNull Map<String, Object[]> createDefinitions() {
        Map<String, Object[]> definitions = new LinkedHashMap<>();

        definitions.put("units", new Object[]{UnitsDat.createSchema(), UnitsDat.class});
        definitions.put("weapons", new Object[]{WeaponsDat.createSchema(), WeaponsDat.class});
        definitions.put("flingy", new Object[]{FlingyDat.createSchema(), FlingyDat.class});
        definitions.put("sprites", new Object[]{SpritesDat.createSchema(), SpritesDat.class});
        definitions.put("images", new Object[]{ImagesDat.createSchema(), ImagesDat.class});
        definitions.put("upgrades", new Object[]{UpgradesDat.createSchema(), UpgradesDat.class});
        definitions.put("techdata", new Object[]{TechdataDat.createSchema(), TechdataDat.class});
        definitions.put("orders", new Object[]{OrdersDat.createSchema(), OrdersDat.class});

        return definitions;
    }

    /**
     * Writes the generated DAT block of the upper layer's declarations: the interface of each table's type objects.
     */
    private static @NotNull String writeDeclarations() {
        StringBuilder text = new StringBuilder(BLOCK_START).append(BLOCK_DESCRIPTION);

        for (ScriptDatTables.Table table : ScriptDatTables.getTables()) {
            String typeName = table.getType().getTypeName();

            text.append("\n/**\n * The ").append(table.getFileName()).append(" values of the ").append(table.getKind()).append('s')
                .append(": raw values of the current game.\n * <p>\n * The set functions change the game and work in shared code only.")
                .append(" They throw a RangeError where the lower layer's\n * set functions do ({@link ").append(getCoreInterfaceName(table))
                .append("}).\n */\ninterface ").append(typeName).append("Data {\n");

            for (ScriptDatTables.Column column : table.getColumns()) {
                writeUpperMembers(text, table, column);
            }

            closeInterface(text);
        }

        return text.append(BLOCK_END).append('\n').toString();
    }

    /**
     * Writes the generated DAT block of the lower layer's declarations: the interface of each table and {@code CoreDat}.
     */
    private static @NotNull String writeCoreDeclarations() {
        StringBuilder text = new StringBuilder(CORE_BLOCK_START).append(BLOCK_DESCRIPTION);

        for (ScriptDatTables.Table table : ScriptDatTables.getTables()) {
            String parameter = toCamelCase(table.getKind());

            text.append("\n/**\n * The ").append(table.getFileName()).append(" values by ").append(table.getKind()).append(" ({@code core.dat.")
                .append(table.getName()).append("}).\n * <p>\n * A get function returns the raw value of an entry; outside the entries of its")
                .append(" column, the value the game reads there. A\n * set function stores a value as an EUD write does and works in shared code only.")
                .append(" It throws a RangeError for an entry outside\n * the entries of its column, a value the column cannot hold or a value")
                .append(" at or above the column's limit.\n */\ninterface ").append(getCoreInterfaceName(table)).append(" {\n");

            for (ScriptDatTables.Column column : table.getColumns()) {
                text.append("    /** ").append(describe(table, column, null)).append(" */\n");
                text.append("    get").append(column.getName()).append('(').append(parameter).append(": number): number;\n\n");

                if (column.isWritable()) {
                    text.append("    set").append(column.getName()).append('(').append(parameter).append(": number, value: number): void;\n\n");
                }
            }

            closeInterface(text);
        }

        text.append("\n/**\n * The DAT tables of the current game ({@code core.dat}).\n */\ninterface CoreDat {\n");

        for (ScriptDatTables.Table table : ScriptDatTables.getTables()) {
            text.append("    /** The ").append(table.getFileName()).append(" values. */\n");
            text.append("    readonly ").append(table.getName()).append(": ").append(getCoreInterfaceName(table)).append(";\n");
        }

        return text.append("}\n").append(CORE_BLOCK_END).append('\n').toString();
    }

    private static void writeUpperMembers(@NotNull StringBuilder text, ScriptDatTables.@NotNull Table table, ScriptDatTables.@NotNull Column column) {
        for (ScriptDatTables.Flag flag : column.getFlags()) {
            text.append("    /** ").append(describe(table, column, flag)).append(" */\n");
            text.append("    is").append(flag.getName()).append("(): boolean;\n\n");

            if (column.isWritable()) {
                text.append("    set").append(flag.getName()).append("(set: boolean): void;\n\n");
            }
        }

        if (!column.getFlags().isEmpty()) {
            return;
        }

        ScriptDatTables.Reference reference = column.getReference();
        String valueType = reference == null ? "number" : reference.getTypeName();
        String parameter = reference == null ? "value" : toCamelCase(reference.getTypeName());

        if (reference != null && reference.getNoneId() != ScriptDatTables.NOT_NULLABLE) {
            valueType += " | null";
        }

        text.append("    /** ").append(describe(table, column, null)).append(" */\n");
        text.append("    get").append(column.getName()).append("(): ").append(valueType).append(";\n\n");

        if (column.isWritable()) {
            text.append("    set").append(column.getName()).append('(').append(parameter).append(": ").append(valueType).append("): void;\n\n");
        }
    }

    /**
     * Ends an interface: drops the blank line after its last member and closes it.
     */
    private static void closeInterface(@NotNull StringBuilder text) {
        if (text.toString().endsWith("\n\n")) {
            text.setLength(text.length() - 1);
        }

        text.append("}\n");
    }

    /**
     * @return the field name with the column's entry range when it holds part of the table, its limit and whether it is read only
     */
    private static @NotNull String describe(ScriptDatTables.@NotNull Table table, ScriptDatTables.@NotNull Column column, ScriptDatTables.Flag flag) {
        List<String> notes = new ArrayList<>();

        if (column.getFirstId() != 0 || column.getCount() != table.getCount()) {
            notes.add(table.getKind() + "s " + column.getFirstId() + " to " + (column.getFirstId() + column.getCount() - 1));
        }

        if (column.getLimit() != ScriptDatTables.NO_LIMIT) {
            notes.add("below " + column.getLimit());
        }

        if (!column.isWritable()) {
            notes.add("read only");
        }

        String description = flag == null ? column.getFieldName() : column.getFieldName() + " 0x" + Integer.toHexString(flag.getMask()).toUpperCase(Locale.ROOT);

        return notes.isEmpty() ? description : description + " (" + String.join(", ", notes) + ")";
    }

    private static @NotNull String getCoreInterfaceName(ScriptDatTables.@NotNull Table table) {
        return "CoreDat" + Character.toUpperCase(table.getName().charAt(0)) + table.getName().substring(1);
    }

    /**
     * @return the words or the upper camel case name in lower camel case ({@code unit type} and {@code UnitType} are {@code unitType})
     */
    private static @NotNull String toCamelCase(@NotNull String name) {
        StringBuilder camelCase = new StringBuilder();

        for (String word : name.split(" ")) {
            camelCase.append(camelCase.isEmpty() ? Character.toLowerCase(word.charAt(0)) : Character.toUpperCase(word.charAt(0))).append(word, 1, word.length());
        }

        return camelCase.toString();
    }

    @Test
    void every_field_of_the_dat_schemas_has_a_column_named_after_its_constant() throws ReflectiveOperationException {
        List<String> tableNames = new ArrayList<>();

        for (ScriptDatTables.Table table : ScriptDatTables.getTables()) {
            Object[] definition = DEFINITIONS.get(table.getName());
            List<DatSchema.Field> fields = ((DatSchema) definition[0]).getFields();
            Set<String> names = new HashSet<>();

            tableNames.add(table.getName());
            Assertions.assertEquals(fields.size(), table.getColumns().size(), table.getName());

            for (int index = 0; index < fields.size(); index++) {
                ScriptDatTables.Column column = table.getColumns().get(index);
                Object constant = ((Class<?>) definition[1]).getField(column.getConstantName()).get(null);

                Assertions.assertEquals(fields.get(index).getName(), column.getFieldName(), column.getConstantName());
                Assertions.assertEquals(column.getFieldName(), constant, column.getConstantName());
                Assertions.assertEquals(fields.get(index).getType(), column.getType(), column.getConstantName());
                Assertions.assertEquals(fields.get(index).getCount(), column.getCount(), column.getConstantName());
                Assertions.assertTrue(column.getName().matches("[A-Z][A-Za-z0-9]*"), column.getName());
                Assertions.assertTrue(names.add(column.getName()), column.getName());
                Assertions.assertTrue(column.getFirstId() + column.getCount() <= table.getCount(), column.getName());
            }
        }

        Assertions.assertEquals(List.copyOf(DEFINITIONS.keySet()), tableNames);
        Assertions.assertEquals(UnitsDat.MINERAL_COST, findColumn("units", "MineralCost").getFieldName());
        Assertions.assertEquals(UnitsDat.TARGET_ACQUISITION_RANGE, findColumn("units", "TargetAcquisitionRange").getFieldName());
        Assertions.assertEquals(List.of("FlingyType", "FirstSubunit", "SecondSubunit", "InfestedType"),
                                ScriptDatTables.findTable("units").getColumns().subList(0, 4).stream().map(ScriptDatTables.Column::getName).toList());
        Assertions.assertEquals(List.of("Sprite", "TopSpeed", "Acceleration", "HaltDistance", "TurnRate", "Unused", "MovementControl"),
                                ScriptDatTables.findTable("flingy").getColumns().stream().map(ScriptDatTables.Column::getName).toList());
        Assertions.assertNull(ScriptDatTables.findTable("sfxdata"));
    }

    @Test
    void flag_columns_name_their_documented_bits() {
        ScriptDatTables.Column unitFlags = findColumn("units", "Flags");
        ScriptDatTables.Column targetFlags = findColumn("weapons", "TargetFlags");
        List<String> unitFlagNames = unitFlags.getFlags().stream().map(ScriptDatTables.Flag::getName).toList();

        Assertions.assertEquals("Building", unitFlags.getFlags().getFirst().getName());
        Assertions.assertEquals(UnitTypeFlags.BUILDING, unitFlags.getFlags().getFirst().getMask());
        Assertions.assertTrue(unitFlagNames.containsAll(List.of("Flyer", "Worker", "Hero", "Burrowable", "Spellcaster", "Mechanical")));
        Assertions.assertEquals(28, unitFlagNames.size(), "the 32 bits without the four FLAG_<bits> constants");
        Assertions.assertEquals(List.of("TargetAir", "TargetGround", "TargetMechanical", "TargetOrganic", "TargetNonBuilding", "TargetNonRobotic",
                                        "TargetTerrain", "TargetOrganicOrMechanical", "TargetOwn"),
                                targetFlags.getFlags().stream().map(ScriptDatTables.Flag::getName).toList());
        Assertions.assertEquals(WeaponTargetFlags.OWN, targetFlags.getFlags().getLast().getMask());
        Assertions.assertEquals("GroupZerg", findColumn("units", "GroupFlags").getFlags().getFirst().getName());
        Assertions.assertEquals("ComputerInternalTownUnit", findColumn("units", "ComputerInternalFlags").getFlags().getLast().getName());
        Assertions.assertEquals("AvailabilityNonNeutral", findColumn("units", "AvailabilityFlags").getFlags().getFirst().getName());

        for (ScriptDatTables.Table table : ScriptDatTables.getTables()) {
            for (ScriptDatTables.Column column : table.getColumns()) {
                for (ScriptDatTables.Flag flag : column.getFlags()) {
                    Assertions.assertEquals(1, Integer.bitCount(flag.getMask()), flag.getConstantName());
                }
            }
        }
    }

    @Test
    void the_dat_methods_of_a_type_object_have_unique_names() {
        Map<String, Set<String>> members = new LinkedHashMap<>();

        for (ScriptDatTables.Table table : ScriptDatTables.getTables()) {
            Set<String> names = members.computeIfAbsent(table.getType().getTypeName(), name -> new HashSet<>(TYPE_MEMBERS));

            for (ScriptDatTables.Column column : table.getColumns()) {
                List<String> methods = new ArrayList<>();

                for (ScriptDatTables.Flag flag : column.getFlags()) {
                    methods.add("is" + flag.getName());
                    methods.add("set" + flag.getName());
                }

                if (column.getFlags().isEmpty()) {
                    methods.add("get" + column.getName());
                    methods.add("set" + column.getName());
                }

                for (String method : methods) {
                    Assertions.assertTrue(names.add(method), table.getName() + " " + method);
                }
            }
        }

        Assertions.assertEquals(8, members.size());
    }

    @Test
    void declarations_hold_the_generated_dat_blocks() throws IOException {
        List<String> problems = new ArrayList<>();

        checkBlock(ScriptProjectUtils.DECLARATIONS_FILE_NAME, BLOCK_START, BLOCK_END, writeDeclarations(), EXPECTED_BLOCK, problems);
        checkBlock(ScriptProjectUtils.CORE_DECLARATIONS_FILE_NAME, CORE_BLOCK_START, CORE_BLOCK_END, writeCoreDeclarations(), EXPECTED_CORE_BLOCK,
                   problems);

        Assertions.assertEquals(List.of(), problems);
    }

    private static @NotNull String removeBlankLines(@NotNull String text) {
        return text.replaceAll("(?m)^[ \\t]*\\n", "");
    }

    /**
     * Compares a generated block of a declarations file with the expected one, blank lines aside, and writes the expected one to a file when
     * they differ.
     */
    private static void checkBlock(@NotNull String fileName, @NotNull String start, @NotNull String end, @NotNull String expected,
                                   @NotNull Path expectedFile, @NotNull List<String> problems) throws IOException {
        String declarations = new String(ScriptProjectUtils.readDeclarations(fileName), StandardCharsets.UTF_8).replace("\r\n", "\n");
        int startIndex = declarations.indexOf(start);
        int endIndex = declarations.indexOf(end);
        String actual = startIndex < 0 || endIndex < startIndex ? "" : declarations.substring(startIndex, endIndex + end.length() + 1);

        if (!removeBlankLines(actual).equals(removeBlankLines(expected))) {
            Files.createDirectories(expectedFile.getParent());
            Files.writeString(expectedFile, expected, StandardCharsets.UTF_8);
            problems.add("The generated DAT block of " + fileName + " differs; the expected block is in " + expectedFile.toAbsolutePath());
        }
    }

    private static ScriptDatTables.@NotNull Column findColumn(@NotNull String tableName, @NotNull String columnName) {
        for (ScriptDatTables.Column column : ScriptDatTables.findTable(tableName).getColumns()) {
            if (column.getName().equals(columnName)) {
                return column;
            }
        }

        return Assertions.fail(columnName);
    }
}
