package io.github.huapeng01016.stata4j;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Parsing and evaluation of filter expressions, independent of any file. */
class DtaFilterTest {

    // Three numeric variables a, b, c and one string variable s.
    private static final List<String> NAMES = List.of("a", "b", "c", "s");
    private static final List<StataVarType> TYPES =
            List.of(StataVarType.DOUBLE, StataVarType.DOUBLE, StataVarType.DOUBLE, StataVarType.str(5));

    private static boolean eval(String expr, double a, double b, double c, String s) {
        DtaFilter.Bound f = DtaFilter.parse(expr).bind(NAMES, TYPES);
        double[] nums = new double[f.numCount()];
        Object[] strs = new Object[f.strCount()];
        double[] values = {a, b, c};
        for (int var = 0; var < 3; var++) {
            if (f.numSlot(var) >= 0) {
                nums[f.numSlot(var)] = values[var];
            }
        }
        if (f.strSlot(3) >= 0) {
            strs[f.strSlot(3)] = s;
        }
        return f.test(nums, strs);
    }

    @Test
    void andBindsTighterThanOr() {
        // a|b&c must be a|(b&c), not (a|b)&c: with a true, b true, c false they differ.
        assertTrue(eval("a == 1 | b == 1 & c == 1", 1, 1, 0, ""));
        assertFalse(eval("(a == 1 | b == 1) & c == 1", 1, 1, 0, ""));
    }

    @Test
    void notBindsTightest() {
        // !a&b must be (!a)&b, not !(a&b): with a false, b false they differ.
        assertFalse(eval("!a == 1 & b == 1", 0, 0, 0, ""));
        assertTrue(eval("!(a == 1 & b == 1)", 0, 0, 0, ""));
        assertTrue(eval("!!a == 1", 1, 0, 0, ""));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "a < 2      | 1.5  | true",
            "a < 2      | 2    | false",
            "a <= 2     | 2    | true",
            "a > -1e3   | -999 | true",
            "a >= .5    | 0.5  | true",
            "a == 2.    | 2    | true",
            "a != 2     | 2    | false",
            "a>=1E2     | 100  | true",
            "  a  <  +3 | 2    | true",
    })
    void comparesNumbers(String expr, double a, boolean expected) {
        assertEquals(expected, eval(expr, a, 0, 0, ""));
    }

    /** Stata order: every number < . < .a < ... < .z. */
    @Test
    void missingValuesSortAboveNumbersInStataOrder() {
        double dot = DtaFilter.missingKey(0);
        double dotA = DtaFilter.missingKey(1);
        double dotZ = DtaFilter.missingKey(26);
        assertTrue(eval("a > 1e300", dot, 0, 0, ""));
        assertTrue(eval("a < .", 8.98e307, 0, 0, ""));
        assertFalse(eval("a < .", dot, 0, 0, ""));
        assertTrue(eval("a == .", dot, 0, 0, ""));
        assertFalse(eval("a == .", dotA, 0, 0, ""));
        assertTrue(eval("a > .", dotA, 0, 0, ""));
        assertTrue(eval("a < .z", dotA, 0, 0, ""));
        assertTrue(eval("a == .z", dotZ, 0, 0, ""));
        assertTrue(eval("a >= .", dotZ, 0, 0, ""));
    }

    @Test
    void comparesStringsExactly() {
        assertTrue(eval("s == \"ab\"", 0, 0, 0, "ab"));
        assertFalse(eval("s == \"ab\"", 0, 0, 0, "ab "));
        assertFalse(eval("s == \"AB\"", 0, 0, 0, "ab"));
        assertTrue(eval("s != \"ab\"", 0, 0, 0, ""));
        assertTrue(eval("s == \"\"", 0, 0, 0, ""));
        assertTrue(eval("s == \"a\\\"b\"", 0, 0, 0, "a\"b"));    // \" escape
        assertTrue(eval("s == \"a\\\\\"", 0, 0, 0, "a\\"));      // \\ escape
        assertTrue(eval("s == \"a&b|!(\"", 0, 0, 0, "a&b|!(")); // operators inside a string
    }

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
            "''              ; 1",
            "a               ; 2",
            "a >             ; 4",
            "a > b           ; 5",
            "a = 1           ; 3",
            "a > 1 &         ; 8",
            "a > 1 b > 2     ; 7",
            "(a > 1          ; 7",
            "a > 1)          ; 6",
            "a > 1.2.3       ; 5",
            "a > 1e999       ; 5",
            "s == \"abc      ; 6",
            "a > 1 || b > 2  ; 8",
            "1 < a           ; 1",
    })
    void reportsSyntaxErrorsWithPosition(String expr, int position) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> DtaFilter.parse(expr));
        assertTrue(e.getMessage().contains("at position " + position + " "), e.getMessage());
    }

    @Test
    void bindingChecksNamesAndTypes() {
        assertThrows(IllegalArgumentException.class, () -> DtaFilter.parse("zz > 1").bind(NAMES, TYPES));
        assertThrows(IllegalArgumentException.class, () -> DtaFilter.parse("a == \"x\"").bind(NAMES, TYPES));
        assertThrows(IllegalArgumentException.class, () -> DtaFilter.parse("s == 1").bind(NAMES, TYPES));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> DtaFilter.parse("s < \"x\"").bind(NAMES, TYPES));
        assertTrue(e.getMessage().contains("only == and !="), e.getMessage());
    }

    @Test
    void reusesOneSlotPerVariable() {
        DtaFilter.Bound f = DtaFilter.parse("a > 1 & a < 5 | s == \"x\" | s == \"y\"").bind(NAMES, TYPES);
        assertEquals(1, f.numCount());
        assertEquals(1, f.strCount());
        assertEquals(-1, f.numSlot(1));
        assertFalse(f.usesStrL());
    }
}
