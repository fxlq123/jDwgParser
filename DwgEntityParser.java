import java.nio.file.*;
import java.util.*;
import java.io.*;

/**
 * DWG R2000 (AC1015) 图形实体详细解析器
 * 尝试解析: 直线、圆弧、圆、块定义、块引用
 */
public class DwgEntityParser {

    // ============ 位流读取器 ============
    static class BitReader {
        byte[] data;
        int bitPos;  // 当前位位置（从 0 开始，绝对位置）

        BitReader(byte[] d, int startByte) {
            this.data = d;
            this.bitPos = startByte * 8;
        }

        // 读取 1 位
        int readBit() {
            int b = data[bitPos / 8] & 0xFF;
            int bit = 7 - (bitPos % 8);
            bitPos++;
            return (b >> bit) & 1;
        }

        // 读取 n 位（无符号）
        long readBits(int n) {
            long val = 0;
            for (int i = 0; i < n; i++) {
                val = (val << 1) | readBit();
            }
            return val;
        }

        // 对齐到字节边界
        void alignToByte() {
            if (bitPos % 8 != 0) {
                bitPos += (8 - bitPos % 8);
            }
        }

        // 对齐到 16-bit 字边界
        void alignToWord() {
            if (bitPos % 16 != 0) {
                bitPos += (16 - bitPos % 16);
            }
        }

        // 当前字节偏移
        int getBytePos() { return bitPos / 8; }
        int getBitOffset() { return bitPos % 8; }

        // 读取 BL (32-bit 长整型，位流中的 32 位)
        long readBL() {
            alignToByte();
            long v = ((long)(data[bitPos/8] & 0xFF))
                   | ((long)(data[bitPos/8+1] & 0xFF) << 8)
                   | ((long)(data[bitPos/8+2] & 0xFF) << 16)
                   | ((long)(data[bitPos/8+3] & 0xFF) << 24);
            bitPos += 32;
            return v;
        }

        // 读取 MS (Modular Short, 16-bit LE word)
        // bit 15 = 0: single word, value = bits 0-14
        // bit 15 = 1: multi-word, next word read
        int readMS() {
            alignToWord();
            int result = 0;
            boolean hasMore;
            int words = 0;
            do {
                int w = (data[bitPos/8] & 0xFF) | ((data[bitPos/8+1] & 0xFF) << 8);
                bitPos += 16;
                hasMore = (w & 0x8000) != 0;
                result = (result << 15) | (w & 0x7FFF);
                words++;
                if (words > 4) break; // 安全
            } while (hasMore);
            return result;
        }

        // 读取 BS (Bit Short) - 通常在对象头，这里不用
        // 读取 H 码 (Handle code) - 4-bit groups, MSB first, highest bit of group = has more
        long readH() {
            // ODA AC1015 spec: H code reads 4-bit groups, from high to low
            // Each 4 bits: highest bit = "more" flag (inverted), remaining 3 bits = data
            // Actually: H-code reads 8-bit groups where the high bit is the flag
            // Let me try: 4 bits at a time where the first bit is "continue" flag
            long val = 0;
            int groups = 0;
            while (groups < 8) {
                int flag = readBit();  // 1 = last group, 0 = more
                int data = (int)readBits(3);  // 3 bits of data
                val = (val << 3) | data;
                groups++;
                if (flag == 1) break;
            }
            return val;
        }

        // 读取 BD (Bit Double) - 64 bits
        double readBD() {
            alignToByte();
            long bits = 0;
            for (int i = 0; i < 8; i++) {
                bits |= ((long)(data[bitPos/8 + i] & 0xFF)) << (i * 8);
            }
            bitPos += 64;
            return Double.longBitsToDouble(bits);
        }

        // 读取 RD (Raw Double) - 8 字节 IEEE 754
        double readRD() {
            return readBD();
        }

        // 跳过 n 位
        void skipBits(int n) { bitPos += n; }
        void skipBytes(int n) { bitPos += n * 8; }

        // 检查剩余位数
        int remaining() { return data.length * 8 - bitPos; }
    }

    // ============ 对象结构 ============
    static class DwgObject {
        int offset;     // 文件中的字节偏移
        int size;       // 对象大小（字节，MS 读取）
        int typeCode;   // 对象类型码
        List<String> strings;  // 内部检测到的文本字符串
        Map<String, Object> props;  // 解析出的属性

