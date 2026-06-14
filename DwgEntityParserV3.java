import java.nio.file.*;
import java.util.*;

/**
 * DWG R2000 实体解析 v3
 * 不使用字节重叠标记，暴力扫描所有可能的 MS+BS 组合
 * 然后按偏移排序去重
 */
public class DwgEntityParserV3 {
    static byte[] data;

    static class Entity {
        int offset, size, type;
        String typeName;
        List<String> strings;
        Entity() { strings = new ArrayList<>(); }
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

    static int[] readMS(int pos) {
        int size = 0, bytesRead = 0;
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
        if (size <= 2 || size > 50000) return null;
        return new int[]{size, bytesRead};
    }

    static int readBSType(int pos) {
        int b0 = data[pos] & 0xFF;
        int b1 = data[pos + 1] & 0xFF;
        if (((b0 >> 6) & 3) != 1) return -1;
        return ((b0 & 0x3F) << 2) | ((b1 >> 6) & 3);
    }

    static void extractStrings(Entity e) {
        int end = Math.min(e.offset + 2 + e.size, e.offset + 4000);
        for (int pos = e.offset + 4; pos < end - 2; pos++) {
            int len = data[pos] & 0xFF;
            if (len < 1 || len > 100 || pos + 1 + len > end) continue;
            StringBuilder sb = new StringBuilder();
            boolean valid = true;
            for (int i = 0; i < len; i++) {
                int c = data[pos + 1 + i] & 0xFF;
                if (c < 32 || c > 126) { valid = false; break; }
                sb.append((char)c);
            }
            if (valid && sb.length() >= 2) {
                String s = sb.toString();
                if (s.matches("[A-Za-z_*][A-Za-z0-9_*\\-]*") && !e.strings.contains(s)) e.strings.add(s);
            }
        }
    }

