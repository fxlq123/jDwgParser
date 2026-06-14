import java.nio.file.*;
import java.util.*;

public class FinalReportV2 {
    static byte[] data;

    static String typeName(int t) {
        if (t == 0x30) return "BLOCK_HEADER";
        if (t == 0x07) return "INSERT";
        if (t == 0x01) return "LAYER";
        if (t == 0x04) return "CIRCLE";
        if (t == 0x05) return "ARC";
        if (t == 0x19) return "LINE";
        if (t == 0x15) return "DIMENSION";
        return String.format("TYPE_%02X", t);
    }

    public static void main(String[] args) throws Exception {
        data = Files.readAllBytes(Paths.get("210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));

        List<int[]> blocks = new ArrayList<>();  // [offset, size]
        List<int[]> inserts = new ArrayList<>();
        Map<String, Integer> typeDist = new TreeMap<>();

        // Find all valid objects by scanning every position
        // Use a boolean array to track positions inside known objects
        boolean[] insideObj = new boolean[data.length];

        for (int pos = 0x5200; pos < data.length - 4; pos++) {
            if (insideObj[pos]) continue;

            int lo = data[pos] & 0xFF;
            int hi = data[pos + 1] & 0xFF;
            int rawSize = lo | (hi << 8);
            if ((rawSize & 0x8000) != 0) continue;  // continuation flag
            int size = rawSize & 0x7FFF;
            if (size < 8 || size > 3000) continue;
            if (pos + 2 + size > data.length) continue;

            // Check BS type code
            int b0 = data[pos + 2] & 0xFF;
            int b1 = data[pos + 3] & 0xFF;
            if (((b0 >> 6) & 3) != 1) continue;  // opcode must be 01
            int typeCode = ((b0 & 0x3F) << 2) | ((b1 >> 6) & 3);

            // Validate
            if (typeCode <= 0 || typeCode > 200) continue;

            // Mark this object's bytes as used
            for (int k = pos; k < pos + 2 + size && k < data.length; k++) {
                insideObj[k] = true;
            }

            typeDist.merge(typeName(typeCode), 1, Integer::sum);

            if (typeCode == 0x30) {
                blocks.add(new int[]{pos, size});
            } else if (typeCode == 0x07) {
                inserts.add(new int[]{pos, size});
            }
        }

        // Extract block names
        Map<Integer, List<String>> blockNames = new HashMap<>();
        Map<Integer, List<String>> insertNames = new HashMap<>();

        for (int[] obj : blocks) {
            List<String> names = new ArrayList<>();
            int start = obj[0] + 4;
            int end = obj[0] + 2 + obj[1];
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
                    if (!names.contains(sb.toString())) names.add(sb.toString());
                }
            }
            blockNames.put(obj[0], names);
        }

        for (int[] obj : inserts) {
            List<String> names = new ArrayList<>();
            int start = obj[0] + 4;
            int end = obj[0] + 2 + obj[1];
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
                    if (!names.contains(sb.toString())) names.add(sb.toString());
                }
            }
            insertNames.put(obj[0], names);
        }

        // Generate report
        System.out.println("╔═══════════════════════════════════════════════════════════════════╗");
        System.out.println("║      DWG FILE BLOCK ANALYSIS - AutoCAD R2000 (AC1015)            ║");
        System.out.println("╚═══════════════════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("File: 210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg");
        System.out.println("Size: " + data.length + " bytes");
        System.out.println();

        System.out.println("━━━ BLOCK DEFINITIONS (BLOCK_HEADER, type=0x30) ━━━");
        System.out.println("Total: " + blocks.size());
        for (int i = 0; i < blocks.size(); i++) {
            int[] bh = blocks.get(i);
            List<String> names = blockNames.get(bh[0]);
            String nameStr;
            if (names == null || names.isEmpty()) {
                nameStr = (i == 0) ? "*Model_Space (implicit, standard)" : "(bit-encoded handle)";
            } else {
                nameStr = String.join(", ", names.subList(0, Math.min(3, names.size())));
            }
            System.out.println(String.format("  Block #%d: @0x%04x, size=%d bytes, name='%s'",
                    i + 1, bh[0], bh[1], nameStr));
        }
        System.out.println();

        System.out.println("━━━ BLOCK INSERTIONS (INSERT, type=0x07) ━━━");
        System.out.println("Total: " + inserts.size());
        Map<String, Integer> countByBlock = new LinkedHashMap<>();
        for (int i = 0; i < inserts.size(); i++) {
            int[] ins = inserts.get(i);
            List<String> names = insertNames.get(ins[0]);
            String blockRef;
            if (names == null || names.isEmpty()) {
                blockRef = "(bit-encoded block handle)";
            } else {
                blockRef = names.get(0);
            }
            countByBlock.merge(blockRef, 1, Integer::sum);
            System.out.println(String.format("  Insert #%d: @0x%04x, size=%d bytes, block='%s'",
                    i + 1, ins[0], ins[1], blockRef));
        }
        System.out.println();

        System.out.println("━━━ INSERTIONS BY BLOCK REFERENCE ━━━");
        List<Map.Entry<String, Integer>> sortedByCount = new ArrayList<>(countByBlock.entrySet());
        sortedByCount.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        for (Map.Entry<String, Integer> e : sortedByCount) {
            String bar = new String(new char[Math.min(50, e.getValue() * 4)]).replace('\0', '#');
            System.out.println(String.format("  '%-35s' : %3d inserts  %s",
                    e.getKey(), e.getValue(), bar));
        }
        System.out.println();

        System.out.println("━━━ FULL ENTITY TYPE DISTRIBUTION ━━━");
        for (Map.Entry<String, Integer> e : typeDist.entrySet()) {
            String bar = new String(new char[Math.min(50, e.getValue() * 2)]).replace('\0', '#');
            System.out.println(String.format("  %-20s : %4d  %s", e.getKey(), e.getValue(), bar));
        }
        System.out.println();

        System.out.println("╔═══════════════════════════════════════════════════════════════════╗");
        System.out.println("║                         END OF REPORT                             ║");
        System.out.println("╚═══════════════════════════════════════════════════════════════════╝");
    }
}
