package io.github.huapeng01016.stata4j;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.*;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class StataReaderSubsetTest {

    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = StataReaderSubsetTest.class.getResourceAsStream("/fixtures/" + name)) {
            return in.readAllBytes();
        }
    }

    /** A writer-made file with binary strLs, which no fixture has. */
    private static byte[] writerFile() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (StataWriter w = new StataWriter(out, 119, ByteOrder.BIG_ENDIAN)) {
            w.addVariable("n", StataVarType.LONG).addVariable("blob", StataVarType.STRL)
                    .addVariable("s", StataVarType.str(4)).addVariable("x", StataVarType.DOUBLE);
            for (int i = 0; i < 6; i++) {
                w.addObservation(i, i % 2 == 0 ? new byte[] {(byte) i, 0, 1} : "t" + i, "s" + i, i * 0.5);
            }
            w.write();
        }
        return out.toByteArray();
    }

    static Stream<Arguments> files() throws IOException {
        List<Arguments> files = new ArrayList<>();
        for (String name : List.of("v114.dta", "v117.dta", "v118.dta", "v119.dta", "v118_big.dta",
                "v120_alias.dta", "v121_alias.dta")) {
            files.add(Arguments.of(name, fixture(name)));
        }
        files.add(Arguments.of("legacy 113", LegacyDtaBuilder.build(113, ByteOrder.LITTLE_ENDIAN)));
        files.add(Arguments.of("legacy 115 BE", LegacyDtaBuilder.build(115, ByteOrder.BIG_ENDIAN)));
        files.add(Arguments.of("writer 119 BE", writerFile()));
        return files.stream();
    }

    private static StataReader read(byte[] bytes, List<String> vars, long from, long to) throws Exception {
        StataReader r = new StataReader(new ByteArrayInputStream(bytes));
        if (vars != null) {
            r.selectVariables(vars);
        }
        r.selectObservations(from, to);
        r.read();
        return r;
    }

    /** Variable selections to try on a file with these names. */
    private static List<List<String>> selections(List<String> names) {
        List<List<String>> result = new ArrayList<>();
        result.add(null);                                            // all
        result.add(List.of());                                       // none
        result.add(List.of(names.get(names.size() - 1), names.get(0))); // reversed pair
        List<String> everyOther = new ArrayList<>();
        for (int i = names.size() - 1; i >= 0; i -= 2) {
            everyOther.add(names.get(i));
        }
        result.add(everyOther);
        return result;
    }

    /** A subset read must equal the same projection of a full read, for every file and selection. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("files")
    void subsetEqualsProjectionOfFullRead(String label, byte[] bytes) throws Exception {
        StataReader full = new StataReader(new ByteArrayInputStream(bytes));
        full.read();
        int n = full.getNumObs();
        long[][] ranges = {{0, Long.MAX_VALUE}, {0, 1}, {1, n}, {n - 1, n}, {1, 2}, {n, n + 5}, {0, 0}, {n + 3, n + 9}};

        for (List<String> vars : selections(full.getVarNames())) {
            List<String> names = vars == null ? full.getVarNames() : vars;
            int[] cols = names.stream().mapToInt(full.getVarNames()::indexOf).toArray();
            for (long[] range : ranges) {
                String what = label + " vars=" + vars + " range=" + Arrays.toString(range);
                StataReader sub = read(bytes, vars, range[0], range[1]);

                assertEquals(full.getNumVars(), sub.getTotalNumVars(), what);
                assertEquals(n, sub.getTotalNumObs(), what);
                assertEquals(names, sub.getVarNames(), what);
                assertEquals(names.size(), sub.getNumVars(), what);
                assertEquals(pick(full.getVarTypes(), cols), sub.getVarTypes(), what);
                assertEquals(pick(full.getVarLabels(), cols), sub.getVarLabels(), what);
                assertEquals(pick(full.getFmtList(), cols), sub.getFmtList(), what);
                assertEquals(pick(full.getValueLabelNames(), cols), sub.getValueLabelNames(), what);
                assertEquals(full.getValueLabels(), sub.getValueLabels(), what);
                assertEquals(full.getDatasetLabel(), sub.getDatasetLabel(), what);

                int first = (int) Math.min(range[0], n);
                int end = (int) Math.min(range[1], n);
                assertEquals(end - first, sub.getNumObs(), what);
                for (int obs = first; obs < end; obs++) {
                    Map<String, Object> expected = full.getObservation(obs);
                    Map<String, Object> actual = sub.getObservation(obs - first);
                    assertEquals(names, List.copyOf(actual.keySet()), what + " obs " + obs);
                    Object[] want = names.stream().map(expected::get).toArray();
                    assertArrayEquals(want, actual.values().toArray(), what + " obs " + obs);
                }
            }
        }
    }

    private static <T> List<T> pick(List<T> all, int[] cols) {
        List<T> out = new ArrayList<>();
        for (int c : cols) {
            out.add(all.get(c));
        }
        return out;
    }

    /**
     * pandas links equal strLs: in v117.dta observation 3's "notes" cell refers to the
     * GSO of observation 1. Reading only observation 3 must still resolve it.
     */
    @Test
    void resolvesStrLDefinedOutsideTheRange() throws Exception {
        byte[] bytes = fixture("v117.dta");
        String s = new String(bytes, StandardCharsets.ISO_8859_1);
        int data = s.indexOf("<data>") + "<data>".length();
        int row = (s.indexOf("</data>") - data) / 3;
        assertArrayEquals(Arrays.copyOfRange(bytes, data + row - 8, data + row),
                Arrays.copyOfRange(bytes, data + 3 * row - 8, data + 3 * row), "fixture should cross-link");

        StataReader r = read(bytes, List.of("notes"), 2, 3);
        assertEquals(Map.of("notes", "first note"), r.getObservation(0));
    }

    @Test
    void skipsRowsOutsideTheRangeWithoutReadingThem() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int n = 100_000;
        try (StataWriter w = new StataWriter(out, 119, ByteOrder.LITTLE_ENDIAN)) {
            w.addVariable("x", StataVarType.DOUBLE);
            for (int i = 0; i < n; i++) {
                w.addObservation((double) i);
            }
            w.write();
        }
        byte[] bytes = out.toByteArray();

        long[] readBytes = {0};
        InputStream counting = new FilterInputStream(new ByteArrayInputStream(bytes)) {
            @Override
            public int read() throws IOException {
                int b = super.read();
                readBytes[0] += b < 0 ? 0 : 1;
                return b;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                int got = super.read(b, off, len);
                readBytes[0] += Math.max(got, 0);
                return got;
            }
        };
        try (StataReader r = new StataReader(counting)) {
            r.selectObservations(n - 10, n).read();
            assertEquals(10, r.getNumObs());
            assertEquals((double) (n - 10), r.getValue(0, "x"));
        }
        assertTrue(readBytes[0] < bytes.length / 10,
                "read " + readBytes[0] + " of " + bytes.length + " bytes; skipped rows should not be read");
    }

    @Test
    void rangeClampsToTheDataset() throws Exception {
        StataReader r = read(fixture("v118.dta"), null, 2, 1000);
        assertEquals(1, r.getNumObs());
        assertEquals(3, r.getTotalNumObs());
        assertNull(r.getValue(0, "b")); // observation 3 is all missing
        assertThrows(IndexOutOfBoundsException.class, () -> r.getObservation(1));
    }

    @Test
    void rejectsBadSelections() throws Exception {
        StataReader r = new StataReader(new ByteArrayInputStream(fixture("v118.dta")));
        assertThrows(IllegalArgumentException.class, () -> r.selectVariables("b", "b"));
        assertThrows(IllegalArgumentException.class, () -> r.selectObservations(-1, 2));
        assertThrows(IllegalArgumentException.class, () -> r.selectObservations(3, 2));
        assertThrows(NullPointerException.class, () -> r.selectVariables("b", null));

        r.selectVariables("b", "nope");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, r::read);
        assertTrue(e.getMessage().contains("nope"), e.getMessage());

        StataReader done = read(fixture("v118.dta"), null, 0, 1);
        assertThrows(IllegalStateException.class, () -> done.selectVariables("b"));
        assertThrows(IllegalStateException.class, () -> done.selectObservations(0, 1));
    }

    /** More than Integer.MAX_VALUE observations can't be read at once, and the error says what to do. */
    @Test
    void hugeDatasetNeedsARange() throws Exception {
        byte[] bytes = fixture("v118.dta");
        int nField = new String(bytes, StandardCharsets.ISO_8859_1).indexOf("<N>") + "<N>".length();
        long huge = Integer.MAX_VALUE + 1L;
        for (int i = 0; i < 8; i++) {
            bytes[nField + i] = (byte) (huge >>> (8 * i)); // little-endian
        }
        StataFormatException e = assertThrows(StataFormatException.class,
                () -> new StataReader(new ByteArrayInputStream(bytes)).read());
        assertTrue(e.getMessage().contains("selectObservations"), e.getMessage());
    }
}
