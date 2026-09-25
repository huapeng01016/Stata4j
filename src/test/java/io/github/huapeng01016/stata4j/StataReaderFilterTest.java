package io.github.huapeng01016.stata4j;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.*;
import java.nio.ByteOrder;
import java.util.*;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class StataReaderFilterTest {

    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = StataReaderFilterTest.class.getResourceAsStream("/fixtures/" + name)) {
            return in.readAllBytes();
        }
    }

    private static StataReader filtered(byte[] bytes, String filter) throws Exception {
        StataReader r = new StataReader(new ByteArrayInputStream(bytes));
        r.filterObservations(filter).read();
        return r;
    }

    private static List<Long> indexes(StataReader r) {
        List<Long> out = new ArrayList<>();
        for (int i = 0; i < r.getNumObs(); i++) {
            out.add(r.getObservationIndex(i));
        }
        return out;
    }

    /**
     * Exact Stata missing-value semantics, on the legacy file that stores extended
     * missing values. Its three observations, per variable:
     * id 1, 100, .a | code 1000, 32740, .z | big 100000, 2147483620, . |
     * x 1.5, -2.25, .a | y 3.125, -1e300, .z | grade 0, 1, .
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "id > 5          | 1,2",   // missing is greater than any number
            "id < .          | 0,1",   // the Stata idiom for non-missing
            "id == .         | ''",    // .a is not .
            "id == .a        | 2",
            "id > .          | 2",     // .a > .
            "id < .b         | 0,1,2",
            "code == .z      | 2",
            "code < .z       | 0,1",
            "big == .        | 2",
            "big > .         | ''",
            "big >= 2147483620 | 1,2",
            "x == .a         | 2",
            "x <= -2.25      | 1",
            "y == .z         | 2",
            "y > .y          | 2",
            "grade == .      | 2",
            "grade >= .      | 2",
            "id <= 100 & grade < . | 0,1",
            "!(id < .)       | 2",
    })
    void followsStataMissingValueRules(String filter, String expected) throws Exception {
        for (ByteOrder order : List.of(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
            StataReader r = filtered(LegacyDtaBuilder.build(115, order), filter);
            List<Long> want = expected.isEmpty() ? List.of()
                    : Arrays.stream(expected.split(",")).map(Long::valueOf).toList();
            assertEquals(want, indexes(r), filter + " " + order);
        }
    }

    // A larger writer-made dataset, checked against the same rules written as Java predicates.

    private static final int ROWS = 300;
    private static final String[] WORDS = {"a", "b", "", "Zoë"};

    private static Double x(int i) {
        return i % 7 == 0 ? null : (i % 11) - 5 + (i % 3) * 0.5;
    }

    private static Integer n(int i) {
        return i % 5 == 0 ? null : i % 9;
    }

    private static String s(int i) {
        return WORDS[i % 4];
    }

    private static String t(int i) {
        return i % 6 == 0 ? "" : "long " + WORDS[(i / 2) % 4];
    }

    private static byte[] dataset(ByteOrder order) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (StataWriter w = new StataWriter(out, 119, order)) {
            w.addVariable("x", StataVarType.DOUBLE).addVariable("n", StataVarType.INT)
                    .addVariable("s", StataVarType.str(5)).addVariable("t", StataVarType.STRL)
                    .addVariable("id", StataVarType.LONG);
            for (int i = 0; i < ROWS; i++) {
                w.addObservation(x(i), n(i), s(i), t(i), i);
            }
            w.write();
        }
        return out.toByteArray();
    }

    /** Stata order with only system missing present: null (.) is above every number. */
    private static int cmp(Number v, double c) {
        return v == null ? 1 : Double.compare(v.doubleValue(), c);
    }

    private static Map<String, Predicate<Integer>> expectations() {
        Map<String, Predicate<Integer>> m = new LinkedHashMap<>();
        m.put("x > 0", i -> cmp(x(i), 0) > 0);
        m.put("x <= 2.5", i -> cmp(x(i), 2.5) <= 0);
        m.put("x < .", i -> x(i) != null);
        m.put("x == .", i -> x(i) == null);
        m.put("n == 3", i -> cmp(n(i), 3) == 0);
        m.put("n != 3", i -> cmp(n(i), 3) != 0);
        m.put("n >= .", i -> n(i) == null);
        m.put("x < . & n >= 0", i -> x(i) != null && cmp(n(i), 0) >= 0);
        m.put("!(x > 0) | s == \"b\"", i -> !(cmp(x(i), 0) > 0) || s(i).equals("b"));
        m.put("s == \"a\" & (n < 2 | x >= 1)", i -> s(i).equals("a") && (cmp(n(i), 2) < 0 || cmp(x(i), 1) >= 0));
        m.put("s == \"Zoë\"", i -> s(i).equals("Zoë"));
        m.put("s == \"\"", i -> s(i).isEmpty());
        m.put("t == \"long b\"", i -> t(i).equals("long b"));
        m.put("t != \"\" & !(n > 5)", i -> !t(i).isEmpty() && !(cmp(n(i), 5) > 0));
        m.put("t == \"\" | x > 3", i -> t(i).isEmpty() || cmp(x(i), 3) > 0);
        m.put("!!(x == 1)", i -> cmp(x(i), 1) == 0);
        m.put("id >= 100 & id < 110", i -> i >= 100 && i < 110);
        return m;
    }

    @Test
    void matchesJavaPredicates() throws Exception {
        for (ByteOrder order : List.of(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
            byte[] bytes = dataset(order);
            for (Map.Entry<String, Predicate<Integer>> e : expectations().entrySet()) {
                List<Long> want = new ArrayList<>();
                for (int i = 0; i < ROWS; i++) {
                    if (e.getValue().test(i)) {
                        want.add((long) i);
                    }
                }
                StataReader r = filtered(bytes, e.getKey());
                assertEquals(want, indexes(r), e.getKey() + " " + order);
                assertFalse(want.isEmpty() || want.size() == ROWS, "expression should split the rows: " + e.getKey());
                for (int k = 0; k < r.getNumObs(); k++) {
                    int i = (int) r.getObservationIndex(k);
                    assertEquals(i, r.getValue(k, "id"), e.getKey());
                    assertEquals(t(i), r.getValue(k, "t"), e.getKey());
                }
            }
        }
    }

    @Test
    void combinesWithRangeAndVariableSelection() throws Exception {
        byte[] bytes = dataset(ByteOrder.LITTLE_ENDIAN);
        StataReader r = new StataReader(new ByteArrayInputStream(bytes));
        r.selectVariables("t", "id").selectObservations(50, 150).filterObservations("n == 3 & s == \"b\"").read();
        List<Long> want = new ArrayList<>();
        for (int i = 50; i < 150; i++) {
            if (Objects.equals(n(i), 3) && s(i).equals("b")) {
                want.add((long) i);
            }
        }
        assertFalse(want.isEmpty());
        assertEquals(want, indexes(r));
        assertEquals(List.of("t", "id"), r.getVarNames());   // filter variables need not be selected
        for (int k = 0; k < r.getNumObs(); k++) {
            assertEquals(List.of("t", "id"), List.copyOf(r.getObservation(k).keySet()));
            assertEquals(t((int) r.getObservationIndex(k)), r.getValue(k, "t"));
        }
        assertEquals(ROWS, r.getTotalNumObs());
    }

    /** A strL filter is evaluated after <strls>, including links to a GSO defined by another observation. */
    @Test
    void filtersOnStrLAcrossLinkedObservations() throws Exception {
        byte[] bytes = fixture("v117.dta"); // notes: "first note", "", "first note" (obs 3 links to obs 1's GSO)
        assertEquals(List.of(0L, 2L), indexes(filtered(bytes, "notes == \"first note\"")));
        assertEquals(List.of(1L), indexes(filtered(bytes, "notes == \"\"")));

        StataReader r = new StataReader(new ByteArrayInputStream(bytes));
        r.selectVariables("s").selectObservations(2, 3).filterObservations("notes != \"\"").read();
        assertEquals(List.of(2L), indexes(r));
        assertEquals(Map.of("s", ""), r.getObservation(0));
    }

    @Test
    void binaryStrLNeverEqualsALiteral() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (StataWriter w = new StataWriter(out, 119, ByteOrder.LITTLE_ENDIAN)) {
            w.addVariable("b", StataVarType.STRL);
            w.addObservation((Object) "ab".getBytes());
            w.addObservation("ab");
            w.write();
        }
        assertEquals(List.of(1L), indexes(filtered(out.toByteArray(), "b == \"ab\"")));
        assertEquals(List.of(0L), indexes(filtered(out.toByteArray(), "b != \"ab\"")));
    }

    @Test
    void worksOnEveryFormat() throws Exception {
        for (String name : List.of("v114.dta", "v117.dta", "v118.dta", "v119.dta", "v118_big.dta")) {
            assertEquals(List.of(1L, 2L), indexes(filtered(fixture(name), "b < 0 | b >= .")), name);
            assertEquals(List.of(0L), indexes(filtered(fixture(name), "s == \"Alice\"")), name);
        }
        for (String name : List.of("v120_alias.dta", "v121_alias.dta")) {
            assertEquals(List.of(1L, 2L), indexes(filtered(fixture(name), "id > 1 & name != \"a\"")), name);
        }
    }

    @Test
    void reportsBindingErrorsAtRead() throws Exception {
        byte[] bytes = fixture("v120_alias.dta");
        for (String bad : List.of("nope > 1", "id == \"1\"", "name > 1", "name < \"b\"", "al == 0")) {
            StataReader r = new StataReader(new ByteArrayInputStream(bytes)).filterObservations(bad);
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, r::read, bad);
            assertTrue(e.getMessage().contains("filter: " + bad), e.getMessage());
        }
    }

    @Test
    void syntaxErrorsAreReportedImmediately() {
        StataReader r = new StataReader(new ByteArrayInputStream(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> r.filterObservations("x >"));
        assertThrows(NullPointerException.class, () -> r.filterObservations(null));
    }

    @Test
    void filterMustBeSetBeforeRead() throws Exception {
        StataReader r = filtered(fixture("v118.dta"), "b > 0 & b < ."); // b > 0 alone also matches missing b
        assertThrows(IllegalStateException.class, () -> r.filterObservations("b > 0"));
        assertEquals(List.of(0L), indexes(r));
        assertThrows(IndexOutOfBoundsException.class, () -> r.getObservationIndex(1));
    }

    @Test
    void observationIndexWithoutFilterFollowsTheRange() throws Exception {
        StataReader r = new StataReader(new ByteArrayInputStream(fixture("v118.dta")));
        r.selectObservations(1, 3).read();
        assertEquals(List.of(1L, 2L), indexes(r));
    }
}
