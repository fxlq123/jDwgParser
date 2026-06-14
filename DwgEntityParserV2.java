import java.nio.file.*;
import java.util.*;

/**
 * DWG R2000 (AC1015) 实体详细解析 v2
 * - 暴力扫描每一字节位置，不漏对象
 * - 检测 MS 多字编码 (bit 15 = 1 表示继续)
 * - 小型 INSERT / 其他实体：提取可见文本字符串
 * - 大型对象：在内部寻找子结构（可能包含多实体）
 */
public class DwgEntityParserV2 {

    static byte[] data;
    static StringBuilder report = new StringBuilder();

    static class Entity {
        int offset, size, type;
        String typeName;
        List<String> strings;
        Map<String, Object> props;
        Entity() { strings = new ArrayList<>(); props = new LinkedHashMap<>(); }
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
            case 0x22: return "XLINE_RAY";
            case 0x23: return "HATCH";
            case 0x2C: return "SPLINE";
            case 0x2D: return "REGION";
            case 0x2E: return "3DSOLID";
            case 0x30: return "BLOCK_HEADER";
            case 0x31: return "ENDBLK";
            case 0x32: return "LAYER_INDEX";
            case 0x33: return "IDBUFFER";
            case 0x34: return "SPATIAL_INDEX";
            case 0x36: return "POLYLINE_3D";
            case 0x37: return "VERTEX_3D";
            case 0x38: return "POLYLINE_PFACE";
            case 0x39: return "VERTEX_PFACE";
            case 0x3B: return "POLYLINE_MESH";
            case 0x3C: return "VERTEX_MESH";
            case 0x3E: return "FACE3D";
            case 0x3F: return "HELIX";
            case 0x40: return "LIGHT";
            case 0x41: return "CAMERA";
            case 0x42: return "TABLE_CONTROL";
            case 0x43: return "LAYER";
            case 0x44: return "LAYER_CONTROL";
            case 0x47: return "LTYPE";
            case 0x48: return "STYLE";
            case 0x49: return "VIEW";
            case 0x4A: return "UCS";
            case 0x4B: return "VPORT";
            case 0x4C: return "VIEW_CONTROL";
            case 0x4D: return "UCS_CONTROL";
            case 0x4E: return "VPORT_CONTROL";
            case 0x4F: return "BLOCK_CONTROL";
            case 0x50: return "BLOCK";
            case 0x51: return "DICTIONARY";
            case 0x52: return "DICTIONARYVAR";
            case 0x53: return "XRECORD";
            case 0x54: return "PLACEHOLDER";
            case 0x55: return "DATATABLE";
            case 0x56: return "GEODATA";
            case 0x57: return "DICTIONARYWDFLT";
            case 0x58: return "FIELD";
            case 0x59: return "LAYOUT";
            case 0x5A: return "MATERIAL";
            case 0x5B: return "PLOTSETTINGS";
            case 0x60: return "VBA_PROJECT";
            case 0x61: return "OLE2FRAME";
            case 0x62: return "RASTERVARIABLES";
            case 0x63: return "DATABASEPREVIEW";
            case 0x64: return "EXTRECORD";
            case 0x65: return "RTEXT";
            case 0x90: return "ACAD_PROXY_OBJECT";
            case 0xA0: return "ACAD_PROXY_ENTITY";
            default:   return "TYPE_0x" + String.format("%02X", tc);
        }
    }

    // 读取 MS - 支持多字
    static int[] readMS(int pos) {
        int size = 0;
        int bytesRead = 0;
        boolean hasMore;
        int words = 0;
        do {
            if (pos + 1 + bytesRead >= data.length) return null;
            int w = (data[pos + bytesRead] & 0xFF) | ((data[pos + bytesRead + 1] & 0xFF) << 8);
            hasMore = (w & 0x8000) != 0;
            size = (size << 15) | (w & 0x7FFF);
            bytesRead += 2;
            words++;
            if (words > 4) return null;
        } while (hasMore);
        if (size <= 0 || size > 100000) return null;
        return new int[]{size, bytesRead};
    }

    // 读取 BS 类型码
    static int readBSType(int pos) {
        int b0 = data[pos] & 0xFF;
        int b1 = data[pos + 1] & 0xFF;
        if (((b0 >> 6) & 3) != 1) return -1;
        return ((b0 & 0x3F) << 2) | ((b1 >> 6) & 3);
    }

    // 提取对象内字符串
    static void extractStrings(Entity e, int start, int end) {
        for (int pos = start; pos < end - 2; pos++) {
            int len = data[pos] & 0xFF;
            if (len < 1 || len > 100 || pos + 1 + len > end) continue;
            StringBuilder sb = new StringBuilder();
            boolean valid = true;
            for (int i = 0; i < len; i++) {
                int c = data[pos + 1 + i] & 0xFF;
                if (c < 32 || c > 126) { valid = false; break; }
                sb.append((char)c);
            }
            if (valid && sb.length() >= 2 && sb.toString().matches("[A-Za-z_*][A-Za-z0-9_*\\-]*")) {
                if (!e.strings.contains(sb.toString())) e.strings.add(sb.toString());
            }
        }
    }

    // ========== 扫描对象 ==========
    static List<Entity> scanAllObjects() {
        List<Entity> entities = new ArrayList<>();
        boolean[] inObject = new boolean[data.length];

        for (int pos = 0x5200; pos < data.length - 4; pos++) {
            if (inObject[pos]) continue;

            int[] ms = readMS(pos);
            if (ms == null) continue;
            int size = ms[0];
            if (size < 6 || size > 100000 || pos + 2 + size > data.length) continue;

            int tc = readBSType(pos + 2);
            if (tc < 0 || tc > 600) continue;

            Entity e = new Entity();
            e.offset = pos;
            e.size = size;
            e.type = tc;
            e.typeName = typeName(tc);

            // 标记该对象占用的字节
            int markEnd = Math.min(pos + 2 + size, data.length);
            for (int j = pos; j < markEnd && j < data.length; j++) inObject[j] = true;

            // 提取字符串
            extractStrings(e, pos + 4, Math.min(pos + 2 + size, pos + 2000));
            // 大对象也扫描后面的部分
            if (size > 2000) {
                extractStrings(e, pos + 1500, Math.min(pos + 2 + size, pos + 5000));
                extractStrings(e, pos + 4500, Math.min(pos + 2 + size, pos + 10000));
            }

            entities.add(e);
        }
        return entities;
    }

    // ========== 检测有效浮点数 ==========
    static boolean isReasonableDouble(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return false;
        if (v == 0.0) return true;
        double abs = Math.abs(v);
        return abs > 1e-10 && abs < 1e10;
    }

    static String fmt(double v) {
        if (!isReasonableDouble(v)) return "—";
        return String.format("%.4f", v);
    }

    // ========== 从对象内提取坐标（查找可能的坐标位置） ==========
    static void extractCoordsFromObject(Entity e) {
        // 在对象数据里尝试每一字节读取 3×8 字节的三元组 (x, y, z)
        // 仅当三者都为合理值时记录
        int dataStart = e.offset + 4;  // 跳过 MS(2) + BS(2)
        int dataEnd = e.offset + 2 + e.size;

        for (int probe = dataStart; probe < dataEnd - 24; probe++) {
            // 读取 3 doubles: x, y, z
            long lx = 0, ly = 0, lz = 0;
            for (int i = 0; i < 8; i++) {
                lx |= ((long)(data[probe + i] & 0xFF)) << (i * 8);
                ly |= ((long)(data[probe + 8 + i] & 0xFF)) << (i * 8);
                lz |= ((long)(data[probe + 16 + i] & 0xFF)) << (i * 8);
            }
            double x = Double.longBitsToDouble(lx);
            double y = Double.longBitsToDouble(ly);
            double z = Double.longBitsToDouble(lz);
            if (isReasonableDouble(x) && isReasonableDouble(y) && isReasonableDouble(z)) {
                // 再读 3 个，看是否也合理（用于确认是坐标数据区）
                long lx2 = 0, ly2 = 0, lz2 = 0;
                if (probe + 48 < dataEnd) {
                    for (int i = 0; i < 8; i++) {
                        lx2 |= ((long)(data[probe + 24 + i] & 0xFF)) << (i * 8);
                        ly2 |= ((long)(data[probe + 32 + i] & 0xFF)) << (i * 8);
                        lz2 |= ((long)(data[probe + 40 + i] & 0xFF)) << (i * 8);
                    }
                    double x2 = Double.longBitsToDouble(lx2);
                    double y2 = Double.longBitsToDouble(ly2);
                    double z2 = Double.longBitsToDouble(lz2);
                    if (isReasonableDouble(x2) && isReasonableDouble(y2) && isReasonableDouble(z2))
                    {
                        // 坐标数据区起点
                        if (!e.props.containsKey("coord_start")) {
                            e.props.put("coord_start", new double[]{probe - e.offset, x, y, z});
                            e.props.put("coord_next", new double[]{x2, y2, z2});
                        }
                    }
                }
            }
        }
    }

    // ========== 十六进制 Dump ==========
    static String hexDump(int offset, int len) {
        StringBuilder sb = new StringBuilder();
        for (int row = 0; row < len; row += 16) {
            int rowLen = Math.min(16, len - row);
            sb.append(String.format("    %04X: ", offset + row));
            for (int col = 0; col < rowLen; col++) {
                sb.append(String.format("%02X ", data[offset + row + col] & 0xFF));
            }
            for (int col = rowLen; col < 16; col++) sb.append("   ");
            sb.append(" | ");
            for (int col = 0; col < rowLen; col++) {
                int c = data[offset + row + col] & 0xFF;
                sb.append(c >= 32 && c <= 126 ? (char)c : '.');
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    public static void main(String[] args) throws Exception {
        String fileName = "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg";
        data = Files.readAllBytes(Paths.get(fileName));

        System.out.println();
        System.out.println("╔════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║          DWG 图形实体详细解析报告 v2                                       ║");
        System.out.println("║   文件: 210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg                ║");
        System.out.println("║   版本: AutoCAD R2000 (AC1015)   大小: " + data.length + " 字节 (" + String.format("%.2f", data.length/1024.0) + " KB)");
        System.out.println("╚════════════════════════════════════════════════════════════════════════════╝");
        System.out.println();

        List<Entity> entities = scanAllObjects();

        // 按类型分组
        Map<String, List<Entity>> byType = new LinkedHashMap<>();
        for (Entity e : entities) {
            byType.computeIfAbsent(e.typeName, k -> new ArrayList<>()).add(e);
        }

        // ============ 统计 ============
        System.out.println("┌──────────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  一、对象扫描统计                                                          │");
        System.out.println("└──────────────────────────────────────────────────────────────────────────┘");
        System.out.println();
        System.out.println("  共扫描到 " + entities.size() + " 个对象，类型分布:");
        System.out.println();
        for (Map.Entry<String, List<Entity>> e : byType.entrySet()) {
            System.out.print(String.format("    %-22s : %3d 个  @ ", e.getKey(), e.getValue().size()));
            for (int i = 0; i < Math.min(4, e.getValue().size()); i++) {
                if (i > 0) System.out.print(", ");
                System.out.print(String.format("0x%04X(%d字节)", e.getValue().get(i).offset, e.getValue().get(i).size));
            }
            if (e.getValue().size() > 4) System.out.print(" ...");
            System.out.println();
        }
        System.out.println();

        // ============ 块定义 ============
        System.out.println("┌──────────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  二、块定义 (BLOCK_HEADER / BLOCK)                                        │");
        System.out.println("└──────────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        // 收集 BLOCK_HEADER 和 BLOCK 类型
        List<Entity> blocks = byType.getOrDefault("BLOCK_HEADER", new ArrayList<>());
        List<Entity> blocks50 = byType.getOrDefault("BLOCK", new ArrayList<>());
        System.out.println("  BLOCK_HEADER (0x30): " + blocks.size() + " 个");
        System.out.println("  BLOCK (0x50): " + blocks50.size() + " 个");
        System.out.println();

        for (int i = 0; i < blocks.size(); i++) {
            Entity b = blocks.get(i);
            extractCoordsFromObject(b);
            System.out.println("  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            System.out.println("  【块定义 #" + (i + 1) + "】 " + b.typeName);
            System.out.println(String.format("    偏移: 0x%04X (%d字节)  大小: %d 字节", b.offset, b.offset, b.size));

            // 块名：从字符串推断
            String bname = "";
            if (!b.strings.isEmpty()) {
                // 优先 *Model_Space / *Paper_Space 等
                for (String s : b.strings) if (s.startsWith("*")) { bname = s; break; }
                if (bname.isEmpty()) bname = b.strings.get(0);
            } else {
                bname = "(位编码，需查表)";
            }
            System.out.println("    块名: \"" + bname + "\"");
            if (!b.strings.isEmpty()) {
                System.out.println("    包含字符串: " + String.join(", ", b.strings.subList(0, Math.min(5, b.strings.size()))));
            }
            if (b.props.containsKey("coord_start")) {
                double[] c = (double[]) b.props.get("coord_start");
                System.out.println(String.format("    检测到坐标: offset_in_obj=%.0f bytes, (x,y,z)=(%.4f, %.4f, %.4f)", c[0], c[1], c[2], c[3]));
            }
            System.out.println();
        }

        // ============ 块引用 (INSERT) ============
        System.out.println("┌──────────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  三、块引用 (INSERT)                                                      │");
        System.out.println("└──────────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        List<Entity> inserts = byType.getOrDefault("INSERT", new ArrayList<>());
        System.out.println("  共 " + inserts.size() + " 个块引用");
        System.out.println();

        // 收集已知块名用于匹配
        Set<String> knownNames = new LinkedHashSet<>();
        for (Entity e : blocks) knownNames.addAll(e.strings);
        for (Entity e : blocks50) knownNames.addAll(e.strings);

        for (int i = 0; i < inserts.size(); i++) {
            Entity ins = inserts.get(i);
            extractCoordsFromObject(ins);
            System.out.println("  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            System.out.println("  【块引用 #" + (i + 1) + "】");
            System.out.println(String.format("    偏移: 0x%04X  大小: %d 字节", ins.offset, ins.size));

            String ref = "";
            if (!ins.strings.isEmpty()) ref = String.join(", ", ins.strings);
            else {
                // 尝试在对象附近的字符串中找匹配
                for (String s : knownNames) ref = s;
                if (!ref.isEmpty()) ref = "(需查表匹配)";
                else ref = "(未知块)";
            }
            System.out.println("    引用块: \"" + ref + "\"");
            if (!ins.strings.isEmpty() && ins.strings.size() > 1) {
                System.out.println("    其他字符串: " + String.join(", ", ins.strings.subList(1, Math.min(3, ins.strings.size()))));
            }

            if (ins.props.containsKey("coord_start")) {
                double[] c = (double[]) ins.props.get("coord_start");
                System.out.println(String.format("    检测到坐标: (%.4f, %.4f, %.4f)", c[1], c[2], c[3]));
            }

            // Hex Dump (前64字节)
            int dumpLen = Math.min(64, ins.size + 2);
            System.out.println("\n" + hexDump(ins.offset, dumpLen));
            System.out.println();
        }

        // ============ 圆 ============
        System.out.println("┌──────────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  四、圆 (CIRCLE)                                                          │");
        System.out.println("└──────────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        List<Entity> circles = byType.getOrDefault("CIRCLE", new ArrayList<>());
        System.out.println("  共 " + circles.size() + " 个圆");
        for (int i = 0; i < circles.size(); i++) {
            Entity c = circles.get(i);
            extractCoordsFromObject(c);
            System.out.println("  【圆 #" + (i+1) + "】 @ 0x" + String.format("%04X", c.offset) + " (" + c.size + " 字节)");
            if (c.props.containsKey("coord_start")) {
                double[] cd = (double[]) c.props.get("coord_start");
                System.out.println(String.format("    圆心: (%.4f, %.4f, %.4f)", cd[1], cd[2], cd[3]));
                // 下一个可能是半径
                double[] nx = (double[]) c.props.get("coord_next");
                if (nx != null) System.out.println(String.format("    后续数据: (%.4f, %.4f, %.4f)", nx[0], nx[1], nx[2]));
            }
            if (!c.strings.isEmpty()) System.out.println("    字符串: " + String.join(", ", c.strings));
            System.out.println();
        }
        if (circles.isEmpty()) System.out.println("  (无)");
        System.out.println();

        // ============ 圆弧 ============
        System.out.println("┌──────────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  五、圆弧 (ARC)                                                          │");
        System.out.println("└──────────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        List<Entity> arcs = byType.getOrDefault("ARC", new ArrayList<>());
        System.out.println("  共 " + arcs.size() + " 个圆弧");
        for (int i = 0; i < arcs.size(); i++) {
            Entity a = arcs.get(i);
            extractCoordsFromObject(a);
            System.out.println("  【圆弧 #" + (i+1) + "】 @ 0x" + String.format("%04X", a.offset) + " (" + a.size + " 字节)");
            if (a.props.containsKey("coord_start")) {
                double[] cd = (double[]) a.props.get("coord_start");
                System.out.println(String.format("    圆心: (%.4f, %.4f, %.4f)", cd[1], cd[2], cd[3]));
            }
            if (!a.strings.isEmpty()) System.out.println("    字符串: " + String.join(", ", a.strings));
            System.out.println();
        }
        if (arcs.isEmpty()) System.out.println("  (无)");
        System.out.println();

        // ============ 直线 ============
        System.out.println("┌──────────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  六、直线 (LINE)                                                         │");
        System.out.println("└──────────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        List<Entity> lines = byType.getOrDefault("LINE", new ArrayList<>());
        System.out.println("  共 " + lines.size() + " 个直线");
        for (int i = 0; i < lines.size(); i++) {
            Entity l = lines.get(i);
            extractCoordsFromObject(l);
            System.out.println("  【直线 #" + (i+1) + "】 @ 0x" + String.format("%04X", l.offset) + " (" + l.size + " 字节)");
            if (l.props.containsKey("coord_start")) {
                double[] cd = (double[]) l.props.get("coord_start");
                double[] nx = (double[]) l.props.get("coord_next");
                System.out.println(String.format("    起点: (%.4f, %.4f, %.4f)", cd[1], cd[2], cd[3]));
                if (nx != null) System.out.println(String.format("    终点: (%.4f, %.4f, %.4f)", nx[0], nx[1], nx[2]));
            }
            if (!l.strings.isEmpty()) System.out.println("    字符串: " + String.join(", ", l.strings));
            System.out.println();
        }
        if (lines.isEmpty()) System.out.println("  (无)");
        System.out.println();

        // ============ 其他重要实体 ============
        System.out.println("┌──────────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  七、其他图形实体 (样条曲线、多段线、标注等)                              │");
        System.out.println("└──────────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        // SPLINE
        List<Entity> splines = byType.getOrDefault("SPLINE", new ArrayList<>());
        System.out.println("  ★ SPLINE (样条曲线): " + splines.size() + " 个");
        for (int i = 0; i < Math.min(3, splines.size()); i++) {
            Entity s = splines.get(i);
            extractCoordsFromObject(s);
            System.out.print(String.format("    [#%d] @0x%04X (%d bytes)", i+1, s.offset, s.size));
            if (s.props.containsKey("coord_start")) {
                double[] cd = (double[]) s.props.get("coord_start");
                System.out.print(String.format("  起点猜测: (%.4f, %.4f, %.4f)", cd[1], cd[2], cd[3]));
            }
            if (!s.strings.isEmpty()) System.out.print("  [字符串: " + String.join(",", s.strings) + "]");
            System.out.println();
        }
        System.out.println();

        // LWPOLYLINE
        List<Entity> lwpl = byType.getOrDefault("LWPOLYLINE", new ArrayList<>());
        System.out.println("  ★ LWPOLYLINE (轻量多段线): " + lwpl.size() + " 个");
        for (int i = 0; i < lwpl.size(); i++) {
            Entity s = lwpl.get(i);
            System.out.println(String.format("    [#%d] @0x%04X (%d bytes)", i+1, s.offset, s.size));
        }
        if (lwpl.isEmpty()) System.out.println("  (无)");
        System.out.println();

        // DIMENSION
        List<Entity> dims = byType.getOrDefault("DIMENSION", new ArrayList<>());
        System.out.println("  ★ DIMENSION (标注): " + dims.size() + " 个");
        for (int i = 0; i < dims.size(); i++) {
            Entity s = dims.get(i);
            System.out.println(String.format("    [#%d] @0x%04X (%d bytes)", i+1, s.offset, s.size));
        }
        if (dims.isEmpty()) System.out.println("  (无)");
        System.out.println();

        // MTEXT / TEXT
        List<Entity> mtexts = byType.getOrDefault("MTEXT", new ArrayList<>());
        List<Entity> texts = byType.getOrDefault("TEXT", new ArrayList<>());
        System.out.println("  ★ MTEXT (多行文字): " + mtexts.size() + " 个");
        for (int i = 0; i < mtexts.size(); i++) {
            Entity s = mtexts.get(i);
            System.out.println(String.format("    [#%d] @0x%04X (%d bytes) [字符串: %s]",
                i+1, s.offset, s.size, String.join(",", s.strings.subList(0, Math.min(3, s.strings.size())))));
        }
        if (mtexts.isEmpty()) System.out.println("  (无)");
        System.out.println();

        System.out.println("  ★ TEXT (单行文字): " + texts.size() + " 个");
        for (int i = 0; i < texts.size(); i++) {
            Entity s = texts.get(i);
            System.out.println(String.format("    [#%d] @0x%04X (%d bytes) [字符串: %s]",
                i+1, s.offset, s.size, String.join(",", s.strings.subList(0, Math.min(3, s.strings.size())))));
        }
        if (texts.isEmpty()) System.out.println("  (无)");
        System.out.println();

        // ============ 其他未识别对象 ============
        System.out.println("┌──────────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  八、其他未识别对象 (供分析参考)                                          │");
        System.out.println("└──────────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        for (Map.Entry<String, List<Entity>> e : byType.entrySet()) {
            String tn = e.getKey();
            if (tn.startsWith("TYPE_") || tn.contains("CONTROL") || tn.contains("INDEX") || tn.equals("ACAD_PROXY_OBJECT")) {
                List<Entity> list = e.getValue();
                System.out.println(String.format("  %-22s: %d 个", tn, list.size()));
                for (Entity ent : list) {
                    System.out.print(String.format("    @0x%04X (%d bytes)", ent.offset, ent.size));
                    if (!ent.strings.isEmpty()) {
                        System.out.print(" [字符串: " + String.join(",", ent.strings.subList(0, Math.min(3, ent.strings.size()))) + "]");
                    }
                    System.out.println();
                }
                System.out.println();
            }
        }

        // ============ 所有文本字符串 ============
        System.out.println("┌──────────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  九、文件中全部文本字符串 (长度>=2的ASCII串)                               │");
        System.out.println("└──────────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        Map<String, Integer> stringPos = new LinkedHashMap<>();
        Map<String, Integer> stringCount = new LinkedHashMap<>();
        for (int pos = 0x5200; pos < data.length - 3; pos++) {
            int len = data[pos] & 0xFF;
            if (len < 1 || len > 100 || pos + 1 + len > data.length) continue;
            StringBuilder sb = new StringBuilder();
            boolean valid = true;
            for (int i = 0; i < len; i++) {
                int c = data[pos + 1 + i] & 0xFF;
                if (c < 32 || c > 126) { valid = false; break; }
                sb.append((char)c);
            }
            if (valid && sb.length() >= 2 && sb.toString().matches("[A-Za-z_*][A-Za-z0-9_*\\-]*")) {
                String s = sb.toString();
                if (!stringPos.containsKey(s)) stringPos.put(s, pos);
                stringCount.merge(s, 1, Integer::sum);
            }
        }

        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(stringCount.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

        System.out.println("  共 " + stringPos.size() + " 个唯一字符串，累计 " + stringCount.values().stream().mapToInt(i->i).sum() + " 次出现");
        System.out.println();
        int idx = 0;
        for (Map.Entry<String, Integer> e : sorted) {
            idx++;
            int p = stringPos.get(e.getKey());
            System.out.println(String.format("    [%3d] \"%-25s\" × %3d  @ 0x%04X", idx, e.getKey(), e.getValue(), p));
        }
        System.out.println();

        // ============ 总结 ============
        System.out.println("┌──────────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  十、汇总统计                                                             │");
        System.out.println("└──────────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        System.out.println("  文件版本: AutoCAD R2000 (AC1015)");
        System.out.println("  文件大小: " + data.length + " 字节");
        System.out.println("  扫描对象: " + entities.size() + " 个");
        System.out.println("  ──────────────────────────────────────────");
        int total = 0;
        for (String[] key : new String[][]{{"块定义", "BLOCK_HEADER"}, {"块引用", "INSERT"},
            {"圆", "CIRCLE"}, {"圆弧", "ARC"}, {"直线", "LINE"}, {"样条", "SPLINE"}}) {
            int n = byType.getOrDefault(key[1], new ArrayList<>()).size();
            System.out.println(String.format("    %-12s: %d 个", key[0], n));
            total += n;
        }
        System.out.println(String.format("    %-12s: %d 个 (剩余)", "其他", entities.size() - total));
        System.out.println();

        System.out.println("  说明:");
        System.out.println("    1. 坐标数据基于在对象数据中查找合理 3×8 字节浮点三元组推断");
        System.out.println("    2. AC1015 实际采用位编码 (BS/BL/BU/BD), 因此推断值仅供参考");
        System.out.println("    3. 小型 INSERT 对象(38-40字节)使用位编码句柄引用块定义");
        System.out.println("    4. 需配合 ODA 规范和 Handles Section 表解析精确对象关系");
        System.out.println();

        System.out.println("╔════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                          解析报告结束                                       ║");
        System.out.println("╚════════════════════════════════════════════════════════════════════════════╝");
        System.out.println();

        // 保存文件
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("DWG 实体解析报告 - " + fileName + "\n\n");
            for (Entity e : entities) {
                sb.append(String.format("[0x%04X] %-22s size=%-5d strings=[%s]\n",
                    e.offset, e.typeName, e.size, String.join(",", e.strings)));
            }
            Files.write(Paths.get("dwg_entities_full.txt"), sb.toString().getBytes("UTF-8"));
            System.out.println("  数据已保存: dwg_entities_full.txt");
        } catch (Exception ex) {}
    }
}
