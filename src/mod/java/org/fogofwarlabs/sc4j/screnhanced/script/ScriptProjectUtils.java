package org.fogofwarlabs.sc4j.screnhanced.script;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.json.JsonWriterUtils;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Writes a new script folder or TypeScript project and reads the script API declarations.
 * <p>
 * A new script folder holds {@value ScriptManifest#FILE_NAME} (the folder name, the main script {@value #MAIN_FILE_NAME} and the script API
 * {@value #API_VERSION}), {@value #MAIN_FILE_NAME} (a commented example with {@code onStart} and {@code onFrame}), {@value #JSCONFIG_FILE_NAME}
 * (the editor settings: no library globals, checked scripts, every file its own module), {@value #CORE_DECLARATIONS_FILE_NAME} (the
 * declarations of the lower layer {@code core}), {@value #DECLARATIONS_FILE_NAME} (the declarations of everything else a script sees) and
 * the empty resource folder {@value ScriptPackage#RESOURCE_FOLDER}.
 * <p>
 * A new TypeScript project holds {@value #TSCONFIG_FILE_NAME} (the tsc settings: target ES5, module commonjs, source maps, no library globals
 * but the two declaration files, every file its own module, the sources {@value #SOURCE_FOLDER} compiled into {@value #SCRIPT_FOLDER}), the
 * two declaration files, {@value #SOURCE_FOLDER}/{@value #MAIN_SOURCE_FILE_NAME} (the example in TypeScript, with {@code defineHooks}) and
 * the script folder {@value #SCRIPT_FOLDER} with {@value ScriptManifest#FILE_NAME} (the project folder name and the main script
 * {@value #MAIN_FILE_NAME}, which tsc writes) and the empty resource folder. The sources and the declarations lie outside the script folder, so
 * that {@code pack} reads only the manifest, the compiled scripts with their source maps and the resources.
 */
public final class ScriptProjectUtils {
    /**
     * The file name of the declarations of the upper layer, the runtime and the standard objects, a resource next to this class.
     */
    public static final @NotNull String DECLARATIONS_FILE_NAME = "scr-enhanced.d.ts";
    /**
     * The file name of the declarations of the lower layer {@code core}, a resource next to this class.
     */
    public static final @NotNull String CORE_DECLARATIONS_FILE_NAME = "core.d.ts";
    /**
     * The file names of the script API declarations, which a new script folder holds.
     */
    public static final @NotNull List<@NotNull String> DECLARATION_FILE_NAMES = List.of(CORE_DECLARATIONS_FILE_NAME, DECLARATIONS_FILE_NAME);
    /**
     * The file name of the editor settings.
     */
    public static final @NotNull String JSCONFIG_FILE_NAME = "jsconfig.json";
    /**
     * The file name of the main script of a new script folder.
     */
    public static final @NotNull String MAIN_FILE_NAME = "main.js";
    /**
     * The script API version of a new script folder.
     */
    public static final @NotNull String API_VERSION = "1";
    /**
     * The file name of the tsc settings of a TypeScript project.
     */
    public static final @NotNull String TSCONFIG_FILE_NAME = "tsconfig.json";
    /**
     * The folder of the TypeScript sources in a TypeScript project.
     */
    public static final @NotNull String SOURCE_FOLDER = "src";
    /**
     * The script folder in a TypeScript project, which tsc fills and {@code pack} reads.
     */
    public static final @NotNull String SCRIPT_FOLDER = "script";
    /**
     * The file name of the main source of a TypeScript project, which tsc compiles into {@value #MAIN_FILE_NAME}.
     */
    public static final @NotNull String MAIN_SOURCE_FILE_NAME = "main.ts";

    private static final @NotNull String MAIN_SCRIPT = """
                                                       // The main script of the map: SC:R Enhanced runs it when the map starts; require('./other.js') loads another script.
                                                       // scr-enhanced.d.ts and core.d.ts declare the script API, and jsconfig.json makes the editor check the scripts against them.
                                                       // Hooks are top-level functions with known names. Type the event of a hook with JSDoc, such as
                                                       // /** @param {UnitDiedEvent} event */ before function onUnitDied(event).
                                                       
                                                       /**
                                                        * Called once at the first frame, before the other hooks of the frame.
                                                        */
                                                       function onStart() {
                                                           game.print('The script runs.');
                                                       }
                                                       
                                                       /**
                                                        * Called every game frame, before the triggers.
                                                        *
                                                        * @param {number} frame the frame number
                                                        */
                                                       function onFrame(frame) {
                                                           if (frame % Time.fromMinutes(1) === 0) {
                                                               log.info('Minute', frame / Time.fromMinutes(1));
                                                           }
                                                       }
                                                       """;
    private static final @NotNull String MAIN_SOURCE = """
                                                       // The main source of the map: tsc compiles it into script/main.js, which SC:R Enhanced runs when the map starts.
                                                       // scr-enhanced.d.ts and core.d.ts declare the script API; import { give } from './bonus' loads the module src/bonus.ts.
                                                       // defineHooks defines the hooks and gives their parameters types.
                                                       
                                                       defineHooks({
                                                           /**
                                                            * Called once at the first frame, before the other hooks of the frame.
                                                            */
                                                           onStart() {
                                                               game.print('The script runs.');
                                                           },
                                                       
                                                           /**
                                                            * Called every game frame, before the triggers.
                                                            *
                                                            * @param frame the frame number
                                                            */
                                                           onFrame(frame) {
                                                               if (frame % Time.fromMinutes(1) === 0) {
                                                                   log.info('Minute', frame / Time.fromMinutes(1));
                                                               }
                                                           }
                                                       });
                                                       """;

    /**
     * Writes a new script folder.
     *
     * @param folder the script folder, absent or holding only hidden entries; its name becomes the package name
     * @throws IOException if the folder holds files, has no name or a file cannot be written
     */
    public static void writeProject(@NotNull Path folder) throws IOException {
        Path absoluteFolder = requireEmptyFolder(folder);
        Map<String, Object> manifest = createManifest(absoluteFolder);

        Files.createDirectories(absoluteFolder.resolve(ScriptPackage.RESOURCE_FOLDER));
        Files.writeString(absoluteFolder.resolve(ScriptManifest.FILE_NAME), JsonWriterUtils.write(manifest), StandardCharsets.UTF_8);
        Files.writeString(absoluteFolder.resolve(MAIN_FILE_NAME), MAIN_SCRIPT, StandardCharsets.UTF_8);
        Files.writeString(absoluteFolder.resolve(JSCONFIG_FILE_NAME), JsonWriterUtils.write(createJsconfig()), StandardCharsets.UTF_8);

        for (String fileName : DECLARATION_FILE_NAMES) {
            Files.write(absoluteFolder.resolve(fileName), readDeclarations(fileName));
        }
    }

    /**
     * Writes a new TypeScript project: the tsc settings, the declarations, the main source and the script folder {@value #SCRIPT_FOLDER} with
     * the manifest and the resource folder.
     *
     * @param folder the project folder, absent or holding only hidden entries; its name becomes the package name
     * @throws IOException if the folder holds files, has no name or a file cannot be written
     */
    public static void writeTypeScriptProject(@NotNull Path folder) throws IOException {
        Path absoluteFolder = requireEmptyFolder(folder);
        Path scriptFolder = absoluteFolder.resolve(SCRIPT_FOLDER);
        Map<String, Object> manifest = createManifest(absoluteFolder);

        Files.createDirectories(scriptFolder.resolve(ScriptPackage.RESOURCE_FOLDER));
        Files.createDirectories(absoluteFolder.resolve(SOURCE_FOLDER));
        Files.writeString(scriptFolder.resolve(ScriptManifest.FILE_NAME), JsonWriterUtils.write(manifest), StandardCharsets.UTF_8);
        Files.writeString(absoluteFolder.resolve(SOURCE_FOLDER).resolve(MAIN_SOURCE_FILE_NAME), MAIN_SOURCE, StandardCharsets.UTF_8);
        Files.writeString(absoluteFolder.resolve(TSCONFIG_FILE_NAME), JsonWriterUtils.write(createTsconfig()), StandardCharsets.UTF_8);

        for (String fileName : DECLARATION_FILE_NAMES) {
            Files.write(absoluteFolder.resolve(fileName), readDeclarations(fileName));
        }
    }

    /**
     * Reads a file of the script API declarations ({@link #DECLARATION_FILE_NAMES}).
     *
     * @param fileName the file name
     * @return the UTF-8 text of the declarations
     * @throws IOException if the resource is missing or cannot be read
     */
    public static byte @NotNull [] readDeclarations(@NotNull String fileName) throws IOException {
        try (InputStream input = ScriptProjectUtils.class.getResourceAsStream(fileName)) {
            if (input == null) {
                throw Exceptions.newIOException("The script API declarations {0} are missing.", fileName);
            }

            return input.readAllBytes();
        }
    }

    /**
     * Checks that a folder to write is absent or holds only hidden entries, whose names start with {@code .}, and has a name.
     *
     * @return the absolute folder
     */
    private static @NotNull Path requireEmptyFolder(@NotNull Path folder) throws IOException {
        Path absoluteFolder = folder.toAbsolutePath().normalize();

        if (absoluteFolder.getFileName() == null) {
            throw Exceptions.newIOException("The script folder {0} has no name.", folder);
        }

        if (Files.exists(absoluteFolder)) {
            if (!Files.isDirectory(absoluteFolder)) {
                throw Exceptions.newIOException("The script folder {0} is a file.", folder);
            }

            try (Stream<Path> entries = Files.list(absoluteFolder)) {
                if (entries.anyMatch(entry -> !ScriptPackage.isHidden(absoluteFolder.relativize(entry)))) {
                    throw Exceptions.newIOException("The script folder is not empty: {0}", folder);
                }
            }
        }

        return absoluteFolder;
    }

    /**
     * Creates the manifest of a new folder: the folder name, the main script {@value #MAIN_FILE_NAME} and the script API {@value #API_VERSION}.
     */
    private static @NotNull Map<String, Object> createManifest(@NotNull Path absoluteFolder) {
        Map<String, Object> manifest = new LinkedHashMap<>();

        manifest.put("name", String.valueOf(absoluteFolder.getFileName()));
        manifest.put("main", MAIN_FILE_NAME);
        manifest.put("api", API_VERSION);

        return manifest;
    }

    /**
     * Creates the editor settings: the declarations as the only library, checked scripts, every file its own module as in the runtime, and
     * {@code require} ids that start without {@code ./} relative to the folder.
     */
    private static @NotNull Map<String, Object> createJsconfig() {
        Map<String, Object> options = new LinkedHashMap<>();
        Map<String, Object> paths = new LinkedHashMap<>();
        Map<String, Object> jsconfig = new LinkedHashMap<>();

        paths.put("*", List.of("./*"));

        options.put("noLib", true);
        options.put("checkJs", true);
        options.put("target", "ES2015");
        options.put("module", "commonjs");
        options.put("moduleDetection", "force");
        options.put("paths", paths);

        jsconfig.put("compilerOptions", options);
        jsconfig.put("include", List.of(CORE_DECLARATIONS_FILE_NAME, DECLARATIONS_FILE_NAME, "**/*.js"));
        jsconfig.put("exclude", List.of(ScriptPackage.RESOURCE_FOLDER));

        return jsconfig;
    }

    /**
     * Creates the tsc settings: target ES5 and module commonjs, which Rhino runs, every file its own module as in the runtime, the declarations
     * as the only library, strict checks, a source map next to each compiled script, and the sources compiled into the script folder.
     */
    private static @NotNull Map<String, Object> createTsconfig() {
        Map<String, Object> options = new LinkedHashMap<>();
        Map<String, Object> tsconfig = new LinkedHashMap<>();

        options.put("target", "ES5");
        options.put("module", "commonjs");
        options.put("moduleDetection", "force");
        options.put("noLib", true);
        options.put("types", List.of());
        options.put("strict", true);
        options.put("noImplicitReturns", true);
        options.put("sourceMap", true);
        options.put("rootDir", SOURCE_FOLDER);
        options.put("outDir", SCRIPT_FOLDER);

        tsconfig.put("compilerOptions", options);
        tsconfig.put("files", List.of(DECLARATIONS_FILE_NAME, CORE_DECLARATIONS_FILE_NAME));
        tsconfig.put("include", List.of(SOURCE_FOLDER + "/**/*.ts"));

        return tsconfig;
    }

    private ScriptProjectUtils() {
    }
}
