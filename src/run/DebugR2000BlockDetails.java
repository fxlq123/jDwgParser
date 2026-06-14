package run;

import io.dwg.api.DwgDocument;
import io.dwg.api.DwgReader;
import io.dwg.entities.DwgObject;
import io.dwg.entities.concrete.DwgBlockHeader;
import io.dwg.entities.concrete.DwgInsert;
import io.dwg.core.io.BitStreamReader;
import io.dwg.core.io.ByteBufferBitInput;
import io.dwg.core.io.SectionInputStream;
import io.dwg.core.type.Point3D;
import io.dwg.core.version.DwgVersion;

import java.nio.file.Paths;
import java.util.*;

/**
 * 详细调试 R2000 BLOCK_HEADER 和 INSERT 解析
 * 特别检查: handle 读取、block_name 文本读取、字段位置是否正确
 */
public class DebugR2000BlockDetails {

    public static void main(String[] args) throws Exception {
        String filename = "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg";
        byte[] data = java.nio.file.Files.readAllBytes(Paths.get(filename));

        // 1. 先从 header 读取 section 信息
        System.out.println("=== 步骤1: 分析 R2000 Header 中的 section 位置 ===");
        analyzeHeaderSections(data);

        // 2. 查看 handles section 数据
        System.out.println("\n=== 步骤2: 分析 Handles section ===");
        analyzeHandlesSection(data);

        // 3. 使用完整解析器检查 BLOCK_HEADER 对象数据
        System.out.println("\n=== 步骤3: 检查 BLOCK_HEADER 前后的原始字节 ===");
        inspectBlockHeaderBytes(data, filename);

        // 4. 检查 INSERT 实体
        System.out.println("\n=== 步骤4: 检查 INSERT 实体 block_header_handle ===");
        inspectInsertEntities(filename);
    }

    private static void analyzeHeaderSections(byte[] data) throws Exception {
        // 直接从原始字节读取 header
        // 0x00-0x05: "AC1015"
        // 0x06-0x0B: Reserved
        // 然后是: unknown_0(RC) + preview_addr(RL) + dwg_version(RC) + maint_version(RC) + codepage(RS) + section_count(RL)
        // 然后 locator[section_count] : (RC record_number + RL seeker + RL size)

        java.io.ByteArrayInputStream bin = new java.io.ByteArrayInputStream(data);
        java.io.DataInputStream in = new java.io.DataInputStream(bin);

        byte[] versionBytes = new byte[6];
        in.readFully(versionBytes);
        System.out.println("Version: " + new String(versionBytes));

        // 跳过 reserved(6)
        in.skipBytes(6);

        // unknown_0 (RC)
        int unknown0 = in.readByte() & 0xFF;
        System.out.println("unknown_0: " + unknown0);

        // preview_addr (RL=little endian 32)
        int previewAddr = Integer.reverseBytes(in.readInt());
        System.out.println("preview address: 0x" + Integer.toHexString(previewAddr));

        // dwg_version (RC)
        int dwgVersion = in.readByte() & 0xFF;
        System.out.println("dwg_version: " + dwgVersion);

        // maint_version (RC)
        int maintVersion = in.readByte() & 0xFF;
        System.out.println("maint_version: " + maintVersion);

        // codepage (RS=little endian 16)
        int codePage = Short.reverseBytes(in.readShort()) & 0xFFFF;
        System.out.println("codepage: 0x" + Integer.toHexString(codePage));

        // section_count (RL)
        int sectionCount = Integer.reverseBytes(in.readInt());
        System.out.println("section_count: " + sectionCount);

        // Locators
        String[] sectionNames = {"HEADER", "CLASSES", "HANDLES", "OBJECTS"};
        System.out.println("\nSection locators:");
        for (int i = 0; i < sectionCount; i++) {
            int number = in.readByte() & 0xFF;       // RC
            int address = Integer.reverseBytes(in.readInt());  // RL
            int size = Integer.reverseBytes(in.readInt());     // RL
            String name = number < sectionNames.length ? sectionNames[number] : "UNKNOWN_" + number;
            System.out.printf("  [%d] %-12s offset=0x%06x (%d) size=%d%n",
                number, name, address, address, size);
        }

        // 检查 CRC
        int crc = Short.reverseBytes(in.readShort()) & 0xFFFF;
        System.out.println("CRC: 0x" + Integer.toHexString(crc));
    }