        DwgObject() {
            strings = new ArrayList<>();
            props = new LinkedHashMap<>();
        }

        String typeName() {
            switch(typeCode) {
                case 0x01: return "TEXT";
                case 0x04: return "CIRCLE";
                case 0x05: return "ARC";
                case 0x07: return "INSERT";
                case 0x08: return "ATTDEF";
                case 0x09: return "ATTRIB";
                case 0x0E: return "ELLIPSE";
                case 0x0F: return "LWPOLYLINE";
                case 0x10: return "LINE";
                case 0x15: return "DIMENSION";
                case 0x1E: return "MLINE";
                case 0x1F: return "MTEXT";
                case 0x2C: return "SPLINE";
                case 0x30: return "BLOCK_HEADER";
                case 0x31: return "ENDBLK";
                case 0x32: return "LAYER_INDEX";
                case 0x34: return "SPATIAL_INDEX";
                case 0x43: return "LAYER";
                case 0x47: return "LTYPE";
                case 0x49: return "STYLE";
                case 0x4F: return "BLOCK_CONTROL";
                case 0x50: return "BLOCK";
                default:   return "TYPE_0x" + String.format("%02X", typeCode);
            }
        }
    }

    // ============ 主程序 ============
    static byte[] data;

    static int readMS(int pos) {
        int w = (data[pos] & 0xFF) | ((data[pos+1] & 0xFF) << 8);
        if ((w & 0x8000) != 0) return -1;
        return w & 0x7FFF;
    }

    static int readBSType(int pos) {
        int b0 = data[pos] & 0xFF;
        int b1 = data[pos+1] & 0xFF;
        if (((b0 >> 6) & 3) != 1) return -1;
        return ((b0 & 0x3F) << 2) | ((b1 >> 6) & 3);
    }

