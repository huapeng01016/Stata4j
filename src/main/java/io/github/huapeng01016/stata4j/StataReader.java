package io.github.huapeng01016.stata4j;

import java.io.*;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * A reader for Stata dataset (.dta) files.
 * Supports the legacy binary formats 113-115 (Stata 8-12) and the tagged
 * formats 117-121 (Stata 13 and later), in either byte order.
 *
 * <p>Numeric values are returned as {@link Byte}, {@link Short}, {@link Integer},
 * {@link Float} or {@link Double}; missing values ({@code .} and {@code .a}-{@code .z})
 * are returned as {@code null}. {@code str#} and text {@code strL} values are
 * returned as {@link String}, binary {@code strL} values as {@code byte[]}.
 * Alias variables (formats 120/121) hold no data in the file and read as {@code null}.
 */
public class StataReader implements AutoCloseable {

    static final int GSO_BINARY = 129;
    static final int GSO_ASCII = 130;

    /** A strL cell's reference into the {@code <strls>} section, resolved after it is read. */
    private record StrLRef(long v, long o) {}

    private final BufferedInputStream in;
    private boolean readCalled;
    private DtaLayout layout;
    private ByteOrder byteOrder;
    private int numVars;
    private int numObs;
    private String datasetLabel = "";
    private String timestamp = "";
    private List<String> varNames = List.of();
    private List<String> varLabels = List.of();
    private List<String> fmtList = List.of();
    private List<String> lblList = List.of();
    private List<StataVarType> varTypes = List.of();
    private List<Map<String, Object>> data = List.of();
    private Map<String, Map<Integer, String>> valueLabels = Map.of();

    /**
     * Creates a new StataReader for the specified file.
     *
     * @param file the Stata .dta file to read
     * @throws IOException if an I/O error occurs
     */
    public StataReader(File file) throws IOException {
        this(new FileInputStream(file));
    }

    /**
     * Creates a new StataReader for the specified file path.
     *
     * @param filePath the path to the Stata .dta file
     * @throws IOException if an I/O error occurs
     */
    public StataReader(String filePath) throws IOException {
        this(new File(filePath));
    }

    /**
     * Creates a new StataReader from an input stream.
     *
     * @param inputStream the input stream to read from
     */
    public StataReader(InputStream inputStream) {
        this.in = new BufferedInputStream(inputStream);
    }

    /**
     * Reads and parses the Stata dataset. May be called only once.
     *
     * @throws IOException if an I/O error occurs, including {@link EOFException} for a truncated file
     * @throws StataFormatException if the file is not a supported .dta file
     */
    public void read() throws IOException, StataFormatException {
        if (readCalled) {
            throw new IllegalStateException("read() has already been called");
        }
        readCalled = true;

        DtaInput input = new DtaInput(in);
        int first = input.peek();
        if (first == -1) {
            throw new EOFException("Empty input");
        }
        if (first == '<') {
            readTagged(input);
        } else {
            readLegacy(input);
        }
    }

    // Formats 113-115: fixed binary header, no tags.
    private void readLegacy(DtaInput input) throws IOException, StataFormatException {
        layout = DtaLayout.forRelease(input.u8());
        if (layout.tagged()) {
            throw new StataFormatException("Unsupported Stata format: " + layout.release());
        }
        int order = input.u8();
        if (order == 0x01) {
            byteOrder = ByteOrder.BIG_ENDIAN;
        } else if (order == 0x02) {
            byteOrder = ByteOrder.LITTLE_ENDIAN;
        } else {
            throw new StataFormatException("Invalid byte order: " + order);
        }
        input.setOrder(byteOrder);
        input.skip(2); // filetype, unused

        Charset cs = layout.charset();
        numVars = input.u16();
        numObs = toObsCount(input.u32());
        datasetLabel = input.fixedString(DtaLayout.LEGACY_DATA_LABEL_LEN, cs);
        timestamp = input.fixedString(DtaLayout.LEGACY_TIMESTAMP_LEN, cs);

        List<StataVarType> types = new ArrayList<>(numVars);
        for (int i = 0; i < numVars; i++) {
            types.add(StataVarType.fromLegacyCode(input.u8()));
        }
        varTypes = types;
        varNames = readStrings(input, layout.varNameLen());
        input.skip((numVars + 1L) * layout.sortEntryBytes());
        fmtList = readStrings(input, layout.formatLen());
        lblList = readStrings(input, layout.labelNameLen());
        varLabels = readStrings(input, layout.varLabelLen());

        // Expansion fields: (type, length) records ended by a zero type and length.
        while (true) {
            int type = input.u8();
            long len = input.u32();
            if (type == 0 && len == 0) {
                break;
            }
            input.skip(len);
        }

        List<Map<String, Object>> rows = readData(input);

        Map<String, Map<Integer, String>> labels = new LinkedHashMap<>();
        while (input.peek() != -1) {
            long len = input.u32();
            String name = input.fixedString(layout.labelNameLen(), cs);
            input.skip(3); // padding
            labels.put(name, readValueLabelTable(input, len));
        }
        finish(rows, labels);
    }

    // Formats 117-119: <stata_dta> with positional, tagged sections.
    private void readTagged(DtaInput input) throws IOException, StataFormatException {
        input.expect("<stata_dta><header><release>");
        String release = new String(input.bytes(3), StandardCharsets.US_ASCII);
        try {
            layout = DtaLayout.forRelease(Integer.parseInt(release));
        } catch (NumberFormatException e) {
            throw new StataFormatException("Unsupported Stata format: " + release);
        }
        if (!layout.tagged()) {
            throw new StataFormatException("Unsupported Stata format: " + release);
        }
        input.expect("</release><byteorder>");
        String order = new String(input.bytes(3), StandardCharsets.US_ASCII);
        if (order.equals("MSF")) {
            byteOrder = ByteOrder.BIG_ENDIAN;
        } else if (order.equals("LSF")) {
            byteOrder = ByteOrder.LITTLE_ENDIAN;
        } else {
            throw new StataFormatException("Invalid byte order: " + order);
        }
        input.setOrder(byteOrder);

        Charset cs = layout.charset();
        input.expect("</byteorder><K>");
        numVars = (int) input.uN(layout.kBytes());
        input.expect("</K><N>");
        numObs = toObsCount(input.uN(layout.nBytes()));
        input.expect("</N><label>");
        datasetLabel = new String(input.bytes((int) input.uN(layout.dataLabelLenBytes())), cs);
        input.expect("</label><timestamp>");
        timestamp = new String(input.bytes(input.u8()), cs);
        input.expect("</timestamp></header>");

        input.expect("<map>");
        input.skip(14 * 8);
        input.expect("</map>");

        input.expect("<variable_types>");
        List<StataVarType> types = new ArrayList<>(numVars);
        for (int i = 0; i < numVars; i++) {
            StataVarType type = StataVarType.fromTaggedCode(input.u16());
            if (type.isAlias() && !layout.allowsAlias()) {
                throw new StataFormatException("Alias variable in format " + layout.release());
            }
            types.add(type);
        }
        varTypes = types;
        input.expect("</variable_types>");

        input.expect("<varnames>");
        varNames = readStrings(input, layout.varNameLen());
        input.expect("</varnames>");
        input.expect("<sortlist>");
        input.skip((numVars + 1L) * layout.sortEntryBytes());
        input.expect("</sortlist>");
        input.expect("<formats>");
        fmtList = readStrings(input, layout.formatLen());
        input.expect("</formats>");
        input.expect("<value_label_names>");
        lblList = readStrings(input, layout.labelNameLen());
        input.expect("</value_label_names>");
        input.expect("<variable_labels>");
        varLabels = readStrings(input, layout.varLabelLen());
        input.expect("</variable_labels>");

        input.expect("<characteristics>");
        while (input.nextIs("<ch>")) {
            input.expect("<ch>");
            input.skip(input.u32());
            input.expect("</ch>");
        }
        input.expect("</characteristics>");

        input.expect("<data>");
        List<Map<String, Object>> rows = readData(input);
        input.expect("</data>");

        input.expect("<strls>");
        Map<StrLRef, Object> strls = new HashMap<>();
        while (input.nextIs("GSO")) {
            input.expect("GSO");
            long v = input.u32();
            long o = input.uN(layout.gsoOBytes());
            int t = input.u8();
            byte[] contents = input.bytes(toArrayLength(input.u32()));
            Object value;
            if (t == GSO_ASCII) {
                value = DtaInput.cString(contents, 0, contents.length, cs);
            } else if (t == GSO_BINARY) {
                value = contents;
            } else {
                throw new StataFormatException("Invalid strL type: " + t);
            }
            strls.put(new StrLRef(v, o), value);
        }
        input.expect("</strls>");
        resolveStrLs(rows, strls);

        input.expect("<value_labels>");
        Map<String, Map<Integer, String>> labels = new LinkedHashMap<>();
        while (input.nextIs("<lbl>")) {
            input.expect("<lbl>");
            long len = input.u32();
            String name = input.fixedString(layout.labelNameLen(), cs);
            input.skip(3); // padding
            labels.put(name, readValueLabelTable(input, len));
            input.expect("</lbl>");
        }
        input.expect("</value_labels>");
        input.expect("</stata_dta>");

        finish(rows, labels);
    }

    private List<String> readStrings(DtaInput input, int width) throws IOException {
        List<String> result = new ArrayList<>(numVars);
        for (int i = 0; i < numVars; i++) {
            result.add(input.fixedString(width, layout.charset()));
        }
        return result;
    }

    private List<Map<String, Object>> readData(DtaInput input) throws IOException {
        // Cap the preallocation so a corrupt N cannot trigger a huge allocation.
        List<Map<String, Object>> rows = new ArrayList<>(Math.min(numObs, 1 << 16));
        for (int obs = 0; obs < numObs; obs++) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int var = 0; var < numVars; var++) {
                row.put(varNames.get(var), readValue(input, varTypes.get(var)));
            }
            rows.add(row);
        }
        return rows;
    }

    private Object readValue(DtaInput input, StataVarType type) throws IOException {
        switch (type.kind()) {
            case BYTE: {
                byte b = input.i8();
                return b >= DtaMissing.BYTE ? null : b;
            }
            case INT: {
                short s = input.i16();
                return s >= DtaMissing.INT ? null : s;
            }
            case LONG: {
                int i = input.i32();
                return i >= DtaMissing.LONG ? null : i;
            }
            case FLOAT: {
                float f = input.f32();
                return (f >= DtaMissing.FLOAT || Float.isNaN(f)) ? null : f;
            }
            case DOUBLE: {
                double d = input.f64();
                return (d >= DtaMissing.DOUBLE || Double.isNaN(d)) ? null : d;
            }
            case STR:
                return input.fixedString(type.getByteWidth(), layout.charset());
            case STRL: {
                // v then o, each in the file's byte order, together 8 bytes.
                long v = input.uN(layout.strlVBytes());
                long o = input.uN(8 - layout.strlVBytes());
                return (v == 0 && o == 0) ? "" : new StrLRef(v, o);
            }
            case ALIAS:
                // Assumed to occupy no bytes in <data>: the dta_120 spec lists the
                // type but not a width. If that is wrong, the </data> check fails.
                return null;
            default:
                throw new AssertionError(type);
        }
    }

    private void resolveStrLs(List<Map<String, Object>> rows, Map<StrLRef, Object> strls)
            throws StataFormatException {
        for (Map<String, Object> row : rows) {
            for (Map.Entry<String, Object> cell : row.entrySet()) {
                if (cell.getValue() instanceof StrLRef ref) {
                    Object value = strls.get(ref);
                    if (value == null) {
                        throw new StataFormatException(
                                "strL (" + ref.v() + "," + ref.o() + ") not found in <strls>");
                    }
                    cell.setValue(value);
                }
            }
        }
    }

    /** Parses {@code n, txtlen, off[n], val[n], txt} into value -> label. */
    private Map<Integer, String> readValueLabelTable(DtaInput input, long len)
            throws IOException, StataFormatException {
        byte[] table = input.bytes(toArrayLength(len));
        try {
            ByteBuffer buf = ByteBuffer.wrap(table).order(byteOrder);
            int n = buf.getInt();
            int txtLen = buf.getInt();
            if (n < 0 || txtLen < 0 || 8L + 8L * n + txtLen > table.length) {
                throw new StataFormatException("Corrupt value label table");
            }
            int[] off = new int[n];
            for (int i = 0; i < n; i++) {
                off[i] = buf.getInt();
            }
            int[] val = new int[n];
            for (int i = 0; i < n; i++) {
                val[i] = buf.getInt();
            }
            int txtStart = buf.position();
            Map<Integer, String> labels = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) {
                if (off[i] < 0 || off[i] >= txtLen) {
                    throw new StataFormatException("Corrupt value label table");
                }
                labels.put(val[i], DtaInput.cString(table, txtStart + off[i], txtStart + txtLen, layout.charset()));
            }
            return Collections.unmodifiableMap(labels);
        } catch (BufferUnderflowException e) {
            throw new StataFormatException("Corrupt value label table", e);
        }
    }

    private void finish(List<Map<String, Object>> rows, Map<String, Map<Integer, String>> labels) {
        List<Map<String, Object>> frozen = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            frozen.add(Collections.unmodifiableMap(row));
        }
        data = Collections.unmodifiableList(frozen);
        valueLabels = Collections.unmodifiableMap(labels);
    }

    private static int toObsCount(long n) throws StataFormatException {
        if (n > Integer.MAX_VALUE) {
            throw new StataFormatException("Too many observations: " + n);
        }
        return (int) n;
    }

    private static int toArrayLength(long n) throws StataFormatException {
        if (n > Integer.MAX_VALUE - 8) {
            throw new StataFormatException("Field too large: " + n + " bytes");
        }
        return (int) n;
    }

    // Getters

    /** Returns the format release, e.g. {@code "115"} or {@code "118"}. */
    public String getFormat() {
        return layout == null ? null : Integer.toString(layout.release());
    }

    public ByteOrder getByteOrder() {
        return byteOrder;
    }

    public int getNumVars() {
        return numVars;
    }

    public int getNumObs() {
        return numObs;
    }

    public String getDatasetLabel() {
        return datasetLabel;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public List<String> getVarNames() {
        return Collections.unmodifiableList(varNames);
    }

    public List<String> getVarLabels() {
        return Collections.unmodifiableList(varLabels);
    }

    public List<String> getFmtList() {
        return Collections.unmodifiableList(fmtList);
    }

    public List<StataVarType> getVarTypes() {
        return Collections.unmodifiableList(varTypes);
    }

    /** Returns the value-label name attached to each variable ({@code ""} if none). */
    public List<String> getValueLabelNames() {
        return Collections.unmodifiableList(lblList);
    }

    /** Returns every value-label set in the file, keyed by label name. */
    public Map<String, Map<Integer, String>> getValueLabels() {
        return valueLabels;
    }

    public List<Map<String, Object>> getData() {
        return data;
    }

    public Map<String, Object> getObservation(int index) {
        if (index < 0 || index >= data.size()) {
            throw new IndexOutOfBoundsException("Observation index out of bounds: " + index);
        }
        return data.get(index);
    }

    /** Returns the value of variable {@code var} (0-based) in observation {@code obs}. */
    public Object getValue(int obs, int var) {
        if (var < 0 || var >= varNames.size()) {
            throw new IndexOutOfBoundsException("Variable index out of bounds: " + var);
        }
        return getObservation(obs).get(varNames.get(var));
    }

    /** Returns the value of the named variable in observation {@code obs}. */
    public Object getValue(int obs, String varName) {
        if (!varNames.contains(varName)) {
            throw new IllegalArgumentException("Variable not found: " + varName);
        }
        return getObservation(obs).get(varName);
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
