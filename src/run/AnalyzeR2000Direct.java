package run;

import io.dwg.core.io.*;
import io.dwg.core.version.DwgVersion;
import java.nio.file.Paths;

/**
 * 精确的 R2000 对象结构分析 - 直接从原始字节入手
 *
 * 观察:
 * 1. obj_size + type_code 的结构清晰
 * 2. BLOCK (0x31): block_name ASCII 字符串位置
 *    - offset 23203: "Paper_Space" 从 byte 12 开始
 *    - offset 21677: "SW_NOTE_0" 从 byte 11 开始
 * 3. 字节 4-7 = 40 00 00 00 (RL bitsize=64) 对所有
 * 4. bytes 8-11 对 BLOCK:
 *    - 23203: 46 E9 0C 2A
 *    - 23159: 47 E9 0C 2A
 *    - 21677: 73 A9 09 53 (但 "SW_NOTE_0" 从 byte 11 开始)
 *
 * 等等：让我重新检查 W_NOTE_0:
 *   byte 0-3: 26 00 4C 6D (obj_size=38, type=0x31)
 *   byte 4-7: 40 00 00 00 (bitsize=64)
 *   byte 8-11: 73 A9 09 53
 *   byte 12-19: 57 5F 4E 4F 54 45 5F 30 = "W_NOTE_0"
 * 实际上 "SW_NOTE_0" = 53 57 5F 4E 4F 54 45 5F 30:
 *   byte 11 = 0x53 = 'S', byte 12 = 0x57 = 'W', byte 13 = '_', ...
 *   所以 "SW_NOTE_0" 从 byte 11 开始，到 byte 19 结束
 *
 * 这意味着 bytes 8-10 = 73 A9 09 是 handle 或其他编码的一部分
 *
 * H 编码：byte8 = 0x73, R=7, C=3 -> value = 4 字节 (A9 09 53 57)
 *   = 0xA9095357 - 这看起来不像 handle
 *
 * 或者 byte8 = 0x46 (对 Paper): R=4, C=6 -> 7 bytes (E9 0C 2A 50 61 70 65)
 *   = 0xE90C2A50617065 太大，而且 50 61 70 65 = "Paper"
 *
 * 啊！我明白了 - block_name 直接从 byte 12 开始，没有 length prefix
 * 而 bytes 8-11 是 entity_handle (H)
 *
 * bytes 8-11 对 Paper_Space: 46 E9 0C 2A
 * 如果把它看作 4-byte H: byte8 = 0x46 (R=4, C=6? No)
 *   让我试试: 0x46 high=4 low=6, counter = 6, 需要 7 bytes value
 *   但接下来只有 3 bytes 到 byte 12... 这不对
 *
 * 另一个可能：byte8 是某种 extended handle 格式
 * 或者 bytes 8-11 是 LE32 handle value (无前缀)
 *   Paper: 0x2A0CE946 = 705,819,526 (太大)
 *   但 0x46 = 'F'?
 *
 * 让我看看实际句柄值 (从 handle section 解析):
 *   对 BLOCK @23203: 从 handle table 看，实际句柄可能是某个小数字
 *
 * 实际上我之前看到 AnalyzeR2000ParsePath 的输出可能有提示...
 * 让我直接读源代码中的 H 解码实现。
 */
public class AnalyzeR2000Direct {

    static byte[] data;

    public static void main(String[] args) throws Exception {
        String filename = "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg";
        data = java.nio.file.Files.readAllBytes(Paths.get(filename));

        // 先用 io.dwg.core.io.BitStreamReader 的现有方法
        // 尝试不同的 bit offset 从 type_code 结束处开始
        System.out.println("===== BLOCK @23203 (Paper_Space) =====");
        analyzeWithBitStart(23203, 26);  // type_code 后的 bit 位置

        System.out.println("\n===== BLOCK_HEADER @21177 =====");
        analyzeWithBitStart(21177, 26);

        System.out.println("\n===== INSERT @50409 =====");
        analyzeWithBitStart(50409, 26);

        // 尝试找到 INSERT 的 block_header_handle 位置
        System.out.println("\n===== INSERT 详细字节结构 =====");
        analyzeInsertBytes(50409);
    }

