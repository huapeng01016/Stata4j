package io.github.huapeng01016.stata4j;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StataVarTypeTest {

    @Test
    void legacyCodes() throws StataFormatException {
        assertEquals(StataVarType.BYTE, StataVarType.fromLegacyCode(251));
        assertEquals(StataVarType.INT, StataVarType.fromLegacyCode(252));
        assertEquals(StataVarType.LONG, StataVarType.fromLegacyCode(253));
        assertEquals(StataVarType.FLOAT, StataVarType.fromLegacyCode(254));
        assertEquals(StataVarType.DOUBLE, StataVarType.fromLegacyCode(255));
        assertEquals(StataVarType.str(1), StataVarType.fromLegacyCode(1));
        assertEquals(StataVarType.str(244), StataVarType.fromLegacyCode(244));
        assertThrows(StataFormatException.class, () -> StataVarType.fromLegacyCode(0));
        assertThrows(StataFormatException.class, () -> StataVarType.fromLegacyCode(245));
        assertThrows(StataFormatException.class, () -> StataVarType.fromLegacyCode(250));
    }

    @Test
    void taggedCodes() throws StataFormatException {
        assertEquals(StataVarType.BYTE, StataVarType.fromTaggedCode(65530));
        assertEquals(StataVarType.INT, StataVarType.fromTaggedCode(65529));
        assertEquals(StataVarType.LONG, StataVarType.fromTaggedCode(65528));
        assertEquals(StataVarType.FLOAT, StataVarType.fromTaggedCode(65527));
        assertEquals(StataVarType.DOUBLE, StataVarType.fromTaggedCode(65526));
        assertEquals(StataVarType.STRL, StataVarType.fromTaggedCode(32768));
        assertEquals(StataVarType.ALIAS, StataVarType.fromTaggedCode(65525));
        assertEquals(StataVarType.str(2045), StataVarType.fromTaggedCode(2045));
        assertThrows(StataFormatException.class, () -> StataVarType.fromTaggedCode(0));
        assertThrows(StataFormatException.class, () -> StataVarType.fromTaggedCode(2046));
        assertThrows(StataFormatException.class, () -> StataVarType.fromTaggedCode(32767));
        // 251 is a numeric code only in legacy files; in 117+ it means str251.
        assertEquals(StataVarType.str(251), StataVarType.fromTaggedCode(251));
    }

    @Test
    void strWidthBounds() {
        assertThrows(IllegalArgumentException.class, () -> StataVarType.str(0));
        assertThrows(IllegalArgumentException.class, () -> StataVarType.str(2046));
    }

    @Test
    void properties() {
        assertTrue(StataVarType.DOUBLE.isNumeric());
        assertFalse(StataVarType.DOUBLE.isString());
        assertEquals(8, StataVarType.DOUBLE.getByteWidth());

        StataVarType s = StataVarType.str(10);
        assertTrue(s.isString());
        assertFalse(s.isStrL());
        assertEquals(10, s.getStringLength());
        assertEquals(10, s.getByteWidth());

        assertTrue(StataVarType.STRL.isString());
        assertTrue(StataVarType.STRL.isStrL());
        assertThrows(IllegalStateException.class, StataVarType.STRL::getStringLength);
        assertThrows(IllegalStateException.class, StataVarType.BYTE::getStringLength);

        assertTrue(StataVarType.ALIAS.isAlias());
        assertFalse(StataVarType.ALIAS.isNumeric());
        assertFalse(StataVarType.ALIAS.isString());
        assertEquals(0, StataVarType.ALIAS.getByteWidth());
        assertThrows(IllegalStateException.class, StataVarType.ALIAS::getStringLength);
    }

    @Test
    void equalityAndNames() {
        assertEquals(StataVarType.str(10), StataVarType.str(10));
        assertEquals(StataVarType.str(10).hashCode(), StataVarType.str(10).hashCode());
        assertNotEquals(StataVarType.str(10), StataVarType.str(11));
        assertNotEquals(StataVarType.LONG, StataVarType.FLOAT); // same width, different kind
        assertEquals("str10", StataVarType.str(10).toString());
        assertEquals("strL", StataVarType.STRL.toString());
        assertEquals("double", StataVarType.DOUBLE.toString());
        assertEquals("alias", StataVarType.ALIAS.toString());
    }
}
