package run;

import io.dwg.core.io.*;
import io.dwg.core.version.DwgVersion;
import java.nio.file.Paths;

/**
 * 从方法 B 的正确起点继续深入分析 BLOCK 和 INSERT 的字段
 *
 * 关键发现 (方法 B - bit 26 开始):
 *   BLOCK @23203: entity_handle = 0x1b (BLOCK 自身 handle ✓)
 *   BLOCK_HEADER @21177: entity_handle = 0x1 (BLOCK_HEADER handle ✓)
 *   INSERT @50409: entity_handle = 0xd5 (INSERT handle ✓)
 *
 * 所以 entity_common 开头是正确的: bitsize(RL 4 bytes, non-byte-aligned)
 *                                             + entity_handle(H)
 *                                             + EED_size(BS)
 *                                             + preview_exists(1 bit)
 *                                             + ent_mode(2 bits)
 *                                             + num_reactors(BS)
 *                                             + xdict_handle(H)
 *                                             + layer_handle(H)
 *                                             + linetype_handle(H)
 *                                             + color(BS)
 *
 * 但后续 xdict_handle/layer_handle 等被读成了乱码，说明 pos 不对。
 * 问题在于: bitsize(RL) 是 32-bit value，但 *不是 byte-aligned read*，
 * 而是从当前 bit position 直接读 32 bits (实际上是 4 bytes via bit-level reads)
 * 
 * 让我追踪精确的 bit position 变化：
 *   offset 23203 (BLOCK_Paper):
 *   起始: bit 26 (byte 3, extra 2)
 *     RL bitsize: read 4 bytes from bit 26 (at bit level, not byte-aligned)
 *       byte3 bit 2-7 = 6 bits: 110001
 *       byte4 = 8 bits: 01000000
 *       byte5 = 8 bits: 00000000
 *       byte6 = 8 bits: 00000000
 *       byte7 bits 0-1 = 2 bits: 00
 *       32 bits: 110001 01000000 00000000 00000000 = 0x31400000? wait
 *       实际按 LE: low byte first
 *       byte 0 of RL: byte3 bits 2-7 (6 bits) + byte4 bits 0-1 (2 bits)? No...
 *
 *       实际上 BitStreamReader readBits32: 循环 4 次，每次 8 bits from current position
 *       bit 26: read 8 bits → byte3 bits 2-7 (6) + byte4 bits 0-1 (2)
 *         bits: 110001 01 = 0xC5 = 197 (lo)
 *       bit 34: read 8 bits → byte4 bits 2-7 (6) + byte5 bits 0-1 (2)
 *         bits: 000000 00 = 0x00 = 0
 *       bit 42: read 8 bits → byte5 bits 2-7 (6) + byte6 bits 0-1 (2)
 *         bits: 000000 00 = 0x00 = 0
 *       bit 50: read 8 bits → byte6 bits 2-7 (6) + byte7 bits 0-1 (2)
 *         bits: 000000 00 = 0x00 = 0
 *       result: 197 | 0<<8 | 0<<16 | 0<<24 = 197
 *
 *   所以 bitsize=197 是对的 (因为 bit-level read)
 *   接下来 readHandle from bit 58:
 *     byte7 = 0x00, bits 2-7 = 000000 (6 bits)
 *     read 4 bits code + 4 bits counter from bit 58:
 *       byte7 bit 2 = 0, bit 3 = 0, bit 4 = 0, bit 5 = 0 → code = 0
 *       byte7 bit 6 = 0, bit 7 = 0, byte8 bit 0 = 1, byte8 bit 1 = 0 → counter = 0010 = 2? wait no
 *
 *     让我用 BitStreamReader.readHandle() - 它读 4+4 bits
 *     readBits(4) for code:
 *       bit 58: byte7 bit 2 = 0
 *       bit 59: byte7 bit 3 = 0  
 *       bit 60: byte7 bit 4 = 0
 *       bit 61: byte7 bit 5 = 0
 *       code = 0000 = 0
 *     readBits(4) for counter:
 *       bit 62: byte7 bit 6 = 0
 *       bit 63: byte7 bit 7 = 0
 *       bit 64: byte8 bit 0 = 0
 *       bit 65: byte8 bit 1 = 1
 *       counter = 0010 = 2
 *     read counter bytes: 2 bytes
 *       byte8 bits 2-7 (6) + byte9 bits 0-1 (2): 100101 11 = 0x97
 *       byte9 bits 2-7 (6) + byte10 bits 0-1 (2): 100101 00 = 0x94
 *       value: 0x9794 = 38804
 *     final handle: (code << 8) << ((counter-1)*8) | value
 *                 = 0 << 8 | 0x9794 = 0x9794 = 38804
 *
 *   但是之前实际得到 entity_handle = 0x1b! 这说明我的位计算是错的，
 *   或者 BitStreamReader 读 bits 的方式不一样...
 *
 *   wait: 之前方法 B 实际运行时，读 bitsize 用的是 readBits32 版本 (我写的 4*8 bits)
 *   但我实际写的是: for (int i = 0; i < 4; i++) bitsize |= (r.getInput().readBits(8) & 0xFF) << (i*8)
 *   这和 BitStreamReader.readBitLong() 当 opcode=00 时的 readBits32 是一样的
 *   然后从当前位位置继续 readHandle()
 *
 *   让我直接追踪实际代码运行时的 bit position...
 */
