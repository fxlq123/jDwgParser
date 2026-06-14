import java.nio.file.*;
import java.util.*;

public class ScanByType {
    static byte[] data;

    static String typeName(int t) {
        String[] names = {
            /* 00 */ "UNUSED", /* 01 */ "LAYER", /* 02 */ "Linetype", /* 03 */ "TextStyle",
            /* 04 */ "CIRCLE", /* 05 */ "ARC", /* 06 */ "SOLID", /* 07 */ "INSERT",
            /* 08 */ "MINSERT", /* 09 */ "SEQEND", /* 0a */ "TEXT", /* 0b */ "?11",
            /* 0c */ "VIEW", /* 0d */ "?13", /* 0e */ "ELLIPSE", /* 0f */ "?15",
            /* 10 */ "?16", /* 11 */ "?17", /* 12 */ "Viewport", /* 13 */ "?19",
            /* 14 */ "?20", /* 15 */ "DIMENSION", /* 16 */ "DIM_STYLE", /* 17 */ "?23",
            /* 18 */ "APP_ID", /* 19 */ "LINE", /* 1a */ "?26", /* 1b */ "DICTIONARY",
            /* 1c */ "?28", /* 1d */ "?29", /* 1e */ "?30", /* 1f */ "MTEXT",
            /* 20 */ "LEADER", /* 21 */ "TOLERANCE", /* 22 */ "LWPOLYLINE", /* 23 */ "?35",
            /* 24 */ "?36", /* 25 */ "?37", /* 26 */ "?38", /* 27 */ "?39",
            /* 28 */ "?40", /* 29 */ "?41", /* 2a */ "IMAGE", /* 2b */ "?43",
            /* 2c */ "?44", /* 2d */ "?45", /* 2e */ "?46", /* 2f */ "?47",
            /* 30 */ "BLOCK_HEADER", /* 31 */ "ENDBLK", /* 32 */ "?50", /* 33 */ "?51",
        };
        if (t >= 0 && t < names.length) return names[t];
        return String.format("T%02x", t);
    }

    // Try reading MS: standard modular char (7 bits/byte, MSB=continuation)
    static int readByteMS(int pos, int[] bytesUsed) {
        int result = 0;
        int i = 0;
        boolean hasMore = true;
        while (hasMore && pos + i < data.length) {
            int b = data[pos + i] & 0xFF;
            result = (result << 7) | (b & 0x7F);
            hasMore = (b & 0x80) != 0;
            i++;
        }
        bytesUsed[0] = i;
        return result;
    }

    // Try reading MS: 16-bit LE words, bit 15 = continuation
    static int readWord16MS(int pos, int[] bytesUsed) {
        int result = 0;
        int i = 0;
        int shift = 0;
        boolean hasMore = true;
        while (hasMore && pos + i + 1 < data.length) {
            int lo = data[pos + i] & 0xFF;
            int hi = data[pos + i + 1] & 0xFF;
            int word = lo | (hi << 8);
            result |= (word & 0x7FFF) << shift;
            hasMore = (word & 0x8000) != 0;
            i += 2;
            shift += 15;
        }
        bytesUsed[0] = i;
        return result;
    }

    // Extract block name text from a byte range
    static List<String> findText(int start, int end) {
        List<String> texts = new ArrayList<>();
        for (int i = start; i < end - 1; i++) {
            int len = data[i] & 0xFF;
            if (len < 1 || len > 80 || i + 1 + len > end) continue;
            StringBuilder sb = new StringBuilder();
            boolean valid = true;
            for (int j = 0; j < len; j++) {
                int c = data[i + 1 + j] & 0xFF;
                if (c < 32 || c > 126) { valid = false; break; }
                sb.append((char) c);
            }
            if (valid && sb.length() >= 2) {
                String t = sb.toString();
                if (t.matches("[A-Za-z_*][A-Za-z0-9_*\\-]*")) texts.add(t);
            }
        }
        return texts;
    }

