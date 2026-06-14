import java.nio.file.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

public class FinalParser {
    static byte[] data;

    static void dumpRange(int start, int length) {
        for (int i = 0; i < length; i += 16) {
            int pos = start + i;
            if (pos >= data.length) break;
            System.out.print(String.format("0x%04x: ", pos));
            for (int j = 0; j < 16 && pos + j < data.length; j++) {
                System.out.print(String.format("%02x ", data[pos + j] & 0xFF));
            }
            System.out.print("  ");
            for (int j = 0; j < 16 && pos + j < data.length; j++) {
                int c = data[pos + j] & 0xFF;
                if (c >= 32 && c <= 126) System.out.print((char) c);
                else System.out.print(".");
            }
            System.out.println();
        }
    }

    public static void main(String[] args) throws Exception {
        data = Files.readAllBytes(Paths.get("210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));

        // Key insight: objects are NOT contiguous! They're at specific offsets.
        // Object format: [2-byte LE: size] [2 bytes: BS type code] [data: size-2 bytes]
        //
        // Known valid objects:
        // 0x52b9: size=114, type=0x30 (BLOCK_HEADER, *Model_Space - no name in data)
        // 0x6971: size=512, type=0x07 (INSERT, contains SSR, ACAD, SLDDIMSTYLE0-1)

        // Strategy:
        // 1. Scan entire file for valid MS + BS type patterns
        // 2. For each potential object: try to read MS (16-bit LE single-word), then BS type
        // 3. Check BS type: opcode==1 (first 2 bits), value in {0x07, 0x30}
        // 4. Look for text strings inside the object data

        Map<String, int[]> blocks = new LinkedHashMap<>();  // block name -> [offset, size]
        Map<String, Integer> blockRefs = new LinkedHashMap<>();  // block name -> reference count
        int totalBLOCK_HEADER = 0;
        int totalINSERT = 0;

        System.out.println("=== Scanning for BLOCK_HEADER (type=0x30) and INSERT (type=0x07) ===");

        // Scan for valid objects at every offset
        Set<Integer> usedObjStarts = new HashSet<>();
        List<int[]> blockHeaderObjs = new ArrayList<>();  // [start, size]
        List<int[]> insertObjs = new ArrayList<>();

        for (int pos = 0x5200; pos < data.length - 4; pos++) {
            int lo = data[pos] & 0xFF;
            int hi = data[pos + 1] & 0xFF;
            int size = lo | (hi << 8);
            boolean cont = (size & 0x8000) != 0;
            size = size & 0x7FFF;

            if (cont) continue;
            if (size < 10 || size > 3000) continue;  // reasonable range
            if (pos + 2 + size > data.length) continue;

            int ds = pos + 2;
            int b0 = data[ds] & 0xFF;
            int b1 = data[ds + 1] & 0xFF;
            int opcode = (b0 >> 6) & 3;
            if (opcode != 1) continue;

            int typeCode = ((b0 & 0x3F) << 2) | ((b1 >> 6) & 3);

            if (typeCode == 0x30) {
                // Found a BLOCK_HEADER!
                totalBLOCK_HEADER++;
                blockHeaderObjs.add(new int[]{pos, size});
                // Search for block name in data
                String name = findNameInRange(ds + 2, ds + size, 64);
                if (name != null) {
                    blocks.put(name, new int[]{pos, size});
                }
                // Don't scan inside this object again
                for (int skip = pos; skip < pos + 2 + size; skip++) usedObjStarts.add(skip);
            } else if (typeCode == 0x07) {
                // Found an INSERT!
                totalINSERT++;
                insertObjs.add(new int[]{pos, size});
                // Search for block name in data
                String name = findNameInRange(ds + 2, ds + size, 64);
                if (name != null) {
                    blockRefs.merge(name, 1, Integer::sum);
                }
                for (int skip = pos; skip < pos + 2 + size; skip++) usedObjStarts.add(skip);
            }
        }

        System.out.println("Found BLOCK_HEADER objects: " + totalBLOCK_HEADER);
        System.out.println("Found INSERT objects: " + totalINSERT);

        // Show all BLOCK_HEADER objects with their names
        System.out.println("\n=== BLOCK_HEADER objects ===");
        for (int[] bh : blockHeaderObjs) {
            int ds = bh[0] + 2;
            String name = findNameInRange(ds + 2, ds + bh[1], 64);
            System.out.println(String.format("  @ 0x%04x: size=%d bytes, name='%s'",
                    bh[0], bh[1], name));
            // Show bytes
            if (bh[1] <= 80) {
                dumpRange(bh[0], bh[1] + 4);
                System.out.println();
            }
        }

        // Show INSERT objects
        System.out.println("\n=== INSERT objects ===");
        for (int[] ins : insertObjs) {
            int ds = ins[0] + 2;
            String name = findNameInRange(ds + 2, ds + ins[1], 64);
            System.out.println(String.format("  @ 0x%04x: size=%d bytes, block='%s'",
                    ins[0], ins[1], name));
        }

        // Final report
        System.out.println("\n\n=========== FINAL ANALYSIS REPORT ===========");
        System.out.println("File: 210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg");
        System.out.println("Size: " + data.length + " bytes");
        System.out.println("Version: AutoCAD R2000 (AC1015)");
        System.out.println();
        System.out.println("--- Block Definitions ---");
        System.out.println("Total BLOCK_HEADER objects: " + totalBLOCK_HEADER);
        List<String> sortedBlocks = new ArrayList<>(blocks.keySet());
        Collections.sort(sortedBlocks);
        for (String name : sortedBlocks) {
            int[] info = blocks.get(name);
            System.out.println(String.format("  '%s'  (@0x%04x, %d bytes)", name, info[0], info[1]));
        }
        System.out.println();
        System.out.println("--- Block Insertions ---");
        System.out.println("Total INSERT objects: " + totalINSERT);
        List<Map.Entry<String, Integer>> sortedRefs = new ArrayList<>(blockRefs.entrySet());
        sortedRefs.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        for (Map.Entry<String, Integer> e : sortedRefs) {
            System.out.println(String.format("  '%s' : %d time(s)", e.getKey(), e.getValue()));
        }
        System.out.println();
        System.out.println("=============================================");
    }

    // Search for a text string preceded by its length byte within a range
    static String findNameInRange(int start, int end, int maxLen) {
        for (int i = start; i < end - 2; i++) {
            int len = data[i] & 0xFF;
            if (len < 1 || len > maxLen || i + 1 + len > end) continue;
            // Check if all chars are printable ASCII
            boolean valid = true;
            for (int j = 0; j < len; j++) {
                int c = data[i + 1 + j] & 0xFF;
                if (c < 32 || c > 126) { valid = false; break; }
            }
            if (!valid) continue;
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < len; j++) {
                sb.append((char) data[i + 1 + j]);
            }
            String s = sb.toString();
            if (s.matches("[A-Za-z_*][A-Za-z0-9_*\\-]*") && s.length() >= 2) {
                return s;
            }
        }
        return null;
    }
}
