package run;

import io.dwg.core.io.*;
import io.dwg.core.version.DwgVersion;
import io.dwg.format.common.*;
import io.dwg.sections.handles.*;
import java.nio.file.Paths;
import java.util.*;

/**
 * 精确分析 R2000 BLOCK 定义对象的位级解析
 *
 * BLOCK_HEADER 对象结构 (R2000):
 *   0: MS (2 bytes, 位级) = obj_size
 *   2: BS (2 bytes bit-packed after MS) = type_code (0x30)
 *   then: BLOCK 特有字段:
 *     num_entries (BS?), entries (HANDLE 数组)
 *
 * BLOCK 定义对象 (type=0x31):
 *   MS + BS type_code (0x31) + object common (owner_handle_H + reactors + xdict) +
 *   block_name(TU) + flags(BS) + base_point(3RD) + xref(TU)
 */
public class AnalyzeR2000BlockFields {

    static byte[] data;

    public static void main(String[] args) throws Exception {
        String filename = "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg";
        byte[] fileData = java.nio.file.Files.readAllBytes(Paths.get(filename));
        data = fileData;

        DwgFileStructureHandler handler = DwgFileStructureHandlerFactory.forVersion(DwgVersion.R2000);
        ByteBufferBitInput input = new ByteBufferBitInput(data);
        FileHeaderFields header = handler.readHeader(input);
        input = new ByteBufferBitInput(data);
        Map<String, SectionInputStream> sections = handler.readSections(input, header);

        SectionInputStream handlesSec = sections.get("AcDb:Handles");
        HandlesSectionParser hsp = new HandlesSectionParser();
        HandleRegistry registry = hsp.parse(handlesSec, DwgVersion.R2000);

        // === 分析 BLOCK_HEADER (0x30): 通常只有 1 个
        //  分析 BLOCK (0x31): 应该是真正的块定义 - 可能有多个
        System.out.println("=== 分析 BLOCK_HEADER (0x30) 与 BLOCK (0x31) 对象 ===\n");

        for (long h : new ArrayList<>(registry.allHandles())) {
            long offset = registry.offsetFor(h).orElse(-1L);
            if (offset < 0) continue;

            ByteBufferBitInput bbuf = new ByteBufferBitInput(data);
            bbuf.seek(offset * 8L);
            BitStreamReader r = new BitStreamReader(bbuf, DwgVersion.R2000);
            int objSize = r.readModularShort();
            int typeCode = r.readBitShort();

            if (typeCode == 0x30) {
                analyzeBlockHeader(h, offset, objSize, bbuf, r);
            } else if (typeCode == 0x31) {
                analyzeBlock(h, offset, objSize, bbuf, r);
            }
        }

        // === 分析 INSERT (0x07) 实体
        System.out.println("\n=== INSERT (0x07) INSERT 实体 ===\n");
        int insertCount = 0;
        for (long h : new ArrayList<>(registry.allHandles())) {
            long offset = registry.offsetFor(h).orElse(-1L);
            if (offset < 0) continue;

            ByteBufferBitInput bbuf = new ByteBufferBitInput(data);
            bbuf.seek(offset * 8L);
            BitStreamReader r = new BitStreamReader(bbuf, DwgVersion.R2000);
            int objSize = r.readModularShort();
            int typeCode = r.readBitShort();
            if (typeCode == 0x07) {
                analyzeInsert(h, offset, objSize, bbuf, r);
                insertCount++;
                if (insertCount > 3) break;
            }
        }
    }

