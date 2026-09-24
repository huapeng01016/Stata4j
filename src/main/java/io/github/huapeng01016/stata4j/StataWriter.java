package io.github.huapeng01016.stata4j;

import java.io.*;
import java.math.BigInteger;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Writes a Stata dataset (.dta) in format 119, 120 or 121, in either byte order.
 *
 * <p>Declare the variables, optionally define value labels, add observations,
 * then call {@link #write()}. The dataset is held in memory until then.
 * {@link #close()} closes the stream but does <em>not</em> write; a writer
 * closed without {@code write()} leaves an empty file.
 *
 * <pre>{@code
 * try (StataWriter w = new StataWriter("out.dta", 119, ByteOrder.LITTLE_ENDIAN)) {
 *     w.addVariable("id", StataVarType.LONG, "Person ID");
 *     w.addVariable("name", StataVarType.str(20));
 *     w.addObservation(1, "Alice");
 *     w.addObservation(2, null);          // null writes missing, or "" for strings
 *     w.write();
 * }
 * }</pre>
 *
 * <p>Values are validated as they are added. Numeric variables accept any
 * {@link Number} in the type's non-missing range (integer types require an
 * integral value); {@code null} and NaN are written as missing ({@code .}).
 * {@code str#} variables take a {@link String} of at most {@code #} UTF-8
 * bytes; {@code strL} variables take a {@link String} or a {@code byte[]}
 * (written as a binary strL).
 *
 * <p>Alias variables cannot be written: format 120/121 is accepted, but the
 * file will not contain alias variables.
 */
public class StataWriter implements AutoCloseable {

    /** Formats this writer can produce. */
    public static final Set<Integer> SUPPORTED_FORMATS = Set.of(119, 120, 121);

    private static final int MAX_NAME_CHARS = 32;
    private static final int MAX_LABEL_CHARS = 80;
    private static final int MAX_VALUE_LABEL_CHARS = 32000;
    /** Format 120 records K in 2 bytes; Stata caps it at 32,767 variables. */
    private static final int MAX_VARS_FORMAT_120 = 32767;
    private static final int MAX_DATASET_LABEL_BYTES = 320;
    private static final int MAP_ENTRIES = 14;

    private static final Set<String> RESERVED_NAMES = Set.of(
            "_all", "_b", "byte", "_coef", "_cons", "double", "float", "if", "in", "int", "long",
            "_n", "_N", "_pi", "_pred", "_rc", "_skip", "strL", "using", "with");
    private static final Pattern STR_TYPE_NAME = Pattern.compile("str[0-9]+");
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", Locale.ENGLISH);

    private static final class Variable {
        final String name;
        final StataVarType type;
        String label = "";
        String format;
        String valueLabel = "";

        Variable(String name, StataVarType type) {
            this.name = name;
            this.type = type;
            this.format = defaultFormat(type);
        }
    }

    /** A non-empty strL value; empty strLs are written as (v,o) = (0,0). */
    private record StrL(byte[] data, boolean binary) {}

    private final OutputStream out;
    private final DtaLayout layout;
    private final ByteOrder byteOrder;
    private String datasetLabel = "";
    private boolean timestampSet;
    private LocalDateTime timestamp;
    private final List<Variable> variables = new ArrayList<>();
    private final Map<String, Variable> byName = new HashMap<>();
    private final Map<String, SortedMap<Integer, String>> valueLabels = new LinkedHashMap<>();
    private final List<Object[]> rows = new ArrayList<>();
    private boolean written;

    /**
     * Creates a writer for the given stream.
     *
     * @param format    119, 120 or 121
     * @param byteOrder {@link ByteOrder#BIG_ENDIAN} (MSF) or {@link ByteOrder#LITTLE_ENDIAN} (LSF)
     * @throws IllegalArgumentException if the format is not supported
     */
    public StataWriter(OutputStream out, int format, ByteOrder byteOrder) {
        this.layout = checkFormat(format);
        this.byteOrder = Objects.requireNonNull(byteOrder, "byteOrder");
        this.out = new BufferedOutputStream(Objects.requireNonNull(out, "out"));
    }

    /**
     * Creates a writer for the given file, which is created or truncated.
     * The arguments are validated before the file is opened.
     */
    public StataWriter(File file, int format, ByteOrder byteOrder) throws IOException {
        this(open(file, format, byteOrder), format, byteOrder);
    }

    /** Creates a writer for the given file path; see {@link #StataWriter(File, int, ByteOrder)}. */
    public StataWriter(String filePath, int format, ByteOrder byteOrder) throws IOException {
        this(new File(filePath), format, byteOrder);
    }

    private static OutputStream open(File file, int format, ByteOrder byteOrder) throws IOException {
        checkFormat(format);
        Objects.requireNonNull(byteOrder, "byteOrder");
        return new FileOutputStream(file);
    }

    private static DtaLayout checkFormat(int format) {
        if (!SUPPORTED_FORMATS.contains(format)) {
            throw new IllegalArgumentException(
                    "Unsupported format for writing: " + format + " (supported: 119, 120, 121)");
        }
        try {
            return DtaLayout.forRelease(format);
        } catch (StataFormatException e) {
            throw new AssertionError(e);
        }
    }

    // Dataset metadata

    /** Sets the dataset label, at most 80 characters. */
    public StataWriter setDatasetLabel(String label) {
        datasetLabel = checkText(label, MAX_LABEL_CHARS, MAX_DATASET_LABEL_BYTES, "Dataset label");
        return this;
    }

    /**
     * Sets the timestamp recorded in the file. By default the time of
     * {@link #write()} is used; {@code null} records no timestamp.
     */
    public StataWriter setTimestamp(LocalDateTime timestamp) {
        this.timestampSet = true;
        this.timestamp = timestamp;
        return this;
    }

    // Variables

    public StataWriter addVariable(String name, StataVarType type) {
        return addVariable(name, type, "");
    }

    /**
     * Adds a variable. Variables must all be added before the first observation.
     *
     * @throws IllegalArgumentException if the name is invalid or taken, the label is too long,
     *                                  the type is alias, or the format's variable limit is reached
     * @throws IllegalStateException    if observations have already been added
     */
    public StataWriter addVariable(String name, StataVarType type, String label) {
        if (!rows.isEmpty()) {
            throw new IllegalStateException("Variables must be added before observations");
        }
        checkName(name, "Variable name");
        Objects.requireNonNull(type, "type");
        if (byName.containsKey(name)) {
            throw new IllegalArgumentException("Duplicate variable name: " + name);
        }
        if (type.isAlias()) {
            throw new IllegalArgumentException("Writing alias variables is not supported: " + name);
        }
        if (layout.release() == 120 && variables.size() >= MAX_VARS_FORMAT_120) {
            throw new IllegalArgumentException(
                    "Format 120 allows at most " + MAX_VARS_FORMAT_120 + " variables; use 121");
        }
        Variable v = new Variable(name, type);
        v.label = checkText(label, MAX_LABEL_CHARS, layout.varLabelLen() - 1, "Variable label");
        variables.add(v);
        byName.put(name, v);
        return this;
    }

    /** Sets a variable's label, at most 80 characters. */
    public StataWriter setVariableLabel(String varName, String label) {
        variable(varName).label = checkText(label, MAX_LABEL_CHARS, layout.varLabelLen() - 1, "Variable label");
        return this;
    }

    /**
     * Sets a variable's display format, such as {@code %9.2f} or {@code %td}.
     * Defaults follow Stata: {@code %8.0g} (byte, int), {@code %12.0g} (long),
     * {@code %9.0g} (float), {@code %10.0g} (double), {@code %#s} (str#, at least 9).
     */
    public StataWriter setFormat(String varName, String format) {
        Variable v = variable(varName);
        Objects.requireNonNull(format, "format");
        byte[] b = format.getBytes(StandardCharsets.UTF_8);
        if (!format.startsWith("%") || b.length > layout.formatLen() - 1 || format.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid display format for " + varName + ": " + format);
        }
        v.format = format;
        return this;
    }

    /**
     * Attaches a value-label set to a numeric variable. The set itself is
     * defined with {@link #defineValueLabel}; Stata allows it to be undefined.
     */
    public StataWriter setValueLabel(String varName, String labelName) {
        Variable v = variable(varName);
        checkName(labelName, "Value label name");
        if (!v.type.isNumeric()) {
            throw new IllegalArgumentException("Value labels apply only to numeric variables: " + varName);
        }
        v.valueLabel = labelName;
        return this;
    }

    /** Defines (or replaces) a value-label set mapping values to labels of at most 32,000 characters. */
    public StataWriter defineValueLabel(String labelName, Map<Integer, String> labels) {
        checkName(labelName, "Value label name");
        Objects.requireNonNull(labels, "labels");
        SortedMap<Integer, String> copy = new TreeMap<>();
        for (Map.Entry<Integer, String> e : labels.entrySet()) {
            Integer value = Objects.requireNonNull(e.getKey(), "value");
            copy.put(value, checkText(e.getValue(), MAX_VALUE_LABEL_CHARS, Integer.MAX_VALUE, "Value label text"));
        }
        valueLabels.put(labelName, copy);
        return this;
    }

    // Observations

    /**
     * Adds an observation with one value per variable, in declaration order.
     *
     * @throws IllegalArgumentException if the count is wrong or a value does not fit its variable
     */
    public StataWriter addObservation(Object... values) {
        if (values == null) {
            values = new Object[] {null};
        }
        if (values.length != variables.size()) {
            throw new IllegalArgumentException(
                    "Expected " + variables.size() + " values but got " + values.length);
        }
        Object[] row = new Object[values.length];
        for (int i = 0; i < values.length; i++) {
            row[i] = convert(variables.get(i), values[i]);
        }
        rows.add(row);
        return this;
    }

    /**
     * Adds an observation from a map of variable name to value. Variables
     * absent from the map are written as missing (or empty strings).
     */
    public StataWriter addObservation(Map<String, ?> values) {
        Objects.requireNonNull(values, "values");
        for (String name : values.keySet()) {
            variable(name);
        }
        Object[] ordered = new Object[variables.size()];
        for (int i = 0; i < ordered.length; i++) {
            ordered[i] = values.get(variables.get(i).name);
        }
        return addObservation(ordered);
    }

    private Object convert(Variable v, Object value) {
        try {
            return convert(v.type, value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Observation " + (rows.size() + 1) + ", variable " + v.name + ": " + e.getMessage(), e);
        }
    }

    private static Object convert(StataVarType type, Object value) {
        switch (type.kind()) {
            case BYTE: {
                Long l = integral(value);
                return l == null ? null : (byte) (long) inRange(l, DtaMissing.BYTE_MIN, DtaMissing.BYTE - 1, type);
            }
            case INT: {
                Long l = integral(value);
                return l == null ? null : (short) (long) inRange(l, DtaMissing.INT_MIN, DtaMissing.INT - 1, type);
            }
            case LONG: {
                Long l = integral(value);
                return l == null ? null : (int) (long) inRange(l, DtaMissing.LONG_MIN, DtaMissing.LONG - 1L, type);
            }
            case FLOAT: {
                if (value == null) {
                    return null;
                }
                double d = number(value).doubleValue();
                if (Double.isNaN(d)) {
                    return null;
                }
                float f = (float) d;
                if (!(Math.abs(f) < DtaMissing.FLOAT)) {
                    throw new IllegalArgumentException(value + " is outside the range of float");
                }
                return f;
            }
            case DOUBLE: {
                if (value == null) {
                    return null;
                }
                double d = number(value).doubleValue();
                if (Double.isNaN(d)) {
                    return null;
                }
                if (!(Math.abs(d) < DtaMissing.DOUBLE)) {
                    throw new IllegalArgumentException(value + " is outside the range of double");
                }
                return d;
            }
            case STR: {
                if (value == null) {
                    return new byte[0];
                }
                if (!(value instanceof CharSequence)) {
                    throw new IllegalArgumentException("expected a String for " + type + " but got " + typeName(value));
                }
                String s = value.toString();
                if (s.indexOf('\0') >= 0) {
                    throw new IllegalArgumentException("str# values cannot contain NUL characters");
                }
                byte[] b = s.getBytes(StandardCharsets.UTF_8);
                if (b.length > type.getStringLength()) {
                    throw new IllegalArgumentException(
                            "value needs " + b.length + " bytes but " + type + " holds " + type.getStringLength());
                }
                return b;
            }
            case STRL: {
                if (value == null) {
                    return null;
                }
                if (value instanceof byte[] b) {
                    return b.length == 0 ? null : new StrL(b.clone(), true);
                }
                if (!(value instanceof CharSequence)) {
                    throw new IllegalArgumentException("expected a String or byte[] for strL but got " + typeName(value));
                }
                String s = value.toString();
                if (s.indexOf('\0') >= 0) {
                    throw new IllegalArgumentException("text strL values cannot contain NUL; pass a byte[] instead");
                }
                return s.isEmpty() ? null : new StrL(s.getBytes(StandardCharsets.UTF_8), false);
            }
            default:
                throw new AssertionError(type);
        }
    }

    /** Returns the value as a long, or null for missing (null or NaN). */
    private static Long integral(Object value) {
        if (value == null) {
            return null;
        }
        Number n = number(value);
        if (n instanceof Byte || n instanceof Short || n instanceof Integer || n instanceof Long) {
            return n.longValue();
        }
        if (n instanceof BigInteger b) {
            if (b.bitLength() > 63) {
                throw new IllegalArgumentException(value + " is out of range");
            }
            return b.longValue();
        }
        double d = n.doubleValue();
        if (Double.isNaN(d)) {
            return null;
        }
        if (Double.isInfinite(d) || d != Math.rint(d) || Math.abs(d) > 0x1.0p62) {
            throw new IllegalArgumentException(value + " is not an integer");
        }
        return (long) d;
    }

    private static long inRange(long v, long min, long max, StataVarType type) {
        if (v < min || v > max) {
            throw new IllegalArgumentException(v + " is outside the range of " + type + " (" + min + " to " + max + ")");
        }
        return v;
    }

    private static Number number(Object value) {
        if (!(value instanceof Number n)) {
            throw new IllegalArgumentException("expected a number but got " + typeName(value));
        }
        return n;
    }

    private static String typeName(Object value) {
        return value.getClass().getSimpleName();
    }

    // Writing

    /**
     * Writes the dataset and flushes the stream. May be called only once.
     *
     * @throws IllegalStateException if already called
     */
    public void write() throws IOException {
        if (written) {
            throw new IllegalStateException("write() has already been called");
        }
        written = true;

        String ts = timestampSet ? (timestamp == null ? "" : TIMESTAMP.format(timestamp))
                : TIMESTAMP.format(LocalDateTime.now());

        // <map> records the offsets of sections that follow it, and a stream
        // cannot seek back. So write once to a counting sink to learn the
        // offsets, then write for real with them filled in.
        long[] offsets = new long[MAP_ENTRIES];
        writeFile(new DtaOutput(OutputStream.nullOutputStream(), byteOrder), ts, new long[MAP_ENTRIES], offsets);
        long[] check = new long[MAP_ENTRIES];
        writeFile(new DtaOutput(out, byteOrder), ts, offsets, check);
        if (!Arrays.equals(offsets, check)) {
            throw new IllegalStateException("Internal error: section offsets differ between passes");
        }
        out.flush();
    }

    private void writeFile(DtaOutput o, String ts, long[] map, long[] offsets) throws IOException {
        int k = variables.size();

        offsets[0] = o.position();
        o.tag("<stata_dta><header><release>" + layout.release() + "</release><byteorder>");
        o.tag(byteOrder == ByteOrder.BIG_ENDIAN ? "MSF" : "LSF");
        o.tag("</byteorder><K>");
        o.uN(k, layout.kBytes());
        o.tag("</K><N>");
        o.uN(rows.size(), layout.nBytes());
        o.tag("</N><label>");
        byte[] label = utf8(datasetLabel);
        o.uN(label.length, layout.dataLabelLenBytes());
        o.bytes(label);
        o.tag("</label><timestamp>");
        byte[] tsBytes = ts.getBytes(StandardCharsets.US_ASCII);
        o.u8(tsBytes.length);
        o.bytes(tsBytes);
        o.tag("</timestamp></header>");

        offsets[1] = o.position();
        o.tag("<map>");
        for (long position : map) {
            o.uN(position, 8);
        }
        o.tag("</map>");

        offsets[2] = o.position();
        o.tag("<variable_types>");
        for (Variable v : variables) {
            o.uN(v.type.toTaggedCode(), 2);
        }
        o.tag("</variable_types>");

        offsets[3] = o.position();
        o.tag("<varnames>");
        for (Variable v : variables) {
            o.fixed(utf8(v.name), layout.varNameLen());
        }
        o.tag("</varnames>");

        offsets[4] = o.position();
        o.tag("<sortlist>");
        o.zeros((k + 1L) * layout.sortEntryBytes()); // not sorted
        o.tag("</sortlist>");

        offsets[5] = o.position();
        o.tag("<formats>");
        for (Variable v : variables) {
            o.fixed(utf8(v.format), layout.formatLen());
        }
        o.tag("</formats>");

        offsets[6] = o.position();
        o.tag("<value_label_names>");
        for (Variable v : variables) {
            o.fixed(utf8(v.valueLabel), layout.labelNameLen());
        }
        o.tag("</value_label_names>");

        offsets[7] = o.position();
        o.tag("<variable_labels>");
        for (Variable v : variables) {
            o.fixed(utf8(v.label), layout.varLabelLen());
        }
        o.tag("</variable_labels>");

        offsets[8] = o.position();
        o.tag("<characteristics></characteristics>");

        offsets[9] = o.position();
        o.tag("<data>");
        int vBytes = layout.strlVBytes();
        for (int obs = 0; obs < rows.size(); obs++) {
            Object[] row = rows.get(obs);
            for (int var = 0; var < k; var++) {
                writeValue(o, variables.get(var).type, row[var], var, obs, vBytes);
            }
        }
        o.tag("</data>");

        // GSOs in the order their (v,o) appeared in <data>: by observation, then variable.
        offsets[10] = o.position();
        o.tag("<strls>");
        for (int obs = 0; obs < rows.size(); obs++) {
            Object[] row = rows.get(obs);
            for (int var = 0; var < k; var++) {
                if (row[var] instanceof StrL s) {
                    o.tag("GSO");
                    o.uN(var + 1, 4);
                    o.uN(obs + 1, layout.gsoOBytes());
                    if (s.binary()) {
                        o.u8(StataReader.GSO_BINARY);
                        o.uN(s.data().length, 4);
                        o.bytes(s.data());
                    } else {
                        o.u8(StataReader.GSO_ASCII);
                        o.uN(s.data().length + 1L, 4); // includes the NUL terminator
                        o.bytes(s.data());
                        o.u8(0);
                    }
                }
            }
        }
        o.tag("</strls>");

        offsets[11] = o.position();
        o.tag("<value_labels>");
        for (Map.Entry<String, SortedMap<Integer, String>> set : valueLabels.entrySet()) {
            writeValueLabel(o, set.getKey(), set.getValue());
        }
        o.tag("</value_labels>");

        offsets[12] = o.position();
        o.tag("</stata_dta>");
        offsets[13] = o.position();
    }

    private static void writeValue(DtaOutput o, StataVarType type, Object value, int var, int obs, int vBytes)
            throws IOException {
        switch (type.kind()) {
            case BYTE:
                o.u8(value == null ? DtaMissing.BYTE : (Byte) value);
                break;
            case INT:
                o.i16(value == null ? DtaMissing.INT : (Short) value);
                break;
            case LONG:
                o.i32(value == null ? DtaMissing.LONG : (Integer) value);
                break;
            case FLOAT:
                o.f32(value == null ? DtaMissing.FLOAT : (Float) value);
                break;
            case DOUBLE:
                o.f64(value == null ? DtaMissing.DOUBLE : (Double) value);
                break;
            case STR:
                o.fixed((byte[]) value, type.getStringLength());
                break;
            case STRL:
                // (v,o) = (variable, observation), both 1-based; (0,0) for "".
                boolean empty = value == null;
                o.uN(empty ? 0 : var + 1, vBytes);
                o.uN(empty ? 0 : obs + 1, 8 - vBytes);
                break;
            default:
                throw new AssertionError(type);
        }
    }

    /** Writes {@code <lbl>}: len, name, padding, then n, txtlen, off[], val[], txt. */
    private void writeValueLabel(DtaOutput o, String name, SortedMap<Integer, String> labels) throws IOException {
        int n = labels.size();
        List<byte[]> texts = new ArrayList<>(n);
        long txtLen = 0;
        for (String text : labels.values()) {
            byte[] b = utf8(text);
            texts.add(b);
            txtLen += b.length + 1;
        }
        long tableLen = 8L + 8L * n + txtLen;
        if (tableLen > Integer.MAX_VALUE) {
            throw new IllegalStateException("Value label " + name + " is too large");
        }

        o.tag("<lbl>");
        o.i32((int) tableLen);
        o.fixed(utf8(name), layout.labelNameLen());
        o.zeros(3);
        o.i32(n);
        o.i32((int) txtLen);
        int off = 0;
        for (byte[] b : texts) {
            o.i32(off);
            off += b.length + 1;
        }
        for (int value : labels.keySet()) {
            o.i32(value);
        }
        for (byte[] b : texts) {
            o.bytes(b);
            o.u8(0);
        }
        o.tag("</lbl>");
    }

    // Validation helpers

    private Variable variable(String name) {
        Variable v = byName.get(name);
        if (v == null) {
            throw new IllegalArgumentException("Variable not found: " + name);
        }
        return v;
    }

    /** Stata names: 1-32 characters, a letter or _ followed by letters, digits or _, not reserved. */
    private static void checkName(String name, String what) {
        Objects.requireNonNull(name, what);
        int chars = name.codePointCount(0, name.length());
        if (chars < 1 || chars > MAX_NAME_CHARS) {
            throw new IllegalArgumentException(what + " must be 1-" + MAX_NAME_CHARS + " characters: \"" + name + "\"");
        }
        int first = name.codePointAt(0);
        boolean valid = (first == '_' || Character.isLetter(first))
                && name.codePoints().allMatch(c -> c == '_' || Character.isLetterOrDigit(c));
        if (!valid) {
            throw new IllegalArgumentException(what + " is not a valid Stata name: \"" + name + "\"");
        }
        if (RESERVED_NAMES.contains(name) || STR_TYPE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(what + " is a reserved word: \"" + name + "\"");
        }
    }

    private static String checkText(String text, int maxChars, int maxBytes, String what) {
        Objects.requireNonNull(text, what);
        if (text.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(what + " cannot contain NUL characters");
        }
        if (text.codePointCount(0, text.length()) > maxChars || utf8(text).length > maxBytes) {
            throw new IllegalArgumentException(what + " is longer than " + maxChars + " characters");
        }
        return text;
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String defaultFormat(StataVarType type) {
        switch (type.kind()) {
            case BYTE:
            case INT: return "%8.0g";
            case LONG: return "%12.0g";
            case FLOAT: return "%9.0g";
            case DOUBLE: return "%10.0g";
            case STR: return "%" + Math.max(9, type.getStringLength()) + "s";
            default: return "%9s";
        }
    }

    // Getters

    /** Returns the format being written, e.g. {@code "119"}. */
    public String getFormat() {
        return Integer.toString(layout.release());
    }

    public ByteOrder getByteOrder() {
        return byteOrder;
    }

    public int getNumVars() {
        return variables.size();
    }

    public int getNumObs() {
        return rows.size();
    }

    /** Closes the underlying stream. Does not write; call {@link #write()} first. */
    @Override
    public void close() throws IOException {
        out.close();
    }
}
