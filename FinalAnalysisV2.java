import java.nio.file.*;
import java.util.*;

public class FinalAnalysisV2 {
    static byte[] fileData;

    // Read modular short at position
    // LE 16-bit words, bit 15 of each word = continuation flag
    // If bit 15 = 1, continue to next 16-bit word
    static int readMSSize(int pos, int[] nextPos) {
        int result = 0;
        int curIdx = pos;
        int shift = 0;
        boolean hasMore = true;
        int iterations = 0;
        while (hasMore && iterations < 8 && curIdx + 1 < fileData.length) {
            int lo = fileData[curIdx] & 0xFF;
            int hi = fileData[curIdx + 1] & 0xFF;
            int word = lo | (hi << 8);
            result |= (word & 0x7FFF) << shift;
            hasMore = (word & 0x8000) != 0;
            curIdx += 2;
            shift += 15;
            iterations++;
        }
        nextPos[0] = curIdx;
        return result;
    }

    // Parse object type code from object data bytes 0-1
    // BS encoding: byte0 = opcode(2 bits) | value_high(6 bits), byte1 = value_low(8 bits)
    // Actually: opcode = (byte0 >> 6) & 3, value = ((byte0 & 0x3F) << 2) | ((byte1 >> 6) & 3)
    // gives only 8 bits. Standard BS: 2 opcode + 14 value bits = 16 total (2 bytes)
    // But the actual R2000 format seems to use: opcode(2) + value(8 bits) = 10 bits total
    // consuming 2 bytes (the remaining 6 bits are for the next field)
    
    static int getTypeCode(int dataStart) {
        int b0 = fileData[dataStart] & 0xFF;
        int b1 = fileData[dataStart + 1] & 0xFF;
        // opcode in bits 7-6 of byte0, value in remaining 6 bits of byte0 + top 2 bits of byte1
        int value = ((b0 & 0x3F) << 2) | ((b1 >> 6) & 3);
        return value;
    }

