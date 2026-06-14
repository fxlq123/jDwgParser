package run;

import io.dwg.api.DwgDocument;
import io.dwg.api.DwgReader;
import io.dwg.core.io.*;
import io.dwg.core.version.DwgVersion;
import io.dwg.format.common.*;
import io.dwg.sections.handles.*;
import io.dwg.entities.DwgObject;
import io.dwg.entities.concrete.DwgBlockHeader;
import io.dwg.entities.concrete.DwgInsert;

import java.nio.file.Paths;
import java.util.*;

/**
 * 字节级分析 R2000 BLOCK 和 INSERT 对象 - 不依赖 BitStreamReader 的假设
 */
public class DebugR2000ByteLevel {

    public static void main(String[] args) throws Exception {
        String filename = "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg";
        byte[] data = java.nio.file.Files.readAllBytes(Paths.get(filename));

        // 获取 handle registry
        DwgFileStructureHandler handler = DwgFileStructureHandlerFactory.forVersion(DwgVersion.R2000);
        ByteBufferBitInput input = new ByteBufferBitInput(data);
        FileHeaderFields header = handler.readHeader(input);
        input = new ByteBufferBitInput(data);
        Map<String, SectionInputStream> sections = handler.readSections(input, header);

        SectionInputStream handlesSec = sections.get("AcDb:Handles");
        HandlesSectionParser hsp = new HandlesSectionParser();
        HandleRegistry registry = hsp.parse(handlesSec, DwgVersion.R2000);

        List<Long> sortedHandles = new ArrayList<>();
        for (long h : registry.allHandles()) sortedHandles.add(h);
        Collections.sort(sortedHandles);

        System.out.println("=== 字节级分析: 找到所有 BLOCK 对象 ===\n");
        System.out.println("Blocks in registry:");

        // 找出 typeCode=0x30 或 0x31 的所有对象
        List<Long> blockObjects = new ArrayList<>();
        for (long h : sortedHandles) {
            long off = registry.offsetFor(h).orElse(-1L);
            if (off < 0 || off > data.length - 4) continue;

            // 直接从字节读 type_code
            // 前 2 字节: modular short (obj_size)
            // 接下来的字节: type code (BS)
            try {
                ByteBufferBitInput bb = new ByteBufferBitInput(data);
                bb.seek(off * 8L);
                BitStreamReader reader = new BitStreamReader(bb, DwgVersion.R2000);

                int objSize = reader.readModularShort();
                int typeCode = reader.readBitShort();

                if (typeCode == 0x30 || typeCode == 0x31) {
                    blockObjects.add(h);
                    System.out.println("  handle=0x" + Long.toHexString(h) +
                        " offset=" + off + " size=" + objSize +
                        " type=" + (typeCode == 0x30 ? "BLOCK_HEADER(0x30)" : "BLOCK(0x31)"));

                    // 显示原始字节
                    System.out.print("  bytes: ");
                    for (int i = 0; i < Math.min(objSize, 80); i++) {
                        if (off + i >= data.length) break;
                        System.out.printf("%02X ", data[(int)off + i] & 0xFF);
                    }
                    System.out.println();
                    System.out.print("  ascii: ");
                    for (int i = 0; i < Math.min(objSize, 80); i++) {
                        if (off + i >= data.length) break;
                        int b = data[(int)off + i] & 0xFF;
                        System.out.print((b >= 32 && b < 127) ? (char)b : '.');
                    }
                    System.out.println("\n");
                }
            } catch (Exception e) {
                // skip
            }
        }

        System.out.println("共找到 " + blockObjects.size() + " 个 BLOCK 对象\n");

        // 现在分析第一个 BLOCK (handle=1) - 这应该是 BLOCK_HEADER 容器
        System.out.println("=== 详细分析 BLOCK_HEADER (handle=1) ===\n");
        analyzeBlockObject(data, registry.offsetFor(1L).orElse(-1L));

        // 分析具体的 BLOCK 定义 (type=0x31)
        for (long h : blockObjects) {
            if (h == 1L) continue; // 跳过容器
            System.out.println("\n=== 详细分析 BLOCK (handle=0x" + Long.toHexString(h) + ") ===\n");
            analyzeBlockObject(data, registry.offsetFor(h).orElse(-1L));
        }

        // 分析 INSERT 实体
        System.out.println("\n=== 分析 INSERT 实体 ===\n");
        DwgDocument doc = DwgReader.defaultReader().open(data);
        int insertCount = 0;
        for (long h : sortedHandles) {
            DwgObject obj = doc.objectMap().get(h);
            if (obj instanceof DwgInsert) {
                if (insertCount < 3) {
                    long off = registry.offsetFor(h).orElse(-1L);
                    System.out.println("【INSERT " + insertCount + "】 handle=0x" + Long.toHexString(h) + " offset=" + off);
                    analyzeInsertObject(data, off, registry);
                    System.out.println();
                }
                insertCount++;
            }
        }
        System.out.println("共找到 " + insertCount + " 个 INSERT 实体");
    }