    static String readLenString(int pos) {
        int len = data[pos] & 0xFF;
        if (len < 1 || len > 100 || pos + 1 + len > data.length) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            int c = data[pos + 1 + i] & 0xFF;
            if (c < 32 || c > 126) return null;
            sb.append((char)c);
        }
        return sb.toString();
    }

    static List<DwgObject> scanObjects() {
        List<DwgObject> objs = new ArrayList<>();
        int scanStart = 0x5200;
        for (int pos = scanStart; pos < data.length - 4; pos++) {
            int size = readMS(pos);
            if (size < 6 || size > 100000 || pos + 2 + size > data.length) continue;
            int tc = readBSType(pos + 2);
            if (tc < 0 || tc > 500) continue;

            DwgObject obj = new DwgObject();
            obj.offset = pos;
            obj.size = size;
            obj.typeCode = tc;

            // 提取文本字符串
            int end = pos + 2 + size;
            for (int j = pos + 4; j < end - 2; j++) {
                String s = readLenString(j);
                if (s != null && s.length() >= 2 && s.matches("[A-Za-z_*][A-Za-z0-9_*\\-]*")) {
                    if (!obj.strings.contains(s)) obj.strings.add(s);
                }
            }

            objs.add(obj);
            pos += size; // 跳过
        }
        return objs;
    }

    // 解析 CIRCLE (0x04)
    static void parseCircle(DwgObject obj) {
        try {
            BitReader br = new BitReader(data, obj.offset + 4);  // 跳过 MS+BS

            // Entity Common Data (ECDD)
            long bitsize = br.readBL();
            long handle = br.readH();
            int eedSize = br.readMS();
            long ownerHandle = br.readH();
            // numReactors - 可能没有，取决于 bitsize

            // 对齐
            br.alignToByte();

            // 读取后面对象属性
            // AC1015 CIRCLE: bitsize > 0 时使用位编码，否则使用字节编码
            double cx = br.readBD();
            double cy = br.readBD();
            double cz = br.readBD();
            double radius = br.readBD();
            double thickness = br.readBD();
            double ex = br.readBD();
            double ey = br.readBD();
            double ez = br.readBD();

            obj.props.put("center", new double[]{cx, cy, cz});
            obj.props.put("radius", radius);
            obj.props.put("thickness", thickness);
            obj.props.put("extrusion", new double[]{ex, ey, ez});
            obj.props.put("bitsize", bitsize);
            obj.props.put("handle", handle);
            obj.props.put("owner_handle", ownerHandle);
        } catch (Exception e) {
            obj.props.put("parse_error", e.getMessage());
        }
    }

    // 解析 ARC (0x05)
    static void parseArc(DwgObject obj) {
        try {
            BitReader br = new BitReader(data, obj.offset + 4);
            long bitsize = br.readBL();
            long handle = br.readH();
            int eedSize = br.readMS();
            long ownerHandle = br.readH();
            br.alignToByte();

            double cx = br.readBD();
            double cy = br.readBD();
            double cz = br.readBD();
            double radius = br.readBD();
            double thickness = br.readBD();
            double startAngle = br.readBD();
            double endAngle = br.readBD();
            double ex = br.readBD();
            double ey = br.readBD();
            double ez = br.readBD();

            obj.props.put("center", new double[]{cx, cy, cz});
            obj.props.put("radius", radius);
            obj.props.put("thickness", thickness);
            obj.props.put("start_angle_rad", startAngle);
            obj.props.put("end_angle_rad", endAngle);
            obj.props.put("start_angle_deg", Math.toDegrees(startAngle));
            obj.props.put("end_angle_deg", Math.toDegrees(endAngle));
            obj.props.put("extrusion", new double[]{ex, ey, ez});
            obj.props.put("bitsize", bitsize);
            obj.props.put("handle", handle);
        } catch (Exception e) {
            obj.props.put("parse_error", e.getMessage());
        }
    }

    // 解析 LINE (0x10)
    static void parseLine(DwgObject obj) {
        try {
            BitReader br = new BitReader(data, obj.offset + 4);
            long bitsize = br.readBL();
            long handle = br.readH();
            int eedSize = br.readMS();
            long ownerHandle = br.readH();
            br.alignToByte();

            double x1 = br.readBD();
            double y1 = br.readBD();
            double z1 = br.readBD();
            double x2 = br.readBD();
            double y2 = br.readBD();
            double z2 = br.readBD();
            double thickness = br.readBD();
            double ex = br.readBD();
            double ey = br.readBD();
            double ez = br.readBD();

            obj.props.put("start", new double[]{x1, y1, z1});
            obj.props.put("end", new double[]{x2, y2, z2});
            obj.props.put("thickness", thickness);
            obj.props.put("extrusion", new double[]{ex, ey, ez});
            obj.props.put("length", Math.sqrt((x2-x1)*(x2-x1) + (y2-y1)*(y2-y1) + (z2-z1)*(z2-z1)));
            obj.props.put("bitsize", bitsize);
            obj.props.put("handle", handle);
        } catch (Exception e) {
            obj.props.put("parse_error", e.getMessage());
        }
    }

    // 解析 INSERT (0x07)
    static void parseInsert(DwgObject obj) {
        try {
            BitReader br = new BitReader(data, obj.offset + 4);
            long bitsize = br.readBL();
            long handle = br.readH();
            int eedSize = br.readMS();
            long ownerHandle = br.readH();
            long blockRecordHandle = br.readH();
            br.alignToByte();

            double x = br.readBD();
            double y = br.readBD();
            double z = br.readBD();
            double sx = br.readBD();
            double sy = br.readBD();
            double sz = br.readBD();
            double rot = br.readBD();
            double ex = br.readBD();
            double ey = br.readBD();
            double ez = br.readBD();

            obj.props.put("position", new double[]{x, y, z});
            obj.props.put("scale", new double[]{sx, sy, sz});
            obj.props.put("rotation_rad", rot);
            obj.props.put("rotation_deg", Math.toDegrees(rot));
            obj.props.put("extrusion", new double[]{ex, ey, ez});
            obj.props.put("bitsize", bitsize);
            obj.props.put("handle", handle);
            obj.props.put("block_record_handle", blockRecordHandle);
            obj.props.put("owner_handle", ownerHandle);
        } catch (Exception e) {
            obj.props.put("parse_error", e.getMessage());
        }
    }

    // 解析 BLOCK_HEADER (0x30)
    static void parseBlockHeader(DwgObject obj) {
        try {
            BitReader br = new BitReader(data, obj.offset + 4);
            long bitsize = br.readBL();
            long handle = br.readH();
            int eedSize = br.readMS();
            long ownerHandle = br.readH();
            long blockControlHandle = br.readH();
            long blockRecordHandle = br.readH();
            br.alignToByte();

            double x = br.readBD();
            double y = br.readBD();
            double z = br.readBD();

            obj.props.put("base_point", new double[]{x, y, z});
            obj.props.put("bitsize", bitsize);
            obj.props.put("handle", handle);
            obj.props.put("block_control_handle", blockControlHandle);
            obj.props.put("block_record_handle", blockRecordHandle);
        } catch (Exception e) {
            obj.props.put("parse_error", e.getMessage());
        }
    }

    static String fmt(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return String.format("%.4f", v);
        if (Math.abs(v) < 0.0001 && v != 0) return String.format("%.4e", v);
        return String.format("%.4f", v);
    }

    static String fmtVec(double[] v) {
        return "(" + fmt(v[0]) + ", " + fmt(v[1]) + ", " + fmt(v[2]) + ")";
    }

    public static void main(String[] args) throws Exception {
        String fileName = "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg";
        data = Files.readAllBytes(Paths.get(fileName));

        PrintStream out = System.out;
        out.println();
        out.println("╔══════════════════════════════════════════════════════════════════════════════════╗");
        out.println("║               DWG 图形实体详细解析报告                                           ║");
        out.println("║         文件: 210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg                 ║");
        out.println("║         版本: AutoCAD R2000 (AC1015)  |  大小: " + data.length + " 字节            ║");
        out.println("╚════════════════════════════════════════════════════════════════════════════════════╝");
        out.println();

        List<DwgObject> objects = scanObjects();

        // 分类
        Map<String, List<DwgObject>> byType = new LinkedHashMap<>();
        for (DwgObject o : objects) {
            byType.computeIfAbsent(o.typeName(), k -> new ArrayList<>()).add(o);
        }

        // 解析各类型对象
        int lineCount = 0, circleCount = 0, arcCount = 0, insertCount = 0, blockCount = 0;
        StringBuilder report = new StringBuilder();

        // ============ 块定义 (BLOCK_HEADER) ============
        out.println("┌──────────────────────────────────────────────────────────────────────────────────┐");
        out.println("│  ★ 块定义 (BLOCK_HEADER, 类型码=0x30)                                            │");
        out.println("└──────────────────────────────────────────────────────────────────────────────────┘");

        List<DwgObject> blocks = byType.getOrDefault("BLOCK_HEADER", new ArrayList<>());
        blockCount = blocks.size();
        out.println("  共 " + blockCount + " 个块定义");
        out.println();

        for (int i = 0; i < blocks.size(); i++) {
            DwgObject b = blocks.get(i);
            parseBlockHeader(b);

            out.println("  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            out.println("  【块 #" + (i + 1) + "】");
            out.println(String.format("    偏移位置: 0x%04X (字节 %d)", b.offset, b.offset));
            out.println("    对象大小: " + b.size + " 字节");
            out.println("    句柄 (Handle): " + b.props.getOrDefault("handle", -1));

            // 块名 - 优先从对象内字符串取
            String blockName = "";
            for (String s : b.strings) {
                if (s.startsWith("*") || s.length() >= 3) {
                    blockName = s;
                    break;
                }
            }
            if (!b.strings.isEmpty() && blockName.isEmpty()) {
                blockName = b.strings.get(0);
            }
            if (blockName.isEmpty()) blockName = "(位编码，无明文)";
            out.println("    块名称: \"" + blockName + "\"");

            double[] bp = (double[]) b.props.get("base_point");
            if (bp != null) out.println("    插入基点: " + fmtVec(bp));

            if (!b.strings.isEmpty()) {
                out.println("    包含字符串: " + String.join(", ", b.strings.subList(0, Math.min(5, b.strings.size()))));
            }
            out.println();
        }

        // ============ 块引用 (INSERT) ============
        out.println("┌──────────────────────────────────────────────────────────────────────────────────┐");
        out.println("│  ★ 块引用 (INSERT, 类型码=0x07)                                                  │");
        out.println("└──────────────────────────────────────────────────────────────────────────────────┘");

        List<DwgObject> inserts = byType.getOrDefault("INSERT", new ArrayList<>());
        insertCount = inserts.size();
        out.println("  共 " + insertCount + " 个块引用");
        out.println();

        // 收集所有已知块名，用于匹配 INSERT
        List<String> knownBlockNames = new ArrayList<>();
        for (DwgObject b : blocks) {
            knownBlockNames.addAll(b.strings);
        }
        // 也添加全局扫描到的块名
        for (int pos = 0x5200; pos < data.length - 5; pos++) {
            String s = readLenString(pos);
            if (s != null && s.length() >= 2 && s.matches("[A-Za-z_*][A-Za-z0-9_*\\-]*")) {
                if (!knownBlockNames.contains(s)) knownBlockNames.add(s);
            }
        }

        for (int i = 0; i < inserts.size(); i++) {
            DwgObject ins = inserts.get(i);
            parseInsert(ins);

            out.println("  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            out.println("  【块引用 #" + (i + 1) + "】");
            out.println(String.format("    偏移位置: 0x%04X (字节 %d)", ins.offset, ins.offset));
            out.println("    对象大小: " + ins.size + " 字节");

            String refName = "";
            if (!ins.strings.isEmpty()) {
                refName = String.join(", ", ins.strings.subList(0, Math.min(2, ins.strings.size())));
            }
            if (refName.isEmpty()) {
                // 尝试从附近位置找匹配的块名
                // 小型 INSERT 通常只有块表记录句柄（位编码），无法直接解析名称
                refName = "(位编码句柄，需配合 BLOCK 表解析)";
            }
            out.println("    引用块名: \"" + refName + "\"");

            double[] pos = (double[]) ins.props.get("position");
            double[] scale = (double[]) ins.props.get("scale");
            if (pos != null) out.println("    插入位置: " + fmtVec(pos));
            if (scale != null) out.println("    缩放系数: " + fmtVec(scale));
            if (ins.props.containsKey("rotation_deg")) out.println("    旋转角度: " + fmt((Double)ins.props.get("rotation_deg")) + "°");
            out.println("    句柄 (Handle): " + ins.props.getOrDefault("handle", "-1"));
            out.println("    块表记录句柄: " + ins.props.getOrDefault("block_record_handle", "-1"));

            if (!ins.strings.isEmpty()) out.println("    其他字符串: " + String.join(", ", ins.strings.subList(0, Math.min(5, ins.strings.size()))));
            out.println();
        }

        // ============ 圆 (CIRCLE) ============
        out.println("┌──────────────────────────────────────────────────────────────────────────────────┐");
        out.println("│  ★ 圆 (CIRCLE, 类型码=0x04)                                                     │");
        out.println("└──────────────────────────────────────────────────────────────────────────────────┘");

        List<DwgObject> circles = byType.getOrDefault("CIRCLE", new ArrayList<>());
        circleCount = circles.size();
        out.println("  共 " + circleCount + " 个圆");
        out.println();

        for (int i = 0; i < circles.size(); i++) {
            DwgObject c = circles.get(i);
            parseCircle(c);
            out.println("  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            out.println("  【圆 #" + (i + 1) + "】");
            out.println(String.format("    偏移位置: 0x%04X", c.offset));
            out.println("    对象大小: " + c.size + " 字节");

            double[] center = (double[]) c.props.get("center");
            Double radius = (Double) c.props.get("radius");
            if (center != null) out.println("    圆心 (X,Y,Z): " + fmtVec(center));
            if (radius != null) out.println("    半径: " + fmt(radius));
            if (radius != null) out.println("    周长: " + fmt(2 * Math.PI * radius) + ", 面积: " + fmt(Math.PI * radius * radius));
            if (c.props.containsKey("thickness")) out.println("    厚度: " + fmt((Double)c.props.get("thickness")));
            out.println("    句柄 (Handle): " + c.props.getOrDefault("handle", "-1"));
            out.println();
        }

        // ============ 圆弧 (ARC) ============
        out.println("┌──────────────────────────────────────────────────────────────────────────────────┐");
        out.println("│  ★ 圆弧 (ARC, 类型码=0x05)                                                      │");
        out.println("└──────────────────────────────────────────────────────────────────────────────────┘");

        List<DwgObject> arcs = byType.getOrDefault("ARC", new ArrayList<>());
        arcCount = arcs.size();
        out.println("  共 " + arcCount + " 个圆弧");
        out.println();

        for (int i = 0; i < arcs.size(); i++) {
            DwgObject a = arcs.get(i);
            parseArc(a);
            out.println("  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            out.println("  【圆弧 #" + (i + 1) + "】");
            out.println(String.format("    偏移位置: 0x%04X", a.offset));

            double[] center = (double[]) a.props.get("center");
            Double radius = (Double) a.props.get("radius");
            if (center != null) out.println("    圆心 (X,Y,Z): " + fmtVec(center));
            if (radius != null) out.println("    半径: " + fmt(radius));
            if (a.props.containsKey("start_angle_deg")) out.println("    起始角: " + fmt((Double)a.props.get("start_angle_deg")) + "°");
            if (a.props.containsKey("end_angle_deg")) out.println("    终止角: " + fmt((Double)a.props.get("end_angle_deg")) + "°");
            if (a.props.containsKey("thickness")) out.println("    厚度: " + fmt((Double)a.props.get("thickness")));
            out.println("    句柄 (Handle): " + a.props.getOrDefault("handle", "-1"));
            out.println();
        }

        // ============ 直线 (LINE) ============
        out.println("┌──────────────────────────────────────────────────────────────────────────────────┐");
        out.println("│  ★ 直线 (LINE, 类型码=0x10)                                                    │");
        out.println("└──────────────────────────────────────────────────────────────────────────────────┘");

        List<DwgObject> lines = byType.getOrDefault("LINE", new ArrayList<>());
        lineCount = lines.size();
        out.println("  共 " + lineCount + " 个直线");
        out.println();

        for (int i = 0; i < lines.size(); i++) {
            DwgObject l = lines.get(i);
            parseLine(l);
            out.println("  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            out.println("  【直线 #" + (i + 1) + "】");
            out.println(String.format("    偏移位置: 0x%04X", l.offset));

            double[] s = (double[]) l.props.get("start");
            double[] e = (double[]) l.props.get("end");
            if (s != null) out.println("    起点 (X,Y,Z): " + fmtVec(s));
            if (e != null) out.println("    终点 (X,Y,Z): " + fmtVec(e));
            if (l.props.containsKey("length")) out.println("    长度: " + fmt((Double)l.props.get("length")));
            if (l.props.containsKey("thickness")) out.println("    厚度: " + fmt((Double)l.props.get("thickness")));
            out.println("    句柄 (Handle): " + l.props.getOrDefault("handle", "-1"));
            out.println();
        }

        // ============ 其他实体类型 ============
        out.println("┌──────────────────────────────────────────────────────────────────────────────────┐");
        out.println("│  ★ 其他图形实体                                                                  │");
        out.println("└──────────────────────────────────────────────────────────────────────────────────┘");
        out.println();

        // 显示所有其他类型
        for (Map.Entry<String, List<DwgObject>> e : byType.entrySet()) {
            String type = e.getKey();
            if (type.equals("BLOCK_HEADER") || type.equals("INSERT") ||
                type.equals("CIRCLE") || type.equals("ARC") || type.equals("LINE")) continue;

            List<DwgObject> ol = e.getValue();
            out.println(String.format("  %-20s: %d 个对象 @ ", type, ol.size()));
            for (int i = 0; i < Math.min(5, ol.size()); i++) {
                DwgObject o = ol.get(i);
                out.print(String.format("    0x%04X (%d bytes)", o.offset, o.size));
                if (!o.strings.isEmpty()) {
                    out.print("  [字符串: " + String.join(", ", o.strings.subList(0, Math.min(3, o.strings.size()))) + "]");
                }
                out.println();
            }
            out.println();
        }

        // ============ 全局检测到的文本字符串 ============
        out.println("┌──────────────────────────────────────────────────────────────────────────────────┐");
        out.println("│  ★ 文件中检测到的文本字符串 (Top 50)                                             │");
        out.println("└──────────────────────────────────────────────────────────────────────────────────┘");
        out.println();

        Map<String, Integer> stringCounts = new LinkedHashMap<>();
        Map<String, Integer> stringPos = new LinkedHashMap<>();
        for (int pos = 0x5200; pos < data.length - 5; pos++) {
            String s = readLenString(pos);
            if (s != null && s.length() >= 2 && s.matches("[A-Za-z_*][A-Za-z0-9_*\\-]*") && !s.matches("\\d+")) {
                stringCounts.merge(s, 1, Integer::sum);
                if (!stringPos.containsKey(s)) stringPos.put(s, pos);
            }
        }

        List<Map.Entry<String, Integer>> sortedStrs = new ArrayList<>(stringCounts.entrySet());
        sortedStrs.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

        out.println(String.format("  共检测到 %d 个唯一字符串，%d 次出现", stringCounts.size(), stringCounts.values().stream().mapToInt(i->i).sum()));
        out.println();

        int count = 0;
        for (Map.Entry<String, Integer> e : sortedStrs) {
            if (count >= 50) break;
            int pos = stringPos.get(e.getKey());
            out.println(String.format("    [%3d] \"%-25s\" × %3d  @ 0x%04X (byte %d)",
                count + 1, e.getKey(), e.getValue(), pos, pos));
            count++;
        }
        out.println();

        // ============ 汇总 ============
        out.println("┌──────────────────────────────────────────────────────────────────────────────────┐");
        out.println("│  ★ 汇总统计                                                                      │");
        out.println("└──────────────────────────────────────────────────────────────────────────────────┘");
        out.println();

        int total = objects.size();
        out.println("  文件版本: AutoCAD R2000 (AC1015)");
        out.println("  文件大小: " + data.length + " 字节 (" + String.format("%.2f", data.length/1024.0) + " KB)");
        out.println("  检测到对象总数: " + total + " 个");
        out.println("  ─────────────────────────────────");
        out.println("    块定义 (BLOCK_HEADER) : " + blockCount + " 个");
        out.println("    块引用 (INSERT)       : " + insertCount + " 个");
        out.println("    圆 (CIRCLE)           : " + circleCount + " 个");
        out.println("    圆弧 (ARC)            : " + arcCount + " 个");
        out.println("    直线 (LINE)           : " + lineCount + " 个");
        out.println("    其他实体              : " + (total - blockCount - insertCount - circleCount - arcCount - lineCount) + " 个");
        out.println();

        // 按类型详细统计
        out.println("  按对象类型统计:");
        for (Map.Entry<String, List<DwgObject>> e : byType.entrySet()) {
            out.println(String.format("    %-20s : %3d 个", e.getKey(), e.getValue().size()));
        }
        out.println();

        out.println("╔══════════════════════════════════════════════════════════════════════════════════╗");
        out.println("║                        解析报告结束                                               ║");
        out.println("║     注: 几何数据按 IEEE 754 双精度浮点格式直接读取,                           ║");
        out.println("║         实际 AC1015 格式使用位编码(BS/BL/BU/BD),                           ║");
        out.println("║         因此部分坐标值可能需要按 ODA 规范进一步修正                            ║");
        out.println("╚════════════════════════════════════════════════════════════════════════════════════╝");
        out.println();

        // 保存详细报告
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("DWG 实体详细解析报告\n");
            sb.append("文件: ").append(fileName).append("\n");
            sb.append("版本: AutoCAD R2000 (AC1015)\n");
            sb.append("大小: ").append(data.length).append(" 字节\n\n");

            for (DwgObject o : objects) {
                sb.append(String.format("[0x%04X] %-20s size=%-5d", o.offset, o.typeName(), o.size));
                for (Map.Entry<String, Object> p : o.props.entrySet()) {
                    Object v = p.getValue();
                    if (v instanceof double[]) {
                        double[] d = (double[])v;
                        sb.append(" ").append(p.getKey()).append("=(").append(fmt(d[0])).append(",")
                          .append(fmt(d[1])).append(",").append(fmt(d[2])).append(")");
                    } else {
                        sb.append(" ").append(p.getKey()).append("=").append(String.format("%.4f", (Double)v));
                    }
                }
                if (!o.strings.isEmpty()) sb.append(" [strings:").append(String.join(",", o.strings)).append("]");
                sb.append("\n");
            }
            Files.write(Paths.get("dwg_entities_detail.txt"), sb.toString().getBytes("UTF-8"));
            out.println("  详细数据已保存: dwg_entities_detail.txt");
        } catch (Exception ex) {
            out.println("  保存失败: " + ex.getMessage());
        }
    }
}