    // Find text within object data (byte-range)
    static List<String> findTextInRange(int start, int end) {
        List<String> texts = new ArrayList<>();
        for (int i = start; i < end - 2; i++) {
            int len = fileData[i] & 0xFF;
            if (len < 1 || len > 100 || i + 1 + len > end) continue;
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
                if (t.matches("[A-Za-z_*][A-Za-z0-9_*\\-]*"))
                    texts.add(t);
            }
        }
        return texts;
    }

    static String hexByte(int offset) { return String.format("%02x", fileData[offset] & 0xFF); }

    public static void main(String[] args) throws Exception {
        fileData = Files.readAllBytes(Paths.get("210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));

        // Parse objects sequentially from 0x52b9
        int pos = 0x52b9;
        int objNum = 0;
        int maxObjects = 200;

        // Store object info
        class ObjInfo {
            int num, offset, size, type;
            String typeName;
            List<String> texts;
        }

        List<ObjInfo> blocks = new ArrayList<>();
        List<ObjInfo> inserts = new ArrayList<>();
        Map<Integer, Integer> typeDist = new HashMap<>();

        while (pos < fileData.length - 10 && objNum < maxObjects) {
            int[] nextPos = new int[1];
            int size = readMSSize(pos, nextPos);

            // Sanity check: valid object
            if (size <= 4 || size > 50000 || nextPos[0] + size > fileData.length) {
                pos++;  // advance and try again
                continue;
            }

            int dataStart = nextPos[0];

            // Get type code from object data
            int type = getTypeCode(dataStart);

            if (type < 0 || type > 10000) {
                pos++;
                continue;
            }

            typeDist.merge(type, 1, Integer::sum);

            // Extract text from object data
            List<String> texts = findTextInRange(dataStart + 2, Math.min(dataStart + size, fileData.length));

            // Determine type name
            String typeName;
            if (type == 0x30) typeName = "BLOCK_HEADER";
            else if (type == 0x07) typeName = "INSERT";
            else if (type == 0x01) typeName = "LAYER";
            else if (type == 0x0A) typeName = "TEXT";
            else if (type == 0x1F) typeName = "MTEXT";
            else if (type == 0x19) typeName = "LINE";
            else if (type == 0x04) typeName = "CIRCLE";
            else if (type == 0x05) typeName = "ARC";
            else if (type == 0x02) typeName = "CLASS";
            else if (type == 0x22) typeName = "LWPOLYLINE";
            else if (type == 0x34) typeName = "ATTRIB";
            else typeName = String.format("TYPE(0x%02x)", type);

            ObjInfo info = new ObjInfo();
            info.num = objNum;
            info.offset = pos;
            info.size = size;
            info.type = type;
            info.typeName = typeName;
            info.texts = texts;

            // Show first 30 objects or all BLOCK_HEADER/INSERT
            if (objNum < 30 || type == 0x30 || type == 0x07) {
                System.out.println(String.format("  Obj#%d @0x%04x: size=%d, type=%s(0x%02x), texts=%s",
                        objNum, pos, size, typeName, type,
                        texts.subList(0, Math.min(5, texts.size()))));

                // Show raw bytes of object data
                if (type == 0x30 || type == 0x07) {
                    System.out.print("    bytes[0-31]: ");
                    for (int i = 0; i < Math.min(32, size); i++) {
                        System.out.print(String.format("%02x ", fileData[dataStart + i] & 0xFF));
                    }
                    System.out.println();
                }
            }

            if (type == 0x30) blocks.add(info);
            if (type == 0x07) inserts.add(info);

            pos = dataStart + size;
            objNum++;
        }

        // Summary
        System.out.println("\n=== OBJECT TYPE DISTRIBUTION ===");
        List<Map.Entry<Integer, Integer>> sortedTypes = new ArrayList<>(typeDist.entrySet());
        sortedTypes.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        for (int i = 0; i < Math.min(20, sortedTypes.size()); i++) {
            Map.Entry<Integer, Integer> e = sortedTypes.get(i);
            int t = e.getKey();
            String name;
            if (t == 0x30) name = "BLOCK_HEADER";
            else if (t == 0x07) name = "INSERT";
            else if (t == 0x01) name = "LAYER";
            else if (t == 0x0A) name = "TEXT";
            else if (t == 0x1F) name = "MTEXT";
            else if (t == 0x19) name = "LINE";
            else if (t == 0x04) name = "CIRCLE";
            else if (t == 0x05) name = "ARC";
            else if (t == 0x02) name = "CLASS";
            else if (t == 0x22) name = "LWPOLYLINE";
            else if (t == 0x34) name = "ATTRIB";
            else name = String.format("0x%02x", t);
            System.out.println(String.format("  %s (%d): %d object(s)", name, t, e.getValue()));
        }

        System.out.println("\n=== BLOCK DEFINITIONS (BLOCK_HEADER) ===");
        Map<String, Integer> blockDefs = new LinkedHashMap<>();
        for (ObjInfo bh : blocks) {
            // Find best block name
            String name = "";
            // Prefer names starting with *
            for (String t : bh.texts) {
                if (t.startsWith("*") || t.startsWith("ACAD_")) {
                    name = t;
                    break;
                }
            }
            if (name.isEmpty()) {
                for (String t : bh.texts) {
                    if (t.length() >= 3 && t.matches("[A-Za-z][A-Za-z0-9_]*")) {
                        name = t;
                        break;
                    }
                }
            }
            if (name.isEmpty()) name = String.format("UNNAMED_%d(@0x%04x)", bh.num, bh.offset);
            blockDefs.merge(name, 1, Integer::sum);
            System.out.println(String.format("  #%d @0x%04x: '%s' (size=%d bytes, texts=%s)",
                    bh.num, bh.offset, name, bh.size, bh.texts));
        }

        System.out.println("\n=== BLOCK INSERTIONS (INSERT) ===");
        Map<String, Integer> insertRefs = new LinkedHashMap<>();
        int totalCounted = 0;
        for (ObjInfo ins : inserts) {
            String name = "";
            // Find block name (not text style names)
            for (String t : ins.texts) {
                if (t.startsWith("*") || t.startsWith("ACAD_")) {
                    name = t;
                    break;
                }
            }
            if (name.isEmpty()) {
                for (String t : ins.texts) {
                    if (t.matches("[A-Za-z_][A-Za-z0-9_]*") && !t.equals("TXT")
                            && !t.contains("STYLE") && !t.equals("Continuous")) {
                        name = t;
                        break;
                    }
                }
            }
            if (name.isEmpty()) name = ins.texts.isEmpty() ? "" : ins.texts.get(0);

            if (!name.isEmpty()) {
                insertRefs.merge(name, 1, Integer::sum);
                totalCounted++;
            }
            System.out.println(String.format("  #%d @0x%04x: block='%s' (size=%d, texts=%s)",
                    ins.num, ins.offset, name, ins.size, ins.texts));
        }

        // Final summary report
        System.out.println("\n================================================");
        System.out.println("FINAL REPORT - DWG BLOCK ANALYSIS");
        System.out.println("================================================");
        System.out.println("File: 210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg");
        System.out.println("File size: " + fileData.length + " bytes");
        System.out.println("DWG Version: R2000 (AC1015)");
        System.out.println();

        System.out.println("--- Block Definitions ---");
        System.out.println("Total BLOCK_HEADER objects found: " + blocks.size());
        System.out.println();
        for (Map.Entry<String, Integer> b : blockDefs.entrySet()) {
            System.out.println(String.format("  '%s': %d definition(s)", b.getKey(), b.getValue()));
        }

        System.out.println("\n--- Block Insertions ---");
        System.out.println("Total INSERT objects found: " + inserts.size());
        System.out.println();
        List<Map.Entry<String, Integer>> sortedIns = new ArrayList<>(insertRefs.entrySet());
        sortedIns.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        int total = 0;
        for (Map.Entry<String, Integer> e : sortedIns) {
            System.out.println(String.format("  '%s': %d insertion(s)", e.getKey(), e.getValue()));
            total += e.getValue();
        }
        System.out.println(String.format("  (unidentified: %d)", inserts.size() - totalCounted));
        System.out.println("================================================");
    }
}
