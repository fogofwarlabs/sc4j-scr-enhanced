package org.fogofwarlabs.sc4j.screnhanced.script;

import org.fogofwarlabs.sc4j.screnhanced.script.ScriptDeclarations.Member;
import org.fogofwarlabs.sc4j.screnhanced.script.ScriptDeclarations.ValueType;
import org.fogofwarlabs.sc4j.screnhanced.script.runtime.ScriptHook;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.mozilla.javascript.*;
import org.mozilla.javascript.ast.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Scans the scripts of a package for the use of gameplay members, the members of the script API that change the game state
 * ({@link ScriptDeclarations}), to pick the compatibility mark of the map: {@link ScriptCompatibility#ENHANCED_ONLY} if the scripts use one or
 * cannot be judged, {@link ScriptCompatibility#COMPATIBLE} otherwise.
 * <p>
 * Each script is parsed by Rhino. A member access {@code value.name} or {@code value['name']}, called or not, and a destructured property is a
 * use of a gameplay member when the scan knows the type of the value and that type's member changes the game state, or, without a known type,
 * when the name is that of a gameplay member only. The type of a value follows the declarations from the globals, the parameters of hooks,
 * {@code new} of a declared interface and literals, through members, method results and array elements, and through variables and properties
 * of the scripts' own objects whose every assignment has the same type. A name that the scripts give their own objects (an object literal
 * property or an assignment {@code value.name = ...}) counts only on a value of a known type. A decision hook ({@code onBefore...}) is a use too.
 * <p>
 * The scan cannot judge, and warns of, a gameplay name that a declaration also has without changing the game (such as {@code set} of
 * {@code Map}) on a value of an unknown type, a computed member {@code value[key]} of a value of a type with gameplay members or a computed
 * member called on a value of an unknown type, {@code with}, and a script that does not parse.
 */
public final class ScriptCompatibilityScan {
    private static final int FIRST_LINE = 1;
    private static final int MAXIMUM_SOURCE_LENGTH = 40;
    private static final @NotNull String SOURCE_ELLIPSIS = "...";
    private static final @NotNull String HOOKS_INTERFACE = "Hooks";
    private static final @NotNull String OBJECT_TYPE = "Object";
    private static final @NotNull String ARRAY_TYPE = "Array";
    private static final @NotNull String STRING_TYPE = "String";
    private static final @NotNull String NUMBER_TYPE = "Number";
    private static final @NotNull String BOOLEAN_TYPE = "Boolean";
    private static final @NotNull String REGULAR_EXPRESSION_TYPE = "RegExp";
    /**
     * The most declarations of one gameplay name that a reason lists.
     */
    private static final int MAXIMUM_LISTED_MEMBERS = 3;
    /**
     * Stands for an assignment whose value the scan does not follow.
     */
    private static final @NotNull AstNode UNKNOWN_VALUE = new EmptyExpression();

    /**
     * One reason of the scan: a script, a line and what it uses or what cannot be judged.
     */
    public static final class Reason {
        private final @NotNull String file;
        private final int line;
        private final @NotNull String text;

        private Reason(@NotNull String file, int line, @NotNull String text) {
            this.file = file;
            this.line = line;
            this.text = text;
        }

        /**
         * @return the script name relative to the script folder with {@code /} separators
         */
        public @NotNull String getFile() {
            return this.file;
        }

        /**
         * @return the line from {@code 1}
         */
        public int getLine() {
            return this.line;
        }

        /**
         * @return what the line uses, such as {@code Player.setMinerals}, or what cannot be judged
         */
        public @NotNull String getText() {
            return this.text;
        }

        /**
         * @return the reason as {@code file:line: text}
         */
        @Override
        public @NotNull String toString() {
            return this.file + ':' + this.line + ": " + this.text;
        }
    }

    /**
     * A variable: its defining scope, compared by identity, and its name.
     */
    private static final class Variable {
        private final @NotNull Scope scope;
        private final @NotNull String name;

        private Variable(@NotNull Scope scope, @NotNull String name) {
            this.scope = scope;
            this.name  = name;
        }

        /**
         * Indicates whether some other object is "equal to" this one.
         *
         * @param other the reference object with which to compare
         * @return {@code true} if this object is the same as the other argument, {@code false} otherwise
         * @implSpec Two variables are equal when they have the same scope object and the same name.
         */
        @Override
        public boolean equals(@Nullable Object other) {
            return other instanceof Variable variable && this.scope == variable.scope && this.name.equals(variable.name);
        }

        /**
         * Returns a hash code value for the object.
         *
         * @return a hash code value for this object
         * @implSpec Combines the identity hash code of the scope and the hash code of the name, consistent with {@link #equals(Object)}.
         */
        @Override
        public int hashCode() {
            return System.identityHashCode(this.scope) * 31 + this.name.hashCode();
        }
    }

    /**
     * Ignores the warnings of the parser and throws at its first error.
     */
    private static final class ThrowingErrors implements ErrorReporter {
        @Override
        public void warning(@NotNull String message, @Nullable String sourceName, int line, @Nullable String lineSource, int lineOffset) {
        }

        @Override
        public void error(@NotNull String message, @Nullable String sourceName, int line, @Nullable String lineSource, int lineOffset) {
            throw new EvaluatorException(message, sourceName, line, lineSource, lineOffset);
        }

        @Override
        public @NotNull EvaluatorException runtimeError(@NotNull String message, @Nullable String sourceName, int line,
                                                        @Nullable String lineSource, int lineOffset) {
            return new EvaluatorException(message, sourceName, line, lineSource, lineOffset);
        }
    }

    /**
     * Scans the scripts of a package with the declarations of the script API.
     *
     * @param scriptPackage the script package
     * @return the created {@link ScriptCompatibilityScan} instance
     * @throws IOException if the declarations cannot be read
     */
    public static @NotNull ScriptCompatibilityScan scan(@NotNull ScriptPackage scriptPackage) throws IOException {
        return new ScriptCompatibilityScan(ScriptDeclarations.read(), scriptPackage.getScripts());
    }

    /**
     * Finds the scope that declares a name: the nearest enclosing scope of the syntax tree whose symbols hold it.
     *
     * @return the scope, or {@code null} for a global
     */
    private static @Nullable Scope findDefiningScope(@NotNull Name name) {
        for (Scope scope = name.getEnclosingScope(); scope != null; scope = scope.getEnclosingScope()) {
            if (scope.getSymbol(name.getIdentifier()) != null) {
                return scope;
            }
        }

        return null;
    }

    private static int findLine(@NotNull AstNode node) {
        for (AstNode current = node; current != null; current = current.getParent()) {
            if (current.getLineno() >= FIRST_LINE) {
                return current.getLineno();
            }
        }

        return FIRST_LINE;
    }

    private static @NotNull String shorten(@NotNull AstNode node) {
        String source = node.toSource().replaceAll("\\s+", " ").strip();

        return source.length() <= MAXIMUM_SOURCE_LENGTH ? source : source.substring(0, MAXIMUM_SOURCE_LENGTH) + SOURCE_ELLIPSIS;
    }

    /**
     * Finds the name of an object literal property that is an identifier or a string.
     *
     * @return the name, or {@code null} if the property has a computed or numeric name
     */
    private static @Nullable String findPropertyName(@NotNull ObjectProperty property) {
        return switch (property.getLeft()) {
            case Name name -> name.getIdentifier();
            case StringLiteral literal -> literal.getValue();
            default -> null;
        };
    }

    /**
     * @return {@code true} if the node is the target of a plain assignment or of a compound one, {@code false} otherwise
     */
    private static boolean isAssigned(@NotNull AstNode node) {
        return node.getParent() instanceof Assignment assignment && assignment.getLeft() == node;
    }

    private static @NotNull List<Reason> sortReasons(@NotNull Collection<Reason> reasons) {
        List<Reason> sorted = new ArrayList<>(reasons);

        sorted.sort(Comparator.comparing(Reason::getFile).thenComparingInt(Reason::getLine));

        return List.copyOf(sorted);
    }


    private final @NotNull ScriptDeclarations declarations;

    private final @NotNull Map<Variable, List<AstNode>> variableWrites;
    private final @NotNull Map<String, List<AstNode>> propertyWrites;
    private final @NotNull Map<FunctionNode, List<String>> hookParameterTypes;
    private final @NotNull Map<Variable, ValueType> variableTypes;
    private final @NotNull Map<String, ValueType> propertyTypes;
    private final @NotNull Set<Object> inferring;

    private final @NotNull Map<String, Reason> uses;
    private final @NotNull Map<String, Reason> warnings;

    /**
     * Creates a {@link ScriptCompatibilityScan} instance and scans the scripts.
     *
     * @param declarations the declarations of the script API
     * @param scripts      the UTF-8 scripts by name
     */
    ScriptCompatibilityScan(@NotNull ScriptDeclarations declarations, @NotNull Map<String, byte[]> scripts) {
        this.declarations = declarations;

        this.variableWrites     = new HashMap<>();
        this.propertyWrites     = new HashMap<>();
        this.hookParameterTypes = new HashMap<>();
        this.variableTypes      = new HashMap<>();
        this.propertyTypes      = new HashMap<>();
        this.inferring          = new HashSet<>();

        this.uses     = new LinkedHashMap<>();
        this.warnings = new LinkedHashMap<>();

        Map<String, AstRoot> roots = new TreeMap<>();

        try (Context context = new ContextFactory().enterContext()) {
            context.setInterpretedMode(true);
            context.setLanguageVersion(Context.VERSION_ECMASCRIPT);

            for (Map.Entry<String, byte[]> script : new TreeMap<>(scripts).entrySet()) {
                CompilerEnvirons environment = new CompilerEnvirons();
                String source = new String(script.getValue(), StandardCharsets.UTF_8);

                environment.initFromContext(context);

                try {
                    roots.put(script.getKey(), new Parser(environment, new ThrowingErrors()).parse(source, script.getKey(), FIRST_LINE));
                } catch (EvaluatorException exception) {
                    this.addWarning(script.getKey(), Math.max(exception.lineNumber(), FIRST_LINE),
                                    "the script cannot be read: " + exception.details());
                }
            }
        }

        for (Map.Entry<String, AstRoot> root : roots.entrySet()) {
            this.collectWrites(root.getKey(), root.getValue());
        }

        for (Map.Entry<String, AstRoot> root : roots.entrySet()) {
            this.checkUses(root.getKey(), root.getValue());
        }
    }

    /**
     * @return {@link ScriptCompatibility#ENHANCED_ONLY} if the scripts use a gameplay member or cannot be judged, {@link ScriptCompatibility#COMPATIBLE} otherwise
     */
    public @NotNull ScriptCompatibility getCompatibility() {
        return this.uses.isEmpty() && this.warnings.isEmpty() ? ScriptCompatibility.COMPATIBLE : ScriptCompatibility.ENHANCED_ONLY;
    }

    /**
     * @return the uses of gameplay members, the first line of each member, by script and line
     */
    public @NotNull List<@NotNull Reason> getUses() {
        return sortReasons(this.uses.values());
    }

    /**
     * @return what the scan cannot judge, by script and line
     */
    public @NotNull List<@NotNull Reason> getWarnings() {
        return sortReasons(this.warnings.values());
    }

    private void addUse(@NotNull String scriptName, @NotNull AstNode node, @NotNull String text) {
        this.uses.putIfAbsent(text, new Reason(scriptName, findLine(node), text));
    }

    private void addWarning(@NotNull String scriptName, int line, @NotNull String text) {
        this.warnings.putIfAbsent(scriptName + ':' + text, new Reason(scriptName, line, text));
    }

    /**
     * Collects the assignments of variables and of properties of the scripts' own objects, the hooks with their parameter types, and the
     * decision hooks as uses.
     */
    private void collectWrites(@NotNull String scriptName, @NotNull AstRoot root) {
        root.visitAll(node -> {
            switch (node) {
                case VariableInitializer initializer when initializer.getTarget() instanceof Name name -> {
                    if (initializer.getInitializer() != null) {
                        this.addVariableWrite(name, initializer.getInitializer());
                    }

                    this.collectHook(scriptName, name.getIdentifier(), initializer.getInitializer());
                }
                case VariableInitializer initializer -> this.addUnknownWrites(initializer.getTarget());
                case Assignment assignment -> this.collectAssignment(scriptName, assignment);
                case UpdateExpression update -> this.addUnknownWrites(update.getOperand());
                case ForInLoop loop -> this.addUnknownWrites(loop.getIterator());
                case ObjectProperty property when !(property.getParent() instanceof ObjectLiteral literal && literal.isDestructuring()) -> {
                    String name = findPropertyName(property);

                    if (name != null) {
                        this.propertyWrites.computeIfAbsent(name, key -> new ArrayList<>()).add(property.getRight());
                        this.collectHook(scriptName, name, property.getRight());
                    }
                }
                case FunctionNode function when function.getFunctionName() != null && function.getFunctionType() == FunctionNode.FUNCTION_STATEMENT &&
                                                function.getParent() instanceof AstRoot -> {
                    this.collectHook(scriptName, function.getFunctionName().getIdentifier(), function);
                }
                default -> {
                }
            }

            return true;
        });
    }

    private void collectAssignment(@NotNull String scriptName, @NotNull Assignment assignment) {
        AstNode left = assignment.getLeft();
        AstNode value = assignment.getType() == Token.ASSIGN ? assignment.getRight() : UNKNOWN_VALUE;

        switch (left) {
            case Name name -> {
                this.addVariableWrite(name, value);
                this.collectHook(scriptName, name.getIdentifier(), assignment.getRight());
            }
            case PropertyGet get -> {
                this.propertyWrites.computeIfAbsent(get.getProperty().getIdentifier(), key -> new ArrayList<>()).add(value);
                this.collectHook(scriptName, get.getProperty().getIdentifier(), assignment.getRight());
            }
            case ElementGet get when get.getElement() instanceof StringLiteral literal -> {
                this.propertyWrites.computeIfAbsent(literal.getValue(), key -> new ArrayList<>()).add(value);
            }
            default -> this.addUnknownWrites(left);
        }
    }

    private void addVariableWrite(@NotNull Name name, @NotNull AstNode value) {
        Scope scope = findDefiningScope(name);

        if (scope != null) {
            this.variableWrites.computeIfAbsent(new Variable(scope, name.getIdentifier()), key -> new ArrayList<>()).add(value);
        }
    }

    /**
     * Marks the variables a node writes without a followed value: a name, or the names of a destructuring pattern or declaration.
     */
    private void addUnknownWrites(@Nullable AstNode target) {
        if (target == null) {
            return;
        }

        target.visit(node -> {
            if (node instanceof Name name) {
                this.addVariableWrite(name, UNKNOWN_VALUE);
            }

            return !(node instanceof FunctionNode);
        });
    }

    /**
     * Records a function bound to a hook name: its parameters get the hook's parameter types, and a decision hook is a use.
     */
    private void collectHook(@NotNull String scriptName, @NotNull String name, @Nullable AstNode value) {
        ScriptHook hook = ScriptHook.findByFunctionName(name);

        if (hook == null || !(value instanceof FunctionNode function)) {
            return;
        }

        List<Member> members = this.declarations.findMembers(new ValueType(List.of(HOOKS_INTERFACE), null), name);

        if (!members.isEmpty()) {
            this.hookParameterTypes.put(function, members.getFirst().getParameterTypes());
        }

        if (hook.isDecision()) {
            this.addUse(scriptName, function, name + ", a decision hook");
        }
    }

    /**
     * Checks the member accesses, the computed members and the {@code with} statements of a script.
     */
    private void checkUses(@NotNull String scriptName, @NotNull AstRoot root) {
        root.visitAll(node -> {
            switch (node) {
                case PropertyGet get when !isAssigned(get) -> {
                    this.checkMember(scriptName, get.getTarget(), get.getProperty().getIdentifier(), get);
                }
                case ElementGet get when get.getElement() instanceof StringLiteral literal && !isAssigned(get) -> {
                    this.checkMember(scriptName, get.getTarget(), literal.getValue(), get);
                }
                case ElementGet get when !(get.getElement() instanceof StringLiteral) && !(get.getElement() instanceof NumberLiteral) -> {
                    this.checkComputedMember(scriptName, get);
                }
                case ObjectLiteral literal when literal.isDestructuring() -> {
                    this.checkDestructuring(scriptName, literal);
                }
                case WithStatement statement -> {
                    this.addWarning(scriptName, findLine(statement), "with cannot be checked");
                }
                default -> {
                }
            }

            return true;
        });
    }

    private void checkMember(@NotNull String scriptName, @Nullable AstNode receiver, @NotNull String name, @NotNull AstNode node) {
        List<Member> gameplayMembers = this.declarations.getGameplayMembers().get(name);

        if (gameplayMembers == null) {
            return;
        }

        ValueType type = receiver == null ? null : this.inferType(receiver);

        if (type != null) {
            List<Member> members = this.declarations.findMembers(type, name);

            for (Member member : members) {
                if (member.isGameplay()) {
                    this.addUse(scriptName, node, member.toString());

                    return;
                }
            }

            return;
        }

        if (this.declarations.isDeclaredOtherwise(name)) {
            String target = receiver == null ? name : shorten(receiver) + '.' + name;

            this.addWarning(scriptName, findLine(node), target + " cannot be checked: the type of the value is not known");
        } else if (!this.propertyWrites.containsKey(name)) {
            List<String> listed = new ArrayList<>();

            for (Member member : gameplayMembers.subList(0, Math.min(gameplayMembers.size(), MAXIMUM_LISTED_MEMBERS))) {
                listed.add(member.toString());
            }

            this.addUse(scriptName, node, String.join(" or ", listed));
        }
    }

    private void checkComputedMember(@NotNull String scriptName, @NotNull ElementGet get) {
        ValueType type = this.inferType(get.getTarget());
        boolean called = get.getParent() instanceof FunctionCall call && call.getTarget() == get;

        if (type != null ? this.declarations.hasGameplayMembers(type) : called) {
            this.addWarning(scriptName, findLine(get), shorten(get) + " cannot be checked: a computed member");
        }
    }

    private void checkDestructuring(@NotNull String scriptName, @NotNull ObjectLiteral literal) {
        AstNode source = switch (literal.getParent()) {
            case VariableInitializer initializer when initializer.getTarget() == literal -> initializer.getInitializer();
            case Assignment assignment when assignment.getLeft() == literal -> assignment.getRight();
            default -> null;
        };

        for (ObjectProperty property : literal.getElements()) {
            String name = findPropertyName(property);

            if (name != null) {
                this.checkMember(scriptName, source, name, property);
            }
        }
    }

    /**
     * Infers the type of an expression.
     *
     * @return the type, or {@code null} if the scan cannot follow it
     */
    private @Nullable ValueType inferType(@NotNull AstNode node) {
        return switch (node) {
            case ParenthesizedExpression expression -> this.inferType(expression.getExpression());
            case Name name -> this.inferName(name);
            case PropertyGet get -> this.inferMember(this.inferType(get.getTarget()), get.getProperty().getIdentifier());
            case ElementGet get -> this.inferElement(get);
            case NewExpression expression -> this.inferConstruction(expression);
            case FunctionCall call -> this.inferCall(call);
            case Assignment assignment when assignment.getType() == Token.ASSIGN -> this.inferType(assignment.getRight());
            case ConditionalExpression expression -> this.inferSame(expression.getTrueExpression(), expression.getFalseExpression());
            case InfixExpression expression when expression.getType() == Token.OR || expression.getType() == Token.AND -> this.inferSame(expression.getLeft(), expression.getRight());
            case ObjectLiteral ignored -> this.createType(OBJECT_TYPE);
            case ArrayLiteral ignored -> this.createType(ARRAY_TYPE);
            case StringLiteral ignored -> this.createType(STRING_TYPE);
            case TemplateLiteral ignored -> this.createType(STRING_TYPE);
            case NumberLiteral ignored -> this.createType(NUMBER_TYPE);
            case RegExpLiteral ignored -> this.createType(REGULAR_EXPRESSION_TYPE);
            case FunctionNode ignored -> this.createType(ScriptDeclarations.FUNCTION_TYPE);
            case KeywordLiteral literal when literal.isBooleanLiteral() -> this.createType(BOOLEAN_TYPE);
            default -> null;
        };
    }

    private @NotNull ValueType createType(@NotNull String interfaceName) {
        return new ValueType(List.of(interfaceName), null);
    }

    private @Nullable ValueType inferSame(@NotNull AstNode first, @NotNull AstNode second) {
        ValueType type = this.inferType(first);

        return type != null && type.equals(this.inferType(second)) ? type : null;
    }

    private @Nullable ValueType inferName(@NotNull Name name) {
        Scope scope = findDefiningScope(name);

        if (scope == null) {
            Member global = this.declarations.findGlobal(name.getIdentifier());

            if (global == null) {
                return null;
            }

            return global.isMethod() ? this.createType(ScriptDeclarations.FUNCTION_TYPE) : this.declarations.resolveType(global.getType());
        }

        org.mozilla.javascript.ast.Symbol symbol = scope.getSymbol(name.getIdentifier());

        if (symbol != null && symbol.getDeclType() == Token.LP) {
            return this.inferParameter(scope, name.getIdentifier());
        }

        Variable variable = new Variable(scope, name.getIdentifier());
        List<AstNode> writes = this.variableWrites.getOrDefault(variable, List.of());

        if (writes.isEmpty()) {
            return symbol != null && symbol.getDeclType() == Token.FUNCTION ? this.createType(ScriptDeclarations.FUNCTION_TYPE) : null;
        }

        return this.inferWrites(variable, writes, this.variableTypes);
    }

    private @Nullable ValueType inferParameter(@NotNull Scope scope, @NotNull String name) {
        if (!(scope instanceof FunctionNode function)) {
            return null;
        }

        List<String> types = this.hookParameterTypes.get(function);
        List<AstNode> parameters = function.getParams();

        if (types == null) {
            return null;
        }

        for (int index = 0; index < parameters.size() && index < types.size(); index++) {
            if (parameters.get(index) instanceof Name parameter && parameter.getIdentifier().equals(name)) {
                return this.declarations.resolveType(types.get(index));
            }
        }

        return null;
    }

    /**
     * Infers the type that every assignment of a variable or a property gives it.
     *
     * @return the type of every assignment, or {@code null} if one is not followed or two differ
     */
    private <K> @Nullable ValueType inferWrites(@NotNull K key, @NotNull List<AstNode> writes, @NotNull Map<K, ValueType> cache) {
        if (cache.containsKey(key)) {
            return cache.get(key);
        }

        if (!this.inferring.add(key)) {
            return null;
        }

        ValueType type = null;

        for (AstNode write : writes) {
            ValueType writeType = write == UNKNOWN_VALUE ? null : this.inferType(write);

            if (writeType == null || type != null && !type.equals(writeType)) {
                type = null;
                break;
            }

            type = writeType;
        }

        this.inferring.remove(key);
        cache.put(key, type);

        return type;
    }

    private @Nullable ValueType inferMember(@Nullable ValueType target, @NotNull String name) {
        if (target != null) {
            List<Member> members = this.declarations.findMembers(target, name);

            if (!members.isEmpty()) {
                Member member = members.getFirst();

                return member.isMethod() ? this.createType(ScriptDeclarations.FUNCTION_TYPE) : this.declarations.resolveType(member.getType());
            }
        }

        List<AstNode> writes = this.propertyWrites.get(name);

        if (writes == null || this.declarations.isDeclaredName(name)) {
            return null;
        }

        return this.inferWrites(name, writes, this.propertyTypes);
    }

    private @Nullable ValueType inferElement(@NotNull ElementGet get) {
        if (get.getElement() instanceof StringLiteral literal) {
            return this.inferMember(this.inferType(get.getTarget()), literal.getValue());
        }

        ValueType target = this.inferType(get.getTarget());

        return target == null ? null : target.getElementType();
    }

    private @Nullable ValueType inferCall(@NotNull FunctionCall call) {
        if (call.getTarget() instanceof PropertyGet get) {
            ValueType target = this.inferType(get.getTarget());
            List<Member> members = target == null ? List.of() : this.declarations.findMembers(target, get.getProperty().getIdentifier());

            return members.isEmpty() || !members.getFirst().isMethod() ? null : this.declarations.resolveType(members.getFirst().getType());
        }

        if (call.getTarget() instanceof Name name && findDefiningScope(name) == null) {
            Member global = this.declarations.findGlobal(name.getIdentifier());

            return global == null || !global.isMethod() ? null : this.declarations.resolveType(global.getType());
        }

        return null;
    }

    private @Nullable ValueType inferConstruction(@NotNull NewExpression expression) {
        if (!(expression.getTarget() instanceof Name name)) {
            return null;
        }

        if (findDefiningScope(name) != null) {
            return this.createType(OBJECT_TYPE);
        }

        return this.declarations.isInterface(name.getIdentifier()) ? this.createType(name.getIdentifier()) : null;
    }
}
