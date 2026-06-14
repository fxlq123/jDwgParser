import java.nio.file.*;
import java.util.*;

/**
 * DWG R2000 最终完整解析
 * 两段式扫描：先找顶层对象，再在 BLOCK 范围内扫描内部对象
 */
public class FinalDwgParser {
    static byte[] data;

    static class Entity {
        int offset, size, type;
        String typeName;
        List<String> strings = new ArrayList<>();
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
        int w = (data[pos] & 0xFF) | ((data[pos+1] & 0xFF) << 8);
        if ((w & 0x8000) != 0) return -1;
        int s = w & 0x7FFF;
        return (s >= 6 && s <= 30000) ? s : -1;
    }

    static int readBSType(int pos) {
        int b0 = data[pos] & 0xFF, b1 = data[pos+1] & 0xFF;
        if (((b0 >> 6) & 3) != 1) return -1;
        return ((b0 & 0x3F) << 2) | ((b1 >> 6) & 3);
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

    // 在指定范围内扫描对象，可选是否使用字节标记
    static List<Entity> scanRange(int start, int end, boolean markOverlap) {
        List<Entity> result = new ArrayList<>();
        boolean[] inObj = new boolean[data.length];

        for (int pos = start; pos < end - 4; pos++) {
            if (markOverlap && inObj[pos]) continue;
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
            // 提取字符串
            int strEnd = Math.min(pos + 2 + size, pos + 2000);
            for (int sp = pos + 4; sp < strEnd - 3; sp++) {
                int len = data[sp] & 0xFF;
                if (len < 2 || len > 200 || sp + 1 + len > strEnd) continue;
                StringBuilder sb = new StringBuilder();
                boolean ok = true;
                for (int i = 0; i < len; i++) { int c = data[sp+1+i]&0xFF; if(c<32||c>126){ok=false;break;} sb.append((char)c); }
                if (ok) {
                    String s = sb.toString();
                    if (s.matches("[A-Za-z_*][A-Za-z0-9_*\\-]*") && !e.strings.contains(s)) e.strings.add(s);
                }
            }
            result.add(e);
            if (markOverlap) for (int j = pos; j < pos + 2 + size && j < data.length; j++) inObj[j] = true;
        }
        return result;
    }

    public static void main(String[] args) throws Exception {
        String fileName = "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg";
        data = Files.readAllBytes(Paths.get(fileName));

        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║              DWG 图形实体 完整解析报告                                          ║");
        System.out.println("║   文件: 210-83-C30302 拨杆座（改2024.06.06）--30件.DWG                         ║");
        System.out.println("║   格式: AutoCAD R2000 (AC1015)  大小: " + String.format("%6d", data.length) + " 字节 (" + String.format("%.1f", data.length/1024.0) + " KB)               ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════════════════════╝");
        System.out.println();

        // ============ 第一段: 顶层扫描（含重叠标记） ============
        List<Entity> topLevel = scanRange(0x5200, data.length, true);
        System.out.println("┌──────────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  第一部分: 顶层对象扫描（共 " + topLevel.size() + " 个）                                                 │");
        System.out.println("└──────────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        Map<String, List<Entity>> byType1 = new LinkedHashMap<>();
        for (Entity e : topLevel) byType1.computeIfAbsent(e.typeName, k -> new ArrayList<>()).add(e);

        List<Map.Entry<String, List<Entity>>> sorted1 = new ArrayList<>(byType1.entrySet());
        sorted1.sort((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()));

        System.out.println("  按类型分布:");
        for (Map.Entry<String, List<Entity>> e : sorted1) {
            System.out.print(String.format("    %-20s : %3d 个   ", e.getKey(), e.getValue().size()));
            for (int i = 0; i < Math.min(2, e.getValue().size()); i++) {
                Entity ent = e.getValue().get(i);
                System.out.print(String.format("@0x%04X(%dB)", ent.offset, ent.size));
                if (!ent.strings.isEmpty()) System.out.print(" \"" + ent.strings.get(0) + "\"");
                if (i == 0) System.out.print("; ");
            }
            System.out.println();
        }
        System.out.println();

        // ============ 第二段: 无重叠标记扫描，找所有对象 ============
        System.out.println("┌──────────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  第二部分: 无重叠标记扫描（找出被大对象覆盖的 INSERT）                      │");
        System.out.println("└──────────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        // 找出 BLOCK 对象的范围，专门扫描该区域内的 INSERT
        int blockStart = -1, blockEnd = -1;
        for (Entity e : topLevel) if (e.type == 0x50) { blockStart = e.offset; blockEnd = e.offset + 2 + e.size; break; }
        if (blockStart > 0) {
            System.out.println(String.format("  BLOCK 对象范围: @0x%04X - @0x%04X (%d 字节)", blockStart, blockEnd, blockEnd-blockStart));
            System.out.println("  在 BLOCK 范围内扫描 INSERT 对象:");
            System.out.println();
            List<Entity> inBlock = scanRange(blockStart, blockEnd, false);
            Map<Integer, Integer> counts = new HashMap<>();
            Map<Integer, List<Entity>> typeObjs = new HashMap<>();
            for (Entity e : inBlock) {
                counts.merge(e.type, 1, Integer::sum);
                typeObjs.computeIfAbsent(e.type, k-> new ArrayList<>()).add(e);
            }
            for (Map.Entry<Integer, Integer> c : counts.entrySet()) {
                System.out.println(String.format("    类型码 0x%02X (%s): %d 个",
                    c.getKey(), typeName(c.getKey()), c.getValue()));
            }
            System.out.println();

            // 打印 INSERT 对象
            List<Entity> insertsInBlock = typeObjs.get(0x07);
            if (insertsInBlock != null && !insertsInBlock.isEmpty()) {
                System.out.println("  INSERT 详情 (" + insertsInBlock.size() + " 个):");
                for (int i = 0; i < insertsInBlock.size(); i++) {
                    Entity e = insertsInBlock.get(i);
                    System.out.println(String.format("    INSERT #%d: @0x%04X (%d 字节) 字符串: %s",
                        i+1, e.offset, e.size,
                        e.strings.isEmpty() ? "(无)" : String.join(", ", e.strings.subList(0, Math.min(2, e.strings.size())))));
                    System.out.print(hexDump(e.offset, Math.min(40, e.size + 2)));
                }
                System.out.println();
            }

            // 打印 CIRCLE 详情
            List<Entity> circlesInBlock = typeObjs.get(0x04);
            if (circlesInBlock != null && !circlesInBlock.isEmpty()) {
                System.out.println("  CIRCLE (圆) 详情 (" + circlesInBlock.size() + " 个):");
                for (int i = 0; i < circlesInBlock.size(); i++) {
                    Entity e = circlesInBlock.get(i);
                    System.out.println(String.format("    圆 #%d: @0x%04X (%d 字节)", i+1, e.offset, e.size));
                    System.out.print(hexDump(e.offset, Math.min(48, e.size + 2)));
                }
                System.out.println();
            }

            // 打印 ARC 详情
            List<Entity> arcsInBlock = typeObjs.get(0x05);
            if (arcsInBlock != null && !arcsInBlock.isEmpty()) {
                System.out.println("  ARC (圆弧) 详情 (" + arcsInBlock.size() + " 个):");
                for (int i = 0; i < arcsInBlock.size(); i++) {
                    Entity e = arcsInBlock.get(i);
                    System.out.println(String.format("    弧 #%d: @0x%04X (%d 字节)", i+1, e.offset, e.size));
                    System.out.print(hexDump(e.offset, Math.min(48, e.size + 2)));
                }
                System.out.println();
            }

            // LINE (0x10)
            List<Entity> linesInBlock = typeObjs.get(0x10);
            if (linesInBlock != null && !linesInBlock.isEmpty()) {
                System.out.println("  LINE (直线) 详情 (" + linesInBlock.size() + " 个):");
                for (int i = 0; i < linesInBlock.size(); i++) {
                    Entity e = linesInBlock.get(i);
                    System.out.println(String.format("    直线 #%d: @0x%04X (%d 字节)", i+1, e.offset, e.size));
                    System.out.print(hexDump(e.offset, Math.min(48, e.size + 2)));
                }
                System.out.println();
            }

            // BLOCK_HEADER
            List<Entity> bhsInBlock = typeObjs.get(0x30);
            if (bhsInBlock != null && !bhsInBlock.isEmpty()) {
                System.out.println("  BLOCK_HEADER 详情 (" + bhsInBlock.size() + " 个):");
                for (int i = 0; i < bhsInBlock.size(); i++) {
                    Entity e = bhsInBlock.get(i);
                    System.out.println(String.format("    块 #%d: @0x%04X (%d 字节)  字符串: %s",
                        i+1, e.offset, e.size,
                        e.strings.isEmpty() ? "(位编码)" : String.join(", ", e.strings.subList(0, Math.min(3, e.strings.size())))));
                }
                System.out.println();
            }
        } else {
            System.out.println("  未找到 BLOCK (0x50) 对象");
        }
        System.out.println();

        // ============ 第三段: 全文件无重叠扫描 ============
        System.out.println("┌──────────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  第三部分: 全文件无重叠标记扫描（完整对象列表）                            │");
        System.out.println("└──────────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        List<Entity> all = scanRange(0x5200, data.length, false);
        System.out.println("  共扫描到 " + all.size() + " 个对象");
        System.out.println();

        Map<String, List<Entity>> byType2 = new TreeMap<>();
        for (Entity e : all) byType2.computeIfAbsent(e.typeName, k -> new ArrayList<>()).add(e);

        // 统计表格
        System.out.println("  ╔═══════════════════════════════════════════════════════════════╗");
        System.out.println("  ║    类型名称                  类型码   数量   主要位置+字符串    ║");
        System.out.println("  ╠═══════════════════════════════════════════════════════════════╣");
        int total = 0;
        for (Map.Entry<String, List<Entity>> e : byType2.entrySet()) {
            total += e.getValue().size();
            List<Entity> lst = e.getValue();
            // 找一个有字符串的代表
            Entity sample = lst.get(0);
            for (Entity ent : lst) if (!ent.strings.isEmpty()) { sample = ent; break; }
            System.out.print(String.format("  ║    %-22s    0x%02X    %3d    ",
                e.getKey(), lst.get(0).type, lst.size()));
            if (lst.size() <= 4) {
                StringBuilder sb = new StringBuilder();
                for (Entity ent : lst) {
                    sb.append(String.format("@0x%04X(%dB)", ent.offset, ent.size));
                    if (!ent.strings.isEmpty()) sb.append("[\"").append(ent.strings.get(0)).append("\"]");
                    sb.append(" ");
                }
                System.out.print(String.format("%-28s", sb.toString()));
            } else {
                String s1 = String.format("@0x%04X(%dB)[%s]", sample.offset, sample.size,
                    sample.strings.isEmpty() ? "-" : sample.strings.get(0));
                System.out.print(String.format("%-28s", s1 + "...+" + (lst.size()-1)));
            }
            System.out.println("║");
        }
        System.out.println("  ╠═══════════════════════════════════════════════════════════════╣");
        System.out.println(String.format("  ║    合计                      ---     %4d                    ║", total));
        System.out.println("  ╚═══════════════════════════════════════════════════════════════╝");
        System.out.println();

        // ============ 第四段: 所有文本字符串 ============
        System.out.println("┌──────────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  第四部分: 文件中所有文本字符串（块名/样式/属性等）                         │");
        System.out.println("└──────────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        Map<String, Integer> strPos = new LinkedHashMap<>();
        Map<String, Integer> strCnt = new LinkedHashMap<>();
        for (int pos = 0x5200; pos < data.length - 3; pos++) {
            int len = data[pos] & 0xFF;
            if (len < 2 || len > 200 || pos + 1 + len > data.length) continue;
            StringBuilder sb = new StringBuilder();
            boolean valid = true;
            for (int i = 0; i < len; i++) { int c = data[pos+1+i]&0xFF; if(c<32||c>126){valid=false;break;} sb.append((char)c); }
            if (valid && sb.toString().matches("[A-Za-z_*][A-Za-z0-9_*\\-]*")) {
                String s = sb.toString();
                if (!strPos.containsKey(s)) strPos.put(s, pos);
                strCnt.merge(s, 1, Integer::sum);
            }
        }

        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(strCnt.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        System.out.println("  " + strPos.size() + " 个唯一字符串，累计 " + strCnt.values().stream().mapToInt(i->i).sum() + " 次出现:");
        System.out.println();
        int idx = 0;
        for (Map.Entry<String, Integer> e : sorted) {
            idx++;
            System.out.println(String.format("    [%3d] \"%s\" × %d  @ 0x%04X (byte %d)",
                idx, e.getKey(), e.getValue(), strPos.get(e.getKey()), strPos.get(e.getKey())));
        }
        System.out.println();

        // ============ 总结 ============
        System.out.println("┌──────────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  第五部分: 汇总（最终）                                                   │");
        System.out.println("└──────────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        System.out.println("  文件: " + fileName);
        System.out.println("  版本: AutoCAD R2000 (AC1015)");
        System.out.println("  大小: " + data.length + " 字节 (" + String.format("%.1f", data.length/1024.0) + " KB)");
        System.out.println();
        System.out.println("  ╔═══════════════════════════════════════════╗");
        System.out.println("  ║    图形实体类型        数量  说明         ║");
        System.out.println("  ╠═══════════════════════════════════════════╣");

        String[][] report = {
            {"BLOCK_HEADER (块表头)", "0x30", "块定义的头部"},
            {"BLOCK (块表)", "0x50", "包含所有块定义"},
            {"ENDBLK (块结束)", "0x31", "每个块的结束标记"},
            {"INSERT (块引用)", "0x07", "引用块定义到图形"},
            {"CIRCLE (圆)", "0x04", "圆心+半径"},
            {"ARC (圆弧)", "0x05", "圆心+半径+起/止角"},
            {"LINE (直线)", "0x10", "起点+终点"},
            {"SPLINE (样条曲线)", "0x2C", "控制点+拟合点"},
            {"ELLIPSE (椭圆)", "0x0E", "中心+轴向量+比例"},
            {"MTEXT (多行文字)", "0x1F", "位置+宽+文本"},
            {"LWPOLYLINE (轻量多段线)", "0x0F", "顶点列表"},
            {"DIMENSION (标注)", "0x15", "尺寸标注"},
        };
        int totalGraphic = 0;
        for (String[] r : report) {
            int n = byType2.getOrDefault(r[0], new ArrayList<>()).size();
            totalGraphic += n;
            System.out.println(String.format("  ║    %-22s %3d  %s", r[0], n, r[2]));
        }
        System.out.println("  ╠═══════════════════════════════════════════╣");
        System.out.println(String.format("  ║    图形实体小计          %4d              ║", totalGraphic));
        System.out.println("  ╚═══════════════════════════════════════════╝");
        System.out.println();

        System.out.println("  表/控制对象:");
        for (String s : new String[]{"LAYER (图层)", "LTYPE (线型)", "STYLE (样式)", "DICTIONARY (字典)",
            "LAYOUT (布局)", "BLOCK_CONTROL (块控制)"}) {
            String key = s.split(" ")[0];
            int n = byType2.getOrDefault(key, new ArrayList<>()).size();
            if (n > 0) System.out.println(String.format("    %-22s: %d 个", s, n));
        }
        System.out.println();

        System.out.println("  ⚠ 注意: 坐标/半径等几何数据在 AC1015 格式中以位编码存储,");
        System.out.println("     不是字节对齐的双精度浮点数,需按 ODA 规范完整逐位解析。");
        System.out.println();

        System.out.println("╔══════════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                               解析报告结束                                       ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════════════════════╝");
        System.out.println();
    }
}
