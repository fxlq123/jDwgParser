package run;

import io.dwg.core.io.*;
import io.dwg.core.version.DwgVersion;
import io.dwg.format.common.*;
import io.dwg.sections.handles.*;
import java.nio.file.Paths;
import java.util.*;

/**
 * 重新理解 R2000 对象编码：
 * 发现 BLOCK 和 INSERT 不是 entity header 格式，而是 object header 格式
 *
 * 观察到的 BLOCK (0x31) 字节:
 *   offset 0-1: 0x25 0x00 = modular_short? No, looks like RL (4 bytes)=0x0025=37 = obj_size
 *     实际上: 0-1 是 obj_size, 2-3 是 type_code
 *   2-3: 0x4C 0x71 = 0x714C (LE) = 29004 (不是 0x31)
 *     问题：readBitShort 用 bit-level 读: 先 opcode2 + 16bit, 但如果读位级
 *     实际 bytes 4C 71 = 0100_1100_0111_0001 : opcode=0b01 = read low8 = 0x4C = 76?
 *     不对，readBitShort 是 opcode=00 读 16bit, opcode=01 读 8bit, opcode=10=0, opcode=11=256
 *     如果从 bit 32 开始: bytes 2-3 = 0x4C 0x71, bit-level: 0100_1100_0111_0001
 *       opcode = 01 (2 bits), 然后读 8 bits = 1100_0111 = 0xC7 = 199?
 *       不对...
 *     实际上: objects section 是 byte-aligned 读取方式！
 *     让我们测试: 用 BitStreamReader 从 byte 4 (bit 32) 开始读
 *
 * 发现 BLOCK_HEADER (0x30) 的 offset 21177 的 bytes:
 *   72 00 4C 12 00 00 00 00 40 69 22 40 30 21 23 21 ...
 *             ^^^^^^^^^^^^^ = bitsize?
 *   bytes 4-7 = 00 00 00 00 = RL 0
 *   bytes 8-11 = 40 69 22 40 = ???
 *
 * 让我看看 libredwg R2000 object 格式:
 *   common object data (不是 entity header!)
 *     owner_handle (H)
 *     reactors: num_reactors (BS) + H * num
 *     xdict_handle (H)
 *   对于 BLOCK_HEADER (type 0x30)：
 *     block_name (TU)
 *     flags (BS)
 *     base_point (3RD)
 *     xref_path (TU)
 *   对于 BLOCK (type 0x31)：
 *     这可能是 block_record (不是真正的 block 定义)
 *     字段: block_name (TV) + block_end_handle (H) + 可选更多...
 */
public class AnalyzeR2000Correct {

    static byte[] data;

