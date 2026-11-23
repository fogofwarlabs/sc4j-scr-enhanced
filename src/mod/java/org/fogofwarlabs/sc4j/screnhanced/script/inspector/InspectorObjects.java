package org.fogofwarlabs.sc4j.screnhanced.script.inspector;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.json.JsonReader;
import org.fogofwarlabs.sc4j.screnhanced.script.runtime.ScriptConsole;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.mozilla.javascript.*;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Mirrors the values of console code as the remote objects of the Chrome DevTools Protocol for one DevTools session: describes a value, gives an
 * object an id in an object group, lists the properties of an object by id, and describes script errors.
 * <p>
 * A game object, an object whose {@code toString} the prelude defines (a unit, a player, a type, a position), is described by that
 * {@code toString}; its preview and its internal properties are the values of the prelude's methods without parameters whose names start
 * with {@code get}, {@code is} or {@code has}, and {@code exists}, called on it. Any other object is described by the name of its constructor,
 * and its preview holds its own enumerable properties.
 * <p>
 * The methods that read a script value run on the game thread inside a console call, since they can call script functions; the others run on
 * any thread that the caller serializes with them.
 */
final class InspectorObjects {
    private static final int OBJECT_PREVIEW_PROPERTIES = 5;
    private static final int ARRAY_PREVIEW_PROPERTIES = 100;
    private static final int PREVIEW_STRING_LENGTH = 100;
    private static final int COMPLETION_ITEMS = 10_000;
    private static final double LARGEST_EXACT_INTEGER = 0x1.0p53;
    private static final int NUMBER_BASE = 10;
    private static final @NotNull String PREVIEW_STRING_END = "...";
    private static final @NotNull String STACK_LINE_PREFIX = "at ";
    private static final @NotNull String STACK_LINE_INDENT = "\n    ";
    private static final @NotNull String PRELUDE_SOURCE_PREFIX = "prelude/";
    private static final @NotNull String UNCAUGHT_TEXT = "Uncaught";
    private static final @NotNull String CALL_SUFFIX = "()";
    private static final @NotNull String PROTOTYPE_PROPERTY = "[[Prototype]]";
    private static final @NotNull String TO_STRING_NAME = "toString";
    private static final @NotNull String CONSTRUCTOR_NAME = "constructor";
    private static final @NotNull String NAME_PROPERTY = "name";
    private static final @NotNull String MESSAGE_PROPERTY = "message";
    private static final @NotNull String STACK_PROPERTY = "stack";
    private static final @NotNull String SIZE_PROPERTY = "size";
    private static final @NotNull String ERROR_CLASS = "Error";
    private static final @NotNull String SYNTAX_ERROR_NAME = "SyntaxError";
    /**
     * The names of the prelude's methods whose values a game object shows.
     */
    private static final @NotNull Pattern GAME_VALUE_NAME = Pattern.compile("(get|is|has)[A-Z].*|exists");
    /**
     * The subtypes of the remote objects by the class names of Rhino's objects.
     */
    private static final @NotNull Map<String, String> SUBTYPES = Map.ofEntries(
            Map.entry("Array", "array"), Map.entry(ERROR_CLASS, "error"), Map.entry("RegExp", "regexp"), Map.entry("Map", "map"),
            Map.entry("Set", "set"), Map.entry("WeakMap", "weakmap"), Map.entry("WeakSet", "weakset"), Map.entry("Promise", "promise"),
            Map.entry("ArrayBuffer", "arraybuffer"), Map.entry("DataView", "dataview"), Map.entry("Int8Array", "typedarray"),
            Map.entry("Uint8Array", "typedarray"), Map.entry("Uint8ClampedArray", "typedarray"), Map.entry("Int16Array", "typedarray"),
            Map.entry("Uint16Array", "typedarray"), Map.entry("Int32Array", "typedarray"), Map.entry("Uint32Array", "typedarray"),
            Map.entry("Float32Array", "typedarray"), Map.entry("Float64Array", "typedarray")
    );

    /**
     * Where a mirrored object belongs: its object group, which gives it an id, and the execution context whose rules its property reads follow.
     */
    static final class Origin {
        private final @Nullable String group;
        private final int contextId;
        private final boolean local;

