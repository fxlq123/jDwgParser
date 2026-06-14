import java.nio.file.*;
import java.util.*;

public class FinalReport {
    static byte[] data;

    static class Entity {
        int offset, size, type;
        String typeName;
        List<String> strings = new ArrayList<>();
    }

    static String tn(int tc) {
        switch(tc) {
            case 0x01: return "TEXT";
            case 0x04: return "CIRCLE";
            case 0x05: return "ARC";
            case 0x07: return "INSERT";
            case 0x08: return "ATTDEF";
            case 0x09: return "ATTRIB";
            case 0x0A: return "POINT";
            case 0x0B: return "POLYLINE_2D";
            case 0x0C: return "VERTEX_2D";
            case 0x0D: return "SEQEND";
            case 0x0E: return "ELLIPSE";
            case 0x0F: return "LWPOLYLINE";
            case 0x10: return "LINE";
            case 0x15: return "DIMENSION";
            case 0x1E: return "MLINE";
            case 0x1F: return "MTEXT";
            case 0x20: return "LEADER";
            case 0x2C: return "SPLINE";
            case 0x30: return "BLOCK_HEADER";
            case 0x31: return "ENDBLK";
            case 0x43: return "LAYER";
            case 0x47: return "LTYPE";
            case 0x48: return "STYLE";
            case 0x4F: return "BLOCK_CONTROL";
            case 0x50: return "BLOCK";
            case 0x51: return "DICTIONARY";
            case 0x59: return "LAYOUT";
            default: return "TYPE_0x" + String.format("%02X", tc);
        }
    }

    static int readMS(int pos) {
        int w = (data[pos]&0xFF) | ((data[pos+1]&0xFF)<<8);
        return ((w & 0x8000) == 0 && w >= 6) ? (w & 0x7FFF) : -1;
    }
    static int readBSType(int pos) {
        int b0 = data[pos]&0xFF, b1 = data[pos+1]&0xFF;
        if (((b0>>6)&3) != 1) return -1;
        return ((b0 & 0x3F)<<2) | ((b1>>6)&3);
    }

    static String hexDump(int offset, int len) {
        StringBuilder sb = new StringBuilder();
        for (int row = 0; row < len; row += 16) {
            int rl = Math.min(16, len-row);
            sb.append(String.format("    %04X: ", offset+row));
            for (int c = 0; c < rl; c++) sb.append(String.format("%02X ", data[offset+row+c]&0xFF));
            for (int c = rl; c < 16; c++) sb.append("   ");
            sb.append(" | ");
            for (int c = 0; c < rl; c++) { int ch = data[offset+row+c]&0xFF; sb.append(ch>=32&&ch<=126?(char)ch:'.'); }
            sb.append("\n");
        }
        return sb.toString();
    }

    // 严格扫描：指定类型，可选大小范围，可选是否去重
    static List<Entity> strictScan(int start, int end, Set<Integer> validTypes, int minSize, int maxSize, boolean dedup) {
        List<Entity> result = new ArrayList<>();
        boolean[] covered = new boolean[data.length];

        for (int pos = start; pos < end - 4; pos++) {
            if (dedup && covered[pos]) continue;
            int size = readMS(pos);
            if (size < 0) continue;
            if (size < minSize || size > maxSize) continue;
            if (pos + 2 + size > data.length) continue;
            int tc = readBSType(pos + 2);
            if (tc < 0) continue;
            if (!validTypes.contains(tc)) continue;

            Entity e = new Entity();
            e.offset = pos;
            e.size = size;
            e.type = tc;
            e.typeName = tn(tc);
            int strEnd = Math.min(pos + 2 + size, pos + 500);
            for (int sp = pos + 4; sp < strEnd - 3; sp++) {
                int len = data[sp] & 0xFF;
                if (len < 2 || len > 200 || sp + 1 + len > strEnd) continue;
                StringBuilder sb = new StringBuilder(); boolean ok = true;
                for (int i = 0; i < len; i++) { int c = data[sp+1+i]&0xFF; if(c<32||c>126){ok=false;break;} sb.append((char)c); }
                if (ok) {
                    String s = sb.toString();
                    if (s.matches("[A-Za-z_*][A-Za-z0-9_*\\-]*") && !e.strings.contains(s)) e.strings.add(s);
                }
            }
            result.add(e);
            if (dedup) for (int j = pos; j < pos + 2 + size && j < data.length; j++) covered[j] = true;
        }
        return result;
    }

