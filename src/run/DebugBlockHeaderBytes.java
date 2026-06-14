package run;

import java.nio.ByteBuffer;
import java.nio.file.Paths;

/**
 * 深度检查 BLOCK_HEADER 对象的原始字节
 * 分析 R2000 BLOCK_HEADER (type=0x05) 的精确字段结构
 */
public class DebugBlockHeaderBytes {

    public static void main(String[] args) throws Exception {
        String filename = "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg";
        byte[] data = java.nio.file.Files.readAllBytes(Paths.get(filename));

        // 已知 BLOCK_HEADER 的对象偏移 (从之前的分析):
        // handle 0x1d @ 0x111c1, handle 0x21 @ 0x1118c, handle 0x25 @ 0x6f0c
        // 还需要找到系统块 (*Model_Space, *Paper_Space) 等
        // 另外: 从 Handles section 我们知道的其他 BLOCK_HEADER 位置
        // handles: 0x1d, 0x21, 0x25, 0xd0, 0xfd, 0x11a, 0x122, 0x185, 0x190, 0x19c...

        // 先分析最典型的几个对象：
        // 选几个具有代表性的对象偏移
        int[] offsets = {
            0x111c1, // 0x1d
            0x1118c, // 0x21
            0x6f0c,  // 0x25
            0x705b,  // 0xd0
            0x71ef,  // 0xfd
        };

        for (int offset : offsets) {
            analyzeObject(data, offset);
            System.out.println();
            System.out.println("----------------------------------------------------------");
            System.out.println();
        }

        // 额外：找系统块 (可能在文件开头附近)
        // 从之前的 type 统计，有 BLOCK_HEADER type=0x30 @ offset 0x52b9 (handle 0x1)
        // 检查这个
        System.out.println();
        System.out.println("===== 检查可能的系统块 (type 0x30) =====");
        analyzeObject(data, 0x52b9);

        // 检查 INSERT
        System.out.println();
        System.out.println("===== 检查 INSERT (type 0x07) =====");
        // 需要找到 INSERT 的偏移 - 从之前分析 handle 0xd5 应该是 INSERT
        // 让我尝试在已知对象中查找
    }