    private static void analyzeWithBitStart(int offset, int headerBits) {
        // 从 offset 字节 + headerBits 位开始尝试多种解码
        System.out.println("HEX dump of bytes 0-50:");
        for (int i = 0; i < 50; i++) {
            if (i % 16 == 0) System.out.print(String.format("%n%3d: ", i));
            System.out.printf("%02X ", data[offset + i] & 0xFF);
        }
        System.out.println();

        // 打印 ASCII
        System.out.print("ASCII: ");
        for (int i = 0; i < 50; i++) {
            int b = data[offset + i] & 0xFF;
            System.out.print((b >= 32 && b < 127) ? (char)b : '.');
        }
        System.out.println();

        // 尝试从 headerBits 位之后直接读取 H + BS + H + ...
        ByteBufferBitInput bb = new ByteBufferBitInput(data);
        bb.seek(offset * 8L + headerBits);
        BitStreamReader r = new BitStreamReader(bb, DwgVersion.R2000);
        try {
            // Read H
            long h1 = r.readHandle();
            System.out.println("H1 = 0x" + Long.toHexString(h1));
            // BS
            int bs1 = r.readBitShort();
            System.out.println("BS1 = " + bs1);
            // H
            long h2 = r.readHandle();
            System.out.println("H2 = 0x" + Long.toHexString(h2));
            // 之后 TV
            int nameLen = r.readBitShort();
            System.out.println("TV length = " + nameLen);
            if (nameLen > 0 && nameLen < 200) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < nameLen; i++) {
                    int b = r.getInput().readBits(8) & 0xFF;
                    if (b >= 32 && b < 127) sb.append((char)b);
                    else sb.append('?');
                }
                System.out.println("  ASCII: '" + sb + "'");
            }
        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
        }

        // 打印关键 bit position 的字节值
        long posAfterHeader = offset * 8L + headerBits;
        int bytePos = (int)(posAfterHeader / 8);
        int extraBits = (int)(posAfterHeader % 8);
        System.out.println("Header 后位置: byte=" + bytePos + " (offset byte " + (bytePos - offset) + ") extra=" + extraBits);
    }

    private static void analyzeInsertBytes(int offset) {
        // INSERT @50409: bytes 0-38
        // obj_size=38, type=0x07
        // 0: 26 00 41 F9 40 00 00 00 75 65 34 21 D0 D2 F0 C3
        // 16: 69 27 19 25 00 32 C6 B1 A9 BD BF 42 40 BA 91 86
        // 32: 08 45 08 12 8B C2
        //
        // 总 38 bytes
        // bytes 0-3: 26 00 41 F9 (obj_size + type_code)
        // bytes 4-7: 40 00 00 00 (bitsize RL=64)
        // bytes 8-11: 75 65 34 21 (entity_handle?)
        // bytes 12-15: D0 D2 F0 C3 (layer or other?)
        // bytes 16-19: 69 27 19 25
        // bytes 20-23: 00 32 C6 B1
        // bytes 24-27: A9 BD BF 42 (可能是 block_header_handle?)
        // bytes 28-31: 40 BA 91 86 (double? 0x40BA9186 = 5.8?)
        // bytes 32-35: 08 45 08 12
        // bytes 36-37: 8B C2

        // 让我看看 0x40 开头的字节
        // BD 编码: 2-bit opcode, 00 = full 64-bit double, 01 = 1-bit sign + 32-bit integer
        // bytes 28-31: 40 BA 91 86 -> bit 0-31
        // 如果把 bytes 28-35 看作 double (64-bit)
        //   40 BA 91 86 08 45 08 12 = LE? BE?
        //   LE double: bytes 28..35 = 40 BA 91 86 08 45 08 12
        //     sign=0, exp=10000000111_2 = 1031 - 1023 = 8
        //     mantissa = 0xBA918608450812...
        //     value ≈ 2^8 * 1.46... ≈ 375

        // 打印 bytes 4 到最后的 16-bit LE 值
        System.out.println("INSERT @50409: 逐字段分析");
        System.out.println("bytes 0-3: obj_size=" + ((data[offset] & 0xFF) | ((data[offset+1] & 0xFF) << 8) & 0x7FFF)
            + ", type_code_bytes=" + String.format("%02X %02X", data[offset+2] & 0xFF, data[offset+3] & 0xFF));

        // bitsize (RL 4 bytes)
        int bitsize = (data[offset+4] & 0xFF) | ((data[offset+5] & 0xFF) << 8)
            | ((data[offset+6] & 0xFF) << 16) | ((data[offset+7] & 0xFF) << 24);
        System.out.println("bytes 4-7: bitsize(RL) = " + bitsize);

        // entity_handle (H): 从 byte 8 开始
        int hbyte = data[offset+8] & 0xFF;
        System.out.println("byte 8: 0x" + Integer.toHexString(hbyte) + " (R=" + (hbyte>>4) + ", C=" + (hbyte & 0x0F) + ")");
        // 尝试 4 字节 LE
        long h4 = (data[offset+8] & 0xFF)
            | ((long)(data[offset+9] & 0xFF) << 8)
            | ((long)(data[offset+10] & 0xFF) << 16)
            | ((long)(data[offset+11] & 0xFF) << 24);
        System.out.println("bytes 8-11 as LE32: 0x" + Long.toHexString(h4));

        // 从 byte 12 开始尝试: block_header_handle (H)
        int hbyte12 = data[offset+12] & 0xFF;
        System.out.println("byte 12: 0x" + Integer.toHexString(hbyte12) + " (R=" + (hbyte12>>4) + ", C=" + (hbyte12 & 0x0F) + ")");
        // 4 字节 LE
        long h12 = (data[offset+12] & 0xFF)
            | ((long)(data[offset+13] & 0xFF) << 8)
            | ((long)(data[offset+14] & 0xFF) << 16)
            | ((long)(data[offset+15] & 0xFF) << 24);
        System.out.println("bytes 12-15 as LE32: 0x" + Long.toHexString(h12));

        // 检查 INSERT 的大小变化是否能找到 block 引用
        // 对于 INSERT 我们需要找到 block_header_handle - 这是一个 H (handle)
        // 实际期望的 handle 可能是某些 BLOCK 的 handle (如 0x21 等小数字)
        // 看看哪些字节是 0xXX 小值
        System.out.println("\n所有字节的十进制值:");
        for (int i = 0; i < 38; i++) {
            System.out.print(String.format("%3d:%3d ", i, data[offset + i] & 0xFF));
            if (i % 10 == 9) System.out.println();
        }
        System.out.println();
    }
}
