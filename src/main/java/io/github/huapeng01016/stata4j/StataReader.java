package io.github.huapeng01016.stata4j;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * A reader for Stata dataset (.dta) files.
 * Supports Stata file formats 115, 117, and 118 (Stata 12-17).
 */
public class StataReader implements AutoCloseable {
    
    private final DataInputStream dis;
    private String format;
    private ByteOrder byteOrder;
    private int numVars;
    private int numObs;
    private String datasetLabel;
    private String timestamp;
    private List<String> varNames;
    private List<String> varLabels;
    private List<String> fmtList;
    private List<String> lblList;
    private List<StataVarType> varTypes;
    private Map<String, String> characteristics;
    private List<Map<String, Object>> data;
    
    /**
     * Creates a new StataReader for the specified file.
     * 
     * @param file the Stata .dta file to read
     * @throws IOException if an I/O error occurs
     */
    public StataReader(File file) throws IOException {
        this(new FileInputStream(file));
    }
    
    /**
     * Creates a new StataReader for the specified file path.
     * 
     * @param filePath the path to the Stata .dta file
     * @throws IOException if an I/O error occurs
     */
    public StataReader(String filePath) throws IOException {
        this(new File(filePath));
    }
    
    /**
     * Creates a new StataReader from an input stream.
     * 
     * @param inputStream the input stream to read from
     */
    public StataReader(InputStream inputStream) {
        this.dis = new DataInputStream(new BufferedInputStream(inputStream));
        this.characteristics = new LinkedHashMap<>();
        this.data = new ArrayList<>();
    }
    
    /**
     * Reads and parses the Stata dataset.
     * 
     * @throws IOException if an I/O error occurs
     * @throws StataFormatException if the file format is invalid
     */
    public void read() throws IOException, StataFormatException {
        readHeader();
        readDescriptors();
        readVariableLabels();
        readExpansionFields();
        readData();
        readValueLabels();
    }
    
    private void readHeader() throws IOException, StataFormatException {
        // Read format
        byte[] formatBytes = new byte[3];
        dis.readFully(formatBytes);
        format = new String(formatBytes, StandardCharsets.US_ASCII);
        
        if (!format.equals("115") && !format.equals("117") && !format.equals("118")) {
            throw new StataFormatException("Unsupported Stata format: " + format);
        }
        
        // Read byte order
        byte byteOrderByte = dis.readByte();
        byteOrder = (byteOrderByte == 0x01) ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
        
        // Skip filetype (1 byte)
        dis.skipBytes(1);
        
        // Skip unused (1 byte)
        dis.skipBytes(1);
        
        // Read number of variables and observations
        numVars = readShort();
        numObs = readInt();
        
        // Read dataset label
        datasetLabel = readString(81);
        
        // Read timestamp
        timestamp = readString(18);
    }
    
    private void readDescriptors() throws IOException, StataFormatException {
        // Read variable types
        varTypes = new ArrayList<>(numVars);
        for (int i = 0; i < numVars; i++) {
            int typeCode = dis.readUnsignedByte();
            varTypes.add(StataVarType.fromCode(typeCode));
        }
        
        // Read variable names
        varNames = new ArrayList<>(numVars);
        for (int i = 0; i < numVars; i++) {
            varNames.add(readString(33));
        }
        
        // Read sort order (not stored)
        dis.skipBytes((numVars + 1) * 2);
        
        // Read formats
        fmtList = new ArrayList<>(numVars);
        for (int i = 0; i < numVars; i++) {
            fmtList.add(readString(49));
        }
        
        // Read value label names
        lblList = new ArrayList<>(numVars);
        for (int i = 0; i < numVars; i++) {
            lblList.add(readString(33));
        }
    }
    
    private void readVariableLabels() throws IOException {
        varLabels = new ArrayList<>(numVars);
        for (int i = 0; i < numVars; i++) {
            varLabels.add(readString(81));
        }
    }
    
