package run;

import io.dwg.api.DwgDocument;
import io.dwg.api.DwgReader;
import io.dwg.entities.DwgObject;
import io.dwg.entities.concrete.DwgBlockHeader;
import io.dwg.entities.concrete.DwgInsert;
import io.dwg.core.type.DwgHandleRef;

import java.nio.file.Paths;
import java.util.*;

/**
 * 详细解析用户上传的 DWG 文件中每个 BLOCK 的内容
 */
public class UserBlockDetailTest {

    public static void main(String[] args) throws Exception {
        String filename = "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg";
        System.out.println("===========================================");
        System.out.println("文件: " + filename);
        System.out.println("===========================================");

        byte[] data = java.nio.file.Files.readAllBytes(Paths.get(filename));
        DwgDocument doc = DwgReader.defaultReader().open(data);

        System.out.println("✓ DWG 版本: " + doc.version());
        System.out.println("✓ 总对象数: " + doc.objectMap().size());
        System.out.println();

        // 1. 收集所有 BLOCK_HEADER 和 INSERT
        List<DwgBlockHeader> blockHeaders = new ArrayList<>();
        List<DwgInsert> inserts = new ArrayList<>();
        Map<String, Integer> entityCount = new LinkedHashMap<>();
        Map<Long, String> handleToBlockName = new HashMap<>();

        System.out.println("--- 扫描对象中... ---");
        for (DwgObject obj : doc.objectMap().values()) {
            String clsName = obj.getClass().getSimpleName();
            entityCount.merge(clsName, 1, Integer::sum);

            if (obj instanceof DwgBlockHeader) {
                DwgBlockHeader bh = (DwgBlockHeader) obj;
                blockHeaders.add(bh);
                try {
                    long h = bh.handle();
                    String name = bh.blockName();
                    if (name != null) {
                        handleToBlockName.put(h, name);
                    }
                } catch (Exception e) {}
            } else if (obj instanceof DwgInsert) {
                inserts.add((DwgInsert) obj);
            }
        }
        System.out.println("找到 " + blockHeaders.size() + " 个 BLOCK_HEADER");
        System.out.println("找到 " + inserts.size() + " 个 INSERT");
        System.out.println();

        // 2. 显示所有实体类型统计
        System.out.println("=============== 实体类型统计 ===============");
        entityCount.entrySet().stream()
            .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
            .forEach(e -> System.out.printf("  %-25s : %d%n", e.getKey(), e.getValue()));
        System.out.println();

        // 3. 详细显示每个 BLOCK_HEADER
        System.out.println("=============== BLOCK 定义详情 ===============");
        System.out.println("共 " + blockHeaders.size() + " 个块定义");
        System.out.println();

        int idx = 1;
        int systemBlocks = 0;
        int userBlocks = 0;
        for (DwgBlockHeader bh : blockHeaders) {
            String name = safeCall(() -> bh.blockName(), "(无名称)");
            boolean isSystem = name.startsWith("*");
            if (isSystem) systemBlocks++; else userBlocks++;

            System.out.println("【块 #" + idx + "】 名称: " + name +
                (isSystem ? " (系统块)" : " (用户块)"));

            try {
                System.out.printf("  基点: (%.4f, %.4f, %.4f)%n",
                    bh.basePoint().x(), bh.basePoint().y(), bh.basePoint().z());
            } catch (Exception e) {
                System.out.println("  基点: (无法读取)");
            }

            try {
                int flags = bh.flags();
                String flagsDesc = String.format(
                    "匿名=%s, 有属性=%s, XRef=%s, XRefOverlay=%s",
                    (flags & 0x01) != 0 ? "是" : "否",
                    (flags & 0x02) != 0 ? "是" : "否",
                    (flags & 0x04) != 0 ? "是" : "否",
                    (flags & 0x08) != 0 ? "是" : "否");
                System.out.println("  标志: 0x" + String.format("%04X", flags) + " [" + flagsDesc + "]");
            } catch (Exception e) {}

            try {
                String xp = bh.xrefPath();
                System.out.println("  XRef 路径: " + (xp == null || xp.isEmpty() ? "(无)" : xp));
            } catch (Exception e) {}

            try {
                System.out.println("  句柄: 0x" + Long.toHexString(bh.handle()));
            } catch (Exception e) {}

            System.out.println();
            idx++;
        }

        // 4. INSERT 实体详情 (块引用)
        System.out.println("=============== INSERT (块引用) 详情 ===============");
        System.out.println("共 " + inserts.size() + " 个块引用");
        System.out.println();

        Map<String, Integer> insertByName = new LinkedHashMap<>();
        int shownCount = 0;
        for (DwgInsert ins : inserts) {
            String refBlockName = "(未知)";
            try {
                DwgHandleRef href = ins.blockHeaderHandle();
                if (href != null) {
                    long refHandle = href.rawHandle();
                    refBlockName = handleToBlockName.getOrDefault(refHandle,
                        "(未映射:0x" + Long.toHexString(refHandle) + ")");
                }
            } catch (Exception e) {}

            insertByName.merge(refBlockName, 1, Integer::sum);

            if (shownCount < Math.min(25, inserts.size())) {
                System.out.println("【引用 #" + (shownCount + 1) + "】 块: " + refBlockName);
                try {
                    System.out.printf("  插入点: (%.4f, %.4f, %.4f)%n",
                        ins.insertionPoint().x(),
                        ins.insertionPoint().y(),
                        ins.insertionPoint().z());
                } catch (Exception e) {
                    System.out.println("  插入点: (无法读取)");
                }
                try {
                    System.out.printf("  缩放: X=%.4f, Y=%.4f, Z=%.4f%n",
                        ins.xScale(), ins.yScale(), ins.zScale());
                } catch (Exception e) {}
                try {
                    double rot = ins.rotation();
                    System.out.printf("  旋转: %.6f rad (%.4f°)%n", rot, rot * 180.0 / Math.PI);
                } catch (Exception e) {}
                try {
                    System.out.println("  句柄: 0x" + Long.toHexString(ins.handle()));
                } catch (Exception e) {}
                System.out.println();
                shownCount++;
            }
        }

        if (inserts.size() > shownCount) {
            System.out.println("... 还有 " + (inserts.size() - shownCount) + " 个引用未显示");
        }
        System.out.println();

        // 5. INSERT 按块名分组统计
        System.out.println("=============== 块引用统计 (分组汇总) ===============");
        insertByName.entrySet().stream()
            .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
            .forEach(e -> System.out.printf("  %-45s : %d 次%n", e.getKey(), e.getValue()));
        System.out.println();

        // 6. 汇总报告
        System.out.println();
        System.out.println("===========================================");
        System.out.println("                  报告摘要");
        System.out.println("===========================================");
        System.out.println("文件版本 : " + doc.version());
        System.out.println("文件大小 : " + data.length + " 字节");
        System.out.println("对象总数 : " + doc.objectMap().size());
        System.out.println("BLOCK_HEADER: " + blockHeaders.size() + " 个 (系统块:" + systemBlocks + ", 用户块:" + userBlocks + ")");
        System.out.println("INSERT      : " + inserts.size() + " 个");
        System.out.println();
        System.out.println("=== 用户定义块 ===");
        for (DwgBlockHeader bh : blockHeaders) {
            String n = safeCall(() -> bh.blockName(), "(无名称)");
            if (n != null && !n.startsWith("*")) {
                System.out.println("  • " + n);
            }
        }
        System.out.println();
        System.out.println("=== 系统块 (*Model, *Paper 等) ===");
        for (DwgBlockHeader bh : blockHeaders) {
            String n = safeCall(() -> bh.blockName(), "(无名称)");
            if (n != null && n.startsWith("*")) {
                System.out.println("  • " + n);
            }
        }
    }

    private static <T> T safeCall(java.util.function.Supplier<T> s, T def) {
        try {
            T result = s.get();
            return result != null ? result : def;
        } catch (Exception e) {
            return def;
        }
    }
}
