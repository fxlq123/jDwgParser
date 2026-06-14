package run;

import java.nio.file.Paths;
import java.util.*;

/**
 * 尝试多种位对齐方式和字段编码组合，找出 R2000 对象的正确结构
 *
 * 已知事实：
 * 1. obj_size(MS) + type_code(BS) 工作正常
 *    - MS: 占用 16 bits (2 bytes), value = LE16 & 0x7FFF
 *    - BS: 从 bit 16 开始, opcode=01 (2 bits) + value (8 bits) = 10 bits total
 *    - 下一位置: bit 26 = byte 3, bit 2
 *
 * 2. BLOCK (0x31) 的 block_name ASCII 从 byte 12 (第 12 字节) 开始
 *    "Paper_Space" 在 offset+12 处找到
 *    byte 4-11 = 40 00 00 00 46 E9 0C 2A (对 Paper_Space)
 *    byte 4-11 = 40 00 00 00 47 E9 0C 2A (对 Model_Space)
 *
 * 3. INSERT (0x07) 需要找到 block_header_handle
 *
 * 4. 从 bit 26 到 byte 12 (bit 96): 中间有 70 bits 的数据
 *    这些 70 bits 是什么？
 *
 * 可能的 entity_common 字段 (非实体? 或许 BLOCK_HEADER/BLOCK 是 "object" 类型不是 entity):
 *   - BLOCK_HEADER 是 object (不是 entity): owner_handle(H) + reactors + xdict(H)
 *   - BLOCK (0x31) 也是 object: 同样的 common
 *
 * H (handle) 格式:
 *   1st byte: 0xRC (R = reference type, C = counter/bytes-1)
 *     如果 low nibble == 0xF, 读 extended counter
 *   接下来 counter+1 字节是 value
 *
 * byte 4 = 0x40 (对 BLOCK offset=23203): R=4, C=0 -> 1 byte value (byte5=0x00)
 *   -> owner_handle = 0x00 ?
 *   不对，byte 4-7 = 40 00 00 00
 *   如果 byte4 是 H(handle): R=4, C=0, value bytes = 1 (byte5) = 0x00 -> owner=0
 *   但那样 byte6-7 是下一个字段
 *
 * byte 6-7 = 00 00 (LE16 BS? opcode=00 -> read 16 bits = 0x0000 = num_reactors=0)
 *   但 BS 不是字节对齐的，是 bit-level
 *
 * 让我尝试多种位偏移量来读 owner_handle(H):
 *   从 bit 26 开始: 当前 byte = byte 3 (0x71), bit offset 2
 *   byte2-3 = 4C 71: bit 0-15 (type_code)
 *   bit 16-25 = type_code, bit 26 开始 = byte 4? No.
 *   重新：offset byte 0 开始：
 *     byte 0-1: MS (2 bytes)
 *     byte 2-3 bit 0-1: BS (10 bits total)
 *     所以 bit 26 就是 byte 3, bit 2 (byte 3 = 0x71 = 0111_0001)
 *
 *   bit 26: byte 3 = 0x71 = 0111_0001, 我们在 bit 2 (0-indexed from byte start)
 *     bit 2-7 = 110001 of byte 3 = 0x31 的 bits 2-7 = 110001 = 0x31
 *     等等：bit 26 实际上是 byte 3 bit 2 = 1
 *
 *   让我不纠结细节，直接从 type_code 结束后尝试多种读方式。
 */
public class AnalyzeR2000Exploration {

    static byte[] data;

    public static void main(String[] args) throws Exception {
        String filename = "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg";
        data = java.nio.file.Files.readAllBytes(Paths.get(filename));

        // 对于每个要分析的对象，打印关键信息
        int[][] offsets = {
            {21177, 0x30},
            {23203, 0x31},
            {23159, 0x31},
            {21677, 0x31},
            {50409, 0x07},
            {52467, 0x07},
        };
        String[] names = {"BLOCK_HEADER", "BLOCK_Paper", "BLOCK_Model", "BLOCK_W_NOTE_0", "INSERT_1", "INSERT_2"};

        for (int i = 0; i < offsets.length; i++) {
            exploreObject(offsets[i][0], offsets[i][1], names[i]);
        }

        // 找到所有 BLOCK_HEADER 和 BLOCK 对象，列出它们的名字
        System.out.println("\n========== 扫描所有 0x30/0x31 对象的原始名字 ==========");
        findAllBlockNames();
    }