        /**
         * Creates an {@link Origin} instance.
         *
         * @param group     the object group, or {@code null} to give the objects no id
         * @param contextId the id of the execution context
         * @param local     whether the context runs local code
         */
        Origin(@Nullable String group, int contextId, boolean local) {
            this.group     = group;
            this.contextId = contextId;
            this.local     = local;
        }

        /**
         * @return the id of the execution context
         */
        int getContextId() {
            return this.contextId;
        }

        /**
         * @return {@code true} if the execution context runs local code, {@code false} if it runs shared code
         */
        boolean isLocal() {
            return this.local;
        }
    }

    /**
     * An object that has an id.
     */
    static final class Entry {
        private final @NotNull Object value;
        private final @NotNull Origin origin;

        private Entry(@NotNull Object value, @NotNull Origin origin) {
            this.value  = value;
            this.origin = origin;
        }

        /**
         * @return the object
         */
        @NotNull Object getValue() {
            return this.value;
        }

        /**
         * @return the group and execution context of the object
         */
        @NotNull Origin getOrigin() {
            return this.origin;
        }
    }


    private static @Nullable Function findPreludeToString(@NotNull Scriptable object) {
        for (Scriptable owner = object; owner != null; owner = owner.getPrototype()) {
            if (owner.has(TO_STRING_NAME, owner)) {
                Object toString = owner.get(TO_STRING_NAME, owner);

                return ScriptConsole.isPreludeFunction(toString) ? (Function) toString : null;
            }
        }

        return null;
    }

    /**
     * Finds the prelude's methods whose values a game object shows, in the order of its prototype chain and of their definition.
     */
    private static @NotNull Map<String, Function> findGameValueMethods(@NotNull Scriptable object) {
        Map<String, Function> methods = new LinkedHashMap<>();

        if (findPreludeToString(object) == null) {
            return methods;
        }

        Set<String> seen = new HashSet<>();

        for (Scriptable owner = object; owner != null; owner = owner.getPrototype()) {
            for (Object id : getOwnIds(owner)) {
                if (!(id instanceof String name) || !seen.add(name) || !GAME_VALUE_NAME.matcher(name).matches() || isAccessor(owner, id)) {
                    continue;
                }

                Object method = owner.get(name, owner);

                if (ScriptConsole.isPreludeFunction(method) && ((BaseFunction) method).getArity() == 0) {
                    methods.put(name, (Function) method);
                }
            }
        }

        return methods;
    }

    private static @NotNull Object @NotNull [] getOwnIds(@NotNull Scriptable object) {
        return object instanceof ScriptableObject scriptableObject ? scriptableObject.getAllIds() : object.getIds();
    }

    private static boolean isAccessor(@NotNull Scriptable object, @NotNull Object id) {
        if (!(object instanceof ScriptableObject scriptableObject)) {
            return false;
        }

        String name = id instanceof String text ? text : null;
        int index = id instanceof Integer number ? number : 0;

        return scriptableObject.getGetterOrSetter(name, index, scriptableObject, false) instanceof Function ||
               scriptableObject.getGetterOrSetter(name, index, scriptableObject, true) instanceof Function;
    }

    private static @Nullable Object getOwnValue(@NotNull Scriptable object, @NotNull Object id) {
        return id instanceof Integer index ? object.get(index, object) : object.get(id.toString(), object);
    }

    private static int getAttributes(@NotNull Scriptable object, @NotNull Object id) {
        if (!(object instanceof ScriptableObject scriptableObject)) {
            return ScriptableObject.EMPTY;
        }

        try {
            return id instanceof Integer index ? scriptableObject.getAttributes(index) : scriptableObject.getAttributes(id.toString());
        } catch (RhinoException exception) {
            return ScriptableObject.EMPTY;
        }
    }

    /**
     * Returns the text of a primitive value without calling script code: the text of a string, number or boolean, otherwise an empty string.
     */
    private static @NotNull String toPrimitiveText(@Nullable Object value) {
        return switch (value) {
            case CharSequence text -> text.toString();
            case BigInteger bigInteger -> bigInteger + "n";
            case Number number -> org.mozilla.javascript.ScriptRuntime.numberToString(number.doubleValue(), NUMBER_BASE);
            case Boolean flag -> flag.toString();
            case null, default -> "";
        };
    }