    public static void main(String[] args) throws Exception {
        String filename = "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg";
        data = java.nio.file.Files.readAllBytes(Paths.get(filename));

        DwgFileStructureHandler handler = DwgFileStructureHandlerFactory.forVersion(DwgVersion.R2000);
        ByteBufferBitInput input = new ByteBufferBitInput(data);
        FileHeaderFields header = handler.readHeader(input);
        input = new ByteBufferBitInput(data);
        Map<String, SectionInputStream> sections = handler.readSections(input, header);
        SectionInputStream handlesSec = sections.get("AcDb:Handles");
        HandlesSectionParser hsp = new HandlesSectionParser();
        HandleRegistry registry = hsp.parse(handlesSec, DwgVersion.R2000);

        // 根据原始观察:
        // BLOCK_HEADER handle=0x1 @offset=21177:
        //   72 00 4C 12 00 00 00 00 40 69 22 40 30 21 23 21 CE 21 FB 22 ...
        //   obj_size=0x72=114
        //   readBitShort: 先读 2 bits opcode (0x4C 第1字节 high 2 bits = 01)
        //     opcode=01, read 8 bits = 0x4C = 76? 不对
        //   实际之前分析得到: type=0x30 (48)
        //   让我重写: bytes 2-3 = 4C 12 (LE) = 0x124C
        //     bit-level: 从 byte 2 开始 (bit 16): 0100_1100_0001_0010
        //     opcode(2) = 01, then read 8 bits = 00_1100_0001?
        //       不对，让我用 BitStreamReader 测试
        System.out.println("=== 重新验证 R2000 type_code 读取 ===\n");

        // 用 BitStreamReader 从 byte 0 开始读 type_code
        for (long offset : new long[]{21177, 23203, 23159, 21636, 50409, 52467}) {
            ByteBufferBitInput bb = new ByteBufferBitInput(data);
            bb.seek(offset * 8L);
            BitStreamReader r = new BitStreamReader(bb, DwgVersion.R2000);

            int objSize = r.readModularShort();
            System.out.print("obj_size: " + objSize + " (bit pos after MS=" + bb.position() + ") ");

            // 从这里开始手动测试 type_code 的正确读方式
            long savePos = bb.position();

            // 方案 1: readBitShort (bit-level)
            int typeBS = r.readBitShort();
            System.out.print(" type(BS): 0x" + Integer.toHexString(typeBS));

            bb.seek(savePos);

            // 方案 2: readBitLong (bit-level, 2 bit opcode + 8 or 32)
            int typeBL = r.readBitLong();
            System.out.print(" type(BL): 0x" + Integer.toHexString(typeBL));

            bb.seek(savePos);

            // 方案 3: LE16 (字节级, 直接读 16bit LE)
            int pos = (int)(savePos / 8);
            int typeLE = (data[pos] & 0xFF) | ((data[pos + 1] & 0xFF) << 8);
            System.out.print(" type(LE16): 0x" + Integer.toHexString(typeLE));

            System.out.println();
        }

        System.out.println("\n=== BLOCK_HEADER (handle=0x1, offset=21177) 字段分析 ===\n");
        {
            long offset = 21177;
            ByteBufferBitInput bb = new ByteBufferBitInput(data);
            bb.seek(offset * 8L);
            BitStreamReader r = new BitStreamReader(bb, DwgVersion.R2000);

            int objSize = r.readModularShort();
            int type = r.readBitShort();
            System.out.println("obj_size: " + objSize + ", type: 0x" + Integer.toHexString(type));
            System.out.println("after type, bit pos: " + bb.position());

            // 尝试 object common data 方式
            long owner = r.readHandle();
            System.out.println("owner_handle: 0x" + Long.toHexString(owner));

            int numReactors = r.readBitShort();
            System.out.println("num_reactors(BS): " + numReactors);

            for (int i = 0; i < numReactors && numReactors < 20; i++) {
                long rh = r.readHandle();
                System.out.println("  reactor[" + i + "]: 0x" + Long.toHexString(rh));
            }

            long xdict = r.readHandle();
            System.out.println("xdict_handle: 0x" + Long.toHexString(xdict));

            System.out.println("after common, bit pos: " + bb.position());

            // 尝试 block_name (TU: BS length + UTF-16LE chars)
            try {
                int nameLen = r.readBitShort();
                System.out.println("nameLen(BS): " + nameLen);

                if (nameLen > 0 && nameLen < 200) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < nameLen; i++) {
                        int lo = r.getInput().readBits(8) & 0xFF;
                        int hi = r.getInput().readBits(8) & 0xFF;
                        int cp = lo | (hi << 8);
                        sb.append((cp >= 32 && cp < 127) ? (char)cp : '?');
                    }
                    System.out.println("block_name(TU): '" + sb.toString() + "'");
                } else {
                    // 也许是 TV (ASCII)
                    // 复位，尝试 ASCII
                    // 如果 nameLen=0 可能是长度读错了
                    System.out.println("nameLen = " + nameLen + " (可能不是 TU)");
                }
            } catch (Exception e) {
                System.out.println("error: " + e.getMessage());
            }
        }

        System.out.println("\n=== BLOCK (handle=0x1b, offset=23203) 字段分析 ===\n");
        {
            long offset = 23203;
            ByteBufferBitInput bb = new ByteBufferBitInput(data);
            bb.seek(offset * 8L);
            BitStreamReader r = new BitStreamReader(bb, DwgVersion.R2000);

            int objSize = r.readModularShort();
            int type = r.readBitShort();
            System.out.println("obj_size: " + objSize + ", type: 0x" + Integer.toHexString(type));

            try {
                long owner = r.readHandle();
                System.out.println("owner_handle: 0x" + Long.toHexString(owner));

                int numReactors = r.readBitShort();
                System.out.println("num_reactors: " + numReactors);

                if (numReactors > 0 && numReactors < 20) {
                    for (int i = 0; i < numReactors; i++) r.readHandle();
                }

                long xdict = r.readHandle();
                System.out.println("xdict_handle: 0x" + Long.toHexString(xdict));

                long pos1 = bb.position();
                System.out.println("after common bit_pos: " + pos1 + " (byte " + pos1/8 + " +" + pos1%8 + ")");

                // 尝试 block_name (TU)
                int nameLen = r.readBitShort();
                System.out.println("nameLen(BS): " + nameLen);

                if (nameLen > 0 && nameLen < 200) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < nameLen; i++) {
                        int lo = r.getInput().readBits(8) & 0xFF;
                        int hi = r.getInput().readBits(8) & 0xFF;
                        int cp = lo | (hi << 8);
                        sb.append((cp >= 32 && cp < 127) ? (char)cp : '?');
                    }
                    System.out.println("block_name(TU): '" + sb.toString() + "'");
                }

                // 恢复位置，试试 ASCII
                bb.seek(pos1);
                int nameLen2 = r.readBitShort();
                if (nameLen2 > 0 && nameLen2 < 200) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < nameLen2; i++) {
                        int b = r.getInput().readBits(8) & 0xFF;
                        sb.append((b >= 32 && b < 127) ? (char)b : '?');
                    }
                    System.out.println("block_name(ASCII): '" + sb.toString() + "'");
                }

                // 也许 bytes 12+ 是直接的 ASCII (没有 length prefix)?
                // 原始字节: ... 4C 71 40 00 00 00 46 E9 0C 2A 50 61 70 65 72 5F 53 70 61 63 65 ...
                //                                         ^^ "Paper_Space" 从 byte 12 开始？
                // 等等：offset=23203 的 dump 是
                //   0: 25 00 4C 71 40 00 00 00 46 E9 0C 2A 50 61 70 65
                //  16: 72 5F 53 70 61 63 65 C0 55 00 52 08 09 82 81 88
                // byte 12 = 50 = 'P' ✓ 即 block_name 在 byte 12 开始 (bit 96)
                // bit 96 是从 offset 开始算：obj_size(2 bytes)+type_code(?) + bitsize(?) + entity_handle(?)
                // 让我从 byte 12 开始直接读
                System.out.println("\n从 byte 12 直接开始读字符串:");
                // 从 object 起始 byte 12 处
                int byteStart = 12;
                StringBuilder sb = new StringBuilder();
                // 找到字符串结束 (0xC0 之后)? 观察:
                // 50 61 70 65 72 5F 53 70 61 63 65 C0 55 00 52 08
                // "Paper_Space" 后面是 C0
                for (int i = 0; i < 20; i++) {
                    int b = data[(int)offset + byteStart + i] & 0xFF;
                    if (b >= 32 && b < 127) sb.append((char)b);
                    else sb.append('[').append(Integer.toHexString(b)).append(']');
                    if (b == 0xC0 || b == 0) break;
                }
                System.out.println("  ASCII until 0xC0: '" + sb.toString() + "'");
            } catch (Exception e) {
                System.out.println("error: " + e.getMessage());
            }
        }

        // 从字节位置精确定位：obj_size(2) + type_code(?) + bitsize(RL 4) + ent_handle(?)
        // 实际上：
        // 0-1: obj_size (MS 编码成 2 bytes)
        // 2-3: type_code (BS: 2-bit opcode + 16-bit or 8-bit)
        // 4-7: bitsize (RL, 4 bytes)
        // 8+: entity_handle (H: counter bytes + counter+1 bytes)
        //   byte8: 0x46 = 0100_0110: high4=4, low4=6, counter=6 => 6 bytes value: E9 0C 2A 50 61 70
        //     = 0xE90C2A506170 - 这显然不是 handle (太大)
        //   等等，byte8 开始是 ASCII 字符串 "Paper_Space" 的一部分？
        //   bytes 8-11: 46 E9 0C 2A - 这看起来像 ASCII "F??*" 不
        //   实际上: byte 12 = 0x50 = 'P', byte 13 = 0x61 = 'a', ...
        //   所以 block_name 从 byte 12 开始
        //   bytes 4-11: 40 00 00 00 46 E9 0C 2A
        //              = bitsize(RL 4 bytes) = 0x40 = 64 (LE16 读到的是 0x00000040)
        //              + 实体 handle (H: counter bytes 4 bytes?)
        //              byte8=0x46: high4=4, low4=6 => 6 bytes after: E9 0C 2A 50 61 70
        //                但 bytes 12+ 是字符串，所以实际 ent_handle 用了不同格式
        //                让我试: byte8 0x46 high4=0x4=4, low4=0x6=6
        //                实际上: 0x46 high nibble 0x4=4, 低 nibble=6 => counter=6?
        //                这不对：那么 handle 是 E9 0C 2A 50 61 70 共 6 字节，
        //                然后后面应是 EED loop size(BS)，但 byte14=0x72=0111_0010 -> opcode 01, 8 bits = 0111_0010 = 114
        //                或 byte14=0x72=114, 然后读 114 bytes，这也不对

        // 哦 wait: handle 编码：1st byte is code(high nibble) + counter(low nibble).
        // counter = low nibble of first byte. 然后 counter 字节读为 handle 内容
        // 但 libredwg 的 handle 编码是: first byte 4 bits reference type + 4 bits counter.
        // 参考代码：bit_read_H()：byte0 (code<<4) | counter, counter bytes follow.
        // 但 R2000 不是这样的 - 或许 ent_handle 有不同的编码
        // 让我看看 bytes 4-7 = 40 00 00 00 (RL=64)：
        //   如果 bitsize=64 (位数量): 64 bits = 8 bytes，正好到 byte 12 结束
        //   所以 0x40 = bitsize 是整个对象的 bitsize 值？
        //   obj_size = 37 bytes = 296 bits, bitsize 可能是对象 size 相关的
        // 等等，之前我们读到 bitsize(RL) = 0x40 = 64 (从 object 起始 byte 4 读 4 bytes LE)
        //   RL 格式: byte4=b0=0x40, byte5=0x00, byte6=0x00, byte7=0x00 => 0x00000040 = 64
        //   bitsize 表示对象的总 bit 数？37 bytes = 296 bits, 不是 64
        //   bitsize 可能表示实体之后的"固定部分"的 bit 数
        //   实际上: libredwg bitsize 就是从 "0x40" 字段（对象头部）读到的 RL 值
        //   这个值会在解析内部字段时用到

        // 那么 bytes 8-11 = 46 E9 0C 2A 是什么？
        // 可能是 ent_handle (H: 1+counter bytes)
        // byte8=0x46, high4=4, low4=6. counter=6 => 6 bytes
        // 但对象总共只有 37 字节，不能读 6 字节！
        // 可能是 H: 1+1 = 2 bytes: low4 是 counter=4?
        // 让我看看：0x46 -> 也许是 (code, counter) = (4, 6)，但 counter 6 意味着
        // 读取 6 字节 -> 0x0C2A50617065 -> 这太大了
        // 也许 R2000 的 H 编码方式不同！
        // 看 libredwg R2000 格式：H (handle) 格式: first byte has code(4 bits) + counter(4 bits)
        // 但 counter 指的是 *32-bit words* 还是 bytes？
        // 或者 counter 是 "额外的 bytes" = counter bytes
        // 如果 counter=6，那么读 6 个字节的数据，得到 handle
        // 但看实际数据：从 byte 8 开始: 46 E9 0C 2A 50 61 70 65 72 5F...
        // 如果 byte8=0x46: code=0x4, counter=0x6, 那么 handle = 0xE90C2A506170
        // 这显然不对，因为 0x5061706572 = "Paper" 这些是字符串！

        // 这里的关键发现：block_name 直接从 byte 12 开始（或 12 附近）
        // 所以 bytes 4-11 是: bitsize(RL 4) + entity_handle (H: 但编码方式?)
        //  40 00 00 00 46 E9 0C 2A -> bitsize=0x40=64, 然后 handle 是 E9 0C 2A? 或其他方式？
        // 让我试试 counter 是个 low nibble 表示 "1-byte handle"

        System.out.println("\n=== 精确分析 bytes 4-12 字段 ===");
        System.out.println("BLOCK @23203: bytes 4-11 = 40 00 00 00 46 E9 0C 2A");
        System.out.println("BLOCK @23159: bytes 4-11 = 40 00 00 00 47 E9 0C 2A");

        // 测试不同可能的 handle 编码方式
        for (long offset : new long[]{23203, 23159, 21636}) {
            System.out.println("\n[BLOCK @offset=" + offset + "]");
            // byte 8:
            int byte8 = data[(int)offset + 8] & 0xFF;
            System.out.println("  byte8=0x" + Integer.toHexString(byte8) + " high4=0x" + Integer.toHexString(byte8>>4) + " low4=0x" + Integer.toHexString(byte8 & 0x0F));

            // bytes 8-11:
            long bytes8_11 = (long)(data[(int)offset + 8] & 0xFF)
                | ((long)(data[(int)offset + 9] & 0xFF) << 8)
                | ((long)(data[(int)offset + 10] & 0xFF) << 16)
                | ((long)(data[(int)offset + 11] & 0xFF) << 24);
            System.out.println("  bytes 8-11 LE32: 0x" + Long.toHexString(bytes8_11));

            // 也许 byte8 是 counter=1 (1 byte handle value only), 然后 byte9=handle
            // 但 byte8=0x46 for handle 0x1b? 0x46 != 0x1b

            // 让我看看 handle 本身: 对于 offset 23203，handle 是 0x1b=27
            // 0x1b 在数据中哪里能找到？ bytes 15:0x65=101, 14:0x70=112, 13:0x61=97, 12:0x50=80
            // byte 11=0x2A=42, byte 10=0x0C=12, byte9=0xE9=233, byte8=0x46=70
            // 都不是 27。这说明 handle 也许不是直接编码的数字，而是 ref number？
            // 或者 R2000 中 entity header 根本没有 entity_handle 字段？
        }

        // 也许正确的解析是：obj_size(MS 2 bytes) + type_code(LE 2 bytes) + block_data(bytes 4..)
        // 其中 block_data 是 block-specific 字段：bitsize(RL 4) + block_name(???) + ...
        // type_code(LE 2 bytes): 0x4C 0x71 (LE) = 0x714C = 29004
        // 但之前从 bit-level 读到的 type_code 是 0x31=49
        // 这说明从 offset 2 开始的 bit-level 读的是 0x31，不是 byte-aligned LE16!

        // 所以：对象数据结构是 bit-level (不是 byte-aligned)
        // MS(obj_size) 从 bit 0 开始
        // BS(type_code) 从 bit <MS 结束位置> 开始
        // 之后 <type_code 结束> 位位置 继续读其他字段

        // obj_size = 0x25 (25h = 37) - 也许 MS 编码为 2 bytes 但读值是 37
        // MS 编码格式: high bit of first byte = 0 means last byte
        //   value is 7 bits per byte (low 7 bits of each byte)
        //   所以 0x25 0x00: first byte high bit = 0 (继续？)
        //   不对：实际上 0x25 = 0010_0101, high bit=0 -> last byte
        //     所以 obj_size = (0x25 & 0x7F) = 0x25 = 37 ✓

        // 那么 type_code 从 byte 1 (bit 8) 开始: 00 4C 71... (0100_1100_0111_0001)
        //   opcode = 00 (2 bits) => read 16 bits = 00_1100_0111_0001 = 0x0C71 = 3185 (错误)
        //   等等，让我重新看 data: offset23203 bytes: 25 00 4C 71 ...
        //   byte 0 = 0x25 = 0010_0101
        //   MS 编码: low 7 bits = value; high bit = continuation (1=继续, 0=最后)
        //   0x25: high bit = 0 -> 最后字节，value = 0x25 & 0x7F = 0x25 = 37，obj_size=37 ✓
        //   所以 MS 只用了 1 byte (byte 0)，下一字段从 byte 1 (bit 8) 开始！
        //   bytes 1-3: 00 4C 71 - 这是 type_code (BS)
        //   BS 从 bit 8 开始: bit stream = 0000_0000_0100_1100_0111_0001 (从 byte 1 开始)
        //   opcode(2 bits) = 00, 然后读 16 bits (0000_0100_1100_0111) = 0x04C7 = 1223
        //   不对！让我再读一次 BitStreamReader.readBitShort:
        //     opcode 2 bits: 00 -> read 16 bits LE
        //     opcode 2 bits: 01 -> read 8 bits
        //     opcode 2 bits: 10 -> return 0
        //     opcode 2 bits: 11 -> return 256
        //   所以 opcode 读的是 first 2 bits of 字段: 00 000000 0100 1100 = wait
        //   byte 1 = 00 = 0000_0000
        //   从 byte 1 开始: bit 1 = 0, bit 2 = 0 -> opcode = 00 -> read 16 bits
        //   剩下的 bit stream from (byte1_bit2): 000000 01001100 01110001...
        //   16 bits = 00000001_00110001 = 0x0131 = 305 (不对)
        //   让我重新考虑，BS 格式: 2 bit opcode, 条件数据
        //   从 byte 1 bit 0 开始: 0000_0000
        //     opcode(2) = 00 -> then 16 bits from position 2: 0000000100110001 = 0x0131 = 305
        //   但是之前测试得到 type=0x31=49! 那应该是 opcode 10 (=0) or...
        //   让我再想想：之前测试打印 "type(BS): 0x30" 对 offset=21177
        //   offset 21177 的 bytes 0-3: 72 00 4C 12
        //   0x72: high bit = 0 (0111_0010), low 7 bits=0x32=50? that's wrong
        //   wait: 0x72=0111_0010, high bit=0 -> last byte, value=0x32=50... 但之前是 114

        // 算了，我有更清晰的方式：从之前的实际代码输出中，
        // MS(obj_size) readBitShort(type_code) 确实工作了：
        //   offset 21177: obj_size=114, type=0x30
        //   offset 23203: obj_size=37, type=0x31
        //   offset 23159: obj_size=40, type=0x31
        //   offset 50409: obj_size=38, type=0x07 (INSERT)
        // 所以 *我们的 BitStreamReader 是正确的*，
        // 问题在于后续字段的解析方式

        // 我现在知道了:
        //   obj_size 从 byte 0 开始，占用 1 byte (MS 0x25 = 37) 或 2 bytes (0x72 0x00 = 114?)
        //   等等 0x72=01110010 high bit=0 是最后字节 -> 1 byte，value 0x72 & 0x7F = 0x32 = 50
        //   但是之前 readModularShort 得到 obj_size = 114!  这说明 MS 编码格式不同

        // 让我直接验证一下 readModularShort 对 offset 21177 的行为
        System.out.println("\n=== 验证 MS 编码方式 ===");
        {
            long offset = 21177;
            ByteBufferBitInput bb = new ByteBufferBitInput(data);
            bb.seek(offset * 8L);

            // byte 0 = 0x72 = 01110010
            int byte0 = data[(int)offset] & 0xFF;
            System.out.println("byte0=0x" + Integer.toHexString(byte0) + " high_bit=" + (byte0 >> 7));

            // readModularShort: 读 MS (modular short)
            // libredwg: modular short reads 16-bit LE words but with high-bit continuation
            int word0 = (data[(int)offset] & 0xFF) | ((data[(int)offset + 1] & 0xFF) << 8);
            System.out.println("word0 (LE16)=0x" + Integer.toHexString(word0));
            // 0x0072 = 114 如果 high bit 为 0 则结束
            // high bit of 16-bit word = 0, 所以 value = word0 & 0x7FFF = 0x0072 & 0x7FFF = 0x0072 = 114
            // 但 wait: word0 = 0x0072 = 00000000_01110010 high bit of 16 bits = 0 -> value=114 ✓
            System.out.println("value=word0 & 0x7FFF = " + (word0 & 0x7FFF));

            // type_code from byte 2: 4C 12 -> LE16 word = 0x124C
            // bit-level BS: from bit 16:
            // bit 16=0, bit17=1, 所以 opcode=01 -> 8-bit mode
            // 读 8 bits: 从 bit 18: 00_1100_0001 = 0xC1 = 193 (不对!)
            // 等等，我们之前的测试显示 type = 0x30 = 48，所以编码方式不同
        }
    }
}
