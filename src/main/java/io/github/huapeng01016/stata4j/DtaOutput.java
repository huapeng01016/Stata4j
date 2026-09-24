package io.github.huapeng01016.stata4j;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Byte-order-aware primitive writes that track the number of bytes written,
 * which {@link StataWriter} uses as the file position for the {@code <map>}.
 */
final class DtaOutput {

    private final OutputStream out;
    private final ByteOrder order;
    private long position;

    DtaOutput(OutputStream out, ByteOrder order) {
        this.out = out;
        this.order = order;
    }

    long position() {
        return position;
    }

    void tag(String tag) throws IOException {
        bytes(tag.getBytes(StandardCharsets.US_ASCII));
    }

    void bytes(byte[] b) throws IOException {
        out.write(b);
        position += b.length;
    }

    void zeros(long n) throws IOException {
        for (long i = 0; i < n; i++) {
            out.write(0);
        }
        position += n;
    }

    void u8(int v) throws IOException {
        out.write(v);
        position++;
    }

    /** Writes the low {@code n} bytes (1-8) of {@code v} in the current byte order. */
    void uN(long v, int n) throws IOException {
        for (int i = 0; i < n; i++) {
            int shift = order == ByteOrder.BIG_ENDIAN ? 8 * (n - 1 - i) : 8 * i;
            out.write((int) (v >>> shift) & 0xFF);
        }
        position += n;
    }

    void i16(short v) throws IOException {
        uN(v, 2);
    }

    void i32(int v) throws IOException {
        uN(v, 4);
    }

    void f32(float v) throws IOException {
        uN(Float.floatToRawIntBits(v), 4);
    }

    void f64(double v) throws IOException {
        uN(Double.doubleToRawLongBits(v), 8);
    }

    /** Writes {@code b} NUL-padded to {@code width}; the caller guarantees it fits. */
    void fixed(byte[] b, int width) throws IOException {
        bytes(b);
        zeros(width - b.length);
    }
}
