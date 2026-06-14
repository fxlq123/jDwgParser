package run;

import io.dwg.api.DwgDocument;
import io.dwg.api.DwgReader;
import io.dwg.core.io.BitStreamReader;
import io.dwg.core.io.ByteBufferBitInput;
import io.dwg.core.io.SectionInputStream;
import io.dwg.core.version.DwgVersion;
import io.dwg.format.common.DwgFileStructureHandler;
import io.dwg.format.common.DwgFileStructureHandlerFactory;
import io.dwg.format.common.FileHeaderFields;
import io.dwg.sections.handles.HandleRegistry;
import io.dwg.sections.handles.HandlesSectionParser;
import io.dwg.entities.DwgObject;
import io.dwg.entities.concrete.DwgBlockHeader;
import io.dwg.entities.concrete.DwgInsert;

import java.nio.file.Paths;
import java.util.*;

/**
 * 验证 HandleRegistry 和对象解析流程
 */
public class DebugR2000ParsePath {

    public static void main(String[] args) throws Exception {
        String filename = "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg";
        byte[] data = java.nio.file.Files.readAllBytes(Paths.get(filename));

        System.out.println("=== 分析 R2000 文件解析路径 ===");

        // 1. 获取文件结构
        DwgFileStructureHandler handler = DwgFileStructureHandlerFactory.forVersion(DwgVersion.R2000);
        ByteBufferBitInput input = new ByteBufferBitInput(data);
        FileHeaderFields header = handler.readHeader(input);

        input = new ByteBufferBitInput(data);
        Map<String, SectionInputStream> sections = handler.readSections(input, header);

        System.out.println("可用的 sections:");
        for (String name : sections.keySet()) {
            SectionInputStream s = sections.get(name);
            System.out.println("  - " + name + " (size=" + s.rawBytes().length + ")");
        }

        // 2. 解析 Handles section
        System.out.println("\n=== 解析 Handles section ===");
        SectionInputStream handles = sections.get("AcDb:Handles");
        if (handles != null) {
            HandlesSectionParser hsp = new HandlesSectionParser();
            HandleRegistry registry = hsp.parse(handles, DwgVersion.R2000);

            System.out.println("Registry entries: " + registry.allHandles().size());
            System.out.println("\n前 20 个 handle:");
            int count = 0;
            for (long h : registry.allHandles()) {
                if (count >= 20) break;
                java.util.Optional<Long> off = registry.offsetFor(h);
                System.out.println("  handle=0x" + Long.toHexString(h) +
                    " offset=" + (off.isPresent() ? off.get() : "N/A"));
                count++;
            }

            // 检查 handle=0x986
            long targetHandle = 0x986L;
            java.util.Optional<Long> targetOffset = registry.offsetFor(targetHandle);
            System.out.println("\nhandle=0x986 offset: " +
                (targetOffset.isPresent() ? targetOffset.get() : "N/A"));

            // 检查是否有 BLOCK_HEADER 相关的句柄
            System.out.println("\n查找可能是 BLOCK_HEADER 的句柄:");
            // 在 R2000 中: block_header handle 通常是第一个或接近开头
            // 检查最小的几个 handle
            List<Long> sortedHandles = new ArrayList<>();
            for (long h : registry.allHandles()) sortedHandles.add(h);
            Collections.sort(sortedHandles);
            System.out.println("最小的 10 个句柄:");
            for (int i = 0; i < Math.min(10, sortedHandles.size()); i++) {
                long h = sortedHandles.get(i);
                long off = registry.offsetFor(h).orElse(-1L);
                System.out.println("  handle=0x" + Long.toHexString(h) + " offset=" + off);
            }
        } else {
            System.out.println("没有 AcDb:Handles section!");
        }

        // 3. 使用 DwgReader 解析并检查对象
        System.out.println("\n=== DwgReader 解析结果 ===");
        DwgDocument doc = DwgReader.defaultReader().open(data);
        System.out.println("总对象数: " + doc.objectMap().size());

        // 检查 BLOCK_HEADER 对象
        System.out.println("\nBLOCK_HEADER 对象:");
        int bhcount = 0;
        for (DwgObject obj : doc.objectMap().values()) {
            if (obj instanceof DwgBlockHeader) {
                DwgBlockHeader bh = (DwgBlockHeader)obj;
                System.out.println("  handle=0x" + Long.toHexString(bh.handle()) +
                    " name='" + bh.blockName() + "'" +
                    " (rawTypeCode=0x" + Integer.toHexString(bh.rawTypeCode()) + ")");
                bhcount++;
            }
        }
        System.out.println("共 " + bhcount + " 个 BLOCK_HEADER");

        // 检查 INSERT 对象
        System.out.println("\nINSERT 引用的 block_header_handle:");
        Map<Long, Integer> handleRefs = new LinkedHashMap<>();
        int insertCount = 0;
        for (DwgObject obj : doc.objectMap().values()) {
            if (obj instanceof DwgInsert) {
                DwgInsert ins = (DwgInsert)obj;
                try {
                    long h = ins.blockHeaderHandle().rawHandle();
                    handleRefs.merge(h, 1, Integer::sum);
                } catch (Exception e) {}
                insertCount++;
            }
        }
        System.out.println("共 " + insertCount + " 个 INSERT");
        for (Map.Entry<Long, Integer> e : handleRefs.entrySet()) {
            System.out.println("  block_header_handle=0x" + Long.toHexString(e.getKey()) +
                " 引用次数=" + e.getValue());
        }

        // 检查问题: block_header_handle=0x986 但没有 BLOCK_HEADER 对应的句柄
        // 这说明 streaming 模式下句柄被错误设置为递增值 1,2,3...
        // 而不是文件中实际的句柄值
        long missingHandle = 0x986L;
        DwgObject objAtHandle = doc.objectMap().get(missingHandle);
        System.out.println("\n对象 0x" + Long.toHexString(missingHandle) + " 是否存在: " +
            (objAtHandle != null ? "是 (类型=" + objAtHandle.getClass().getSimpleName() + ")" : "否"));

        // 检查对象映射中所有 key
        System.out.println("\n对象映射中的句柄范围 (前20):");
        List<Long> allKeys = new ArrayList<>(doc.objectMap().keySet());
        Collections.sort(allKeys);
        for (int i = 0; i < Math.min(20, allKeys.size()); i++) {
            long h = allKeys.get(i);
            DwgObject obj = doc.objectMap().get(h);
            System.out.println("  key=0x" + Long.toHexString(h) + " handle=0x" +
                Long.toHexString(obj.handle()) + " " + obj.getClass().getSimpleName());
        }
    }
}