    private static void analyzeObject(byte[] data, int offset) {
        System.out.println();
        System.out.println("=== 对象 @ 0x" + Integer.toHexString(offset) + " ===");

        // 读取 objSize (2 bytes LE)
        ByteBuffer bb = ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int objSize = bb.getShort(offset) & 0xFFFF;

        // 验证: 另一种可能 - objSize 是 modular short (最高位为 0 表示只有 1 字节)
        int byte0 = data[offset] & 0xFF;
        int byte1 = data[offset + 1] & 0xFF;
        int byte2 = data[offset + 2] & 0xFF;
        int byte3 = data[offset + 3] & 0xFF;

        System.out.println("  Obj size (LE short): " + objSize);
        System.out.println("  原始字节: " + String.format("%02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X",
            byte0, byte1, byte2, byte3,
            data[offset + 4] & 0xFF, data[offset + 5] & 0xFF,
            data[offset + 6] & 0xFF, data[offset + 7] & 0xFF,
            data[offset + 8] & 0xFF, data[offset + 9] & 0xFF,
            data[offset + 10] & 0xFF, data[offset + 11] & 0xFF));

        // 打印接下来的 64 字节 (十六进制 + ASCII)
        System.out.println();
        System.out.println("  对象内容 (前 80 字节):");
        int printLen = Math.min(80, data.length - offset);

        // ASCII 行:
        StringBuilder asciiLine = new StringBuilder("  ASCII: ");
        StringBuilder hexLine = new StringBuilder("  HEX:   ");

        for (int i = 0; i < printLen; i++) {
            int b = data[offset + i] & 0xFF;
            hexLine.append(String.format("%02X ", b));
            char c = (char) b;
            asciiLine.append((c >= 32 && c < 127) ? c : '.');

            if ((i + 1) % 16 == 0) {
                // 打印位置标记
                System.out.printf("  +%03X | %s | %s%n",
                    i - 15, hexLine.substring(8), asciiLine.substring(10));
                hexLine.setLength(8);
                asciiLine.setLength(10);
            }
        }
        // 打印剩余部分
        if (hexLine.length() > 8) {
            while (hexLine.length() < 8 + 16 * 3) hexLine.append("   ");
            System.out.printf("  +%03X | %s | %s%n",
                (printLen / 16) * 16, hexLine.substring(8), asciiLine.substring(10));
        }

        // 尝试用 bit-level 解析:
        System.out.println();
        System.out.println("  Bit-level 解析尝试:");

        // 假设 MS = 2 bytes (modular short: 第一个16位最高位是0, 即值 < 0x8000)
        // 实际上 R2000 的 objSize 可能是 simple uint16 LE
        // 让我们尝试两种理解方式:

        // 方式1: objSize = LE uint16 (前2字节)
        // typeCode = LE uint16 或者 BS (2bit opcode + data)
        // 从字节看: 0x12 0x00 -> objSize=0x0012=18
        // 下两字节: 0x41 0x55 -> typeCode?
        // 但 0x4155 = 16725，看起来不像 typeCode

        // 方式2: objSize 是 modular short (bit-level)
        //   第一个 16 bits: bit 15 (continuation) = 0, bits 0-14 = value
        //   0x12 0x00 = bits: 0x0012 = 18 (valid, continuation bit 0)
        //   BS: opcode = bits 15-16 of next word? No, BS is 2-bit opcode + data

        // 让我们直接从 offset*8 位开始逐步读取:
        // MS -> 读取 16 bits LE (如果最高位为0，停止)
        // BS -> 2 bits opcode, then 0/8/16 bits based on opcode

        // 手动模拟读取:
        // byte0=0x12 byte1=0x00 -> MS = 0x0012 = 18 (continuation bit=0)
        // 然后从 offset+2 开始是 BS
        // byte2=0x41 byte3=0x55
        // BS: 先看2 bits: 0x41 = 0100 0001
        // opcode = bits 0-1 of byte2... wait, bit-level reading starts from LSB or MSB?
        // In R2000, typically read from LSB of current byte

        // 让我们从 offset+2 开始，手动尝试不同的 BS 编码:
        // byte[2]=0x41 = 0100_0001
        // byte[3]=0x55 = 0101_0101
        //
        // BS opcode 00: read 16 bits LE = 0x5541 = 21825 (太大)
        // BS opcode 01: read 8 bits = 0x41 = 65 (这是 'A' 字符!)
        // BS opcode 10: value = 0
        // BS opcode 11: value = 256

        // 等等，也许对象的结构并不是 MS+BS
        // 让我们检查 byte2 之后是否是文本字符串
        // 0x41='A', 0x55='U', 然后是 0x00 0x00 0x00 0x00, 然后是 0x74='t'

        // 看起来 "AU" 可能不是块名，也许这是 bit-coded 的一部分
        // 但 "t!4!" 在后续位置
        // 让我们看看第 8 字节开始: 0x74 0x21 0x34 0x21 = "t!4!"

        // 让我们尝试: objSize = 18 (bytes 0-1), 然后 byte2+ 是文本长度 (BS)
        // byte2 = 0x41, byte3 = 0x55
        // BS opcode = bits 0-1 of byte2 = 01 -> read 8 bits = byte2>>2 = 0x10 = 16?
        // 不，BS 编码规则: opcode (LSB 2位) 决定后续读取
        // opcode=01, 接下来 8 bits (从 bit2 开始) = 0x41 >> 2 = 0x10? 不对

        // 实际 BS 规则:
        // 2-bit opcode: 00 -> 接下来读16 bits LE
        //               01 -> 接下来读8 bits (当前 byte 的高6位)
        //               10 -> value = 0
        //               11 -> value = 256

        // byte2 = 0x41 = 0b0100_0001
        // opcode = 01 (bits 0-1 = 01)
        // value = bits 2-7 of byte2 = 0b0001_0000 = 0x10 = 16? No.
        // 或者: value = byte2 >> 2 = 0x10 = 16

        // 让我尝试另一种解释: value = byte2 (去掉已读的2 bits) = 0x41 >> 2 = 0x10 = 16
        // 16 可能是 block name 长度
        // 然后接下来的 16 字节是块名: 0x55 0x00 0x00 0x00 0x00 0x74 0x21 0x34 0x21 ...
        // 这不对，0x55='U' 后是 0x00

        // 让我尝试直接从字节查找字符串，然后看字节2是什么
        System.out.println();
        System.out.println("  ASCII 字符串查找:");
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < Math.min(100, data.length - offset); i++) {
            int b = data[offset + i] & 0xFF;
            if (b >= 32 && b <= 126) {
                cur.append((char) b);
            } else {
                if (cur.length() >= 2) {
                    System.out.println("    +" + (i - cur.length()) + ": \"" + cur + "\" (len=" + cur.length() + ")");
                }
                cur.setLength(0);
            }
        }
        if (cur.length() >= 2) {
            System.out.println("    +" + (printLen - cur.length()) + ": \"" + cur + "\"");
        }

        // 尝试理解 objSize=18: 对象大小是 18 bytes，从 offset+2 开始到 offset+2+18
        System.out.println();
        System.out.println("  对象体 (objSize=18, 字节 2-19):");
        for (int i = 2; i < 2 + 18 && offset + i < data.length; i++) {
            int b = data[offset + i] & 0xFF;
            char c = (b >= 32 && b < 127) ? (char) b : '.';
            System.out.printf("    byte[%02d] 0x%02X %3d  '%c'%n", i, b, b, c);
        }
    }
}
