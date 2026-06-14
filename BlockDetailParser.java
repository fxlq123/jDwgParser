import java.nio.file.*;
import java.util.*;

public class BlockDetailParser {
    static byte[] data;

    // MS (Modular Short): 16-bit LE, bit15=continuation, bits0-14=value
    static int readMS(int pos) {
        if (pos + 1 >= data.length) return -1;
        int w = (data[pos] & 0xFF) | ((data[pos + 1] & 0xFF) << 8);
        if ((w & 0x8000) != 0) return -1;  // multi-word
        return w & 0x7FFF;
    }

    // BS (Bit Short): opcode(2bits) + typecode(8bits)
    static int readBS(int pos) {
        int b0 = data[pos] & 0xFF;
        int b1 = data[pos + 1] & 0xFF;
        int opcode = (b0 >> 6) & 3;
        int typeCode = ((b0 & 0x3F) << 2) | ((b1 >> 6) & 3);
        return typeCode;
    }

    // BL (Bit Long): 32-bit LE
    static long readBL(int pos) {
        return ((long)(data[pos] & 0xFF))
            | ((long)(data[pos + 1] & 0xFF) << 8)
            | ((long)(data[pos + 2] & 0xFF) << 16)
            | ((long)(data[pos + 3] & 0xFF) << 24);
    }

    // RD (Bit Double): 64-bit IEEE 754
    static double readRD(int pos) {
        long bits = 0;
        for (int i = 0; i < 8; i++) bits |= ((long)(data[pos + i] & 0xFF)) << (i * 8);
        return Double.longBitsToDouble(bits);
    }

    // Bit position helpers
    static int getBit(int pos, int bitIdx) {
        return (data[pos + bitIdx / 8] >> (7 - (bitIdx % 8))) & 1;
    }

