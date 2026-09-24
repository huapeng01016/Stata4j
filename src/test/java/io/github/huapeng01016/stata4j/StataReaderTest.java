package io.github.huapeng01016.stata4j;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.*;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StataReaderTest {

    private static final String LONG = "x".repeat(300);

    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = StataReaderTest.class.getResourceAsStream("/fixtures/" + name)) {
            assertNotNull(in, "missing fixture " + name);
            return in.readAllBytes();
        }
    }

    private static StataReader read(byte[] bytes) throws IOException, StataFormatException {
        StataReader reader = new StataReader(new ByteArrayInputStream(bytes));
        reader.read();
        return reader;
    }

    /** Files written by pandas from the frame in src/test/resources/fixtures/make_fixtures.py. */
    @ParameterizedTest
    @CsvSource({
            "v114.dta,     114, false, false",
            "v117.dta,     117, false, true",
            "v118.dta,     118, false, true",
            "v119.dta,     119, false, true",
            "v118_big.dta, 118, true,  false",
    })
    void readsPandasFixture(String file, String release, boolean bigEndian, boolean hasStrL) throws Exception {
        int rel = Integer.parseInt(release);
        try (StataReader r = read(fixture(file))) {
            assertEquals(release, r.getFormat());
            assertEquals(bigEndian ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN, r.getByteOrder());
            assertEquals("Stata4j fixture", r.getDatasetLabel());
            assertEquals(17, r.getTimestamp().length(), r.getTimestamp());
            assertEquals(3, r.getNumObs());

            List<String> names = rel == 114
                    ? List.of("b", "i", "l", "f", "d", "s", "grade")
                    : List.of("b", "i", "l", "f", "d", "s", "grade", "longs", "notes");
            assertEquals(names, r.getVarNames());
            assertEquals(names.size(), r.getNumVars());
            assertEquals("label of grade", r.getVarLabels().get(6));

            List<StataVarType> types = r.getVarTypes();
            assertEquals(List.of(StataVarType.BYTE, StataVarType.INT, StataVarType.LONG, StataVarType.FLOAT,
                    StataVarType.DOUBLE, StataVarType.str(5), StataVarType.BYTE), types.subList(0, 7));
            if (rel >= 117) {
                assertEquals(StataVarType.str(300), types.get(7));
                assertEquals(hasStrL ? StataVarType.STRL : StataVarType.str(10), types.get(8));
            }

            Map<String, Object> o1 = r.getObservation(0);
            assertEquals((byte) 1, o1.get("b"));
            assertEquals((short) 1000, o1.get("i"));
            assertEquals(100000, o1.get("l"));
            assertEquals(1.5f, o1.get("f"));
            assertEquals(3.125, o1.get("d"));
            assertEquals("Alice", o1.get("s"));
            assertEquals((byte) 0, o1.get("grade"));

            Map<String, Object> o2 = r.getObservation(1);
            assertEquals((byte) -5, o2.get("b"));
            assertEquals((short) -1000, o2.get("i"));
            assertEquals(-100000, o2.get("l"));
            assertEquals(-2.25f, o2.get("f"));
            assertEquals(-1e300, o2.get("d"));
            assertEquals("Bob", o2.get("s"));
            assertEquals((byte) 1, o2.get("grade"));

            Map<String, Object> o3 = r.getObservation(2);
            for (String v : List.of("b", "i", "l", "f", "d")) {
                assertTrue(o3.containsKey(v));
                assertNull(o3.get(v), v);
            }
            assertEquals(rel >= 118 ? "Zoë" : "", o3.get("s"));

            if (rel >= 117) {
                assertEquals(List.of(LONG, "short", ""),
                        r.getData().stream().map(o -> o.get("longs")).toList());
                assertEquals(List.of("first note", "", "first note"),
                        r.getData().stream().map(o -> o.get("notes")).toList());
            }

            assertEquals("grade", r.getValueLabelNames().get(6));
            assertEquals("", r.getValueLabelNames().get(0));
            assertEquals(Map.of("grade", Map.of(0, "low", 1, "high")), r.getValueLabels());

            assertEquals((byte) 1, r.getValue(0, 0));
            assertEquals("Bob", r.getValue(1, "s"));
        }
    }

    /** Synthetic 120/121 files; see write_alias in make_fixtures.py. */
    @ParameterizedTest
    @CsvSource({"v120_alias.dta, 120", "v121_alias.dta, 121"})
    void readsAliasFixture(String file, String release) throws Exception {
        try (StataReader r = read(fixture(file))) {
            assertEquals(release, r.getFormat());
            assertEquals(List.of("id", "al", "name", "notes"), r.getVarNames());
            assertEquals(List.of(StataVarType.BYTE, StataVarType.ALIAS, StataVarType.str(3), StataVarType.STRL),
                    r.getVarTypes());
            assertEquals(Arrays.asList((byte) 1, null, "a", "n1"), Arrays.asList(r.getObservation(0).values().toArray()));
            assertEquals(Arrays.asList((byte) 2, null, "bb", ""), Arrays.asList(r.getObservation(1).values().toArray()));
            assertEquals(Arrays.asList((byte) 3, null, "ccc", "n1"), Arrays.asList(r.getObservation(2).values().toArray()));
            assertTrue(r.getObservation(0).containsKey("al"));
        }
    }

    @Test
    void rejectsAliasBeforeFormat120() throws IOException {
        byte[] bytes = fixture("v118.dta");
        int types = new String(bytes, StandardCharsets.ISO_8859_1).indexOf("<variable_types>") + "<variable_types>".length();
        bytes[types] = (byte) 0xF5; // 65525 little-endian
        bytes[types + 1] = (byte) 0xFF;
        StataFormatException e = assertThrows(StataFormatException.class, () -> read(bytes));
        assertTrue(e.getMessage().contains("Alias"), e.getMessage());
    }

    @ParameterizedTest
    @CsvSource({"113, LITTLE_ENDIAN", "115, LITTLE_ENDIAN", "115, BIG_ENDIAN"})
    void readsLegacyFile(int release, String order) throws Exception {
        ByteOrder byteOrder = order.equals("BIG_ENDIAN") ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
        try (StataReader r = read(LegacyDtaBuilder.build(release, byteOrder))) {
            assertEquals(Integer.toString(release), r.getFormat());
            assertEquals(byteOrder, r.getByteOrder());
            assertEquals("Legacy fixture", r.getDatasetLabel());
            assertEquals("24 Sep 2026 09:00", r.getTimestamp());
            assertEquals(List.of("id", "code", "big", "x", "y", "name", "grade"), r.getVarNames());
            assertEquals(StataVarType.str(8), r.getVarTypes().get(5));
            assertEquals("label of x", r.getVarLabels().get(3));
            assertEquals("%9.0g", r.getFmtList().get(0));

            assertEquals(Arrays.asList((byte) 1, (short) 1000, 100000, 1.5f, 3.125, "Alice", (byte) 0),
                    List.copyOf(r.getObservation(0).values()));
            assertEquals(Arrays.asList((byte) 100, (short) 32740, 2147483620, -2.25f, -1e300, "Zoë", (byte) 1),
                    List.copyOf(r.getObservation(1).values()));
            assertEquals(Arrays.asList(null, null, null, null, null, "12345678", null),
                    Arrays.asList(r.getObservation(2).values().toArray()));

            assertEquals(List.of("", "", "", "", "", "", "gradelbl"), r.getValueLabelNames());
            assertEquals(Map.of("gradelbl", Map.of(0, "low", 1, "high")), r.getValueLabels());
        }
    }

    @Test
    void rejectsNonStataInput() {
        assertThrows(StataFormatException.class, () -> read("999".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void rejectsUnsupportedLegacyRelease() {
        byte[] bytes = LegacyDtaBuilder.build(115, ByteOrder.LITTLE_ENDIAN);
        bytes[0] = 112;
        StataFormatException e = assertThrows(StataFormatException.class, () -> read(bytes));
        assertTrue(e.getMessage().contains("112"), e.getMessage());
    }

    @Test
    void rejectsUnsupportedTaggedRelease() throws IOException {
        byte[] bytes = fixture("v119.dta");
        String header = "<stata_dta><header><release>";
        bytes[header.length() + 2] = '0'; // 119 -> 110
        StataFormatException e = assertThrows(StataFormatException.class, () -> read(bytes));
        assertTrue(e.getMessage().contains("110"), e.getMessage());
    }

    @Test
    void rejectsMisplacedTag() throws IOException {
        byte[] bytes = fixture("v117.dta");
        String s = new String(bytes, StandardCharsets.ISO_8859_1);
        int at = s.indexOf("<varnames>") + "<varname".length();
        bytes[at] = 'z';
        StataFormatException e = assertThrows(StataFormatException.class, () -> read(bytes));
        assertTrue(e.getMessage().contains("<varnames>"), e.getMessage());
    }

    @Test
    void truncatedFilesThrowEof() throws IOException {
        byte[] tagged = fixture("v118.dta");
        assertThrows(EOFException.class, () -> read(Arrays.copyOf(tagged, tagged.length / 2)));
        byte[] legacy = LegacyDtaBuilder.build(115, ByteOrder.LITTLE_ENDIAN);
        assertThrows(EOFException.class, () -> read(Arrays.copyOf(legacy, 200)));
        assertThrows(EOFException.class, () -> read(new byte[0]));
    }

    @Test
    void readOnlyOnce() throws Exception {
        try (StataReader r = read(fixture("v118.dta"))) {
            assertThrows(IllegalStateException.class, r::read);
        }
    }

    @Test
    void accessorsValidateArguments() throws Exception {
        try (StataReader r = read(fixture("v118.dta"))) {
            assertThrows(IndexOutOfBoundsException.class, () -> r.getObservation(-1));
            assertThrows(IndexOutOfBoundsException.class, () -> r.getObservation(3));
            assertThrows(IndexOutOfBoundsException.class, () -> r.getValue(0, 9));
            assertThrows(IllegalArgumentException.class, () -> r.getValue(0, "nonexistent"));
            assertThrows(UnsupportedOperationException.class, () -> r.getObservation(0).put("b", null));
        }
    }

    @Test
    void gettersBeforeReadAreEmpty() {
        StataReader r = new StataReader(new ByteArrayInputStream(new byte[0]));
        assertNull(r.getFormat());
        assertTrue(r.getVarNames().isEmpty());
        assertTrue(r.getData().isEmpty());
        assertTrue(r.getValueLabels().isEmpty());
    }

    @Test
    void missingFileThrows() {
        assertThrows(FileNotFoundException.class, () -> new StataReader("nonexistent.dta"));
    }
}
