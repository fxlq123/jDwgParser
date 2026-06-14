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
 * 使用位级(BitStreamReader)分析 R2000 对象格式
 */
public class DebugR2000BitLevel {

    public static void main(String[] args) throws Exception {
        String filename = "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg";
        byte[] data = java.nio.file.Files.readAllBytes(Paths.get(filename));

        System.out.println("=== 位级分析 R2000 对象 ===\n");

        // 获取 handle registry
        DwgFileStructureHandler handler = DwgFileStructureHandlerFactory.forVersion(DwgVersion.R2000);
        ByteBufferBitInput input = new ByteBufferBitInput(data);
        FileHeaderFields header = handler.readHeader(input);
        input = new ByteBufferBitInput(data);
        Map<String, SectionInputStream> sections = handler.readSections(input, header);

        SectionInputStream handlesSec = sections.get("AcDb:Handles");
        HandlesSectionParser hsp = new HandlesSectionParser();
        HandleRegistry registry = hsp.parse(handlesSec, DwgVersion.R2000);

        // 排序 handles
        List<Long> sortedHandles = new ArrayList<>();
        for (long h : registry.allHandles()) sortedHandles.add(h);
        Collections.sort(sortedHandles);

        // 对位级解析第一个对象 (handle=1, offset=21177)
        long h1 = 1L;
        long offset1 = registry.offsetFor(h1).orElse(-1L);
        System.out.println("=== 对象 1 @offset=" + offset1 + " (字节级分析) ===");
        analyzeObjectBitLevel(data, (int)offset1, 500);

        // 检查 BLOCK_HEADER 的位置 - 让我看看 Paper_Space 字符串在哪里
        System.out.println("\n=== 查找包含 BLOCK 名称的对象偏移 ===");
        // 搜索 "Paper_Space" 字符串
        String search = "Paper_Space";
        int psOffset = findString(data, search.getBytes());
        if (psOffset > 0) {
            System.out.println("找到 '" + search + "' @offset=" + psOffset + " (0x" + Integer.toHexString(psOffset) + ")");
            // 从字符串位置向前搜索, 找到对象的起始位置
            // 对象起始位置可能在字符串前 10-50 字节
            for (int back = 10; back < 100; back++) {
                int objStart = psOffset - back;
                if (objStart < 21000) continue;
                try {
                    // 尝试从这个位置位级读取 MS 和 type code
                    ByteBufferBitInput bbuf = new ByteBufferBitInput(data);
                    bbuf.seek(objStart * 8L); // 转换到位偏移
                    BitStreamReader reader = new BitStreamReader(bbuf, DwgVersion.R2000);

                    int objSize = reader.readModularShort();
                    long posAfterMS = bbuf.position();
                    int typeCode = reader.readBitShort();

                    // 合理的对象大小: 20-500, type code: 0-200
                    if (objSize > 10 && objSize < 2000 && typeCode >= 0 && typeCode < 500) {
                        System.out.println("  可能对象起始 @offset=" + objStart +
                            " objSize=" + objSize + " typeCode=0x" + Integer.toHexString(typeCode));
                        break;
                    }
                } catch (Exception e) {
                    // ignore
                }
            }
        }

        // 让我检查所有对象 - 用正确的位级方法
        System.out.println("\n=== 所有对象 (前 100 个) 的正确位级分析 ===");
        int matchCount = 0;
        for (int i = 0; i < Math.min(100, sortedHandles.size()); i++) {
            long h = sortedHandles.get(i);
            long off = registry.offsetFor(h).orElse(-1L);
            if (off > 0 && off < data.length - 50) {
                try {
                    ByteBufferBitInput bbuf = new ByteBufferBitInput(data);
                    bbuf.seek(off * 8L);
                    BitStreamReader reader = new BitStreamReader(bbuf, DwgVersion.R2000);

                    int objSize = reader.readModularShort();
                    // readModularShort 可能读不到对象大小, 让我读取下一个值
                    long posAfterMS = bbuf.position();
                    // 实际对象数据从这里开始
                    // 第一个字段是 type code (BS)
                    int typeCode = reader.readBitShort();

                    if (objSize > 0 && objSize < 10000 && typeCode >= 0 && typeCode < 1000) {
                        if (typeCode == 0x30 || typeCode == 0x31) { // BLOCK_HEADER 或 BLOCK_ENDBLK
                            System.out.println("  [" + i + "] handle=0x" + Long.toHexString(h) +
                                " offset=" + off + " objSize=" + objSize +
                                " typeCode=0x" + Integer.toHexString(typeCode) +
                                " <- BLOCK_HEADER/END");
                            matchCount++;
                        } else if (i < 20) {
                            System.out.println("  [" + i + "] handle=0x" + Long.toHexString(h) +
                                " offset=" + off + " objSize=" + objSize +
                                " typeCode=0x" + Integer.toHexString(typeCode));
                        }
                    } else if (i < 20) {
                        System.out.println("  [" + i + "] handle=0x" + Long.toHexString(h) +
                            " offset=" + off + " objSize=" + objSize +
                            " typeCode=" + typeCode + " (异常)");
                    }
                } catch (Exception e) {
                    if (i < 20) {
                        System.out.println("  [" + i + "] handle=0x" + Long.toHexString(h) +
                            " offset=" + off + " 错误: " + e.getMessage());
                    }
                }
            }
        }

        if (matchCount == 0) {
            System.out.println("  没有找到 typeCode=0x30 的对象! BLOCK_HEADER 可能用了不同的类型标识");
            // 让我检查 handle=1 的对象的完整位级读取
            System.out.println("\n=== handle=1 对象的详细位级读取 ===");
            try {
                ByteBufferBitInput bbuf = new ByteBufferBitInput(data);
                long off = registry.offsetFor(1L).orElse(-1L);
                bbuf.seek(off * 8L);
                BitStreamReader reader = new BitStreamReader(bbuf, DwgVersion.R2000);

                // 逐字段读取
                int v1 = reader.readModularShort();
                System.out.println("readModularShort() = " + v1 + " @bit=" + bbuf.position());

                int v2 = reader.readBitShort();
                System.out.println("readBitShort() = " + v2 + " (0x" + Integer.toHexString(v2) + ") @bit=" + bbuf.position());

                // 尝试读取 object common header
                // 根据 AutoCAD 规范: object 有 handle reference, xref 等
                // 让我尝试读取 variable text (TU)
                int textLen = reader.readBitShort();
                System.out.println("尝试 TU length = " + textLen + " @bit=" + bbuf.position());

                System.out.println("尝试 readText(): '" + reader.readText() + "'");

                // 另一种方法: 跳过可能的 object common header, 找 block_name
                // 让我重新开始, 尝试不同的读取顺序
                System.out.println("\n重新开始, 显示对象数据的位级结构:");
                bbuf = new ByteBufferBitInput(data);
                bbuf.seek(off * 8L);
                reader = new BitStreamReader(bbuf, DwgVersion.R2000);

                // 显示前 100 个字节作为 bits
                System.out.print("字节: ");
                for (int j = 0; j < 50; j++) {
                    System.out.printf("%02X ", data[(int)off + j] & 0xFF);
                }
                System.out.println();

                // 让我先检查 R2000 中实际上是否有 object 前缀
                // 对象格式可能是: obj_size (MS) + [object common fields] + type_code + ...
                // 或者 type code 在 common header 之前
                //
                // 让我检查第一个对象:
                // offset=21177, data: 72 00 4C 12 00 00 00 00 40 69 22 40 30 21 ...
                // readModularShort(72 00) = ?
                // 如果 modular short 是: 高位字节的最高位表示是否继续
                // 或者 modular short 可能就是普通的 LE16

                // 让我先检查 readModularShort 的实际行为
                // 字节 72 00 (LE16 = 114)
                // 如果是 "模块化 short" 格式, 可能有不同的含义

                // 让我重新用 readBitShort() 开头看看
                bbuf = new ByteBufferBitInput(data);
                bbuf.seek(off * 8L);
                reader = new BitStreamReader(bbuf, DwgVersion.R2000);

                // 顺序尝试: BS, BS, BS...
                System.out.println("\n连续 readBitShort():");
                for (int j = 0; j < 20; j++) {
                    int v = reader.readBitShort();
                    System.out.println("  [" + j + "] = " + v + " (0x" + Integer.toHexString(v) + ")");
                }

                // 再尝试: MS, MS, MS...
                bbuf = new ByteBufferBitInput(data);
                bbuf.seek(off * 8L);
                reader = new BitStreamReader(bbuf, DwgVersion.R2000);
                System.out.println("\n连续 readModularShort():");
                for (int j = 0; j < 20; j++) {
                    try {
                        int v = reader.readModularShort();
                        System.out.println("  [" + j + "] = " + v + " (0x" + Integer.toHexString(v) + ")");
                    } catch (Exception e) { break; }
                }

                // 检查: 如果 objSize=114, 那整个对象有 114 字节
                // 让我读取这 114 字节的末尾, 看看是否有字符串
                System.out.println("\n对象末尾 (114 bytes, offset+" + (114-40) + " to +" + 114 + "):");
                for (int j = 114-40; j < 114 && (int)off + j < data.length; j++) {
                    int b = data[(int)off + j] & 0xFF;
                    System.out.print((b >= 32 && b < 127) ? (char)b : '.');
                }
                System.out.println();

                // 现在让我用正确的方法检查:
                // 根据 ODA 规范, R2000 对象的格式是:
                //   obj_size (BS)  - 对象数据大小 (以字节为单位)
                //   [common object data]
                //     handle (H) - 对象的 handle
                //     owner_handle (H)
                //     reactors (optional)
                //     xdic_obj_handle (optional)
                //     num_attr (BS, optional)
                //     version (BS, optional)
                //   [specific object data]
                //   type_code (BS) - 实际上可能在 common header 内

                // 注意: type code 可能嵌入在 common header 中
                // 而不是紧跟 obj_size
            } catch (Exception e) {
                System.out.println("错误: " + e.getMessage());
                e.printStackTrace();
            }
        }

        // 让我也检查 INSERT 实体的 block_header_handle
        // 实际上 INSERT 读取到的是 0x986, 但是 handle registry 中没有 0x986
        // 让我看看 0x986 附近的字节
        System.out.println("\n=== 检查 block_header_handle=0x986 (2438) 的含义 ===");
        // handle=0x986 在 registry 中不存在
        // 但可能这是一个 "soft pointer" 或 "hard pointer" 引用
        // 在 R2000 中, handle reference 的编码可能不同
        // 让我看看: 0x986 = 2438
        // 让我检查 registry 中是否有接近的 handle
        for (long h : sortedHandles) {
            if (h >= 0x980L && h <= 0x990L) {
                System.out.println("  附近 handle: 0x" + Long.toHexString(h) +
                    " offset=" + registry.offsetFor(h).orElse(-1L));
            }
        }

        // 让我也检查: INSERT 的 block_header_handle 可能读取错位了
        // 让我找到一个 INSERT 并手动分析它的字节数据
        System.out.println("\n=== 手动检查 INSERT 实体 ===");
        DwgDocument doc = DwgReader.defaultReader().open(data);
        for (long h : sortedHandles) {
            DwgObject obj = doc.objectMap().get(h);
            if (obj instanceof DwgInsert) {
                long off = registry.offsetFor(h).orElse(-1L);
                System.out.println("INSERT handle=0x" + Long.toHexString(h) +
                    " offset=" + off);

                // 显示原始字节
                System.out.print("原始字节: ");
                for (int j = 0; j < 64; j++) {
                    if ((int)off + j >= data.length) break;
                    System.out.printf("%02X ", data[(int)off + j] & 0xFF);
                }
                System.out.println();

                // 显示 ASCII
                System.out.print("ASCII:    ");
                for (int j = 0; j < 64; j++) {
                    if ((int)off + j >= data.length) break;
                    int b = data[(int)off + j] & 0xFF;
                    System.out.print((b >= 32 && b < 127) ? (char)b : '.');
                }
                System.out.println();

                // 用位级读取逐步分析
                try {
                    ByteBufferBitInput bbuf = new ByteBufferBitInput(data);
                    bbuf.seek(off * 8L);
                    BitStreamReader reader = new BitStreamReader(bbuf, DwgVersion.R2000);

                    int objSize = reader.readModularShort();
                    System.out.println("obj_size: " + objSize);

                    // 检查 type_code
                    int typeCode = reader.readBitShort();
                    System.out.println("typeCode: 0x" + Integer.toHexString(typeCode) + "(" + typeCode + ")");

                    // 检查 INSERT 的 common entity data
                    // 先看看 R2000 的 entity header 格式
                    System.out.println("检查 entity common header 的位置:");
                    long currentBit = bbuf.position();
                    System.out.println("当前位偏移: " + currentBit + " (字节: " + (currentBit/8) + ")");

                    // 尝试读取 common object data
                    // 先试试: version handle
                    int versionHandle = reader.readBitShort();
                    System.out.println("可能 version_handle: " + versionHandle);

                    // 尝试读取 handle reference
                    // R2000 的 handle reference (H): 长度(BS) + handle(字节数组, 大端, 左零填充)
                    int handleLen = reader.readBitShort();
                    System.out.println("handle reference length: " + handleLen);

                    if (handleLen > 0 && handleLen < 16) {
                        long handleVal = 0;
                        long curBit = bbuf.position();
                        int bytePos = (int)(curBit / 8);
                        // 直接从 data 数组读取
                        for (int j = 0; j < handleLen && bytePos + j < data.length; j++) {
                            handleVal = (handleVal << 8) | (data[bytePos + j] & 0xFF);
                        }
                        // 也将 bit position 推进
                        bbuf.seek(curBit + handleLen * 8L);
                        System.out.println("handle reference value: 0x" + Long.toHexString(handleVal));
                    }
                } catch (Exception e) {
                    System.out.println("解析错误: " + e.getMessage());
                }

                break; // 只检查第一个
            }
        }
    }