    private static void analyzeHandlesSection(byte[] data) throws Exception {
        // 使用 FileStructureHandler 获取 section
        io.dwg.format.common.DwgFileStructureHandler handler =
            io.dwg.format.common.DwgFileStructureHandlerFactory.forVersion(DwgVersion.R2000);
        io.dwg.core.io.BitInput input = new ByteBufferBitInput(data);
        io.dwg.format.common.FileHeaderFields header = handler.readHeader(input);

        input = new ByteBufferBitInput(data);
        java.util.Map<String, io.dwg.core.io.SectionInputStream> sections =
            handler.readSections(input, header);

        io.dwg.core.io.SectionInputStream handles = sections.get("AcDb:Handles");
        if (handles != null) {
            byte[] hd = handles.rawBytes();
            System.out.println("Handles section: " + hd.length + " bytes");
            // 显示前 64 字节
            System.out.print("前 64 字节: ");
            for (int i = 0; i < Math.min(64, hd.length); i++) {
                System.out.printf("%02X ", hd[i] & 0xFF);
            }
            System.out.println();

            // 让我尝试解析 handle 表
            // 格式: 每个条目是 handle(MS/BS) + offset(RL)
            // 尝试不同的方式解析
            System.out.println("\n尝试解析 handle 偏移表 (MS handle + LE32 offset):");
            ByteBufferBitInput bbuf = new ByteBufferBitInput(hd);
            BitStreamReader r = new BitStreamReader(bbuf, DwgVersion.R2000);

            try {
                int firstHandle = r.readModularShort();
                System.out.println("first_handle (MS): " + firstHandle);
                int nextHandle = r.readModularShort();
                System.out.println("next_handle (MS): " + nextHandle);
                int entries = nextHandle - firstHandle;
                System.out.println("条目数: " + entries);

                if (entries > 0 && entries < 5000) {
                    for (int i = 0; i < Math.min(entries, 10); i++) {
                        // 每个条目: offset (LE32)
                        long offset = (long)bbuf.readBits(32);  // 32 bits = LE32
                        // 但要注意字节序
                        // 重新解释: 这是 byte-aligned 的 LE32
                        System.out.println("  entry " + i + ": offset=" + offset +
                            "(0x" + Long.toHexString(offset) + ")");
                    }
                }
            } catch (Exception e) {
                System.out.println("解析失败: " + e.getMessage());
            }

            // 另一种解释方式: 把整个 section 当作 LE32 数组
            System.out.println("\n另一种解释 (按 LE32 数组):");
            for (int i = 0; i < Math.min(20, hd.length / 4); i++) {
                long val = ((long)(hd[i*4] & 0xFF)) |
                          ((long)(hd[i*4+1] & 0xFF) << 8) |
                          ((long)(hd[i*4+2] & 0xFF) << 16) |
                          ((long)(hd[i*4+3] & 0xFF) << 24);
                System.out.printf("  [%d] = %d (0x%08x)%n", i, val, val);
            }
        } else {
            System.out.println("没有 AcDb:Handles section");
        }

        // 检查实际对象数据位置
        System.out.println("\n=== 对象数据来源检查 ===");
        io.dwg.core.io.SectionInputStream classes = sections.get("AcDb:Classes");
        io.dwg.core.io.SectionInputStream hdr = sections.get("AcDb:Header");
        if (classes != null) System.out.println("Classes: " + classes.rawBytes().length + " bytes");
        if (hdr != null) System.out.println("Header: " + hdr.rawBytes().length + " bytes");

        // 检查 R2000 中对象数据是否直接从文件 header 之后开始
        // 让我计算 header 总长度
        System.out.println("\n检查 header 之后的文件数据:");
        // header 大约在 0x60 左右结束，实际对象数据应该紧跟
        dumpHexBytes(data, 0x60, 64, "0x60 (header之后)");
        dumpHexBytes(data, 0x200, 64, "0x200");
        dumpHexBytes(data, 0x400, 64, "0x400");
        dumpHexBytes(data, 0x1000, 64, "0x1000");
    }

