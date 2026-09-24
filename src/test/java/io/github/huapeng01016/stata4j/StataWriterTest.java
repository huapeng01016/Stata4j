package io.github.huapeng01016.stata4j;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.*;
import java.math.BigInteger;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class StataWriterTest {

    private static final String LONG = "x".repeat(300);
    private static final byte[] BINARY = {0, 1, 2, (byte) 0xFF, 0};

    private static ByteOrder order(String name) {
        return name.equals("BIG_ENDIAN") ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
    }

    /** Builds the sample dataset used by most tests. */
    private static byte[] sample(int format, ByteOrder order) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (StataWriter w = new StataWriter(bytes, format, order)) {
            w.setDatasetLabel("Written by Stata4j").setTimestamp(LocalDateTime.of(2032, 7, 4, 4, 23));
            w.addVariable("b", StataVarType.BYTE, "a byte")
                    .addVariable("i", StataVarType.INT)
                    .addVariable("l", StataVarType.LONG)
                    .addVariable("f", StataVarType.FLOAT)
                    .addVariable("d", StataVarType.DOUBLE)
                    .addVariable("s", StataVarType.str(6), "Zo\u00eb label")
                    .addVariable("longs", StataVarType.str(300))
                    .addVariable("notes", StataVarType.STRL)
                    .addVariable("grade", StataVarType.BYTE);
            w.setValueLabel("grade", "gradelbl").defineValueLabel("gradelbl", Map.of(1, "high", 0, "low"));
            w.setFormat("d", "%9.2f");
            w.addObservation(1, 1000, 100000, 1.5f, 3.125, "Alice", LONG, "first note", 0);
            w.addObservation(-127, -32767, -2147483647, -2.25, -1e300, "Zo\u00eb", "short", "", 1);
            w.addObservation(100, 32740, 2147483620, null, Double.NaN, null, null, BINARY, null);
            w.write();
        }
        return bytes.toByteArray();
    }

    private static StataReader read(byte[] bytes) throws IOException, StataFormatException {
        StataReader r = new StataReader(new ByteArrayInputStream(bytes));
        r.read();
        return r;
    }

    @ParameterizedTest
    @CsvSource({
            "119, LITTLE_ENDIAN", "119, BIG_ENDIAN",
            "120, LITTLE_ENDIAN", "120, BIG_ENDIAN",
            "121, LITTLE_ENDIAN", "121, BIG_ENDIAN",
    })
    void roundTrips(int format, String byteOrder) throws Exception {
        ByteOrder order = order(byteOrder);
        try (StataReader r = read(sample(format, order))) {
            assertEquals(Integer.toString(format), r.getFormat());
            assertEquals(order, r.getByteOrder());
            assertEquals("Written by Stata4j", r.getDatasetLabel());
            assertEquals("04 Jul 2032 04:23", r.getTimestamp());
            assertEquals(List.of("b", "i", "l", "f", "d", "s", "longs", "notes", "grade"), r.getVarNames());
            assertEquals(List.of(StataVarType.BYTE, StataVarType.INT, StataVarType.LONG, StataVarType.FLOAT,
                    StataVarType.DOUBLE, StataVarType.str(6), StataVarType.str(300), StataVarType.STRL,
                    StataVarType.BYTE), r.getVarTypes());
            assertEquals(List.of("a byte", "", "", "", "", "Zo\u00eb label", "", "", ""), r.getVarLabels());
            assertEquals(List.of("%8.0g", "%8.0g", "%12.0g", "%9.0g", "%9.2f", "%9s", "%300s", "%9s", "%8.0g"),
                    r.getFmtList());
            assertEquals("gradelbl", r.getValueLabelNames().get(8));
            assertEquals(Map.of("gradelbl", Map.of(0, "low", 1, "high")), r.getValueLabels());

            assertEquals(Arrays.asList((byte) 1, (short) 1000, 100000, 1.5f, 3.125, "Alice", LONG, "first note", (byte) 0),
                    List.copyOf(r.getObservation(0).values()));
            // Smallest and largest non-missing values must not come back as missing.
            assertEquals(Arrays.asList((byte) -127, (short) -32767, -2147483647, -2.25f, -1e300, "Zo\u00eb", "short", "", (byte) 1),
                    List.copyOf(r.getObservation(1).values()));
            Map<String, Object> o3 = r.getObservation(2);
            assertEquals((byte) 100, o3.get("b"));
            assertEquals((short) 32740, o3.get("i"));
            assertEquals(2147483620, o3.get("l"));
            assertNull(o3.get("f"));
            assertNull(o3.get("d"));
            assertEquals("", o3.get("s"));
            assertEquals("", o3.get("longs"));
            assertArrayEquals(BINARY, (byte[]) o3.get("notes"));
            assertNull(o3.get("grade"));
        }
    }

    /** Every <map> entry must hold the offset of its section; entry 14 is the file length. */
    @ParameterizedTest
    @CsvSource({"119, LITTLE_ENDIAN", "120, BIG_ENDIAN", "121, BIG_ENDIAN"})
    void mapHoldsSectionOffsets(int format, String byteOrder) throws IOException {
        ByteOrder order = order(byteOrder);
        byte[] bytes = sample(format, order);
        String s = new String(bytes, StandardCharsets.ISO_8859_1);
        String[] tags = {"<stata_dta>", "<map>", "<variable_types>", "<varnames>", "<sortlist>", "<formats>",
                "<value_label_names>", "<variable_labels>", "<characteristics>", "<data>", "<strls>",
                "<value_labels>", "</stata_dta>"};
        int map = s.indexOf("<map>") + "<map>".length();
        for (int i = 0; i < 14; i++) {
            long expected = i < tags.length ? s.indexOf(tags[i]) : bytes.length;
            assertEquals(expected, readU64(bytes, map + 8 * i, order), "map entry " + (i + 1));
        }
    }

    /**
     * strL (v,o) cells are v then o, each in the file's byte order (dta spec 5.11.1).
     * "notes" is variable 8; its first cell is observation 1.
     */
    @ParameterizedTest
    @CsvSource({
            "120, BIG_ENDIAN,    0008000000000001",
            "120, LITTLE_ENDIAN, 0800010000000000",
            "119, BIG_ENDIAN,    0000080000000001",
            "119, LITTLE_ENDIAN, 0800000100000000",
    })
    void strlCellLayoutFollowsSpec(int format, String byteOrder, String cellHex) throws IOException {
        byte[] bytes = sample(format, order(byteOrder));
        int data = new String(bytes, StandardCharsets.ISO_8859_1).indexOf("<data>") + "<data>".length();
        int notes = 1 + 2 + 4 + 4 + 8 + 6 + 300;
        assertEquals(cellHex, HexFormat.of().formatHex(bytes, data + notes, data + notes + 8));
    }

    @Test
    void timestampDefaultsAndCanBeOmitted() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (StataWriter w = new StataWriter(bytes, 119, ByteOrder.LITTLE_ENDIAN)) {
            w.write();
        }
        String ts = read(bytes.toByteArray()).getTimestamp();
        assertTrue(ts.matches("\\d\\d [A-Z][a-z]{2} \\d{4} \\d\\d:\\d\\d"), ts);

        bytes.reset();
        try (StataWriter w = new StataWriter(bytes, 119, ByteOrder.LITTLE_ENDIAN)) {
            w.setTimestamp(null).write();
        }
        assertEquals("", read(bytes.toByteArray()).getTimestamp());
    }

    @Test
    void writesEmptyDataset() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (StataWriter w = new StataWriter(bytes, 121, ByteOrder.BIG_ENDIAN)) {
            w.write();
        }
        try (StataReader r = read(bytes.toByteArray())) {
            assertEquals(0, r.getNumVars());
            assertEquals(0, r.getNumObs());
        }
    }

    @Test
    void observationFromMapFillsAbsentVariables() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (StataWriter w = new StataWriter(bytes, 119, ByteOrder.LITTLE_ENDIAN)) {
            w.addVariable("x", StataVarType.DOUBLE).addVariable("name", StataVarType.str(5))
                    .addVariable("note", StataVarType.STRL);
            w.addObservation(Map.of("name", "abc"));
            assertThrows(IllegalArgumentException.class, () -> w.addObservation(Map.of("nope", 1)));
            w.write();
        }
        assertEquals(Arrays.asList(null, "abc", ""),
                Arrays.asList(read(bytes.toByteArray()).getObservation(0).values().toArray()));
    }

    @Test
    void acceptsAnyIntegralNumber() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (StataWriter w = new StataWriter(bytes, 119, ByteOrder.LITTLE_ENDIAN)) {
            w.addVariable("a", StataVarType.LONG).addVariable("b", StataVarType.INT).addVariable("c", StataVarType.BYTE);
            w.addObservation(5L, 7.0, BigInteger.TEN);
            w.addObservation(Double.NaN, null, Float.NaN);
            w.write();
        }
        try (StataReader r = read(bytes.toByteArray())) {
            assertEquals(Arrays.asList(5, (short) 7, (byte) 10), List.copyOf(r.getObservation(0).values()));
            assertEquals(Arrays.asList(null, null, null), Arrays.asList(r.getObservation(1).values().toArray()));
        }
    }

    /** The "Copying a Dataset to Another Format" example in API.md, applied to a pandas fixture. */
    @Test
    void copiesDatasetBetweenFormats() throws Exception {
        byte[] original;
        try (InputStream fixture = getClass().getResourceAsStream("/fixtures/v118.dta")) {
            original = fixture.readAllBytes();
        }
        ByteArrayOutputStream copy = new ByteArrayOutputStream();
        try (StataReader in = read(original);
             StataWriter out = new StataWriter(copy, 121, ByteOrder.BIG_ENDIAN)) {
            out.setDatasetLabel(in.getDatasetLabel());
            for (int i = 0; i < in.getNumVars(); i++) {
                String name = in.getVarNames().get(i);
                out.addVariable(name, in.getVarTypes().get(i), in.getVarLabels().get(i));
                out.setFormat(name, in.getFmtList().get(i));
                if (!in.getValueLabelNames().get(i).isEmpty()) {
                    out.setValueLabel(name, in.getValueLabelNames().get(i));
                }
            }
            in.getValueLabels().forEach(out::defineValueLabel);
            for (Map<String, Object> obs : in.getData()) {
                out.addObservation(obs);
            }
            out.write();
        }
        try (StataReader a = read(original); StataReader b = read(copy.toByteArray())) {
            assertEquals("121", b.getFormat());
            assertEquals(a.getDatasetLabel(), b.getDatasetLabel());
            assertEquals(a.getVarNames(), b.getVarNames());
            assertEquals(a.getVarTypes(), b.getVarTypes());
            assertEquals(a.getVarLabels(), b.getVarLabels());
            assertEquals(a.getFmtList(), b.getFmtList());
            assertEquals(a.getValueLabelNames(), b.getValueLabelNames());
            assertEquals(a.getValueLabels(), b.getValueLabels());
            assertEquals(a.getData(), b.getData());
        }
    }

    // Validation

    @ParameterizedTest
    @ValueSource(ints = {113, 117, 118, 122})
    void rejectsUnsupportedFormat(int format, @TempDir Path dir) {
        assertThrows(IllegalArgumentException.class,
                () -> new StataWriter(new ByteArrayOutputStream(), format, ByteOrder.LITTLE_ENDIAN));
        File file = dir.resolve("out.dta").toFile();
        assertThrows(IllegalArgumentException.class, () -> new StataWriter(file, format, ByteOrder.LITTLE_ENDIAN));
        assertFalse(file.exists(), "file must not be created for invalid arguments");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "byte   | 101",
            "byte   | -128",
            "int    | 32741",
            "int    | -32768",
            "long   | 2147483621",
            "long   | -2147483648",
            "float  | 1.7014118346046923E38",
            "double | 8.98846567431158E307",
            "double | Infinity",
    })
    void rejectsOutOfRangeNumbers(String type, double value) {
        StataVarType t = switch (type) {
            case "byte" -> StataVarType.BYTE;
            case "int" -> StataVarType.INT;
            case "long" -> StataVarType.LONG;
            case "float" -> StataVarType.FLOAT;
            default -> StataVarType.DOUBLE;
        };
        StataWriter w = new StataWriter(new ByteArrayOutputStream(), 119, ByteOrder.LITTLE_ENDIAN);
        w.addVariable("x", t);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> w.addObservation(value));
        assertTrue(e.getMessage().contains("Observation 1, variable x"), e.getMessage());
    }

    @Test
    void rejectsValuesOfTheWrongKind() {
        StataWriter w = new StataWriter(new ByteArrayOutputStream(), 119, ByteOrder.LITTLE_ENDIAN);
        w.addVariable("n", StataVarType.INT).addVariable("s", StataVarType.str(5)).addVariable("l", StataVarType.STRL);
        assertThrows(IllegalArgumentException.class, () -> w.addObservation("1", "a", "a"));      // string into int
        assertThrows(IllegalArgumentException.class, () -> w.addObservation(1.5, "a", "a"));      // non-integer
        assertThrows(IllegalArgumentException.class, () -> w.addObservation(1, 5, "a"));          // number into str
        assertThrows(IllegalArgumentException.class, () -> w.addObservation(1, "\u00e9\u00e9\u00e9", "a")); // 6 bytes > str5
        assertThrows(IllegalArgumentException.class, () -> w.addObservation(1, "a\0", "a"));      // NUL in str#
        assertThrows(IllegalArgumentException.class, () -> w.addObservation(1, "a", "a\0b"));    // NUL in text strL
        assertThrows(IllegalArgumentException.class, () -> w.addObservation(1, "a", 5));          // number into strL
        assertThrows(IllegalArgumentException.class, () -> w.addObservation(1, "a"));             // too few values
        assertEquals(0, w.getNumObs());
        w.addObservation(1, "\u00e9\u00e9", "a");                                                  // 4 bytes fits
        assertEquals(1, w.getNumObs());
    }

    @Test
    void rejectsInvalidNamesAndLabels() {
        StataWriter w = new StataWriter(new ByteArrayOutputStream(), 119, ByteOrder.LITTLE_ENDIAN);
        for (String bad : List.of("", "1abc", "a b", "a-b", "x".repeat(33), "int", "str10", "_n", "strL")) {
            assertThrows(IllegalArgumentException.class, () -> w.addVariable(bad, StataVarType.BYTE), bad);
        }
        w.addVariable("_ok", StataVarType.BYTE).addVariable("\u00e9t\u00e9", StataVarType.BYTE)
                .addVariable("x".repeat(32), StataVarType.BYTE);
        assertThrows(IllegalArgumentException.class, () -> w.addVariable("_ok", StataVarType.INT));
        assertThrows(IllegalArgumentException.class, () -> w.addVariable("y", StataVarType.BYTE, "l".repeat(81)));
        assertThrows(IllegalArgumentException.class, () -> w.setDatasetLabel("l".repeat(81)));
        assertThrows(IllegalArgumentException.class, () -> w.setFormat("_ok", "9.0g"));
        assertThrows(IllegalArgumentException.class, () -> w.setFormat("missing", "%9.0g"));
        assertThrows(IllegalArgumentException.class, () -> w.defineValueLabel("1bad", Map.of(1, "a")));
        w.setVariableLabel("_ok", "l".repeat(80));
    }

    @Test
    void rejectsAliasAndValueLabelsOnStrings() {
        StataWriter w = new StataWriter(new ByteArrayOutputStream(), 120, ByteOrder.LITTLE_ENDIAN);
        assertThrows(IllegalArgumentException.class, () -> w.addVariable("a", StataVarType.ALIAS));
        w.addVariable("s", StataVarType.str(3));
        assertThrows(IllegalArgumentException.class, () -> w.setValueLabel("s", "lbl"));
    }

    @Test
    void enforcesOrderingOfCalls() throws IOException {
        StataWriter w = new StataWriter(new ByteArrayOutputStream(), 119, ByteOrder.LITTLE_ENDIAN);
        w.addVariable("a", StataVarType.BYTE).addObservation(1);
        assertThrows(IllegalStateException.class, () -> w.addVariable("b", StataVarType.BYTE));
        w.write();
        assertThrows(IllegalStateException.class, w::write);
    }

    @Test
    void format120IsLimitedTo32767Variables() {
        StataWriter w120 = new StataWriter(new ByteArrayOutputStream(), 120, ByteOrder.LITTLE_ENDIAN);
        StataWriter w121 = new StataWriter(new ByteArrayOutputStream(), 121, ByteOrder.LITTLE_ENDIAN);
        for (int i = 1; i <= 32767; i++) {
            w120.addVariable("v" + i, StataVarType.BYTE);
            w121.addVariable("v" + i, StataVarType.BYTE);
        }
        assertThrows(IllegalArgumentException.class, () -> w120.addVariable("v32768", StataVarType.BYTE));
        w121.addVariable("v32768", StataVarType.BYTE);
        assertEquals(32768, w121.getNumVars());
    }

    private static long readU64(byte[] b, int at, ByteOrder order) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            int idx = order == ByteOrder.BIG_ENDIAN ? at + i : at + 7 - i;
            v = (v << 8) | (b[idx] & 0xFF);
        }
        return v;
    }
}
