import java.nio.file.*;
import java.util.*;

/**
 * DWG 图形实体完整解析 - 使用 jDwgParser 原始类型码
 * 关键修正：LINE=0x13, ARC=0x11, CIRCLE=0x12, SPLINE=0x24, ELLIPSE=0x23, MTEXT=0x2C
 */
public class CorrectTypeCodeParser {
    static byte[] data;

    static class Entity {
        int offset, size, type;
        String typeName;
        List<String> strings = new ArrayList<>();
    }

    // jDwgParser 原始类型码
    static String tn(int tc) {
        switch(tc) {
            case 0x01: return "TEXT";
            case 0x02: return "ATTDEF";
            case 0x03: return "ATTRIB";
            case 0x04: return "SEQEND";
            case 0x05: return "ENDBLK";
            case 0x07: return "INSERT";
            case 0x08: return "MINSERT";
            case 0x0A: return "VERTEX_2D";
            case 0x0B: return "VERTEX_3D";
            case 0x0C: return "VERTEX_MESH";
            case 0x0D: return "VERTEX_PFACE";
            case 0x0E: return "VERTEX_PFACE_FACE";
            case 0x0F: return "POLYLINE_2D";
            case 0x10: return "POLYLINE_3D";
            case 0x11: return "ARC";
            case 0x12: return "CIRCLE";
            case 0x13: return "LINE";
            case 0x14: return "DIMENSION_ORDINATE";
            case 0x15: return "DIMENSION_LINEAR";
            case 0x16: return "DIMENSION_ALIGNED";
            case 0x17: return "DIMENSION_ANG_3PT";
            case 0x18: return "DIMENSION_ANG_2LN";
            case 0x19: return "DIMENSION_RADIUS";
            case 0x1A: return "DIMENSION_DIAMETER";
            case 0x1B: return "POINT";
            case 0x1C: return "FACE3D";
            case 0x1D: return "POLYLINE_PFACE";
            case 0x1E: return "POLYLINE_MESH";
            case 0x1F: return "SOLID";
            case 0x20: return "TRACE";
            case 0x21: return "SHAPE";
            case 0x22: return "VIEWPORT";
            case 0x23: return "ELLIPSE";
            case 0x24: return "SPLINE";
            case 0x25: return "REGION";
            case 0x26: return "SOLID3D";
            case 0x27: return "BODY";
            case 0x28: return "RAY";
            case 0x29: return "XLINE";
            case 0x2A: return "DICTIONARY";
            case 0x2C: return "MTEXT";
            case 0x2D: return "LEADER";
            case 0x2E: return "TOLERANCE";
            case 0x2F: return "MLINE";
            case 0x30: return "BLOCK_HEADER";
            case 0x31: return "BLOCK_END";
            case 0x32: return "LTYPE";
            case 0x33: return "LAYER";
            case 0x34: return "STYLE";
            case 0x35: return "STYLE_ALTERNATE";
            case 0x36: return "VIEW";
            case 0x37: return "UCS";
            case 0x38: return "VPORT";
            case 0x39: return "APPID";
            case 0x3A: return "DIMSTYLE";
            case 0x3B: return "VP_ENT_HDR";
            case 0x3C: return "GROUP";
            case 0x3D: return "MLINESTYLE";
            case 0x3E: return "OLE2FRAME";
            case 0x4B: return "LWPLINE";
            case 0x4C: return "HATCH";
            case 0x4D: return "XRECORD";
            case 0x4E: return "PLACEHOLDER";
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
        System.out.println("║         DWG 图形实体 完整解析报告 (正确类型码)                               ║");
        System.out.println("║   文件: 210-83-C30302 拨杆座（改2024.06.06）--30件.DWG                         ║");
        System.out.println("║   版本: AutoCAD R2000 (AC1015)  大小: " + String.format("%6d", data.length) + " 字节 (" + String.format("%.1f", data.length/1024.0) + " KB)               ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════════════════════╝");
        System.out.println();

        // ============ 类型码映射表 ============
        System.out.println("┌──────────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  jDwgParser 实体类型码映射表 (jDwgParser 原项目定义)                        │");
        System.out.println("└──────────────────────────────────────────────────────────────────────────┘");
        System.out.println();
        String[][] typeMap = {
            {"LINE (直线)", "0x13"},
            {"ARC (圆弧)", "0x11"},
            {"CIRCLE (圆)", "0x12"},
            {"INSERT (块引用)", "0x07"},
            {"BLOCK_HEADER (块表头)", "0x30"},
            {"BLOCK_END (块结束)", "0x31"},
            {"SPLINE (样条曲线)", "0x24"},
            {"ELLIPSE (椭圆)", "0x23"},
            {"MTEXT (多行文字)", "0x2C"},
            {"LWPOLYLINE (轻量多段线)", "0x4B"},
            {"TEXT (单行文字)", "0x01"},
            {"DIMENSION_LINEAR (线性标注)", "0x15"},
            {"POLYLINE_2D (多段线)", "0x0F"},
            {"SOLID (填充)", "0x1F"},
            {"TRACE (轨迹)", "0x20"},
            {"POINT (点)", "0x1B"},
            {"SHAPE (形)", "0x21"},
            {"ATTRIB (属性)", "0x03"},
            {"ATTDEF (属性定义)", "0x02"},
        };
        System.out.println("  ╔═════════════════════════════════════════════════════════╗");
        System.out.println("  ║  实体类型名称                     类型码              ║");
        System.out.println("  ╠═════════════════════════════════════════════════════════╣");
        for (String[] m : typeMap) {
            System.out.println(String.format("  ║  %-35s    %-10s      ║", m[0], m[1]));
        }
        System.out.println("  ╚═════════════════════════════════════════════════════════╝");
        System.out.println();

        // ============ 1. 顶层对象扫描（去重） ============
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

        // ============ 2. 直线 (LINE 0x13) ============
        System.out.println("┌──────────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  二、直线 (LINE) - 类型码 0x13                                            │");
        System.out.println("└──────────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        Set<Integer> lineSet = new HashSet<>(Collections.singletonList(0x13));
        List<Entity> lines = strictScan(0x5200, data.length, lineSet, 15, 200, false);
        Map<Integer, Entity> lineMap = new LinkedHashMap<>();
        for (Entity e : lines) if (!lineMap.containsKey(e.offset)) lineMap.put(e.offset, e);
        List<Entity> finalLines = new ArrayList<>(lineMap.values());

        System.out.println("  共找到 " + finalLines.size() + " 个 LINE (直线)");
        System.out.println();
        for (int i = 0; i < Math.min(30, finalLines.size()); i++) {
            Entity e = finalLines.get(i);
            System.out.println(String.format("  直线 #%d: @0x%04X (@%d), 大小 %d 字节", i+1, e.offset, e.offset, e.size));
            System.out.print(hexDump(e.offset, Math.min(48, e.size + 2)));
        }
        if (finalLines.size() > 30) System.out.println(String.format("  ... 其余 %d 个省略", finalLines.size() - 30));
        System.out.println();

        // ============ 3. 圆 (CIRCLE 0x12) ============
        System.out.println("┌──────────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  三、圆 (CIRCLE) - 类型码 0x12                                            │");
        System.out.println("└──────────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        Set<Integer> circleSet = new HashSet<>(Collections.singletonList(0x12));
        List<Entity> circles = strictScan(0x5200, data.length, circleSet, 15, 100, false);
        Map<Integer, Entity> circleMap = new LinkedHashMap<>();
        for (Entity e : circles) if (!circleMap.containsKey(e.offset)) circleMap.put(e.offset, e);
        List<Entity> finalCircles = new ArrayList<>(circleMap.values());

        System.out.println("  共找到 " + finalCircles.size() + " 个 CIRCLE (圆)");
        System.out.println();
        for (int i = 0; i < Math.min(20, finalCircles.size()); i++) {
            Entity e = finalCircles.get(i);
            System.out.println(String.format("  圆 #%d: @0x%04X (@%d), 大小 %d 字节", i+1, e.offset, e.offset, e.size));
            System.out.print(hexDump(e.offset, Math.min(48, e.size + 2)));
        }
        if (finalCircles.size() > 20) System.out.println(String.format("  ... 其余 %d 个省略", finalCircles.size() - 20));
        System.out.println();

        // ============ 4. 圆弧 (ARC 0x11) ============
        System.out.println("┌──────────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  四、圆弧 (ARC) - 类型码 0x11                                             │");
        System.out.println("└──────────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        Set<Integer> arcSet = new HashSet<>(Collections.singletonList(0x11));
        List<Entity> arcs = strictScan(0x5200, data.length, arcSet, 12, 100, false);
        Map<Integer, Entity> arcMap = new LinkedHashMap<>();
        for (Entity e : arcs) if (!arcMap.containsKey(e.offset)) arcMap.put(e.offset, e);
        List<Entity> finalArcs = new ArrayList<>(arcMap.values());

        System.out.println("  共找到 " + finalArcs.size() + " 个 ARC (圆弧)");
        System.out.println();
        for (int i = 0; i < Math.min(20, finalArcs.size()); i++) {
            Entity e = finalArcs.get(i);
            System.out.println(String.format("  弧 #%d: @0x%04X (@%d), 大小 %d 字节", i+1, e.offset, e.offset, e.size));
            System.out.print(hexDump(e.offset, Math.min(48, e.size + 2)));
        }
        if (finalArcs.size() > 20) System.out.println(String.format("  ... 其余 %d 个省略", finalArcs.size() - 20));
        System.out.println();

        // ============ 5. 块引用 (INSERT 0x07) ============
        System.out.println("┌──────────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  五、块引用 (INSERT) - 类型码 0x07                                        │");
        System.out.println("└──────────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        Set<Integer> insertSet = new HashSet<>(Collections.singletonList(0x07));
        List<Entity> inserts = strictScan(0x5200, data.length, insertSet, 30, 200, false);
        Map<Integer, Entity> insertMap = new LinkedHashMap<>();
        for (Entity e : inserts) if (!insertMap.containsKey(e.offset)) insertMap.put(e.offset, e);
        List<Entity> finalInserts = new ArrayList<>(insertMap.values());

        System.out.println("  共找到 " + finalInserts.size() + " 个 INSERT (块引用)");
        System.out.println();
        for (int i = 0; i < Math.min(15, finalInserts.size()); i++) {
            Entity e = finalInserts.get(i);
            System.out.println(String.format("  INSERT #%d: @0x%04X (@%d), 大小 %d 字节", i+1, e.offset, e.offset, e.size));
            System.out.print(hexDump(e.offset, Math.min(48, e.size + 2)));
        }
        if (finalInserts.size() > 15) System.out.println(String.format("  ... 其余 %d 个省略", finalInserts.size() - 15));
        System.out.println();

        // ============ 6. 块定义 (BLOCK_HEADER 0x30) ============
        System.out.println("┌──────────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  六、块定义 (BLOCK_HEADER) - 类型码 0x30                                   │");
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

        // ============ 7. 样条曲线 (SPLINE 0x24) ============
        System.out.println("┌──────────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  七、样条曲线 (SPLINE) - 类型码 0x24                                        │");
        System.out.println("└──────────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        Set<Integer> splineSet = new HashSet<>(Collections.singletonList(0x24));
        List<Entity> splines = strictScan(0x5200, data.length, splineSet, 20, 2000, true);
        System.out.println("  共找到 " + splines.size() + " 个 SPLINE (样条曲线)");
        for (Entity e : splines) {
            System.out.print(String.format("    @0x%04X (%d B)", e.offset, e.size));
            if (!e.strings.isEmpty()) System.out.print(" [\"" + e.strings.get(0) + "\"]");
            System.out.println();
        }
        System.out.println();

        // ============ 8. 椭圆 (ELLIPSE 0x23) ============
        System.out.println("┌──────────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  八、椭圆 (ELLIPSE) - 类型码 0x23                                          │");
        System.out.println("└──────────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        Set<Integer> ellipseSet = new HashSet<>(Collections.singletonList(0x23));
        List<Entity> ellipses = strictScan(0x5200, data.length, ellipseSet, 20, 20000, true);
        System.out.println("  共找到 " + ellipses.size() + " 个 ELLIPSE (椭圆)");
        for (Entity e : ellipses) {
            System.out.print(String.format("    @0x%04X (%d B)", e.offset, e.size));
            if (!e.strings.isEmpty()) System.out.print(" [\"" + e.strings.get(0) + "\"]");
            System.out.println();
        }
        System.out.println();

        // ============ 9. 多行文字 (MTEXT 0x2C) ============
        System.out.println("┌──────────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  九、多行文字 (MTEXT) - 类型码 0x2C                                         │");
        System.out.println("└──────────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        Set<Integer> mtextSet = new HashSet<>(Collections.singletonList(0x2C));
        List<Entity> mtexts = strictScan(0x5200, data.length, mtextSet, 20, 20000, true);
        System.out.println("  共找到 " + mtexts.size() + " 个 MTEXT (多行文字)");
        for (Entity e : mtexts) {
            System.out.print(String.format("    @0x%04X (%d B)", e.offset, e.size));
            if (!e.strings.isEmpty()) System.out.print(" [\"" + e.strings.get(0) + "\"]");
            System.out.println();
        }
        System.out.println();

        // ============ 10. 汇总统计 ============
        System.out.println("┌──────────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  十、最终数据汇总                                                         │");
        System.out.println("└──────────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        System.out.println("  ╔══════════════════════════════════════════════════════════╗");
        System.out.println("  ║   文件: 210-83-C30302 拨杆座（改2024.06.06）--30件.DWG      ║");
        System.out.println("  ║   版本: AutoCAD R2000 (AC1015)                           ║");
        System.out.println("  ║   大小: " + String.format("%6d", data.length) + " 字节 (" + String.format("%5.1f", data.length/1024.0) + " KB)                    ║");
        System.out.println("  ╠══════════════════════════════════════════════════════════╣");
        System.out.println("  ║   图形实体数量统计 (使用正确类型码):                     ║");
        System.out.println(String.format("  ║     LINE (直线) 0x13      : %4d                           ║", finalLines.size()));
        System.out.println(String.format("  ║     CIRCLE (圆) 0x12      : %4d                           ║", finalCircles.size()));
        System.out.println(String.format("  ║     ARC (圆弧) 0x11       : %4d                           ║", finalArcs.size()));
        System.out.println(String.format("  ║     INSERT (块引用) 0x07  : %4d                           ║", finalInserts.size()));
        System.out.println(String.format("  ║     BLOCK_HEADER 0x30    : %4d                           ║", finalBHs.size()));
        System.out.println(String.format("  ║     SPLINE 0x24          : %4d                           ║", splines.size()));
        System.out.println(String.format("  ║     ELLIPSE 0x23         : %4d                           ║", ellipses.size()));
        System.out.println(String.format("  ║     MTEXT 0x2C           : %4d                           ║", mtexts.size()));
        System.out.println("  ╚══════════════════════════════════════════════════════════╝");
        System.out.println();

        System.out.println("  ⚠ 说明:");
        System.out.println("  1. 之前报告使用错误的类型码导致 LINE/ARC/CIRCLE 等未正确识别");
        System.out.println("  2. jDwgParser 原项目定义: LINE=0x13, ARC=0x11, CIRCLE=0x12, SPLINE=0x24, ELLIPSE=0x23");
        System.out.println("  3. 几何数据（坐标、半径、角度）在 AC1015 中为位编码，需位级解析");
        System.out.println("  4. 以上数字为扫描对象，可能含少量假阳性");
        System.out.println();

        System.out.println("╔══════════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                              解析报告 - 完成                                      ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════════════════════╝");
        System.out.println();
    }
}
