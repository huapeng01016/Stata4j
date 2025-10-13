package io.github.huapeng01016.stata4j;

import org.junit.Test;
import static org.junit.Assert.*;

import java.io.IOException;

/**
 * Test class for StataDatasetReader
 */
public class StataDatasetReaderTest {
    
    @Test
    public void testConstructor() {
        StataDatasetReader reader = new StataDatasetReader("test.dta");
        assertNotNull(reader);
        assertEquals(0, reader.getNumberOfVariables());
        assertEquals(0, reader.getNumberOfObservations());
    }
    
    @Test
    public void testGetVariableNames() {
        StataDatasetReader reader = new StataDatasetReader("test.dta");
        assertNotNull(reader.getVariableNames());
        assertTrue(reader.getVariableNames().isEmpty());
    }
    
    @Test
    public void testGetVariableTypes() {
        StataDatasetReader reader = new StataDatasetReader("test.dta");
        assertNotNull(reader.getVariableTypes());
        assertTrue(reader.getVariableTypes().isEmpty());
    }
    
    @Test
    public void testGetVariableLabels() {
        StataDatasetReader reader = new StataDatasetReader("test.dta");
        assertNotNull(reader.getVariableLabels());
        assertTrue(reader.getVariableLabels().isEmpty());
    }
    
    @Test
    public void testGetDataEmpty() {
        StataDatasetReader reader = new StataDatasetReader("test.dta");
        Object[][] data = reader.getData();
        assertNotNull(data);
        assertEquals(0, data.length);
    }
    
    @Test(expected = IndexOutOfBoundsException.class)
    public void testGetValueInvalidIndex() {
        StataDatasetReader reader = new StataDatasetReader("test.dta");
        reader.getValue(0, 0);
    }
    
    @Test(expected = IllegalArgumentException.class)
    public void testGetValueInvalidName() {
        StataDatasetReader reader = new StataDatasetReader("test.dta");
        reader.getValue(0, "nonexistent");
    }
    
    @Test(expected = IOException.class)
    public void testReadNonExistentFile() throws IOException {
        StataDatasetReader reader = new StataDatasetReader("nonexistent.dta");
        reader.read();
    }
    
    @Test
    public void testVariableTypeEnum() {
        assertEquals(1, StataDatasetReader.VariableType.BYTE.getSize());
        assertEquals(2, StataDatasetReader.VariableType.INT.getSize());
        assertEquals(4, StataDatasetReader.VariableType.LONG.getSize());
        assertEquals(4, StataDatasetReader.VariableType.FLOAT.getSize());
        assertEquals(8, StataDatasetReader.VariableType.DOUBLE.getSize());
        assertEquals(0, StataDatasetReader.VariableType.STRING.getSize());
    }
    
    @Test
    public void testPrintSummary() {
        StataDatasetReader reader = new StataDatasetReader("test.dta");
        // Just ensure it doesn't throw an exception
        reader.printSummary();
    }
    
    @Test
    public void testPrintData() {
        StataDatasetReader reader = new StataDatasetReader("test.dta");
        // Just ensure it doesn't throw an exception
        reader.printData(10);
    }
}
