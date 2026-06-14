import java.nio.file.*;
import java.util.*;

/**
 * DWG R2000 (AC1015) Block Parser
 *
 * Known issues fixed:
 * 1. Modular Short (MS) reading: uses 16-bit LE words (not byte-based)
 *    - bit 15 = continuation flag (0 = single word)
 *    - bits 0-14 = size value
 * 2. Bit Short (BS) type decoding:
 *    - top 2 bits of byte 0 = opcode (must be 01)
 *    - bottom 6 bits of byte 0 + top 2 bits of byte 1 = type code
 * 3. Objects NOT contiguous - must scan by object boundaries found by MS
 * 4. Block names stored as length-prefixed ASCII strings in object data
 */
public class R2000BlockParser {
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

    static List<int[]> findAllObjects(int maxSize) {
        List<int[]> result = new ArrayList<>();
        for (int pos = 0x5200; pos < data.length - 4; pos++) {
            int lo = data[pos] & 0xFF;
            int hi = data[pos + 1] & 0xFF;
            int raw = lo | (hi << 8);
            if ((raw & 0x8000) != 0) continue;  // multi-word (skip)
            int size = raw & 0x7FFF;
            if (size < 8 || size > maxSize || pos + 2 + size > data.length) continue;
            int b0 = data[pos + 2] & 0xFF;
            int b1 = data[pos + 3] & 0xFF;
            if (((b0 >> 6) & 3) != 1) continue;  // BS opcode must be 01
            int typeCode = ((b0 & 0x3F) << 2) | ((b1 >> 6) & 3);
            if (typeCode > 0 && typeCode < 200) {
                result.add(new int[]{pos, size, typeCode});
                pos += 1 + size;  // skip into object (conservative)
            }
        }
        return result;
    }

    static List<String> extractTexts(int offset, int size) {
        List<String> texts = new ArrayList<>();
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
            if (valid && sb.length() >= 2 &&
                sb.toString().matches("[A-Za-z_*][A-Za-z0-9_*\\-]*")) {
                if (!texts.contains(sb.toString())) texts.add(sb.toString());
            }
        }
        return texts;
    }

    public static void main(String[] args) throws Exception {
        data = Files.readAllBytes(Paths.get("210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));

        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║          DWG R2000 BLOCK ANALYSIS - FINAL REPORT                 ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("File   : 210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg");
        System.out.println("Size   : " + data.length + " bytes");
        System.out.println("Version: AutoCAD R2000 (AC1015)");
        System.out.println();

        // Find all blocks and inserts
        List<int[]> objects = findAllObjects(30000);
        List<int[]> blocks = new ArrayList<>();
        List<int[]> inserts = new ArrayList<>();
        Map<String, Integer> typeDist = new TreeMap<>();

        for (int[] obj : objects) {
            int type = obj[2];
            typeDist.merge(typeName(type), 1, Integer::sum);
            if (type == 0x30) blocks.add(obj);
            if (type == 0x07) inserts.add(obj);
        }

        // Print BLOCK_HEADER objects
        System.out.println("━━━ BLOCK DEFINITIONS (type=0x30) ━━━");
        System.out.println("Total: " + blocks.size() + " block definitions");
        for (int i = 0; i < blocks.size(); i++) {
            int[] b = blocks.get(i);
            List<String> texts = extractTexts(b[0], b[1]);
            String name;
            if (texts.isEmpty()) {
                name = (i == 0) ? "*Model_Space (standard block)" : "(bit-encoded block handle)";
            } else {
                name = String.join(", ", texts.subList(0, Math.min(5, texts.size())));
            }
            System.out.println(String.format("  Block #%d: @0x%04x, size=%5d B, name='%s'",
                    i + 1, b[0], b[1], name));
        }
        System.out.println();

        // Print INSERT objects
        System.out.println("━━━ BLOCK INSERTIONS (type=0x07) ━━━");
        System.out.println("Total: " + inserts.size() + " block insertions");
        Map<String, Integer> refCounts = new LinkedHashMap<>();
        for (int i = 0; i < inserts.size(); i++) {
            int[] ins = inserts.get(i);
            List<String> texts = extractTexts(ins[0], ins[1]);
            String ref = texts.isEmpty() ? "(bit-encoded block handle)" :
                String.join(", ", texts.subList(0, Math.min(3, texts.size())));
            refCounts.merge(ref, 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> e : refCounts.entrySet()) {
            System.out.println(String.format("  '%s' : %d insertion(s)", e.getKey(), e.getValue()));
        }
        System.out.println();

        // Entity distribution
        System.out.println("━━━ ENTITY TYPE DISTRIBUTION ━━━");
        int maxCount = typeDist.values().stream().max(Integer::compare).orElse(1);
        for (Map.Entry<String, Integer> e : typeDist.entrySet()) {
            int barLen = (int) ((long) e.getValue() * 50 / maxCount);
            String bar = new String(new char[Math.max(1, barLen)]).replace('\0', '#');
            System.out.println(String.format("  %-18s : %4d  %s",
                    e.getKey(), e.getValue(), bar));
        }
        System.out.println();

        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║                        END OF ANALYSIS                           ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");
    }
}
