package io.github.huapeng01016.stata4j;

/**
 * Stata's system missing value {@code .} for each numeric type. Every stored
 * value at or above it is missing ({@code .a}-{@code .z} follow it), so these
 * are also one past the largest non-missing value.
 */
final class DtaMissing {

    static final byte BYTE = 101;
    static final short INT = 32741;
    static final int LONG = 2147483621;
    static final float FLOAT = 0x1.0p127f;
    static final double DOUBLE = 0x1.0p1023;

    /** Smallest valid values; Stata's ranges are symmetric except for the integer types. */
    static final byte BYTE_MIN = -127;
    static final short INT_MIN = -32767;
    static final int LONG_MIN = -2147483647;

    private DtaMissing() {
    }
}
