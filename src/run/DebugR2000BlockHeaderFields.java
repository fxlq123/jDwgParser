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
 * 深入分析 R2000 BLOCK_HEADER 对象的位级字段结构
 */
public class DebugR2000BlockHeaderFields {

    public static void main(String[] args) throws Exception {
        String filename = "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg";
        byte[] data = java.nio.file.Files.readAllBytes(Paths.get(filename));

        System.out.println("=== 深入分析 R2000 BLOCK_HEADER 字段 ===\n");

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

        // 分析 BLOCK_HEADER 对象 (handle=1, offset=21177)
        long blockHeaderOffset = registry.offsetFor(1L).orElse(-1L);
        System.out.println("=== BLOCK_HEADER (handle=1) @offset=" + blockHeaderOffset + " ===");
        analyzeBlockHeader(data, (int)blockHeaderOffset, "BLOCK_HEADER #1");

        // 分析 BLOCK_ENDBLK 对象 (handle=0x1b, offset=23203)
        long endBlkOffset = registry.offsetFor(0x1bL).orElse(-1L);
        if (endBlkOffset > 0) {
            System.out.println("\n=== BLOCK_ENDBLK (handle=0x1b) @offset=" + endBlkOffset + " ===");
            analyzeBlockHeader(data, (int)endBlkOffset, "BLOCK_ENDBLK");
        }

        // 分析包含 "Paper_Space" 的 BLOCK_HEADER
        // 查找 typeCode=0x31 且包含字符串的对象
        System.out.println("\n=== 查找所有 BLOCK_HEADER/END 并分析 ===");
        for (long h : sortedHandles) {
            long off = registry.offsetFor(h).orElse(-1L);
            if (off < 0 || off > data.length - 20) continue;

            try {
                ByteBufferBitInput bbuf = new ByteBufferBitInput(data);
                bbuf.seek(off * 8L);
                BitStreamReader reader = new BitStreamReader(bbuf, DwgVersion.R2000);

                int objSize = reader.readModularShort();
                int typeCode = reader.readBitShort();

                if (typeCode == 0x30 || typeCode == 0x31) {
                    System.out.println("\n[BLOCK type=0x" + Integer.toHexString(typeCode) +
                        "] handle=0x" + Long.toHexString(h) + " offset=" + off + " size=" + objSize);
                    analyzeBlockHeader(data, (int)off,
                        (typeCode == 0x30 ? "HEADER" : "END") + " h=0x" + Long.toHexString(h));
                }
            } catch (Exception e) {
                // skip
            }
        }

        // 现在让我分析 INSERT 实体的 block_header_handle 字段
        System.out.println("\n=== 深入分析 INSERT 实体 ===");
        DwgDocument doc = DwgReader.defaultReader().open(data);
        for (long h : sortedHandles) {
            DwgObject obj = doc.objectMap().get(h);
            if (obj instanceof DwgInsert) {
                long off = registry.offsetFor(h).orElse(-1L);
                System.out.println("\nINSERT handle=0x" + Long.toHexString(h) + " offset=" + off);
                analyzeInsertEntity(data, (int)off);
                break; // 先只分析一个
            }
        }
    }

