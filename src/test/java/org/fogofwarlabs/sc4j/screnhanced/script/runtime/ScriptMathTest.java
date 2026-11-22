package org.fogofwarlabs.sc4j.screnhanced.script.runtime;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.ScriptableObject;

import java.io.IOException;
import java.util.*;
import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleUnaryOperator;

/**
 * Tests that the script {@code Math} of {@link ScriptMath} has the members of Rhino's {@code Math} and gives the {@link StrictMath} results
 * with the ECMAScript special cases.
 */
final class ScriptMathTest {
    private static final double @NotNull [] SAMPLES = {
            -1e300, -1e10, -100, -3.7, -2.5, -1, -0.75, -0.5, -0.3, -1e-10, -0.0, 0, 1e-300, 1e-10, 0.3, 0.5, 0.75, 1, 1.5, 2, 2.5, 3.7, 10,
            100, 1e10, 1e300, Double.MIN_VALUE, Double.MAX_VALUE, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY
    };

    private static @NotNull String toScriptLiteral(double value) {
        if (Double.isNaN(value)) {
            return "NaN";
        }

        if (Double.isInfinite(value)) {
            return value > 0 ? "Infinity" : "-Infinity";
        }

        return Double.compare(value, -0.0) == 0 ? "-0" : Double.toString(value);
    }

    /**
     * Runs a script that logs each result of a {@code Math} function as {@code Object.is}-comparable text: the number in hexadecimal
     * bits, so that {@code -0} and every NaN stay apart from {@code 0}.
     */
    private static @NotNull List<@NotNull String> runCalls(@NotNull List<@NotNull String> expressions) throws IOException {
        StringBuilder script = new StringBuilder("""
                                                 const view = new DataView(new ArrayBuffer(8));
                                                 function bits(value) {
                                                     if (value !== value) {
                                                         return "NaN";
                                                     }
                                                     view.setFloat64(0, value);
                                                     return view.getUint32(0).toString(16) + ':' + view.getUint32(4).toString(16);
                                                 }
                                                 """);

        for (String expression : expressions) {
            script.append("log.info(bits(").append(expression).append("));\n");
        }

        return ScriptRuntimeScenario.start(script.toString()).getInfoMessages();
    }

    private static @NotNull String toBits(double value) {
        if (Double.isNaN(value)) {
            return "NaN";
        }

        long bits = Double.doubleToRawLongBits(value);

        return Integer.toHexString((int) (bits >>> Integer.SIZE)) + ':' + Integer.toHexString((int) bits);
    }

    private static void assertCalls(@NotNull Map<String, Double> expected) throws IOException {
        List<String> expectedBits = new ArrayList<>();

        for (double value : expected.values()) {
            expectedBits.add(toBits(value));
        }

        List<String> actual = runCalls(List.copyOf(expected.keySet()));

        for (int index = 0; index < expectedBits.size(); index++) {
            Assertions.assertEquals(expectedBits.get(index), actual.get(index), List.copyOf(expected.keySet()).get(index));
        }
    }

    @Test
    void math_has_the_members_of_rhinos_math() throws IOException {
        TreeSet<String> rhinoMembers = new TreeSet<>();

        try (Context context = new ContextFactory().enterContext()) {
            ScriptableObject global = context.initSafeStandardObjects();
            ScriptableObject math = (ScriptableObject) global.get("Math", global);

            for (Object id : math.getAllIds()) {
                rhinoMembers.add(String.valueOf(id));
            }
        }

        ScriptRuntimeScenario scenario = ScriptRuntimeScenario.start("""
                                                                     log.info(Object.getOwnPropertyNames(Math).sort().join(','));
                                                                     log.info(Object.prototype.toString.call(Math), Math.E === 2.718281828459045, Math.PI === 3.141592653589793, Math.LN2, Math.LN10,
                                                                              Math.LOG2E, Math.LOG10E, Math.SQRT1_2, Math.SQRT2, Math.max.length, Math.hypot.length, Math.random.length);
                                                                     """);

        Assertions.assertEquals(String.join(",", rhinoMembers), scenario.getInfoMessages().getFirst());
        Assertions.assertEquals("[object Math] true true 0.6931471805599453 2.302585092994046 1.4426950408889634 0.4342944819032518 "
                                + "0.7071067811865476 1.4142135623730951 2 2 0", scenario.getInfoMessages().get(1));
    }

