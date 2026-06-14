package run;

import io.dwg.core.io.*;
import io.dwg.core.version.DwgVersion;
import java.nio.file.Paths;

/**
 * 正确的起点: object data 从 type_code 后的位置开始
 * 但 R2000 对象流中: obj_size (MS) + type_code (BS) + data fields
 *   - MS: 16-bit word, high bit 为继续位 (对 size<32768，只需1 word=2 bytes)
 *   - BS: opcode 2 bits + conditional data
 * obj_size: 从 byte 0 开始, 2 bytes (1 word)
 * type_code: 从 bit 16 开始, BS format (opcode=01, 8 bits of data) = 10 bits total
 *   所以 type_code 从 bit 16 到 bit 25 (含), data 从 bit 26 开始
 *   bit 26 = byte 3, bit offset 2
 *
 * 但实际上，libredwg 的实现是: read object (size + type), 然后重置或直接用 bit reader
 * 从 object data area 读取字段。关键点: readHandle, readBitShort 等都从 *当前 bit position* 读, 
 * 不是 byte-aligned.
 *
 * 但如果 entity header 用 RL (32 bits), 那它从 *下一个 byte boundary* 开始或从
 * 当前 bit position 开始？RL 应该是 byte-aligned 还是 bit-aligned?
 *
 * 检查实际数据:
 *   BLOCK @23203:
 *     bytes 2-3: 4C 71 = 0100_1100_0111_0001
 *       bit 16 (从对象开始) = bit 0 of byte 2 = 0
 *       bit 17 = bit 1 of byte 2 = 1
 *       opcode = 01 → 8-bit mode
 *       bits 18-25 (8 bits): 00_1100_0111 = bits 2-7 of byte2 + bits 0-1 of byte3
 *         byte2 = 4C = 0100_1100, bits 2-7 = 00_1100 = 12
 *         byte3 = 71 = 0111_0001, bits 0-1 = 01
 *         所以 8 bits = 0011_0001 = 0x31 ✓ (BLOCK type)
 *       type_code 结束: bit 25 (16 + 2 + 8 = 26? wait: 16 start, 2 opcode, 8 data = 26 bits total)
 *       所以 type_code 结束于 bit 25, 下一字段从 bit 26 开始
 *
 *     bit 26: byte 3, bit offset 2. byte3=71=0111_0001. bit 2 = 1, bit 3 = 1, ...
 *
 *   bitsize RL 应该从 byte boundary 开始 (从 byte 4 或从当前 bit position)?
 *   如果 RL=32 bit LE integer (字节对齐), 从 byte 4 读: 40 00 00 00 = 64
 *   如果 RL=32 bit LE integer (非字节对齐), 从 bit 26 读: 取 32 bits from bit 26
 *     byte 3 bits 2-7 = 6 bits: 11_0001 = 0x31
 *     byte 4 = 0x40 = 0100_0000 (8 bits)
 *     byte 5 = 0x00 = 0000_0000 (8 bits)
 *     byte 6 = 0x00 = 0000_0000 (8 bits)
 *     byte 7 = 0x00 = 0000_0000 (8 bits - but need only 2 more to make 32)
 *     2 bits from byte 7 = 00
 *     总共 6+8+8+8+2 = 32 bits = 0x00000431 (等等)
 *     让我重新算: 从 bit 26 (byte 3, bit 2) 取 32 bits
 *       byte3 bits 2-7: 110001 (bits 26-31)
 *       byte4: 01000000 (bits 32-39)
 *       byte5: 00000000 (bits 40-47)
 *       byte6: 00000000 (bits 48-55)
 *       byte7 bits 0-1: 00 (bits 56-57) - 不够 32!
 *     wait: 从 byte 3, bit 2 开始, 连续 32 bits:
 *       byte3 bits 2,3,4,5,6,7 = 6 bits: 110001
 *       byte4 = 8 bits: 01000000
 *       byte5 = 8 bits: 00000000
 *       byte6 = 8 bits: 00000000
 *       累计: 6+8+8+8 = 30 bits, 还需要 2 bits
 *       byte7 bits 0-1 = 2 bits: 00
 *       32 bits: 110001_01000000_00000000_00000000_00
 *       作为 LE integer: low byte first
 *         byte 0 (low): bits 0-7 = from bit 26 to bit 33? 太复杂
 *
 *   让我直接用 BitStreamReader 实际读看看！
 */
public class AnalyzeR2000Real {

    static byte[] data;