    private static void analyzeBlockHeader(long handle, long offset, int objSize,
                                         ByteBufferBitInput bbuf, BitStreamReader r) {
        System.out.println("【BLOCK_HEADER handle=0x" + Long.toHexString(handle) +
            " @offset=" + offset + " obj_size=" + objSize + "】");

        // 打印原始字节
        System.out.print("  raw: ");
        for (int i = 4; i < Math.min(4 + 64, objSize); i++) {
            System.out.printf("%02X ", data[(int)offset + i] & 0xFF);
            if ((i - 4) % 16 == 15) System.out.print("\n       ");
        }
        System.out.println();

        // BLOCK_HEADER: 是一个表对象，包含 num_entries + entries[]
        // 尝试读取: object-common (owner_H + reactors + xdict) + entries
        // 注意 BLOCK_HEADER 不同于普通 object，可能没有 entity header

        // 方案：直接读 object common data
        try {
            // 读取 owner handle
            long owner = r.readHandle();
            System.out.println("  owner: 0x" + Long.toHexString(owner));

            // num reactors (BS)
            int nr = r.readBitShort();
            System.out.println("  num_reactors: " + nr);

            if (nr > 0 && nr < 20) {
                for (int i = 0; i < nr; i++) {
                long rh = r.readHandle();
                }
            }

            // xdict handle
            long xdict = r.readHandle();
            System.out.println("  xdict: 0x" + Long.toHexString(xdict));

            // entries: 可能是 entries[]
            // 这里 block_name (TV) + block_end_handle(H) + entity_handles
            // 尝试各种可能

            long afterCommon = bbuf.position();
            System.out.println("  after common @bit=" + afterCommon + " (@byte=" + afterCommon/8);

            // 读取 num_entries (BS)? 尝试作为表
            // 实际上 BLOCK_HEADER 是一个 TABLE 样式的对象
            // 可能的字段：
            // block_name(TV) + handle
            // + block_end_handle(H)
            // + first_entity_handle (如果有的话
            // + ...

            // 方案 1: 读 TV 后直接是 handle
            // 读取 block_name
            try {
                String name = r.readText();
                System.out.println("  block_name(T): '" + name + "'");
            } catch (Exception e) {
                System.out.println("  block_name(T) failed: " + e.getMessage());
            }
            bbuf.seek(afterCommon);

            try {
                String name = r.readVariableText();
                System.out.println("  block_name(TV): '" + name + "'");
            } catch (Exception e) {
                System.out.println("  block_name(TV) failed: " + e.getMessage());
            }
            bbuf.seek(afterCommon);

        } catch (Exception e) {
            System.out.println("  common failed: " + e.getMessage());
        }
    }

    private static void analyzeBlock(long handle, long offset, int objSize,
                                     ByteBufferBitInput bbuf, BitStreamReader r) {
        System.out.println("【BLOCK handle=0x" + Long.toHexString(handle) +
            " @offset=" + offset + " obj_size=" + objSize + "】");

        // 打印原始字节 (前 32 字节)
        System.out.print("  raw bytes: ");
        for (int i = 4; i < Math.min(4 + 32, objSize); i++) {
            System.out.printf("%02X ", data[(int)offset + i] & 0xFF);
        }
        System.out.println();

        // ASCII print
        System.out.print("  ascii:     ");
        for (int i = 4; i < Math.min(4 + 32, objSize); i++) {
            int b = data[(int)offset + i] & 0xFF;
            System.out.print((b >= 32 && b < 127) ? ((char) b) : '.');
        }
        System.out.println();

        // 尝试解析 BLOCK 定义对象
        try {
            long owner = r.readHandle();
            System.out.println("  owner: 0x" + Long.toHexString(owner));

            int nr = r.readBitShort();
            System.out.println("  num_reactors: " + nr);
            if (nr > 0 && nr < 20) {
                for (int i = 0; i < nr; i++) {
                    long rh = r.readHandle();
                    System.out.println("  reactor[" + i + "]: 0x" + Long.toHexString(rh));
                }
            }
            long xdict = r.readHandle();
            System.out.println("  xdict: 0x" + Long.toHexString(xdict));

            long afterCommon = bbuf.position();
            System.out.println("  after common @bit=" + afterCommon);

            // block_name (T/TV)
            try {
                String name = r.readText();
                System.out.println("  block_name(T): '" + name + "'");
            } catch (Exception e) {
                System.out.println("  block_name(T) failed: " + e.getMessage());
            }
            bbuf.seek(afterCommon);
            try {
                String name = r.readVariableText();
                System.out.println("  block_name(TV): '" + name + "'");
            } catch (Exception e) {
                System.out.println("  block_name(TV) failed: " + e.getMessage());
            }
            bbuf.seek(afterCommon);

            // 尝试 TU (BS + ASCII bytes) - 不跳过
            // 从当前位置尝试读取 BS 长度 + ASCII
            int nameLen = r.readBitShort();
            System.out.println("  block_name BS length: " + nameLen);
            if (nameLen > 0 && nameLen < 100) {
                byte[] nameBytes = new byte[nameLen];
                for (int i = 0; i < nameLen; i++) {
                    nameBytes[i] = (byte) (r.getInput().readBits(8) & 0xFF);
                }
                String n = new String(nameBytes, java.nio.charset.StandardCharsets.US_ASCII);
                System.out.println("  block_name(BS+ASCII): '" + n + "'");
            } else {
                // nameLen 异常，说明这个结构不对
                System.out.println("  nameLen 不正常 (" + nameLen + ") - 重置");
                bbuf.seek(afterCommon);
            }

        } catch (Exception e) {
            System.out.println("  block field parse failed: " + e.getMessage());
        }

        System.out.println();
    }