    @Test
    void math_functions_equal_strict_math() throws IOException {
        Map<String, DoubleUnaryOperator> unary = new LinkedHashMap<>();
        Map<String, DoubleBinaryOperator> binary = new LinkedHashMap<>();
        Map<String, Double> expected = new LinkedHashMap<>();

        unary.put("sin", StrictMath::sin);
        unary.put("cos", StrictMath::cos);
        unary.put("tan", StrictMath::tan);
        unary.put("asin", StrictMath::asin);
        unary.put("acos", StrictMath::acos);
        unary.put("atan", StrictMath::atan);
        unary.put("sinh", StrictMath::sinh);
        unary.put("cosh", StrictMath::cosh);
        unary.put("tanh", StrictMath::tanh);
        unary.put("exp", StrictMath::exp);
        unary.put("expm1", StrictMath::expm1);
        unary.put("log", StrictMath::log);
        unary.put("log1p", StrictMath::log1p);
        unary.put("log10", StrictMath::log10);
        unary.put("sqrt", StrictMath::sqrt);
        unary.put("cbrt", StrictMath::cbrt);
        unary.put("floor", StrictMath::floor);
        unary.put("ceil", StrictMath::ceil);
        unary.put("abs", Math::abs);
        unary.put("sign", Math::signum);
        binary.put("atan2", StrictMath::atan2);
        binary.put("pow", StrictMath::pow);
        binary.put("hypot", StrictMath::hypot);
        binary.put("max", Math::max);
        binary.put("min", Math::min);

        for (Map.Entry<String, DoubleUnaryOperator> function : unary.entrySet()) {
            for (double sample : SAMPLES) {
                expected.put("Math." + function.getKey() + "(" + toScriptLiteral(sample) + ")", function.getValue().applyAsDouble(sample));
            }
        }

        for (Map.Entry<String, DoubleBinaryOperator> function : binary.entrySet()) {
            for (double first : SAMPLES) {
                for (double second : new double[]{-2, -0.5, -0.0, 0, 0.5, 1, 3, Double.NaN, Double.POSITIVE_INFINITY}) {
                    expected.put("Math." + function.getKey() + "(" + toScriptLiteral(first) + ", " + toScriptLiteral(second) + ")",
                                 function.getValue().applyAsDouble(first, second));
                }
            }
        }

        assertCalls(expected);
    }

    @Test
    void math_functions_follow_the_ecmascript_special_cases() throws IOException {
        Map<String, Double> expected = new LinkedHashMap<>();

        expected.put("Math.round(2.5)", 3.0);
        expected.put("Math.round(-2.5)", -2.0);
        expected.put("Math.round(-0.5)", -0.0);
        expected.put("Math.round(-0.3)", -0.0);
        expected.put("Math.round(0.49999999999999994)", 0.0);
        expected.put("Math.round(4503599627370497)", 4503599627370497.0);
        expected.put("Math.round(-0)", -0.0);
        expected.put("Math.round(NaN)", Double.NaN);
        expected.put("Math.trunc(-0.5)", -0.0);
        expected.put("Math.trunc(-3.7)", -3.0);
        expected.put("Math.trunc(3.7)", 3.0);
        expected.put("Math.log2(8)", 3.0);
        expected.put("Math.log2(1)", 0.0);
        expected.put("Math.log2(0.125)", -3.0);
        expected.put("Math.log2(Number.MIN_VALUE)", -1074.0);
        expected.put("Math.log2(2 ** -1030)", -1030.0);
        expected.put("Math.log2(0)", Double.NEGATIVE_INFINITY);
        expected.put("Math.log2(-1)", Double.NaN);
        expected.put("Math.log2(3)", StrictMath.log(3) / StrictMath.log(2));
        expected.put("Math.max()", Double.NEGATIVE_INFINITY);
        expected.put("Math.min()", Double.POSITIVE_INFINITY);
        expected.put("Math.max(-0, 0)", 0.0);
        expected.put("Math.min(0, -0)", -0.0);
        expected.put("Math.max(1, NaN, 3)", Double.NaN);
        expected.put("Math.hypot()", 0.0);
        expected.put("Math.hypot(-3)", 3.0);
        expected.put("Math.hypot(3, 4, 12)", StrictMath.hypot(StrictMath.hypot(3, 4), 12));
        expected.put("Math.hypot(NaN, Infinity)", Double.POSITIVE_INFINITY);
        expected.put("Math.pow(1, Infinity)", Double.NaN);
        expected.put("Math.pow(NaN, 0)", 1.0);
        expected.put("Math.clz32(1)", 31.0);
        expected.put("Math.clz32(0)", 32.0);
        expected.put("Math.clz32(-1)", 0.0);
        expected.put("Math.imul(0xffffffff, 5)", -5.0);
        expected.put("Math.imul(0x7fffffff, 2)", -2.0);
        expected.put("Math.fround(5.05)", (double) 5.05f);
        expected.put("Math.fround(1e40)", Double.POSITIVE_INFINITY);
        expected.put("Math.asinh(-0)", -0.0);
        expected.put("Math.asinh(1)", StrictMath.log1p(1 + 1 / (1 + StrictMath.sqrt(2))));
        expected.put("Math.asinh(-1e300)", -(StrictMath.log(1e300) + 0.6931471805599453));
        expected.put("Math.acosh(1)", 0.0);
        expected.put("Math.acosh(0.5)", Double.NaN);
        expected.put("Math.acosh(Infinity)", Double.POSITIVE_INFINITY);
        expected.put("Math.atanh(-0)", -0.0);
        expected.put("Math.atanh(1)", Double.POSITIVE_INFINITY);
        expected.put("Math.atanh(-1)", Double.NEGATIVE_INFINITY);
        expected.put("Math.atanh(2)", Double.NaN);
        expected.put("Math.atanh(0.5)", 0.5 * StrictMath.log1p(2 * 0.5 / (1 - 0.5)));
        expected.put("Math.sin()", Double.NaN);
        expected.put("Math.abs('-4')", 4.0);

        assertCalls(expected);
    }

    @Test
    void math_converts_every_argument_in_order() throws IOException {
        ScriptRuntimeScenario scenario = ScriptRuntimeScenario.start("""
                                                                     const order = [];
                                                                     const value = name => ({ valueOf() { order.push(name); return NaN; } });
                                                                     Math.max(value('a'), value('b'), value('c'));
                                                                     Math.atan2(value('d'), value('e'));
                                                                     log.info(order.join(''));
                                                                     """);

        Assertions.assertEquals(List.of("abcde"), scenario.getInfoMessages(), scenario.getLines()::toString);
    }
}