    public static void main(String[] args) throws Exception {
        String filename = "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg";
        data = java.nio.file.Files.readAllBytes(Paths.get(filename));

        System.out.println("========== 方法 A: 从 byte 4 (bit 32) 开始, 字节对齐 ==========\n");
        analyzeWithByteAlign(23203, "BLOCK_Paper_Space");
        analyzeWithByteAlign(21177, "BLOCK_HEADER");
        analyzeWithByteAlign(50409, "INSERT_1");
        analyzeWithByteAlign(52467, "INSERT_2");
        analyzeWithByteAlign(21677, "BLOCK_W_NOTE_0");

        System.out.println("\n\n========== 方法 B: 从 bit 26 开始, 位对齐 ==========\n");
        analyzeWithBitAlign(23203, "BLOCK_Paper_Space");
        analyzeWithBitAlign(21177, "BLOCK_HEADER");
        analyzeWithBitAlign(50409, "INSERT_1");
        analyzeWithBitAlign(21677, "BLOCK_W_NOTE_0");

        System.out.println("\n\n========== 方法 C: 原始位流检查关键位置 ==========\n");
        showBitsAroundKeyPositions(23203);
    }

    private static void analyzeWithByteAlign(int offset, String name) {
        System.out.println("\n[" + name + " @ " + offset + "]");

        ByteBufferBitInput bb = new ByteBufferBitInput(data);
        bb.seek(offset * 8L + 32);  // 从 byte 4 开始
        BitStreamReader r = new BitStreamReader(bb, DwgVersion.R2000);

        // obj_size, type_code 信息
        int objSize = (data[offset] & 0xFF) | ((data[offset+1] & 0xFF) << 8);
        int type = (data[offset+2] & 0x3F) << 2 | (data[offset+3] >> 6);
        System.out.println("  obj_size=" + (objSize & 0x7FFF) + ", type=0x" + Integer.toHexString(type));

        // 尝试 BLOCK / BLOCK_HEADER 字段
        // object_common: owner_handle(H) + num_reactors(BS) + reactors*H + xdict_handle(H)
        try {
            long owner = r.readHandle();
            int numReactors = r.readBitShort();
            long xdict = r.readHandle();
            System.out.println("  object_common: owner=0x" + Long.toHexString(owner)
                + ", num_reactors=" + numReactors + ", xdict=0x" + Long.toHexString(xdict));

            // block_name (TV - BS length + ASCII)
            long pos1 = bb.position();
            System.out.println("  pos after common: byte=" + (pos1/8 - offset) + " extra=" + (pos1%8));

            // 尝试 TU (Unicode)
            bb.seek(pos1);
            try {
                int tuLen = r.readBitShort();
                System.out.print("  [尝试 TU] length=" + tuLen);
                if (tuLen > 0 && tuLen < 100) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < tuLen; i++) {
                        int lo = r.getInput().readBits(8) & 0xFF;
                        int hi = r.getInput().readBits(8) & 0xFF;
                        int cp = lo | (hi << 8);
                        sb.append((cp >= 32 && cp < 127) ? (char)cp : '?');
                    }
                    System.out.println(", text=\"" + sb + "\"");
                } else System.out.println();
            } catch (Exception e) {
                System.out.println("  [TU failed] " + e.getMessage());
            }

