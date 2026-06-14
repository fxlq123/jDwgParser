package run;

import io.dwg.core.io.*;
import io.dwg.core.version.DwgVersion;
import io.dwg.format.common.*;
import io.dwg.sections.handles.*;
import java.nio.file.Paths;
import java.util.*;

/**
 * 基于直接字节级定位的 R2000 字段解析
 * 关键观察：
 * 1. obj_size (MS 1-2 bytes) + type_code (BS bit-level) + ... 数据
 * 2. BLOCK (0x31) 的 block_name ASCII 字符串起始于字节 12
 *    例如 offset 23203: bytes 12-22 = 50 61 70 65 72 5F 53 70 61 63 65 = "Paper_Space"
 * 3. block_name 后面: C0 55 00 52 08 09 82 81 ...
 *    - 0xC0: 可能是 flags 或 end marker
 *    - 0x55 00 52 08 09 82 81: 可能是 handle 或其他
 * 4. INSERT (0x07) 的 block_header_handle 需要找到
 *
 * 让我尝试从 type_code 结束后的 bit_pos 开始正确解析：
 * MS(obj_size) 从 byte 0 开始，BS(type_code) 从 <MS结束> 开始
 * 对 obj_size=37 (0x25): 占 1 byte (bit 0-7)
 * 对 obj_size=114 (0x72): 占 1 byte? wait: word0=0x0072, high bit=0, value=114
 *   但 MS 可能以 16-bit word 方式工作，所以占 2 bytes?
 *
 * 之前：obj_size=114 后 bit pos = 169432 = offset*8 + 16 (21177*8=169416, +16=169432)
 * 所以 MS 占用 16 bits = 2 bytes
 * obj_size=37 后 bit pos = 185640 = 23203*8 + 16 = 185624+16=185640 ✓
 *
 * 所以 MS 总是 16-bit (2 bytes) = word0 & 0x7FFF
 * 然后 BS(type_code) 从 byte 2 (bit 16) 开始
 *
 * BS type_code:
 *   offset 21177 (BLOCK_HEADER): bit pos 169416+16=169432
 *     bytes 2-3 = 4C 12 = 0100_1100_0001_0010
 *     opcode = bit[0] bit[1] = 01 -> 8-bit mode
 *     然后读 8 bits = bit[2..9] = 00_1100_0001? No wait:
 *     Let me trace: byte2=0x4C=0100_1100, byte3=0x12=0001_0010
 *     从 byte2 bit0: 0 1 0 0 | 1 1 0 0  0 0 0 1 | 0 0 1 0
 *     BS: opcode=01 -> 读 8 bits from position 2: 00_1100_0001
 *       0011_0000? Let me count: after 2 bits (opcode), 16 bits follow (if opcode=00)
 *       opcode=01: after first 2 bits, read next 8 bits
 *       bits: 0(0), 1(1) -> opcode 01
 *       next 8 bits: 0(2), 0(3), 1(4), 1(5), 0(6), 0(7), 0(8), 0(9)
 *         = 00110000 = 0x30 = 48 ✓!
 *
 *   offset 23203 (BLOCK): bytes 2-3 = 4C 71 = 0100_1100_0111_0001
 *     opcode=01 (bits 0,1)
 *     next 8 bits = 00_1100_0111 = 00110001 = 0x31 = 49 ✓!
 *
 *   offset 50409 (INSERT): bytes 2-3 = 41 F9 = 0100_0001_1111_1001
 *     opcode=01
 *     next 8 bits = 00_0001_1111 = 00000111 = 0x07 = 7 ✓!
 *
 * 结论：BS type_code 格式:
 *   - opcode=01 (2 bits)
 *   - 然后 8 bits = value (low 8 bits of first byte)
 *   - 所以 type_code = (byte2 & 0x3F) << 2 | (byte3 >> 6)? 等等
 *
 *   让我重新算： byte2=0x4C=01001100, byte3=0x12=00010010
 *   bit stream: 0 1 0 0 1 1 0 0  0 0 0 1 0 0 1 0 (字节 2 和 3)
 *   位置:       0 1 2 3 4 5 6 7  8 9 10 11 12 13 14 15
 *   opcode = bits 0,1 = 01
 *   接下来 8 bits = bits 2-9 = 00110000 = 0x30
 *   验证: 4C = 0100_1100, bits 2-7 of byte2 = 00_1100 = 0x0C? 不对
 *   让我直接看: opcode=01 意味着 "8-bit value from 16-bit input, shifted"
 *   实际上 readBitShort 代码:
 *     opcode 00 -> read 16-bit LE
 *     opcode 01 -> read 8-bit (low 8 after opcode bits)
 *     opcode 10 -> return 0
 *     opcode 11 -> return 256
 *
 *   对 bytes 4C 12: bit stream = 0100_1100_0001_0010
 *   - 从 bit 0 开始: bit0=0, bit1=1 -> opcode=01
 *   - 接下来 8 bits = bit2=0, bit3=0, bit4=1, bit5=1, bit6=0, bit7=0, bit8=0, bit9=0
 *     = 00110000 = 0x30 = 48 ✓
 *   所以 type_code 从 bit 2 开始读 8 位
 *
 *   type_code 字段占用 10 bits 总（2 opcode + 8 data）
 *   所以 type_code 后 bit pos = start_bit + 10
 *   验证: offset 21177, type=0x30
 *     start_bit = 21177*8 + 16 = 169416+16 = 169432 (MS 占 16 bits)
 *     after type_code bit pos = 169432 + 10 = 169442 ✓ (匹配之前的 after type 169442)
 *
 * OK！现在我懂了。让我用这个知识来正确解析对象的后续字段。
 *
 * BLOCK_HEADER (0x30) 对象结构 (对象类型，不是实体):
 *   - 公共对象数据: owner_handle(H) + num_reactors(BS) + H*num + xdict_handle(H)
 *   - 然后 BLOCK_HEADER 特定: block_name(TU) + block_flags(BS) + base_point(3RD)
 *
 * BLOCK (0x31) 实体结构:
 *   - 实体公共数据: bitsize(RL) + entity_handle(H) + EED_size(BS) + preview_exists(1bit)
 *                 + ent_mode(2bits) + num_reactors(BS) + H*num + xdict_handle(H)
 *                 + layer_handle(H) + linetype_handle(H) + color(BS)
 *   - BLOCK 特定: block_name(TV) + flags(BS) + ...
 *
 * INSERT (0x07) 实体结构:
 *   - 实体公共数据（同上）
 *   - INSERT 特定: block_header_handle(H) + insertion_point(3BD) + scale(3BD)
 *                 + rotation_angle(BD) + ...
 */