public class AnalyzeR2000Precise {

    static byte[] data;

    public static void main(String[] args) throws Exception {
        String filename = "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg";
        data = java.nio.file.Files.readAllBytes(Paths.get(filename));

        // BLOCK_Paper_Space: 从 bit 26 开始, 追踪每一步
        System.out.println("========== BLOCK @ 23203 - 精确位追踪 ==========");
        traceEntity(23203, true);

        System.out.println("\n\n========== BLOCK_HEADER @ 21177 - 精确位追踪 ==========");
        traceObject(21177);

        System.out.println("\n\n========== INSERT @ 50409 - 精确位追踪 ==========");
        traceEntity(50409, false);
    }

    private static void traceEntity(int offset, boolean isBlock) {
        ByteBufferBitInput bb = new ByteBufferBitInput(data);
        bb.seek(offset * 8L + 26);  // MS(16) + BS_type(10) = bit 26
        BitStreamReader r = new BitStreamReader(bb, DwgVersion.R2000);

        System.out.println("Start bit_pos = " + bb.position() + " (byte " + (bb.position()/8) + ", extra " + (bb.position()%8) + ")");

        // bitsize (RL) - 32 bits LE, bit-level (4 times 8 bits)
        int bitsize = 0;
        for (int i = 0; i < 4; i++) {
            bitsize |= (r.getInput().readBits(8) & 0xFF) << (i * 8);
        }
        System.out.println("1. bitsize(RL) = " + bitsize + " [pos=" + bb.position() + "]");

        // entity_handle (H)
        long eh = r.readHandle();
        System.out.println("2. entity_handle(H) = 0x" + Long.toHexString(eh) + " [pos=" + bb.position() + "]");

        // EED_size (BS)
        int eedSize = r.readBitShort();
        System.out.println("3. EED_size(BS) = " + eedSize + " [pos=" + bb.position() + "]");

        if (eedSize > 0 && eedSize < 32768) {
            // skip EED - 应该是 byte-aligned 还是 bit-level?
            // libredwg: 跳过 eedSize 个 bytes (byte-aligned)
            long cur = bb.position();
            bb.seek(cur + eedSize * 8L);
            System.out.println("4. [跳过 EED " + eedSize + " bytes] [pos=" + bb.position() + "]");
        }

        // preview_exists (1 bit)
        boolean preview = (r.getInput().readBits(1) & 1) == 1;
        System.out.println("5. preview_exists = " + preview + " [pos=" + bb.position() + "]");

        // ent_mode (2 bits)
        int entMode = r.getInput().readBits(2) & 3;
        System.out.println("6. ent_mode = " + entMode + " [pos=" + bb.position() + "]");

        // num_reactors (BS)
        int numReactors = r.readBitShort();
        System.out.println("7. num_reactors(BS) = " + numReactors + " [pos=" + bb.position() + "]");

        if (numReactors > 0 && numReactors < 100) {
            for (int i = 0; i < numReactors; i++) {
                r.readHandle();
            }
            System.out.println("   [跳过 " + numReactors + " reactors]");
        }

        // xdict_handle (H)
        long xdict = r.readHandle();
        System.out.println("8. xdict_handle(H) = 0x" + Long.toHexString(xdict) + " [pos=" + bb.position() + "]");

        // layer_handle (H)
        long layer = r.readHandle();
        System.out.println("9. layer_handle(H) = 0x" + Long.toHexString(layer) + " [pos=" + bb.position() + "]");

        // linetype_handle (H)
        long ltype = r.readHandle();
        System.out.println("10. linetype_handle(H) = 0x" + Long.toHexString(ltype) + " [pos=" + bb.position() + "]");

        // color (BS)
        int color = r.readBitShort();
        System.out.println("11. color(BS) = " + color + " [pos=" + bb.position() + "]");

        long afterCommon = bb.position();
        System.out.println("   [entity_common 结束: pos=" + afterCommon + " = byte " + (afterCommon/8 - offset) + ", extra " + afterCommon%8 + "]");

        if (isBlock) {
            // BLOCK 特定字段
            // block_name (TV)
            bb.seek(afterCommon);
            try {
                int nameLen = r.readBitShort();
                System.out.println("\n12. block_name(TV) length = " + nameLen);
                if (nameLen > 0 && nameLen < 200) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < nameLen; i++) {
                        int b = r.getInput().readBits(8) & 0xFF;
                        if (b >= 32 && b < 127) sb.append((char)b);
                        else sb.append('?');
                    }
                    System.out.println("    block_name = \"" + sb + "\" [pos=" + bb.position() + "]");
                }
            } catch (Exception e) {
                System.out.println("    [TV fail] " + e.getMessage());
            }

