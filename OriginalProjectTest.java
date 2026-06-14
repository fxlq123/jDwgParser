import io.dwg.api.DwgDocument;
import io.dwg.api.DwgReader;
import io.dwg.entities.DwgEntity;
import io.dwg.entities.DwgObject;
import io.dwg.entities.concrete.*;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * 使用 jDwgParser 原项目测试 DWG 文件解析
 */
public class OriginalProjectTest {
    public static void main(String[] args) throws Exception {
        String fileName = "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg";
        Path filePath = Paths.get(fileName);

        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║         jDwgParser 原项目解析测试                                          ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════════════════════╝");
        System.out.println();

        try {
            long startTime = System.currentTimeMillis();
            DwgDocument doc = DwgReader.defaultReader().open(filePath);
            long parseTime = System.currentTimeMillis() - startTime;

            // 基本信息
            System.out.println("┌──────────────────────────────────────────────────────────────────────────┐");
            System.out.println("│  文件信息                                                               │");
            System.out.println("└──────────────────────────────────────────────────────────────────────────┘");
            System.out.println();
            System.out.println("  文件: " + fileName);
            System.out.println("  版本: " + doc.version());
            System.out.println("  解析时间: " + parseTime + " ms");
            System.out.println();

            // 对象统计
            Map<Long, DwgObject> objectMap = doc.objectMap();
            List<DwgEntity> entities = doc.entities();

            System.out.println("┌──────────────────────────────────────────────────────────────────────────┐");
            System.out.println("│  对象统计                                                               │");
            System.out.println("└──────────────────────────────────────────────────────────────────────────┘");
            System.out.println();
            System.out.println("  对象总数: " + objectMap.size());
            System.out.println("  实体总数: " + entities.size());
            System.out.println();

            // 按类型统计实体
            System.out.println("┌──────────────────────────────────────────────────────────────────────────┐");
            System.out.println("│  实体类型分布                                                           │");
            System.out.println("└──────────────────────────────────────────────────────────────────────────┘");
            System.out.println();

            Map<String, Integer> entityTypeCount = new TreeMap<>();
            for (DwgEntity obj : entities) {
                String typeName = getTypeName(obj);
                entityTypeCount.merge(typeName, 1, Integer::sum);
            }

            // 按数量排序
            List<Map.Entry<String, Integer>> sorted = new ArrayList<>(entityTypeCount.entrySet());
            sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

            int shown = 0;
            for (Map.Entry<String, Integer> e : sorted) {
                shown++;
                System.out.printf("    %-25s: %5d 个%n", e.getKey(), e.getValue());
                if (shown >= 30) {
                    System.out.println("    ...");
                    break;
                }
            }
            System.out.println();

            // 详细列出各种实体
            System.out.println("┌──────────────────────────────────────────────────────────────────────────┐");
            System.out.println("│  图形实体详细信息                                                        │");
            System.out.println("└──────────────────────────────────────────────────────────────────────────┘");
            System.out.println();

            // LINE 直线
            List<DwgLine> lines = doc.objectsOfType(DwgLine.class);
            if (!lines.isEmpty()) {
                System.out.println("  [LINE 直线] 共 " + lines.size() + " 个:");
                for (int i = 0; i < Math.min(10, lines.size()); i++) {
                    DwgLine line = lines.get(i);
                    System.out.printf("    #%d: 起点=(%.4f, %.4f, %.4f) 终点=(%.4f, %.4f, %.4f)%n",
                        i + 1,
                        line.start().x(), line.start().y(), line.start().z(),
                        line.end().x(), line.end().y(), line.end().z());
                }
                if (lines.size() > 10) {
                    System.out.println("    ... 其余 " + (lines.size() - 10) + " 个省略");
                }
                System.out.println();
            }

            // CIRCLE 圆
            List<DwgCircle> circles = doc.objectsOfType(DwgCircle.class);
            if (!circles.isEmpty()) {
                System.out.println("  [CIRCLE 圆] 共 " + circles.size() + " 个:");
                for (int i = 0; i < Math.min(10, circles.size()); i++) {
                    DwgCircle circle = circles.get(i);
                    System.out.printf("    #%d: 圆心=(%.4f, %.4f, %.4f) 半径=%.4f%n",
                        i + 1,
                        circle.center().x(), circle.center().y(), circle.center().z(),
                        circle.radius());
                }
                if (circles.size() > 10) {
                    System.out.println("    ... 其余 " + (circles.size() - 10) + " 个省略");
                }
                System.out.println();
            }

            // ARC 圆弧
            List<DwgArc> arcs = doc.objectsOfType(DwgArc.class);
            if (!arcs.isEmpty()) {
                System.out.println("  [ARC 圆弧] 共 " + arcs.size() + " 个:");
                for (int i = 0; i < Math.min(10, arcs.size()); i++) {
                    DwgArc arc = arcs.get(i);
                    System.out.printf("    #%d: 圆心=(%.4f, %.4f, %.4f) 半径=%.4f 起始角=%.2f° 终止角=%.2f°%n",
                        i + 1,
                        arc.center().x(), arc.center().y(), arc.center().z(),
                        arc.radius(),
                        Math.toDegrees(arc.startAngle()),
                        Math.toDegrees(arc.endAngle()));
                }
                if (arcs.size() > 10) {
                    System.out.println("    ... 其余 " + (arcs.size() - 10) + " 个省略");
                }
                System.out.println();
            }

            // INSERT 块引用
            List<DwgInsert> inserts = doc.objectsOfType(DwgInsert.class);
            if (!inserts.isEmpty()) {
                System.out.println("  [INSERT 块引用] 共 " + inserts.size() + " 个:");
                for (int i = 0; i < Math.min(10, inserts.size()); i++) {
                    DwgInsert insert = inserts.get(i);
                    String blockName = "未知";
                    if (insert.blockHeaderHandle() != null) {
                        blockName = "handle=0x" + Long.toHexString(insert.blockHeaderHandle().rawHandle());
                    }
                    System.out.printf("    #%d: 位置=(%.4f, %.4f, %.4f) 块=%s%n",
                        i + 1,
                        insert.insertionPoint().x(), insert.insertionPoint().y(), insert.insertionPoint().z(),
                        blockName);
                }
                if (inserts.size() > 10) {
                    System.out.println("    ... 其余 " + (inserts.size() - 10) + " 个省略");
                }
                System.out.println();
            }

            // SPLINE 样条曲线
            List<DwgSpline> splines = doc.objectsOfType(DwgSpline.class);
            if (!splines.isEmpty()) {
                System.out.println("  [SPLINE 样条曲线] 共 " + splines.size() + " 个:");
                for (int i = 0; i < Math.min(5, splines.size()); i++) {
                    DwgSpline spline = splines.get(i);
                    System.out.printf("    #%d: 有定义%n", i + 1);
                }
                if (splines.size() > 5) {
                    System.out.println("    ... 其余 " + (splines.size() - 5) + " 个省略");
                }
                System.out.println();
            }

            // MTEXT 多行文字
            List<DwgMText> mtexts = doc.objectsOfType(DwgMText.class);
            if (!mtexts.isEmpty()) {
                System.out.println("  [MTEXT 多行文字] 共 " + mtexts.size() + " 个:");
                for (int i = 0; i < Math.min(10, mtexts.size()); i++) {
                    DwgMText mtext = mtexts.get(i);
                    String text = mtext.text();
                    if (text != null && text.length() > 50) text = text.substring(0, 50) + "...";
                    System.out.printf("    #%d: \"%s\"%n", i + 1, text);
                }
                if (mtexts.size() > 10) {
                    System.out.println("    ... 其余 " + (mtexts.size() - 10) + " 个省略");
                }
                System.out.println();
            }

            // LWPOLYLINE 轻量多段线
            List<DwgLwPolyline> lwplines = doc.objectsOfType(DwgLwPolyline.class);
            if (!lwplines.isEmpty()) {
                System.out.println("  [LWPOLYLINE 轻量多段线] 共 " + lwplines.size() + " 个:");
                for (int i = 0; i < Math.min(5, lwplines.size()); i++) {
                    DwgLwPolyline lwpline = lwplines.get(i);
                    System.out.printf("    #%d: 有定义%n", i + 1);
                }
                if (lwplines.size() > 5) {
                    System.out.println("    ... 其余 " + (lwplines.size() - 5) + " 个省略");
                }
                System.out.println();
            }

            // ELLIPSE 椭圆
            List<DwgEllipse> ellipses = doc.objectsOfType(DwgEllipse.class);
            if (!ellipses.isEmpty()) {
                System.out.println("  [ELLIPSE 椭圆] 共 " + ellipses.size() + " 个:");
                for (int i = 0; i < Math.min(5, ellipses.size()); i++) {
                    DwgEllipse ellipse = ellipses.get(i);
                    System.out.printf("    #%d: 中心=(%.4f, %.4f, %.4f)%n",
                        i + 1,
                        ellipse.center().x(), ellipse.center().y(), ellipse.center().z());
                }
                System.out.println();
            }

            // BLOCK_HEADER 块定义
            System.out.println("┌──────────────────────────────────────────────────────────────────────────┐");
            System.out.println("│  块定义列表 (BLOCK_HEADER)                                               │");
            System.out.println("└──────────────────────────────────────────────────────────────────────────┘");
            System.out.println();
            int blockCount = 0;
            for (DwgObject obj : objectMap.values()) {
                if (obj instanceof DwgBlockHeader) {
                    DwgBlockHeader bh = (DwgBlockHeader) obj;
                    blockCount++;
                    System.out.println("    块 #" + blockCount + ": " + bh.blockName());
                }
            }
            System.out.println("  共 " + blockCount + " 个块定义");
            System.out.println();

            // 图层列表
            System.out.println("┌──────────────────────────────────────────────────────────────────────────┐");
            System.out.println("│  图层列表 (LAYER)                                                        │");
            System.out.println("└──────────────────────────────────────────────────────────────────────────┘");
            System.out.println();
            int layerCount = 0;
            for (DwgLayer layer : doc.layers()) {
                layerCount++;
                System.out.println("    图层 #" + layerCount + ": " + layer.name());
            }
            System.out.println("  共 " + layerCount + " 个图层");
            System.out.println();

            // 汇总
            System.out.println("┌──────────────────────────────────────────────────────────────────────────┐");
            System.out.println("│  最终汇总                                                               │");
            System.out.println("└──────────────────────────────────────────────────────────────────────────┘");
            System.out.println();
            System.out.println("  ╔══════════════════════════════════════════════════════════╗");
            System.out.println("  ║   文件: 210-83-C30302 拨杆座（改2024.06.06）--30件.DWG      ║");
            System.out.println("  ║   版本: " + String.format("%-30s", doc.version()) + "              ║");
            System.out.println("  ╠══════════════════════════════════════════════════════════╣");
            System.out.println("  ║   解析结果统计:                                      ║");
            System.out.printf  ("  ║     对象总数              : %5d                        ║%n", objectMap.size());
            System.out.printf  ("  ║     实体总数             : %5d                        ║%n", entities.size());
            System.out.printf  ("  ║     LINE (直线)          : %5d                        ║%n", lines.size());
            System.out.printf  ("  ║     CIRCLE (圆)          : %5d                        ║%n", circles.size());
            System.out.printf  ("  ║     ARC (圆弧)           : %5d                        ║%n", arcs.size());
            System.out.printf  ("  ║     INSERT (块引用)       : %5d                        ║%n", inserts.size());
            System.out.printf  ("  ║     SPLINE (样条曲线)     : %5d                        ║%n", splines.size());
            System.out.printf  ("  ║     MTEXT (多行文字)     : %5d                        ║%n", mtexts.size());
            System.out.printf  ("  ║     LWPOLYLINE (多段线)   : %5d                        ║%n", lwplines.size());
            System.out.printf  ("  ║     ELLIPSE (椭圆)       : %5d                        ║%n", ellipses.size());
            System.out.printf  ("  ║     块定义 (BLOCK_HEADER): %5d                        ║%n", blockCount);
            System.out.printf  ("  ║     图层 (LAYER)         : %5d                        ║%n", layerCount);
            System.out.println("  ╚══════════════════════════════════════════════════════════╝");
            System.out.println();

            // 所有实体类型列表
            System.out.println("┌──────────────────────────────────────────────────────────────────────────┐");
            System.out.println("│  所有实体类型列表                                                        │");
            System.out.println("└──────────────────────────────────────────────────────────────────────────┘");
            System.out.println();
            System.out.println("  " + sorted.size() + " 种实体类型:");
            for (Map.Entry<String, Integer> e : sorted) {
                System.out.printf("    %-25s: %5d 个%n", e.getKey(), e.getValue());
            }
            System.out.println();

        } catch (Exception e) {
            System.out.println("  解析错误: " + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                              测试完成                                        ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════════════════════╝");
        System.out.println();
    }

    private static String getTypeName(DwgEntity obj) {
        if (obj instanceof DwgLine) return "LINE";
        if (obj instanceof DwgCircle) return "CIRCLE";
        if (obj instanceof DwgArc) return "ARC";
        if (obj instanceof DwgInsert) return "INSERT";
        if (obj instanceof DwgMinsert) return "MINSERT";
        if (obj instanceof DwgSpline) return "SPLINE";
        if (obj instanceof DwgEllipse) return "ELLIPSE";
        if (obj instanceof DwgMText) return "MTEXT";
        if (obj instanceof DwgLwPolyline) return "LWPOLYLINE";
        if (obj instanceof DwgPolyline2D) return "POLYLINE_2D";
        if (obj instanceof DwgPolyline3D) return "POLYLINE_3D";
        if (obj instanceof DwgSolid) return "SOLID";
        if (obj instanceof DwgTrace) return "TRACE";
        if (obj instanceof DwgText) return "TEXT";
        if (obj instanceof DwgAttdef) return "ATTDEF";
        if (obj instanceof DwgAttrib) return "ATTRIB";
        if (obj instanceof DwgPoint) return "POINT";
        if (obj instanceof DwgBlockEnd) return "BLOCK_END";
        if (obj instanceof DwgDimensionAligned) return "DIMENSION_ALIGNED";
        if (obj instanceof DwgDimensionLinear) return "DIMENSION_LINEAR";
        if (obj instanceof DwgDimensionRadius) return "DIMENSION_RADIUS";
        if (obj instanceof DwgDimensionDiameter) return "DIMENSION_DIAMETER";
        if (obj instanceof DwgDimensionOrdinate) return "DIMENSION_ORDINATE";
        if (obj instanceof DwgHatch) return "HATCH";
        if (obj instanceof DwgLeader) return "LEADER";
        if (obj instanceof DwgViewport) return "VIEWPORT";
        if (obj instanceof DwgFace3D) return "FACE3D";
        if (obj instanceof DwgMLine) return "MLINE";
        if (obj instanceof DwgShape) return "SHAPE";
        if (obj instanceof DwgRay) return "RAY";
        if (obj instanceof DwgXLine) return "XLINE";
        if (obj instanceof DwgVertex2D) return "VERTEX_2D";
        if (obj instanceof DwgVertex3D) return "VERTEX_3D";
        if (obj instanceof DwgSeqEnd) return "SEQEND";
        return obj.getClass().getSimpleName();
    }
}