    private static void exploreObject(int offset, int expectedType, String name) {
        System.out.println("\n========== " + name + " @offset=" + offset + " ==========");

        // 1. hex dump
        int objSize = readLE16(offset) & 0x7FFF;
        System.out.print("HEX: ");
        for (int i = 0; i < Math.min(objSize, 64); i++) {
            if (i % 16 == 0) System.out.print(String.format("%n  %2d: ", i));
            System.out.printf("%02X ", data[offset + i] & 0xFF);
        }
        System.out.println();

        // 2. 尝试从多个字节偏移量读字符串
        System.out.println("\n字符串扫描 (ASCII):");
        StringBuilder current = new StringBuilder();
        int startIdx = -1;
        for (int i = 4; i < Math.min(objSize, 60); i++) {
            int b = data[offset + i] & 0xFF;
            if (b >= 32 && b < 127) {
                if (startIdx < 0) startIdx = i;
                current.append((char)b);
            } else {
                if (current.length() >= 3) {
                    System.out.println("  byte " + startIdx + "-" + (startIdx + current.length() - 1) + ": '" + current + "'");
                }
                current.setLength(0);
                startIdx = -1;
            }
        }
        if (current.length() >= 3) {
            System.out.println("  byte " + startIdx + "-" + (startIdx + current.length() - 1) + ": '" + current + "'");
        }

        // 3. 字节级字段尝试
        System.out.println("\n字节级字段尝试:");
        // bytes 4-7 作为 RL (LE32)
        int rl4_7 = readLE32(offset + 4);
        System.out.println("  bytes 4-7 as RL (LE32): " + rl4_7);

        // 从 byte 4 开始尝试: RL + H + TV
        // 对 BLOCK @23203: bytes 4-11 = 40 00 00 00 46 E9 0C 2A
        //   RL=0x40=64, then H at byte 8: byte8=0x46, R=4, C=6 -> 7 bytes value? Too many.
        //   或者 byte8=0x46, R=4, C=6, counter 6 -> value 由接下来 6 字节组成
        //   但 0x46 可能是其他东西
        //   bytes 4-11 as RL + RL: RL(byte4-7)=64, RL(byte8-11)=0x2A0CE946

        // 另一种思路: byte12 是 block_name ASCII，前 8 个 bytes (4-11) 可能是
        // 一些简单的字段：owner_handle(4), xdict_handle(4)
        // 对 BLOCK_HEADER: 0x40=0100_0000... 似乎不是 handle 编码
    }

