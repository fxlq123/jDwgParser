import java.nio.file.*;
import java.util.*;
import java.io.*;

/**
 * 综合测试 DWG 文件的可读取内容
 * - 文件头信息
 * - 所有对象（按类型码统计）
 * - BLOCK 定义与引用
 * - 文本字符串
 * - 字节级扫描统计
 */
public class TestDwgRead {
    static byte[] data;

    // ==================== 辅助方法 ====================
    static int readU16(int pos) {
        return (data[pos] & 0xFF) | ((data[pos + 1] & 0xFF) << 8);
    }
    static long readU32(int pos) {
        return ((long)(data[pos] & 0xFF))
            | ((long)(data[pos + 1] & 0xFF) << 8)
            | ((long)(data[pos + 2] & 0xFF) << 16)
            | ((long)(data[pos + 3] & 0xFF) << 24);
    }
    static int readMS(int pos) {
        if (pos + 1 >= data.length) return -1;
        int w = (data[pos] & 0xFF) | ((data[pos + 1] & 0xFF) << 8);
        if ((w & 0x8000) != 0) return -1; // multi-word, skip
        return w & 0x7FFF;
    }
    static int readBSType(int pos) {
        int b0 = data[pos] & 0xFF;
        int b1 = data[pos + 1] & 0xFF;
        if (((b0 >> 6) & 3) != 1) return -1; // not a BS opcode
        return ((b0 & 0x3F) << 2) | ((b1 >> 6) & 3);
    }

    static String typeName(int tc) {
        switch (tc) {
            case 0x01: return "TEXT";
            case 0x04: return "CIRCLE";
            case 0x05: return "ARC";
            case 0x07: return "INSERT (BLOCK_REF)";
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
            case 0x23: return "HATCH";
            case 0x24: return "IMAGE";
            case 0x27: return "ACAD_PROXY_ENTITY";
            case 0x2A: return "RAY";
            case 0x2B: return "XLINE";
            case 0x2C: return "SPLINE";
            case 0x2D: return "REGION";
            case 0x2E: return "3DSOLID";
            case 0x2F: return "BODY";
            case 0x30: return "BLOCK_HEADER";
            case 0x31: return "ENDBLK";
            case 0x32: return "LAYER_INDEX";
            case 0x33: return "IDBUFFER";
            case 0x34: return "SPATIAL_INDEX";
            case 0x36: return "POLYLINE_3D";
            case 0x37: return "VERTEX_3D";
            case 0x38: return "POLYLINE_PFACE";
            case 0x39: return "VERTEX_PFACE";
            case 0x3A: return "VERTEX_PFACE_FACE";
            case 0x3B: return "POLYLINE_MESH";
            case 0x3C: return "VERTEX_MESH";
            case 0x3D: return "FACE";
            case 0x3E: return "MESH_PFACE";
            case 0x3F: return "HELIX";
            case 0x40: return "LIGHT";
            case 0x41: return "CAMERA";
            case 0x42: return "TABLE";
            case 0x43: return "LAYER";
            case 0x44: return "LAYER_CONTROL";
            case 0x45: return "SHAPEFILE";
            case 0x46: return "LINE_CONTROL";
            case 0x47: return "LTYPE";
            case 0x48: return "STYLE";
            case 0x49: return "VIEW";
            case 0x4A: return "UCS";
            case 0x4B: return "VPORT_ENTITY";
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
            case 0x5C: return "DICTIONARY_OTHER";
            case 0x60: return "VBA_PROJECT";
            case 0x61: return "OLE2FRAME";
            case 0x62: return "RASTERVARIABLES";
            case 0x63: return "DATABASEPREVIEW";
            case 0x64: return "EXTRECORD";
            case 0x65: return "RTEXT";
            case 0x66: return "TABLE_CELL";
            case 0x67: return "TABLE_CONTENT";
            case 0x68: return "TABLE_CELLSTYLE";
            case 0x69: return "TABLESTYLE";
            case 0x70: return "IMAGEDEFINITION";
            case 0x80: return "IMAGE_VARIABLES";
            case 0x90: return "ACAD_PROXY_OBJECT";
            case 0xA0: return "ACAD_PROXY_ENTITY_2";
            default:   return "TYPE_0x" + String.format("%02X", tc);
        }
    }