    private static @NotNull String findClassName(@NotNull Scriptable object) {
        if (object instanceof NativeArray) {
            return object.getClassName();
        }

        try {
            if (ScriptableObject.getProperty(object, CONSTRUCTOR_NAME) instanceof Function constructor &&
                ScriptableObject.getProperty(constructor, NAME_PROPERTY) instanceof CharSequence name && !name.isEmpty()) {
                return name.toString();
            }
        } catch (RhinoException exception) {
            // A constructor getter that throws leaves Rhino's class name.
        }

        return object.getClassName();
    }

    /**
     * Describes an error object as V8 does: its name and message, then a line per frame of its stack outside the prelude.
     */
    private static @NotNull String describeError(@NotNull Scriptable error) {
        StringBuilder description = new StringBuilder(toPrimitiveText(ScriptableObject.getProperty(error, NAME_PROPERTY)));
        String message = toPrimitiveText(ScriptableObject.getProperty(error, MESSAGE_PROPERTY));

        if (!message.isEmpty()) {
            description.append(": ").append(message);
        }

        appendStack(description, toPrimitiveText(ScriptableObject.getProperty(error, STACK_PROPERTY)));

        return description.toString();
    }

    private static void appendStack(@NotNull StringBuilder description, @NotNull String stack) {
        for (String line : stack.split("\n")) {
            String frame = line.strip();

            if (frame.startsWith(STACK_LINE_PREFIX) && !frame.startsWith(STACK_LINE_PREFIX + PRELUDE_SOURCE_PREFIX)) {
                description.append(STACK_LINE_INDENT).append(frame);
            }
        }
    }

    private static @NotNull String describeFunction(@NotNull Context context, @NotNull Function function) {
        return context.decompileFunction(function, 0).strip();
    }

    /**
     * Creates the remote object of a primitive value, or returns {@code null} for an object.
     */
    private static @Nullable Map<String, Object> createPrimitiveObject(@Nullable Object value) {
        Map<String, Object> remote = new LinkedHashMap<>();

        switch (value) {
            case null -> {
                remote.put("type", "object");
                remote.put("subtype", "null");
                remote.put("value", null);
            }
            case Object undefined when Undefined.isUndefined(undefined) -> remote.put("type", "undefined");
            case Boolean flag -> {
                remote.put("type", "boolean");
                remote.put("value", flag);
            }
            case BigInteger bigInteger -> {
                remote.put("type", "bigint");
                remote.put("unserializableValue", bigInteger + "n");
                remote.put("description", bigInteger + "n");
            }
            case Number number -> {
                double doubleValue = number.doubleValue();
                String unserializable = findUnserializableNumber(doubleValue);

                remote.put("type", "number");

                if (unserializable == null) {
                    remote.put("value", toJsonNumber(doubleValue));
                } else {
                    remote.put("unserializableValue", unserializable);
                }

                remote.put("description", toPrimitiveText(number));
            }
            case CharSequence text -> {
                remote.put("type", "string");
                remote.put("value", text.toString());
            }
            default -> {
                return null;
            }
        }

        return remote;
    }

    private static @Nullable String findUnserializableNumber(double value) {
        if (Double.isNaN(value)) {
            return "NaN";
        }

        if (Double.isInfinite(value)) {
            return value > 0 ? "Infinity" : "-Infinity";
        }

        return value == 0 && Double.doubleToRawLongBits(value) != 0 ? "-0" : null;
    }

    private static @NotNull Object toJsonNumber(double value) {
        return value == Math.rint(value) && Math.abs(value) <= LARGEST_EXACT_INTEGER ? (Object) (long) value : (Object) value;
    }


    private final @NotNull Map<String, Entry> entries;
    private long nextObjectId;
    private int nextExceptionId;

    /**
     * Creates an {@link InspectorObjects} instance without objects.
     */
    InspectorObjects() {
        this.entries         = new HashMap<>();
        this.nextObjectId    = 1;
        this.nextExceptionId = 1;
    }

    /**
     * Finds an object by id.
     *
     * @param objectId the id
     * @return the object's entry, or {@code null} if no object has the id
     */
    @Nullable Entry findEntry(@NotNull String objectId) {
        return this.entries.get(objectId);
    }

    /**
     * Forgets an object.
     *
     * @param objectId the object's id
     */
    void release(@NotNull String objectId) {
        this.entries.remove(objectId);
    }

    /**
     * Forgets the objects of a group.
     *
     * @param group the group
     */
    void releaseGroup(@NotNull String group) {
        this.entries.values().removeIf(entry -> group.equals(entry.origin.group));
    }

