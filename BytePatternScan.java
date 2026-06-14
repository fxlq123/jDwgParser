import java.nio.file.*;
import java.util.*;

public class BytePatternScan {
    static byte[] fileData;

    // BS type=0x30 (BLOCK_HEADER): bytes 0x4c, 0x12
    // BS type=0x07 (INSERT): bytes 0x41, 0xc9
    // But actually: BS encodes opcode(2 bits) + value(8 bits) consuming 10 bits across 2 bytes
    // For value 0x30 (00110000): opcode 01 + value 00110000
    // byte0: 01 001100 = 0x4c, byte1: 00 000000... wait but high 2 bits of byte1 are part of value
    // bits: 0 1 0 0 1 1 0 0 0 0 = opcode(01) + value(001100 00) = 0x30
    // So byte0=01001100=0x4c, byte1: first 2 bits are 00, rest is for next field
    // But byte1[7:6]=00 means: byte1 & 0xc0 == 0x00
    
    // Let's just scan for byte pattern: [0x4c][any byte with high 2 bits = 00]
    // byte1 high 2 bits: 00 → byte1 in range [0x00, 0x3f]

    // For INSERT (0x07 = 00000111):
    // bits: 0 1 0 0 0 0 0 1 1 1 = opcode(01) + value(000001 11)
    // byte0 = 01000001 = 0x41, byte1 starts with: 11...
    // byte1 in range [0xc0, 0xff]

    static boolean isBlockHeaderType(int offset) {
        if (offset + 1 >= fileData.length) return false;
        int b0 = fileData[offset] & 0xFF;
        int b1 = fileData[offset + 1] & 0xFF;
        return b0 == 0x4c && (b1 & 0xC0) == 0x00;
    }

    static boolean isInsertType(int offset) {
        if (offset + 1 >= fileData.length) return false;
        int b0 = fileData[offset] & 0xFF;
        int b1 = fileData[offset + 1] & 0xFF;
        return b0 == 0x41 && (b1 & 0xC0) == 0xC0;
    }

    static String extractTextNear(int offset, int range) {
        // Look for [length byte][ASCII text] pattern near the given offset
        int start = Math.max(0, offset - 10);
        int end = Math.min(fileData.length, offset + range);

        for (int i = start; i < end - 2; i++) {
            int len = fileData[i] & 0xFF;
            if (len >= 3 && len <= 80 && i + 1 + len <= end) {
                boolean valid = true;
                boolean hasLetter = false;
                for (int j = 0; j < len; j++) {
                    int c = fileData[i + 1 + j] & 0xFF;
                    if (c < 32 || c > 126) { valid = false; break; }
                    if (((c >= 'A') && (c <= 'Z')) || ((c >= 'a') && (c <= 'z'))) hasLetter = true;
                }
                if (valid && hasLetter) {
                    StringBuilder sb = new StringBuilder();
                    for (int j = 0; j < len; j++) {
                        sb.append((char) fileData[i + 1 + j]);
                    }
                    String name = sb.toString();
                    if (name.matches("[A-Za-z0-9_*\\-]{3,80}")) {
                        return name + "(+" + (i - offset) + ")";
                    }
                }
            }
        }
        return "";
    }

    static void dumpBytes(int offset, int count, String label) {
        System.out.print(label + " @0x" + Integer.toHexString(offset) + ": ");
        for (int i = 0; i < Math.min(count, fileData.length - offset); i++) {
            System.out.print(String.format("%02x ", fileData[offset + i] & 0xFF));
        }
        System.out.println();
    }

    public static void main(String[] args) throws Exception {
        fileData = Files.readAllBytes(Paths.get("210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));

        int scanStart = 0x5200;
        int scanEnd = Math.min(fileData.length, 0x0f000);

        System.out.println("=== BLOCK_HEADER pattern (0x4c [0x00-0x3f]) ===");
        List<Integer> bhOffsets = new ArrayList<>();
        for (int i = scanStart; i < scanEnd; i++) {
            if (isBlockHeaderType(i)) {
                // Check if this is likely an object start (preceded by MS size bytes)
                dumpBytes(i, 40, "BH-type");
                String text = extractTextNear(i, 200);
                if (!text.isEmpty()) {
                    System.out.println("  Text near: " + text);
                }
                bhOffsets.add(i);
            }
        }

        System.out.println("\nTotal BLOCK_HEADER patterns: " + bhOffsets.size());

        System.out.println("\n=== INSERT pattern (0x41 [0xc0-0xff]) ===");
        List<Integer> insOffsets = new ArrayList<>();
        for (int i = scanStart; i < scanEnd; i++) {
            if (isInsertType(i)) {
                dumpBytes(i, 40, "INS-type");
                String text = extractTextNear(i, 200);
                if (!text.isEmpty()) {
                    System.out.println("  Text near: " + text);
                }
                insOffsets.add(i);
            }
        }
        System.out.println("\nTotal INSERT patterns: " + insOffsets.size());

        // Print full hex of first BLOCK_HEADER
        if (!bhOffsets.isEmpty()) {
            int first = bhOffsets.get(0);
            System.out.println("\n=== First BLOCK_HEADER full data ===");
            for (int i = 0; i < 200 && first + i < fileData.length; i++) {
                if (i % 32 == 0) System.out.print("\n  [" + String.format("%04x", i) + "] ");
                System.out.print(String.format("%02x ", fileData[first + i] & 0xFF));
            }
            System.out.println();
        }

        // Print first INSERT
        if (!insOffsets.isEmpty()) {
            int first = insOffsets.get(0);
            System.out.println("\n=== First INSERT full data ===");
            for (int i = 0; i < 300 && first + i < fileData.length; i++) {
                if (i % 32 == 0) System.out.print("\n  [" + String.format("%04x", i) + "] ");
                System.out.print(String.format("%02x ", fileData[first + i] & 0xFF));
            }
            System.out.println();
        }
    }
}