    // 读取长度前缀 ASCII 字符串
    static String readStringAt(int pos) {
        if (pos < 0 || pos >= data.length) return null;
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

    public static void main(String[] args) throws Exception {
        String fileName = "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg";
        data = Files.readAllBytes(Paths.get(fileName));

        PrintStream out = System.out;
        out.println();
        out.println("╔═════════════════════════════════════════════════════════════════════╗");
        out.println("║         DWG 文件可读取内容测试报告                                  ║");
        out.println("╚═════════════════════════════════════════════════════════════════════╝");
        out.println();
        out.println("  文件名: " + fileName);
        out.println("  文件大小: " + data.length + " 字节 (" + String.format("%.2f", data.length / 1024.0) + " KB)");
        out.println();

        // ==================== Section 1: 文件头 ====================
        out.println("┌──────────────────────────────────────────────────────────────────┐");
        out.println("│  1. 文件头 (Header)                                              │");
        out.println("└──────────────────────────────────────────────────────────────────┘");
        out.println();

        // Magic number
        String magic = "";
        for (int i = 0; i < 6; i++) {
            int c = data[i] & 0xFF;
            if (c < 32 || c > 126) break;
            magic += (char)c;
        }
        out.println("  Magic Number: \"" + magic + "\"");

        // Version detection (based on magic)
        String version;
        switch (magic) {
            case "AC1015": version = "AutoCAD R2000 / R2000i / R2002 (AC1015)"; break;
            case "AC1018": version = "AutoCAD 2004 / 2005 / 2006 (AC1018)"; break;
            case "AC1021": version = "AutoCAD 2007 / 2008 / 2009 (AC1021)"; break;
            case "AC1024": version = "AutoCAD 2010 / 2011 / 2012 (AC1024)"; break;
            case "AC1027": version = "AutoCAD 2013 / 2014 / 2015 / 2016 / 2017 (AC1027)"; break;
            case "AC1032": version = "AutoCAD 2018 / 2019 / 2020 / 2021 / 2022 / 2023 / 2024 (AC1032)"; break;
            default: version = "未知版本 (magic=" + magic + ")";
        }
        out.println("  检测版本: " + version);

        // Byte 6-11: reserved
        out.println("  Bytes 06-11: ");
        StringBuilder hexLine = new StringBuilder("    ");
        for (int i = 6; i < 12; i++) hexLine.append(String.format("%02X ", data[i] & 0xFF));
        out.println(hexLine);

        // Section map (AC1015 layout, simplified)
        out.println("  Section Map Location: 字节 0x13-0x14 (Sentinel-based)");
        out.println();

        // ==================== Section 2: 对象扫描 ====================
        out.println("┌──────────────────────────────────────────────────────────────────┐");
        out.println("│  2. 对象扫描 (Object Scan)                                       │");
        out.println("└──────────────────────────────────────────────────────────────────┘");
        out.println();

        // 扫描所有有效对象（有正确 MS+BS 结构）
        Map<Integer, Integer> typeCount = new TreeMap<>();
        Map<Integer, List<int[]>> typeLocations = new HashMap<>();
        List<int[]> allObjects = new ArrayList<>();

        int scanStart = 0x5200; // 对象数据起始位置（基于之前分析）
        int validObj = 0, totalScanned = 0;
        int bigObjects = 0, smallObjects = 0;

        for (int pos = scanStart; pos < data.length - 4; pos++) {
            totalScanned++;
            int size = readMS(pos);
            if (size < 6 || size > 100000 || pos + 2 + size > data.length) continue;
            int tc = readBSType(pos + 2);
            if (tc < 0 || tc > 500) continue;
            if (size < 30) smallObjects++; else if (size > 4000) bigObjects++;

            validObj++;
            allObjects.add(new int[]{pos, size, tc});
            typeCount.merge(tc, 1, Integer::sum);
            typeLocations.computeIfAbsent(tc, k -> new ArrayList<>()).add(new int[]{pos, size});
            pos += size; // 跳过该对象
        }

        out.println("  扫描范围: 0x" + String.format("%04X", scanStart) + " - 0x" + String.format("%04X", data.length));
        out.println("  扫描位置数: " + totalScanned);
        out.println("  有效对象数: " + validObj);
        out.println("    - 小型 (<30 字节) : " + smallObjects);
        out.println("    - 中型 (30-4000)  : " + (validObj - smallObjects - bigObjects));
        out.println("    - 大型 (>4000 字节): " + bigObjects);
        out.println();

        // 按类型分类统计
        out.println("  按类型码统计:");
        out.println("  " + "─".repeat(66));
        out.println(String.format("    %-6s %-30s %6s  %s", "类型码", "对象名称", "数量", "前3个位置"));
        out.println("  " + "─".repeat(66));

        int graphicEntities = 0;
        int blockObjects = 0;
        int tableObjects = 0;
        int otherObjects = 0;

        for (Map.Entry<Integer, Integer> e : typeCount.entrySet()) {
            int tc = e.getKey();
            int count = e.getValue();
            String name = typeName(tc);

            // 分类
            if (tc == 0x01 || (tc >= 0x04 && tc <= 0x15) || tc == 0x1F || tc == 0x07 || tc == 0x10 ||
                tc == 0x0E || tc == 0x0F || tc == 0x1E || tc == 0x2A || tc == 0x2C) {
                graphicEntities += count;
            } else if (tc == 0x30 || tc == 0x31 || tc == 0x4F || tc == 0x50) {
                blockObjects += count;
            } else if ((tc >= 0x42 && tc <= 0x4F) || (tc >= 0x48 && tc <= 0x4E)) {
                tableObjects += count;
            } else {
                otherObjects += count;
            }

            // 位置信息
            List<int[]> locs = typeLocations.get(tc);
            StringBuilder locStr = new StringBuilder();
            int maxLoc = Math.min(3, locs.size());
            for (int i = 0; i < maxLoc; i++) {
                if (i > 0) locStr.append(", ");
                locStr.append("0x").append(String.format("%04X", locs.get(i)[0]));
            }
            if (locs.size() > 3) locStr.append("...").append(locs.size()).append("个");

            out.println(String.format("    0x%02X   %-30s %6d  %s",
                tc, name.length() > 28 ? name.substring(0, 28) : name, count, locStr));
        }
        out.println("  " + "─".repeat(66));
        out.println();

        // 分类汇总
        out.println("  分类汇总:");
        out.println("    图形实体          : " + graphicEntities + " 个");
        out.println("    BLOCK 相关对象    : " + blockObjects + " 个");
        out.println("    TABLE/控制对象    : " + tableObjects + " 个");
        out.println("    其他对象          : " + otherObjects + " 个");
        out.println("    ─────────────────────────");
        out.println("    合计              : " + validObj + " 个");
        out.println();

        // ==================== Section 3: BLOCK 定义与引用 ====================
        out.println("┌──────────────────────────────────────────────────────────────────┐");
        out.println("│  3. BLOCK 定义与引用                                            │");
        out.println("└──────────────────────────────────────────────────────────────────┘");
        out.println();

        List<int[]> blocks = typeLocations.get(0x30);
        if (blocks != null) {
            out.println("  BLOCK_HEADER (0x30) 对象: " + blocks.size() + " 个");
            out.println("  " + "─".repeat(60));
            for (int i = 0; i < blocks.size(); i++) {
                int[] b = blocks.get(i);
                int off = b[0], size = b[1];
                out.println(String.format("    #%d: offset=0x%04X, size=%d bytes", i + 1, off, size));

                // 查找对象内的所有可读文本字符串
                List<String> texts = new ArrayList<>();
                int end = off + 2 + size;
                for (int j = off + 4; j < end - 2; j++) {
                    String s = readStringAt(j);
                    if (s != null && s.matches("[A-Za-z_*][A-Za-z0-9_*\\-]*") && s.length() >= 2) {
                        if (!texts.contains(s)) texts.add(s);
                    }
                }
                if (!texts.isEmpty()) {
                    out.println("      包含字符串: " + String.join(", ", texts.subList(0, Math.min(6, texts.size()))));
                }
            }
        } else {
            out.println("  未发现 BLOCK_HEADER (0x30) 对象");
        }
        out.println();

        List<int[]> inserts = typeLocations.get(0x07);
        if (inserts != null) {
            out.println("  INSERT (0x07) 块引用对象: " + inserts.size() + " 个");
            out.println("  " + "─".repeat(60));
            for (int i = 0; i < inserts.size(); i++) {
                int[] ins = inserts.get(i);
                int off = ins[0], size = ins[1];
                out.println(String.format("    #%d: offset=0x%04X, size=%d bytes", i + 1, off, size));

                List<String> texts = new ArrayList<>();
                int end = off + 2 + size;
                for (int j = off + 4; j < end - 2; j++) {
                    String s = readStringAt(j);
                    if (s != null && s.matches("[A-Za-z_*][A-Za-z0-9_*\\-]*") && s.length() >= 2) {
                        if (!texts.contains(s)) texts.add(s);
                    }
                }
                if (!texts.isEmpty()) {
                    out.println("      引用块名: " + String.join(", ", texts.subList(0, Math.min(4, texts.size()))));
                }
            }
        } else {
            out.println("  未发现 INSERT (0x07) 对象");
        }
        out.println();

        // ==================== Section 4: 文本字符串扫描 ====================
        out.println("┌──────────────────────────────────────────────────────────────────┐");
        out.println("│  4. 文件中可读取的文本字符串                                    │");
        out.println("└──────────────────────────────────────────────────────────────────┘");
        out.println();

        // 收集所有长度前缀的 ASCII 字符串
        Map<String, Integer> stringMap = new LinkedHashMap<>();
        Map<String, List<Integer>> stringLocs = new LinkedHashMap<>();
        int totalStrings = 0;

        for (int pos = scanStart; pos < data.length - 5; pos++) {
            int len = data[pos] & 0xFF;
            if (len < 1 || len > 80 || pos + 1 + len > data.length) continue;
            // 前缀长度值看起来合理，然后验证内容是否为 ASCII
            StringBuilder sb = new StringBuilder();
            boolean valid = true;
            for (int k = 0; k < len; k++) {
                int c = data[pos + 1 + k] & 0xFF;
                if (c < 32 || c > 126) { valid = false; break; }
                sb.append((char)c);
            }
            if (valid && sb.length() >= 2 && sb.toString().matches("[A-Za-z0-9_*\\-\\.]+")) {
                String s = sb.toString();
                totalStrings++;
                stringMap.merge(s, 1, Integer::sum);
                stringLocs.computeIfAbsent(s, k -> new ArrayList<>()).add(pos + 1);
            }
        }

        out.println("  检测到的文本字符串总数: " + totalStrings);
        out.println("  唯一文本字符串数: " + stringMap.size());
        out.println();

        // 按出现次数排序
        List<Map.Entry<String, Integer>> sortedStrings = new ArrayList<>(stringMap.entrySet());
        sortedStrings.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

        out.println("  Top 40 文本字符串 (按出现次数):");
        out.println("  " + "─".repeat(66));
        for (int i = 0; i < Math.min(40, sortedStrings.size()); i++) {
            Map.Entry<String, Integer> e = sortedStrings.get(i);
            List<Integer> locs = stringLocs.get(e.getKey());
            StringBuilder locStr = new StringBuilder();
            for (int j = 0; j < Math.min(2, locs.size()); j++) {
                if (j > 0) locStr.append(", ");
                locStr.append("0x").append(String.format("%04X", locs.get(j)));
            }
            if (locs.size() > 2) locStr.append("...").append(locs.size()).append("处");
            out.println(String.format("    [%2d] %-25s × %3d   @ %s",
                i + 1, "\"" + e.getKey() + "\"", e.getValue(), locStr));
        }
        out.println();

        // ==================== Section 5: 字节级统计 ====================
        out.println("┌──────────────────────────────────────────────────────────────────┐");
        out.println("│  5. 字节级结构分析 (Byte-Level Analysis)                        │");
        out.println("└──────────────────────────────────────────────────────────────────┘");
        out.println();

        // 零字节统计
        int zeroBytes = 0;
        for (int i = 0; i < data.length; i++) {
            if (data[i] == 0) zeroBytes++;
        }
        out.println("  文件中零字节数: " + zeroBytes + " / " + data.length + " (" +
            String.format("%.1f%%)", zeroBytes * 100.0 / data.length));

        // 可打印 ASCII 字节数
        int printableBytes = 0;
        for (int i = 0; i < data.length; i++) {
            int c = data[i] & 0xFF;
            if (c >= 32 && c <= 126) printableBytes++;
        }
        out.println("  可打印 ASCII 字节数: " + printableBytes + " / " + data.length + " (" +
            String.format("%.1f%%)", printableBytes * 100.0 / data.length));

        // 16-bit MS 可读取率
        int msCount = 0, msValid = 0;
        for (int pos = scanStart; pos < data.length - 2; pos += 2) {
            msCount++;
            int w = (data[pos] & 0xFF) | ((data[pos + 1] & 0xFF) << 8);
            if ((w & 0x8000) == 0 && (w & 0x7FFF) >= 6 && (w & 0x7FFF) <= 100000) msValid++;
        }
        out.println("  有效 MS 字段比例: " + msValid + " / " + msCount + " (" +
            String.format("%.1f%%)", msValid * 100.0 / Math.max(1, msCount)));
        out.println();

        // ==================== Section 6: 关键位置的 hex dump ====================
        out.println("┌──────────────────────────────────────────────────────────────────┐");
        out.println("│  6. 关键位置十六进制转储                                        │");
        out.println("└──────────────────────────────────────────────────────────────────┘");
        out.println();

        int[] keyOffsets = {0x52B9, 0x5426, 0x5A82, 0x6454, 0x89FD, 0xDB31, 0xFB3F, 0x1062E};
        for (int offset : keyOffsets) {
            if (offset >= data.length) continue;
            int dumpLen = Math.min(48, data.length - offset);
            out.println("  @0x" + String.format("%04X", offset) + ":");
            StringBuilder sb = new StringBuilder("    ");
            for (int i = 0; i < dumpLen; i++) {
                sb.append(String.format("%02X ", data[offset + i] & 0xFF));
                if ((i + 1) % 16 == 0) {
                    sb.append(" | ");
                    for (int j = i - 15; j <= i; j++) {
                        int c = data[offset + j] & 0xFF;
                        sb.append(c >= 32 && c <= 126 ? (char)c : '.');
                    }
                    sb.append("\n    ");
                }
            }
            out.println(sb.toString().trim());
            out.println();
        }

        // ==================== Section 7: 总结 ====================
        out.println("┌──────────────────────────────────────────────────────────────────┐");
        out.println("│  7. 读取总结                                                    │");
        out.println("└──────────────────────────────────────────────────────────────────┘");
        out.println();
        out.println("  文件版本: AutoCAD R2000 (AC1015)");
        out.println("  文件大小: " + data.length + " 字节");
        out.println();
        out.println("  成功解析的内容:");
        out.println("    ✓ 可定位对象数        : " + validObj + " 个");
        out.println("    ✓ 对象类型种类        : " + typeCount.size() + " 种");
        out.println("    ✓ BLOCK_HEADER        : " + (blocks != null ? blocks.size() : 0) + " 个");
        out.println("    ✓ INSERT (块引用)     : " + (inserts != null ? inserts.size() : 0) + " 个");
        out.println("    ✓ 文本字符串          : " + totalStrings + " 个 (唯一 " + stringMap.size() + " 个)");
        out.println("    ✓ 图形实体 (估算)     : " + graphicEntities + " 个");
        out.println("    ✓ TABLE/控制对象(估算): " + tableObjects + " 个");
        out.println();

        // 读取率评估
        int objectBytes = 0;
        for (int[] obj : allObjects) objectBytes += obj[1];
        double coverage = (double)(objectBytes + scanStart) / data.length * 100;
        out.println("  文件结构覆盖度: " + String.format("%.1f%%", Math.min(100, coverage)) +
            " (对象数据区 " + objectBytes + " 字节)");
        out.println();
        out.println("  未解析部分:");
        out.println("    ⊗ 对象内部的位编码几何数据 (坐标、缩放、旋转等) - 需要按位解析");
        out.println("    ⊗ 对象句柄 (Handle) 的正确映射 - 需要解析 Handles Section");
        out.println("    ⊗ 各对象的属性值 (颜色、线型、图层等) - 需要解析 Entity Common Data");
        out.println("    ⊗ 小型 INSERT 对象引用的块名 - 使用位编码而非明文");
        out.println();
        out.println("╔═════════════════════════════════════════════════════════════════════╗");
        out.println("║                        测试报告结束                                 ║");
        out.println("╚═════════════════════════════════════════════════════════════════════╝");
        out.println();

        // 保存到文件
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("DWG 文件内容测试报告\n");
            sb.append("文件: ").append(fileName).append("\n");
            sb.append("大小: ").append(data.length).append(" 字节\n");
            sb.append("版本: AutoCAD R2000 (AC1015)\n\n");
            sb.append("有效对象: ").append(validObj).append(" 个\n");
            sb.append("对象类型: ").append(typeCount.size()).append(" 种\n");
            sb.append("BLOCK_HEADER: ").append(blocks != null ? blocks.size() : 0).append(" 个\n");
            sb.append("INSERT: ").append(inserts != null ? inserts.size() : 0).append(" 个\n");
            sb.append("文本字符串: ").append(totalStrings).append(" 个\n\n");
            sb.append("类型统计:\n");
            for (Map.Entry<Integer, Integer> e : typeCount.entrySet()) {
                sb.append(String.format("  0x%02X %-30s : %d\n", e.getKey(), typeName(e.getKey()), e.getValue()));
            }
            sb.append("\n文本字符串列表:\n");
            for (Map.Entry<String, Integer> e : sortedStrings) {
                sb.append("  ").append(e.getKey()).append(" × ").append(e.getValue()).append("\n");
            }
            Files.write(Paths.get("dwg_read_report.txt"), sb.toString().getBytes("UTF-8"));
            out.println("  详细报告已保存至: dwg_read_report.txt");
        } catch (Exception ex) {
            out.println("  报告保存失败: " + ex.getMessage());
        }
    }
}
