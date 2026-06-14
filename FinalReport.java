import java.nio.file.*;
import java.util.*;

public class FinalReport {
    static byte[] fileData;

    // Read modular short: 16-bit LE words, bit 15 = continuation flag
    static int readMS16(int pos, int[] nextPos) {
        int result = 0;
        int curIdx = pos;
        int shift = 0;
        boolean hasMore = true;
        while (hasMore && curIdx + 1 < fileData.length) {
            int lo = fileData[curIdx] & 0xFF;
            int hi = fileData[curIdx + 1] & 0xFF;
            int word = lo | (hi << 8);
            result |= (word & 0x7FFF) << shift;
            hasMore = (word & 0x8000) != 0;
            curIdx += 2;
            shift += 15;
        }
        nextPos[0] = curIdx;
        return result;
    }

    static int getTypeCode(int dataStart) {
        int b0 = fileData[dataStart] & 0xFF;
        int b1 = fileData[dataStart + 1] & 0xFF;
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
        if (t == 0x0E) return "ELLIPSE";
        if (t == 0x0B) return "ARC";
        if (t == 0x1B) return "SOLID";
        return String.format("T%02x", t);
    }

    public static void main(String[] args) throws Exception {
        fileData = Files.readAllBytes(Paths.get("210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));

        int pos = 0x52b9;
        int objNum = 0;

        class Obj { int num, offset, size, type; String typeName; List<String> texts; }

        List<Obj> blocks = new ArrayList<>();
        List<Obj> inserts = new ArrayList<>();
        List<Obj> all = new ArrayList<>();
        Map<Integer, Integer> typeDist = new HashMap<>();

        while (pos < fileData.length - 20 && objNum < 500) {
            int[] nextPos = new int[1];
            int size = readMS16(pos, nextPos);

            if (size <= 2 || size > 20000 || nextPos[0] + size > fileData.length) {
                pos++;
                continue;
            }

            int dataStart = nextPos[0];
            int type = getTypeCode(dataStart);

            if (type < 0 || type > 500) {
                pos++;
                continue;
            }

            List<String> texts = findTextInRange(dataStart + 2, Math.min(dataStart + size, fileData.length));

            typeDist.merge(type, 1, Integer::sum);

            Obj info = new Obj();
            info.num = objNum;
            info.offset = pos;
            info.size = size;
            info.type = type;
            info.typeName = typeName(type);
            info.texts = texts;
            all.add(info);

            if (type == 0x30) blocks.add(info);
            if (type == 0x07) inserts.add(info);

            pos = dataStart + size;
            objNum++;
        }

        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║           DWG BLOCK ANALYSIS - FINAL REPORT              ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("File: 210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg");
        System.out.println("File size: " + fileData.length + " bytes");
        System.out.println("DWG Version: R2000 (AC1015)");
        System.out.println("Total objects parsed: " + all.size());
        System.out.println();

        System.out.println("--- Object Type Distribution ---");
        List<Map.Entry<Integer, Integer>> sortedTypes = new ArrayList<>(typeDist.entrySet());
        sortedTypes.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        for (Map.Entry<Integer, Integer> e : sortedTypes) {
            System.out.println(String.format("  %-12s : %d", typeName(e.getKey()), e.getValue()));
        }

        System.out.println("\n--- Block Definitions (BLOCK_HEADER objects) ---");
        System.out.println("Total found: " + blocks.size());
        Map<String, Integer> blockDefs = new LinkedHashMap<>();
        for (Obj bh : blocks) {
            String name = "";
            for (String t : bh.texts) {
                if (t.startsWith("*") || t.startsWith("ACAD_")) {
                    name = t;
                    break;
                }
            }
            if (name.isEmpty()) {
                for (String t : bh.texts) {
                    if (t.length() >= 3 && t.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                        name = t;
                        break;
                    }
                }
            }
            if (name.isEmpty() && !bh.texts.isEmpty()) name = bh.texts.get(0);
            if (name.isEmpty()) name = String.format("UNNAMED_BLOCK_%d", bh.num);
            blockDefs.merge(name, 1, Integer::sum);
            System.out.println(String.format("  %-25s (%d bytes, @0x%04x) texts=%s",
                    "'" + name + "'", bh.size, bh.offset,
                    bh.texts.subList(0, Math.min(6, bh.texts.size()))));
        }

        System.out.println("\n--- Block Insertions (INSERT objects) ---");
        System.out.println("Total found: " + inserts.size());
        Map<String, Integer> insertRefs = new LinkedHashMap<>();
        int totalCounted = 0;
        for (Obj ins : inserts) {
            String name = "";
            for (String t : ins.texts) {
                if (t.startsWith("*") || t.startsWith("ACAD_")) {
                    name = t;
                    break;
                }
            }
            if (name.isEmpty()) {
                for (String t : ins.texts) {
                    if (t.length() >= 2 && !t.equals("TXT") && !t.contains("STYLE")
                            && !t.equals("Continuous") && t.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                        name = t;
                        break;
                    }
                }
            }
            if (name.isEmpty() && !ins.texts.isEmpty()) name = ins.texts.get(0);
            if (name.isEmpty()) name = "<unknown>";

            if (!name.equals("<unknown>")) {
                insertRefs.merge(name, 1, Integer::sum);
                totalCounted++;
            }

            System.out.println(String.format("  block='%-18s' (size=%d, @0x%04x, texts=%s)",
                    name, ins.size, ins.offset,
                    ins.texts.subList(0, Math.min(5, ins.texts.size()))));
        }

        System.out.println("\n=== SUMMARY ===");
        System.out.println("Block definitions: " + blocks.size());
        for (Map.Entry<String, Integer> b : blockDefs.entrySet()) {
            System.out.println("  - " + b.getKey() + ": " + b.getValue() + " definition(s)");
        }
        System.out.println();
        System.out.println("Block insertions: " + inserts.size());
        List<Map.Entry<String, Integer>> sortedIns = new ArrayList<>(insertRefs.entrySet());
        sortedIns.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        for (Map.Entry<String, Integer> e : sortedIns) {
            System.out.println("  - " + e.getKey() + ": " + e.getValue() + " insertion(s)");
        }
        if (inserts.size() > totalCounted) {
            System.out.println("  - Unidentified: " + (inserts.size() - totalCounted));
        }
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║                END OF ANALYSIS                           ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
    }
}
