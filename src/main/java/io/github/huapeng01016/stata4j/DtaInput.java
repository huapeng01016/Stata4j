package io.github.huapeng01016.stata4j;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Byte-order-aware primitive reads over a .dta stream. Truncated input
 * surfaces as {@link java.io.EOFException}.
 */
final class DtaInput {

    private static final int MAX_TAG_LEN = 64;

    private final BufferedInputStream in;
    private final DataInputStream dis;
    private ByteOrder order = ByteOrder.LITTLE_ENDIAN;

    DtaInput(BufferedInputStream in) {
        this.in = in;
        this.dis = new DataInputStream(in);
    }

    void setOrder(ByteOrder order) {
        this.order = order;
    }

    ByteOrder order() {
        return order;
    }

    /** Returns the next byte without consuming it, or -1 at end of stream. */
    int peek() throws IOException {
        in.mark(1);
        int b = in.read();
        in.reset();
        return b;
    }

    /** True if the stream continues with {@code tag}; consumes nothing. */
    boolean nextIs(String tag) throws IOException {
        byte[] want = tag.getBytes(StandardCharsets.US_ASCII);
        in.mark(want.length);
        byte[] got = in.readNBytes(want.length);
        in.reset();
        return Arrays.equals(want, got);
    }

    /** Consumes {@code tag}, failing if the stream does not continue with it. */
    void expect(String tag) throws IOException, StataFormatException {
        byte[] want = tag.getBytes(StandardCharsets.US_ASCII);
        byte[] got = bytes(want.length);
        if (!Arrays.equals(want, got)) {
            String seen = new String(got, 0, Math.min(got.length, MAX_TAG_LEN), StandardCharsets.ISO_8859_1);
            throw new StataFormatException("Expected " + tag + " but found " + seen);
        }
    }

    byte[] bytes(int n) throws IOException {
        byte[] b = new byte[n];
        dis.readFully(b);
        return b;
    }

    void skip(long n) throws IOException {
        in.skipNBytes(n);
    }

    int u8() throws IOException {
        return dis.readUnsignedByte();
    }

    /** Reads an unsigned integer of {@code n} bytes (1-8) in the current byte order. */
    long uN(int n) throws IOException {
        byte[] b = bytes(n);
        long v = 0;
        for (int i = 0; i < n; i++) {
            int idx = order == ByteOrder.BIG_ENDIAN ? i : n - 1 - i;
            v = (v << 8) | (b[idx] & 0xFF);
        }
        return v;
    }

    int u16() throws IOException {
        return (int) uN(2);
    }

    long u32() throws IOException {
        return uN(4);
    }

    byte i8() throws IOException {
        return dis.readByte();
    }

    short i16() throws IOException {
        return (short) uN(2);
    }

    int i32() throws IOException {
        return (int) uN(4);
    }

    float f32() throws IOException {
        return Float.intBitsToFloat(i32());
    }

    double f64() throws IOException {
        return Double.longBitsToDouble(uN(8));
    }

    /** Reads a fixed-width field and decodes it up to the first NUL. */
    String fixedString(int n, Charset cs) throws IOException {
        return cString(bytes(n), 0, n, cs);
    }

    /** Decodes {@code b[from, to)} up to the first NUL. */
    static String cString(byte[] b, int from, int to, Charset cs) {
        int end = from;
        while (end < to && b[end] != 0) {
            end++;
        }
        return new String(b, from, end - from, cs);
    }
}