    private void readExpansionFields() throws IOException {
        // Read characteristics
        while (true) {
            int dataType = dis.readUnsignedByte();
            if (dataType == 0) break; // End of expansion fields
            
            int length = readInt();
            if (dataType == 1) {
                // Characteristic
                byte[] charData = new byte[length];
                dis.readFully(charData);
            } else {
                // Skip unknown expansion field
                dis.skipBytes(length);
            }
        }
    }
    
    private void readData() throws IOException, StataFormatException {
        for (int obs = 0; obs < numObs; obs++) {
            Map<String, Object> observation = new LinkedHashMap<>();
            for (int var = 0; var < numVars; var++) {
                Object value = readValue(varTypes.get(var));
                observation.put(varNames.get(var), value);
            }
            data.add(observation);
        }
    }
    
    private Object readValue(StataVarType varType) throws IOException, StataFormatException {
        switch (varType) {
            case BYTE:
                byte b = dis.readByte();
                return (b == 127) ? null : b;
            
            case INT:
                short s = readShort();
                return (s == 32767) ? null : s;
            
            case LONG:
                int i = readInt();
                return (i == 2147483647) ? null : i;
            
            case FLOAT:
                float f = readFloat();
                return (Float.isNaN(f)) ? null : f;
            
            case DOUBLE:
                double d = readDouble();
                return (Double.isNaN(d)) ? null : d;
            
            case STR1: return readString(1);
            case STR2: return readString(2);
            case STR3: return readString(3);
            case STR4: return readString(4);
            case STR5: return readString(5);
            case STR6: return readString(6);
            case STR7: return readString(7);
            case STR8: return readString(8);
            case STR9: return readString(9);
            case STR10: return readString(10);
            case STR11: return readString(11);
            case STR12: return readString(12);
            case STR13: return readString(13);
            case STR14: return readString(14);
            case STR15: return readString(15);
            case STR16: return readString(16);
            case STR17: return readString(17);
            case STR18: return readString(18);
            case STR19: return readString(19);
            case STR20: return readString(20);
            case STR21: return readString(21);
            case STR22: return readString(22);
            case STR23: return readString(23);
            case STR24: return readString(24);
            case STR25: return readString(25);
            case STR26: return readString(26);
            case STR27: return readString(27);
            case STR28: return readString(28);
            case STR29: return readString(29);
            case STR30: return readString(30);
            case STR31: return readString(31);
            case STR32: return readString(32);
            case STR33: return readString(33);
            case STR34: return readString(34);
            case STR35: return readString(35);
            case STR36: return readString(36);
            case STR37: return readString(37);
            case STR38: return readString(38);
            case STR39: return readString(39);
            case STR40: return readString(40);
            case STR41: return readString(41);
            case STR42: return readString(42);
            case STR43: return readString(43);
            case STR44: return readString(44);
            case STR45: return readString(45);
            case STR46: return readString(46);
            case STR47: return readString(47);
            case STR48: return readString(48);
            case STR49: return readString(49);
            case STR50: return readString(50);
            case STR51: return readString(51);
            case STR52: return readString(52);
            case STR53: return readString(53);
            case STR54: return readString(54);
            case STR55: return readString(55);
            case STR56: return readString(56);
            case STR57: return readString(57);
            case STR58: return readString(58);
            case STR59: return readString(59);
            case STR60: return readString(60);
            case STR61: return readString(61);
            case STR62: return readString(62);
            case STR63: return readString(63);
            case STR64: return readString(64);
            case STR65: return readString(65);
            case STR66: return readString(66);
            case STR67: return readString(67);
            case STR68: return readString(68);
            case STR69: return readString(69);
            case STR70: return readString(70);
            case STR71: return readString(71);
            case STR72: return readString(72);
            case STR73: return readString(73);
            case STR74: return readString(74);
            case STR75: return readString(75);
            case STR76: return readString(76);
            case STR77: return readString(77);
            case STR78: return readString(78);
            case STR79: return readString(79);
            case STR80: return readString(80);
            case STR81: return readString(81);
            case STR82: return readString(82);
            case STR83: return readString(83);
            case STR84: return readString(84);
            case STR85: return readString(85);
            case STR86: return readString(86);
            case STR87: return readString(87);
            case STR88: return readString(88);
            case STR89: return readString(89);
            case STR90: return readString(90);
            case STR91: return readString(91);
            case STR92: return readString(92);
            case STR93: return readString(93);
            case STR94: return readString(94);
            case STR95: return readString(95);
            case STR96: return readString(96);
            case STR97: return readString(97);
            case STR98: return readString(98);
            case STR99: return readString(99);
            case STR100: return readString(100);
            case STR101: return readString(101);
            case STR102: return readString(102);
            case STR103: return readString(103);
            case STR104: return readString(104);
            case STR105: return readString(105);
            case STR106: return readString(106);
            case STR107: return readString(107);
            case STR108: return readString(108);
            case STR109: return readString(109);
            case STR110: return readString(110);
            case STR111: return readString(111);
            case STR112: return readString(112);
            case STR113: return readString(113);
            case STR114: return readString(114);
            case STR115: return readString(115);
            case STR116: return readString(116);
            case STR117: return readString(117);
            case STR118: return readString(118);
            case STR119: return readString(119);
            case STR120: return readString(120);
            case STR121: return readString(121);
            case STR122: return readString(122);
            case STR123: return readString(123);
            case STR124: return readString(124);
            case STR125: return readString(125);
            case STR126: return readString(126);
            case STR127: return readString(127);
            case STR128: return readString(128);
            case STR129: return readString(129);
            case STR130: return readString(130);
            case STR131: return readString(131);
            case STR132: return readString(132);
            case STR133: return readString(133);
            case STR134: return readString(134);
            case STR135: return readString(135);
            case STR136: return readString(136);
            case STR137: return readString(137);
            case STR138: return readString(138);
            case STR139: return readString(139);
            case STR140: return readString(140);
            case STR141: return readString(141);
            case STR142: return readString(142);
            case STR143: return readString(143);
            case STR144: return readString(144);
            case STR145: return readString(145);
            case STR146: return readString(146);
            case STR147: return readString(147);
            case STR148: return readString(148);
            case STR149: return readString(149);
            case STR150: return readString(150);
            case STR151: return readString(151);
            case STR152: return readString(152);
            case STR153: return readString(153);
            case STR154: return readString(154);
            case STR155: return readString(155);
            case STR156: return readString(156);
            case STR157: return readString(157);
            case STR158: return readString(158);
            case STR159: return readString(159);
            case STR160: return readString(160);
            case STR161: return readString(161);
            case STR162: return readString(162);
            case STR163: return readString(163);
            case STR164: return readString(164);
            case STR165: return readString(165);
            case STR166: return readString(166);
            case STR167: return readString(167);
            case STR168: return readString(168);
            case STR169: return readString(169);
            case STR170: return readString(170);
            case STR171: return readString(171);
            case STR172: return readString(172);
            case STR173: return readString(173);
            case STR174: return readString(174);
            case STR175: return readString(175);
            case STR176: return readString(176);
            case STR177: return readString(177);
            case STR178: return readString(178);
            case STR179: return readString(179);
            case STR180: return readString(180);
            case STR181: return readString(181);
            case STR182: return readString(182);
            case STR183: return readString(183);
            case STR184: return readString(184);
            case STR185: return readString(185);
            case STR186: return readString(186);
            case STR187: return readString(187);
            case STR188: return readString(188);
            case STR189: return readString(189);
            case STR190: return readString(190);
            case STR191: return readString(191);
            case STR192: return readString(192);
            case STR193: return readString(193);
            case STR194: return readString(194);
            case STR195: return readString(195);
            case STR196: return readString(196);
            case STR197: return readString(197);
            case STR198: return readString(198);
            case STR199: return readString(199);
            case STR200: return readString(200);
            case STR201: return readString(201);
            case STR202: return readString(202);
            case STR203: return readString(203);
            case STR204: return readString(204);
            case STR205: return readString(205);
            case STR206: return readString(206);
            case STR207: return readString(207);
            case STR208: return readString(208);
            case STR209: return readString(209);
            case STR210: return readString(210);
            case STR211: return readString(211);
            case STR212: return readString(212);
            case STR213: return readString(213);
            case STR214: return readString(214);
            case STR215: return readString(215);
            case STR216: return readString(216);
            case STR217: return readString(217);
            case STR218: return readString(218);
            case STR219: return readString(219);
            case STR220: return readString(220);
            case STR221: return readString(221);
            case STR222: return readString(222);
            case STR223: return readString(223);
            case STR224: return readString(224);
            case STR225: return readString(225);
            case STR226: return readString(226);
            case STR227: return readString(227);
            case STR228: return readString(228);
            case STR229: return readString(229);
            case STR230: return readString(230);
            case STR231: return readString(231);
            case STR232: return readString(232);
            case STR233: return readString(233);
            case STR234: return readString(234);
            case STR235: return readString(235);
            case STR236: return readString(236);
            case STR237: return readString(237);
            case STR238: return readString(238);
            case STR239: return readString(239);
            case STR240: return readString(240);
            case STR241: return readString(241);
            case STR242: return readString(242);
            case STR243: return readString(243);
            case STR244: return readString(244);
            
            default:
                throw new StataFormatException("Unknown variable type: " + varType);
        }
    }
    
