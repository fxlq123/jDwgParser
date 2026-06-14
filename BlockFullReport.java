import java.nio.file.*;
import java.util.*;

public class BlockFullReport {
    static byte[] data;

    static int readMS(int pos) {
        int w = (data[pos] & 0xFF) | ((data[pos + 1] & 0xFF) << 8);
        return (w & 0x8000) != 0 ? -1 : (w & 0x7FFF);
    }

    static int readBS(int pos) {
        int b0 = data[pos] & 0xFF;
        int b1 = data[pos + 1] & 0xFF;
        if (((b0 >> 6) & 3) != 1) return -1;
        return ((b0 & 0x3F) << 2) | ((b1 >> 6) & 3);
    }

    static long readBL(int pos) {
        return ((long)(data[pos] & 0xFF))
            | ((long)(data[pos + 1] & 0xFF) << 8)
            | ((long)(data[pos + 2] & 0xFF) << 16)
            | ((long)(data[pos + 3] & 0xFF) << 24);
    }

    static double readRD(int pos) {
        long bits = 0;
        for (int i = 0; i < 8; i++)
            bits |= ((long)(data[pos + i] & 0xFF)) << (i * 8);
        return Double.longBitsToDouble(bits);
    }

    static int getBit(int bitPos) {
        return (data[bitPos / 8] >> (7 - (bitPos % 8))) & 1;
    }

    static int[] readHCode(int bitPos) {
        int val = 0, bits = 0;
        for (int i = 0; i < 8; i++) {
            int b = getBit(bitPos);
            val = (val << 1) | b;
            bitPos++; bits++;
            if (b == 1) break;
        }
        return new int[]{val, bits};
    }

