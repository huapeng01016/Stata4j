package io.github.huapeng01016.stata4j;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StataReaderTest {
    
    @Test
    void testStataReaderConstruction() throws IOException {
        // Test that StataReader can be constructed from different sources
        assertThrows(FileNotFoundException.class, () -> new StataReader("nonexistent.dta"));
    }
    
    @Test
    void testInvalidFormat(@TempDir Path tempDir) throws IOException {
        // Create a file with invalid format
        File testFile = tempDir.resolve("invalid.dta").toFile();
        try (FileOutputStream fos = new FileOutputStream(testFile)) {
            fos.write("999".getBytes()); // Invalid format
        }
        
        StataReader reader = new StataReader(testFile);
        assertThrows(StataFormatException.class, reader::read);
        reader.close();
    }
    
    @Test
    void testSimpleStataFile(@TempDir Path tempDir) throws IOException, StataFormatException {
        // Create a minimal valid Stata file
        File testFile = tempDir.resolve("test.dta").toFile();
        createMinimalStataFile(testFile);
        
        try (StataReader reader = new StataReader(testFile)) {
            reader.read();
            
            assertEquals("117", reader.getFormat());
            assertEquals(2, reader.getNumVars());
            assertEquals(3, reader.getNumObs());
            
            List<String> varNames = reader.getVarNames();
            assertEquals(2, varNames.size());
            assertEquals("id", varNames.get(0));
            assertEquals("name", varNames.get(1));
            
            List<StataVarType> varTypes = reader.getVarTypes();
            assertEquals(2, varTypes.size());
            assertEquals(StataVarType.BYTE, varTypes.get(0));
            assertEquals(StataVarType.STR10, varTypes.get(1));
            
            List<Map<String, Object>> data = reader.getData();
            assertEquals(3, data.size());
            
            // Check first observation
            Map<String, Object> obs1 = data.get(0);
            assertEquals((byte)1, obs1.get("id"));
            assertEquals("Alice", obs1.get("name"));
            
            // Check second observation
            Map<String, Object> obs2 = data.get(1);
            assertEquals((byte)2, obs2.get("id"));
            assertEquals("Bob", obs2.get("name"));
            
            // Check third observation
            Map<String, Object> obs3 = data.get(2);
            assertEquals((byte)3, obs3.get("id"));
            assertEquals("Charlie", obs3.get("name"));
        }
    }
    
    @Test
    void testGetObservation(@TempDir Path tempDir) throws IOException, StataFormatException {
        File testFile = tempDir.resolve("test.dta").toFile();
        createMinimalStataFile(testFile);
        
        try (StataReader reader = new StataReader(testFile)) {
            reader.read();
            
            Map<String, Object> obs = reader.getObservation(1);
            assertEquals((byte)2, obs.get("id"));
            assertEquals("Bob", obs.get("name"));
            
            assertThrows(IndexOutOfBoundsException.class, () -> reader.getObservation(-1));
            assertThrows(IndexOutOfBoundsException.class, () -> reader.getObservation(3));
        }
    }
    
    @Test
    void testStataVarType() throws StataFormatException {
        assertEquals(251, StataVarType.BYTE.getCode());
        assertEquals(252, StataVarType.INT.getCode());
        assertEquals(253, StataVarType.LONG.getCode());
        assertEquals(254, StataVarType.FLOAT.getCode());
        assertEquals(255, StataVarType.DOUBLE.getCode());
        
        assertTrue(StataVarType.BYTE.isNumeric());
        assertFalse(StataVarType.BYTE.isString());
        
        assertTrue(StataVarType.STR10.isString());
        assertFalse(StataVarType.STR10.isNumeric());
        assertEquals(10, StataVarType.STR10.getStringLength());
        
        assertEquals(StataVarType.BYTE, StataVarType.fromCode(251));
        assertEquals(StataVarType.STR10, StataVarType.fromCode(10));
        
        assertThrows(StataFormatException.class, () -> StataVarType.fromCode(250));
        assertThrows(IllegalStateException.class, () -> StataVarType.BYTE.getStringLength());
    }
    
    private void createMinimalStataFile(File file) throws IOException {
        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(file))) {
            // Header
            dos.write("117".getBytes()); // Format
            dos.writeByte(0x01); // Byte order (little-endian)
            dos.writeByte(0x01); // File type
            dos.writeByte(0x00); // Unused
            writeShortLE(dos, (short) 2); // Number of variables
            writeIntLE(dos, 3); // Number of observations
            writeString(dos, "", 81); // Dataset label
            writeString(dos, "13 Oct 2025 10:15", 18); // Timestamp
            
            // Variable types
            dos.writeByte(251); // BYTE
            dos.writeByte(10);  // STR10
            
            // Variable names
            writeString(dos, "id", 33);
            writeString(dos, "name", 33);
            
            // Sort order (2 vars + 1)
            writeShortLE(dos, (short) 0);
            writeShortLE(dos, (short) 0);
            writeShortLE(dos, (short) 0);
            
            // Formats
            writeString(dos, "%8.0g", 49);
            writeString(dos, "%10s", 49);
            
            // Value label names
            writeString(dos, "", 33);
            writeString(dos, "", 33);
            
            // Variable labels
            writeString(dos, "ID", 81);
            writeString(dos, "Name", 81);
            
            // Expansion fields
            dos.writeByte(0); // End of expansion fields
            
            // Data
            // Observation 1
            dos.writeByte(1);
            writeString(dos, "Alice", 10);
            
            // Observation 2
            dos.writeByte(2);
            writeString(dos, "Bob", 10);
            
            // Observation 3
            dos.writeByte(3);
            writeString(dos, "Charlie", 10);
            
            // Value labels (empty)
        }
    }
    
    private void writeShortLE(DataOutputStream dos, short value) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putShort(value);
        dos.write(buffer.array());
    }
    
    private void writeIntLE(DataOutputStream dos, int value) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(value);
        dos.write(buffer.array());
    }
    
    private void writeString(DataOutputStream dos, String str, int length) throws IOException {
        byte[] bytes = new byte[length];
        byte[] strBytes = str.getBytes("UTF-8");
        System.arraycopy(strBytes, 0, bytes, 0, Math.min(strBytes.length, length));
        dos.write(bytes);
    }
}