            // 尝试 TV (ASCII)
            bb.seek(pos1);
            try {
                int tvLen = r.readBitShort();
                System.out.print("  [尝试 TV] length=" + tvLen);
                if (tvLen > 0 && tvLen < 100) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < tvLen; i++) {
                        int b = r.getInput().readBits(8) & 0xFF;
                        sb.append((b >= 32 && b < 127) ? (char)b : '?');
                    }
                    System.out.println(", text=\"" + sb + "\"");
                } else System.out.println();
            } catch (Exception e) {
                System.out.println("  [TV failed] " + e.getMessage());
            }

            // 对于 INSERT, 尝试 block_header_handle(H)
            if (type == 0x07) {
                bb.seek(pos1);
                long bh = r.readHandle();
                System.out.println("  [INSERT] block_header_handle=0x" + Long.toHexString(bh));

                // 之后是 3BD (insertion point)
                try {
                    double x = r.readBitDouble();
                    double y = r.readBitDouble();
                    double z = r.readBitDouble();
                    System.out.printf("  [INSERT] insert_point: (%.4f, %.4f, %.4f)%n", x, y, z);

                    double sx = r.readBitDouble();
                    double sy = r.readBitDouble();
                    double sz = r.readBitDouble();
                    System.out.printf("  [INSERT] scale: (%.4f, %.4f, %.4f)%n", sx, sy, sz);

                    double rot = r.readBitDouble();
                    System.out.printf("  [INSERT] rotation: %.6f rad%n", rot);
                } catch (Exception e) {
                    System.out.println("  [INSERT BD failed] " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.out.println("  Error in main parse: " + e.getMessage());
        }
    }

    private static void analyzeWithBitAlign(int offset, String name) {
        System.out.println("\n[" + name + " @ " + offset + "]");

        ByteBufferBitInput bb = new ByteBufferBitInput(data);
        bb.seek(offset * 8L + 26);  // 从 type_code 后开始 (bit 26)
        BitStreamReader r = new BitStreamReader(bb, DwgVersion.R2000);

        // 尝试 object_common 或 entity_common
        try {
            // bitsize RL (4 bytes)
            int bitsize = 0;
            for (int i = 0; i < 4; i++) {
                bitsize |= (r.getInput().readBits(8) & 0xFF) << (i * 8);
            }
            System.out.println("  bitsize(RL from bit26) = " + bitsize);

            // entity_handle
            long eh = r.readHandle();
            System.out.println("  entity_handle = 0x" + Long.toHexString(eh));

            // EED_size (BS)
            int eedSize = r.readBitShort();
            System.out.println("  EED_size(BS) = " + eedSize);

            if (eedSize > 0 && eedSize < 32768) {
                // skip EED data (byte-aligned)
                long cur = bb.position();
                bb.seek(cur + eedSize * 8L);
            }

            // preview_exists (1 bit) + ent_mode (2 bits)
            boolean preview = (r.getInput().readBits(1) & 1) == 1;
            int entMode = r.getInput().readBits(2) & 3;
            System.out.println("  preview=" + preview + ", ent_mode=" + entMode);

            // rest of entity_common
            int numReactors = r.readBitShort();
            System.out.println("  num_reactors(BS) = " + numReactors);
            if (numReactors > 0 && numReactors < 100) {
                for (int i = 0; i < numReactors; i++) r.readHandle();
            }

            long xdict = r.readHandle();
            long layer = r.readHandle();
            long ltype = r.readHandle();
            int color = r.readBitShort();
            System.out.println("  xdict=0x" + Long.toHexString(xdict)
                + ", layer=0x" + Long.toHexString(layer)
                + ", ltype=0x" + Long.toHexString(ltype)
                + ", color=" + color);

            long pos = bb.position();
            System.out.println("  current pos: byte=" + (pos/8 - offset));

            // block_name (TV)
            int nameLen = r.readBitShort();
            System.out.print("  block_name length=" + nameLen);
            if (nameLen > 0 && nameLen < 100) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < nameLen; i++) {
                    int b = r.getInput().readBits(8) & 0xFF;
                    sb.append((b >= 32 && b < 127) ? (char)b : '?');
                }
                System.out.println(", text=\"" + sb + "\"");
            } else System.out.println();
        } catch (Exception e) {
            System.out.println("  Error: " + e.getMessage());
        }
    }

    private static void showBitsAroundKeyPositions(int offset) {
        System.out.println("[" + offset + "] bytes 0-20:");
        for (int i = 0; i < 20; i++) {
            System.out.print(String.format("%02X ", data[offset + i] & 0xFF));
        }
        System.out.println();

        // bit 26 (byte 3, bit 2): next byte values
        // byte 3: bits 2-7 = 6 bits
        int b3 = data[offset + 3] & 0xFF;
        int bitsFromByte3 = b3 & 0x3F;  // bit 2-7 = bits 2-7 = low 6 bits
        System.out.println("  byte 3 = 0x" + Integer.toHexString(b3)
            + " = " + String.format("%8s", Integer.toBinaryString(b3)).replace(' ', '0'));
        System.out.println("  bits 2-7 of byte 3 = " + String.format("%6s", Integer.toBinaryString(bitsFromByte3)).replace(' ', '0')
            + " = 0x" + Integer.toHexString(bitsFromByte3));

        // bytes 4-7
        int b4 = data[offset + 4] & 0xFF;
        int b5 = data[offset + 5] & 0xFF;
        int b6 = data[offset + 6] & 0xFF;
        int b7 = data[offset + 7] & 0xFF;
        System.out.println("  bytes 4-7: " + String.format("%02X %02X %02X %02X", b4, b5, b6, b7)
            + " = LE32 = " + (b4 | (b5<<8) | (b6<<16) | (b7<<24)));

        // byte 8 - H handle
        int b8 = data[offset + 8] & 0xFF;
        System.out.println("  byte 8 = 0x" + Integer.toHexString(b8) + " (H code=" + (b8>>4) + ", counter=" + (b8 & 0x0F) + ")");
    }
}