    private static void analyzeObjectBitLevel(byte[] data, int offset, int bytesToShow) {
        // 显示原始字节
        System.out.print("原始字节: ");
        for (int j = 0; j < bytesToShow; j++) {
            if (offset + j >= data.length) break;
            System.out.printf("%02X ", data[offset + j] & 0xFF);
        }
        System.out.println();

        System.out.print("ASCII:    ");
        for (int j = 0; j < bytesToShow; j++) {
            if (offset + j >= data.length) break;
            int b = data[offset + j] & 0xFF;
            System.out.print((b >= 32 && b < 127) ? (char)b : '.');
        }
        System.out.println();

        // 用位级读取逐步分析
        try {
            ByteBufferBitInput bbuf = new ByteBufferBitInput(data);
            bbuf.seek(offset * 8L);
            BitStreamReader reader = new BitStreamReader(bbuf, DwgVersion.R2000);

            int objSize = reader.readModularShort();
            System.out.println("obj_size: " + objSize);

            // 检查 type_code
            int typeCode = reader.readBitShort();
            System.out.println("typeCode: 0x" + Integer.toHexString(typeCode) + "(" + typeCode + ")");
        } catch (Exception e) {
            System.out.println("位级解析错误: " + e.getMessage());
        }
    }

    private static int findString(byte[] data, byte[] pattern) {
        for (int i = 0; i < data.length - pattern.length; i++) {
            boolean match = true;
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    match = false;
                    break;
                }
            }
            if (match) return i;
        }
        return -1;
    }
}
