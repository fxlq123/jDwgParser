import java.nio.file.*;
import java.util.*;

public class DwgBlockReport {
    static byte[] data;

    // Parse an object at given position
    static ObjectInfo parseObject(int pos) {
        int lo = data[pos] & 0xFF;
        int hi = data[pos + 1] & 0xFF;
        int size = (lo | (hi << 8)) & 0x7FFF;
        boolean cont = (lo | (hi << 8) & 0x8000) != 0;
        if (cont || size < 8 || size > 30000 || pos + 2 + size > data.length) return null;

        int b0 = data[pos + 2] & 0xFF;
        int b1 = data[pos + 3] & 0xFF;
        int opcode = (b0 >> 6) & 3;
        if (opcode != 1) return null;
        int typeCode = ((b0 & 0x3F) << 2) | ((b1 >> 6) & 3);

        ObjectInfo obj = new ObjectInfo();
        obj.offset = pos;
        obj.size = size;
        obj.typeCode = typeCode;
        obj.typeName = typeName(typeCode);

        // Search for text strings in data
        int dataStart = pos + 4;
        int dataEnd = pos + 2 + size;
        List<String> texts = new ArrayList<>();
        for (int i = dataStart; i < dataEnd - 2; i++) {
            int len = data[i] & 0xFF;
            if (len < 1 || len > 80 || i + 1 + len > dataEnd) continue;
            boolean valid = true;
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < len; j++) {
                int c = data[i + 1 + j] & 0xFF;
                if (c < 32 || c > 126) { valid = false; break; }
                sb.append((char) c);
            }
            if (valid && sb.length() >= 2 && sb.toString().matches("[A-Za-z_*][A-Za-z0-9_*\\-]*")) {
                if (!texts.contains(sb.toString())) texts.add(sb.toString());
            }
        }
        obj.texts = texts;
        return obj;
    }

    static String typeName(int t) {
        if (t == 0x30) return "BLOCK_HEADER";
        if (t == 0x07) return "INSERT";
        if (t == 0x01) return "LAYER";
        if (t == 0x04) return "CIRCLE";
        if (t == 0x05) return "ARC";
        if (t == 0x19) return "LINE";
        if (t == 0x15) return "DIMENSION";
        if (t == 0x22) return "LWPOLYLINE";
        if (t == 0x0A) return "TEXT";
        if (t == 0x1F) return "MTEXT";
        if (t == 0x0E) return "ELLIPSE";
        return String.format("TYPE_%02X", t);
    }

    static class ObjectInfo {
        int offset, size, typeCode;
        String typeName;
        List<String> texts;
    }

    public static void main(String[] args) throws Exception {
        data = Files.readAllBytes(Paths.get("210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));

        // Scan for objects at even positions (objects are 2-byte aligned)
        List<ObjectInfo> allBlocks = new ArrayList<>();
        List<ObjectInfo> allInserts = new ArrayList<>();
        Set<Integer> scannedRanges = new HashSet<>();
        Map<String, Integer> typeDistribution = new TreeMap<>();

        for (int pos = 0x5200; pos < data.length - 4; pos++) {
            // Check if this position is already inside a known object
            boolean inside = false;
            for (int s : scannedRanges) {
                if (pos == s) { inside = true; break; }
            }
            if (inside) continue;

            ObjectInfo obj = parseObject(pos);
            if (obj != null && (obj.typeCode == 0x30 || obj.typeCode == 0x07)) {
                // Mark this range as scanned
                for (int skip = pos; skip < pos + 2 + obj.size; skip++) {
                    scannedRanges.add(skip);
                }
                if (obj.typeCode == 0x30) allBlocks.add(obj);
                else allInserts.add(obj);
            }
        }

        // Also collect type distribution for all objects
        scannedRanges.clear();
        for (int pos = 0x5200; pos < data.length - 4; pos++) {
            if (scannedRanges.contains(pos)) continue;
            ObjectInfo obj = parseObject(pos);
            if (obj != null && obj.typeCode != 0) {
                for (int skip = pos; skip < pos + 2 + obj.size; skip++) scannedRanges.add(skip);
                typeDistribution.merge(obj.typeName, 1, Integer::sum);
            }
        }

        // Generate report
        System.out.println("╔════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║        DWG FILE BLOCK ANALYSIS REPORT - AutoCAD R2000 (AC1015)         ║");
        System.out.println("╚════════════════════════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("File: 210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg");
        System.out.println("File size: " + data.length + " bytes");
        System.out.println();

        // Block definitions
        System.out.println("┌─────────────────────────────────────────────────────────────────────────┐");
        System.out.println("│ BLOCK DEFINITIONS (BLOCK_HEADER objects, type=0x30)                    │");
        System.out.println("├─────────────────────────────────────────────────────────────────────────┤");
        System.out.println(String.format("│ Total found: %-3d                                                      │",
                allBlocks.size()));
        System.out.println("├─────────────────────────────────────────────────────────────────────────┤");

        // First BLOCK_HEADER at 0x52b9 = *Model_Space (implicit block)
        int blockNum = 1;
        for (ObjectInfo bh : allBlocks) {
            String name = bh.texts.isEmpty() ? (blockNum == 1 ? "*Model_Space (implicit)" : "(no name in data)") : String.join(", ", bh.texts.subList(0, Math.min(3, bh.texts.size())));
            System.out.println(String.format("│ Block #%d:  offset=0x%04x, size=%d bytes, name='%s'   │",
                    blockNum, bh.offset, bh.size, name));
            blockNum++;
        }

        // Block insertions
        System.out.println("├─────────────────────────────────────────────────────────────────────────┤");
        System.out.println("│ BLOCK INSERTIONS (INSERT objects, type=0x07)                           │");
        System.out.println("├─────────────────────────────────────────────────────────────────────────┤");
        System.out.println(String.format("│ Total found: %-3d                                                      │",
                allInserts.size()));
        System.out.println("├─────────────────────────────────────────────────────────────────────────┤");

        int insertNum = 1;
        // Count by block name
        Map<String, Integer> insertByBlock = new LinkedHashMap<>();
        for (ObjectInfo ins : allInserts) {
            String ref = ins.texts.isEmpty() ? "(bit-encoded block handle)" :
                ins.texts.get(0);
            insertByBlock.merge(ref, 1, Integer::sum);
        }

        List<Map.Entry<String, Integer>> sortedInsertBy = new ArrayList<>(insertByBlock.entrySet());
        sortedInsertBy.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

        int counter = 1;
        for (Map.Entry<String, Integer> e : sortedInsertBy) {
            String bar = new String(new char[Math.min(40, e.getValue() * 3)]).replace('\0', '#');
            System.out.println(String.format("│ Insert #%-2d: %-30s %4d  %-40s  │",
                    counter, "'" + e.getKey() + "'", e.getValue(), bar));
            counter++;
        }

        // Summary: list all INSERTs with offsets
        System.out.println("├─────────────────────────────────────────────────────────────────────────┤");
        System.out.println("│ INSERT object positions:                                               │");
        System.out.println("├─────────────────────────────────────────────────────────────────────────┤");
        StringBuilder line = new StringBuilder("│ ");
        for (int i = 0; i < allInserts.size(); i++) {
            ObjectInfo ins = allInserts.get(i);
            String entry = String.format("0x%04x(%dB)", ins.offset, ins.size);
            if (line.length() + entry.length() + 2 > 72) {
                while (line.length() < 72) line.append(" ");
                line.append(" │");
                System.out.println(line.toString());
                line = new StringBuilder("│ ");
            }
            line.append(entry).append(" ");
        }
        while (line.length() < 72) line.append(" ");
        line.append(" │");
        System.out.println(line.toString());

        // Object type distribution
        System.out.println("├─────────────────────────────────────────────────────────────────────────┤");
        System.out.println("│ All entity type distribution in file:                                  │");
        System.out.println("├─────────────────────────────────────────────────────────────────────────┤");
        for (Map.Entry<String, Integer> e : typeDistribution.entrySet()) {
            System.out.println(String.format("│ %-30s : %4d object(s)                                │",
                    e.getKey(), e.getValue()));
        }

        System.out.println("└─────────────────────────────────────────────────────────────────────────┘");
        System.out.println();
        System.out.println("End of report.");
    }
}
