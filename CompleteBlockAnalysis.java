import java.nio.file.*;
import java.util.*;

public class CompleteBlockAnalysis {
    static byte[] fileData;

    // Check if bytes at offset match BS type code
    static boolean isTypeBH(int offset) {
        // BLOCK_HEADER type 0x30: byte pattern 0x4c, (0x00-0x3f)
        if (offset + 1 >= fileData.length) return false;
        int b0 = fileData[offset] & 0xFF;
        int b1 = fileData[offset + 1] & 0xFF;
        return b0 == 0x4c && (b1 & 0xC0) == 0x00;
    }

    static boolean isTypeInsert(int offset) {
        // INSERT type 0x07: byte pattern 0x41, (0xc0-0xff)
        if (offset + 1 >= fileData.length) return false;
        int b0 = fileData[offset] & 0xFF;
        int b1 = fileData[offset + 1] & 0xFF;
        return b0 == 0x41 && (b1 & 0xC0) == 0xC0;
    }

    // Find all text ([length byte][ASCII]) within a byte range
    static List<String> findText(int start, int end, int minLen) {
        List<String> texts = new ArrayList<>();
        for (int i = start; i < end - 2; i++) {
            int len = fileData[i] & 0xFF;
            if (len < minLen || len > 100 || i + 1 + len > end) continue;

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
                String t = sb.toString();
                // Filter valid block name patterns
                if (t.matches("[A-Za-z_*][A-Za-z0-9_*\\-]*")) {
                    texts.add(t);
                }
            }
        }
        return texts;
    }

    // Find all INSERT references: get bytes 2-4 of object data (block handle ref)
    static String getBlockReference(int offset) {
        // After type code (2 bytes), the next bytes might contain block header handle
        // Look for BS-prefixed or raw handle references in object data
        // Common pattern: after type code, several zero bytes then handle 0x?? 0x?? 0x?? 0x??
        int dataStart = offset;
        // Scan next ~50 bytes for patterns that look like handles
        // Looking for: 0x?? 0x01 or 0x?? 0x22 or 0x?? 0x?? 0x?? 0x?? (handle refs)
        for (int i = 2; i < 50 && dataStart + i + 3 < fileData.length; i++) {
            int b0 = fileData[dataStart + i] & 0xFF;
            int b1 = fileData[dataStart + i + 1] & 0xFF;
            // Pattern: 0x40 XX, 0xXX 0x40, or handle-like 2-byte code
            if (b0 == 0x40 && b1 > 0x00 && b1 < 0xFF) {
                // Could be handle reference: 0x40 (opcode) + value
                return String.format("handle_ref:0x40%02x", b1);
            }
        }
        return "";
    }

    // Look for block name in INSERT data (look for distinctive ACAD_* or known block names)
    static String findBlockNameInInsert(int offset, int range) {
        List<String> texts = findText(offset + 2, Math.min(offset + range, fileData.length), 3);
        // Prioritize known block name patterns
        for (String t : texts) {
            if (t.startsWith("ACAD_") || t.equals("*Model_Space") || t.equals("*Paper_Space"))
                return t;
            if (t.length() >= 3 && !t.contains("ACAD_") && !t.matches(".*\\s.*"))
                return t;
        }
        // Return first valid text if nothing found
        return texts.isEmpty() ? "" : texts.get(0);
    }

    static String hex(int val) { return String.format("%04x", val); }

    public static void main(String[] args) throws Exception {
        fileData = Files.readAllBytes(Paths.get("210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));

        int scanStart = 0x5200;
        int scanEnd = Math.min(fileData.length, 0x0f000);

        // 1. Find all BLOCK_HEADER objects
        List<Integer> bhList = new ArrayList<>();
        // 2. Find all INSERT objects
        List<Integer> insList = new ArrayList<>();

        for (int i = scanStart; i < scanEnd; i++) {
            if (isTypeBH(i)) {
                // Verify: check that this isn't inside another object (heuristic)
                // Require that preceding bytes are 0x00 0x?? or 0x?? 0x?? (size prefix)
                bhList.add(i);
            }
            if (isTypeInsert(i)) {
                insList.add(i);
            }
        }

        System.out.println("=== BLOCK DEFINITION ANALYSIS ===");
        System.out.println("File size: " + fileData.length + " bytes");
        System.out.println("BLOCK_HEADER candidates: " + bhList.size());
        System.out.println("INSERT candidates: " + insList.size());
        System.out.println();

        // Analyze BLOCK_HEADER objects
        System.out.println("=== BLOCK HEADER OBJECTS ===");
        Map<Integer, String> blockMap = new LinkedHashMap<>(); // offset -> name
        Set<String> allBlockNames = new LinkedHashSet<>();

        for (int bh : bhList) {
            // Extract text from the next 200 bytes
            List<String> texts = findText(bh, bh + 200, 3);
            String bestName = "";

            // Find the most block-like name
            for (String t : texts) {
                if (t.equals("*Model_Space") || t.equals("*Paper_Space")) {
                    bestName = t;
                    break;
                }
                if (t.startsWith("*")) {
                    bestName = t;
                    break;
                }
                if (t.startsWith("ACAD_") && bestName.isEmpty()) {
                    bestName = t;
                }
            }
            if (bestName.isEmpty() && !texts.isEmpty()) {
                // Take first text not matching common noise
                for (String t : texts) {
                    if (t.matches("[A-Za-z][A-Za-z0-9_]*") && t.length() >= 3 && t.length() <= 30) {
                        bestName = t;
                        break;
                    }
                }
            }

            blockMap.put(bh, bestName);
            if (!bestName.isEmpty()) allBlockNames.add(bestName);

            System.out.println(String.format("  @0x%s: '%s' (next 8 bytes: %s %s %s %s %s %s %s %s)",
                    Integer.toHexString(bh), bestName,
                    String.format("%02x", fileData[bh] & 0xFF),
                    String.format("%02x", fileData[bh+1] & 0xFF),
                    String.format("%02x", fileData[bh+2] & 0xFF),
                    String.format("%02x", fileData[bh+3] & 0xFF),
                    String.format("%02x", fileData[bh+4] & 0xFF),
                    String.format("%02x", fileData[bh+5] & 0xFF),
                    String.format("%02x", fileData[bh+6] & 0xFF),
                    String.format("%02x", fileData[bh+7] & 0xFF)));
        }

        // Analyze INSERT objects
        System.out.println("\n=== INSERT OBJECTS ===");
        Map<String, Integer> insertCounts = new LinkedHashMap<>();
        int unnamedInserts = 0;

        for (int ins : insList) {
            List<String> texts = findText(ins, ins + 300, 3);
            String blockName = "";

            // Look for the most likely block reference name
            for (String t : texts) {
                if (t.equals("*Model_Space") || t.equals("*Paper_Space") || t.startsWith("ACAD_")) {
                    blockName = t;
                    break;
                }
            }
            // Look for non-ACAD names too
            if (blockName.isEmpty()) {
                for (String t : texts) {
                    if (t.matches("[A-Za-z][A-Za-z0-9_]*") && !t.contains("ACAD")
                            && !t.contains("STANDARD") && !t.equals("STYLE")) {
                        blockName = t;
                        break;
                    }
                }
            }
            // Use first text found
            if (blockName.isEmpty() && !texts.isEmpty()) {
                blockName = texts.get(0);
            }

            if (!blockName.isEmpty()) {
                insertCounts.merge(blockName, 1, Integer::sum);
            } else {
                unnamedInserts++;
            }

            System.out.println(String.format("  @0x%s: block='%s', texts=%s",
                    Integer.toHexString(ins), blockName,
                    texts.subList(0, Math.min(5, texts.size()))));
        }

        // Final summary
        System.out.println("\n=== FINAL SUMMARY ===");
        System.out.println();
        System.out.println("Blocks defined (BLOCK_HEADER): " + allBlockNames.size());
        System.out.println("Block names found: " + allBlockNames);
        System.out.println();
        System.out.println("Block insertions (INSERT):");
        int totalIns = 0;
        List<Map.Entry<String, Integer>> sortedCounts = new ArrayList<>(insertCounts.entrySet());
        sortedCounts.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        for (Map.Entry<String, Integer> e : sortedCounts) {
            System.out.println(String.format("  '%s': %d time(s)", e.getKey(), e.getValue()));
            totalIns += e.getValue();
        }
        System.out.println("  (unnamed/unsure: " + unnamedInserts + ")");
        System.out.println("  Total: " + (totalIns + unnamedInserts) + " INSERT(s)");
    }
}
