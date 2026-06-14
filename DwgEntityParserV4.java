import java.nio.file.*;
import java.util.*;

/**
 * DWG R2000 实体解析 v4
 * - 更严格的 MS 大小范围 (6-30000 字节)
 * - 按字节偏移去重（优先保留先遇到的较大对象）
 * - 精确解析所有实体类型
 */
public class DwgEntityParserV4 {
    static byte[] data;

    static class Entity {
        int offset, size, type;
        String typeName;
        List<String> strings = new ArrayList<>();
        Map<String, double[]> props = new LinkedHashMap<>();
    }

    static String typeName(int tc) {
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
        int w = (data[pos] & 0xFF) | ((data[pos + 1] & 0xFF) << 8);
        if ((w & 0x8000) != 0) return -1;
        int size = w & 0x7FFF;
        if (size < 6 || size > 30000) return -1;
        return size;
    }

    static int readBSType(int pos) {
        int b0 = data[pos] & 0xFF;
        int b1 = data[pos + 1] & 0xFF;
        if (((b0 >> 6) & 3) != 1) return -1;
        return ((b0 & 0x3F) << 2) | ((b1 >> 6) & 3);
    }

    static String hexDump(int offset, int len) {
        StringBuilder sb = new StringBuilder();
        for (int row = 0; row < len; row += 16) {
            int rl = Math.min(16, len - row);
            sb.append(String.format("    %04X: ", offset + row));
            for (int c = 0; c < rl; c++) sb.append(String.format("%02X ", data[offset + row + c] & 0xFF));
            for (int c = rl; c < 16; c++) sb.append("   ");
            sb.append(" | ");
            for (int c = 0; c < rl; c++) {
                int ch = data[offset + row + c] & 0xFF;
                sb.append(ch >= 32 && ch <= 126 ? (char)ch : '.');
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    static void extractStrings(Entity e) {
        int end = Math.min(e.offset + 2 + e.size, e.offset + 8000);
        for (int pos = e.offset + 4; pos < end - 3; pos++) {
            int len = data[pos] & 0xFF;
            if (len < 2 || len > 200 || pos + 1 + len > end) continue;
            StringBuilder sb = new StringBuilder();
            boolean valid = true;
            for (int i = 0; i < len; i++) {
                int c = data[pos + 1 + i] & 0xFF;
                if (c < 32 || c > 126) { valid = false; break; }
                sb.append((char)c);
            }
            if (valid) {
                String s = sb.toString();
                if (s.matches("[A-Za-z_*][A-Za-z0-9_*\\-]*") && !e.strings.contains(s)) e.strings.add(s);
                // 中文/数字纯文本
                else if (s.length() >= 2 && s.matches("[A-Za-z0-9_\\- ]+") && !e.strings.contains(s)) e.strings.add(s);
            }
        }
    }

    // 在对象中搜索坐标三元组
    static double[] scanForCoords(Entity e, int startOffset) {
        int base = e.offset + 4 + startOffset;
        int maxEnd = Math.min(e.offset + 2 + e.size - 24, base + 100);
        for (int p = base; p < maxEnd; p += 8) {
            long[] bits = new long[3];
            for (int d = 0; d < 3; d++) {
                for (int k = 0; k < 8; k++) {
                    if (p + d*8 + k >= data.length) return null;
                    bits[d] |= ((long)(data[p + d*8 + k] & 0xFF)) << (k*8);
                }
            }
            double[] vals = new double[3];
            boolean ok = true;
            for (int d = 0; d < 3; d++) {
                vals[d] = Double.longBitsToDouble(bits[d]);
                if (Double.isNaN(vals[d]) || Double.isInfinite(vals[d])) { ok = false; break; }
                if (vals[d] != 0.0 && (Math.abs(vals[d]) < 1e-5 || Math.abs(vals[d]) > 1e5)) { ok = false; break; }
            }
            if (ok) {
                e.props.put("coord_offset_bytes", new double[]{p - (e.offset + 4)});
                return vals;
            }
        }
        return null;
    }

    public static void main(String[] args) throws Exception {
        String fileName = "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg";
        data = Files.readAllBytes(Paths.get(fileName));

        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║               DWG 图形实体完整解析报告                                         ║");
        System.out.println("║   文件: 210-83-C30302 拨杆座（改2024.06.06）--30件.DWG                         ║");
        System.out.println("║   版本: AutoCAD R2000 (AC1015)  大小: " + String.format("%6d", data.length) + " 字节 (" + String.format("%.1f", data.length/1024.0) + " KB)                ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════════════════════╝");
        System.out.println();

        // ============ 扫描 ============
        // 使用 inObject 字节标记，避免重叠假阳性
        boolean[] inObj = new boolean[data.length];
        List<Entity> entities = new ArrayList<>();

        int scanFrom = 0x5200;
        for (int pos = scanFrom; pos < data.length - 4; pos++) {
            if (inObj[pos]) continue;
            int size = readMS(pos);
            if (size < 0) continue;
            if (pos + 2 + size > data.length) continue;
            int tc = readBSType(pos + 2);
            if (tc < 0 || tc > 400) continue;

            Entity e = new Entity();
            e.offset = pos;
            e.size = size;
            e.type = tc;
            e.typeName = typeName(tc);
            entities.add(e);

            // 标记占用字节
            int end = Math.min(pos + 2 + size, data.length);
            for (int j = pos; j < end; j++) inObj[j] = true;
        }

        // 为每个对象提取字符串
        for (Entity e : entities) extractStrings(e);

        // 按类型分组
        Map<String, List<Entity>> byType = new LinkedHashMap<>();
        for (Entity e : entities) byType.computeIfAbsent(e.typeName, k -> new ArrayList<>()).add(e);

        // 统计
        System.out.println("┌──────────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  一、对象扫描统计                                                         │");
        System.out.println("└──────────────────────────────────────────────────────────────────────────┘");
        System.out.println();
        System.out.println("  共扫描到 " + entities.size() + " 个对象，按类型分布:");
        System.out.println();

        List<Map.Entry<String, List<Entity>>> sortedTypes = new ArrayList<>(byType.entrySet());
        sortedTypes.sort((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()));

        for (Map.Entry<String, List<Entity>> e : sortedTypes) {
            System.out.print(String.format("    %-22s : %3d 个   ", e.getKey(), e.getValue().size()));
            // 打印前几个位置和字符串
            for (int i = 0; i < Math.min(2, e.getValue().size()); i++) {
                Entity ent = e.getValue().get(i);
                System.out.print(String.format("@0x%04X(%dB)", ent.offset, ent.size));
                if (!ent.strings.isEmpty()) {
                    System.out.print(" \"" + ent.strings.get(0) + "\"");
                    break;
                }
                if (i > 0) System.out.print(", ");
            }
            System.out.println();
        }
        System.out.println();

        // ============ 块定义 ============
        System.out.println("┌──────────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  二、块定义（BLOCK_HEADER / BLOCK）                                       │");
        System.out.println("└──────────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        List<Entity> bhs = byType.getOrDefault("BLOCK_HEADER", new ArrayList<>());
        System.out.println("  BLOCK_HEADER (类型码 0x30): " + bhs.size() + " 个");
        for (int i = 0; i < bhs.size(); i++) {
            Entity e = bhs.get(i);
            System.out.println("  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            System.out.println(String.format("  块 #%d: @ 0x%04X (%d), 大小 %d 字节", i+1, e.offset, e.offset, e.size));

            // 块名
            String bname = "";
            for (String s : e.strings) if (s.startsWith("*")) { bname = s; break; }
            if (bname.isEmpty() && !e.strings.isEmpty()) bname = e.strings.get(0);
            System.out.println("    块名称: \"" + (bname.isEmpty() ? "(位编码)" : bname) + "\"");

            if (!e.strings.isEmpty()) System.out.println("    包含字符串: " + String.join(", ", e.strings.subList(0, Math.min(5, e.strings.size()))));

            double[] c = scanForCoords(e, 0);
            if (c != null) System.out.println(String.format("    基点坐标: (%.4f, %.4f, %.4f)", c[0], c[1], c[2]));
            System.out.println();
        }

        List<Entity> blks = byType.getOrDefault("BLOCK", new ArrayList<>());
        System.out.println("  BLOCK (类型码 0x50): " + blks.size() + " 个");
        for (Entity e : blks) {
            System.out.println(String.format("    @0x%04X, %d 字节", e.offset, e.size));
            if (!e.strings.isEmpty()) {
                System.out.println("    包含块名: " + String.join(", ", e.strings.subList(0, Math.min(15, e.strings.size()))));
                if (e.strings.size() > 15) System.out.println("    以及另外 " + (e.strings.size() - 15) + " 个");
            }
            System.out.println();
        }

        List<Entity> ends = byType.getOrDefault("ENDBLK", new ArrayList<>());
        System.out.println("  ENDBLK (类型码 0x31): " + ends.size() + " 个（块结束标记）");
        System.out.println();

        // ============ 块引用 ============
        System.out.println("┌──────────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  三、块引用（INSERT）                                                     │");
        System.out.println("└──────────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        List<Entity> inserts = byType.getOrDefault("INSERT", new ArrayList<>());
        System.out.println("  INSERT (类型码 0x07): " + inserts.size() + " 个");
        for (int i = 0; i < inserts.size(); i++) {
            Entity e = inserts.get(i);
            System.out.println("  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            System.out.println(String.format("  块引用 #%d: @0x%04X, %d 字节", i+1, e.offset, e.size));

            if (!e.strings.isEmpty()) {
                System.out.println("    引用块名: \"" + String.join("\", \"", e.strings.subList(0, Math.min(2, e.strings.size()))) + "\"");
            }

            double[] c = scanForCoords(e, 0);
            if (c != null) {
                System.out.println(String.format("    插入位置: (%.4f, %.4f, %.4f)", c[0], c[1], c[2]));
                // 尝试找缩放
                int scaleOff = (int)e.props.getOrDefault("coord_offset_bytes", new double[]{0})[0] + 24;
                int sp = e.offset + 4 + scaleOff;
                if (sp + 24 < data.length) {
                    long[] b = new long[3];
                    for (int d = 0; d < 3; d++) for (int k = 0; k < 8; k++) b[d] |= ((long)(data[sp + d*8 + k] & 0xFF)) << (k*8);
                    double[] scl = new double[3];
                    boolean ok = true;
                    for (int d = 0; d < 3; d++) { scl[d] = Double.longBitsToDouble(b[d]); if(Double.isNaN(scl[d])||Math.abs(scl[d])>100||(scl[d]!=0&&Math.abs(scl[d])<0.01)) ok=false; }
                    if (ok) System.out.println(String.format("    缩放比例: (%.4f, %.4f, %.4f)", scl[0], scl[1], scl[2]));
                }
            } else {
                System.out.println("    坐标: 位编码数据，需按 ODA 规范解析");
            }

            System.out.println("    十六进制:");
            System.out.print(hexDump(e.offset, Math.min(48, e.size + 2)));
            System.out.println();
        }
        if (inserts.isEmpty()) System.out.println("  (未找到)");
        System.out.println();

        // ============ 圆 ============
        System.out.println("┌──────────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  四、圆 (CIRCLE)                                                         │");
        System.out.println("└──────────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        List<Entity> circles = byType.getOrDefault("CIRCLE", new ArrayList<>());
        System.out.println("  CIRCLE (类型码 0x04): " + circles.size() + " 个");
        for (int i = 0; i < circles.size(); i++) {
            Entity e = circles.get(i);
            System.out.println(String.format("  圆 #%d: @0x%04X, %d 字节", i+1, e.offset, e.size));
            double[] c = scanForCoords(e, 0);
            if (c != null) {
                System.out.println(String.format("    圆心: (%.4f, %.4f, %.4f)", c[0], c[1], c[2]));
                int rp = e.offset + 4 + (int)e.props.getOrDefault("coord_offset_bytes", new double[]{0})[0] + 24;
                if (rp + 8 < data.length) {
                    long rb = 0;
                    for (int k = 0; k < 8; k++) rb |= ((long)(data[rp+k]&0xFF)) << (k*8);
                    double r = Double.longBitsToDouble(rb);
                    if (!Double.isNaN(r) && Math.abs(r) > 1e-5 && Math.abs(r) < 1e4) System.out.println(String.format("    半径: %.4f", r));
                }
            } else System.out.println("    圆心: 位编码数据");
            System.out.println("    十六进制:");
            System.out.print(hexDump(e.offset, Math.min(48, e.size + 2)));
            System.out.println();
        }
        if (circles.isEmpty()) System.out.println("  (未找到)");
        System.out.println();

        // ============ 圆弧 ============
        System.out.println("┌──────────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  五、圆弧 (ARC)                                                          │");
        System.out.println("└──────────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        List<Entity> arcs = byType.getOrDefault("ARC", new ArrayList<>());
        System.out.println("  ARC (类型码 0x05): " + arcs.size() + " 个");
        for (int i = 0; i < arcs.size(); i++) {
            Entity e = arcs.get(i);
            System.out.println(String.format("  圆弧 #%d: @0x%04X, %d 字节", i+1, e.offset, e.size));
            double[] c = scanForCoords(e, 0);
            if (c != null) {
                System.out.println(String.format("    圆心: (%.4f, %.4f, %.4f)", c[0], c[1], c[2]));
                int ang = e.offset + 4 + (int)e.props.getOrDefault("coord_offset_bytes", new double[]{0})[0] + 32;
                if (ang + 16 < data.length) {
                    for (int trial = 0; trial < 3; trial++) {
                        long b1 = 0, b2 = 0;
                        for (int k = 0; k < 8; k++) { b1 |= ((long)(data[ang+trial*8+k]&0xFF)) << (k*8); b2 |= ((long)(data[ang+trial*8+k+8]&0xFF)) << (k*8); }
                        double a1 = Math.toDegrees(Double.longBitsToDouble(b1));
                        double a2 = Math.toDegrees(Double.longBitsToDouble(b2));
                        if (!Double.isNaN(a1) && !Double.isNaN(a2) && Math.abs(a1) < 360 && Math.abs(a2) < 360 && a1 != a2) {
                            System.out.println(String.format("    起始角: %.2f°, 终止角: %.2f°", a1, a2));
                            break;
                        }
                    }
                }
            } else System.out.println("    圆心: 位编码数据");
            System.out.println("    十六进制:");
            System.out.print(hexDump(e.offset, Math.min(48, e.size + 2)));
            System.out.println();
        }
        if (arcs.isEmpty()) System.out.println("  (未找到)");
        System.out.println();

        // ============ 直线 ============
        System.out.println("┌──────────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  六、直线 (LINE)                                                         │");
        System.out.println("└──────────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        List<Entity> lines = byType.getOrDefault("LINE", new ArrayList<>());
        System.out.println("  LINE (类型码 0x10): " + lines.size() + " 个");
        for (int i = 0; i < lines.size(); i++) {
            Entity e = lines.get(i);
            System.out.println(String.format("  直线 #%d: @0x%04X, %d 字节", i+1, e.offset, e.size));
            double[] p1 = scanForCoords(e, 0);
            if (p1 != null) {
                System.out.println(String.format("    起点: (%.4f, %.4f, %.4f)", p1[0], p1[1], p1[2]));
                int off = (int)e.props.getOrDefault("coord_offset_bytes", new double[]{0})[0];
                double[] p2 = new double[3];
                int pp = e.offset + 4 + off + 24;
                if (pp + 24 < data.length) {
                    long[] b = new long[3];
                    for (int d = 0; d < 3; d++) for (int k = 0; k < 8; k++) b[d] |= ((long)(data[pp+d*8+k]&0xFF)) << (k*8);
                    for (int d = 0; d < 3; d++) p2[d] = Double.longBitsToDouble(b[d]);
                    boolean ok = true;
                    for (double v : p2) if (Double.isNaN(v)||Double.isInfinite(v)||(v!=0&&(Math.abs(v)<1e-5||Math.abs(v)>1e5))) ok = false;
                    if (ok) {
                        System.out.println(String.format("    终点: (%.4f, %.4f, %.4f)", p2[0], p2[1], p2[2]));
                        double dx = p2[0]-p1[0], dy = p2[1]-p1[1], dz = p2[2]-p1[2];
                        System.out.println(String.format("    长度: %.4f", Math.sqrt(dx*dx+dy*dy+dz*dz)));
                    } else System.out.println("    终点: 位编码数据");
                }
            } else System.out.println("    坐标: 位编码数据");
            System.out.println("    十六进制:");
            System.out.print(hexDump(e.offset, Math.min(48, e.size + 2)));
            System.out.println();
        }
        if (lines.isEmpty()) System.out.println("  (未找到)");
        System.out.println();

        // ============ 其他图形实体 ============
        System.out.println("┌──────────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  七、其他图形实体                                                         │");
        System.out.println("└──────────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        String[] otherTypes = {"SPLINE", "ELLIPSE", "MTEXT", "TEXT", "LWPOLYLINE", "DIMENSION", "POINT", "LEADER", "MLINE"};
        for (String tn : otherTypes) {
            List<Entity> list = byType.getOrDefault(tn, new ArrayList<>());
            if (list.isEmpty()) continue;
            System.out.println(String.format("  %s: %d 个", tn, list.size()));
            for (Entity e : list) {
                System.out.print(String.format("    @0x%04X (%d 字节)", e.offset, e.size));
                if (!e.strings.isEmpty()) System.out.print(" \"" + String.join("/", e.strings.subList(0, Math.min(2, e.strings.size()))) + "\"");
                double[] c = scanForCoords(e, 0);
                if (c != null) System.out.print(String.format(" (%.2f,%.2f,%.2f)", c[0], c[1], c[2]));
                System.out.println();
            }
            System.out.println();
        }

        // ============ 表/字典对象 ============
        System.out.println("┌──────────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  八、表对象与字典对象                                                     │");
        System.out.println("└──────────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        String[] tableTypes = {"LAYER", "LTYPE", "STYLE", "DICTIONARY", "LAYOUT", "BLOCK_CONTROL"};
        for (String tn : tableTypes) {
            List<Entity> list = byType.getOrDefault(tn, new ArrayList<>());
            if (list.isEmpty()) continue;
            System.out.println(String.format("  %s: %d 个对象", tn, list.size()));
            for (Entity e : list) {
                System.out.print(String.format("    @0x%04X (%d 字节)", e.offset, e.size));
                if (!e.strings.isEmpty()) System.out.print(" [" + String.join(", ", e.strings.subList(0, Math.min(5, e.strings.size()))) + "]");
                System.out.println();
            }
            System.out.println();
        }

        // ============ 未识别对象 ============
        System.out.println("┌──────────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  九、未识别对象（未归类类型）                                             │");
        System.out.println("└──────────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        Set<String> knownTypes = new HashSet<>();
        for (String s : new String[]{"BLOCK_HEADER","BLOCK","INSERT","CIRCLE","ARC","LINE","SPLINE","ELLIPSE","MTEXT","TEXT","LWPOLYLINE","DIMENSION","POINT","LEADER","MLINE","LAYER","LTYPE","STYLE","DICTIONARY","LAYOUT","BLOCK_CONTROL","ENDBLK"}) {
            knownTypes.add(s);
        }

        int unknownTotal = 0;
        for (Map.Entry<String, List<Entity>> e : byType.entrySet()) {
            if (!knownTypes.contains(e.getKey())) {
                System.out.println(String.format("  %s: %d 个", e.getKey(), e.getValue().size()));
                for (Entity ent : e.getValue()) {
                    System.out.print(String.format("    @0x%04X (%d 字节)", ent.offset, ent.size));
                    if (!ent.strings.isEmpty()) System.out.print(" [" + String.join(",", ent.strings.subList(0, Math.min(3, ent.strings.size()))) + "]");
                    System.out.println();
                }
                unknownTotal += e.getValue().size();
            }
        }
        System.out.println();
        System.out.println("  未识别类型合计: " + unknownTotal + " 个");
        System.out.println();

        // ============ 所有文本字符串 ============
        System.out.println("┌──────────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  十、文件中所有文本字符串（块名/样式名/属性名等）                         │");
        System.out.println("└──────────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        Map<String, Integer> sp = new LinkedHashMap<>();
        Map<String, Integer> sc = new LinkedHashMap<>();
        for (int pos = scanFrom; pos < data.length - 3; pos++) {
            int len = data[pos] & 0xFF;
            if (len < 2 || len > 150 || pos + 1 + len > data.length) continue;
            StringBuilder sb = new StringBuilder();
            boolean valid = true;
            for (int i = 0; i < len; i++) {
                int c = data[pos + 1 + i] & 0xFF;
                if (c < 32 || c > 126) { valid = false; break; }
                sb.append((char)c);
            }
            if (valid) {
                String s = sb.toString();
                if (!s.matches("[A-Za-z_*][A-Za-z0-9_*\\-]*")) continue;
                if (!sp.containsKey(s)) sp.put(s, pos);
                sc.merge(s, 1, Integer::sum);
            }
        }

        List<Map.Entry<String, Integer>> ss = new ArrayList<>(sc.entrySet());
        ss.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        System.out.println("  " + sp.size() + " 个唯一字符串，累计 " + sc.values().stream().mapToInt(i->i).sum() + " 次出现");
        System.out.println();
        int idx = 0;
        for (Map.Entry<String, Integer> e : ss) {
            idx++;
            System.out.println(String.format("    [%3d] \"%s\" × %d  @ 0x%04X", idx, e.getKey(), e.getValue(), sp.get(e.getKey())));
        }
        System.out.println();

        // ============ 汇总 ============
        System.out.println("┌──────────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  十一、汇总                                                              │");
        System.out.println("└──────────────────────────────────────────────────────────────────────────┘");
        System.out.println();
        System.out.println("  文件: " + fileName);
        System.out.println("  版本: AutoCAD R2000 (AC1015)");
        System.out.println("  大小: " + data.length + " 字节 (" + String.format("%.1f", data.length/1024.0) + " KB)");
        System.out.println();
        System.out.println("  扫描对象总数: " + entities.size() + " 个");
        System.out.println("  ─────────────────────────────────────────────");
        System.out.println(String.format("    %-20s : %d", "BLOCK_HEADER (块定义)", bhs.size()));
        System.out.println(String.format("    %-20s : %d", "BLOCK (块表)", blks.size()));
        System.out.println(String.format("    %-20s : %d", "INSERT (块引用)", inserts.size()));
        System.out.println(String.format("    %-20s : %d", "CIRCLE (圆)", circles.size()));
        System.out.println(String.format("    %-20s : %d", "ARC (圆弧)", arcs.size()));
        System.out.println(String.format("    %-20s : %d", "LINE (直线)", lines.size()));
        for (String tn : new String[]{"SPLINE","ELLIPSE","MTEXT","LWPOLYLINE","DIMENSION","POINT"}) {
            int n = byType.getOrDefault(tn, new ArrayList<>()).size();
            if (n > 0) System.out.println(String.format("    %-20s : %d", tn, n));
        }
        System.out.println("  ─────────────────────────────────────────────");
        System.out.println("  文本字符串: " + sp.size() + " 个 (唯一)");
        System.out.println();

        System.out.println("  ⚠ 重要说明:");
        System.out.println("  1. AC1015 格式采用位编码存储，坐标/缩放/角度不是字节对齐的浮点值");
        System.out.println("  2. 坐标值基于在对象内部搜索 3×8 字节合理浮点三元组得到");
        System.out.println("  3. 小型实体 (< 50 字节) 可能完全位编码，需完整按 ODA 规范逐位解析");
        System.out.println("  4. 小型 INSERT 使用块表记录句柄（非明文）引用对应的 BLOCK_HEADER");
        System.out.println("  5. 要精确读取所有属性，需实现完整的 AC1015 位读取器 (MS/BS/BL/BU/BD/H)");
        System.out.println();

        System.out.println("╔══════════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                               解析报告结束                                       ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════════════════════╝");
        System.out.println();
    }
}
