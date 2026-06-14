package run;

import io.dwg.api.DwgDocument;
import io.dwg.api.DwgReader;
import io.dwg.entities.DwgObject;
import io.dwg.entities.concrete.DwgBlockHeader;
import io.dwg.core.io.BitStreamReader;
import io.dwg.core.io.ByteBufferBitInput;
import io.dwg.core.io.SectionInputStream;
import io.dwg.core.version.DwgVersion;
import io.dwg.format.common.SectionType;

import java.nio.file.Paths;
import java.util.*;

/**
 * 调试 R2000 对象流解析 - 检查实际读取的类型码分布
 */
public class DebugR2000Blocks {

    public static void main(String[] args) throws Exception {
        String filename = "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg";
        System.out.println("文件: " + filename);

        byte[] data = java.nio.file.Files.readAllBytes(Paths.get(filename));
        DwgDocument doc = DwgReader.defaultReader().open(data);

        System.out.println("版本: " + doc.version());
        System.out.println("对象数: " + doc.objectMap().size());
        System.out.println();

        // 1. 显示从文件中通过 DwgReader 解析到的 BLOCK_HEADER 详情
        System.out.println("=== 通过 DwgReader 解析到的 BLOCK_HEADER ===");
        int bhCount = 0;
        for (DwgObject obj : doc.objectMap().values()) {
            if (obj instanceof DwgBlockHeader) {
                DwgBlockHeader bh = (DwgBlockHeader) obj;
                bhCount++;
                System.out.println("[" + bhCount + "]");
                System.out.println("  handle: 0x" + Long.toHexString(bh.handle()));
                System.out.println("  rawTypeCode: 0x" + Integer.toHexString(bh.rawTypeCode()) +
                    " (" + bh.rawTypeCode() + ")");
                System.out.println("  blockName: '" + bh.blockName() + "'");
                System.out.println("  flags: 0x" + Integer.toHexString(bh.flags()));
                System.out.println("  xrefPath: '" + bh.xrefPath() + "'");
                try {
                    System.out.println("  basePoint: (" +
                        bh.basePoint().x() + ", " + bh.basePoint().y() + ", " + bh.basePoint().z() + ")");
                } catch (Exception e) {
                    System.out.println("  basePoint: (null)");
                }
            }
        }
        System.out.println("共找到 " + bhCount + " 个 BLOCK_HEADER");
        System.out.println();

        // 2. 原始方式分析对象流 - 直接读取 MS+type 查看所有对象的类型码分布
        System.out.println("=== 原始对象流类型码分布 ===");
        analyzeObjectStream(filename, doc.version());
    }

    private static void analyzeObjectStream(String filename, DwgVersion version) throws Exception {
        // 重新打开文件获取 section 数据
        byte[] data = java.nio.file.Files.readAllBytes(Paths.get(filename));
        io.dwg.format.common.DwgFileStructureHandler handler =
            io.dwg.format.common.DwgFileStructureHandlerFactory.forVersion(version);
        io.dwg.core.io.BitInput input = new io.dwg.core.io.ByteBufferBitInput(data);

        io.dwg.format.common.FileHeaderFields header = handler.readHeader(input);
        input = new io.dwg.core.io.ByteBufferBitInput(data);
        java.util.Map<String, io.dwg.core.io.SectionInputStream> sections =
            handler.readSections(input, header);

        // 查找对象 section - 可能有不同的名称
        byte[] objBytes = null;
        String sectionName = null;
        for (String name : new String[]{"AcDb:AcDbObjects", "AcDb:Objects", "Objects", "object", "ENTITY"}) {
            SectionInputStream s = sections.get(name);
            if (s != null && s.rawBytes().length > 0) {
                objBytes = s.rawBytes();
                sectionName = name;
                break;
            }
        }

        if (objBytes == null) {
            System.out.println("没找到对象 section! 可用 sections:");
            for (String name : sections.keySet()) {
                System.out.println("  - " + name + " (size=" + sections.get(name).rawBytes().length + ")");
            }
            return;
        }
        System.out.println("找到了 section: '" + sectionName + "' (大小=" + objBytes.length + ")");
        System.out.println();

        // 以 streaming 方式读取
        ByteBufferBitInput bbuf = new ByteBufferBitInput(objBytes);
        java.util.Map<Integer, Integer> typeCount = new LinkedHashMap<>();
        java.util.Map<Integer, java.util.List<String>> typeDetails = new LinkedHashMap<>();
        long bitOffset = 0;
        int objCount = 0;
        int blockHeaderType = 0x30;
        int insertType = 0x1F;

        System.out.println("读取对象流...");
        System.out.println("理论上 BLOCK_HEADER type=0x30(" + blockHeaderType + "), INSERT type=0x1F(" + insertType + ")");
        System.out.println();

        while (bitOffset < (long)(objBytes.length - 6) * 8L && objCount < 1000) {
            long startBit = bitOffset;
            try {
                bbuf.seek(bitOffset);
                BitStreamReader r = new BitStreamReader(bbuf, version);

                int objSize = r.readModularShort();
                if (objSize <= 0 || objSize > 0x40000) {
                    bitOffset = startBit + 16;
                    continue;
                }

                long dataStartBit = bbuf.position();
                int typeCode = r.readBitShort();  // R2000 使用 BS (不是 BOT)

                typeCount.merge(typeCode, 1, Integer::sum);

                // 只记录某些关键类型的前几个位置
                if (typeCode == blockHeaderType || typeCode == 0x8 ||
                    (typeCode >= 0x1F && typeCode <= 0x21) || typeCode == 10) {
                    String desc = String.format("@byteOffset=%d(0x%x) objSize=%d type=0x%x(%d)",
                        (int)(startBit/8), (int)(startBit/8), objSize, typeCode, typeCode);
                    typeDetails.computeIfAbsent(typeCode, k -> new ArrayList<>()).add(desc);
                }

                // 下一个对象的位置
                bitOffset = dataStartBit + (long)objSize * 8L;
                objCount++;
            } catch (Exception e) {
                bitOffset = startBit + 16;
            }
        }

        System.out.println("共扫描到 " + objCount + " 个对象");
        System.out.println();
        System.out.println("类型码分布 (前15个):");
        typeCount.entrySet().stream()
            .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
            .limit(15)
            .forEach(e -> {
                int tc = e.getKey();
                System.out.printf("  0x%04x (%4d) : %d 个%s%n", tc, tc, e.getValue(),
                    tc == 0x30 ? " <-- BLOCK_HEADER" :
                    tc == 0x1F ? " <-- INSERT" :
                    tc == 0x8 ? " <-- BLOCK_END" :
                    tc == 0x1 ? " <-- TEXT" :
                    tc == 0x15 ? " <-- LINE" :
                    tc == 0x17 ? " <-- CIRCLE" :
                    tc == 0x12 ? " <-- LAYER" :
                    "");
            });

        // 显示关键类型的位置
        for (int key : new int[]{0x8, 0x1F, 0x30}) {
            if (typeDetails.containsKey(key)) {
                System.out.println();
                System.out.println("Type 0x" + String.format("%02x", key) + " 位置:");
                int shown = 0;
                for (String s : typeDetails.get(key)) {
                    if (shown < 5) {
                        System.out.println("  " + s);
                        shown++;
                    }
                }
                if (typeDetails.get(key).size() > 5) {
                    System.out.println("  ... 共 " + typeDetails.get(key).size() + " 个");
                }
            }
        }
    }
}