    public static void main(String[] args) throws Exception {
        data = Files.readAllBytes(Paths.get("210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));

        // Scan the file looking for BS type code patterns:
        // A valid object: [MS size][BS type][data...]
        // Type code 0x30 (BLOCK_HEADER): data byte (b0 & 0x3F) << 2 | (b1 >> 6) == 0x30
        //   => (b0 & 0x3F) << 2 == 0x30 (b1 is 0b00xxxxxx) OR
        //      (b0 & 0x3F) << 2 == 0x2C and b1 >> 6 == 1 etc.
        // Actually: (b0 & 0x3F) << 2 | (b1 >> 6) == target
        // For target 0x30 (= 48): need value = 12 (0x0C), opcode = 0b01
        // So b0 & 0xC0 == 0x40, b0 & 0x3F == 0x0C, and b1 >> 6 == 0x00
        // i.e., b0 = 0x4C, b1 & 0xC0 == 0x00

        // For INSERT (0x07 = 7): need value = 1 (0x01), opcode = 0b11?
        // BS value of 7: 7 = (b0 & 0x3F) << 2 | (b1 >> 6)
        // Cases: b0 & 0x3F = 1, b1 >> 6 = 3 → b0 = 0x41, b1 & 0xC0 = 0xC0
        // OR: b0 & 0x3F = 0, b1 >> 6 = 7 → b1 >> 6 = 7 impossible (only 2 bits)
        // Actually: (b0 & 0x3F) can be 0..11 for max, but b1 >> 6 = 0..3
        // 7 = 1*4 + 3, so: b0 = 0x41, b1 & 0xC0 = 0xC0

        System.out.println("=== SCANNING for BLOCK_HEADER (type 0x30) and INSERT (type 0x07) ===");

        // Try: byte-based MS for size, then BS for type
        System.out.println("\n--- Using byte-based MS for size ---");
        Map<String, Integer> blockNames = new LinkedHashMap<>();
        Map<String, Integer> insertNames = new LinkedHashMap<>();

        for (int pos = 0x5000; pos < data.length - 20; pos++) {
            int[] bytesUsed = new int[1];
            int sizeB = readByteMS(pos, bytesUsed);
            if (sizeB > 2 && sizeB < 2000 && pos + bytesUsed[0] + sizeB < data.length) {
                int ds = pos + bytesUsed[0];
                int b0 = data[ds] & 0xFF;
                int b1 = data[ds + 1] & 0xFF;
                int type = ((b0 & 0x3F) << 2) | ((b1 >> 6) & 3);

                if (type == 0x30 || type == 0x07) {
                    List<String> texts = findText(ds + 2, ds + sizeB);
                    String txt = texts.isEmpty() ? "(no text)" : String.join(",", texts);
                    System.out.println(String.format("  pos=0x%04x, size=%d, type=0x%02x(%s), texts=%s",
                            pos, sizeB, type, typeName(type), txt));
                    if (type == 0x30) {
                        for (String t : texts) blockNames.merge(t, 1, Integer::sum);
                    } else {
                        for (String t : texts) insertNames.merge(t, 1, Integer::sum);
                    }
                }
            }
        }

        System.out.println("\n--- Using 16-bit LE MS for size ---");
        Map<String, Integer> blockNames2 = new LinkedHashMap<>();
        Map<String, Integer> insertNames2 = new LinkedHashMap<>();

        for (int pos = 0x5000; pos < data.length - 20; pos++) {
            int[] bytesUsed = new int[1];
            int sizeW = readWord16MS(pos, bytesUsed);
            if (sizeW > 2 && sizeW < 2000 && pos + bytesUsed[0] + sizeW < data.length) {
                int ds = pos + bytesUsed[0];
                int b0 = data[ds] & 0xFF;
                int b1 = data[ds + 1] & 0xFF;
                int type = ((b0 & 0x3F) << 2) | ((b1 >> 6) & 3);

                if (type == 0x30 || type == 0x07) {
                    List<String> texts = findText(ds + 2, ds + sizeW);
                    String txt = texts.isEmpty() ? "(no text)" : String.join(",", texts);
                    System.out.println(String.format("  pos=0x%04x, size=%d, type=0x%02x(%s), texts=%s",
                            pos, sizeW, type, typeName(type), txt));
                    if (type == 0x30) {
                        for (String t : texts) blockNames2.merge(t, 1, Integer::sum);
                    } else {
                        for (String t : texts) insertNames2.merge(t, 1, Integer::sum);
                    }
                }
            }
        }

        System.out.println("\n=== SUMMARY ===");
        System.out.println("Block defs (byte-MS): " + blockNames);
        System.out.println("Block defs (word-MS): " + blockNames2);
        System.out.println("Block refs (byte-MS): " + insertNames);
        System.out.println("Block refs (word-MS): " + insertNames2);
    }
}
