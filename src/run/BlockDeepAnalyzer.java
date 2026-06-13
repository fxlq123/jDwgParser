package run;

import io.dwg.api.DwgDocument;
import io.dwg.api.DwgReader;
import io.dwg.core.version.DwgVersion;
import io.dwg.entities.DwgEntity;
import io.dwg.entities.DwgObject;
import io.dwg.entities.concrete.DwgBlockHeader;
import io.dwg.entities.concrete.DwgInsert;
import io.dwg.entities.concrete.DwgLayer;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * BLOCK / INSERT 深度分析工具
 * 用法: java -cp build/classes run.BlockDeepAnalyzer samples/你的文件.dwg
 *
 * 功能:
 *   1) 列出版本信息 + 对象总数
 *   2) 列出所有 BLOCK_HEADER (块定义),包括名称、基点、flags、xref
 *   3) 列出所有 INSERT (块引用),包括引用的块名、插入点、缩放、旋转
 *   4) 统计每个块被引用的次数
 *   5) 输出实体类型分布
 *   6) 列出图层
 */
public class BlockDeepAnalyzer {

    public static void main(String[] args) {
        String filePath;
        if (args.length > 0) {
            filePath = args[0];
        } else {
            // 默认尝试用户的文件名
            String[] candidates = {
                "samples/210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg",
                "samples/210-83-C30302 拨杆座.dwg",
                "samples/2007/Constraints.dwg",
            };
            filePath = null;
            for (String c : candidates) {
                if (new File(c).exists()) { filePath = c; break; }
            }
            if (filePath == null) {
                System.out.println("ERROR: 未找到待测试的 DWG 文件。");
                System.out.println("用法: java -cp build/classes run.BlockDeepAnalyzer <路径/到/文件.dwg>");
                System.out.println();
                System.out.println("请先把文件复制到 samples/ 目录,或者通过命令行参数指定完整路径。");
                return;
            }
        }

        Path p = Paths.get(filePath);
        if (!p.toFile().exists()) {
            System.out.println("ERROR: 文件不存在: " + filePath);
            return;
        }

        System.out.println("=".repeat(100));
        System.out.println("BLOCK / INSERT 深度分析 — " + filePath);
        System.out.println("=".repeat(100));
        System.out.println();

        DwgDocument doc;
        try {
            doc = DwgReader.defaultReader().open(p);
        } catch (Exception e) {
            System.out.println("打开失败: " + e);
            e.printStackTrace();
            return;
        }

        // 1) 基本信息
        System.out.println("【1】基本信息");
        System.out.println("-".repeat(100));
        System.out.printf("  DWG 版本     : %s (%s)%n", doc.version(), versionCode(doc.version()));
        int total = doc.objectMap() == null ? 0 : doc.objectMap().size();
        System.out.printf("  对象总数     : %d%n", total);
        System.out.printf("  实体总数     : %d%n", doc.entities() == null ? 0 : doc.entities().size());
        System.out.println();

        Map<Long, DwgObject> map = doc.objectMap();
        if (map == null || map.isEmpty()) {
            System.out.println("对象映射为空,无法继续分析。");
            return;
        }

        // 2) 块定义
        System.out.println("【2】块定义 (BLOCK_HEADER, 类型 0x30 / 48)");
        System.out.println("-".repeat(100));
        List<DwgBlockHeader> blocks = new ArrayList<>();
        for (DwgObject o : map.values()) {
            if (o instanceof DwgBlockHeader bh) blocks.add(bh);
        }
        Map<Long, String> handleToBlockName = new HashMap<>();
        if (blocks.isEmpty()) {
            System.out.println("  (未解析到任何 BLOCK_HEADER)");
        } else {
            System.out.printf("  %-6s %-10s %-32s %-10s %-20s %s%n", "序号", "HANDLE", "块名", "FLAGS", "基点", "XREF路径");
            int n = 0;
            for (DwgBlockHeader bh : blocks) {
                n++;
                String name = bh.blockName();
                if (name == null || name.isEmpty()) name = "<未解析到名称>";
                String flagsDesc = describeBlockFlags(bh.flags());
                String base = bh.basePoint() != null ? bh.basePoint().toString() : "(null)";
                String xref = bh.xrefPath() != null ? bh.xrefPath() : "";
                System.out.printf("  #%-5d 0x%-8X %-32s 0x%04X %-4s %-20s %s%n",
                    n, bh.handle(), truncate(name, 32), bh.flags(), flagsDesc, base, xref);
                handleToBlockName.put(bh.handle(), name);
            }
        }
        System.out.println();

        // 3) 块引用 (INSERT)
        System.out.println("【3】块引用 (INSERT, 类型 0x07 / 7)");
        System.out.println("-".repeat(100));
        List<DwgInsert> inserts = new ArrayList<>();
        for (DwgObject o : map.values()) {
            if (o instanceof DwgInsert ins) inserts.add(ins);
        }
        Map<String, Integer> insertCount = new LinkedHashMap<>();
        if (inserts.isEmpty()) {
            System.out.println("  (未解析到任何 INSERT 实体)");
        } else {
            System.out.printf("  %-6s %-10s %-32s %-22s %-14s %s%n",
                "序号", "HANDLE", "引用块名", "插入点", "缩放(x,y,z)", "旋转(弧度)");
            int n = 0;
            for (DwgInsert ins : inserts) {
                n++;
                long bhRef = ins.blockHeaderHandle() != null ? ins.blockHeaderHandle().rawHandle() : 0L;
                String name = handleToBlockName.getOrDefault(bhRef, "0x" + Long.toHexString(bhRef));
                String pos = ins.insertionPoint() != null ? ins.insertionPoint().toString() : "(null)";
                String scale = String.format("%.3f,%.3f,%.3f", ins.xScale(), ins.yScale(), ins.zScale());
                double rot = ins.rotation();
                System.out.printf("  #%-5d 0x%-8X %-32s %-22s %-14s %.6f%n",
                    n, ins.handle(), truncate(name, 32), pos, scale, rot);
                insertCount.merge(name, 1, Integer::sum);
                if (n >= 200) { System.out.println("  ... 截断,前 200 条共 " + inserts.size() + " 条"); break; }
            }
        }
        System.out.println();

        // 4) 块引用统计
        System.out.println("【4】块被引用次数统计");
        System.out.println("-".repeat(100));
        if (insertCount.isEmpty()) {
            System.out.println("  (当前没有解析到 INSERT,无法统计)");
        } else {
            List<Map.Entry<String, Integer>> sorted = new ArrayList<>(insertCount.entrySet());
            sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
            for (var e : sorted) {
                System.out.printf("  %-40s %d 次%n", e.getKey(), e.getValue());
            }
        }
        System.out.println();

        // 5) 实体类型统计
        System.out.println("【5】对象类型分布 (按 rawTypeCode)");
        System.out.println("-".repeat(100));
        Map<Integer, Integer> typeCount = new TreeMap<>();
        Map<Integer, String> typeName = new HashMap<>();
        for (DwgObject o : map.values()) {
            typeCount.merge(o.rawTypeCode(), 1, Integer::sum);
            typeName.put(o.rawTypeCode(), o.objectType().name());
        }
        System.out.printf("  %-8s %-6s %-30s %s%n", "HEX", "DEC", "类型名", "数量");
        for (var e : typeCount.entrySet()) {
            int code = e.getKey();
            System.out.printf("  0x%-6X %-6d %-30s %d%n",
                code, code, typeName.getOrDefault(code, "UNKNOWN"), e.getValue());
        }
        System.out.println();

        // 6) 图层
        System.out.println("【6】图层 (LAYER, 类型 0x33 / 51)");
        System.out.println("-".repeat(100));
        int layerCount = 0;
        for (DwgLayer ly : doc.layers()) {
            layerCount++;
            System.out.printf("  #%-3d 0x%-8X %-32s colorIdx=%d%n",
                layerCount, ly.handle(), truncate(ly.name(), 32),
                ly.color() != null ? ly.color().getColorIndex() : -1);
        }
        System.out.println();

        // 7) 问题/提示总结
        System.out.println("【7】解析质量 & 建议");
        System.out.println("-".repeat(100));
        boolean hasUnnamedBlock = blocks.stream().anyMatch(b -> b.blockName() == null || b.blockName().isEmpty());
        if (hasUnnamedBlock) {
            System.out.println("  ⚠  存在 BLOCK_HEADER 未解析到 name — 块定义读取器可能字段顺序错误");
            System.out.println("     (BlockHeaderObjectReader 使用了 EntityHeader,但 BLOCK_HEADER 是非实体对象)");
        } else if (!blocks.isEmpty()) {
            System.out.println("  ✓  块名全部解析成功");
        }

        int totalEntities = doc.entities() != null ? doc.entities().size() : 0;
        System.out.println("  ✓  成功解析对象 " + total + " 个,实体 " + totalEntities + " 个");
        System.out.println("  ✓  BlockHeader 数 " + blocks.size() + " / Insert 数 " + inserts.size());
        System.out.println("=".repeat(100));
    }

    private static String versionCode(DwgVersion v) {
        return switch (v) {
            case R13 -> "AC1012";
            case R14 -> "AC1014";
            case R2000 -> "AC1015";
            case R2004 -> "AC1018";
            case R2007 -> "AC1021";
            case R2010 -> "AC1024";
            case R2013 -> "AC1027";
            case R2018 -> "AC1032";
        };
    }

    private static String describeBlockFlags(int f) {
        StringBuilder sb = new StringBuilder();
        if ((f & 0x01) != 0) sb.append("anon ");
        if ((f & 0x02) != 0) sb.append("atts ");
        if ((f & 0x04) != 0) sb.append("xref ");
        if ((f & 0x08) != 0) sb.append("xref_overlay ");
        if (f == 0) sb.append("normal");
        return sb.toString().trim();
    }

    private static String truncate(String s, int w) {
        if (s == null) return "(null)";
        return s.length() <= w ? s : s.substring(0, w - 1) + "…";
    }
}
