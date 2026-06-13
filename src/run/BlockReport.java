package run;

import io.dwg.api.DwgDocument;
import io.dwg.api.DwgReader;
import io.dwg.entities.DwgObject;
import io.dwg.entities.concrete.DwgBlockHeader;
import io.dwg.entities.concrete.DwgInsert;
import io.dwg.entities.concrete.DwgBlockEnd;
import io.dwg.entities.concrete.DwgLine;
import io.dwg.entities.concrete.DwgCircle;
import io.dwg.entities.concrete.DwgArc;
import io.dwg.entities.concrete.DwgText;
import io.dwg.entities.concrete.DwgMText;
import io.dwg.entities.concrete.DwgPolyline2D;
import io.dwg.entities.concrete.DwgLwPolyline;
import io.dwg.entities.concrete.DwgPolyline3D;
import io.dwg.entities.concrete.DwgPoint;
import io.dwg.core.type.Point3D;
import io.dwg.core.type.DwgHandleRef;

import java.nio.file.Paths;
import java.util.*;

public class BlockReport {

    public static void main(String[] args) throws Exception {
        // 查找可用的 DWG 文件
        List<String> testFiles = new ArrayList<>();
        String[] candidates = {
            "samples/2018/Dynblocks.dwg",
            "samples/2018/Arc.dwg",
            "samples/2018/circle.dwg",
            "samples/2018/Line.dwg",
            "samples/2018/Text.dwg",
            "samples/2018/Polyline.dwg",
            "samples/2013/Arc.dwg",
            "samples/2010/Arc.dwg",
            "samples/2007/ATMOS-DC22S.dwg",
            "samples/example_2018.dwg",
            "samples/sample_2018.dwg",
        };
        for (String f : candidates) {
            if (new java.io.File(f).exists()) testFiles.add(f);
        }

        System.out.println("==========================================");
        System.out.println("   DWG 块解析报告 (Block Analysis)");
        System.out.println("==========================================");
        System.out.println();
        System.out.println("可解析文件数量: " + testFiles.size());
        System.out.println();

        for (String path : testFiles) {
            try {
                analyzeFile(path);
            } catch (Exception e) {
                System.out.println("✗ 解析失败: " + path + " - " + e.getMessage());
            }
        }

        System.out.println();
        System.out.println("==========================================");
        System.out.println("   报告结束 (End of Report)");
        System.out.println("==========================================");
    }