public class AnalyzeR2000ProperFields {

    static byte[] data;

    public static void main(String[] args) throws Exception {
        String filename = "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg";
        data = java.nio.file.Files.readAllBytes(Paths.get(filename));

        // 1. BLOCK (0x31) 详细分析
        System.out.println("========== BLOCK (0x31) 分析 ==========");
        for (long offset : new long[]{23203, 23159, 21677, 21719}) {
            analyzeBlockEntity(offset);
        }

        // 2. BLOCK_HEADER (0x30) 详细分析
        System.out.println("\n========== BLOCK_HEADER (0x30) 分析 ==========");
        analyzeBlockHeaderObject(21177);

        // 3. INSERT (0x07) 详细分析
        System.out.println("\n========== INSERT (0x07) 分析 ==========");
        for (long offset : new long[]{50409, 52467}) {
            analyzeInsertEntity(offset);
        }
    }

    /**
     * 分析 BLOCK 实体 (type 0x31)
     * 结构: obj_size(MS) + type_code(BS) + entity_common + block_specific
     * entity_common: bitsize(RL) + entity_handle(H) + EED_size(BS) + preview_exists(1bit)
     *              + ent_mode(2bits) + num_reactors(BS) + H*num + xdict_handle(H)
     *              + layer_handle(H) + linetype_handle(H) + color(BS)
     * block_specific: block_name(TV) + block_end_handle(H) + flags(BS)
     *               + base_point(3RD) + xref_path(TV)
     */
    private static void analyzeBlockEntity(long offset) throws Exception {
        ByteBufferBitInput bb = new ByteBufferBitInput(data);
        bb.seek(offset * 8L);
        BitStreamReader r = new BitStreamReader(bb, DwgVersion.R2000);

        int objSize = r.readModularShort();
        int typeCode = r.readBitShort();
        System.out.println("\n[BLOCK @offset=" + offset + " size=" + objSize + " type=0x" + Integer.toHexString(typeCode) + "]");
        long bitPos = bb.position();
        long bytePos = bitPos / 8;
        System.out.println("  起始位位置: " + bitPos + " (byte " + bytePos + " extra=" + bitPos%8 + ")");

        // entity common data: bitsize (RL) - 注意！实体格式需要从 obj_size 和 type_code 的
        // 实际结束位置开始
        try {
            // bitsize (RL) - 但可能是字节对齐后再读？
            // 我们的实际数据中：type_code 后 bit pos = offset*8 + 16 + 10
            //   例如 23203*8 + 26 = 185624+26 = 185650
            // 但是 bitsize 是 RL (4 bytes / 32 bits LE)
            // 让我尝试从下一字节边界开始读 RL
            long currentBit = bb.position();
            int extra = (int)(currentBit % 8);
            System.out.println("  type_code 结束: bit=" + currentBit + " (byte=" + currentBit/8 + " extra=" + extra + ")");

            // 尝试：entity header 从 byte boundary 开始
            long skip = (8 - extra) % 8;
            bb.seek(currentBit + skip);

            int bitsize = r.readBitLong(); // 4 bytes RL
            System.out.println("  bitsize(RL): " + bitsize);

            long entityHandle = r.readHandle();
            System.out.println("  entity_handle: 0x" + Long.toHexString(entityHandle));

            int eedSize = r.readBitShort();
            System.out.println("  EED_size(BS): " + eedSize);

            // 如果 EED_size > 0, 跳过 EED 数据
            if (eedSize > 0 && eedSize < 10000) {
                // EED 数据通常是字节对齐的
                long eedBytes = (long)eedSize * 8L; // 转换为 bits
                bb.seek(bb.position() + eedBytes);
                System.out.println("  [跳过 EED 数据: " + eedSize + " bytes]");
            }

            // preview_exists (1 bit)
            boolean preview = (r.getInput().readBits(1) & 1) == 1;
            System.out.println("  preview_exists: " + preview);

            // ent_mode (2 bits)
            int entMode = r.getInput().readBits(2) & 3;
            System.out.println("  ent_mode: " + entMode);

            int numReactors = r.readBitShort();
            System.out.println("  num_reactors: " + numReactors);

            for (int i = 0; i < numReactors && numReactors < 20; i++) {
                long rh = r.readHandle();
                System.out.println("    reactor[" + i + "]: 0x" + Long.toHexString(rh));
            }

            long xdict = r.readHandle();
            System.out.println("  xdict_handle: 0x" + Long.toHexString(xdict));

            long layer = r.readHandle();
            System.out.println("  layer_handle: 0x" + Long.toHexString(layer));

            long ltype = r.readHandle();
            System.out.println("  linetype_handle: 0x" + Long.toHexString(ltype));

            int color = r.readBitShort();
            System.out.println("  color(BS): " + color);

            // ===== BLOCK 特定字段 =====
            System.out.println("  --- BLOCK 特定字段 ---");
            long blockStartBit = bb.position();
            System.out.println("  block-specific start: bit=" + blockStartBit + " (byte " + blockStartBit/8 + ")");

            // block_name (TV): BS length + ASCII chars
            try {
                int nameLen = r.readBitShort();
                System.out.println("  block_name(TV) length: " + nameLen);
                if (nameLen > 0 && nameLen < 200) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < nameLen; i++) {
                        int b = r.getInput().readBits(8) & 0xFF;
                        if (b >= 32 && b < 127) sb.append((char)b);
                        else sb.append('?');
                    }
                    System.out.println("  block_name: '" + sb.toString() + "'");
                }
            } catch (Exception e) {
                System.out.println("  block_name read failed: " + e.getMessage());
            }