    private void readValueLabels() throws IOException {
        // Value labels are optional and come after the data
        // For now, skip them
    }
    
    private short readShort() throws IOException {
        byte[] bytes = new byte[2];
        dis.readFully(bytes);
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(byteOrder);
        return buffer.getShort();
    }
    
    private int readInt() throws IOException {
        byte[] bytes = new byte[4];
        dis.readFully(bytes);
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(byteOrder);
        return buffer.getInt();
    }
    
    private float readFloat() throws IOException {
        byte[] bytes = new byte[4];
        dis.readFully(bytes);
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(byteOrder);
        return buffer.getFloat();
    }
    
    private double readDouble() throws IOException {
        byte[] bytes = new byte[8];
        dis.readFully(bytes);
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(byteOrder);
        return buffer.getDouble();
    }
    
    private String readString(int length) throws IOException {
        byte[] bytes = new byte[length];
        dis.readFully(bytes);
        // Find null terminator
        int nullIndex = 0;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == 0) {
                nullIndex = i;
                break;
            }
        }
        return new String(bytes, 0, nullIndex, StandardCharsets.UTF_8).trim();
    }
    
    // Getters
    
    public String getFormat() {
        return format;
    }
    
    public int getNumVars() {
        return numVars;
    }
    
    public int getNumObs() {
        return numObs;
    }
    
    public String getDatasetLabel() {
        return datasetLabel;
    }
    
    public String getTimestamp() {
        return timestamp;
    }
    
    public List<String> getVarNames() {
        return Collections.unmodifiableList(varNames);
    }
    
    public List<String> getVarLabels() {
        return Collections.unmodifiableList(varLabels);
    }
    
    public List<String> getFmtList() {
        return Collections.unmodifiableList(fmtList);
    }
    
    public List<StataVarType> getVarTypes() {
        return Collections.unmodifiableList(varTypes);
    }
    
    public List<Map<String, Object>> getData() {
        return Collections.unmodifiableList(data);
    }
    
    public Map<String, Object> getObservation(int index) {
        if (index < 0 || index >= numObs) {
            throw new IndexOutOfBoundsException("Observation index out of bounds: " + index);
        }
        return data.get(index);
    }
    
    @Override
    public void close() throws IOException {
        dis.close();
    }
}