    /**
     * 扫描对象表 (从 handles section 得到 offsets)，找到所有 BLOCK_HEADER 和 BLOCK 对象
     * 然后打印它们的原始字节数据以提取名字
     */
    private static void findAllBlockNames() throws Exception {
        // 先解析 handles section
        // 我们从之前的测试知道一些 offsets:
        // 实际扫描: 遍历所有对象位置，找 type=0x30 或 0x31

        // 让我用一个更简单的方式：遍历可能的对象位置
        // 实际上我们需要先找到 Objects section 区域

        // 看一下已知的 offsets: 21177, 21636, 21677, 21719, 23159, 23203
        // 这些都是 21000-23500 范围内的，可能 objects 区域比较大

        // 尝试从 21000 开始，以 MS 的第一个字节 (obj_size) 来扫描
        // obj_size 占 2 bytes, type 占 2 bytes, 然后是数据
        // 所以每个对象占 obj_size + 2 (起始处 obj_size 本身)? No:
        //   obj_size 字段本身就在 obj bytes 里，大小包含 obj_size 字段
        //   总大小 = obj_size bytes (从第 0 字节算)

        System.out.println("扫描对象...");
        int totalBlocks = 0;
        int totalBlockHeaders = 0;
        int totalInserts = 0;

        // 扫描 21000 到 55000 的范围（已知有些 INSERT 在 50409, 52467）
        int pos = 21000;
        while (pos < 55000 && pos < data.length - 4) {
            // 检查 bytes[pos] bytes[pos+1] 作为 MS: high bit of LE16 word 应该是 0
            int word0 = (data[pos] & 0xFF) | ((data[pos + 1] & 0xFF) << 8);
            int size = word0 & 0x7FFF;
            if (size < 4 || size > 50000) {
                pos++;
                continue;
            }

            // 尝试读取 type_code: 从 byte 2 开始 BS
            // BS: opcode=01, 然后 8 bits value
            // byte 2 bit 0 = ?
            int byte2 = data[pos + 2] & 0xFF;
            int byte3 = data[pos + 3] & 0xFF;
            // bit stream = byte2_bits followed by byte3_bits
            // opcode = bit0,bit1 of byte2 = (byte2 >> 6) & 3
            int opcode = (byte2 >> 6) & 3;
            int type;
            if (opcode == 1) {
                // 8-bit mode: value from bit 2..9 (跨越 byte2, byte3)
                // byte2 bits 2-7 + byte3 bits 0-1 = 8 bits total?
                // Let's actually: opcode occupies bits 0-1, then 8 bits follow
                // bits 2-7 of byte2: (byte2 & 0x3F) = 6 bits
                // then bits 0-1 of byte3 = 2 bits -> total 8 bits
                int val = ((byte2 & 0x3F) << 2) | (byte3 >> 6);
                type = val;
            } else if (opcode == 0) {
                // 16-bit mode, LE16 after 2-bit opcode
                // bits 2-7 of byte2 = 6 bits
                // byte3 = 8 bits
                // byte4 bits 0-1 = 2 bits -> total 16 bits? Too complex
                // skip
                pos++;
                continue;
            } else {
                pos++;
                continue;
            }

            if (type == 0x30 || type == 0x31 || type == 0x07) {
                // 找到有效对象
                if (type == 0x30) totalBlockHeaders++;
                if (type == 0x31) totalBlocks++;
                if (type == 0x07) totalInserts++;

                // 提取 block_name: 从 byte 4 开始找 ASCII 字符串
                StringBuilder nameSb = new StringBuilder();
                int nameStart = -1;
                for (int i = 4; i < size && i < 60; i++) {
                    int b = data[pos + i] & 0xFF;
                    if (b >= 32 && b < 127) {
                        if (nameStart < 0) nameStart = i;
                        nameSb.append((char)b);
                    } else {
                        if (nameSb.length() >= 3) break;
                        nameSb.setLength(0);
                        nameStart = -1;
                    }
                }
                String name = nameSb.length() >= 2 ? nameSb.toString() : "";
                String typeName = type == 0x30 ? "BLOCK_HEADER" : (type == 0x31 ? "BLOCK" : "INSERT");
                System.out.println(String.format("  pos=%d, type=0x%02X (%s), size=%d, name_in_bytes='%s' (at byte %d)",
                    pos, type, typeName, size, name, nameStart));

                // 下一个对象 = pos + size
                pos += size;
                continue;
            }

            pos++;
        }

        System.out.println("\n总数: BLOCK_HEADER=" + totalBlockHeaders + ", BLOCK=" + totalBlocks + ", INSERT=" + totalInserts);
    }

    private static int readLE16(int offset) {
        if (offset + 1 >= data.length) return 0;
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private static int readLE32(int offset) {
        if (offset + 3 >= data.length) return 0;
        long b0 = data[offset] & 0xFF;
        long b1 = data[offset + 1] & 0xFF;
        long b2 = data[offset + 2] & 0xFF;
        long b3 = data[offset + 3] & 0xFF;
        return (int)(b0 | (b1 << 8) | (b2 << 16) | (b3 << 24));
    }
}