    private static void inspectBlockHeaderBytes(byte[] data, String filename) throws Exception {
        // 使用解析器来找到 BLOCK_HEADER 对象
        DwgDocument doc = DwgReader.defaultReader().open(data);

        System.out.println("总对象数: " + doc.objectMap().size());

        // 显示前 15 个对象的类型
        System.out.println("\n前 15 个对象:");
        int idx = 0;
        for (DwgObject obj : doc.objectMap().values()) {
            if (idx >= 15) break;
            System.out.println("  [" + idx + "] handle=0x" + Long.toHexString(obj.handle()) +
                " type=" + obj.getClass().getSimpleName() +
                " rawTypeCode=0x" + Integer.toHexString(obj.rawTypeCode()));
            idx++;
        }

        // 查看 BLOCK_HEADER 位置的原始数据
        // 在 R2000 streaming 模式下，对象数据不是来自 Objects section，
        // 而是来自 file header 中指定的 Objects 偏移位置 (被移除了!)
        // 我们需要找到 R2000FileStructureHandler 如何提供 Objects section 给解析器

        // 让我直接读 header 中第 3 个 locator (objects)
        analyzeHeaderSections(data);

        // 手动重新读取，找到 objects 偏移
        // 0x00-0x05: version (6)
        // 0x06-0x0B: reserved (6)
        // 0x0C: unknown_0 (1)
        // 0x0D-0x10: preview_addr (4)
        // 0x11: dwg_version (1)
        // 0x12: maint_version (1)
        // 0x13-0x14: codepage (2)
        // 0x15-0x18: section_count (4)
        // 然后: locator[0] 1+4+4=9
        //       locator[1] 1+4+4=9
        //       locator[2] 1+4+4=9
        //       locator[3] 1+4+4=9
        // 0x19: CRC (2)
        // 让我验证这个

        System.out.println("\n=== 手动读取 section locators ===");
        // 直接计算偏移
        // section_count 在 0x15 (21) 位置
        int sectionCount = readLE32(data, 0x15);
        System.out.println("section_count (0x15): " + sectionCount);

        // locators 从 0x19 (25) 开始
        // 每个 locator 9字节
        for (int i = 0; i < sectionCount; i++) {
            int locatorOffset = 0x19 + i * 9;
            int number = data[locatorOffset] & 0xFF;
            int address = readLE32(data, locatorOffset + 1);
            int size = readLE32(data, locatorOffset + 5);
            String[] names = {"HEADER(0)", "CLASSES(1)", "HANDLES(2)", "OBJECTS(3)"};
            String name = number < names.length ? names[number] : "UNK_" + number;
            System.out.printf("  locator[%d] @0x%x: number=%d(%s) offset=0x%x(%d) size=%d%n",
                i, locatorOffset, number, name, address, address, size);
        }

        // CRC
        int crcOffset = 0x19 + sectionCount * 9;
        System.out.println("\nCRC @0x" + Integer.toHexString(crcOffset));

        // 对象数据开始位置
        System.out.println("\n文件中 OBJECTS section 数据 (使用 locator[3]):");
        int objOffset = readLE32(data, 0x19 + 3 * 9 + 1);
        int objSize = readLE32(data, 0x19 + 3 * 9 + 5);
        System.out.println("  Objects locator: offset=0x" + Integer.toHexString(objOffset) +
            "(" + objOffset + "), size=" + objSize);

        if (objOffset > 0 && objOffset < data.length) {
            System.out.println("  前 64 字节:");
            System.out.print("    ");
            for (int i = 0; i < Math.min(64, objSize); i++) {
                System.out.printf("%02X ", data[objOffset + i] & 0xFF);
                if ((i+1) % 16 == 0 && i > 0) System.out.print("\n    ");
            }
            System.out.println();

            // 显示这部分数据中的可读字符串
            System.out.print("  ASCII:    ");
            for (int i = 0; i < Math.min(64, objSize); i++) {
                int b = data[objOffset + i] & 0xFF;
                System.out.print((b >= 32 && b < 127) ? (char)b : '.');
            }
            System.out.println();
        }
    }

    private static void inspectInsertEntities(String filename) throws Exception {
        byte[] data = java.nio.file.Files.readAllBytes(Paths.get(filename));
        DwgDocument doc = DwgReader.defaultReader().open(data);

        System.out.println("检查前 15 个 INSERT 实体:");
        int count = 0;
        for (DwgObject obj : doc.objectMap().values()) {
            if (obj instanceof DwgInsert && count < 15) {
                DwgInsert ins = (DwgInsert)obj;
                System.out.println("\nINSERT handle=0x" + Long.toHexString(ins.handle()));
                try {
                    long bh = ins.blockHeaderHandle().rawHandle();
                    System.out.println("  block_header_handle=0x" + Long.toHexString(bh));
                } catch (Exception e) {
                    System.out.println("  block_header_handle: null");
                }
                try {
                    Point3D pt = ins.insertionPoint();
                    System.out.printf("  insertion_point=(%.2f, %.2f, %.2f)%n",
                        pt.x(), pt.y(), pt.z());
                } catch (Exception e) {}
                try {
                    System.out.printf("  scale=(%.2f, %.2f, %.2f)%n",
                        ins.xScale(), ins.yScale(), ins.zScale());
                } catch (Exception e) {}
                try {
                    System.out.printf("  rotation=%.4f rad%n", ins.rotation());
                } catch (Exception e) {}
                count++;
            }
        }
        System.out.println("\n共 " + count + " 个 INSERT");
    }

    private static int readLE32(byte[] data, int offset) {
        return (data[offset] & 0xFF) |
              ((data[offset+1] & 0xFF) << 8) |
              ((data[offset+2] & 0xFF) << 16) |
              ((data[offset+3] & 0xFF) << 24);
    }

    private static void dumpHexBytes(byte[] data, int start, int length, String label) {
        System.out.println(label + ":");
        System.out.print("  ");
        for (int i = 0; i < length && start + i < data.length; i++) {
            System.out.printf("%02X ", data[start + i] & 0xFF);
            if ((i+1) % 16 == 0) System.out.print("  ");
            if ((i+1) % 32 == 0) System.out.print("\n  ");
        }
        System.out.println();
    }
}
