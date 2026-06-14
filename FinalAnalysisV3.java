import java.nio.file.*;
import java.util.*;

public class FinalAnalysisV3 {
    static byte[] fileData;

    // Read modular short (byte-based): each byte's bit 7 = continuation flag
    // bit 7 = 1 → more bytes follow, bit 7 = 0 → this is last byte
    // value = concatenation of low 7 bits of each byte (big-endian within the sequence)
    static int readByteMS(int pos, int[] nextPos) {
        int result = 0;
        int curIdx = pos;
        boolean hasMore = true;
        int iterations = 0;
        while (hasMore && iterations < 8 && curIdx < fileData.length) {
            int b = fileData[curIdx] & 0xFF;
            result = (result << 7) | (b & 0x7F);
            hasMore = (b & 0x80) != 0;
            curIdx++;
            iterations++;
        }
        nextPos[0] = curIdx;
        return result;
    }

    // Get type code from first 2 bytes of object data (BS encoding)
    static int getTypeCode(int dataStart) {
        int b0 = fileData[dataStart] & 0xFF;
        int b1 = fileData[dataStart + 1] & 0xFF;
        // opcode = (b0 >> 6) & 3, value = low 6 bits of b0 + high 2 bits of b1
        return ((b0 & 0x3F) << 2) | ((b1 >> 6) & 3);
    }

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

    static String typeName(int t) {
        if (t == 0x30) return "BLOCK_HEADER";
        if (t == 0x07) return "INSERT";
        if (t == 0x01) return "LAYER";
        if (t == 0x0A) return "TEXT";
        if (t == 0x1F) return "MTEXT";
        if (t == 0x19) return "LINE";
        if (t == 0x04) return "CIRCLE";
        if (t == 0x05) return "ARC";
        if (t == 0x22) return "LWPOLYLINE";
        if (t == 0x34) return "ATTRIB";
        if (t == 0x15) return "DIMENSION";
        if (t == 0x2F) return "LWPOLYLINE";
        return String.format("T0x%02x", t);
    }

    public static void main(String[] args) throws Exception {
        fileData = Files.readAllBytes(Paths.get("210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));

        int pos = 0x52b9;
        int objNum = 0;
        int maxObjects = 500;

        class ObjInfo {
            int num, offset, size, type;
            String typeName;
            List<String> texts;
        }

        List<ObjInfo> blocks = new ArrayList<>();
        List<ObjInfo> inserts = new ArrayList<>();
        List<ObjInfo> allObjs = new ArrayList<>();
        Map<Integer, Integer> typeDist = new HashMap<>();

        // First pass: parse all objects
        while (pos < fileData.length - 10 && objNum < maxObjects) {
            int[] nextPos = new int[1];
            int size = readByteMS(pos, nextPos);

            if (size <= 2 || size > 20000 || nextPos[0] + size > fileData.length) {
                pos++;
                continue;
            }

            int dataStart = nextPos[0];
            int type = getTypeCode(dataStart);

            // Validate type
            if (type < 0 || type > 200) {
                pos++;
                continue;
            }

            // Get text from object data (skip the 2-byte type code)
            List<String> texts = findTextInRange(dataStart + 2, Math.min(dataStart + size, fileData.length));

            typeDist.merge(type, 1, Integer::sum);

            ObjInfo info = new ObjInfo();
            info.num = objNum;
            info.offset = pos;
            info.size = size;
            info.type = type;
            info.typeName = typeName(type);
            info.texts = texts;
            allObjs.add(info);

            if (type == 0x30) blocks.add(info);
            if (type == 0x07) inserts.add(info);

            pos = dataStart + size;
            objNum++;
        }

        System.out.println("=== R2000 DWG OBJECT ANALYSIS ===");
        System.out.println("Total objects parsed: " + allObjs.size());
        System.out.println();

        System.out.println("--- Object Type Distribution ---");
        List<Map.Entry<Integer, Integer>> sortedTypes = new ArrayList<>(typeDist.entrySet());
        sortedTypes.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        for (Map.Entry<Integer, Integer> e : sortedTypes) {
            System.out.println(String.format("  %s: %d object(s)", typeName(e.getKey()), e.getValue()));
        }

        System.out.println("\n--- BLOCK_HEADER Objects ---");
        Map<String, Integer> blockDefs = new LinkedHashMap<>();
        System.out.println("Total: " + blocks.size());
        for (ObjInfo bh : blocks) {
            // Determine block name from text or position
            String name = "";
            for (String t : bh.texts) {
                if (t.startsWith("*") || t.startsWith("ACAD_") ||
                    (t.length() >= 3 && t.matches("[A-Za-z_][A-Za-z0-9_]*"))) {
                    name = t;
                    break;
                }
            }
            if (name.isEmpty() && !bh.texts.isEmpty()) name = bh.texts.get(0);
            if (name.isEmpty()) name = String.format("BLOCK_%d(@0x%04x)", bh.num, bh.offset);

            blockDefs.merge(name, 1, Integer::sum);
            System.out.println(String.format("  #%d @0x%04x: size=%d, name='%s', texts=%s",
                    bh.num, bh.offset, bh.size, name, bh.texts));
        }

        System.out.println("\n--- INSERT Objects ---");
        Map<String, Integer> insertRefs = new LinkedHashMap<>();
        System.out.println("Total: " + inserts.size());
        int totalCounted = 0;
        for (ObjInfo ins : inserts) {
            String name = "";
            for (String t : ins.texts) {
                if (t.startsWith("*") || t.startsWith("ACAD_") ||
                    (t.length() >= 2 && !t.equals("TXT") && !t.contains("STYLE") &&
                     !t.equals("Continuous") && t.matches("[A-Za-z_][A-Za-z0-9_]*"))) {
                    name = t;
                    break;
                }
            }
            if (name.isEmpty() && !ins.texts.isEmpty()) name = ins.texts.get(0);

            if (!name.isEmpty()) {
                insertRefs.merge(name, 1, Integer::sum);
                totalCounted++;
            }

            System.out.println(String.format("  #%d @0x%04x: size=%d, block='%s', texts=%s",
                    ins.num, ins.offset, ins.size, name, ins.texts));
        }

        // Summary
        System.out.println("\n================================================");
        System.out.println("FINAL REPORT");
        System.out.println("================================================");
        System.out.println("File: 210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg");
        System.out.println("Size: " + fileData.length + " bytes");
        System.out.println("Version: R2000 (AC1015)");
        System.out.println();

        System.out.println("--- Block Definitions ---");
        System.out.println("Total BLOCK_HEADER: " + blocks.size());
        for (Map.Entry<String, Integer> b : blockDefs.entrySet()) {
            System.out.println(String.format("  '%s': %d", b.getKey(), b.getValue()));
        }

        System.out.println("\n--- Block Insertions ---");
        System.out.println("Total INSERT: " + inserts.size());
        List<Map.Entry<String, Integer>> sortedIns = new ArrayList<>(insertRefs.entrySet());
        sortedIns.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        int sum = 0;
        for (Map.Entry<String, Integer> e : sortedIns) {
            System.out.println(String.format("  '%s': %d time(s)", e.getKey(), e.getValue()));
            sum += e.getValue();
        }
        if (inserts.size() > totalCounted) {
            System.out.println(String.format("  (unidentified: %d)", inserts.size() - totalCounted));
        }
        System.out.println("================================================");
    }
}
