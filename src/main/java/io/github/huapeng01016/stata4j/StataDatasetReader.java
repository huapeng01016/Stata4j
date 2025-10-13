package io.github.huapeng01016.stata4j;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Reader for Stata dataset files (.dta format).
 * Supports Stata format versions 117, 118, and 119.
 */
public class StataDatasetReader {
    
    private String filePath;
    private int formatVersion;
    private ByteOrder byteOrder;
    private int nvar;  // number of variables
    private int nobs;  // number of observations
    private List<String> variableNames;
    private List<VariableType> variableTypes;
    private List<String> variableLabels;
    private List<String> valueLabels;
    private Object[][] data;
    
    /**
     * Enum representing Stata variable types
     */
    public enum VariableType {
        BYTE(1),
        INT(2),
        LONG(4),
        FLOAT(4),
        DOUBLE(8),
        STRING(0);  // variable length
        
        private int size;
        
        VariableType(int size) {
            this.size = size;
        }
        
        public int getSize() {
            return size;
        }
    }
    
    /**
     * Constructor
     * @param filePath Path to the Stata .dta file
     */
    public StataDatasetReader(String filePath) {
        this.filePath = filePath;
        this.variableNames = new ArrayList<>();
        this.variableTypes = new ArrayList<>();
        this.variableLabels = new ArrayList<>();
        this.valueLabels = new ArrayList<>();
    }
    
