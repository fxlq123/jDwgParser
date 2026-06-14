package run;

import io.dwg.api.*;
import io.dwg.format.common.*;
import io.dwg.format.r2018.*;
import io.dwg.core.io.*;
import io.dwg.core.version.*;
import io.dwg.sections.objects.readers.*;
import io.dwg.entities.*;
import io.dwg.entities.concrete.*;
import java.nio.file.*;
import java.util.*;

/**
 * 测试 R2018 解析
 */
public class TestR2018Parse {
    public static void main(String[] args) throws Exception {
        String path = "samples/2018/Dynblocks.dwg";
        if (args.length > 0) path = args[0];

        System.out.println("=== 测试 R2018 解析: " + path + " ===");

        // 方法 1: 使用 DwgReader
        try {
            DwgDocument doc = DwgReader.defaultReader().open(Paths.get(path));
            System.out.println("✓ DwgReader 打开成功");
            System.out.println("  版本: " + doc.version());
            Map<Long, DwgObject> objects = doc.objectMap();
            System.out.println("  对象数: " + objects.size());

            // 统计对象类型
            Map<String, Integer> typeCounts = new LinkedHashMap<>();
            for (DwgObject obj : objects.values()) {
                String name = obj.getClass().getSimpleName();
                typeCounts.merge(name, 1, Integer::sum);
            }
            System.out.println("  类型分布 (前 10): ");
            typeCounts.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .limit(10)
                .forEach(e -> System.out.println("    " + e.getKey() + ": " + e.getValue()));

            // 查找 BLOCK_HEADER
            int blockCount = 0;
            for (DwgObject obj : objects.values()) {
                if (obj instanceof DwgBlockHeader) {
                    DwgBlockHeader bh = (DwgBlockHeader) obj;
                    blockCount++;
                    if (blockCount <= 5) {
                        System.out.println("  Block #" + blockCount + ": " + bh.blockName() +
                            " @ " + bh.basePoint());
                    }
                }
            }
            System.out.println("  总块数: " + blockCount);

        } catch (Exception e) {
            System.out.println("✗ DwgReader 失败: " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println();
        System.out.println("=== 直接测试 R2007FileStructureHandler ===");

        try {
            byte[] data = Files.readAllBytes(Paths.get(path));
            DwgVersion version = DwgVersionDetector.detect(data);
            System.out.println("检测版本: " + version);

            // 测试 R2007 处理器 (虽然是 R2018 文件)
            io.dwg.format.r2007.R2007FileStructureHandler h2007 =
                new io.dwg.format.r2007.R2007FileStructureHandler();

            try {
                BitInput input = new ByteBufferBitInput(java.nio.ByteBuffer.wrap(data));
                FileHeaderFields hdr = h2007.readHeader(input);
                System.out.println("✓ R2007 readHeader 成功");
                System.out.println("  pageMapOffset: 0x" + Long.toHexString(hdr.pageMapOffset()));
                System.out.println("  pageMapSizeComp: " + hdr.pageMapSizeComp());
                System.out.println("  pageMapSizeUncomp: " + hdr.pageMapSizeUncomp());
                System.out.println("  sectionMapId: " + hdr.sectionMapId());
            } catch (Exception e) {
                System.out.println("✗ R2007 readHeader 失败: " + e.getMessage());
            }

            // 测试 R2018 处理器
            R2018FileStructureHandler h2018 = new R2018FileStructureHandler();
            try {
                BitInput input = new ByteBufferBitInput(java.nio.ByteBuffer.wrap(data));
                FileHeaderFields hdr = h2018.readHeader(input);
                System.out.println("✓ R2018 readHeader 成功");
                System.out.println("  pageMapOffset: 0x" + Long.toHexString(hdr.pageMapOffset()));
            } catch (Exception e) {
                System.out.println("✗ R2018 readHeader 失败: " + e.getMessage());
            }

            try {
                BitInput input = new ByteBufferBitInput(java.nio.ByteBuffer.wrap(data));
                FileHeaderFields hdr = h2018.readHeader(input);
                Map<String, SectionInputStream> sections = h2018.readSections(input, hdr);
                System.out.println("✓ R2018 readSections 成功: " + sections.size() + " sections");
                for (String name : sections.keySet()) {
                    System.out.println("  - " + name);
                }
            } catch (Exception e) {
                System.out.println("✗ R2018 readSections 失败: " + e.getMessage());
            }
        } catch (Exception e) {
            System.out.println("✗ 测试失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