    private static void analyzeBlockObject(byte[] data, long offset) {
        if (offset < 0 || offset > data.length - 50) return;

        // 用 BitStreamReader 读取前两个字段，然后检查实际位置
        ByteBufferBitInput bbuf = new ByteBufferBitInput(data);
        bbuf.seek(offset * 8L);
        BitStreamReader reader = new BitStreamReader(bbuf, DwgVersion.R2000);

        int objSize = reader.readModularShort();
        int typeCode = reader.readBitShort();
        long bitPosAfterHeader = bbuf.position();
        long bytePosAfterHeader = bitPosAfterHeader / 8L;

        System.out.println("  obj_size: " + objSize);
        System.out.println("  typeCode: 0x" + Integer.toHexString(typeCode) + " (" + typeCode + ")");
        System.out.println("  header 结束 @ byte=" + bytePosAfterHeader + " (bit=" + bitPosAfterHeader + ")");

        // 从 header 结束位置开始，尝试不同方式解析
        // 方法 1: 用 BitStreamReader 继续
        System.out.println("\n  【方法1】 用 BitStreamReader 继续:");
        try {
            // 尝试读 handle reference
            long h1 = reader.readHandle();
            long p1 = bbuf.position();
            System.out.println("    handle_ref1: 0x" + Long.toHexString(h1) + " (@bit=" + p1 + ")");

            // 尝试读 num_reactors (BS)
            int numReactors = reader.readBitShort();
            long p2 = bbuf.position();
            System.out.println("    num_reactors: " + numReactors + " (@bit=" + p2 + ")");

            // 尝试读 xdict handle (H)
            long xdict = reader.readHandle();
            long p3 = bbuf.position();
            System.out.println("    xdict_handle: 0x" + Long.toHexString(xdict) + " (@bit=" + p3 + ")");

            // 尝试读 block_name (TV)
            long pBeforeText = bbuf.position();
            int textLen = reader.readBitShort();
            System.out.println("    block_name length: " + textLen + " (@bit=" + bbuf.position() + ")");

            if (textLen > 0 && textLen < 100) {
                // 读取 textLen 字节
                int byteStart = (int)(bbuf.position() / 8L);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < textLen && byteStart + i < data.length; i++) {
                    int b = data[byteStart + i] & 0xFF;
                    sb.append((char)b);
                }
                System.out.println("    block_name: '" + sb + "'");
            }
        } catch (Exception e) {
            System.out.println("    错误: " + e.getMessage());
        }

        // 方法 2: 从 bytePosAfterHeader 开始，直接读字节数据
        System.out.println("\n  【方法2】 直接字节级分析 (从 byte " + bytePosAfterHeader + " 开始):");
        int bpos = (int)bytePosAfterHeader;
        System.out.print("    bytes: ");
        for (int i = 0; i < Math.min(60, objSize - 4); i++) {
            if (bpos + i >= data.length) break;
            System.out.printf("%02X ", data[bpos + i] & 0xFF);
        }
        System.out.println();
        System.out.print("    ascii: ");
        for (int i = 0; i < Math.min(60, objSize - 4); i++) {
            if (bpos + i >= data.length) break;
            int b = data[bpos + i] & 0xFF;
            System.out.print((b >= 32 && b < 127) ? (char)b : '.');
        }
        System.out.println();

        // 方法 3: 查找字符串 - BLOCK 对象通常包含块名称
        // 先尝试直接从 header 后找 block_name
        // 对于 BLOCK_HEADER (type 0x30): 可能是 block_name
        // 对于 BLOCK (type 0x31): 也可能包含 block_name
        System.out.println("\n  【方法3】 查找可打印字符串:");
        StringBuilder sb = new StringBuilder();
        int start = -1;
        for (int i = (int)bytePosAfterHeader; i < offset + objSize; i++) {
            if (i >= data.length) break;
            int b = data[i] & 0xFF;
            if (b >= 32 && b < 127) {
                if (start < 0) start = i;
                sb.append((char)b);
            } else {
                if (sb.length() >= 3) {
                    System.out.println("    [+" + start + "] len=" + sb.length() + ": '" + sb + "'");
                }
                sb.setLength(0);
                start = -1;
            }
        }
        if (sb.length() >= 3) {
            System.out.println("    [+" + start + "] len=" + sb.length() + ": '" + sb + "'");
        }