    static void analyzeFile(String path) throws Exception {
        System.out.println("──────────────────────────────────────────");
        System.out.println("文件: " + path);

        DwgDocument doc = DwgReader.defaultReader().open(Paths.get(path));
        Map<Long, DwgObject> objects = doc.objectMap();

        System.out.println("版本: " + doc.version());
        System.out.println("对象总数: " + objects.size());

        // === Phase 1: 统计对象类型 ===
        Map<String, Integer> typeCounts = new LinkedHashMap<>();
        int entityCount = 0;
        for (DwgObject obj : objects.values()) {
            String name = obj.getClass().getSimpleName();
            typeCounts.merge(name, 1, Integer::sum);
            if (obj.isEntity()) entityCount++;
        }

        // 按数量排序取前 15 个
        List<Map.Entry<String, Integer>> sortedTypes = new ArrayList<>(typeCounts.entrySet());
        sortedTypes.sort((a, b) -> b.getValue().compareTo(a.getValue()));

        System.out.println();
        System.out.println("  【对象类型分布 (Top 15)】");
        for (int i = 0; i < Math.min(15, sortedTypes.size()); i++) {
            Map.Entry<String, Integer> e = sortedTypes.get(i);
            int pct = (int)((e.getValue() * 100.0) / objects.size());
            System.out.printf("    %-28s : %5d  (%d%%)%n", e.getKey(), e.getValue(), pct);
        }
        System.out.println("    " + "-".repeat(45));
        System.out.printf("    %-28s : %5d%n", "实体 (Entities)", entityCount);

        // === Phase 2: Block Definitions (BLOCK_HEADER) ===
        List<DwgBlockHeader> blocks = new ArrayList<>();
        Map<Long, String> handleToBlockName = new HashMap<>();

        for (DwgObject obj : objects.values()) {
            if (obj instanceof DwgBlockHeader) {
                blocks.add((DwgBlockHeader)obj);
            }
        }

        System.out.println();
        System.out.println("  【块定义 (Block Definitions)】 共 " + blocks.size() + " 个");

        if (blocks.isEmpty()) {
            System.out.println("    (未找到 BLOCK_HEADER 对象)");
        } else {
            int idx = 1;
            for (DwgBlockHeader bh : blocks) {
                String name = bh.blockName();
                if (name == null || name.isEmpty()) name = "(无名)";
                long handle = 0;
                try {
                    handle = bh.handle();
                } catch (Exception e) {}

                handleToBlockName.put(handle, name);

                String flagsStr = describeBlockFlags(bh.flags());
                Point3D bp = bh.basePoint();
                String basePointStr = bp != null ?
                    String.format("(%.2f, %.2f, %.2f)", bp.x(), bp.y(), bp.z()) :
                    "(未知)";

                System.out.println();
                System.out.println("    Block #" + idx + ": " + name);
                System.out.println("      Handle    : 0x" + Long.toHexString(handle));
                System.out.println("      Flags     : 0x" + String.format("%04X", bh.flags()) + " " + flagsStr);
                System.out.println("      BasePoint : " + basePointStr);
                if (bh.xrefPath() != null && !bh.xrefPath().isEmpty()) {
                    System.out.println("      XrefPath  : " + bh.xrefPath());
                }
                idx++;
            }
        }

        // === Phase 3: Block References (INSERT) ===
        List<DwgInsert> inserts = new ArrayList<>();
        Map<String, Integer> insertByBlock = new LinkedHashMap<>();

        for (DwgObject obj : objects.values()) {
            if (obj instanceof DwgInsert) {
                inserts.add((DwgInsert)obj);
            }
        }

        System.out.println();
        System.out.println("  【块引用 (INSERT 引用)】 共 " + inserts.size() + " 个");

        if (inserts.isEmpty()) {
            System.out.println("    (未找到 INSERT 实体)");
        } else {
            int idx = 1;
            for (DwgInsert ins : inserts) {
                // 从 blockHeaderHandle 查找对应的块名
                String blockName = "(未知块)";
                try {
                    DwgHandleRef href = ins.blockHeaderHandle();
                    if (href != null) {
                        long h = href.rawHandle();
                        blockName = handleToBlockName.getOrDefault(h,
                            "(未映射:0x" + Long.toHexString(h) + ")");
                        insertByBlock.merge(blockName, 1, Integer::sum);
                    }
                } catch (Exception e) {}

                Point3D pt = ins.insertionPoint();
                String pos = pt != null ?
                    String.format("(%.2f, %.2f, %.2f)", pt.x(), pt.y(), pt.z()) :
                    "(未知)";

                System.out.println();
                System.out.println("    Insert #" + idx + ": " + blockName);
                System.out.println("      Position : " + pos);
                System.out.printf("      Scale    : (%.2f, %.2f, %.2f)%n", ins.xScale(), ins.yScale(), ins.zScale());
                System.out.printf("      Rotation : %.4f rad (%.2f°)%n", ins.rotation(), Math.toDegrees(ins.rotation()));
                if (ins.hasAttribs()) System.out.println("      HasAttrs : true");
                idx++;
            }

            // 块引用频率统计
            if (!insertByBlock.isEmpty()) {
                System.out.println();
                System.out.println("    块引用频率:");
                List<Map.Entry<String, Integer>> sortedRefs = new ArrayList<>(insertByBlock.entrySet());
                sortedRefs.sort((a, b) -> b.getValue().compareTo(a.getValue()));
                for (Map.Entry<String, Integer> e : sortedRefs) {
                    System.out.printf("      %-30s : %d%n", e.getKey(), e.getValue());
                }
            }
        }

        // === Phase 4: Block End 标记 ===
        int blockEndCount = 0;
        for (DwgObject obj : objects.values()) {
            if (obj instanceof DwgBlockEnd) blockEndCount++;
        }
        System.out.println();
        System.out.println("  【块结束标记 (BLOCK_END)】 共 " + blockEndCount + " 个");

        // === Phase 5: 几何实体快速统计 ===
        int lines = 0, circles = 0, arcs = 0, texts = 0;
        int lwpolys = 0, poly2d = 0, poly3d = 0, points = 0;
        for (DwgObject obj : objects.values()) {
            if (obj instanceof DwgLine) lines++;
            else if (obj instanceof DwgCircle) circles++;
            else if (obj instanceof DwgArc) arcs++;
            else if (obj instanceof DwgText || obj instanceof DwgMText) texts++;
            else if (obj instanceof DwgLwPolyline) lwpolys++;
            else if (obj instanceof DwgPolyline2D) poly2d++;
            else if (obj instanceof DwgPolyline3D) poly3d++;
            else if (obj instanceof DwgPoint) points++;
        }

        System.out.println();
        System.out.println("  【几何实体统计】");
        if (lines > 0) System.out.println("    LINE (直线)           : " + lines);
        if (circles > 0) System.out.println("    CIRCLE (圆)           : " + circles);
        if (arcs > 0) System.out.println("    ARC (圆弧)            : " + arcs);
        if (lwpolys > 0) System.out.println("    LWPOLYLINE (轻多段线) : " + lwpolys);
        if (poly2d > 0) System.out.println("    POLYLINE_2D (2D多段线): " + poly2d);
        if (poly3d > 0) System.out.println("    POLYLINE_3D (3D多段线): " + poly3d);
        if (texts > 0) System.out.println("    TEXT/MTEXT (文字)     : " + texts);
        if (points > 0) System.out.println("    POINT (点)            : " + points);
        if (lines+circles+arcs+lwpolys+poly2d+poly3d+texts+points == 0) {
            System.out.println("    (无标准几何实体)");
        }

        System.out.println();
        System.out.println("  ✓ 解析完成");
        System.out.println();
    }

    static String describeBlockFlags(int flags) {
        List<String> parts = new ArrayList<>();
        if ((flags & 0x01) != 0) parts.add("匿名块");
        if ((flags & 0x02) != 0) parts.add("有属性");
        if ((flags & 0x04) != 0) parts.add("外部参照");
        if ((flags & 0x08) != 0) parts.add("外部参照覆盖");
        if ((flags & 0x10) != 0) parts.add("依赖外部参照");
        if ((flags & 0x20) != 0) parts.add("已解析外部参照");
        return parts.isEmpty() ? "(普通块)" : "[" + String.join(", ", parts) + "]";
    }
}