    // Hex dump
    static String hexDump(int offset, int len) {
        StringBuilder sb = new StringBuilder();
        for (int row = 0; row < len; row += 16) {
            int rowLen = Math.min(16, len - row);
            sb.append(String.format("        %04X: ", offset + row));
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

    // 读取可能的坐标：在对象中查找 3×8 字节浮点三元组
    static double[] findCoords(Entity e, int offsetInObj) {
        int p = e.offset + 4 + offsetInObj;
        if (p + 24 >= data.length) return null;
        long[] bits = new long[3];
        for (int dim = 0; dim < 3; dim++) {
            for (int i = 0; i < 8; i++) {
                bits[dim] |= ((long)(data[p + dim*8 + i] & 0xFF)) << (i * 8);
            }
        }
        double[] coords = new double[3];
        for (int i = 0; i < 3; i++) coords[i] = Double.longBitsToDouble(bits[i]);
        boolean ok = true;
        for (double v : coords) {
            if (Double.isNaN(v) || Double.isInfinite(v)) { ok = false; break; }
            if (v != 0 && (Math.abs(v) < 1e-6 || Math.abs(v) > 1e6)) ok = false;
        }
        return ok ? coords : null;
    }

    public static void main(String[] args) throws Exception {
        String fileName = "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg";
        data = Files.readAllBytes(Paths.get(fileName));

        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║              DWG 图形实体解析完整报告                                           ║");
        System.out.println("║   文件: " + fileName.substring(0, Math.min(40, fileName.length())) + "   ║");
        System.out.println("║   版本: AutoCAD R2000 (AC1015)  大小: " + String.format("%6d", data.length) + " 字节 (" + String.format("%.1f", data.length/1024.0) + " KB)          ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════════════════╝");
        System.out.println();

        // ============ 扫描：暴力扫描所有 MS+BS 对 ============
        System.out.println("┌──────────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  第一部分：扫描所有对象                                                   │");
        System.out.println("└──────────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        List<Entity> raw = new ArrayList<>();
        int scanStart = 0x5200;
        for (int pos = scanStart; pos < data.length - 4; pos++) {
            int[] ms = readMS(pos);
            if (ms == null) continue;
            int size = ms[0], msBytes = ms[1];
            if (pos + msBytes + 2 > data.length) continue;
            int tc = readBSType(pos + msBytes);  // BS starts right after MS bytes
            if (tc < 0 || tc > 600) continue;

            Entity e = new Entity();
            e.offset = pos;
            e.size = size;
            e.type = tc;
            e.typeName = typeName(tc);
            raw.add(e);
        }

        // 按类型分组
        Map<String, List<Entity>> byType = new LinkedHashMap<>();
        for (Entity e : raw) {
            byType.computeIfAbsent(e.typeName, k -> new ArrayList<>()).add(e);
        }

        // 统计
        System.out.println("  扫描到 " + raw.size() + " 个对象，按类型统计:");
        System.out.println();

        // 按数量排序
        List<Map.Entry<String, List<Entity>>> sorted = new ArrayList<>(byType.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()));

        for (Map.Entry<String, List<Entity>> e : sorted) {
            List<Entity> list = e.getValue();
            // 提取字符串
            for (Entity ent : list) extractStrings(ent);
            System.out.print(String.format("    %-22s : %3d 个  ", e.getKey(), list.size()));
            // 打印前几个位置
            for (int i = 0; i < Math.min(3, list.size()); i++) {
                if (i > 0) System.out.print(", ");
                Entity ent = list.get(i);
                System.out.print(String.format("@0x%04X(%dB)", ent.offset, ent.size));
                if (!ent.strings.isEmpty()) {
                    System.out.print("[" + ent.strings.get(0) + "]");
                    break;  // 第一个有字符串就够了
                }
            }
            System.out.println();
        }
        System.out.println();

        // ============ 第二部分：详细解析各类实体 ============
        System.out.println("┌──────────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  第二部分：块详细信息                                                     │");
        System.out.println("└──────────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        // BLOCK_HEADER (0x30)
        List<Entity> bh = byType.getOrDefault("BLOCK_HEADER", new ArrayList<>());
        System.out.println("  ★ BLOCK_HEADER (块表头, 类型码 0x30) : " + bh.size() + " 个");
        for (int i = 0; i < bh.size(); i++) {
            Entity e = bh.get(i);
            extractStrings(e);
            System.out.println("    ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            System.out.println(String.format("    块 #%d:  @ 0x%04X, 大小 %d 字节", i+1, e.offset, e.size));

            // 块名：从字符串或已知位置推断
            String bname = "";
            for (String s : e.strings) if (s.startsWith("*")) { bname = s; break; }
            if (bname.isEmpty() && !e.strings.isEmpty()) bname = e.strings.get(0);
            System.out.println("    块名称: \"" + (bname.isEmpty() ? "(位编码)" : bname) + "\"");
            if (!e.strings.isEmpty()) System.out.println("    包含字符串: " + String.join(", ", e.strings.subList(0, Math.min(4, e.strings.size()))));

            // 尝试在对象中查找坐标
            for (int off = 0; off < Math.min(200, e.size - 4); off += 8) {
                double[] c = findCoords(e, off);
                if (c != null) {
                    System.out.println(String.format("    对象内偏移 %d 处检测到坐标: (%.4f, %.4f, %.4f)", off, c[0], c[1], c[2]));
                    break;
                }
            }
            System.out.println();
        }

        // BLOCK (0x50)
        List<Entity> b50 = byType.getOrDefault("BLOCK", new ArrayList<>());
        System.out.println("  ★ BLOCK (块表, 类型码 0x50) : " + b50.size() + " 个");
        for (Entity e : b50) {
            extractStrings(e);
            System.out.println(String.format("    @0x%04X, 大小 %d 字节", e.offset, e.size));
            if (!e.strings.isEmpty()) {
                System.out.println("    包含的块名: " + String.join(", ", e.strings.subList(0, Math.min(10, e.strings.size()))));
                if (e.strings.size() > 10) System.out.println("    ... 以及另外 " + (e.strings.size() - 10) + " 个");
            }
            System.out.println();
        }

        // ENDBLK (0x31)
        List<Entity> endb = byType.getOrDefault("ENDBLK", new ArrayList<>());
        System.out.println("  ★ ENDBLK (块结束标记, 类型码 0x31) : " + endb.size() + " 个");
        System.out.println();

        // ============ 第三部分：图形实体 ============
        System.out.println("┌──────────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  第三部分：图形实体（圆/弧/直线/块引用等）                               │");
        System.out.println("└──────────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        // CIRCLE
        List<Entity> circles = byType.getOrDefault("CIRCLE", new ArrayList<>());
        System.out.println("  ★ 圆 (CIRCLE, 类型码 0x04) : " + circles.size() + " 个");
        for (int i = 0; i < circles.size(); i++) {
            Entity e = circles.get(i);
            System.out.println(String.format("    圆 #%d: @0x%04X, %d 字节", i+1, e.offset, e.size));
            // 尝试解析
            int p = e.offset + 4;  // MS(2) + BS(2) = 4 bytes
            // 常见格式: bitsize(4) + handle(variable) + ... + center(24 bytes) + radius(8 bytes)
            // 由于位编码，尝试不同偏移
            for (int off = 0; off < Math.min(50, e.size - 24); off += 4) {
                double[] c = findCoords(e, off);
                if (c != null) {
                    System.out.println(String.format("      圆心 (x,y,z) = (%.4f, %.4f, %.4f)", c[0], c[1], c[2]));
                    // 半径: 接下来8字节
                    int rp = p + off + 24;
                    if (rp + 8 < data.length) {
                        long rb = 0;
                        for (int k = 0; k < 8; k++) rb |= ((long)(data[rp + k] & 0xFF)) << (k*8);
                        double r = Double.longBitsToDouble(rb);
                        if (!Double.isNaN(r) && !Double.isInfinite(r) && Math.abs(r) > 1e-6 && Math.abs(r) < 1e5) {
                            System.out.println(String.format("      半径 = %.4f", r));
                            break;
                        }
                    }
                }
            }
            System.out.println("      十六进制 dump:");
            System.out.print(hexDump(e.offset, Math.min(48, e.size + 2)));
            System.out.println();
        }
        if (circles.isEmpty()) System.out.println("    (无)");
        System.out.println();

        // ARC
        List<Entity> arcs = byType.getOrDefault("ARC", new ArrayList<>());
        System.out.println("  ★ 圆弧 (ARC, 类型码 0x05) : " + arcs.size() + " 个");
        for (int i = 0; i < arcs.size(); i++) {
            Entity e = arcs.get(i);
            System.out.println(String.format("    圆弧 #%d: @0x%04X, %d 字节", i+1, e.offset, e.size));
            for (int off = 0; off < Math.min(40, e.size - 24); off += 4) {
                double[] c = findCoords(e, off);
                if (c != null) {
                    System.out.println(String.format("      圆心 (x,y,z) = (%.4f, %.4f, %.4f)", c[0], c[1], c[2]));
                    break;
                }
            }
            System.out.println("      十六进制 dump:");
            System.out.print(hexDump(e.offset, Math.min(48, e.size + 2)));
            System.out.println();
        }
        if (arcs.isEmpty()) System.out.println("    (无)");
        System.out.println();

        // LINE
        List<Entity> lines = byType.getOrDefault("LINE", new ArrayList<>());
        System.out.println("  ★ 直线 (LINE, 类型码 0x10) : " + lines.size() + " 个");
        for (int i = 0; i < lines.size(); i++) {
            Entity e = lines.get(i);
            System.out.println(String.format("    直线 #%d: @0x%04X, %d 字节", i+1, e.offset, e.size));
            for (int off = 0; off < Math.min(50, e.size - 48); off += 4) {
                double[] p1 = findCoords(e, off);
                double[] p2 = findCoords(e, off + 24);
                if (p1 != null && p2 != null) {
                    System.out.println(String.format("      起点: (%.4f, %.4f, %.4f)", p1[0], p1[1], p1[2]));
                    System.out.println(String.format("      终点: (%.4f, %.4f, %.4f)", p2[0], p2[1], p2[2]));
                    break;
                }
            }
        }
        if (lines.isEmpty()) System.out.println("    (无)");
        System.out.println();

        // INSERT (块引用)
        List<Entity> inserts = byType.getOrDefault("INSERT", new ArrayList<>());
        System.out.println("  ★ 块引用 (INSERT, 类型码 0x07) : " + inserts.size() + " 个");
        for (int i = 0; i < inserts.size(); i++) {
            Entity e = inserts.get(i);
            extractStrings(e);
            System.out.println(String.format("    引用 #%d: @0x%04X, %d 字节", i+1, e.offset, e.size));
            if (!e.strings.isEmpty()) {
                System.out.println("    引用的块名: " + String.join(", ", e.strings));
            }
            for (int off = 0; off < Math.min(50, e.size - 24); off += 4) {
                double[] c = findCoords(e, off);
                if (c != null) {
                    System.out.println(String.format("      插入点 (x,y,z) = (%.4f, %.4f, %.4f)", c[0], c[1], c[2]));
                    // 检查缩放比例
                    int sp = e.offset + 4 + off + 24;
                    if (sp + 24 < data.length) {
                        long[] b = new long[3];
                        for (int dim = 0; dim < 3; dim++) {
                            for (int k = 0; k < 8; k++) b[dim] |= ((long)(data[sp + dim*8 + k] & 0xFF)) << (k*8);
                        }
                        double[] sc = new double[3];
                        boolean scOk = true;
                        for (int k = 0; k < 3; k++) { sc[k] = Double.longBitsToDouble(b[k]); if(Math.abs(sc[k]) > 1e6 || Math.abs(sc[k]) < 1e-6 && sc[k] != 0) scOk = false; }
                        if (scOk && !Double.isNaN(sc[0])) System.out.println(String.format("      缩放 (x,y,z) = (%.4f, %.4f, %.4f)", sc[0], sc[1], sc[2]));
                    }
                    break;
                }
            }
            System.out.println("      十六进制 dump:");
            System.out.print(hexDump(e.offset, Math.min(64, e.size + 2)));
            System.out.println();
        }
        if (inserts.isEmpty()) System.out.println("    (无)");
        System.out.println();

        // SPLINE
        List<Entity> splines = byType.getOrDefault("SPLINE", new ArrayList<>());
        System.out.println("  ★ 样条曲线 (SPLINE, 类型码 0x2C) : " + splines.size() + " 个");
        for (int i = 0; i < splines.size(); i++) {
            Entity e = splines.get(i);
            System.out.println(String.format("    样条 #%d: @0x%04X, %d 字节", i+1, e.offset, e.size));
            for (int off = 0; off < Math.min(50, e.size - 24); off += 4) {
                double[] c = findCoords(e, off);
                if (c != null) {
                    System.out.println(String.format("      检测到坐标: (%.4f, %.4f, %.4f)", c[0], c[1], c[2]));
                    break;
                }
            }
        }
        if (splines.isEmpty()) System.out.println("    (无)");
        System.out.println();

        // ELLIPSE
        List<Entity> ellipse = byType.getOrDefault("ELLIPSE", new ArrayList<>());
        System.out.println("  ★ 椭圆 (ELLIPSE, 类型码 0x0E) : " + ellipse.size() + " 个");
        for (Entity e : ellipse) System.out.println(String.format("    @0x%04X, %d 字节", e.offset, e.size));
        if (ellipse.isEmpty()) System.out.println("    (无)");
        System.out.println();

        // MTEXT / TEXT
        List<Entity> mtexts = byType.getOrDefault("MTEXT", new ArrayList<>());
        System.out.println("  ★ 多行文字 (MTEXT, 类型码 0x1F) : " + mtexts.size() + " 个");
        for (int i = 0; i < mtexts.size(); i++) {
            Entity e = mtexts.get(i);
            extractStrings(e);
            System.out.println(String.format("    多行文字 #%d: @0x%04X, %d 字节", i+1, e.offset, e.size));
            if (!e.strings.isEmpty()) System.out.println("      文本内容 (可能): " + String.join(", ", e.strings));
            for (int off = 0; off < Math.min(40, e.size - 24); off += 4) {
                double[] c = findCoords(e, off);
                if (c != null) {
                    System.out.println(String.format("      位置 (x,y,z) = (%.4f, %.4f, %.4f)", c[0], c[1], c[2]));
                    break;
                }
            }
        }
        if (mtexts.isEmpty()) System.out.println("    (无)");
        System.out.println();

        // ============ 第四部分：所有文本字符串 ============
        System.out.println("┌──────────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  第四部分：文件中所有文本字符串（块名、样式名等）                         │");
        System.out.println("└──────────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        Map<String, Integer> strPos = new LinkedHashMap<>();
        Map<String, Integer> strCnt = new LinkedHashMap<>();
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
            if (valid && sb.length() >= 2) {
                String s = sb.toString();
                if (!s.matches("[A-Za-z_*][A-Za-z0-9_*\\-]*")) continue;
                if (!strPos.containsKey(s)) strPos.put(s, pos);
                strCnt.merge(s, 1, Integer::sum);
            }
        }

        List<Map.Entry<String, Integer>> strSorted = new ArrayList<>(strCnt.entrySet());
        strSorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        System.out.println("  共 " + strPos.size() + " 个唯一字符串，累计 " + strCnt.values().stream().mapToInt(i->i).sum() + " 次出现");
        System.out.println();
        int idx = 0;
        for (Map.Entry<String, Integer> e : strSorted) {
            idx++;
            System.out.println(String.format("    [%3d] \"%s\" × %d  @ 0x%04X (byte %d)",
                idx, e.getKey(), e.getValue(), strPos.get(e.getKey()), strPos.get(e.getKey())));
        }
        System.out.println();

        // ============ 总结 ============
        System.out.println("┌──────────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  第五部分：汇总统计                                                       │");
        System.out.println("└──────────────────────────────────────────────────────────────────────────┘");
        System.out.println();
        System.out.println("  文件: " + fileName);
        System.out.println("  版本: AutoCAD R2000 (AC1015)");
        System.out.println("  大小: " + data.length + " 字节 (" + String.format("%.1f", data.length/1024.0) + " KB)");
        System.out.println();
        System.out.println("  ──────────────────────────────────────────────────");
        String[] wanted = {"BLOCK_HEADER","BLOCK","INSERT","CIRCLE","ARC","LINE","SPLINE","ELLIPSE","MTEXT","LAYER","DICTIONARY","LAYOUT","LTYPE","STYLE"};
        int total = 0;
        for (String name : wanted) {
            int n = byType.getOrDefault(name, new ArrayList<>()).size();
            total += n;
        }
        System.out.println("  已知可识别实体: " + total + " 个");
        for (String name : wanted) {
            int n = byType.getOrDefault(name, new ArrayList<>()).size();
            if (n > 0) System.out.println(String.format("    %-18s: %d 个", name, n));
        }
        System.out.println("  ──────────────────────────────────────────────────");
        System.out.println("  已检测对象总数: " + raw.size() + " 个");
        System.out.println("  唯一文本字符串数: " + strPos.size() + " 个");
        System.out.println();

        System.out.println("  注意事项:");
        System.out.println("    1. AC1015 使用位编码方式存储实体属性，坐标/缩放等不按字节对齐");
        System.out.println("    2. 坐标值基于 8 字节 IEEE 754 双精度浮点数格式搜索得到");
        System.out.println("    3. 小型实体(<50 字节)的坐标可能完全是位编码，需完整按 ODA 规范解析");
        System.out.println("    4. 块名在 BLOCK_HEADER 和 BLOCK 对象中存储，小型 INSERT 使用句柄引用");
        System.out.println("    5. 对象之间可能存在嵌套/覆盖关系，大对象可能吞掉小对象");
        System.out.println();

        System.out.println("╔══════════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                               解析报告结束                                       ║");
        System.out.println("╚════════════════════════════════════════════════════════════════════════════════════╝");
        System.out.println();
    }
}