    static String hexDump(int offset, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len && offset + i < data.length; i++) {
            sb.append(String.format("%02X ", data[offset + i] & 0xFF));
            if ((i + 1) % 16 == 0) sb.append("\n");
        }
        return sb.toString();
    }

    static String asciiDump(int offset, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len && offset + i < data.length; i++) {
            int c = data[offset + i] & 0xFF;
            sb.append(c >= 32 && c <= 126 ? (char)c : '.');
        }
        return sb.toString();
    }

    static String readString(int pos, int maxLen) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxLen && pos + i < data.length; i++) {
            int c = data[pos + i] & 0xFF;
            if (c == 0) break;
            if (c >= 32 && c <= 126) sb.append((char)c);
            else sb.append('?');
        }
        return sb.toString();
    }

    static String typeName(int t) {
        switch(t) {
            case 0x30: return "BLOCK_HEADER";
            case 0x07: return "INSERT";
            case 0x01: return "LAYER";
            case 0x04: return "CIRCLE";
            case 0x05: return "ARC";
            case 0x19: return "LINE";
            case 0x15: return "DIMENSION";
            case 0x1F: return "MTEXT";
            case 0x0A: return "TEXT";
            default: return String.format("TYPE_%02X", t);
        }
    }

    static int[][] findObjects(int targetType, int maxSize) {
        List<int[]> result = new ArrayList<>();
        for (int pos = 0x5200; pos < data.length - 4; pos++) {
            int size = readMS(pos);
            if (size < 8 || size > maxSize || pos + 2 + size > data.length) continue;
            int typeCode = readBS(pos + 2);
            if (typeCode == targetType) {
                result.add(new int[]{pos, size});
                pos += 1 + size;  // skip past this object
            }
        }
        int[][] arr = new int[result.size()][2];
        for (int i = 0; i < result.size(); i++) arr[i] = result.get(i);
        return arr;
    }

    static void dumpBlockHeader(int offset, int size) {
        System.out.println("┌─────────────────────────────────────────────────────────┐");
        System.out.println("│  BLOCK_HEADER DETAIL @ offset=0x" + String.format("%04X", offset) + "                              │");
        System.out.println("└─────────────────────────────────────────────────────────┘");

        // Object header
        int objSize = readMS(offset);
        int typeCode = readBS(offset + 2);
        System.out.println("\n  [Object Header]");
        System.out.println("    MS(objSize)    = " + objSize + " bytes");
        System.out.println("    BS(typeCode)   = 0x" + String.format("%02X", typeCode) + " (" + typeName(typeCode) + ")");
        System.out.println("    Bit offset     = " + (offset + 2) * 8 + "  (byte " + (offset + 2) + ")");

        // Entity common data (after BS type)
        // Entity common = bitsize(BL) + entity_handle(H) + EED_size(MS) + owner_handle(H) + ...
        int bitPos = (offset + 4) * 8;  // start after MS(2B) + BS(2B) = 4 bytes

        // Read bitsize (BL, 32 bits)
        long bitsize = readBL(bitPos / 8);
        bitPos += 32;
        System.out.println("\n  [Entity Common Data @ byte " + (bitPos / 8) + "]");
        System.out.println("    bitsize        = " + bitsize + " (0x" + Long.toHexString(bitsize) + ")");
        System.out.println("    Bit position   = " + bitPos);

        // Entity handle (variable length, H code)
        int handleStartBit = bitPos;
        long handleVal = 0;
        int handleBytes = 0;
        for (int i = 0; i < 8; i++) {
            int bitVal = getBit(bitPos / 8, bitPos % 8);
            handleVal = (handleVal << 1) | bitVal;
            bitPos++;
            handleBytes++;
            if (bitVal == 1) break;
        }
        System.out.println("    entity_handle  = 0x" + Long.toHexString(handleVal) + " (H code, " + handleBytes + " bits)");
        handleStartBit = bitPos;

        // EED size (MS)
        int eedSize = 0;
        if (bitPos / 8 + 1 < data.length) {
            int eedW = (data[bitPos / 8] & 0xFF) | ((data[bitPos / 8 + 1] & 0xFF) << 8);
            if ((eedW & 0x8000) == 0) {
                eedSize = eedW & 0x7FFF;
            }
        }
        bitPos += 16;  // MS takes 2 bytes
        System.out.println("    EED_size(MS)   = " + eedSize + " bytes");

        // Owner handle (H code)
        long ownerVal = 0;
        int ownerBits = 0;
        int ownerStartBit = bitPos;
        for (int i = 0; i < 8; i++) {
            int bitVal = getBit(bitPos / 8, bitPos % 8);
            ownerVal = (ownerVal << 1) | bitVal;
            bitPos++;
            ownerBits++;
            if (bitVal == 1) break;
        }
        System.out.println("    owner_handle   = 0x" + Long.toHexString(ownerVal) + " (H code, " + ownerBits + " bits)");
        System.out.println("    Byte after EC  = " + (ownerStartBit / 8 + (ownerBits + 7) / 8));

        // Remaining data - scan for text strings
        int dataStart = ownerStartBit / 8 + (ownerBits + 7) / 8;
        if (dataStart % 2 == 1) dataStart++;  // align to word

        System.out.println("\n  [Block-Specific Data @ byte " + dataStart + "]");

        // Hex dump of first 128 bytes of object data
        int dumpLen = Math.min(128, size - (dataStart - offset));
        if (dumpLen > 0) {
            System.out.println("\n  [Hex Dump (first " + dumpLen + " bytes of data)]");
            for (int row = 0; row < dumpLen; row += 16) {
                int rowLen = Math.min(16, dumpLen - row);
                System.out.print("    " + String.format("%04X", dataStart + row) + ": ");
                for (int col = 0; col < rowLen; col++) {
                    System.out.print(String.format("%02X ", data[dataStart + row + col] & 0xFF));
                }
                for (int col = rowLen; col < 16; col++) System.out.print("   ");
                System.out.print(" |");
                for (int col = 0; col < rowLen; col++) {
                    int c = data[dataStart + row + col] & 0xFF;
                    System.out.print(c >= 32 && c <= 126 ? (char)c : '.');
                }
                System.out.println("|");
            }
        }

        // Search for text strings in the object
        System.out.println("\n  [Text Strings Found]");
        int textCount = 0;
        int end = offset + 2 + size;
        for (int i = dataStart; i < end - 2; i++) {
            int len = data[i] & 0xFF;
            if (len < 1 || len > 80 || i + 1 + len > end) continue;
            StringBuilder sb = new StringBuilder();
            boolean valid = true;
            for (int j = 0; j < len; j++) {
                int c = data[i + 1 + j] & 0xFF;
                if (c < 32 || c > 126) { valid = false; break; }
                sb.append((char)c);
            }
            if (valid && sb.length() >= 2 && sb.toString().matches("[A-Za-z_*][A-Za-z0-9_*\\-]*")) {
                String str = sb.toString();
                System.out.println("    @ byte 0x" + String.format("%04X", i + 1) + " (len=" + len + "): \"" + str + "\"");
                textCount++;
            }
        }
        if (textCount == 0) System.out.println("    (none found)");
    }

    public static void main(String[] args) throws Exception {
        data = Files.readAllBytes(Paths.get("210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));

        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════════════╗");
        System.out.println("║          DWG R2000 BLOCK HEADER 详细解析测试                        ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════╝");
        System.out.println("File: 210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg");
        System.out.println("Size: " + data.length + " bytes");
        System.out.println();

        int[][] blocks = findObjects(0x30, 30000);
        System.out.println("Found " + blocks.length + " BLOCK_HEADER object(s)\n");

        for (int i = 0; i < blocks.length; i++) {
            System.out.println();
            System.out.println("════════════════════════════════════════════════════════════");
            System.out.println("  BLOCK HEADER #" + (i + 1) + " / " + blocks.length);
            System.out.println("════════════════════════════════════════════════════════════");
            dumpBlockHeader(blocks[i][0], blocks[i][1]);
        }

        // Also dump INSERT objects
        System.out.println();
        System.out.println();
        System.out.println("════════════════════════════════════════════════════════════");
        System.out.println("  INSERT OBJECTS");
        System.out.println("════════════════════════════════════════════════════════════");
        int[][] inserts = findObjects(0x07, 30000);
        System.out.println("Found " + inserts.length + " INSERT object(s)\n");

        for (int i = 0; i < Math.min(inserts.length, 5); i++) {
            int[] ins = inserts[i];
            System.out.println("┌─────────────────────────────────────────────────────────┐");
            System.out.println("│  INSERT DETAIL #" + (i + 1) + " @ offset=0x" + String.format("%04X", ins[0]) + "                       │");
            System.out.println("└─────────────────────────────────────────────────────────┘");

            int objSize = readMS(ins[0]);
            int typeCode = readBS(ins[0] + 2);
            System.out.println("\n  [Object Header]");
            System.out.println("    MS(objSize)    = " + objSize + " bytes");
            System.out.println("    BS(typeCode)   = 0x" + String.format("%02X", typeCode) + " (" + typeName(typeCode) + ")");

            // Entity common data
            long bitsize = readBL(ins[0] + 4);
            System.out.println("    bitsize        = " + bitsize + " (0x" + Long.toHexString(bitsize) + ")");

            // Hex dump
            System.out.println("\n  [Hex Dump (first 48 bytes)]");
            for (int row = 0; row < Math.min(48, ins[1]); row += 16) {
                int rowLen = Math.min(16, ins[1] - row);
                System.out.print("    " + String.format("%04X", ins[0] + row) + ": ");
                for (int col = 0; col < rowLen; col++) {
                    System.out.print(String.format("%02X ", data[ins[0] + row + col] & 0xFF));
                }
                for (int col = rowLen; col < 16; col++) System.out.print("   ");
                System.out.print(" |");
                for (int col = 0; col < rowLen; col++) {
                    int c = data[ins[0] + row + col] & 0xFF;
                    System.out.print(c >= 32 && c <= 126 ? (char)c : '.');
                }
                System.out.println("|");
            }

            // Text strings
            System.out.println("\n  [Text Strings]");
            int textCount = 0;
            int end = ins[0] + 2 + ins[1];
            for (int j = ins[0] + 4; j < end - 2; j++) {
                int len = data[j] & 0xFF;
                if (len < 1 || len > 80 || j + 1 + len > end) continue;
                StringBuilder sb = new StringBuilder();
                boolean valid = true;
                for (int k = 0; k < len; k++) {
                    int c = data[j + 1 + k] & 0xFF;
                    if (c < 32 || c > 126) { valid = false; break; }
                    sb.append((char)c);
                }
                if (valid && sb.length() >= 2 && sb.toString().matches("[A-Za-z_*][A-Za-z0-9_*\\-]*")) {
                    System.out.println("    @ byte 0x" + String.format("%04X", j + 1) + " (len=" + len + "): \"" + sb + "\"");
                    textCount++;
                }
            }
            if (textCount == 0) System.out.println("    (none found)");
            System.out.println();
        }
    }
}
