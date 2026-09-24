package io.github.huapeng01016.stata4j;

import java.util.Objects;

/**
 * A Stata storage type: one of the numeric types, a fixed-width {@code str#}
 * (1 to 2045 bytes), {@code strL}, or {@code alias} (formats 120/121).
 */
public final class StataVarType {

    /** Maximum width of a fixed-length string in formats 117 and later. */
    public static final int MAX_STR_WIDTH = 2045;

    enum Kind { BYTE, INT, LONG, FLOAT, DOUBLE, STR, STRL, ALIAS }

    public static final StataVarType BYTE = new StataVarType(Kind.BYTE, 1);
    public static final StataVarType INT = new StataVarType(Kind.INT, 2);
    public static final StataVarType LONG = new StataVarType(Kind.LONG, 4);
    public static final StataVarType FLOAT = new StataVarType(Kind.FLOAT, 4);
    public static final StataVarType DOUBLE = new StataVarType(Kind.DOUBLE, 8);
    /** Long string; each cell in the data section is an 8-byte (v,o) reference. */
    public static final StataVarType STRL = new StataVarType(Kind.STRL, 8);
    /**
     * Alias variable (Stata 18+, formats 120/121): a reference to a variable in
     * another frame. It stores no data in the file, so its values read as null.
     */
    public static final StataVarType ALIAS = new StataVarType(Kind.ALIAS, 0);

    private final Kind kind;
    private final int width;

    private StataVarType(Kind kind, int width) {
        this.kind = kind;
        this.width = width;
    }

    /**
     * Returns the fixed-width string type {@code str<width>}.
     *
     * @throws IllegalArgumentException if width is not in 1..2045
     */
    public static StataVarType str(int width) {
        if (width < 1 || width > MAX_STR_WIDTH) {
            throw new IllegalArgumentException("String width out of range: " + width);
        }
        return new StataVarType(Kind.STR, width);
    }

    /** Type codes used by formats 113-115: 1-244 are str#, 251-255 numeric. */
    static StataVarType fromLegacyCode(int code) throws StataFormatException {
        switch (code) {
            case 251: return BYTE;
            case 252: return INT;
            case 253: return LONG;
            case 254: return FLOAT;
            case 255: return DOUBLE;
            default:
                if (code >= 1 && code <= 244) {
                    return str(code);
                }
                throw new StataFormatException("Invalid variable type code: " + code);
        }
    }

    /**
     * Type codes used by formats 117+: 1-2045 are str#, 32768 strL, 65525 alias,
     * 65526-65530 numeric. The caller must reject alias in releases before 120.
     */
    static StataVarType fromTaggedCode(int code) throws StataFormatException {
        switch (code) {
            case 32768: return STRL;
            case 65525: return ALIAS;
            case 65526: return DOUBLE;
            case 65527: return FLOAT;
            case 65528: return LONG;
            case 65529: return INT;
            case 65530: return BYTE;
            default:
                if (code >= 1 && code <= MAX_STR_WIDTH) {
                    return str(code);
                }
                throw new StataFormatException("Invalid variable type code: " + code);
        }
    }

    /** Inverse of {@link #fromTaggedCode}. */
    int toTaggedCode() {
        switch (kind) {
            case STR: return width;
            case STRL: return 32768;
            case ALIAS: return 65525;
            case DOUBLE: return 65526;
            case FLOAT: return 65527;
            case LONG: return 65528;
            case INT: return 65529;
            case BYTE: return 65530;
            default: throw new AssertionError(kind);
        }
    }

    Kind kind() {
        return kind;
    }

    public boolean isNumeric() {
        switch (kind) {
            case BYTE: case INT: case LONG: case FLOAT: case DOUBLE: return true;
            default: return false;
        }
    }

    /** True for both {@code str#} and {@code strL}. */
    public boolean isString() {
        return kind == Kind.STR || kind == Kind.STRL;
    }

    public boolean isAlias() {
        return kind == Kind.ALIAS;
    }

    public boolean isStrL() {
        return kind == Kind.STRL;
    }

    /**
     * Returns the declared width of a {@code str#} type.
     *
     * @throws IllegalStateException if this is not a {@code str#} type
     */
    public int getStringLength() {
        if (kind != Kind.STR) {
            throw new IllegalStateException("Not a str# type: " + this);
        }
        return width;
    }

    /** Number of bytes one value of this type occupies in the data section (0 for alias). */
    public int getByteWidth() {
        return width;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof StataVarType)) return false;
        StataVarType other = (StataVarType) o;
        return kind == other.kind && width == other.width;
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, width);
    }

    /** Stata's name for the type, e.g. {@code double}, {@code str10}, {@code strL}. */
    @Override
    public String toString() {
        switch (kind) {
            case STR: return "str" + width;
            case STRL: return "strL";
            default: return kind.name().toLowerCase();
        }
    }
}
