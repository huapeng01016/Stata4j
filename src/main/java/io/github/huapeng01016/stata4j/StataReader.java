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
 *
 * <p>To read part of a dataset, call {@link #selectVariables} and/or
 * {@link #selectObservations} before {@link #read()}. Unselected rows and
 * columns are skipped by byte count rather than decoded, and only the strLs
 * the selection refers to are loaded.
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
    private int totalVars;
    private long totalObs;
    private int numVars;
    private int numObs;
    /** Variables to read, in output order; null reads all of them in file order. */
    private List<String> selectedVars;
    private long obsFrom = 0;
    private long obsTo = Long.MAX_VALUE;
    private DtaFilter filter;
    /** 0-based file index of each observation read. */
    private long[] obsIndex = new long[0];
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
     * Reads only the named variables, in the order given. Call before {@link #read()}.
     * The metadata getters then describe just these variables, and each
     * observation map holds just these keys. Names are checked by {@code read()},
     * which throws {@link IllegalArgumentException} for a name not in the file.
     * Unselected variables are skipped without being decoded.
     *
     * @throws IllegalArgumentException if a name is repeated
     * @throws IllegalStateException    if {@code read()} has already been called
     */
    public StataReader selectVariables(String... names) {
        return selectVariables(Arrays.asList(names));
    }

    /** Collection form of {@link #selectVariables(String...)}. */
    public StataReader selectVariables(Collection<String> names) {
        checkNotRead();
        List<String> copy = new ArrayList<>(names.size());
        Set<String> seen = new HashSet<>();
        for (String name : names) {
            Objects.requireNonNull(name, "variable name");
            if (!seen.add(name)) {
                throw new IllegalArgumentException("Variable selected twice: " + name);
            }
            copy.add(name);
        }
        selectedVars = copy;
        return this;
    }

    /**
     * Reads only observations {@code from} (inclusive) to {@code to} (exclusive),
     * 0-based. Call before {@link #read()}. The range is clamped to the dataset,
     * so a range past the end reads fewer (or no) observations. Afterwards
     * {@link #getObservation(int) getObservation(0)} is observation {@code from}
     * of the file; {@link #getTotalNumObs()} still reports the file's size.
     * Rows outside the range are skipped without being decoded.
     *
     * @throws IllegalArgumentException if {@code from < 0} or {@code to < from}
     * @throws IllegalStateException    if {@code read()} has already been called
     */
    public StataReader selectObservations(long from, long to) {
        checkNotRead();
        if (from < 0 || to < from) {
            throw new IllegalArgumentException("Invalid observation range [" + from + ", " + to + ")");
        }
        obsFrom = from;
        obsTo = to;
        return this;
    }

    /**
     * Reads only the observations matching a filter expression. Call before {@link #read()}.
     * The filter applies within any {@link #selectObservations range}, and may use
     * variables that aren't {@link #selectVariables selected}.
     *
     * <p>Syntax: comparisons {@code var op constant} with {@code op} one of
     * {@code < <= > >= == !=}, combined with {@code &} (and), {@code |} (or),
     * {@code !} (not) and parentheses; {@code !} binds tightest, then {@code &},
     * then {@code |}. For example:
     * <pre>{@code age >= 18 & (state == "CA" | state == "NY") & !(income < .)}</pre>
     *
     * <ul>
     * <li>Numeric variables compare with numbers or the missing values {@code .} and
     *     {@code .a}-{@code .z}. As in Stata, missing is greater than every number
     *     and {@code . < .a < ... < .z}: {@code x > 5} is true for missing {@code x},
     *     and {@code x < .} keeps only non-missing values. The stored value is
     *     compared, so a float variable holding 0.1 does not equal the constant
     *     0.1 (as in Stata).</li>
     * <li>String variables ({@code str#} or {@code strL}) support only {@code ==} and
     *     {@code !=} with a double-quoted literal ({@code \"} and {@code \\} escape),
     *     compared exactly. A binary strL never equals a literal.</li>
     * </ul>
     *
     * <p>Afterwards {@link #getNumObs()} counts the matching observations and
     * {@link #getObservationIndex(int)} gives each one's position in the file.
     *
     * @throws IllegalArgumentException for a syntax error (with its position). Unknown
     *                                  variables and type mismatches are reported by {@code read()}.
     * @throws IllegalStateException    if {@code read()} has already been called
     */
    public StataReader filterObservations(String expression) {
        checkNotRead();
        filter = DtaFilter.parse(expression);
        return this;
    }

    private void checkNotRead() {
        if (readCalled) {
            throw new IllegalStateException("read() has already been called");
        }
    }

    /**
     * Reads and parses the Stata dataset, or the part chosen with
     * {@link #selectVariables} and {@link #selectObservations}. May be called only once.
     *
     * @throws IOException if an I/O error occurs, including {@link EOFException} for a truncated file
     * @throws StataFormatException if the file is not a supported .dta file, or the selection
     *                              holds more than {@link Integer#MAX_VALUE} observations
     * @throws IllegalArgumentException if a selected variable is not in the file
     */
    public void read() throws IOException, StataFormatException {
        checkNotRead();
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
        totalVars = input.u16();
        totalObs = input.u32();
        datasetLabel = input.fixedString(DtaLayout.LEGACY_DATA_LABEL_LEN, cs);
        timestamp = input.fixedString(DtaLayout.LEGACY_TIMESTAMP_LEN, cs);

        List<StataVarType> types = new ArrayList<>(totalVars);
        for (int i = 0; i < totalVars; i++) {
            types.add(StataVarType.fromLegacyCode(input.u8()));
        }
        varTypes = types;
        varNames = readStrings(input, layout.varNameLen());
        input.skip((totalVars + 1L) * layout.sortEntryBytes());
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

        int[] columns = selectedColumns();
        DataPass pass = readData(input, columns, bindFilter(), new HashSet<>());

        Map<String, Map<Integer, String>> labels = new LinkedHashMap<>();
        while (input.peek() != -1) {
            long len = input.u32();
            String name = input.fixedString(layout.labelNameLen(), cs);
            input.skip(3); // padding
            labels.put(name, readValueLabelTable(input, len));
        }
        finish(columns, pass, labels);
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
        totalVars = toCount(input.uN(layout.kBytes()), "variables");
        input.expect("</K><N>");
        totalObs = input.uN(layout.nBytes());
        if (totalObs < 0) {
            throw new StataFormatException("Invalid observation count");
        }
        input.expect("</N><label>");
        datasetLabel = new String(input.bytes((int) input.uN(layout.dataLabelLenBytes())), cs);
        input.expect("</label><timestamp>");
        timestamp = new String(input.bytes(input.u8()), cs);
        input.expect("</timestamp></header>");

        input.expect("<map>");
        input.skip(14 * 8);
        input.expect("</map>");

        input.expect("<variable_types>");
        List<StataVarType> types = new ArrayList<>(Math.min(totalVars, 1 << 16));
        for (int i = 0; i < totalVars; i++) {
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
        input.skip((totalVars + 1L) * layout.sortEntryBytes());
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

        int[] columns = selectedColumns();
        DtaFilter.Bound bound = bindFilter();
        input.expect("<data>");
        Set<StrLRef> needed = new HashSet<>();
        DataPass pass = readData(input, columns, bound, needed);
        input.expect("</data>");

        // Load only the GSOs that the cells read refer to. A cell may link to a
        // GSO first defined by another variable or observation (spec 5.11.1),
        // so this goes by the references collected, not by the selection.
        input.expect("<strls>");
        Map<StrLRef, Object> strls = new HashMap<>();
        while (input.nextIs("GSO")) {
            input.expect("GSO");
            StrLRef key = new StrLRef(input.u32(), input.uN(layout.gsoOBytes()));
            int t = input.u8();
            if (t != GSO_ASCII && t != GSO_BINARY) {
                throw new StataFormatException("Invalid strL type: " + t);
            }
            long len = input.u32();
            if (!needed.contains(key)) {
                input.skip(len);
                continue;
            }
            byte[] contents = input.bytes(toArrayLength(len));
            strls.put(key, t == GSO_ASCII ? DtaInput.cString(contents, 0, contents.length, cs) : contents);
        }
        input.expect("</strls>");
        if (pass.deferred != null) {
            pass.applyDeferredFilter(bound, strls);
        }
        resolveStrLs(pass.rows, strls);

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

        finish(columns, pass, labels);
    }

    private List<String> readStrings(DtaInput input, int width) throws IOException {
        List<String> result = new ArrayList<>(Math.min(totalVars, 1 << 16));
        for (int i = 0; i < totalVars; i++) {
            result.add(input.fixedString(width, layout.charset()));
        }
        return result;
    }

    /** File index of each variable to read, in output order. */
    private int[] selectedColumns() {
        if (selectedVars == null) {
            int[] all = new int[totalVars];
            for (int i = 0; i < all.length; i++) {
                all[i] = i;
            }
            return all;
        }
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < varNames.size(); i++) {
            index.put(varNames.get(i), i);
        }
        int[] columns = new int[selectedVars.size()];
        for (int i = 0; i < columns.length; i++) {
            Integer at = index.get(selectedVars.get(i));
            if (at == null) {
                throw new IllegalArgumentException("Variable not found: " + selectedVars.get(i));
            }
            columns[i] = at;
        }
        return columns;
    }

    private DtaFilter.Bound bindFilter() {
        return filter == null ? null : filter.bind(varNames, varTypes);
    }

    /** Result of reading {@code <data>}: the rows kept, and where each came from in the file. */
    private static final class DataPass {
        final List<Map<String, Object>> rows = new ArrayList<>();
        final List<Long> obsIndex = new ArrayList<>();
        /**
         * Filter inputs per row when the filter reads a strL variable, whose values
         * are known only after {@code <strls>}; null when the filter was applied while reading.
         */
        List<Object[]> deferred;

        /** Keeps the rows whose deferred filter inputs pass, with strL references resolved. */
        void applyDeferredFilter(DtaFilter.Bound bound, Map<StrLRef, Object> strls) throws StataFormatException {
            List<Map<String, Object>> keptRows = new ArrayList<>();
            List<Long> keptIndex = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                double[] nums = (double[]) deferred.get(i)[0];
                Object[] strs = (Object[]) deferred.get(i)[1];
                for (int s = 0; s < strs.length; s++) {
                    if (strs[s] instanceof StrLRef ref) {
                        strs[s] = lookup(strls, ref);
                    }
                }
                if (bound.test(nums, strs)) {
                    keptRows.add(rows.get(i));
                    keptIndex.add(obsIndex.get(i));
                }
            }
            rows.clear();
            rows.addAll(keptRows);
            obsIndex.clear();
            obsIndex.addAll(keptIndex);
            deferred = null;
        }
    }

    /**
     * Reads the selected rows and columns of {@code <data>}, skipping the rest
     * by byte count (every row has the same width), and applies the filter.
     * Adds the strL references of the rows kept to {@code strlRefs}. Leaves
     * the input just past the last row.
     */
    private DataPass readData(DtaInput input, int[] columns, DtaFilter.Bound bound, Set<StrLRef> strlRefs)
            throws IOException, StataFormatException {
        int[] outPos = new int[totalVars];
        Arrays.fill(outPos, -1);
        for (int i = 0; i < columns.length; i++) {
            outPos[columns[i]] = i;
        }
        String[] names = new String[columns.length];
        for (int i = 0; i < columns.length; i++) {
            names[i] = varNames.get(columns[i]);
        }
        long rowWidth = 0;
        for (StataVarType t : varTypes) {
            rowWidth += t.getByteWidth();
        }

        long first = Math.min(obsFrom, totalObs);
        long end = Math.min(obsTo, totalObs);
        if (bound == null && end - first > Integer.MAX_VALUE) {
            throw tooManyObservations();
        }

        DataPass pass = new DataPass();
        boolean defer = bound != null && bound.usesStrL();
        if (defer) {
            pass.deferred = new ArrayList<>();
        }
        List<StrLRef> rowRefs = new ArrayList<>();
        // Bytes to skip before the next value read; merges adjacent skips across columns and rows.
        long pending = bytes(first, rowWidth);
        for (long obs = first; obs < end; obs++) {
            Object[] values = new Object[columns.length];
            double[] nums = bound == null ? null : new double[bound.numCount()];
            Object[] strs = bound == null ? null : new Object[bound.strCount()];
            rowRefs.clear();
            for (int var = 0; var < totalVars; var++) {
                StataVarType type = varTypes.get(var);
                int out = outPos[var];
                int numSlot = bound == null ? -1 : bound.numSlot(var);
                int strSlot = bound == null ? -1 : bound.strSlot(var);
                if (out < 0 && numSlot < 0 && strSlot < 0) {
                    pending += type.getByteWidth();
                    continue;
                }
                if (pending > 0) {
                    input.skip(pending);
                    pending = 0;
                }
                Object value = readValue(input, type, nums, numSlot);
                if (value instanceof StrLRef ref) {
                    rowRefs.add(ref);
                }
                if (out >= 0) {
                    values[out] = value;
                }
                if (strSlot >= 0) {
                    strs[strSlot] = value;
                }
            }
            if (bound != null && !defer && !bound.test(nums, strs)) {
                continue;
            }
            if (pass.rows.size() == Integer.MAX_VALUE - 8) {
                throw tooManyObservations();
            }
            strlRefs.addAll(rowRefs);
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < values.length; i++) {
                row.put(names[i], values[i]);
            }
            pass.rows.add(row);
            pass.obsIndex.add(obs);
            if (defer) {
                pass.deferred.add(new Object[] {nums, strs});
            }
        }
        input.skip(pending + bytes(totalObs - end, rowWidth));
        return pass;
    }

    private StataFormatException tooManyObservations() {
        return new StataFormatException("Dataset has " + totalObs + " observations; at most "
                + Integer.MAX_VALUE + " can be read at once. Use selectObservations to read a range.");
    }

    private static long bytes(long rows, long rowWidth) throws StataFormatException {
        try {
            return Math.multiplyExact(rows, rowWidth);
        } catch (ArithmeticException e) {
            throw new StataFormatException("Data section too large", e);
        }
    }

    /**
     * Reads one value. Missing numbers return null; when {@code keySlot >= 0} the
     * number's filter sort key (which keeps {@code . < .a < ... < .z} above all
     * numbers) is also stored in {@code keys[keySlot]}.
     */
    private Object readValue(DtaInput input, StataVarType type, double[] keys, int keySlot) throws IOException {
        switch (type.kind()) {
            case BYTE: {
                byte b = input.i8();
                boolean missing = b >= DtaMissing.BYTE;
                if (keySlot >= 0) {
                    keys[keySlot] = missing ? DtaFilter.missingKey(b - DtaMissing.BYTE) : b;
                }
                return missing ? null : b;
            }
            case INT: {
                short s = input.i16();
                boolean missing = s >= DtaMissing.INT;
                if (keySlot >= 0) {
                    keys[keySlot] = missing ? DtaFilter.missingKey(s - DtaMissing.INT) : s;
                }
                return missing ? null : s;
            }
            case LONG: {
                int i = input.i32();
                boolean missing = i >= DtaMissing.LONG;
                if (keySlot >= 0) {
                    keys[keySlot] = missing ? DtaFilter.missingKey(i - DtaMissing.LONG) : i;
                }
                return missing ? null : i;
            }
            case FLOAT: {
                float f = input.f32();
                boolean missing = f >= DtaMissing.FLOAT || Float.isNaN(f);
                if (keySlot >= 0) {
                    keys[keySlot] = missing ? DtaFilter.floatMissingKey(f) : f;
                }
                return missing ? null : f;
            }
            case DOUBLE: {
                double d = input.f64();
                boolean missing = d >= DtaMissing.DOUBLE || Double.isNaN(d);
                if (keySlot >= 0) {
                    keys[keySlot] = missing ? DtaFilter.doubleMissingKey(d) : d;
                }
                return missing ? null : d;
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

    private static void resolveStrLs(List<Map<String, Object>> rows, Map<StrLRef, Object> strls)
            throws StataFormatException {
        for (Map<String, Object> row : rows) {
            for (Map.Entry<String, Object> cell : row.entrySet()) {
                if (cell.getValue() instanceof StrLRef ref) {
                    cell.setValue(lookup(strls, ref));
                }
            }
        }
    }

    private static Object lookup(Map<StrLRef, Object> strls, StrLRef ref) throws StataFormatException {
        Object value = strls.get(ref);
        if (value == null) {
            throw new StataFormatException("strL (" + ref.v() + "," + ref.o() + ") not found in <strls>");
        }
        return value;
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

    /** Freezes the rows and narrows the per-variable metadata to the selected columns. */
    private void finish(int[] columns, DataPass pass, Map<String, Map<Integer, String>> labels) {
        List<Map<String, Object>> frozen = new ArrayList<>(pass.rows.size());
        for (Map<String, Object> row : pass.rows) {
            frozen.add(Collections.unmodifiableMap(row));
        }
        data = Collections.unmodifiableList(frozen);
        valueLabels = Collections.unmodifiableMap(labels);
        numObs = frozen.size();
        obsIndex = new long[numObs];
        for (int i = 0; i < numObs; i++) {
            obsIndex[i] = pass.obsIndex.get(i);
        }

        numVars = columns.length;
        varNames = project(varNames, columns);
        varTypes = project(varTypes, columns);
        varLabels = project(varLabels, columns);
        fmtList = project(fmtList, columns);
        lblList = project(lblList, columns);
    }

    private static <T> List<T> project(List<T> all, int[] columns) {
        List<T> result = new ArrayList<>(columns.length);
        for (int c : columns) {
            result.add(all.get(c));
        }
        return result;
    }

    private static int toCount(long n, String what) throws StataFormatException {
        if (n > Integer.MAX_VALUE) {
            throw new StataFormatException("Too many " + what + ": " + n);
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

    /** Returns the number of variables read: all of them, or those selected. */
    public int getNumVars() {
        return numVars;
    }

    /** Returns the number of observations read: all of them, or those in the selected range that pass the filter. */
    public int getNumObs() {
        return numObs;
    }

    /**
     * Returns the 0-based position in the file of observation {@code index} as read.
     * Without a range or filter this is {@code index}; with a range it is offset by
     * the range start; with a filter it identifies which file observation matched.
     */
    public long getObservationIndex(int index) {
        if (index < 0 || index >= obsIndex.length) {
            throw new IndexOutOfBoundsException("Observation index out of bounds: " + index);
        }
        return obsIndex[index];
    }

    /** Returns the number of variables in the file, regardless of any selection. */
    public int getTotalNumVars() {
        return totalVars;
    }

    /** Returns the number of observations in the file, regardless of any selection. */
    public long getTotalNumObs() {
        return totalObs;
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
