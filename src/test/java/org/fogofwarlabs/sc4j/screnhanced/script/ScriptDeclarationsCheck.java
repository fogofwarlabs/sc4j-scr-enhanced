package org.fogofwarlabs.sc4j.screnhanced.script;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the names of TypeScript declarations as {@code scr-enhanced.d.ts} and {@code core.d.ts} write them: the global declarations
 * ({@code declare const}, {@code declare var}, {@code declare function}) with their types, and the interfaces with their bases and members. It
 * reads names and simple types only; call, construct and computed members are skipped.
 */
final class ScriptDeclarationsCheck {
    private static final @NotNull Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final @NotNull Pattern LINE_COMMENT = Pattern.compile("//[^\\n]*");
    private static final @NotNull Pattern INTERFACE = Pattern.compile(
            "^interface\\s+([A-Za-z_$][\\w$]*)\\s*(?:<[^{]*?>)?\\s*(?:extends\\s+([^{]+?))?\\s*\\{(.*)}$", Pattern.DOTALL);
    private static final @NotNull Pattern GLOBAL = Pattern.compile("^declare\\s+(?:const|var|let)\\s+([A-Za-z_$][\\w$]*)\\s*:\\s*(.+);$",
                                                                   Pattern.DOTALL);
    private static final @NotNull Pattern GLOBAL_FUNCTION = Pattern.compile("^declare\\s+function\\s+([A-Za-z_$][\\w$]*)");
    private static final @NotNull Pattern INDEX_SIGNATURE = Pattern.compile("^\\[\\s*[\\w$]+\\s*:\\s*string\\s*]");
    private static final @NotNull Pattern MEMBER = Pattern.compile("^(?:readonly\\s+)?([A-Za-z_$][\\w$]*|\"[^\"]*\")\\s*\\??\\s*([(<:])(.*)$",
                                                                   Pattern.DOTALL);
    private static final @NotNull Pattern FIRST_PARAMETER_TYPE = Pattern.compile("^\\(\\s*[\\w$]+\\??\\s*:\\s*([A-Za-z_$][\\w$]*)");
    private static final @NotNull Pattern TYPE_NAME = Pattern.compile("^[A-Za-z_$][\\w$]*$");
    private static final @NotNull String READONLY_MODIFIER = "readonly ";

    /**
     * One member of an interface.
     */
    static final class Member {
        private final @NotNull String name;
        private final boolean method;
        private final @NotNull String type;

        private Member(@NotNull String name, boolean method, @NotNull String type) {
            this.name   = name;
            this.method = method;
            this.type   = type;
        }

        @NotNull String getName() {
            return this.name;
        }

        boolean isMethod() {
            return this.method;
        }

        /**
         * @return the type of a property, or the type of the first parameter of a method; an empty text when it is not a plain type name
         */
        @NotNull String getType() {
            return this.type;
        }
    }

    private static final class Interface {
        private final @NotNull List<String> bases;
        private final @NotNull List<Member> members;
        private boolean indexed;

        private Interface() {
            this.bases   = new ArrayList<>();
            this.members = new ArrayList<>();
            this.indexed = false;
        }
    }


