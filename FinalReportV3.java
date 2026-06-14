import java.nio.file.*;
import java.util.*;

public class FinalReportV3 {
    static byte[] data;

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
            case 0x22: return "LWPOLYLINE";
            case 0x0E: return "ELLIPSE";
            default: return String.format("TYPE_%02X", t);
        }
    }

    // Find all objects of given type by scanning
    static List<int[]> findObjects(int targetType, int maxSize) {
        List<int[]> result = new ArrayList<>();
        for (int pos = 0x5200; pos < data.length - 4; pos++) {
            int lo = data[pos] & 0xFF;
            int hi = data[pos + 1] & 0xFF;
            int raw = lo | (hi << 8);
            if ((raw & 0x8000) != 0) continue;
            int size = raw & 0x7FFF;
            if (size < 8 || size > maxSize || pos + 2 + size > data.length) continue;
            int b0 = data[pos + 2] & 0xFF;
            int b1 = data[pos + 3] & 0xFF;
            if (((b0 >> 6) & 3) != 1) continue;
            int typeCode = ((b0 & 0x3F) << 2) | ((b1 >> 6) & 3);
            if (typeCode == targetType) {
                result.add(new int[]{pos, size});
                pos += 1 + size;  // skip past this object
            }
        }
        return result;
    }

    // Find text strings within an object
    static List<String> findTextsInObject(int offset, int size) {
        List<String> result = new ArrayList<>();
        int start = offset + 4;
        int end = offset + 2 + size;
        for (int i = start; i < end - 2; i++) {
            int len = data[i] & 0xFF;
            if (len < 1 || len > 80 || i + 1 + len > end) continue;
            StringBuilder sb = new StringBuilder();
            boolean valid = true;
            for (int j = 0; j < len; j++) {
                int c = data[i + 1 + j] & 0xFF;
                if (c < 32 || c > 126) { valid = false; break; }
                sb.append((char) c);
            }
            if (valid && sb.length() >= 2 && sb.toString().matches("[A-Za-z_*][A-Za-z0-9_*\\-]*")) {
                if (!result.contains(sb.toString())) result.add(sb.toString());
            }
        }
        return result;
    }

    public static void main(String[] args) throws Exception {
        data = Files.readAllBytes(Paths.get("210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));

        // Find BLOCK_HEADER and INSERT objects
        List<int[]> blocks = findObjects(0x30, 30000);
        List<int[]> inserts = findObjects(0x07, 30000);

        // Print report
        System.out.println("╔═══════════════════════════════════════════════════════════════════╗");
        System.out.println("║      DWG FILE BLOCK ANALYSIS - AutoCAD R2000 (AC1015)            ║");
        System.out.println("╚═══════════════════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("File: 210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg");
        System.out.println("Size: " + data.length + " bytes");
        System.out.println();

        // BLOCK DEFINITIONS
        System.out.println("━━━ BLOCK DEFINITIONS (BLOCK_HEADER objects, type=0x30) ━━━");
        System.out.println("Total found: " + blocks.size());
        Map<String, Integer> blockDefCounts = new LinkedHashMap<>();
        for (int i = 0; i < blocks.size(); i++) {
            int[] bh = blocks.get(i);
            List<String> texts = findTextsInObject(bh[0], bh[1]);
            String name;
            if (texts.isEmpty()) {
                name = (i == 0) ? "*Model_Space (standard block)" : "(block handle)";
            } else {
                name = String.join(", ", texts.subList(0, Math.min(3, texts.size())));
            }
            blockDefCounts.merge(name, 1, Integer::sum);
            System.out.println(String.format("  [%d] offset=0x%04x, size=%5d bytes, name='%s'",
                    i + 1, bh[0], bh[1], name));
        }
        System.out.println();

        // BLOCK INSERTIONS
        System.out.println("━━━ BLOCK INSERTIONS (INSERT objects, type=0x07) ━━━");
        System.out.println("Total found: " + inserts.size());
        Map<String, Integer> insertByRef = new LinkedHashMap<>();
        for (int i = 0; i < inserts.size(); i++) {
            int[] ins = inserts.get(i);
            List<String> texts = findTextsInObject(ins[0], ins[1]);
            String ref;
            if (texts.isEmpty()) {
                ref = "(block handle)";
            } else {
                ref = String.join(" / ", texts.subList(0, Math.min(2, texts.size())));
            }
            insertByRef.merge(ref, 1, Integer::sum);
            System.out.println(String.format("  [%d] offset=0x%04x, size=%5d bytes, block='%s'",
                    i + 1, ins[0], ins[1], ref));
        }
        System.out.println();

        // REFERENCE SUMMARY
        System.out.println("━━━ REFERENCE COUNT BY BLOCK ━━━");
        List<Map.Entry<String, Integer>> sortedRefs = new ArrayList<>(insertByRef.entrySet());
        sortedRefs.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        int totalCount = 0;
        for (Map.Entry<String, Integer> e : sortedRefs) totalCount += e.getValue();
        for (Map.Entry<String, Integer> e : sortedRefs) {
            int pct = e.getValue() * 100 / totalCount;
            String bar = new String(new char[Math.min(50, pct)]).replace('\0', '#');
            System.out.println(String.format("  '%-40s' : %3d (%2d%%)  %s",
                    e.getKey(), e.getValue(), pct, bar));
        }
        System.out.println();

        // ENTITY DISTRIBUTION
        System.out.println("━━━ ENTITY TYPE DISTRIBUTION ━━━");
        Map<Integer, Integer> types = new TreeMap<>();
        for (int pos = 0x5200; pos < data.length - 4; pos++) {
            int lo = data[pos] & 0xFF;
            int hi = data[pos + 1] & 0xFF;
            int raw = lo | (hi << 8);
            if ((raw & 0x8000) != 0) continue;
            int size = raw & 0x7FFF;
            if (size < 8 || size > 30000 || pos + 2 + size > data.length) continue;
            int b0 = data[pos + 2] & 0xFF;
            int b1 = data[pos + 3] & 0xFF;
            if (((b0 >> 6) & 3) != 1) continue;
            int typeCode = ((b0 & 0x3F) << 2) | ((b1 >> 6) & 3);
            if (typeCode <= 0 || typeCode > 200) continue;
            types.merge(typeCode, 1, Integer::sum);
            pos += 1 + size;
        }
        int maxTypeCount = 0;
        for (int v : types.values()) maxTypeCount = Math.max(maxTypeCount, v);
        for (Map.Entry<Integer, Integer> e : types.entrySet()) {
            int normalized = e.getValue() * 50 / Math.max(maxTypeCount, 1);
            String bar = new String(new char[normalized]).replace('\0', '#');
            System.out.println(String.format("  %-15s : %4d  %s",
                    typeName(e.getKey()), e.getValue(), bar));
        }
        System.out.println();
        System.out.println("╔═══════════════════════════════════════════════════════════════════╗");
        System.out.println("║                         END OF REPORT                             ║");
        System.out.println("╚═══════════════════════════════════════════════════════════════════╝");
    }
}