            // 也尝试 TU
            bb.seek(afterCommon);
            try {
                int tuLen = r.readBitShort();
                System.out.println("12b. block_name(TU) length = " + tuLen);
                if (tuLen > 0 && tuLen < 200) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < tuLen; i++) {
                        int lo = r.getInput().readBits(8) & 0xFF;
                        int hi = r.getInput().readBits(8) & 0xFF;
                        int cp = lo | (hi << 8);
                        if (cp >= 32 && cp < 127) sb.append((char)cp);
                        else sb.append('?');
                    }
                    System.out.println("    block_name(TU) = \"" + sb + "\"");
                }
            } catch (Exception e) {
                System.out.println("    [TU fail] " + e.getMessage());
            }
        } else {
            // INSERT 特定字段
            // block_header_handle (H)
            long bhh = r.readHandle();
            System.out.println("\n12. block_header_handle(H) = 0x" + Long.toHexString(bhh) + " [pos=" + bb.position() + "]");

            // 3BD insert_point
            try {
                double x = r.readBitDouble();
                double y = r.readBitDouble();
                double z = r.readBitDouble();
                System.out.printf("13. insert_point(3BD) = (%.4f, %.4f, %.4f) [pos=%d]%n", x, y, z, bb.position());

                double sx = r.readBitDouble();
                double sy = r.readBitDouble();
                double sz = r.readBitDouble();
                System.out.printf("14. scale(3BD) = (%.4f, %.4f, %.4f) [pos=%d]%n", sx, sy, sz, bb.position());

                double rot = r.readBitDouble();
                System.out.printf("15. rotation(BD) = %.6f rad [pos=%d]%n", rot, bb.position());
            } catch (Exception e) {
                System.out.println("   [BD fail] " + e.getMessage());
            }
        }
    }

    private static void traceObject(int offset) {
        // BLOCK_HEADER 是 object 不是 entity, 用 object_common
        ByteBufferBitInput bb = new ByteBufferBitInput(data);
        bb.seek(offset * 8L + 26);
        BitStreamReader r = new BitStreamReader(bb, DwgVersion.R2000);

        System.out.println("Start bit_pos = " + bb.position());

        // object_common
        long owner = r.readHandle();
        System.out.println("1. owner_handle = 0x" + Long.toHexString(owner));

        int numReactors = r.readBitShort();
        System.out.println("2. num_reactors = " + numReactors);

        if (numReactors > 0 && numReactors < 100) {
            for (int i = 0; i < numReactors; i++) r.readHandle();
        }

        long xdict = r.readHandle();
        System.out.println("3. xdict_handle = 0x" + Long.toHexString(xdict) + " [pos=" + bb.position() + "]");

        long afterCommon = bb.position();
        System.out.println("   object_common 结束: byte " + (afterCommon/8 - offset) + " extra " + afterCommon%8);

        // block_header 特定字段
        // block_name (TU or TV)
        try {
            int nameLen = r.readBitShort();
            System.out.println("4. block_name length = " + nameLen);
            if (nameLen > 0 && nameLen < 200) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < nameLen; i++) {
                    int b = r.getInput().readBits(8) & 0xFF;
                    if (b >= 32 && b < 127) sb.append((char)b);
                    else sb.append('?');
                }
                System.out.println("   block_name(ASCII) = \"" + sb + "\"");
            }
        } catch (Exception e) {
            System.out.println("   [name fail] " + e.getMessage());
        }

        // block_flags (BS)
        try {
            int flags = r.readBitShort();
            System.out.println("5. block_flags(BS) = " + flags);
        } catch (Exception e) {
            System.out.println("   [flags fail] " + e.getMessage());
        }
    }
}