    static String readStringAt(int pos, int maxLen) {
        if (pos < 0 || pos >= data.length) return "";
        int len = data[pos] & 0xFF;
        if (len < 1 || len > maxLen || pos + 1 + len > data.length) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            int c = data[pos + 1 + i] & 0xFF;
            if (c < 32 || c > 126) return "";
            sb.append((char)c);
        }
        return sb.toString();
    }

    static List<String> findStringsInRange(int start, int end, int minLen, int maxLen) {
        List<String> results = new ArrayList<>();
        for (int i = start; i < end - 2 && results.size() < 50; i++) {
            int len = data[i] & 0xFF;
            if (len < minLen || len > maxLen || i + 1 + len > end) continue;
            StringBuilder sb = new StringBuilder();
            boolean valid = true;
            for (int k = 0; k < len; k++) {
                int c = data[i + 1 + k] & 0xFF;
                if (c < 32 || c > 126) { valid = false; break; }
                sb.append((char)c);
            }
            if (valid && sb.length() >= minLen) {
                String s = sb.toString();
                if (s.matches("[A-Za-z_*][A-Za-z0-9_*\\-]*")) {
                    if (!results.contains(s)) results.add(s);
                }
            }
        }
        return results;
    }

    // Parse BLOCK_HEADER entity structure
    static Map<String, Object> parseBlockHeader(int offset, int size) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("offset", offset);
        info.put("size_bytes", size);
        info.put("type_code", 0x30);

        // Entity common data: bitsize(BL) + entity_handle(H) + EED_size(MS) + owner_handle(H) + numReactors(BL)
        int bitPos = (offset + 4) * 8;  // after MS(2B) + BS(2B) = 4B

        // bitsize (BL = 32-bit)
        long bitsize = readBL(bitPos / 8);
        bitPos += 32;
        info.put("bitsize", bitsize);

        // entity handle (H code)
        int[] eh = readHCode(bitPos);
        bitPos += eh[1];
        info.put("entity_handle", eh[0]);
        info.put("entity_handle_bits", eh[1]);

        // EED size (MS)
        int eedPos = bitPos / 8;
        if (bitPos % 8 != 0) eedPos++;  // align to byte
        int eedSize = readMS(eedPos);
        int eedBits = (eedPos * 8) - bitPos + 16;
        info.put("eed_size", eedSize);

        // owner handle (H code)
        int[] ow = readHCode((eedPos + 2) * 8);
        info.put("owner_handle", ow[0]);

        // Block name
        int dataStartByte = Math.min(offset + size - 1, eedPos + 40);
        String blockName = "";
        List<String> names = findStringsInRange(
            Math.max(offset + 8, (eedPos + 4) * 8 / 8),
            Math.min(offset + 2 + size, dataStartByte + 80),
            2, 60);

        if (!names.isEmpty()) {
            blockName = names.get(0);
        }
        info.put("block_name", blockName);
        info.put("all_names", names);

        return info;
    }

    // Parse INSERT entity structure
    static Map<String, Object> parseInsert(int offset, int size) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("offset", offset);
        info.put("size_bytes", size);
        info.put("type_code", 0x07);

        // Entity common data (similar to above)
        int bitPos = (offset + 4) * 8;

        // bitsize (BL)
        long bitsize = readBL(bitPos / 8);
        bitPos += 32;
        info.put("bitsize", bitsize);

        // entity handle
        int[] eh = readHCode(bitPos);
        bitPos += eh[1];
        info.put("entity_handle", eh[0]);

        // EED size
        int eedPos = (bitPos + 7) / 8;
        int eedSize = readMS(eedPos);
        info.put("eed_size", eedSize);

        // owner handle
        int[] ow = readHCode((eedPos + 2) * 8);
        info.put("owner_handle", ow[0]);

        // INSERT specific: block_table_record_handle(H) + position(3 RD) + scale(3 RD) + rotation(RD) + ...
        int insBit = (eedPos + 4) * 8;
        int[] bh = readHCode(insBit);
        insBit += bh[1];
        info.put("block_table_record_handle", bh[0]);

        // position XYZ (3 doubles)
        int rdStart = (insBit + 7) / 8;
        try {
            double x = readRD(rdStart);
            double y = readRD(rdStart + 8);
            double z = readRD(rdStart + 16);
            info.put("position", new double[]{x, y, z});
        } catch (Exception e) {
            info.put("position", new double[]{0, 0, 0});
        }

        // scale factors
        try {
            double sx = readRD(rdStart + 24);
            double sy = readRD(rdStart + 32);
            double sz = readRD(rdStart + 40);
            info.put("scale", new double[]{sx, sy, sz});
        } catch (Exception e) {
            info.put("scale", new double[]{1, 1, 1});
        }

        // rotation
        try {
            double rot = readRD(rdStart + 48);
            info.put("rotation_rad", rot);
            info.put("rotation_deg", Math.toDegrees(rot));
        } catch (Exception e) {
            info.put("rotation_rad", 0.0);
        }

        // Block name (referenced block)
        List<String> names = findStringsInRange(
            offset + 8, Math.min(offset + 2 + size, offset + 80),
            2, 60);
        if (!names.isEmpty()) info.put("block_ref_name", names.get(0));
        info.put("all_ref_names", names);

        return info;
    }

    public static void main(String[] args) throws Exception {
        data = Files.readAllBytes(Paths.get("210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));

        System.out.println();
        System.out.println("╔═════════════════════════════════════════════════════════════════════╗");
        System.out.println("║     DWG 文件 块 (BLOCK) 详细信息报告                                ║");
        System.out.println("║     文件: 210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg        ║");
        System.out.println("║     格式: AutoCAD R2000 (AC1015)                                    ║");
        System.out.println("║     大小: " + String.format("%-54d", data.length) + " ║");
        System.out.println("╚═════════════════════════════════════════════════════════════════════╝");
        System.out.println();

        // ============ Step 1: 扫描所有 BLOCK_HEADER 对象 ============
        System.out.println("┌──────────────────────────────────────────────────────────────────┐");
        System.out.println("│  第一部分：BLOCK 定义 (BLOCK_HEADER 对象, 类型码=0x30)           │");
        System.out.println("└──────────────────────────────────────────────────────────────────┘");

        List<Map<String, Object>> blocks = new ArrayList<>();
        for (int pos = 0x5200; pos < data.length - 8; pos++) {
            int size = readMS(pos);
            if (size < 8 || size > 30000 || pos + 2 + size > data.length) continue;
            int tc = readBS(pos + 2);
            if (tc == 0x30) {
                blocks.add(parseBlockHeader(pos, size));
                pos += size;
            }
        }

        // 查找全局已知的块名位置
        Map<String, Integer> blockNameOffsets = new LinkedHashMap<>();
        // 扫描整个文件中类似块名的字符串
        for (int pos = 0x5200; pos < data.length - 80; pos++) {
            int len = data[pos] & 0xFF;
            if (len < 2 || len > 80 || pos + 1 + len > data.length) continue;
            StringBuilder sb = new StringBuilder();
            boolean valid = true;
            for (int k = 0; k < len; k++) {
                int c = data[pos + 1 + k] & 0xFF;
                if (c < 32 || c > 126) { valid = false; break; }
                sb.append((char)c);
            }
            if (valid && sb.length() >= 2) {
                String s = sb.toString();
                if (s.matches("[A-Za-z_*][A-Za-z0-9_*\\-]*") &&
                    !s.equals("ACAD") && !s.startsWith("ISO") &&
                    !s.equals("Standard") && !s.equals("Continuous") &&
                    !s.startsWith("SLDTEXT") && !s.startsWith("SLDDIM")) {
                    if (!blockNameOffsets.containsKey(s)) blockNameOffsets.put(s, pos + 1);
                }
            }
        }

        // 打印 BLOCK_HEADER
        System.out.println("  找到 " + blocks.size() + " 个 BLOCK_HEADER 对象\n");
        for (int i = 0; i < blocks.size(); i++) {
            Map<String, Object> b = blocks.get(i);
            System.out.println("  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            System.out.println("  【块定义 #" + (i + 1) + "】");
            System.out.println("    偏移位置        : 0x" + String.format("%04X", (Integer)b.get("offset")) +
                " (" + b.get("offset") + " 字节)");
            System.out.println("    对象大小        : " + b.get("size_bytes") + " 字节");
            System.out.println("    实体句柄        : 0x" + Integer.toHexString((Integer)b.get("entity_handle")));
            System.out.println("    所有者句柄      : 0x" + Integer.toHexString((Integer)b.get("owner_handle")));
            System.out.println("    位流大小        : " + b.get("bitsize") + " bits");
            System.out.println("    EED 数据大小    : " + b.get("eed_size") + " 字节");
            String bn = (String)b.get("block_name");
            if (!bn.isEmpty()) {
                System.out.println("    块名称 (检测)   : \"" + bn + "\"");
            } else {
                // 尝试从已知块名中找出最接近的
                int myOff = (Integer)b.get("offset");
                String best = "";
                int bestDist = Integer.MAX_VALUE;
                for (Map.Entry<String, Integer> e : blockNameOffsets.entrySet()) {
                    int d = Math.abs(e.getValue() - myOff);
                    if (d < bestDist && d < 1000) { bestDist = d; best = e.getKey(); }
                }
                System.out.println("    块名称 (近似)   : \"" + best + "\" (距离 " + bestDist + " 字节)");
            }
            @SuppressWarnings("unchecked")
            List<String> allNames = (List<String>)b.get("all_names");
            if (allNames != null && !allNames.isEmpty()) {
                System.out.println("    内部字符串      : " + String.join(", ", allNames.subList(0, Math.min(5, allNames.size()))));
            }
            System.out.println();
        }

        // ============ Step 2: 扫描所有 INSERT 对象 ============
        System.out.println("┌──────────────────────────────────────────────────────────────────┐");
        System.out.println("│  第二部分：BLOCK 引用 (INSERT 对象, 类型码=0x07)                 │");
        System.out.println("└──────────────────────────────────────────────────────────────────┘");

        List<Map<String, Object>> inserts = new ArrayList<>();
        for (int pos = 0x5200; pos < data.length - 8; pos++) {
            int size = readMS(pos);
            if (size < 8 || size > 30000 || pos + 2 + size > data.length) continue;
            int tc = readBS(pos + 2);
            if (tc == 0x07) {
                inserts.add(parseInsert(pos, size));
                pos += size;
            }
        }

        System.out.println("  找到 " + inserts.size() + " 个 INSERT 对象\n");
        for (int i = 0; i < inserts.size(); i++) {
            Map<String, Object> ins = inserts.get(i);
            System.out.println("  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            System.out.println("  【块引用 #" + (i + 1) + "】");
            System.out.println("    偏移位置        : 0x" + String.format("%04X", (Integer)ins.get("offset")) +
                " (" + ins.get("offset") + " 字节)");
            System.out.println("    对象大小        : " + ins.get("size_bytes") + " 字节");
            System.out.println("    实体句柄        : 0x" + Integer.toHexString((Integer)ins.get("entity_handle")));
            System.out.println("    块表记录句柄    : 0x" + Integer.toHexString((Integer)ins.get("block_table_record_handle")));
            double[] pos_xyz = (double[])ins.get("position");
            System.out.println(String.format("    插入位置 (X,Y,Z): (%.4f, %.4f, %.4f)", pos_xyz[0], pos_xyz[1], pos_xyz[2]));
            double[] scale = (double[])ins.get("scale");
            System.out.println(String.format("    缩放系数 (X,Y,Z): (%.4f, %.4f, %.4f)", scale[0], scale[1], scale[2]));
            Double rot = (Double)ins.get("rotation_deg");
            System.out.println(String.format("    旋转角度        : %.4f 度", rot));

            String refName = (String)ins.get("block_ref_name");
            if (refName != null && !refName.isEmpty()) {
                System.out.println("    引用块名称      : \"" + refName + "\"");
            } else {
                // 从已知块名中找最接近的
                int myOff = (Integer)ins.get("offset");
                String best = "";
                int bestDist = Integer.MAX_VALUE;
                for (Map.Entry<String, Integer> e : blockNameOffsets.entrySet()) {
                    int d = Math.abs(e.getValue() - myOff);
                    if (d < bestDist && d < 800) { bestDist = d; best = e.getKey(); }
                }
                System.out.println("    引用块名称 (近) : \"" + best + "\" (距离 " + bestDist + " 字节)");
            }
            @SuppressWarnings("unchecked")
            List<String> refs = (List<String>)ins.get("all_ref_names");
            if (refs != null && !refs.isEmpty()) {
                System.out.println("    内部字符串      : " + String.join(", ", refs.subList(0, Math.min(3, refs.size()))));
            }
            System.out.println();
        }

        // ============ Step 3: 块引用统计汇总 ============
        System.out.println("┌──────────────────────────────────────────────────────────────────┐");
        System.out.println("│  第三部分：块引用次数统计汇总                                    │");
        System.out.println("└──────────────────────────────────────────────────────────────────┘");
        Map<String, Integer> refCount = new TreeMap<>();
        for (Map<String, Object> ins : inserts) {
            String n = (String)ins.get("block_ref_name");
            if (n == null || n.isEmpty()) {
                // 用块表记录句柄作为替代标识
                n = "Handle_0x" + Integer.toHexString((Integer)ins.get("block_table_record_handle"));
            }
            refCount.merge(n, 1, Integer::sum);
        }

        // 按次数排序
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(refCount.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

        System.out.println("\n  各块被引用次数（共 " + inserts.size() + " 次引用）:\n");
        int maxBar = 0;
        for (int v : refCount.values()) maxBar = Math.max(maxBar, v);
        for (Map.Entry<String, Integer> e : sorted) {
            int barLen = (int)((long)e.getValue() * 40 / Math.max(maxBar, 1));
            String bar = new String(new char[Math.max(1, barLen)]).replace('\0', '█');
            int pct = e.getValue() * 100 / Math.max(inserts.size(), 1);
            System.out.println(String.format("    %-25s : %3d 次 (%2d%%)  %s",
                "\"" + e.getKey() + "\"", e.getValue(), pct, bar));
        }
        System.out.println();

        // ============ Step 4: 文件中已知块名位置 ============
        System.out.println("┌──────────────────────────────────────────────────────────────────┐");
        System.out.println("│  第四部分：文件中检测到的块名称位置                              │");
        System.out.println("└──────────────────────────────────────────────────────────────────┘");

        System.out.println("\n  共检测到 " + blockNameOffsets.size() + " 个潜在块名:\n");
        int idx = 0;
        for (Map.Entry<String, Integer> e : blockNameOffsets.entrySet()) {
            idx++;
            System.out.println(String.format("    [%3d] %-25s @ offset 0x%04X (%d)",
                idx, "\"" + e.getKey() + "\"", e.getValue(), e.getValue()));
        }
        System.out.println();

        System.out.println("╔═════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                          报告生成完毕                                ║");
        System.out.println("╚═════════════════════════════════════════════════════════════════════╝");
    }
}
