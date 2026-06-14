import java.nio.file.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

public class FinalBlockParser {
    static byte[] data;
    static ByteBuffer bb;

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

    // Find all block-like text strings in the file
    static List<int[]> findBlockNames() {
        List<int[]> result = new ArrayList<>();
        // Pattern: [length_byte][ASCII text starting with uppercase/_/*]
        for (int i = 0; i < data.length - 2; i++) {
            int len = data[i] & 0xFF;
            if (len < 3 || len > 64 || i + 1 + len > data.length) continue;
            int first = data[i + 1] & 0xFF;
            if (!((first >= 'A' && first <= 'Z') || first == '_' || first == '*')) continue;

            StringBuilder sb = new StringBuilder();
            boolean valid = true;
            for (int j = 0; j < len; j++) {
                int c = data[i + 1 + j] & 0xFF;
                if (c < 32 || c > 126) { valid = false; break; }
                sb.append((char) c);
            }
            if (valid && sb.toString().matches("[A-Za-z_*][A-Za-z0-9_*\\-]*")) {
                String name = sb.toString();
                // Filter: must look like a block name (exclude common words)
                if (name.startsWith("SLD") || name.startsWith("ACAD") ||
                    name.startsWith("*") || name.startsWith("Layout") ||
                    name.startsWith("_") || name.contains("CENTER") ||
                    name.equals("A0") || name.equals("A1") || name.equals("A2") ||
                    name.equals("A3") || name.equals("A4") || name.equals("A5") ||
                    name.equals("A6") || name.equals("B0") || name.equals("B1") ||
                    name.equals("SSR") || name.equals("TXT") ||
                    name.equals("I-Me5") || name.equals("I-A1") ||
                    name.startsWith("I-") ||
                    name.equals("Continuous") || name.contains("STYLE") ||
                    name.contains("DIM")) {
                    result.add(new int[]{i, len});
                }
            }
        }
        return result;
    }

    // Try to find object start by scanning backwards for MS+BS pattern
    static int findObjectStart(int namePos) {
        // Look up to 300 bytes before the name for a valid MS+BS pattern
        int start = Math.max(0, namePos - 300);
        for (int pos = start; pos < namePos; pos++) {
            // Try 16-bit LE MS: read size, then check if data_start has valid BS type
            int lo = data[pos] & 0xFF;
            int hi = data[pos + 1] & 0xFF;
            int word = lo | (hi << 8);
            boolean hasMore = (word & 0x8000) != 0;
            int size = word & 0x7FFF;
            if (hasMore) continue;
            if (size < 4 || size > 4000) continue;

            int dataStart = pos + 2;
            if (dataStart + size > data.length || dataStart >= namePos) continue;

            // Check BS type code
            int b0 = data[dataStart] & 0xFF;
            int b1 = data[dataStart + 1] & 0xFF;
            int opcode = (b0 >> 6) & 3;
            int value = ((b0 & 0x3F) << 2) | ((b1 >> 6) & 3);

            // BLOCK_HEADER = 0x30, INSERT = 0x07
            if (opcode == 1 && (value == 0x30 || value == 0x07)) {
                // Make sure namePos is within this object
                if (namePos < pos + 2 + size) {
                    return pos;
                }
            }
        }
        return -1;
    }

