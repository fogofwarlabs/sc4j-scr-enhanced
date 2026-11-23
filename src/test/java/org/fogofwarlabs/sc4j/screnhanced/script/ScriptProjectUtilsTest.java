package org.fogofwarlabs.sc4j.screnhanced.script;

import org.fogofwarlabs.sc4j.base.json.JsonReader;
import org.fogofwarlabs.sc4j.base.log.LogLevel;
import org.fogofwarlabs.sc4j.scr.core.LcgRandom;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.terrain.GameMap;
import org.fogofwarlabs.sc4j.screnhanced.script.core.ScriptDatTables;
import org.fogofwarlabs.sc4j.screnhanced.script.runtime.ScriptHook;
import org.fogofwarlabs.sc4j.screnhanced.script.runtime.ScriptLogOutput;
import org.fogofwarlabs.sc4j.screnhanced.script.runtime.ScriptRuntime;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mozilla.javascript.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Tests the new script folder of {@link ScriptProjectUtils} and that its declarations name exactly what a script sees: every global, every
 * member of the game API objects, the hooks and their event and decision objects, and the type constants; declared standard objects exist.
 * The prelude's hooks are those the runtime knows ({@link ScriptHook}).
 */
final class ScriptProjectUtilsTest {
    private static final @NotNull String PRELUDE_FOLDER = "/org/fogofwarlabs/sc4j/screnhanced/script/prelude/";
    /**
     * The area files of the prelude, in load order.
     */
    private static final @NotNull List<String> PRELUDE_FILES = List.of("values.js", "types.js", "buttons.js", "game.js", "sync.js", "local.js",
                                                                       "command-card.js", "presentation.js", "render.js", "hooks.js");
    private static final int MAP_TILE_SIZE = 4;
    private static final int MAP_TILESET = 0;
    private static final long SEED = 1;
    /**
     * The deepest property path the declarations are followed to.
     */
    private static final int MAXIMUM_PATH_DEPTH = 3;
    /**
     * The runtime globals the declarations leave out: the generator function replacement, which only throws.
     */
    private static final @NotNull Set<String> UNDECLARED_GLOBALS = Set.of("GeneratorFunction");
    /**
     * The globals that Rhino's standard objects have and the runtime replaces.
     */
    private static final @NotNull Set<String> REPLACED_GLOBALS = Set.of("Math");
    /**
     * The constant tables whose members come from the game's type data: a mismatch prints the members to declare.
     */
    private static final @NotNull List<String> GENERATED_TABLES = List.of("UnitType", "WeaponType", "UpgradeType", "TechType", "Order", "Key");
    private static final @NotNull String GENERATED_COMMENT = "    // GENERATED ";
    private static final @NotNull String DECISION_HOOK_PREFIX = "onBefore";
    /**
     * The key of the prelude's hook names among the instance names.
     */
    private static final @NotNull String HOOK_NAMES = "hookNames";
    /**
     * The event kinds the stub {@code core} names, those of the runtime's {@code core.events}.
     */
    private static final @NotNull List<String> EVENT_KINDS = List.of("UnitCreated", "UnitCompleted", "UnitDied", "UnitDamaged", "OrderIssued",
                                                                     "TargetAcquired", "UnitTrained", "ResearchCompleted", "UpgradeCompleted",
                                                                     "SpellCast", "OwnerChanged", "UnitLoaded", "UnitUnloaded", "BulletCreated",
                                                                     "BulletHit", "PlayerLeft", "PlayerResult");
    /**
     * The instances of the upper layer that the prelude makes over a stub {@code core}, by their interface.
     */
    private static final @NotNull Map<String, String> INSTANCES = Map.ofEntries(
            Map.entry("Player", "game.players.get(0)"),
            Map.entry("PlayerUpgrades", "game.players.get(0).upgrades"),
            Map.entry("PlayerTechs", "game.players.get(0).techs"),
            Map.entry("PlayerUnits", "game.players.get(0).units"),
            Map.entry("PlayerDeaths", "game.players.get(0).deaths"),
            Map.entry("PlayerKills", "game.players.get(0).kills"),
            Map.entry("PlayerScore", "game.players.get(0).score"),
            Map.entry("PlayerVision", "game.players.get(0).vision"),
            Map.entry("Location", "game.map.locations.get(0)"),
            Map.entry("Tile", "game.map.tileset.getTile(0, 0)"),
            Map.entry("Timer", "game.every(1, function () {})"),
            Map.entry("Grp", "game.map.resources.getGrp('a.grp')"),
            Map.entry("Anim", "game.map.resources.getAnim('a.anim')"),
            Map.entry("Position", "Position.of(1, 2)"),
            Map.entry("Unit", "prelude.createEvent(record(EventKind.UnitCreated.id)).getUnit()"),
            Map.entry("UnitTimers", "prelude.createEvent(record(EventKind.UnitCreated.id)).getUnit().timers"),
            Map.entry("UnitType", "UnitType.get(0)"),
            Map.entry("WeaponType", "WeaponType.get(0)"),
            Map.entry("FlingyType", "FlingyType.get(0)"),
            Map.entry("SpriteType", "SpriteType.get(0)"),
            Map.entry("UpgradeType", "UpgradeType.get(0)"),
            Map.entry("TechType", "TechType.get(0)"),
            Map.entry("Order", "Order.get(0)"),
            Map.entry("ImageType", "ImageType.get(0)"),
            Map.entry("Key", "Key.get(65)"),
            Map.entry("MouseButton", "MouseButton.get(0)"),
            Map.entry("UnitGroup", "UnitGroup.Men"),
            Map.entry("ScoreType", "ScoreType.get(0)"),
            Map.entry("ResourceType", "ResourceType.get(0)"),
            Map.entry("Alliance", "Alliance.get(0)"),
            Map.entry("GameSpeed", "GameSpeed.get(0)"),
            Map.entry("Cheat", "Cheat.BlackSheepWall"),
            Map.entry("GameMode", "GameMode.get(2)"),
            Map.entry("UnitSize", "UnitSize.get(0)"),
            Map.entry("DamageType", "DamageType.get(0)"),
            Map.entry("LocationFlag", "LocationFlag.LowGround"),
            Map.entry("DrawFunction", "DrawFunction.get(0)"),
            Map.entry("BulletState", "BulletState.get(0)"),
            Map.entry("Animation", "Animation.get(0)"),
            Map.entry("Sprite", "game.createEffect(SpriteType.get(0), Position.of(1, 2))"),
            Map.entry("SpriteImage", "game.createEffect(SpriteType.get(0), Position.of(1, 2)).getMainImage()"),
            Map.entry("Bullet", "prelude.createEvent(record(EventKind.BulletCreated.id)).getBullet()"),
            Map.entry("EventKind", "EventKind.get(0)"),
            Map.entry("InputKind", "InputKind.get(0)"),
            Map.entry("DecisionKind", "DecisionKind.get(0)"),
            Map.entry("Channel", "sync.createChannel(function () {})"),
            Map.entry("PlayerValue", "sync.createPlayerValue(0)"),
            Map.entry("Image", "game.map.resources.getImage('a.png')"),
            Map.entry("Sound", "game.map.resources.getSound('a.wav')"),
            Map.entry("Font", "Font.get(0)"),
            Map.entry("Align", "Align.get(0)"),
            Map.entry("GraphicsKind", "Graphics.get(0)"),
            Map.entry("ConsoleControl", "ConsoleControl.get(0)"),
            Map.entry("ConsoleImage", "ConsoleImage.get(0)"),
            Map.entry("ConsoleAnchor", "ConsoleAnchor.get(0)"),
            Map.entry("ConsoleControlHandle", "display.console.getControl(ConsoleControl.Minimap)"),
            Map.entry("ButtonCondition", "ButtonCondition.get(0)"),
            Map.entry("ButtonAction", "ButtonAction.get(0)"),
            Map.entry("ButtonState", "ButtonState.Enabled"),
            Map.entry("ButtonKind", "ButtonKind.get(0)"),
            Map.entry("ButtonSet", "display.commandCard.getButtonSet(0)"),
            Map.entry("Button", "prelude.createButtonObject(0, 0)")
    );
    /**
     * A stub {@code core} with what the prelude reads while it runs and makes the objects above: one type of each kind, one player, one location
     * and every tile, no live unit or bullet, one sprite with its main image.
     */
    private static final @NotNull String STUB_CORE = """
                                                     (function () {
                                                         function types() {
                                                             return {getCount: function () { return 1; }, getName: function () { return 'Stub Type'; }};
                                                         }
                                                         return {
                                                             unitType: types(), weaponType: types(), flingyType: types(), spriteType: types(), imageType: types(), upgradeType: types(),
                                                             techType: types(), orderType: types(),
                                                             dat: {units: {}, weapons: {}, flingy: {}, sprites: {}, images: {}, upgrades: {}, techdata: {}, orders: {}},
                                                             events: {getKindCount: function () { return KINDS.length; }, getKindName: function (kind) { return KINDS[kind]; }},
                                                             command: {submit: function () {}},
                                                             player: {getCount: function () { return 1; }},
                                                             unit: {exists: function () { return false; }},
                                                             location: {getCount: function () { return 1; }},
                                                             terrain: {isValidTile: function () { return true; }},
                                                             sprite: {exists: function () { return true; }, getGeneration: function () { return 1; },
                                                                      createEffect: function () { return 0; }, getMainImage: function () { return 0; }},
                                                             image: {exists: function () { return true; }, getGeneration: function () { return 1; }},
                                                             bullet: {exists: function () { return false; }},
                                                             game: {getFrame: function () { return 0; }, getFrameDuration: function () { return 42; }},
                                                             resources: {getGrp: function () { return 0; }, getAnim: function () { return 1; }, getImage: function () { return 2; },
                                                                         getSound: function () { return 3; }},
                                                             buttons: {getSetCount: function () { return 250; }, getConditionCount: function () { return 1; },
                                                                       getConditionName: function () { return 'Stub Condition'; }, getActionCount: function () { return 1; },
                                                                       getActionName: function () { return 'Stub Action'; }},
                                                             screen: {}, world: {}, minimap: {}
                                                         };
                                                     })()
                                                     """;
    /**
     * The values of every event, decision and input record: one unit that no longer exists, no other unit; record(kind) copies them with a
     * kind.
     */
    private static final @NotNull String EVENT_VALUES = """
                                                        var values = {frame: 0, unit: 1, unitGeneration: 0, unitType: 0, owner: 0, player: 0, weapon: 0, amount: 0, order: 0, target: -1,
                                                                      targetGeneration: -1, targetX: 0, targetY: 0, buildType: 0, queued: false, tech: 0, upgrade: 0, level: 1, source: -1,
                                                                      sourceGeneration: -1, killer: -1, killerGeneration: -1, killerPlayer: 0, sourcePlayer: 0, producer: -1,
                                                                      producerGeneration: -1, transport: -1, transportGeneration: -1, previousOwner: 0, reason: 0, result: 3, missed: false,
                                                                      key: 65, button: 0, modifiers: 0, positioned: false, x: 0, y: 0, text: '', consumed: false, cancelled: false,
                                                                      rejected: false, set: 0, position: 1, condition: 0, conditionValue: 0, action: 0, actionValue: 0, hotkey: false,
                                                                      bullet: 0, bulletGeneration: 1};
                                                        function record(kind) {
                                                            var copy = {kind: kind};
                                                            for (var key in values) {
                                                                copy[key] = values[key];
                                                            }
                                                            return copy;
                                                        }
                                                        """;
    /**
     * Reads the names of the module scope and the global scope, which of the declared globals a script reaches, the own and the prototype
     * chain property names of property paths from the module scope, and the identifiers of the generated tables by id, as one JSON log line.
     */
    private static final @NotNull String RUNTIME_PROBE = """
                                                         (function (scope, declared, paths, tables) {
                                                             function chainNames(value) {
                                                                 var names = [];
                                                                 for (var object = value; object !== null; object = Object.getPrototypeOf(object)) {
                                                                     names = names.concat(Object.getOwnPropertyNames(object));
                                                                 }
                                                                 return names;
                                                             }
                                                             var result = {globals: Object.getOwnPropertyNames(scope).concat(Object.getOwnPropertyNames(Object.getPrototypeOf(scope))),
                                                                           defined: declared.filter(function (name) { return name in scope; }), own: {}, chain: {}, tables: {}};
                                                             paths.forEach(function (path) {
                                                                 var value = scope;
                                                                 path.forEach(function (name) {
                                                                     value = value === null || value === undefined ? undefined : value[name];
                                                                 });
                                                                 if (value !== null && (typeof value === 'object' || typeof value === 'function')) {
                                                                     result.own[path.join('.')] = Object.getOwnPropertyNames(value);
                                                                     result.chain[path.join('.')] = chainNames(value);
                                                                 }
                                                             });
                                                             tables.forEach(function (name) {
                                                                 var table = scope[name];
                                                                 var entries = [];
                                                                 for (var id = 0; id < table.getCount(); id++) {
                                                                     entries.push([table.get(id).getName()]);
                                                                 }
                                                                 Object.getOwnPropertyNames(table).forEach(function (key) {
                                                                     var constant = table[key];
                                                                     if (constant !== null && typeof constant === 'object') {
                                                                         entries[constant.getId()].push(key);
                                                                     }
                                                                 });
                                                                 result.tables[name] = entries;
                                                             });
                                                             log.info(JSON.stringify(result));
                                                         })(this, DECLARED, PATHS, TABLES);
                                                         """;

