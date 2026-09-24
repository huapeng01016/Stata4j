package io.github.huapeng01016.stata4j;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;

/**
 * Builds legacy (113-115) .dta files byte by byte, for the cases pandas cannot
 * write: formats 113/115 and extended missing values (.a-.z).
 *
 * <p>Variables: id byte, code int, big long, x float, y double, name str8,
 * grade byte (value label "gradelbl"). Observation 2 holds the largest
 * non-missing values; observation 3 holds a missing value in every numeric column.
 */
final class LegacyDtaBuilder {

    static final Charset CP1252 = Charset.forName("windows-1252");

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteOrder order;

    private LegacyDtaBuilder(ByteOrder order) {
        this.order = order;
    }

    static byte[] build(int release, ByteOrder order) {
        LegacyDtaBuilder b = new LegacyDtaBuilder(order);
        int fmtLen = release == 113 ? 12 : 49;
        String[] names = {"id", "code", "big", "x", "y", "name", "grade"};
        int[] types = {251, 252, 253, 254, 255, 8, 251};
        int k = names.length;

        b.u8(release);
        b.u8(order == ByteOrder.BIG_ENDIAN ? 0x01 : 0x02);
        b.u8(1);
        b.u8(0);
        b.i16(k);
        b.i32(3);
        b.str("Legacy fixture", 81);
        b.str("24 Sep 2026 09:00", 18);
        for (int t : types) {
            b.u8(t);
        }
        for (String n : names) {
            b.str(n, 33);
        }
        for (int i = 0; i <= k; i++) {
            b.i16(0);
        }
        for (int i = 0; i < k; i++) {
            b.str(i < 5 ? "%9.0g" : "%9s", fmtLen);
        }
        for (int i = 0; i < k; i++) {
            b.str(i == k - 1 ? "gradelbl" : "", 33);
        }
        for (String n : names) {
            b.str("label of " + n, 81);
        }

        // One characteristic, which the reader must skip, then the terminator.
        byte[] ch = "_dta\0note0\0".getBytes(CP1252);
        b.u8(1);
        b.i32(ch.length);
        b.raw(ch);
        b.u8(0);
        b.i32(0);

        // obs 1
        b.u8(1);
        b.i16(1000);
        b.i32(100000);
        b.f32(1.5f);
        b.f64(3.125);
        b.str("Alice", 8);
        b.u8(0);
        // obs 2: largest valid values; name uses a windows-1252 character
        b.u8(100);
        b.i16(32740);
        b.i32(2147483620);
        b.f32(-2.25f);
        b.f64(-1e300);
        b.str("Zoë", 8);
        b.u8(1);
        // obs 3: missing values (.a, .z, ., .a, .z, then . for grade)
        b.u8(102);
        b.i16(32767);
        b.i32(2147483621);
        b.i32(0x7f000800);
        b.i64(0x7fe01a0000000000L);
        b.str("12345678", 8); // exactly fills the field, no NUL
        b.u8(101);

        // Value label "gradelbl": {0: "low", 1: "high"}
        byte[] txt = "low\0high\0".getBytes(CP1252);
        int tableLen = 4 + 4 + 2 * 4 + 2 * 4 + txt.length;
        b.i32(tableLen);
        b.str("gradelbl", 33);
        b.raw(new byte[3]);
        b.i32(2);
        b.i32(txt.length);
        b.i32(0);
        b.i32(4);
        b.i32(0);
        b.i32(1);
        b.raw(txt);

        return b.out.toByteArray();
    }

    private void raw(byte[] bytes) {
        out.writeBytes(bytes);
    }

    private void u8(int v) {
        out.write(v);
    }

    private void i16(int v) {
        raw(ByteBuffer.allocate(2).order(order).putShort((short) v).array());
    }

    private void i32(int v) {
        raw(ByteBuffer.allocate(4).order(order).putInt(v).array());
    }

    private void i64(long v) {
        raw(ByteBuffer.allocate(8).order(order).putLong(v).array());
    }

    private void f32(float v) {
        raw(ByteBuffer.allocate(4).order(order).putFloat(v).array());
    }

    private void f64(double v) {
        raw(ByteBuffer.allocate(8).order(order).putDouble(v).array());
    }

    private void str(String s, int width) {
        byte[] field = new byte[width];
        byte[] b = s.getBytes(CP1252);
        System.arraycopy(b, 0, field, 0, Math.min(b.length, width));
        raw(field);
    }
}