    /**
     * Forgets every object, as when the game changes.
     */
    void clear() {
        this.entries.clear();
    }

    /**
     * Creates the remote object of a value: a primitive by value, an object with an id in the origin's group.
     *
     * @param context the current context
     * @param value   the value
     * @param origin  the group and execution context of the objects
     * @param preview whether an object gets a preview of its properties
     * @return the remote object
     */
    @NotNull Map<String, Object> createRemoteObject(@NotNull Context context, @Nullable Object value, @NotNull Origin origin, boolean preview) {
        Map<String, Object> primitive = createPrimitiveObject(value);

        if (primitive != null) {
            return primitive;
        }

        Map<String, Object> remote = new LinkedHashMap<>();

        switch (value) {
            case Symbol symbol -> {
                remote.put("type", "symbol");
                remote.put("description", symbol.toString());
            }
            case Function function -> {
                remote.put("type", "function");
                remote.put("className", "Function");
                remote.put("description", describeFunction(context, function));
            }
            case Scriptable object -> {
                String className = findClassName(object);
                String subtype = SUBTYPES.get(object.getClassName());
                String description = this.describeObject(context, object, className, subtype);

                remote.put("type", "object");

                if (subtype != null) {
                    remote.put("subtype", subtype);
                }

                remote.put("className", className);
                remote.put("description", description);

                if (preview) {
                    remote.put("preview", this.createPreview(context, object, subtype, description));
                }
            }
            default -> {
                remote.put("type", "object");
                remote.put("className", value.getClass().getSimpleName());
                remote.put("description", String.valueOf(value));

                return remote;
            }
        }

        if (origin.group != null) {
            String objectId = Long.toString(this.nextObjectId++);

            this.entries.put(objectId, new Entry(value, origin));
            remote.put("objectId", objectId);
        }

        return remote;
    }

    private @NotNull String describeObject(@NotNull Context context, @NotNull Scriptable object, @NotNull String className, @Nullable String subtype) {
        if (object instanceof NativeArray array) {
            return className + "(" + array.getLength() + ")";
        }

        if (ERROR_CLASS.equals(object.getClassName())) {
            return describeError(object);
        }

        if ("map".equals(subtype) || "set".equals(subtype)) {
            return className + "(" + toPrimitiveText(ScriptableObject.getProperty(object, SIZE_PROPERTY)) + ")";
        }

        Function toString = findPreludeToString(object);

        if (toString != null) {
            try {
                Object text = toString.call(context, ScriptableObject.getTopLevelScope(toString), object, Context.emptyArgs);

                if (text instanceof CharSequence) {
                    return text.toString();
                }
            } catch (RhinoException exception) {
                // A game object whose toString throws is described by its class name.
            }
        }

        return className;
    }

    /**
     * Creates the preview of an object: the values of a game object, else its own enumerable properties, up to a few for an object and more for
     * an array.
     */
    private @NotNull Map<String, Object> createPreview(@NotNull Context context, @NotNull Scriptable object, @Nullable String subtype,
                                                       @NotNull String description) {
        List<Object> properties = new ArrayList<>();
        Map<String, Function> gameValues = findGameValueMethods(object);
        boolean overflow = false;

        if (!gameValues.isEmpty()) {
            for (Map.Entry<String, Function> method : gameValues.entrySet()) {
                if (properties.size() == OBJECT_PREVIEW_PROPERTIES) {
                    overflow = true;
                    break;
                }

                try {
                    Object value = method.getValue().call(context, ScriptableObject.getTopLevelScope(method.getValue()), object, Context.emptyArgs);

                    properties.add(this.createPropertyPreview(context, method.getKey() + CALL_SUFFIX, value));
                } catch (RhinoException exception) {
                    // A value that cannot be read, as of a unit that is gone, is left out.
                }
            }
        } else {
            int limit = "array".equals(subtype) ? ARRAY_PREVIEW_PROPERTIES : OBJECT_PREVIEW_PROPERTIES;

            for (Object id : object.getIds()) {
                if (properties.size() == limit) {
                    overflow = true;
                    break;
                }

                if (isAccessor(object, id)) {
                    properties.add(new LinkedHashMap<>(Map.of("name", id.toString(), "type", "accessor")));
                } else {
                    properties.add(this.createPropertyPreview(context, id.toString(), getOwnValue(object, id)));
                }
            }
        }

        Map<String, Object> preview = new LinkedHashMap<>();

        preview.put("type", "object");

        if (subtype != null) {
            preview.put("subtype", subtype);
        }

        preview.put("description", description);
        preview.put("overflow", overflow);
        preview.put("properties", properties);

        return preview;
    }