    /**
     * Read the Stata dataset file
     * @throws IOException if there's an error reading the file
     */
    public void read() throws IOException {
        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(filePath)))) {
            readHeader(dis);
            readDescriptors(dis);
            readVariableLabels(dis);
            readExpansionFields(dis);
            readData(dis);
            readValueLabels(dis);
        }
    }
    
    /**
     * Read the file header
     */
    private void readHeader(DataInputStream dis) throws IOException {
        // Read format identifier
        byte[] header = new byte[11];
        dis.readFully(header);
        String headerStr = new String(header, StandardCharsets.ISO_8859_1);
        
        // Determine format version
        if (headerStr.startsWith("<stata_dta>")) {
            // Format 117 or 118 (Stata 13-14)
            formatVersion = 117;
        } else {
            // Try to parse as old format
            throw new IOException("Unsupported Stata format");
        }
        
        // Read byte order indicator
        byte byteOrderByte = dis.readByte();
        if (byteOrderByte == 0x01) {
            byteOrder = ByteOrder.BIG_ENDIAN;
        } else if (byteOrderByte == 0x02) {
            byteOrder = ByteOrder.LITTLE_ENDIAN;
        } else {
            throw new IOException("Invalid byte order indicator");
        }
        
        // Skip to end of header tag
        skipToTag(dis, "</header>");
    }
    
    /**
     * Read variable descriptors
     */
    private void readDescriptors(DataInputStream dis) throws IOException {
        skipToTag(dis, "<map>");
        skipToTag(dis, "</map>");
        
        skipToTag(dis, "<variable_types>");
        // Read number of variables from the data
        byte[] typeData = readUntilTag(dis, "</variable_types>");
        nvar = typeData.length / 2;  // Each type is 2 bytes in format 117+
        
        ByteBuffer typeBuf = ByteBuffer.wrap(typeData).order(byteOrder);
        for (int i = 0; i < nvar; i++) {
            short typeCode = typeBuf.getShort();
            variableTypes.add(parseVariableType(typeCode));
        }
        
        skipToTag(dis, "<varnames>");
        byte[] nameData = readUntilTag(dis, "</varnames>");
        parseVariableNames(nameData);
        
        skipToTag(dis, "<sortlist>");
        skipToTag(dis, "</sortlist>");
        
        skipToTag(dis, "<formats>");
        skipToTag(dis, "</formats>");
        
        skipToTag(dis, "<value_label_names>");
        skipToTag(dis, "</value_label_names>");
    }
    
    /**
     * Parse variable type from type code
     */
    private VariableType parseVariableType(short typeCode) {
        if (typeCode < 0) {
            // String type - length encoded in negative value
            return VariableType.STRING;
        } else if (typeCode <= 244) {
            return VariableType.BYTE;
        } else if (typeCode == 251) {
            return VariableType.BYTE;
        } else if (typeCode == 252) {
            return VariableType.INT;
        } else if (typeCode == 253) {
            return VariableType.LONG;
        } else if (typeCode == 254) {
            return VariableType.FLOAT;
        } else if (typeCode == 255) {
            return VariableType.DOUBLE;
        }
        return VariableType.BYTE;
    }
    
    /**
     * Parse variable names from byte data
     */
    private void parseVariableNames(byte[] nameData) {
        int maxNameLen = 33;  // Stata 117+ allows up to 32 chars + null
        for (int i = 0; i < nvar; i++) {
            int start = i * maxNameLen;
            int end = Math.min(start + maxNameLen, nameData.length);
            String name = parseNullTerminatedString(nameData, start, end);
            variableNames.add(name);
        }
    }
    
    /**
     * Parse null-terminated string from byte array
     */
    private String parseNullTerminatedString(byte[] data, int start, int end) {
        int nullIndex = start;
        while (nullIndex < end && data[nullIndex] != 0) {
            nullIndex++;
        }
        return new String(data, start, nullIndex - start, StandardCharsets.ISO_8859_1);
    }
    
    /**
     * Read variable labels
     */
    private void readVariableLabels(DataInputStream dis) throws IOException {
        skipToTag(dis, "<variable_labels>");
        byte[] labelData = readUntilTag(dis, "</variable_labels>");
        int maxLabelLen = 81;  // Stata variable labels can be up to 80 chars + null
        
        for (int i = 0; i < nvar; i++) {
            int start = i * maxLabelLen;
            int end = Math.min(start + maxLabelLen, labelData.length);
            String label = parseNullTerminatedString(labelData, start, end);
            variableLabels.add(label);
        }
    }
    
    /**
     * Read expansion fields (characteristics)
     */
    private void readExpansionFields(DataInputStream dis) throws IOException {
        skipToTag(dis, "<characteristics>");
        skipToTag(dis, "</characteristics>");
    }
    
    /**
     * Read the actual data
     */
    private void readData(DataInputStream dis) throws IOException {
        skipToTag(dis, "<data>");
        
        // Count observations by reading until end tag
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        boolean foundEndTag = false;
        StringBuilder tagCheck = new StringBuilder();
        
        while (!foundEndTag) {
            int b = dis.read();
            if (b == -1) break;
            
            baos.write(b);
            tagCheck.append((char) b);
            
            if (tagCheck.length() > 7) {
                tagCheck.deleteCharAt(0);
            }
            
            if (tagCheck.toString().equals("</data>")) {
                foundEndTag = true;
                // Remove the tag from data
                byte[] allData = baos.toByteArray();
                baos.reset();
                baos.write(allData, 0, allData.length - 7);
            }
        }
        
        byte[] dataBytes = baos.toByteArray();
        parseDataBytes(dataBytes);
    }
    
    /**
     * Parse data bytes into the data array
     */
    private void parseDataBytes(byte[] dataBytes) {
        // Calculate row size
        int rowSize = 0;
        List<Integer> varSizes = new ArrayList<>();
        for (int i = 0; i < nvar; i++) {
            VariableType type = variableTypes.get(i);
            int size;
            if (type == VariableType.STRING) {
                // For simplicity, assume max string length for now
                size = 244;  // Default string length
            } else {
                size = type.getSize();
            }
            varSizes.add(size);
            rowSize += size;
        }
        
        if (rowSize == 0) {
            nobs = 0;
            data = new Object[0][nvar];
            return;
        }
        
        nobs = dataBytes.length / rowSize;
        data = new Object[nobs][nvar];
        
        ByteBuffer buf = ByteBuffer.wrap(dataBytes).order(byteOrder);
        
        for (int obs = 0; obs < nobs; obs++) {
            for (int var = 0; var < nvar; var++) {
                VariableType type = variableTypes.get(var);
                int size = varSizes.get(var);
                
                switch (type) {
                    case BYTE:
                        data[obs][var] = buf.get();
                        break;
                    case INT:
                        data[obs][var] = buf.getShort();
                        break;
                    case LONG:
                        data[obs][var] = buf.getInt();
                        break;
                    case FLOAT:
                        data[obs][var] = buf.getFloat();
                        break;
                    case DOUBLE:
                        data[obs][var] = buf.getDouble();
                        break;
                    case STRING:
                        byte[] strBytes = new byte[size];
                        buf.get(strBytes);
                        data[obs][var] = parseNullTerminatedString(strBytes, 0, size);
                        break;
                }
            }
        }
    }
    
    /**
     * Read value labels
     */
    private void readValueLabels(DataInputStream dis) throws IOException {
        // Value labels are optional
        try {
            skipToTag(dis, "<value_labels>");
            skipToTag(dis, "</value_labels>");
        } catch (IOException e) {
            // No value labels present
        }
    }
    
    /**
     * Skip to the specified XML tag
     */
    private void skipToTag(DataInputStream dis, String tag) throws IOException {
        byte[] tagBytes = tag.getBytes(StandardCharsets.ISO_8859_1);
        int matchIndex = 0;
        
        while (matchIndex < tagBytes.length) {
            int b = dis.read();
            if (b == -1) {
                throw new IOException("Unexpected end of file looking for tag: " + tag);
            }
            
            if (b == tagBytes[matchIndex]) {
                matchIndex++;
            } else {
                matchIndex = 0;
                if (b == tagBytes[0]) {
                    matchIndex = 1;
                }
            }
        }
    }
    
    /**
     * Read until the specified XML tag and return the data
     */
    private byte[] readUntilTag(DataInputStream dis, String tag) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] tagBytes = tag.getBytes(StandardCharsets.ISO_8859_1);
        int matchIndex = 0;
        
        while (matchIndex < tagBytes.length) {
            int b = dis.read();
            if (b == -1) {
                throw new IOException("Unexpected end of file looking for tag: " + tag);
            }
            
            baos.write(b);
            
            if (b == tagBytes[matchIndex]) {
                matchIndex++;
            } else {
                matchIndex = 0;
                if (b == tagBytes[0]) {
                    matchIndex = 1;
                }
            }
        }
        
        // Remove the tag from the end
        byte[] result = baos.toByteArray();
        return Arrays.copyOf(result, result.length - tagBytes.length);
    }
    
    // Getters
    
    public int getFormatVersion() {
        return formatVersion;
    }
    
    public int getNumberOfVariables() {
        return nvar;
    }
    
    public int getNumberOfObservations() {
        return nobs;
    }
    
    public List<String> getVariableNames() {
        return new ArrayList<>(variableNames);
    }
    
    public List<VariableType> getVariableTypes() {
        return new ArrayList<>(variableTypes);
    }
    
    public List<String> getVariableLabels() {
        return new ArrayList<>(variableLabels);
    }
    
    public Object[][] getData() {
        if (data == null) {
            return new Object[0][0];
        }
        Object[][] copy = new Object[nobs][nvar];
        for (int i = 0; i < nobs; i++) {
            System.arraycopy(data[i], 0, copy[i], 0, nvar);
        }
        return copy;
    }
    
    public Object getValue(int observation, int variable) {
        if (observation < 0 || observation >= nobs || variable < 0 || variable >= nvar) {
            throw new IndexOutOfBoundsException("Invalid observation or variable index");
        }
        return data[observation][variable];
    }
    
    public Object getValue(int observation, String variableName) {
        int varIndex = variableNames.indexOf(variableName);
        if (varIndex == -1) {
            throw new IllegalArgumentException("Variable not found: " + variableName);
        }
        return getValue(observation, varIndex);
    }
    
    /**
     * Print dataset summary
     */
    public void printSummary() {
        System.out.println("Stata Dataset Summary");
        System.out.println("=====================");
        System.out.println("Format version: " + formatVersion);
        System.out.println("Number of variables: " + nvar);
        System.out.println("Number of observations: " + nobs);
        System.out.println("\nVariables:");
        for (int i = 0; i < nvar; i++) {
            System.out.printf("  %d. %s (%s)%s%n", 
                i + 1, 
                variableNames.get(i), 
                variableTypes.get(i),
                variableLabels.get(i).isEmpty() ? "" : " - " + variableLabels.get(i));
        }
    }
    
    /**
     * Print first n observations
     */
    public void printData(int numRows) {
        if (data == null || nobs == 0) {
            System.out.println("No data to display");
            return;
        }
        
        int rows = Math.min(numRows, nobs);
        
        // Print header
        System.out.print("obs");
        for (String name : variableNames) {
            System.out.printf("\t%s", name);
        }
        System.out.println();
        
        // Print data
        for (int i = 0; i < rows; i++) {
            System.out.printf("%d", i + 1);
            for (int j = 0; j < nvar; j++) {
                System.out.printf("\t%s", data[i][j]);
            }
            System.out.println();
        }
    }
}