        // 方法 4: 检查是否是 byte-aligned 对象，跳过 header bytes (MS + type) = 4 bytes
        System.out.println("\n  【方法4】 从 offset+4 开始尝试读 LE16 + 字符串:");
        int offset4 = (int)offset + 4;
        for (int i = 0; i < 15; i++) {
            int pos = offset4 + i;
            if (pos >= data.length - 1) break;

            // 读 length (LE16)
            int len = (data[pos] & 0xFF) | ((data[pos + 1] & 0xFF) << 8);

            // 如果 len 合理，尝试读字符串
            if (len > 0 && len < 50 && pos + 2 + len <= data.length) {
                StringBuilder name = new StringBuilder();
                boolean printable = true;
                for (int j = 0; j < len; j++) {
                    int c = data[pos + 2 + j] & 0xFF;
                    if (c >= 32 && c < 127) {
                        name.append((char)c);
                    } else {
                        printable = false;
                        break;
                    }
                }
                if (printable) {
                    System.out.println("    [+4+" + i + "] LE16 len=" + len + ": '" + name + "'");
                }
            }
        }
    }

    private static void analyzeInsertObject(byte[] data, long offset, HandleRegistry registry) {
        if (offset < 0 || offset > data.length - 50) return;

        ByteBufferBitInput bbuf = new ByteBufferBitInput(data);
        bbuf.seek(offset * 8L);
        BitStreamReader reader = new BitStreamReader(bbuf, DwgVersion.R2000);

        int objSize = reader.readModularShort();
        int typeCode = reader.readBitShort();
        long bitPosAfterHeader = bbuf.position();

        System.out.println("  obj_size: " + objSize);
        System.out.println("  typeCode: 0x" + Integer.toHexString(typeCode) + " (" + typeCode + ")");
        System.out.println("  header 结束 @ byte=" + (bitPosAfterHeader / 8L));

        // 显示原始字节
        System.out.print("  raw bytes: ");
        for (int i = 0; i < Math.min(objSize, 80); i++) {
            if (offset + i >= data.length) break;
            System.out.printf("%02X ", data[(int)offset + i] & 0xFF);
        }
        System.out.println();

        // 方法 1: 用 BitStreamReader 继续
        System.out.println("\n  【方法1】 BitStreamReader:");
        try {
            long hOwner = reader.readHandle();
            System.out.println("    handle_to_owner: 0x" + Long.toHexString(hOwner));

            // entity handle (H)
            long hEnt = reader.readHandle();
            System.out.println("    entity_handle: 0x" + Long.toHexString(hEnt));

            // layer handle (H)
            long hLayer = reader.readHandle();
            System.out.println("    layer_handle: 0x" + Long.toHexString(hLayer));

            // color index (BS)
            int color = reader.readBitShort();
            System.out.println("    color_index: " + color);

            // linetype scale (BD)
            double ltscale = reader.readBitDouble();
            System.out.println("    linetype_scale: " + ltscale);

            // visibility (BS)
            int vis = reader.readBitShort();
            System.out.println("    visibility: " + vis);

            // block_header_handle (H) - 关键！
            long blockHeaderHandle = reader.readHandle();
            System.out.println("    block_header_handle: 0x" + Long.toHexString(blockHeaderHandle));

            // insertion point (3BD)
            double x = reader.readBitDouble();
            double y = reader.readBitDouble();
            double z = reader.readBitDouble();
            System.out.printf("    insertion_point: (%.4f, %.4f, %.4f)%n", x, y, z);
        } catch (Exception e) {
            System.out.println("    解析错误: " + e.getMessage());
        }

        // 方法 2: 直接字节级检查
        System.out.println("\n  【方法2】 字节级查找字符串和引用:");
        int bpos = (int)(bitPosAfterHeader / 8L);
        System.out.print("    bytes: ");
        for (int i = 0; i < Math.min(60, objSize - 4); i++) {
            if (bpos + i >= data.length) break;
            System.out.printf("%02X ", data[bpos + i] & 0xFF);
        }
        System.out.println();

        // 查找可能的 block_header_handle (32-bit LE)
        for (int i = 0; i < 30; i++) {
            int pos = bpos + i;
            if (pos >= data.length - 4) break;

            // 32-bit LE handle
            long h = ((long)(data[pos] & 0xFF)) |
                     ((long)(data[pos + 1] & 0xFF) << 8) |
                     ((long)(data[pos + 2] & 0xFF) << 16) |
                     ((long)(data[pos + 3] & 0xFF) << 24);

            if (registry.offsetFor(h).isPresent()) {
                System.out.println("    找到已知 handle @ byte+" + i + ": 0x" + Long.toHexString(h));
            }
        }
    }
}