    private @NotNull Map<String, Object> createPropertyPreview(@NotNull Context context, @NotNull String name, @Nullable Object value) {
        Map<String, Object> property = new LinkedHashMap<>();
        Map<String, Object> primitive = createPrimitiveObject(value);

        property.put("name", name);

        if (primitive != null) {
            String text = value == null ? "null" : Undefined.isUndefined(value) ? "undefined" : toPrimitiveText(value);

            property.put("type", primitive.get("type"));
            property.put("value", text.length() > PREVIEW_STRING_LENGTH ? text.substring(0, PREVIEW_STRING_LENGTH) + PREVIEW_STRING_END : text);

            if (primitive.containsKey("subtype")) {
                property.put("subtype", primitive.get("subtype"));
            }

            return property;
        }

        switch (value) {
            case Symbol symbol -> {
                property.put("type", "symbol");
                property.put("value", symbol.toString());
            }
            case Function function -> {
                property.put("type", "function");
                property.put("value", "");
            }
            case Scriptable object -> {
                String className = findClassName(object);
                String subtype = SUBTYPES.get(object.getClassName());

                property.put("type", "object");
                property.put("value", ERROR_CLASS.equals(object.getClassName()) ? className : this.describeObject(context, object, className, subtype));

                if (subtype != null) {
                    property.put("subtype", subtype);
                }
            }
            default -> {
                property.put("type", "object");
                property.put("value", String.valueOf(value));
            }
        }

        return property;
    }

    /**
     * Lists the properties of an object by id, as {@code Runtime.getProperties} does.
     *
     * @param context                the current context
     * @param entry                  the object's entry
     * @param ownProperties          whether only the object's own properties are listed, with its prototype and the values of a game object
     *                               as internal properties
     * @param accessorPropertiesOnly whether only the accessor properties of the object's prototypes are listed
     * @param nonIndexedOnly         whether the properties of array indices are left out
     * @param preview                whether the objects of the values get previews
     * @return the result of {@code Runtime.getProperties}: {@code result} and {@code internalProperties}
     */
    @NotNull Map<String, Object> getProperties(@NotNull Context context, @NotNull Entry entry, boolean ownProperties, boolean accessorPropertiesOnly,
                                               boolean nonIndexedOnly, boolean preview) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Object> properties = new ArrayList<>();
        List<Object> internalProperties = new ArrayList<>();

        result.put("result", properties);

        if (!(entry.value instanceof Scriptable object)) {
            return result;
        }

        Set<String> names = new HashSet<>();

        if (accessorPropertiesOnly) {
            for (Object id : getOwnIds(object)) {
                names.add(id.toString());
            }

            for (Scriptable owner = object.getPrototype(); owner != null; owner = owner.getPrototype()) {
                this.addProperties(context, owner, false, true, nonIndexedOnly, names, properties, entry.origin, preview);
            }

            return result;
        }

        this.addProperties(context, object, true, false, nonIndexedOnly, names, properties, entry.origin, preview);

        if (!ownProperties) {
            for (Scriptable owner = object.getPrototype(); owner != null; owner = owner.getPrototype()) {
                this.addProperties(context, owner, false, false, nonIndexedOnly, names, properties, entry.origin, preview);
            }

            return result;
        }

        for (Map.Entry<String, Function> method : findGameValueMethods(object).entrySet()) {
            try {
                Object value = method.getValue().call(context, ScriptableObject.getTopLevelScope(method.getValue()), object, Context.emptyArgs);

                internalProperties.add(this.createInternalProperty(context, method.getKey() + CALL_SUFFIX, value, entry.origin, preview));
            } catch (RhinoException exception) {
                // A value that cannot be read, as of a unit that is gone, is left out.
            }
        }

        if (object.getPrototype() != null) {
            internalProperties.add(this.createInternalProperty(context, PROTOTYPE_PROPERTY, object.getPrototype(), entry.origin, preview));
        }

        result.put("internalProperties", internalProperties);