            // 尝试从 byte 12 (block_name 原始位置) 手动读字符串
            int byte12 = (int)offset + 12;
            StringBuilder rawName = new StringBuilder();
            for (int i = 0; i < 30; i++) {
                int b = data[byte12 + i] & 0xFF;
                if (b >= 32 && b < 127) rawName.append((char)b);
                else break;
            }
            System.out.println("  [byte12 raw ASCII]: '" + rawName + "'");

        } catch (Exception e) {
            System.out.println("  解析错误: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 分析 BLOCK_HEADER 对象 (type 0x30)
     * 结构: obj_size(MS) + type_code(BS) + object_common + block_header_specific
     * object_common: owner_handle(H) + num_reactors(BS) + H*num + xdict_handle(H)
     * block_header_specific: block_name(TU) + block_flags(BS) + base_point(3RD) + xref_path(TU)
     */
    private static void analyzeBlockHeaderObject(long offset) throws Exception {
        ByteBufferBitInput bb = new ByteBufferBitInput(data);
        bb.seek(offset * 8L);
        BitStreamReader r = new BitStreamReader(bb, DwgVersion.R2000);

        int objSize = r.readModularShort();
        int typeCode = r.readBitShort();
        System.out.println("[BLOCK_HEADER @offset=" + offset + " size=" + objSize + " type=0x" + Integer.toHexString(typeCode) + "]");

        // 对象公共数据
        long owner = r.readHandle();
        System.out.println("  owner_handle: 0x" + Long.toHexString(owner));

        int numReactors = r.readBitShort();
        System.out.println("  num_reactors: " + numReactors);

        for (int i = 0; i < numReactors && numReactors < 20; i++) {
            long rh = r.readHandle();
            System.out.println("    reactor[" + i + "]: 0x" + Long.toHexString(rh));
        }

        long xdict = r.readHandle();
        System.out.println("  xdict_handle: 0x" + Long.toHexString(xdict));

        // BLOCK_HEADER 特定
        System.out.println("  --- BLOCK_HEADER 特定字段 ---");

        // block_name (TU): BS length + UTF-16LE chars
        try {
            int nameLen = r.readBitShort();
            System.out.println("  block_name(TU) length: " + nameLen);
            if (nameLen > 0 && nameLen < 200) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < nameLen; i++) {
                    int lo = r.getInput().readBits(8) & 0xFF;
                    int hi = r.getInput().readBits(8) & 0xFF;
                    int cp = lo | (hi << 8);
                    if (cp >= 32 && cp < 127) sb.append((char)cp);
                    else sb.append('?');
                }
                System.out.println("  block_name: '" + sb.toString() + "'");
            }
        } catch (Exception e) {
            System.out.println("  block_name(TU) failed: " + e.getMessage());
        }

        // block_flags (BS)
        try {
            int flags = r.readBitShort();
            System.out.println("  block_flags(BS): " + flags);
        } catch (Exception e) {
            System.out.println("  block_flags failed: " + e.getMessage());
        }

        // base_point: 3 RD values (x, y, z)
        try {
            double bx = r.readBitDouble();
            double by = r.readBitDouble();
            double bz = r.readBitDouble();
            System.out.printf("  base_point: (%.4f, %.4f, %.4f)%n", bx, by, bz);
        } catch (Exception e) {
            System.out.println("  base_point failed: " + e.getMessage());
        }
    }

    /**
     * 分析 INSERT 实体 (type 0x07)
     * 结构: obj_size(MS) + type_code(BS) + entity_common + insert_specific
     * entity_common: 同上
     * insert_specific: block_header_handle(H) + insert_point(3BD) + scale(3BD)
     *                + rotation_angle(BD) + has_attribs(1bit) + path_type(BS)
     */
    private static void analyzeInsertEntity(long offset) throws Exception {
        ByteBufferBitInput bb = new ByteBufferBitInput(data);
        bb.seek(offset * 8L);
        BitStreamReader r = new BitStreamReader(bb, DwgVersion.R2000);

        int objSize = r.readModularShort();
        int typeCode = r.readBitShort();
        System.out.println("\n[INSERT @offset=" + offset + " size=" + objSize + " type=0x" + Integer.toHexString(typeCode) + "]");

        // entity common (bitsize, ent_handle, EED)
        long currentBit = bb.position();
        int extra = (int)(currentBit % 8);
        long skip = (8 - extra) % 8;
        bb.seek(currentBit + skip);

        int bitsize = r.readBitLong();
        System.out.println("  bitsize(RL): " + bitsize);

        long entityHandle = r.readHandle();
        System.out.println("  entity_handle: 0x" + Long.toHexString(entityHandle));

        int eedSize = r.readBitShort();
        System.out.println("  EED_size(BS): " + eedSize);
        if (eedSize > 0 && eedSize < 10000) {
            bb.seek(bb.position() + (long)eedSize * 8L);
        }

        boolean preview = (r.getInput().readBits(1) & 1) == 1;
        int entMode = r.getInput().readBits(2) & 3;
        int numReactors = r.readBitShort();
        System.out.println("  preview=" + preview + " ent_mode=" + entMode + " num_reactors=" + numReactors);

        for (int i = 0; i < numReactors && numReactors < 20; i++) {
            r.readHandle();
        }

        long xdict = r.readHandle();
        long layer = r.readHandle();
        long ltype = r.readHandle();
        int color = r.readBitShort();
        System.out.println("  layer=0x" + Long.toHexString(layer) + " ltype=0x" + Long.toHexString(ltype) + " color=" + color);

        // INSERT 特定
        System.out.println("  --- INSERT 特定字段 ---");
        long blockHeaderHandle = r.readHandle();
        System.out.println("  block_header_handle: 0x" + Long.toHexString(blockHeaderHandle));

        try {
            double x = r.readBitDouble();
            double y = r.readBitDouble();
            double z = r.readBitDouble();
            System.out.printf("  insert_point: (%.4f, %.4f, %.4f)%n", x, y, z);

            double sx = r.readBitDouble();
            double sy = r.readBitDouble();
            double sz = r.readBitDouble();
            System.out.printf("  scale: (%.4f, %.4f, %.4f)%n", sx, sy, sz);

            double rot = r.readBitDouble();
            System.out.printf("  rotation_angle: %.6f (rad) = %.3f (deg)%n", rot, rot * 180.0 / Math.PI);
        } catch (Exception e) {
            System.out.println("  BD 读取失败: " + e.getMessage());
        }
    }
}