    private static @NotNull List<String> splitTopLevel(@NotNull String text, char separator) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;

        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);

            if (character == '{' || character == '(' || character == '[' || character == '<') {
                depth++;
            } else if (character == '}' || character == ')' || character == ']' || (character == '>' && text.charAt(index - 1) != '=')) {
                depth--;
            } else if (character == separator && depth == 0) {
                parts.add(text.substring(start, index).trim());
                start = index + 1;
            }
        }

        parts.add(text.substring(start).trim());
        parts.removeIf(String::isEmpty);

        return parts;
    }

    private static @NotNull String stripTypeArguments(@NotNull String type) {
        int open = type.indexOf('<');

        return (open < 0 ? type : type.substring(0, open)).trim();
    }


    private final @NotNull Map<String, String> globals;
    private final @NotNull Map<String, Interface> interfaces;

    /**
     * Creates a {@link ScriptDeclarationsCheck} instance from the text of declarations.
     *
     * @param text the declarations
     */
    ScriptDeclarationsCheck(@NotNull String text) {
        this.globals    = new LinkedHashMap<>();
        this.interfaces = new LinkedHashMap<>();

        String code = LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(text).replaceAll("")).replaceAll("");

        for (String statement : this.splitStatements(code)) {
            this.readStatement(statement);
        }
    }

    /**
     * @return the declared globals by name, with the type text of a constant or variable and an empty text for a function
     */
    @NotNull Map<String, String> getGlobals() {
        return this.globals;
    }

    /**
     * Returns the interfaces of a global's type: the type names joined by {@code &}, without type arguments.
     *
     * @param type the type text
     * @return the interface names, empty if the type is not made of declared interfaces
     */
    @NotNull List<String> getInterfaces(@NotNull String type) {
        List<String> names = new ArrayList<>();

        for (String part : splitTopLevel(type, '&')) {
            String name = stripTypeArguments(part);

            if (!this.interfaces.containsKey(name)) {
                return List.of();
            }

            names.add(name);
        }

        return names;
    }

    /**
     * Collects the members of interfaces with those of their bases.
     *
     * @param names the interface names
     * @return the members by name, the first declaration of each name
     */
    @NotNull Map<String, Member> getMembers(@NotNull List<String> names) {
        Map<String, Member> members = new LinkedHashMap<>();

        for (String name : names) {
            this.collectMembers(name, members, new LinkedHashSet<>());
        }

        return members;
    }

    /**
     * @return {@code true} if one of the interfaces or their bases has a string index signature, {@code false} otherwise
     */
    boolean isIndexed(@NotNull List<String> names) {
        for (String name : names) {
            Interface declared = this.interfaces.get(name);

            if (declared != null && (declared.indexed || this.isIndexed(declared.bases))) {
                return true;
            }
        }

        return false;
    }

    /**
     * @return {@code true} if the type is a declared interface, {@code false} otherwise
     */
    boolean isInterface(@NotNull String type) {
        return TYPE_NAME.matcher(type).matches() && this.interfaces.containsKey(type);
    }

    private void collectMembers(@NotNull String name, @NotNull Map<String, Member> members, @NotNull Set<String> visited) {
        Interface declared = this.interfaces.get(name);

        if (declared == null || !visited.add(name)) {
            return;
        }

        for (Member member : declared.members) {
            members.putIfAbsent(member.name, member);
        }

        for (String base : declared.bases) {
            this.collectMembers(base, members, visited);
        }
    }

    /**
     * Splits code into its top-level statements: an interface ends with its body, every other statement with a semicolon.
     */
    private @NotNull List<String> splitStatements(@NotNull String code) {
        List<String> statements = new ArrayList<>();
        StringBuilder statement = new StringBuilder();
        int depth = 0;

        for (int index = 0; index < code.length(); index++) {
            char character = code.charAt(index);

            statement.append(character);

            if (character == '{') {
                depth++;
            } else if (character == '}') {
                depth--;

                if (depth == 0 && statement.toString().trim().startsWith("interface")) {
                    statements.add(statement.toString().trim());
                    statement.setLength(0);
                }
            } else if (character == ';' && depth == 0) {
                statements.add(statement.toString().trim());
                statement.setLength(0);
            }
        }

        return statements;
    }

    private void readStatement(@NotNull String statement) {
        Matcher interfaceMatcher = INTERFACE.matcher(statement);

        if (interfaceMatcher.matches()) {
            Interface declared = this.interfaces.computeIfAbsent(interfaceMatcher.group(1), name -> new Interface());

            if (interfaceMatcher.group(2) != null) {
                for (String base : splitTopLevel(interfaceMatcher.group(2), ',')) {
                    declared.bases.add(stripTypeArguments(base));
                }
            }

            for (String memberText : splitTopLevel(interfaceMatcher.group(3), ';')) {
                this.readMember(declared, memberText);
            }

            return;
        }

        Matcher globalMatcher = GLOBAL.matcher(statement);

        if (globalMatcher.matches()) {
            this.globals.put(globalMatcher.group(1), globalMatcher.group(2).trim());

            return;
        }

        Matcher functionMatcher = GLOBAL_FUNCTION.matcher(statement);

        if (functionMatcher.find()) {
            this.globals.put(functionMatcher.group(1), "");
        }
    }

    private void readMember(@NotNull Interface declared, @NotNull String text) {
        String withoutModifier = text.startsWith(READONLY_MODIFIER) ? text.substring(READONLY_MODIFIER.length()).trim() : text;

        if (withoutModifier.startsWith("[")) {
            declared.indexed |= INDEX_SIGNATURE.matcher(withoutModifier).find();

            return;
        }

        Matcher matcher = MEMBER.matcher(text);

        if (!matcher.matches() || matcher.group(1).equals("new")) {
            return;
        }

        String name = matcher.group(1).startsWith("\"") ? matcher.group(1).substring(1, matcher.group(1).length() - 1) : matcher.group(1);
        boolean method = !matcher.group(2).equals(":");
        String type = method ? this.readFirstParameterType(matcher.group(2) + matcher.group(3)) : matcher.group(3).trim();

        declared.members.add(new Member(name, method, TYPE_NAME.matcher(type).matches() ? type : ""));
    }

    private @NotNull String readFirstParameterType(@NotNull String signature) {
        Matcher matcher = FIRST_PARAMETER_TYPE.matcher(signature);

        return matcher.find() ? matcher.group(1) : "";
    }

    /**
     * @return the declared global type of a name, or {@code null} if it is not declared
     */
    @Nullable String findGlobal(@NotNull String name) {
        return this.globals.get(name);
    }
}