    private static void analyzeInsert(long handle, long offset, int objSize,
                                     ByteBufferBitInput bbuf, BitStreamReader r) {
        System.out.println("【INSERT handle=0x" + Long.toHexString(handle) +
            " @offset=" + offset + " obj_size=" + objSize + "】");

        // INSERT 是实体，需要读取 entity header
        try {
            // entity header: bitsize(RL) + handle(H) + EED(loop BS-while)
            // R2000: entity header structure

            // bitsize (RL) = 4 字节 raw bytes
            int bitSize = readRawLong(r);
            System.out.println("  bitsize(RL): " + bitSize);

            long entHandle = r.readHandle();
            System.out.println("  entity_handle: 0x" + Long.toHexString(entHandle));

            // EED loop
            int eedSize = r.readBitShort();
            int eedCount = 0;
            while (eedSize != 0 && eedSize > 0 && eedSize < 0x7FFF && eedCount < 20) {
                // read appid handle
                r.readHandle();
                // read eedSize bytes
                for (int i = 0; i < eedSize; i++) {
                    r.getInput().readBits(8);
                }
                eedSize = r.readBitShort();
                eedCount++;
            }
            System.out.println("  eed_loops: " + eedCount);

            long afterHeader = bbuf.position();
            System.out.println("  after entity header @bit=" + afterHeader);

            // common entity data
            // R2000 common entity data:
            // preview_exists(B) + if=1 → preview_size(RL)+preview_data
            // nolinks(B)?
            // layer_handle(H)
            // linetype_handle(H)
            // color(BS)
            // ... etc
            // Actually R2000 common entity data order:
            // preview_exists + bitsize(RL) → NO:
            // entmode(BB) + num_reactors(BS/BL?) + xdict_handle(H) + ...

            boolean previewExists = r.getInput().readBit();
            System.out.println("  preview_exists: " + previewExists);
            if (previewExists) {
                long previewSize = readRawLong(r);
                bbuf.seek(bbuf.position() + previewSize * 8L);
                System.out.println("  preview_size: " + previewSize);
            }

            // entity mode
            int entMode = r.getInput().readBits(2);
            System.out.println("  entity_mode: " + entMode);

            // num reactors (BS or BL)
            int numReactors = r.readBitLong();
            System.out.println("  num_reactors(BL): " + numReactors);
            if (numReactors > 0 && numReactors < 50) {
                for (int i = 0; i < numReactors; i++) {
                    r.readHandle();
                }
            }

            long xdict = r.readHandle();
            System.out.println("  xdict_handle: 0x" + Long.toHexString(xdict));

            // layer
            long layer = r.readHandle();
            System.out.println("  layer_handle: 0x" + Long.toHexString(layer));

            // linetype
            long ltype = r.readHandle();
            System.out.println("  linetype_handle: 0x" + Long.toHexString(ltype));

            // color
            int color = r.readBitShort();
            System.out.println("  color(BS): " + color);

            // 可能还有其他：lweight, invisible, thickness, extrusion 等...
            // 关键：block_header_handle(H)

            // 尝试几种可能顺序

            // Scale 因子 3BD
            try {
                double sx = r.readBitDouble();
                double sy = r.readBitDouble();
                double sz = r.readBitDouble();
                System.out.printf("  scale: (%.4f, %.4f, %.4f)%n", sx, sy, sz);
            } catch (Exception e) {
                System.out.println("  scale failed: " + e.getMessage());
            }

            // rotation
            try {
                double rot = r.readBitDouble();
                System.out.printf("  rotation: %.6f%n", rot);
            } catch (Exception e) {
                System.out.println("  rotation failed: " + e.getMessage());
            }

            // insertion point 3BD
            try {
                double ix = r.readBitDouble();
                double iy = r.readBitDouble();
                double iz = r.readBitDouble();
                System.out.printf("  insertion: (%.4f, %.4f, %.4f)%n", ix, iy, iz);
            } catch (Exception e) {
                System.out.println("  insertion failed: " + e.getMessage());
            }

            // attribs follow (BS)
            try {
                int af = r.readBitShort();
                System.out.println("  attribs_follow: " + af);
            } catch (Exception e) {
                System.out.println("  attribs failed: " + e.getMessage());
            }
        } catch (Exception e) {
            System.out.println("  insert parse failed: " + e.getMessage());
            e.printStackTrace();
        }
        System.out.println();
    }

    private static int readRawLong(BitStreamReader r) {
        BitInput input = r.getInput();
        long b0 = input.readBits(8) & 0xFF;
        long b1 = input.readBits(8) & 0xFF;
        long b2 = input.readBits(8) & 0xFF;
        long b3 = input.readBits(8) & 0xFF;
        return (int)(b0 | (b1 << 8) | (b2 << 16) | (b3 << 24));
    }
}
