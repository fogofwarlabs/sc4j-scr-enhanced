package org.fogofwarlabs.sc4j.screnhanced.script;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the script API declarations ({@link ScriptProjectUtils#DECLARATION_FILE_NAMES}): the interfaces with their bases, members and string
 * index signatures, the globals, the types of properties, method results and parameters, and which members are gameplay members.
 * <p>
 * A gameplay member changes the game state: its comment says {@code Shared code only}, or it is a set function ({@code set...}) of an
 * interface whose comment says that its set functions or setters work in shared code only, and its comment says neither that it works while
 * the game is read-only nor that it changes graphics only. Call, construct and other index signatures and type aliases are skipped.
 */
final class ScriptDeclarations {
    /**
     * The interface of functions.
     */
    static final @NotNull String FUNCTION_TYPE = "Function";

    /**
     * The note of a member that works in shared code only.
     */
    private static final @NotNull String SHARED_CODE_NOTE = "Shared code only";
    /**
     * The note of a member that works in shared code only without changing the game state.
     */
    private static final @NotNull String READ_ONLY_NOTE = "works while the game is read-only";
    /**
     * The note of a member that works in shared code only and changes only what is drawn.
     */
    private static final @NotNull String GRAPHICS_NOTE = "Changes graphics only";
    /**
     * The note of an interface whose set functions work in shared code only.
     */
    private static final @NotNull Pattern SHARED_SETTERS_NOTE = Pattern.compile("\\b(set functions?|setters)\\b[^.]*\\bshared code only",
                                                                                Pattern.CASE_INSENSITIVE);
    private static final @NotNull String SETTER_PREFIX = "set";
    private static final @NotNull Pattern INTERFACE = Pattern.compile("^interface\\s+([A-Za-z_$][\\w$]*)\\s*(<.*?>)?\\s*(?:extends\\s+(.+?))?\\s*$",
                                                                      Pattern.DOTALL);
    private static final @NotNull Pattern GLOBAL = Pattern.compile("^declare\\s+(const|var|let|function)\\s+([A-Za-z_$][\\w$]*)\\s*(.*)$",
                                                                   Pattern.DOTALL);
    private static final @NotNull Pattern MEMBER_NAME = Pattern.compile("^(?:readonly\\s+)?([A-Za-z_$][\\w$]*)\\s*\\??\\s*");
    private static final @NotNull Pattern STRING_INDEX_SIGNATURE = Pattern.compile("^(?:readonly\\s+)?\\[\\s*[\\w$]+\\s*:\\s*string\\s*]");
    private static final @NotNull Pattern TYPE_NAME = Pattern.compile("[A-Za-z_$][\\w$]*");
    private static final @NotNull String ARRAY_SUFFIX = "[]";
    private static final @NotNull String ARRAY_TYPE = "Array";
    private static final @NotNull Set<String> ARRAY_TYPES = Set.of(ARRAY_TYPE, "ReadonlyArray");
    private static final @NotNull Set<String> EMPTY_TYPES = Set.of("null", "undefined", "void");
    private static final @NotNull Map<String, String> PRIMITIVE_TYPES = Map.of("string", "String", "number", "Number", "boolean", "Boolean");
    private static final @NotNull String CONSTRUCTOR_TYPE_PREFIX = "new ";

    /**
     * A declared member of an interface, or a declared global.
     */
    static final class Member {
        private final @NotNull String owner;
        private final @NotNull String name;
        private final boolean method;
        private final @NotNull String type;
        private final @NotNull List<String> parameterTypes;
        private final boolean gameplay;

        private Member(@NotNull String owner, @NotNull String name, boolean method, @NotNull String type, @NotNull List<String> parameterTypes,
                       boolean gameplay) {
            this.owner          = owner;
            this.name           = name;
            this.method         = method;
            this.type           = type;
            this.parameterTypes = parameterTypes;
            this.gameplay       = gameplay;
        }

        /**
         * @return the interface that declares the member, or an empty text for a global
         */
        @NotNull String getOwner() {
            return this.owner;
        }

        /**
         * @return {@code true} if the member is a method or a global function, {@code false} for a property or a global variable
         */
        boolean isMethod() {
            return this.method;
        }

        /**
         * @return the type text of a property or variable, or the result type text of a method
         */
        @NotNull String getType() {
            return this.type;
        }

        /**
         * @return the type texts of a method's parameters, empty for a property
         */
        @NotNull List<String> getParameterTypes() {
            return this.parameterTypes;
        }

        /**
         * @return {@code true} if the member changes the game state, {@code false} otherwise
         */
        boolean isGameplay() {
            return this.gameplay;
        }

        /**
         * @return the member as {@code Interface.member}, or the global's name
         */
        @Override
        public @NotNull String toString() {
            return this.owner.isEmpty() ? this.name : this.owner + '.' + this.name;
        }
    }

    /**
     * A type of a value: the interfaces it has, and for an array the type of its elements when known.
     */
    static final class ValueType {
        private final @NotNull List<String> interfaces;
        private final @Nullable ValueType elementType;

        /**
         * Creates a {@link ValueType} instance.
         *
         * @param interfaces  the interfaces of the value, copied
         * @param elementType the type of the elements of an array, or {@code null} if it is no array or the type is not known
         */
        ValueType(@NotNull List<String> interfaces, @Nullable ValueType elementType) {
            this.interfaces  = List.copyOf(interfaces);
            this.elementType = elementType;
        }

        /**
         * @return the interfaces of the value
         */
        @NotNull List<String> getInterfaces() {
            return this.interfaces;
        }

        /**
         * @return the type of the elements of an array, or {@code null} if it is no array or the type is not known
         */
        @Nullable ValueType getElementType() {
            return this.elementType;
        }

        /**
         * Indicates whether some other object is "equal to" this one.
         *
         * @param other the reference object with which to compare
         * @return {@code true} if this object is the same as the other argument, {@code false} otherwise
         * @implSpec Two types are equal when their interfaces and their element types are equal.
         */
        @Override
        public boolean equals(@Nullable Object other) {
            return other instanceof ValueType type && this.interfaces.equals(type.interfaces) && Objects.equals(this.elementType, type.elementType);
        }

        /**
         * Returns a hash code value for the object.
         *
         * @return a hash code value for this object
         * @implSpec Combines the hash codes of the interfaces and the element type, consistent with {@link #equals(Object)}.
         */
        @Override
        public int hashCode() {
            return Objects.hash(this.interfaces, this.elementType);
        }
    }

    /**
     * A declared interface: its bases, its members by name, each name with its overloads, and whether it has a string index signature.
     */
    private static final class Declaration {
        private final @NotNull List<String> bases;
        private final @NotNull Map<String, List<Member>> members;
        private boolean indexed;

        private Declaration() {
            this.bases   = new ArrayList<>();
            this.members = new LinkedHashMap<>();
            this.indexed = false;
        }
    }

    /**
     * Reads the declaration files of the script API.
     *
     * @return the read {@link ScriptDeclarations} instance
     * @throws IOException if a declaration file is missing or cannot be read
     */
    static @NotNull ScriptDeclarations read() throws IOException {
        List<String> texts = new ArrayList<>();

        for (String fileName : ScriptProjectUtils.DECLARATION_FILE_NAMES) {
            texts.add(new String(ScriptProjectUtils.readDeclarations(fileName), StandardCharsets.UTF_8));
        }

        return new ScriptDeclarations(texts);
    }

    /**
     * Finds the end of a comment or a string literal at an index.
     *
     * @return the index after the comment or the literal, or {@code -1} if none starts at the index
     */
    private static int skipCommentOrString(@NotNull String text, int index) {
        char character = text.charAt(index);

        if (text.startsWith("/*", index)) {
            int end = text.indexOf("*/", index + 2);

            return end < 0 ? text.length() : end + 2;
        }

        if (text.startsWith("//", index)) {
            int end = text.indexOf('\n', index);

            return end < 0 ? text.length() : end + 1;
        }

        if (character == '"' || character == '\'' || character == '`') {
            int end = index + 1;

            while (end < text.length() && text.charAt(end) != character) {
                end += text.charAt(end) == '\\' ? 2 : 1;
            }

            return Math.min(end + 1, text.length());
        }

        return -1;
    }

    /**
     * Gives the nesting change of a character outside comments and strings: {@code +1} for an opening bracket, {@code -1} for a closing one,
     * {@code 0} otherwise; the {@code >} of {@code =>} is no bracket.
     */
    private static int getNesting(@NotNull String text, int index) {
        return switch (text.charAt(index)) {
            case '{', '(', '[', '<' -> 1;
            case '}', ')', ']' -> -1;
            case '>' -> index > 0 && text.charAt(index - 1) == '=' ? 0 : -1;
            default -> 0;
        };
    }

    /**
     * Splits a text at a separator outside brackets, comments and strings.
     *
     * @return the trimmed nonempty parts
     */
    private static @NotNull List<String> splitTopLevel(@NotNull String text, char separator) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        int index = 0;

        while (index < text.length()) {
            int skipped = skipCommentOrString(text, index);

            if (skipped >= 0) {
                index = skipped;
                continue;
            }

            if (text.charAt(index) == separator && depth == 0) {
                parts.add(text.substring(start, index).trim());
                start = index + 1;
            } else {
                depth += getNesting(text, index);
            }

            index++;
        }

        parts.add(text.substring(start).trim());
        parts.removeIf(String::isEmpty);

        return parts;
    }

    /**
     * Finds the bracket that closes the bracket at an index.
     *
     * @return the index of the closing bracket, or the text length if it is missing
     */
    private static int findClosing(@NotNull String text, int open) {
        int depth = 0;
        int index = open;

        while (index < text.length()) {
            int skipped = skipCommentOrString(text, index);

            if (skipped >= 0) {
                index = skipped;
                continue;
            }

            depth += getNesting(text, index);

            if (depth == 0) {
                return index;
            }

            index++;
        }

        return text.length();
    }

    /**
     * Reads the text of a documentation comment without its stars, its lines joined by single spaces.
     */
    private static @NotNull String readDocumentation(@NotNull String comment) {
        StringBuilder text = new StringBuilder();

        for (String line : comment.split("\n")) {
            String trimmed = line.strip();

            while (trimmed.startsWith("*") || trimmed.startsWith("/")) {
                trimmed = trimmed.substring(1).strip();
            }

            if (!trimmed.isEmpty()) {
                text.append(text.isEmpty() ? "" : " ").append(trimmed);
            }
        }

        return text.toString();
    }

    private static @NotNull String stripTypeArguments(@NotNull String type) {
        int open = type.indexOf('<');

        return (open < 0 ? type : type.substring(0, open)).trim();
    }

    private static boolean isGameplay(@NotNull String name, @NotNull String documentation, boolean sharedSetters) {
        boolean shared = documentation.contains(SHARED_CODE_NOTE) || sharedSetters && name.startsWith(SETTER_PREFIX);

        return shared && !documentation.contains(READ_ONLY_NOTE) && !documentation.contains(GRAPHICS_NOTE);
    }


    private final @NotNull Map<String, Declaration> declarations;
    private final @NotNull Map<String, Member> globals;
    private final @NotNull Map<String, List<Member>> gameplayMembers;
    private final @NotNull Set<String> otherMemberNames;

    /**
     * Creates a {@link ScriptDeclarations} instance from the texts of declaration files.
     *
     * @param texts the texts of the declaration files
     */
    ScriptDeclarations(@NotNull List<String> texts) {
        this.declarations     = new LinkedHashMap<>();
        this.globals          = new LinkedHashMap<>();
        this.gameplayMembers  = new TreeMap<>();
        this.otherMemberNames = new HashSet<>();

        for (String text : texts) {
            this.readStatements(text);
        }

        for (Declaration declaration : this.declarations.values()) {
            for (List<Member> overloads : declaration.members.values()) {
                for (Member member : overloads) {
                    if (member.gameplay) {
                        this.gameplayMembers.computeIfAbsent(member.name, name -> new ArrayList<>()).add(member);
                    } else {
                        this.otherMemberNames.add(member.name);
                    }
                }
            }
        }
    }

    /**
     * @return the gameplay members by name, each name with every interface member of that name that changes the game state
     */
    @NotNull Map<String, List<Member>> getGameplayMembers() {
        return Collections.unmodifiableMap(this.gameplayMembers);
    }

    /**
     * @return {@code true} if some interface also declares a member of that name that does not change the game state, {@code false} otherwise
     */
    boolean isDeclaredOtherwise(@NotNull String name) {
        return this.otherMemberNames.contains(name);
    }

    /**
     * @return {@code true} if an interface declares a member of that name, {@code false} otherwise
     */
    boolean isDeclaredName(@NotNull String name) {
        return this.gameplayMembers.containsKey(name) || this.otherMemberNames.contains(name);
    }

    /**
     * @return {@code true} if an interface of that name is declared, {@code false} otherwise
     */
    boolean isInterface(@NotNull String name) {
        return this.declarations.containsKey(name);
    }

    /**
     * @return the declared globals by name, in declaration order
     */
    @NotNull Map<String, Member> getGlobals() {
        return Collections.unmodifiableMap(this.globals);
    }

    /**
     * Finds a global.
     *
     * @param name the name of the global
     * @return the declared global, or {@code null} if none has the name
     */
    @Nullable Member findGlobal(@NotNull String name) {
        return this.globals.get(name);
    }

    /**
     * Collects the members of interfaces with those of their bases.
     *
     * @param interfaces the interface names
     * @return the members by name, the first declaration of each name, an interface's own members before those of its bases
     */
    @NotNull Map<String, Member> collectMembers(@NotNull List<String> interfaces) {
        Map<String, Member> members = new LinkedHashMap<>();

        for (String interfaceName : interfaces) {
            this.collectMembers(interfaceName, members, new HashSet<>());
        }

        return members;
    }

    private void collectMembers(@NotNull String interfaceName, @NotNull Map<String, Member> members, @NotNull Set<String> visited) {
        Declaration declaration = this.declarations.get(interfaceName);

        if (declaration == null || !visited.add(interfaceName)) {
            return;
        }

        for (List<Member> overloads : declaration.members.values()) {
            members.putIfAbsent(overloads.getFirst().name, overloads.getFirst());
        }

        for (String base : declaration.bases) {
            this.collectMembers(base, members, visited);
        }
    }

    /**
     * Checks whether interfaces or their bases have a string index signature.
     *
     * @param interfaces the interface names
     * @return {@code true} if one of them has a string index signature, {@code false} otherwise
     */
    boolean isIndexed(@NotNull List<String> interfaces) {
        for (String interfaceName : interfaces) {
            Declaration declaration = this.declarations.get(interfaceName);

            if (declaration != null && (declaration.indexed || this.isIndexed(declaration.bases))) {
                return true;
            }
        }

        return false;
    }

    /**
     * Finds the declarations of a member in a type's interfaces and their bases.
     *
     * @param type the type
     * @param name the member name
     * @return the overloads of the member in the first interface that declares it; empty if none declares it
     */
    @NotNull List<Member> findMembers(@NotNull ValueType type, @NotNull String name) {
        for (String interfaceName : type.getInterfaces()) {
            List<Member> members = this.findMembers(interfaceName, name, new HashSet<>());

            if (!members.isEmpty()) {
                return members;
            }
        }

        return List.of();
    }

    private @NotNull List<Member> findMembers(@NotNull String interfaceName, @NotNull String name, @NotNull Set<String> visited) {
        Declaration declaration = this.declarations.get(interfaceName);

        if (declaration == null || !visited.add(interfaceName)) {
            return List.of();
        }

        List<Member> members = declaration.members.get(name);

        if (members != null) {
            return members;
        }

        for (String base : declaration.bases) {
            List<Member> baseMembers = this.findMembers(base, name, visited);

            if (!baseMembers.isEmpty()) {
                return baseMembers;
            }
        }

        return List.of();
    }

    /**
     * Checks whether a type has a gameplay member, in its interfaces or their bases.
     *
     * @param type the type
     * @return {@code true} if a member of the type changes the game state, {@code false} otherwise
     */
    boolean hasGameplayMembers(@NotNull ValueType type) {
        for (String interfaceName : type.getInterfaces()) {
            if (this.hasGameplayMembers(interfaceName, new HashSet<>())) {
                return true;
            }
        }

        return false;
    }

    private boolean hasGameplayMembers(@NotNull String interfaceName, @NotNull Set<String> visited) {
        Declaration declaration = this.declarations.get(interfaceName);

        if (declaration == null || !visited.add(interfaceName)) {
            return false;
        }

        for (List<Member> overloads : declaration.members.values()) {
            for (Member member : overloads) {
                if (member.gameplay) {
                    return true;
                }
            }
        }

        for (String base : declaration.bases) {
            if (this.hasGameplayMembers(base, visited)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Resolves a type text: a declared interface, an intersection of them, an array, a primitive as its wrapper interface or a function type,
     * after {@code null}, {@code undefined} and {@code void} leave a union.
     *
     * @param text the type text
     * @return the type, or {@code null} if the text is no such type, such as a union of two types or a type parameter
     */
    @Nullable ValueType resolveType(@NotNull String text) {
        List<String> alternatives = splitTopLevel(text, '|');

        alternatives.removeIf(EMPTY_TYPES::contains);

        if (alternatives.size() != 1) {
            return null;
        }

        String type = alternatives.getFirst();

        while (type.startsWith("(") && findClosing(type, 0) == type.length() - 1) {
            type = type.substring(1, type.length() - 1).trim();
        }

        if (type.startsWith("(") || type.startsWith(CONSTRUCTOR_TYPE_PREFIX)) {
            return new ValueType(List.of(FUNCTION_TYPE), null);
        }

        if (type.endsWith(ARRAY_SUFFIX)) {
            return new ValueType(List.of(ARRAY_TYPE), this.resolveType(type.substring(0, type.length() - ARRAY_SUFFIX.length())));
        }

        List<String> interfaces = new ArrayList<>();
        ValueType elementType = null;

        for (String part : splitTopLevel(type, '&')) {
            String name = stripTypeArguments(part);
            String interfaceName = PRIMITIVE_TYPES.getOrDefault(name, name);

            if (!TYPE_NAME.matcher(interfaceName).matches() || !this.declarations.containsKey(interfaceName)) {
                return null;
            }

            if (ARRAY_TYPES.contains(interfaceName) && part.indexOf('<') > 0) {
                elementType = this.resolveType(part.substring(part.indexOf('<') + 1, part.lastIndexOf('>')));
            }

            interfaces.add(interfaceName);
        }

        return new ValueType(interfaces, elementType);
    }

    /**
     * Reads the top-level statements of a declaration file: interfaces and globals, each with the documentation comment before it.
     */
    private void readStatements(@NotNull String text) {
        String documentation = "";
        int index = 0;

        while (index < text.length()) {
            if (Character.isWhitespace(text.charAt(index))) {
                index++;
                continue;
            }

            if (text.startsWith("/**", index)) {
                int end = skipCommentOrString(text, index);

                documentation = readDocumentation(text.substring(index, end));
                index         = end;
                continue;
            }

            int skipped = skipCommentOrString(text, index);

            if (skipped >= 0) {
                index = skipped;
                continue;
            }

            index         = this.readStatement(text, index, documentation);
            documentation = "";
        }
    }

    /**
     * Reads one top-level statement: an interface up to its closing brace, any other statement up to its semicolon.
     *
     * @return the index after the statement
     */
    private int readStatement(@NotNull String text, int start, @NotNull String documentation) {
        if (text.startsWith("interface", start)) {
            int open = text.indexOf('{', start);
            int close = findClosing(text, open);
            Matcher matcher = INTERFACE.matcher(text.substring(start, open).trim());

            if (matcher.matches()) {
                this.readInterface(matcher.group(1), matcher.group(3), text.substring(open + 1, close), documentation);
            }

            return close + 1;
        }

        int depth = 0;
        int index = start;

        while (index < text.length()) {
            int skipped = skipCommentOrString(text, index);

            if (skipped >= 0) {
                index = skipped;
                continue;
            }

            if (text.charAt(index) == ';' && depth == 0) {
                break;
            }

            depth += getNesting(text, index);
            index++;
        }

        Matcher matcher = GLOBAL.matcher(text.substring(start, Math.min(index, text.length())).trim());

        if (matcher.matches()) {
            Member member = this.readMember("", matcher.group(2) + matcher.group(3), documentation, false);

            if (member != null) {
                this.globals.putIfAbsent(member.name, member);
            }
        }

        return index + 1;
    }

    /**
     * Reads the bases and the members of an interface, each member with the documentation comment before it.
     */
    private void readInterface(@NotNull String name, @Nullable String bases, @NotNull String body, @NotNull String documentation) {
        Declaration declaration = this.declarations.computeIfAbsent(name, key -> new Declaration());
        boolean sharedSetters = SHARED_SETTERS_NOTE.matcher(documentation).find();

        if (bases != null) {
            for (String base : splitTopLevel(bases, ',')) {
                declaration.bases.add(stripTypeArguments(base));
            }
        }

        String memberDocumentation = "";
        int depth = 0;
        int start = -1;
        int index = 0;

        while (index < body.length()) {
            char character = body.charAt(index);

            if (start < 0 && Character.isWhitespace(character)) {
                index++;
                continue;
            }

            if (start < 0 && body.startsWith("/**", index)) {
                int commentEnd = skipCommentOrString(body, index);

                memberDocumentation = readDocumentation(body.substring(index, commentEnd));
                index               = commentEnd;
                continue;
            }

            if (character == ';' && depth == 0) {
                this.addMember(declaration, name, body.substring(start < 0 ? index : start, index), memberDocumentation, sharedSetters);
                memberDocumentation = "";
                start               = -1;
                index++;
                continue;
            }

            int skipped = skipCommentOrString(body, index);

            if (skipped >= 0) {
                if (start < 0 && character != '/') {
                    start = index;
                }

                index = skipped;
                continue;
            }

            if (start < 0) {
                start = index;
            }

            depth += getNesting(body, index);
            index++;
        }

        if (start >= 0) {
            this.addMember(declaration, name, body.substring(start), memberDocumentation, sharedSetters);
        }
    }

    private void addMember(@NotNull Declaration declaration, @NotNull String owner, @NotNull String text, @NotNull String documentation,
                           boolean sharedSetters) {
        if (STRING_INDEX_SIGNATURE.matcher(text.trim()).find()) {
            declaration.indexed = true;

            return;
        }

        Member member = this.readMember(owner, text.trim(), documentation, sharedSetters);

        if (member != null) {
            declaration.members.computeIfAbsent(member.name, key -> new ArrayList<>()).add(member);
        }
    }

    /**
     * Reads a member of an interface or a global: {@code name: Type}, {@code name(parameters): Type} or {@code name<T>(parameters): Type}.
     *
     * @return the member, or {@code null} for a call, construct or index signature
     */
    private @Nullable Member readMember(@NotNull String owner, @NotNull String text, @NotNull String documentation, boolean sharedSetters) {
        Matcher matcher = MEMBER_NAME.matcher(text);

        if (!matcher.lookingAt() || matcher.group(1).equals("new")) {
            return null;
        }

        String name = matcher.group(1);
        String rest = text.substring(matcher.end());
        boolean gameplay = isGameplay(name, documentation, sharedSetters);

        if (rest.startsWith(":")) {
            return new Member(owner, name, false, rest.substring(1).trim(), List.of(), gameplay);
        }

        if (rest.startsWith("<")) {
            rest = rest.substring(findClosing(rest, 0) + 1).trim();
        }

        if (!rest.startsWith("(")) {
            return null;
        }

        int close = findClosing(rest, 0);
        List<String> parameterTypes = new ArrayList<>();

        for (String parameter : splitTopLevel(rest.substring(1, Math.min(close, rest.length())), ',')) {
            int colon = parameter.indexOf(':');

            parameterTypes.add(colon < 0 ? "" : parameter.substring(colon + 1).trim());
        }

        String result = close + 1 < rest.length() ? rest.substring(close + 1).trim() : "";

        return new Member(owner, name, true, result.startsWith(":") ? result.substring(1).trim() : "", parameterTypes, gameplay);
    }
}