    /**
     * Reads the declarations of every declarations file as one text.
     */
    private static @NotNull ScriptDeclarationsCheck readDeclarations() throws IOException {
        StringBuilder text = new StringBuilder();

        for (String fileName : ScriptProjectUtils.DECLARATION_FILE_NAMES) {
            text.append(new String(ScriptProjectUtils.readDeclarations(fileName), StandardCharsets.UTF_8)).append('\n');
        }

        return new ScriptDeclarationsCheck(text.toString());
    }

    private static @NotNull String readPreludeFile(@NotNull String fileName) throws IOException {
        try (InputStream input = ScriptProjectUtilsTest.class.getResourceAsStream(PRELUDE_FOLDER + fileName)) {
            Assertions.assertNotNull(input, fileName);

            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Lists the property paths to read: each declared global with an interface type, and each property of an interface type below it other
     * than the instance interfaces, which {@link #readInstanceNames} reads.
     */
    private static void collectPaths(@NotNull ScriptDeclarationsCheck declarations, @NotNull List<String> path, @NotNull List<String> interfaces,
                                     @NotNull Map<List<String>, List<String>> paths) {
        paths.put(path, interfaces);

        if (path.size() >= MAXIMUM_PATH_DEPTH) {
            return;
        }

        for (ScriptDeclarationsCheck.Member member : declarations.getMembers(interfaces).values()) {
            if (!member.isMethod() && declarations.isInterface(member.getType()) && !INSTANCES.containsKey(member.getType())) {
                List<String> childPath = new ArrayList<>(path);

                childPath.add(member.getName());
                collectPaths(declarations, childPath, List.of(member.getType()), paths);
            }
        }
    }

    /**
     * Runs the probe in a script runtime with the game API of a game without game data and returns its JSON result.
     */
    private static @NotNull Map<?, ?> runRuntimeProbe(@NotNull Set<String> declared, @NotNull Set<List<String>> paths) throws IOException {
        List<String> messages = new ArrayList<>();
        String source = RUNTIME_PROBE.replace("PATHS", toJsonArray(paths.stream().map(ScriptProjectUtilsTest::toJsonPath).toList(), false))
                                     .replace("DECLARED", toJsonArray(List.copyOf(declared), true))
                                     .replace("TABLES", toJsonArray(GENERATED_TABLES, true));
        ScriptPackage scriptPackage = ScriptPackage.create(
                ScriptManifest.parse("{\"name\": \"Probe\", \"main\": \"main.js\", \"api\": \"1\"}".getBytes(StandardCharsets.UTF_8)),
                Map.of("main.js", source.getBytes(StandardCharsets.UTF_8)), Map.of()
        );
        Game game = Game.create(null, new GameMap(MAP_TILE_SIZE, MAP_TILE_SIZE, MAP_TILESET, new int[MAP_TILE_SIZE * MAP_TILE_SIZE]),
                                new LcgRandom(0));
        ScriptLogOutput logOutput = (level, moduleName, message) -> messages.add(level == LogLevel.INFO ? message : level + " " + message);
        ScriptRuntime runtime = ScriptRuntime.create(scriptPackage, game, ScriptResources.EMPTY, SEED, ScriptRuntime.INSTRUCTION_BUDGET, logOutput);

        runtime.start();

        Assertions.assertEquals(1, messages.size(), messages::toString);

        return (Map<?, ?>) JsonReader.parse(messages.getFirst().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Creates the expression of the object a hook receives: a decision object ({@code onBefore*}), a local input object ({@code onLocal*}) or
     * an event object over a record of the hook's kind.
     */
    private static @NotNull String createHookObjectExpression(@NotNull String hookName) {
        if (hookName.startsWith(DECISION_HOOK_PREFIX)) {
            String kind = "DecisionKind." + hookName.substring(DECISION_HOOK_PREFIX.length()) + ".id";

            return "prelude.createDecision(" + kind + ", record(" + kind + "))";
        }

        if (hookName.startsWith(ScriptHook.LOCAL_NAME_PREFIX)) {
            return "prelude.createInputEvent(record(InputKind." + hookName.substring(ScriptHook.LOCAL_NAME_PREFIX.length()) + ".id))";
        }

        return "prelude.createEvent(record(EventKind." + hookName.substring(ScriptHook.NAME_PREFIX.length()) + ".id))";
    }

    private static @NotNull String toJsonArray(@NotNull List<String> values, boolean quoted) {
        List<String> items = new ArrayList<>();

        for (String value : values) {
            items.add(quoted ? '"' + value + '"' : value);
        }

        return '[' + String.join(", ", items) + ']';
    }

    private static @NotNull String toJsonPath(@NotNull List<String> path) {
        return toJsonArray(path, true);
    }

    /**
     * Runs the prelude's area files over the stub {@code core} as the runtime does, with their names in the global {@code prelude}, and returns
     * the enumerable inherited names of each expression's object, without {@code toString}.
     */
    private static @NotNull Map<String, Set<String>> readInstanceNames(@NotNull Map<String, String> expressions) throws IOException {
        Map<String, Set<String>> names = new LinkedHashMap<>();

        try (Context context = new ContextFactory().enterContext()) {
            context.setInterpretedMode(true);
            context.setLanguageVersion(Context.VERSION_ECMASCRIPT);

            TopLevel global = new TopLevel();

            context.initSafeStandardObjects(global, false);
            ScriptableObject.putProperty(global, "KINDS", context.newArray(global, EVENT_KINDS.toArray()));

            Object core = context.evaluateString(global, STUB_CORE, "core.js", 1, null);
            Scriptable prelude = context.newObject(global);

            ScriptableObject.putProperty(prelude, "datTables", ScriptDatTables.createPreludeTables(context, global));

            for (String fileName : PRELUDE_FILES) {
                Function area = (Function) context.evaluateString(global, readPreludeFile(fileName), fileName, 1, null);
                Scriptable result = (Scriptable) area.call(context, global, global, new Object[]{core, prelude});

                for (String part : List.of("exports", "shared")) {
                    Scriptable values = (Scriptable) ScriptableObject.getProperty(result, part);

                    for (Object id : values.getIds()) {
                        Object value = ScriptableObject.getProperty(values, (String) id);

                        ScriptableObject.putProperty(prelude, (String) id, value);

                        if (part.equals("exports")) {
                            ScriptableObject.putProperty(global, (String) id, value);
                        }
                    }
                }
            }

            ScriptableObject.putProperty(global, "prelude", prelude);
            context.evaluateString(global, EVENT_VALUES, "values.js", 1, null);
            names.put(HOOK_NAMES, new TreeSet<>(List.of(Context.toString(context.evaluateString(global, "prelude.hookNames.join(',')", "hooks", 1,
                                                                                                null)).split(","))));

            for (Map.Entry<String, String> expression : expressions.entrySet()) {
                String code = "(function (object) { var names = []; for (var name in object) { if (name !== 'toString' && " +
                              "!Object.prototype.hasOwnProperty.call(object, name)) { names.push(name); } } return names.join(','); })(" +
                              expression.getValue() + ")";
                String joined = Context.toString(context.evaluateString(global, code, expression.getKey(), 1, null));

                names.put(expression.getKey(), new TreeSet<>(joined.isEmpty() ? List.of() : List.of(joined.split(","))));
            }
        }

        return names;
    }

    /**
     * @return the global names of Rhino's safe standard objects
     */
    private static @NotNull Set<String> readStandardGlobals() {
        Set<String> names = new HashSet<>();

        try (Context context = new ContextFactory().enterContext()) {
            context.setLanguageVersion(Context.VERSION_ECMASCRIPT);

            Scriptable global = context.initSafeStandardObjects(new TopLevel(), false);

            for (Object id : ((ScriptableObject) global).getAllIds()) {
                names.add(id.toString());
            }
        }

        return names;
    }

    private static @NotNull Set<String> toNames(@NotNull Object value) {
        Set<String> names = new TreeSet<>();

        for (Object name : (List<?>) value) {
            names.add(name.toString());
        }

        return names;
    }

    /**
     * Writes the declared members of a generated table, one line per identifier in id order.
     */
    private static @NotNull String writeGeneratedMembers(@NotNull String table, @NotNull List<?> entries) {
        StringBuilder text = new StringBuilder(GENERATED_COMMENT).append(table).append('\n');

        for (int id = 0; id < entries.size(); id++) {
            List<?> entry = (List<?>) entries.get(id);

            for (Object identifier : entry.subList(1, entry.size())) {
                String name = identifier.toString().matches("[A-Za-z_$][\\w$]*") ? identifier.toString() : '"' + identifier.toString() + '"';

                text.append("    /** ").append(id).append(' ').append(entry.getFirst()).append(" */ readonly ").append(name).append(": ")
                    .append(table).append(";\n");
            }
        }

        return text.toString();
    }

    private static void compareNames(@NotNull String subject, @NotNull Set<String> declared, @NotNull Set<String> runtime, boolean exact,
                                     boolean indexed, @NotNull List<String> problems) {
        Set<String> missingInRuntime = new TreeSet<>(declared);
        Set<String> undeclared = new TreeSet<>(runtime);

        missingInRuntime.removeAll(runtime);
        undeclared.removeAll(declared);

        if (!missingInRuntime.isEmpty()) {
            problems.add(subject + " declares what the runtime lacks: " + missingInRuntime);
        }

        if (exact && !indexed && !undeclared.isEmpty()) {
            problems.add(subject + " does not declare: " + undeclared);
        }
    }

    @Test
    void write_project_should_write_a_folder_that_checks_and_packs(@TempDir @NotNull Path folder) throws IOException {
        Path project = folder.resolve("Star Map");

        ScriptProjectUtils.writeProject(project);

        ScriptPackage scriptPackage = ScriptPackage.readFolder(project);
        Map<?, ?> jsconfig = (Map<?, ?>) JsonReader.parse(Files.readAllBytes(project.resolve(ScriptProjectUtils.JSCONFIG_FILE_NAME)));
        Map<?, ?> options = (Map<?, ?>) jsconfig.get("compilerOptions");

        Assertions.assertEquals("Star Map", scriptPackage.getManifest().getName());
        Assertions.assertEquals(ScriptProjectUtils.MAIN_FILE_NAME, scriptPackage.getManifest().getMain());
        Assertions.assertEquals(ScriptProjectUtils.API_VERSION, scriptPackage.getManifest().getApi());
        Assertions.assertEquals(Set.of(ScriptProjectUtils.MAIN_FILE_NAME), scriptPackage.getScripts().keySet());
        Assertions.assertTrue(scriptPackage.getResources().isEmpty());
        Assertions.assertTrue(Files.isDirectory(project.resolve(ScriptPackage.RESOURCE_FOLDER)));

        for (String fileName : ScriptProjectUtils.DECLARATION_FILE_NAMES) {
            Assertions.assertArrayEquals(ScriptProjectUtils.readDeclarations(fileName), Files.readAllBytes(project.resolve(fileName)));
        }

        Assertions.assertEquals(true, options.get("noLib"));
        Assertions.assertEquals(true, options.get("checkJs"));
        Assertions.assertEquals(List.of(ScriptProjectUtils.CORE_DECLARATIONS_FILE_NAME, ScriptProjectUtils.DECLARATIONS_FILE_NAME, "**/*.js"),
                                jsconfig.get("include"));
        Assertions.assertEquals(List.of(), ScriptCheckUtils.check(project).stream().map(ScriptFinding::toString).toList());

        try (Stream<Path> files = Files.list(project)) {
            Assertions.assertEquals(Set.of(ScriptManifest.FILE_NAME, ScriptProjectUtils.MAIN_FILE_NAME, ScriptProjectUtils.JSCONFIG_FILE_NAME,
                                           ScriptProjectUtils.CORE_DECLARATIONS_FILE_NAME, ScriptProjectUtils.DECLARATIONS_FILE_NAME,
                                           ScriptPackage.RESOURCE_FOLDER),
                                    new HashSet<>(files.map(file -> file.getFileName().toString()).toList()));
        }
    }

    @Test
    void write_project_should_refuse_a_folder_with_files(@TempDir @NotNull Path folder) throws IOException {
        Files.writeString(folder.resolve("notes.txt"), "keep");

        Assertions.assertThrows(IOException.class, () -> ScriptProjectUtils.writeProject(folder));
        Assertions.assertFalse(Files.exists(folder.resolve(ScriptManifest.FILE_NAME)));
    }

    @Test
    void declarations_name_every_global_and_member_of_the_runtime() throws IOException {
        ScriptDeclarationsCheck declarations = readDeclarations();
        Map<List<String>, List<String>> paths = new LinkedHashMap<>();

        for (Map.Entry<String, String> global : declarations.getGlobals().entrySet()) {
            List<String> interfaces = declarations.getInterfaces(global.getValue());

            if (!interfaces.isEmpty()) {
                collectPaths(declarations, List.of(global.getKey()), interfaces, paths);
            }
        }

        Map<?, ?> probe = runRuntimeProbe(declarations.getGlobals().keySet(), paths.keySet());
        Set<String> apiGlobals = toNames(probe.get("globals"));
        List<String> problems = new ArrayList<>();

        apiGlobals.removeAll(readStandardGlobals());
        apiGlobals.addAll(REPLACED_GLOBALS);
        apiGlobals.removeAll(UNDECLARED_GLOBALS);
        compareNames("The globals", declarations.getGlobals().keySet(), toNames(probe.get("defined")), false, false, problems);

        if (!declarations.getGlobals().keySet().containsAll(apiGlobals)) {
            Set<String> undeclared = new TreeSet<>(apiGlobals);

            undeclared.removeAll(declarations.getGlobals().keySet());
            problems.add("The globals do not declare: " + undeclared);
        }

        for (Map.Entry<List<String>, List<String>> path : paths.entrySet()) {
            String key = String.join(".", path.getKey());
            boolean exact = apiGlobals.contains(path.getKey().getFirst());
            Object names = ((Map<?, ?>) probe.get(exact ? "own" : "chain")).get(key);

            if (names == null) {
                problems.add(key + " is no object in the runtime");
                continue;
            }

            List<String> problemsBefore = new ArrayList<>(problems);

            compareNames(key + " (" + path.getValue() + ")", declarations.getMembers(path.getValue()).keySet(), toNames(names), exact,
                         declarations.isIndexed(path.getValue()), problems);

            if (!problems.equals(problemsBefore) && GENERATED_TABLES.contains(key)) {
                problems.add(writeGeneratedMembers(key, (List<?>) ((Map<?, ?>) probe.get("tables")).get(key)));
            }
        }

        Assertions.assertEquals(List.of(), problems, () -> String.join("\n", problems));
    }

    @Test
    void declarations_name_every_hook_and_the_members_of_its_object() throws IOException {
        ScriptDeclarationsCheck declarations = readDeclarations();
        Map<String, ScriptDeclarationsCheck.Member> hooks = declarations.getMembers(List.of("Hooks"));
        Set<String> hookNames = new TreeSet<>();
        Map<String, String> expressions = new LinkedHashMap<>();
        Map<String, String> interfaces = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();

        for (ScriptHook hook : ScriptHook.values()) {
            String name = hook.getFunctionName();

            hookNames.add(name);

            ScriptDeclarationsCheck.Member member = hooks.get(name);

            if (member != null && declarations.isInterface(member.getType()) && hook != ScriptHook.RENDER) {
                expressions.put(name, createHookObjectExpression(name));
                interfaces.put(name, member.getType());
            }
        }

        for (Map.Entry<String, String> instance : INSTANCES.entrySet()) {
            expressions.put(instance.getKey(), instance.getValue());
            interfaces.put(instance.getKey(), instance.getKey());
        }

        compareNames("Hooks", hooks.keySet(), hookNames, true, false, problems);

        Map<String, Set<String>> instanceNames = readInstanceNames(expressions);

        compareNames("The prelude's hooks", instanceNames.remove(HOOK_NAMES), hookNames, true, false, problems);

        for (Map.Entry<String, Set<String>> names : instanceNames.entrySet()) {
            String declared = interfaces.get(names.getKey());

            compareNames(names.getKey() + " (" + declared + ")", declarations.getMembers(List.of(declared)).keySet(), names.getValue(), true,
                         false, problems);
        }

        Assertions.assertEquals(List.of(), problems, () -> String.join("\n", problems));
    }
}