    private static void analyzeBlockHeader(byte[] data, int offset, String label) {
        try {
            // 显示原始字节
            System.out.println(label + ":");
            System.out.print("  原始字节: ");
            for (int i = 0; i < 80; i++) {
                if (offset + i >= data.length) break;
                System.out.printf("%02X ", data[offset + i] & 0xFF);
            }
            System.out.println();

            System.out.print("  ASCII:    ");
            for (int i = 0; i < 80; i++) {
                if (offset + i >= data.length) break;
                int b = data[offset + i] & 0xFF;
                System.out.print((b >= 32 && b < 127) ? (char)b : '.');
            }
            System.out.println();

            // 位级分析
            ByteBufferBitInput bbuf = new ByteBufferBitInput(data);
            bbuf.seek(offset * 8L);
            BitStreamReader reader = new BitStreamReader(bbuf, DwgVersion.R2000);

            int objSize = reader.readModularShort();
            int typeCode = reader.readBitShort();
            System.out.println("  obj_size: " + objSize);
            System.out.println("  typeCode: 0x" + Integer.toHexString(typeCode));
            System.out.println("  起始bit (after obj_size+typeCode): " + bbuf.position() +
                " (字节: " + (bbuf.position()/8) + ")");

            // BLOCK_HEADER 的 common object data:
            // 参考: object common data 可能包含:
            //   - no_links (B) [可选]
            //   - object_version (BS) [可选]
            //   - handle_to_owner_object (H)
            //   - num_reactors (BS)
            //   - reactor_handles (多个 H) [如果 num_reactors > 0]
            //   - xdict_obj_handle (H) [可选]
            //   - num_attr (BS) [可选]
            //   - version (BS) [可选]

            // 让我逐步尝试读取
            // 方法1: 先尝试读取 "common object header" 中的字段
            // 但是 BLOCK_HEADER 是非实体对象, 它的 common header 可能与 entity 不同

            // 让我直接尝试读取 block_name (TU)
            // 先跳过 object common data
            long curPos = bbuf.position();

            // 方法1: 尝试从当前位置直接读取文本
            // 根据 ODA 规范, R2000 BLOCK_HEADER 的结构:
            // common object data:
            //   handle_to_owner_object (H)
            //   num_reactors (BS) -> 通常是 0
            //   xdict_obj_handle (H) [optional]
            // specific data:
            //   block_name (TU)
            //   flags (BS)
            //   base_point (3BD)
            //   xref_path (TU)

            // 先读取 handle reference (H)
            // H 格式: length (BS) + handle bytes (length 个字节, big-endian)
            int hLen1 = reader.readBitShort();
            System.out.println("  handle_to_owner length: " + hLen1 + " @bit=" + bbuf.position());

            if (hLen1 > 0 && hLen1 < 8) {
                long ownerHandle = 0;
                int byteStart = (int)(bbuf.position() / 8);
                for (int i = 0; i < hLen1 && byteStart + i < data.length; i++) {
                    ownerHandle = (ownerHandle << 8) | (data[byteStart + i] & 0xFF);
                }
                // 跳过这些字节
                bbuf.seek(bbuf.position() + hLen1 * 8L);
                System.out.println("  handle_to_owner value: 0x" + Long.toHexString(ownerHandle));
            } else {
                System.out.println("  (无效, 跳过 handle_to_owner)");
                bbuf.seek(curPos); // 恢复
                reader = new BitStreamReader(bbuf, DwgVersion.R2000);
                reader.readBitShort(); // 重新同步
                reader.readBitShort();
            }

            // 读取 num_reactors
            int numReactors = reader.readBitShort();
            System.out.println("  num_reactors: " + numReactors + " @bit=" + bbuf.position());

            if (numReactors > 0 && numReactors < 100) {
                // 跳过 reactor handles
                for (int i = 0; i < numReactors; i++) {
                    int rLen = reader.readBitShort();
                    if (rLen > 0 && rLen < 8) {
                        bbuf.seek(bbuf.position() + rLen * 8L);
                    }
                }
            } else if (numReactors != 0) {
                System.out.println("  (num_reactors 异常, 可能位置不对)");
            }

            // 尝试读取 xdict_obj_handle (H) [可选]
            // 注意: 这里可能有问题, 因为 xdict 可能不存在
            // 让我们先尝试直接读取 block_name
            long posBeforeBlockName = bbuf.position();
            System.out.println("  可能 block_name 起始 @bit=" + posBeforeBlockName +
                " (字节: " + (posBeforeBlockName/8) + ")");

            // 尝试读取 TU (block_name)
            // TU 格式: length (BS) + ASCII 文本 (length 个字节)
            int nameLen = reader.readBitShort();
            System.out.println("  可能 block_name length: " + nameLen);

            if (nameLen > 0 && nameLen < 100) {
                int nameByteStart = (int)(bbuf.position() / 8);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < nameLen && nameByteStart + i < data.length; i++) {
                    int c = data[nameByteStart + i] & 0xFF;
                    if (c >= 32 && c < 127) {
                        sb.append((char)c);
                    } else {
                        sb.append('?');
                    }
                }
                bbuf.seek(bbuf.position() + nameLen * 8L);
                System.out.println("  block_name: '" + sb + "'");
            } else {
                System.out.println("  (block_name length 无效, 可能字段顺序不对)");
                // 恢复到前面, 试试其他方式
                bbuf.seek(posBeforeBlockName);
            }

            // 现在尝试用另一种方法:
            // 跳过 type_code 后的 6 字节 (可能是 object common header 的一部分)
            // 直接找字符串
            System.out.println("\n  方法2: 从不同偏移尝试读取文本:");
            for (int skipBits = 0; skipBits < 200; skipBits += 8) {
                try {
                    ByteBufferBitInput bbuf2 = new ByteBufferBitInput(data);
                    bbuf2.seek(offset * 8L + 32L + skipBits); // 跳过 obj_size(16) + type(16)
                    BitStreamReader reader2 = new BitStreamReader(bbuf2, DwgVersion.R2000);

                    int len = reader2.readBitShort();
                    if (len > 0 && len < 50) {
                        int byteStart = (int)(bbuf2.position() / 8);
                        StringBuilder sb = new StringBuilder();
                        boolean printable = true;
                        for (int i = 0; i < len && byteStart + i < data.length; i++) {
                            int c = data[byteStart + i] & 0xFF;
                            if (c >= 32 && c < 127) {
                                sb.append((char)c);
                            } else {
                                printable = false;
                                break;
                            }
                        }
                        if (printable && sb.length() >= 2) {
                            System.out.println("  [+" + skipBits + "bits] len=" + len +
                                " text: '" + sb + "'");
                        }
                    }
                } catch (Exception e) {
                    // skip
                }
            }

            // 方法3: 直接显示对象内的文本
            System.out.println("\n  对象内的字符串:");
            for (int startPos = 4; startPos < objSize - 4; startPos++) {
                int bytePos = offset + startPos;
                if (bytePos >= data.length) break;
                StringBuilder sb = new StringBuilder();
                int len = 0;
                while (bytePos + len < data.length && len < 40) {
                    int c = data[bytePos + len] & 0xFF;
                    if (c >= 32 && c < 127) {
                        sb.append((char)c);
                        len++;
                    } else {
                        break;
                    }
                }
                if (len >= 3) {
                    // 检查字符串前的 2 字节是否是 length (BS)
                    int lenBefore = (data[bytePos - 2] & 0xFF) | ((data[bytePos - 1] & 0xFF) << 8);
                    System.out.println("  [offset+" + startPos + "] (前值=" + lenBefore + ") '" + sb + "'");
                    startPos += len;
                }
            }

        } catch (Exception e) {
            System.out.println("  分析错误: " + e.getMessage());
        }
    }

    private static void analyzeInsertEntity(byte[] data, int offset) {
        try {
            ByteBufferBitInput bbuf = new ByteBufferBitInput(data);
            bbuf.seek(offset * 8L);
            BitStreamReader reader = new BitStreamReader(bbuf, DwgVersion.R2000);

            int objSize = reader.readModularShort();
            int typeCode = reader.readBitShort();
            System.out.println("  obj_size: " + objSize);
            System.out.println("  typeCode: 0x" + Integer.toHexString(typeCode) + "(" + typeCode + ")");

            // INSERT 是 entity, 有 entity common header
            // R2000 Entity common data:
            //   object_size (已读)
            //   entity_mode (BS) - 可能没有, 直接 object common header
            //   version (BS) [optional]
            //   handle_to_owner (H)
            //   num_attr (BS) [optional]
            //   handle (H)
            //   layer_handle (H)
            //   linetype_handle (H) [optional]
            //   color_index (BS)
            //   line_type_scale (BD) [optional]
            //   visibility (BS)
            //   lineweight (BS) [optional]
            // specific data:
            //   block_header_handle (H)
            //   insertion_point (3BD)
            //   scale_factor (3BD) [optional]
            //   rotation_angle (BD)
            //   attribs_follow (BS)

            System.out.println("\n  INSERT 字段分析:");

            // 先尝试读取 common object data
            // 读取 owner handle
            int hLen = reader.readBitShort();
            System.out.println("  owner_handle length: " + hLen);
            if (hLen > 0 && hLen < 8) {
                long h = readHandleBytes(data, bbuf.position(), hLen);
                bbuf.seek(bbuf.position() + hLen * 8L);
                System.out.println("  owner_handle: 0x" + Long.toHexString(h));
            }

            // 尝试读取 num_attr
            int numAttr = reader.readBitShort();
            System.out.println("  num_attr: " + numAttr);

            // entity handle
            hLen = reader.readBitShort();
            System.out.println("  entity_handle length: " + hLen);
            if (hLen > 0 && hLen < 8) {
                long h = readHandleBytes(data, bbuf.position(), hLen);
                bbuf.seek(bbuf.position() + hLen * 8L);
                System.out.println("  entity_handle: 0x" + Long.toHexString(h));
            }

            // layer handle
            hLen = reader.readBitShort();
            System.out.println("  layer_handle length: " + hLen);
            if (hLen > 0 && hLen < 8) {
                long h = readHandleBytes(data, bbuf.position(), hLen);
                bbuf.seek(bbuf.position() + hLen * 8L);
                System.out.println("  layer_handle: 0x" + Long.toHexString(h));
            }

            // linetype handle (可能是 0 长度)
            hLen = reader.readBitShort();
            System.out.println("  linetype_handle length: " + hLen);
            if (hLen > 0 && hLen < 8) {
                long h = readHandleBytes(data, bbuf.position(), hLen);
                bbuf.seek(bbuf.position() + hLen * 8L);
                System.out.println("  linetype_handle: 0x" + Long.toHexString(h));
            }

            // color index
            int colorIndex = reader.readBitShort();
            System.out.println("  color_index: " + colorIndex);

            // 跳过可能的 linetype scale (8 bytes) 和 visibility (2 bytes)
            // lineweight (2 bytes)

            // block_header_handle (H) - 关键字段!
            hLen = reader.readBitShort();
            System.out.println("  block_header_handle length: " + hLen);
            if (hLen > 0 && hLen < 8) {
                long h = readHandleBytes(data, bbuf.position(), hLen);
                bbuf.seek(bbuf.position() + hLen * 8L);
                System.out.println("  block_header_handle: 0x" + Long.toHexString(h));
            }

            // 插入点 (3BD) - 12 bytes
            double x = reader.readBitDouble();
            double y = reader.readBitDouble();
            double z = reader.readBitDouble();
            System.out.printf("  insertion_point: (%.4f, %.4f, %.4f)%n", x, y, z);

            // 旋转角度 (BD)
            double rot = reader.readBitDouble();
            System.out.printf("  rotation_angle: %.4f%n", rot);

        } catch (Exception e) {
            System.out.println("  分析错误: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static long readHandleBytes(byte[] data, long bitPos, int byteLen) {
        int bytePos = (int)(bitPos / 8);
        long result = 0;
        for (int i = 0; i < byteLen && bytePos + i < data.length; i++) {
            result = (result << 8) | (data[bytePos + i] & 0xFF);
        }
        return result;
    }
}