    public static void main(String[] args) throws Exception {
        data = Files.readAllBytes(Paths.get("210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));
        bb = ByteBuffer.wrap(data);
        bb.order(ByteOrder.LITTLE_ENDIAN);

        System.out.println("╔══════════════════════════════════════════════════════════════════════╗");
        System.out.println("║         DWG FILE BLOCK ANALYSIS - COMPLETE PARSER                   ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("File: 210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg");
        System.out.println("File size: " + data.length + " bytes (" + String.format("0x%04x", data.length) + ")");
        System.out.println("Version: AC1015 (AutoCAD R2000)");
        System.out.println();

        // Step 1: Find the first BLOCK_HEADER at 0x52b9 (Model Space)
        System.out.println("=== OBJECT #1 @ 0x52b9 ===");
        int size1 = (data[0x52b9] & 0xFF) | ((data[0x52ba] & 0xFF) << 8);
        int type1 = ((data[0x52bb] & 0x3F) << 2) | ((data[0x52bc] >> 6) & 3);
        System.out.println(String.format("  Size: %d bytes, Type: 0x%02x (%s)",
                size1 & 0x7FFF, type1, type1 == 0x30 ? "BLOCK_HEADER" : "UNKNOWN"));
        System.out.println("  This is likely the *Model_Space BLOCK_HEADER");

        // Step 2: Find all block name strings
        System.out.println("\n=== SEARCHING FOR BLOCK NAMES ===");
        List<int[]> names = findBlockNames();

        // Dedup and collect unique names
        Set<String> uniqueNames = new LinkedHashSet<>();
        Map<String, List<Integer>> namePositions = new LinkedHashMap<>();
        for (int[] nm : names) {
            String s = new String(data, nm[0] + 1, nm[1]);
            uniqueNames.add(s);
            namePositions.computeIfAbsent(s, k -> new ArrayList<>()).add(nm[0]);
        }

        System.out.println("Found " + uniqueNames.size() + " unique block names:");
        for (String name : uniqueNames) {
            List<Integer> positions = namePositions.get(name);
            System.out.println(String.format("  '%s': %d occurrence(s) @ %s",
                    name, positions.size(),
                    positions.stream()
                        .map(p -> String.format("0x%04x", p))
                        .limit(5)
                        .reduce((a, b) -> a + ", " + b).orElse("")));
        }

        // Step 3: For each unique block name, try to find its BLOCK_HEADER object
        System.out.println("\n=== TRACING BLOCK_HEADER OBJECTS ===");
        Map<String, int[]> blockHeaders = new LinkedHashMap<>(); // name -> [start, size]
        Set<Integer> usedPositions = new HashSet<>();

        for (String name : uniqueNames) {
            // For each occurrence, try to find the BLOCK_HEADER
            for (int namePos : namePositions.get(name)) {
                if (usedPositions.contains(namePos)) continue;
                int objStart = findObjectStart(namePos);
                if (objStart >= 0) {
                    int lo = data[objStart] & 0xFF;
                    int hi = data[objStart + 1] & 0xFF;
                    int size = (lo | (hi << 8)) & 0x7FFF;
                    int ds = objStart + 2;
                    int tcode = ((data[ds] & 0x3F) << 2) | ((data[ds + 1] >> 6) & 3);
                    if (tcode == 0x30) {
                        if (!blockHeaders.containsKey(name)) {
                            blockHeaders.put(name, new int[]{objStart, size});
                            System.out.println(String.format(
                                "  BLOCK_HEADER '%s' @ 0x%04x, size=%d",
                                name, objStart, size));
                            usedPositions.add(namePos);
                        }
                    }
                }
            }
            if (!blockHeaders.containsKey(name)) {
                // Try to find it as an INSERT reference
                int foundIns = 0;
                for (int namePos : namePositions.get(name)) {
                    int objStart = findObjectStart(namePos);
                    if (objStart >= 0) {
                        int lo = data[objStart] & 0xFF;
                        int hi = data[objStart + 1] & 0xFF;
                        int size = (lo | (hi << 8)) & 0x7FFF;
                        int ds = objStart + 2;
                        int tcode = ((data[ds] & 0x3F) << 2) | ((data[ds + 1] >> 6) & 3);
                        if (tcode == 0x07 && foundIns == 0) {
                            System.out.println(String.format(
                                "  '%s' - found in INSERT @ 0x%04x, size=%d",
                                name, objStart, size));
                            foundIns++;
                        }
                    }
                }
                if (foundIns == 0) {
                    System.out.println(String.format("  '%s' - no BLOCK_HEADER found, first @ 0x%04x",
                            name, namePositions.get(name).get(0)));
                }
            }
        }

        // Step 4: Count INSERT references
        System.out.println("\n=== COUNTING INSERT REFERENCES ===");
        Map<String, Integer> insertCounts = new LinkedHashMap<>();
        int totalInserts = 0;

        // Search entire object region for INSERT objects
        for (int pos = 0x52b9; pos < data.length - 20; pos++) {
            int lo = data[pos] & 0xFF;
            int hi = data[pos + 1] & 0xFF;
            int word = lo | (hi << 8);
            if ((word & 0x8000) != 0) continue; // multi-word MS, skip
            int size = word & 0x7FFF;
            if (size < 10 || size > 2000) continue;

            int ds = pos + 2;
            if (ds + size > data.length) continue;

            int b0 = data[ds] & 0xFF;
            int b1 = data[ds + 1] & 0xFF;
            int opcode = (b0 >> 6) & 3;
            int type = ((b0 & 0x3F) << 2) | ((b1 >> 6) & 3);

            if (opcode == 1 && type == 0x07) {
                // It's an INSERT! Look for block name inside
                totalInserts++;
                boolean foundBlockName = false;
                for (String bn : uniqueNames) {
                    byte[] bnBytes = bn.getBytes();
                    // Search for block name within this INSERT data
                    for (int i = ds + 2; i < ds + size - bnBytes.length; i++) {
                        boolean match = true;
                        for (int j = 0; j < bnBytes.length; j++) {
                            if ((data[i + j] & 0xFF) != bnBytes[j]) {
                                match = false; break;
                            }
                        }
                        if (match) {
                            insertCounts.merge(bn, 1, Integer::sum);
                            foundBlockName = true;
                            break;
                        }
                    }
                    if (foundBlockName) break;
                }
                if (!foundBlockName && totalInserts < 5) {
                    // Show first few unidentified inserts
                    System.out.println(String.format(
                        "  INSERT @ 0x%04x (size=%d): no block name found in data",
                        pos, size));
                }
                pos = ds + size - 1; // skip to end of this object
            }
        }

        System.out.println("\n=== FINAL REPORT ===");
        System.out.println();
        System.out.println("┌─ BLOCK DEFINITIONS ─────────────────────────────────────────────────┐");
        System.out.println("│                                                                     │");

        if (blockHeaders.isEmpty()) {
            System.out.println("│  *Model_Space (implicit, @ 0x52b9)                                  │");
        } else {
            for (Map.Entry<String, int[]> e : blockHeaders.entrySet()) {
                int[] v = e.getValue();
                System.out.println(String.format("│  BLOCK_HEADER '%-25s' @ 0x%04x (%4d bytes)            │",
                        e.getKey(), v[0], v[1]));
            }
        }
        System.out.println("│                                                                     │");
        System.out.println("├─ BLOCK USAGE (INSERT REFERENCES) ───────────────────────────────────┤");
        System.out.println("│                                                                     │");
        System.out.println(String.format("│  Total INSERT objects found: %-4d                               │", totalInserts));
        System.out.println("│                                                                     │");

        List<Map.Entry<String, Integer>> sortedCounts = new ArrayList<>(insertCounts.entrySet());
        sortedCounts.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

        for (Map.Entry<String, Integer> e : sortedCounts) {
            int count = e.getValue();
            String bar = new String(new char[Math.min(40, count * 2)]).replace('\0', '#');
            System.out.println(String.format("│  '%-25s': %4d references  %-40s  │",
                    e.getKey(), count, bar));
        }
        System.out.println("│                                                                     │");
        System.out.println("└─────────────────────────────────────────────────────────────────────┘");
        System.out.println();
        System.out.println("Done.");
    }
}