    public static void main(String[] args) throws Exception {
        String fileName = "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg";
        data = Files.readAllBytes(Paths.get(fileName));

        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                 DWG 图形实体 完整解析报告                                       ║");
        System.out.println("║   文件: 210-83-C30302 拨杆座（改2024.06.06）--30件.DWG                         ║");
        System.out.println("║   版本: AutoCAD R2000 (AC1015)  大小: " + String.format("%6d", data.length) + " 字节 (" + String.format("%.1f", data.length/1024.0) + " KB)               ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════════════════════╝");
        System.out.println();

        // ============ 1. 顶层对象（去重） ============
        System.out.println("┌──────────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  一、顶层对象扫描（去重后）                                                 │");
        System.out.println("└──────────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        Set<Integer> allTypes = new HashSet<>();
        for (int i = 0; i < 256; i++) allTypes.add(i);
        List<Entity> topLevel = strictScan(0x5200, data.length, allTypes, 6, 30000, true);

        Map<String, List<Entity>> byType = new LinkedHashMap<>();
        for (Entity e : topLevel) byType.computeIfAbsent(e.typeName, k -> new ArrayList<>()).add(e);

        System.out.println("  共 " + topLevel.size() + " 个顶层对象, 按类型分布:");
        System.out.println();

        List<Map.Entry<String, List<Entity>>> sorted = new ArrayList<>(byType.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()));
        for (Map.Entry<String, List<Entity>> e : sorted) {
            List<Entity> lst = e.getValue();
            System.out.print(String.format("    %-22s: %3d 个  ", e.getKey(), lst.size()));
            int shown = 0;
            for (Entity ent : lst) {
                System.out.print(String.format("@0x%04X(%dB)", ent.offset, ent.size));
                if (!ent.strings.isEmpty()) System.out.print("[" + ent.strings.get(0) + "]");
                if (++shown >= 3) { System.out.print(" ..."); break; }
                System.out.print(" ");
            }
            System.out.println();
        }
        System.out.println();

        // ============ 2. 找 BLOCK 对象范围 ============
        int blockStart = -1, blockEnd = -1;
        for (Entity e : topLevel) if (e.type == 0x50) { blockStart = e.offset; blockEnd = e.offset + 2 + e.size; break; }
        if (blockStart > 0) {
            System.out.println(String.format("  主要 BLOCK 对象: @0x%04X (@%d) 到 @0x%04X (@%d), 含 %d 字节",
                blockStart, blockStart, blockEnd, blockEnd, blockEnd - blockStart));
        }
        System.out.println();

        // ============ 3. BLOCK 范围内扫描 INSERT ============
        System.out.println("┌──────────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  二、块引用 (INSERT)                                                      │");
        System.out.println("└──────────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        Set<Integer> insertSet = new HashSet<>(Collections.singletonList(0x07));
        List<Entity> inserts = strictScan(0x5200, data.length, insertSet, 30, 60, false);
        // 去重（按偏移相同的）
        Map<Integer, Entity> insertMap = new LinkedHashMap<>();
        for (Entity e : inserts) if (!insertMap.containsKey(e.offset)) insertMap.put(e.offset, e);
        List<Entity> finalInserts = new ArrayList<>(insertMap.values());

        System.out.println("  共找到 " + finalInserts.size() + " 个 INSERT (块引用)");
        System.out.println();
        for (int i = 0; i < finalInserts.size(); i++) {
            Entity e = finalInserts.get(i);
            System.out.println(String.format("  INSERT #%d: @0x%04X (@%d), 大小 %d 字节", i+1, e.offset, e.offset, e.size));
            System.out.println("    数据:");
            System.out.print(hexDump(e.offset, Math.min(48, e.size + 2)));
            if (!e.strings.isEmpty()) System.out.println("    含字符串: " + String.join(", ", e.strings));
            System.out.println();
        }

        // ============ 4. CIRCLE 圆 ============
        System.out.println("┌──────────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  三、圆 (CIRCLE)                                                          │");
        System.out.println("└──────────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        Set<Integer> circleSet = new HashSet<>(Collections.singletonList(0x04));
        List<Entity> circles = strictScan(0x5200, data.length, circleSet, 15, 80, false);
        Map<Integer, Entity> circleMap = new LinkedHashMap<>();
        for (Entity e : circles) if (!circleMap.containsKey(e.offset)) circleMap.put(e.offset, e);
        List<Entity> finalCircles = new ArrayList<>(circleMap.values());

        System.out.println("  共找到 " + finalCircles.size() + " 个 CIRCLE (圆)");
        System.out.println();
        for (int i = 0; i < finalCircles.size(); i++) {
            Entity e = finalCircles.get(i);
            System.out.println(String.format("  圆 #%d: @0x%04X (@%d), 大小 %d 字节", i+1, e.offset, e.offset, e.size));
            System.out.println("    数据:");
            System.out.print(hexDump(e.offset, Math.min(48, e.size + 2)));
            System.out.println();
        }

        // ============ 5. ARC 圆弧 ============
        System.out.println("┌──────────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  四、圆弧 (ARC)                                                           │");
        System.out.println("└──────────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        Set<Integer> arcSet = new HashSet<>(Collections.singletonList(0x05));
        List<Entity> arcs = strictScan(0x5200, data.length, arcSet, 12, 80, false);
        Map<Integer, Entity> arcMap = new LinkedHashMap<>();
        for (Entity e : arcs) if (!arcMap.containsKey(e.offset)) arcMap.put(e.offset, e);
        List<Entity> finalArcs = new ArrayList<>(arcMap.values());

        System.out.println("  共找到 " + finalArcs.size() + " 个 ARC (圆弧)");
        System.out.println();
        for (int i = 0; i < finalArcs.size(); i++) {
            Entity e = finalArcs.get(i);
            System.out.println(String.format("  弧 #%d: @0x%04X (@%d), 大小 %d 字节", i+1, e.offset, e.offset, e.size));
            System.out.println("    数据:");
            System.out.print(hexDump(e.offset, Math.min(48, e.size + 2)));
            System.out.println();
        }

        // ============ 6. LINE 直线 ============
        System.out.println("┌──────────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  五、直线 (LINE)                                                          │");
        System.out.println("└──────────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        Set<Integer> lineSet = new HashSet<>(Collections.singletonList(0x10));
        List<Entity> lines = strictScan(0x5200, data.length, lineSet, 15, 80, false);
        Map<Integer, Entity> lineMap = new LinkedHashMap<>();
        for (Entity e : lines) if (!lineMap.containsKey(e.offset)) lineMap.put(e.offset, e);
        List<Entity> finalLines = new ArrayList<>(lineMap.values());

        System.out.println("  共找到 " + finalLines.size() + " 个 LINE (直线)");
        System.out.println();
        for (int i = 0; i < Math.min(20, finalLines.size()); i++) {
            Entity e = finalLines.get(i);
            System.out.println(String.format("  直线 #%d: @0x%04X (@%d), 大小 %d 字节", i+1, e.offset, e.offset, e.size));
            System.out.print(hexDump(e.offset, Math.min(32, e.size + 2)));
        }
        if (finalLines.size() > 20) System.out.println(String.format("  ... 其余 %d 个省略", finalLines.size() - 20));
        System.out.println();

        // ============ 7. BLOCK_HEADER 块定义 ============
        System.out.println("┌──────────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  六、块定义 (BLOCK_HEADER)                                                │");
        System.out.println("└──────────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        Set<Integer> bhSet = new HashSet<>(Collections.singletonList(0x30));
        List<Entity> bhs = strictScan(0x5200, data.length, bhSet, 20, 500, false);
        Map<Integer, Entity> bhMap = new LinkedHashMap<>();
        for (Entity e : bhs) if (!bhMap.containsKey(e.offset)) bhMap.put(e.offset, e);
        List<Entity> finalBHs = new ArrayList<>(bhMap.values());

        System.out.println("  共找到 " + finalBHs.size() + " 个 BLOCK_HEADER (块表头)");
        System.out.println();
        for (int i = 0; i < finalBHs.size(); i++) {
            Entity e = finalBHs.get(i);
            System.out.println(String.format("  块 #%d: @0x%04X (@%d), 大小 %d 字节  字符串: %s",
                i+1, e.offset, e.offset, e.size,
                e.strings.isEmpty() ? "(位编码)" : String.join(", ", e.strings.subList(0, Math.min(3, e.strings.size())))));
            System.out.println("    前 80 字节数据:");
            System.out.print(hexDump(e.offset, Math.min(80, e.size + 2)));
            System.out.println();
        }

        // ============ 8. 其他图形实体 ============
        System.out.println("┌──────────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  七、其他图形实体（顶层扫描结果）                                          │");
        System.out.println("└──────────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        String[][] otherTypes = {
            {"0x2C", "SPLINE (样条曲线)"},
            {"0x0E", "ELLIPSE (椭圆)"},
            {"0x1F", "MTEXT (多行文字)"},
            {"0x0F", "LWPOLYLINE (轻量多段线)"},
            {"0x15", "DIMENSION (尺寸标注)"},
            {"0x01", "TEXT (单行文字)"},
            {"0x08", "ATTDEF (属性定义)"},
        };
        for (String[] t : otherTypes) {
            int tc = Integer.parseInt(t[0].substring(2), 16);
            Set<Integer> s = new HashSet<>(Collections.singletonList(tc));
            List<Entity> lst = strictScan(0x5200, data.length, s, 8, 30000, true);
            if (!lst.isEmpty()) {
                System.out.println(String.format("  %s (%s): %d 个", t[1], t[0], lst.size()));
                for (Entity e : lst) {
                    System.out.print(String.format("    @0x%04X (%d B)", e.offset, e.size));
                    if (!e.strings.isEmpty()) System.out.print(" [\"" + e.strings.get(0) + "\"]");
                    System.out.println();
                }
                System.out.println();
            }
        }

        // ============ 9. 表对象 ============
        System.out.println("┌──────────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  八、表对象与控制对象                                                      │");
        System.out.println("└──────────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        String[][] tableTypes = {
            {"0x31", "ENDBLK (块结束标记)"},
            {"0x43", "LAYER (图层)"},
            {"0x47", "LTYPE (线型)"},
            {"0x48", "STYLE (文字样式)"},
            {"0x51", "DICTIONARY (字典)"},
            {"0x59", "LAYOUT (布局)"},
            {"0x4F", "BLOCK_CONTROL (块控制)"},
        };
        for (String[] t : tableTypes) {
            int tc = Integer.parseInt(t[0].substring(2), 16);
            Set<Integer> s = new HashSet<>(Collections.singletonList(tc));
            List<Entity> lst = strictScan(0x5200, data.length, s, 6, 30000, true);
            if (!lst.isEmpty()) {
                System.out.println(String.format("  %s: %d 个", t[1], lst.size()));
                for (Entity e : lst) {
                    System.out.print(String.format("    @0x%04X (%d B)", e.offset, e.size));
                    if (!e.strings.isEmpty()) System.out.print(" [\"" + e.strings.get(0) + "\"]");
                    System.out.println();
                }
                System.out.println();
            }
        }

        // ============ 10. 所有文本字符串 ============
        System.out.println("┌──────────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  九、文件中所有文本字符串（块名/样式/图层等名称）                             │");
        System.out.println("└──────────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        Map<String, Integer> strPos = new LinkedHashMap<>();
        Map<String, Integer> strCnt = new LinkedHashMap<>();
        for (int pos = 0x5200; pos < data.length - 3; pos++) {
            int len = data[pos] & 0xFF;
            if (len < 2 || len > 200 || pos + 1 + len > data.length) continue;
            StringBuilder sb = new StringBuilder(); boolean valid = true;
            for (int i = 0; i < len; i++) { int c = data[pos+1+i]&0xFF; if(c<32||c>126){valid=false;break;} sb.append((char)c); }
            if (valid && sb.toString().matches("[A-Za-z_*][A-Za-z0-9_*\\-]*")) {
                String s = sb.toString();
                if (!strPos.containsKey(s)) strPos.put(s, pos);
                strCnt.merge(s, 1, Integer::sum);
            }
        }

        List<Map.Entry<String, Integer>> strSorted = new ArrayList<>(strCnt.entrySet());
        strSorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        System.out.println("  " + strPos.size() + " 个唯一字符串, 累计 " + strCnt.values().stream().mapToInt(i->i).sum() + " 次出现");
        System.out.println();
        int idx = 0;
        for (Map.Entry<String, Integer> e : strSorted) {
            idx++;
            System.out.println(String.format("    [%3d] \"%s\" × %d  @ 0x%04X",
                idx, e.getKey(), e.getValue(), strPos.get(e.getKey())));
        }
        System.out.println();

        // ============ 11. 最终总结 ============
        System.out.println("┌──────────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  十、最终数据汇总                                                         │");
        System.out.println("└──────────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        System.out.println("  ╔══════════════════════════════════════════════════════════╗");
        System.out.println("  ║   文件: 210-83-C30302 拨杆座（改2024.06.06）--30件.DWG      ║");
        System.out.println("  ║   版本: AutoCAD R2000 (AC1015)                           ║");
        System.out.println("  ║   大小: " + String.format("%6d", data.length) + " 字节 (" + String.format("%5.1f", data.length/1024.0) + " KB)                    ║");
        System.out.println("  ╠══════════════════════════════════════════════════════════╣");
        System.out.println("  ║   图形实体数量统计:                                      ║");
        System.out.println(String.format("  ║     INSERT (块引用)    : %4d                           ║", finalInserts.size()));
        System.out.println(String.format("  ║     CIRCLE (圆)        : %4d                           ║", finalCircles.size()));
        System.out.println(String.format("  ║     ARC (圆弧)         : %4d                           ║", finalArcs.size()));
        System.out.println(String.format("  ║     LINE (直线)        : %4d                           ║", finalLines.size()));
        System.out.println("  ║     SPLINE (样条)      : " + String.format("%4d", byType.getOrDefault("SPLINE", new ArrayList<>()).size()) + "                           ║");
        System.out.println("  ║     ELLIPSE (椭圆)     : " + String.format("%4d", byType.getOrDefault("ELLIPSE", new ArrayList<>()).size()) + "                           ║");
        System.out.println("  ║     MTEXT (多行文字)   : " + String.format("%4d", byType.getOrDefault("MTEXT", new ArrayList<>()).size()) + "                           ║");
        System.out.println(String.format("  ║     BLOCK_HEADER (块)  : %4d                           ║", finalBHs.size()));
        System.out.println("  ╚══════════════════════════════════════════════════════════╝");
        System.out.println();

        System.out.println("  ⚠ 说明:");
        System.out.println("  1. 以上数字为扫描对象，可能含少量假阳性（由数据字节误匹配产生）");
        System.out.println("  2. 几何数据（坐标、半径、角度）在 AC1015 中为位编码，非字节对齐的双精度浮点");
        System.out.println("  3. 需按 ODA (OpenDesign Alliance) 规范实现完整的位级解析器才能还原精确坐标");
        System.out.println("  4. INSERT 对象的块名由 handle 关联到 BLOCK_HEADER，而非直接明文存储");
        System.out.println("  5. BLOCK 对象（0x50）包含块表，其中嵌套了所有块的实体定义");
        System.out.println();

        System.out.println("╔══════════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                              解析报告 - 完成                                      ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════════════════════╝");
        System.out.println();
    }
}