        return result;
    }

    private @NotNull Map<String, Object> createInternalProperty(@NotNull Context context, @NotNull String name, @Nullable Object value,
                                                                @NotNull Origin origin, boolean preview) {
        Map<String, Object> property = new LinkedHashMap<>();

        property.put("name", name);
        property.put("value", this.createRemoteObject(context, value, origin, preview));

        return property;
    }

    /**
     * Adds the property descriptors of an object's own properties whose names are not listed yet.
     */
    private void addProperties(@NotNull Context context, @NotNull Scriptable owner, boolean own, boolean accessorsOnly, boolean nonIndexedOnly,
                               @NotNull Set<String> names, @NotNull List<Object> properties, @NotNull Origin origin, boolean preview) {
        for (Object id : getOwnIds(owner)) {
            if (!(id instanceof String || id instanceof Integer && !nonIndexedOnly) || names.contains(id.toString())) {
                continue;
            }

            boolean accessor = isAccessor(owner, id);

            if (accessorsOnly && !accessor) {
                continue;
            }

            int attributes = getAttributes(owner, id);
            Map<String, Object> property = new LinkedHashMap<>();

            names.add(id.toString());
            property.put("name", id.toString());

            if (accessor) {
                ScriptableObject scriptableObject = (ScriptableObject) owner;
                String name = id instanceof String text ? text : null;
                int index = id instanceof Integer number ? number : 0;

                property.put("get", this.createRemoteObject(context, scriptableObject.getGetterOrSetter(name, index, scriptableObject, false), origin,
                                                            false));
                property.put("set", this.createRemoteObject(context, scriptableObject.getGetterOrSetter(name, index, scriptableObject, true), origin,
                                                            false));
            } else {
                property.put("value", this.createRemoteObject(context, getOwnValue(owner, id), origin, preview));
                property.put("writable", (attributes & ScriptableObject.READONLY) == 0);
            }

            property.put("configurable", (attributes & ScriptableObject.PERMANENT) == 0);
            property.put("enumerable", (attributes & ScriptableObject.DONTENUM) == 0);
            property.put("isOwn", own);
            properties.add(property);
        }
    }

    /**
     * Creates the remote object of a value given by value, as JSON, as {@code returnByValue} asks.
     *
     * @param context the current context
     * @param scope   the scope of {@code JSON.stringify}
     * @param value   the value
     * @return the remote object, with the value as the JSON value of {@code JSON.stringify}, or without a value if it gives none
     * @throws RhinoException if {@code JSON.stringify} throws, as for a cycle
     */
    @NotNull Map<String, Object> createValueObject(@NotNull Context context, @NotNull Scriptable scope, @Nullable Object value) {
        Map<String, Object> primitive = createPrimitiveObject(value);

        if (primitive != null) {
            return primitive;
        }

        Map<String, Object> remote = new LinkedHashMap<>();
        Object json = NativeJSON.stringify(context, scope, value, null, null);

        remote.put("type", value instanceof Function ? "function" : "object");

        if (json instanceof CharSequence text) {
            try {
                remote.put("value", JsonReader.parse(text.toString().getBytes(StandardCharsets.UTF_8)));
            } catch (IOException exception) {
                throw Exceptions.newIllegalStateException(exception, "JSON.stringify wrote no JSON: {0}", exception.getMessage());
            }
        }

        return remote;
    }

    /**
     * Lists the property names of an object and its prototypes for completion, as the {@code getCompletions} function of DevTools does: one
     * group per object of the prototype chain, with the constructor name of the object that owns {@code constructor} as its title.
     *
     * @param scope  the scope of the standard prototypes
     * @param target the object, or {@code null} for the prototype of a primitive type
     * @param type   the type of the completed value: {@code string}, {@code number}, {@code boolean}, {@code bigint} or {@code symbol} for a
     *               primitive, else any
     * @return the groups, each with {@code items} and maybe {@code title}
     */
    @NotNull List<Object> findCompletions(@NotNull Scriptable scope, @Nullable Object target, @NotNull String type) {
        List<Object> groups = new ArrayList<>();
        Scriptable object = switch (type) {
            case "string" -> ScriptableObject.getClassPrototype(scope, "String");
            case "number" -> ScriptableObject.getClassPrototype(scope, "Number");
            case "boolean" -> ScriptableObject.getClassPrototype(scope, "Boolean");
            case "bigint" -> ScriptableObject.getClassPrototype(scope, "BigInt");
            case "symbol" -> ScriptableObject.getClassPrototype(scope, "Symbol");
            default -> target instanceof Scriptable scriptable ? scriptable : null;
        };

        for (Scriptable owner = object; owner != null; owner = owner.getPrototype()) {
            Map<String, Object> group = new LinkedHashMap<>();
            List<Object> items = new ArrayList<>();
            boolean array = owner instanceof NativeArray;

            if (owner.has(CONSTRUCTOR_NAME, owner) && !isAccessor(owner, CONSTRUCTOR_NAME) &&
                owner.get(CONSTRUCTOR_NAME, owner) instanceof Function constructor &&
                ScriptableObject.getProperty(constructor, NAME_PROPERTY) instanceof CharSequence name && !name.isEmpty()) {
                group.put("title", name.toString());
            }

            for (Object id : getOwnIds(owner)) {
                if (items.size() == COMPLETION_ITEMS) {
                    break;
                }

                if (id instanceof String name || id instanceof Integer && !array) {
                    items.add(id.toString());
                }
            }

            group.put("items", items);
            groups.add(group);
        }

        return groups;
    }

    /**
     * Describes a script error as the {@code exceptionDetails} of the Chrome DevTools Protocol: the thrown value, and the location and stack
     * outside the prelude.
     *
     * @param context   the current context
     * @param exception the script error
     * @param origin    the group and execution context of the thrown object
     * @return the exception details
     */
    @NotNull Map<String, Object> createExceptionDetails(@NotNull Context context, @NotNull RhinoException exception, @NotNull Origin origin) {
        if (exception instanceof JavaScriptException javaScriptException) {
            return this.createExceptionDetails(exception, this.createRemoteObject(context, javaScriptException.getValue(), origin, false));
        }

        return this.createExceptionDetails(exception);
    }

    /**
     * Describes a script error as the {@code exceptionDetails} of the Chrome DevTools Protocol without running script code: a description of
     * the error from its message, and the location and stack outside the prelude.
     *
     * @param exception the script error
     * @return the exception details
     */
    @NotNull Map<String, Object> createExceptionDetails(@NotNull RhinoException exception) {
        String name = switch (exception) {
            case EcmaError ecmaError -> ecmaError.getName();
            case EvaluatorException evaluatorException -> SYNTAX_ERROR_NAME;
            default -> exception instanceof JavaScriptException ? UNCAUGHT_TEXT : ERROR_CLASS;
        };
        String message = exception instanceof EcmaError ecmaError ? ecmaError.getErrorMessage() : exception.details();
        StringBuilder description = new StringBuilder(exception instanceof JavaScriptException ? message : name + ": " + message);
        Map<String, Object> thrown = new LinkedHashMap<>();

        appendStack(description, exception.getScriptStackTrace());

        thrown.put("type", "object");
        thrown.put("subtype", "error");
        thrown.put("className", name);
        thrown.put("description", description.toString());

        return this.createExceptionDetails(exception, thrown);
    }

    private @NotNull Map<String, Object> createExceptionDetails(@NotNull RhinoException exception, @NotNull Map<String, Object> thrown) {
        Map<String, Object> details = new LinkedHashMap<>();
        List<Object> callFrames = new ArrayList<>();

        for (ScriptStackElement element : exception.getScriptStack()) {
            if (element.fileName == null || element.fileName.startsWith(PRELUDE_SOURCE_PREFIX)) {
                continue;
            }

            Map<String, Object> frame = new LinkedHashMap<>();

            frame.put("functionName", element.functionName == null ? "" : element.functionName);
            frame.put("scriptId", "0");
            frame.put("url", element.fileName);
            frame.put("lineNumber", Math.max(element.lineNumber - 1, 0));
            frame.put("columnNumber", 0);
            callFrames.add(frame);
        }

        details.put("exceptionId", this.nextExceptionId++);
        details.put("text", UNCAUGHT_TEXT);
        details.put("lineNumber", Math.max(exception.lineNumber() - 1, 0));
        details.put("columnNumber", Math.max(exception.columnNumber() - 1, 0));

        if (exception.sourceName() != null && !exception.sourceName().startsWith(PRELUDE_SOURCE_PREFIX)) {
            details.put("url", exception.sourceName());
        }

        if (!callFrames.isEmpty()) {
            details.put("stackTrace", Map.of("callFrames", callFrames));
        }

        details.put("exception", thrown);

        return details;
    }
}
