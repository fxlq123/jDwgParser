import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 手动验证 R2000 对象位级解析
 */
public class VerifyBitParse {

    public static void main(String[] args) throws Exception {
        byte[] data = Files.readAllBytes(Paths.get("210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));

        // 已知 offsets
        int[] offsets = {0x52b9, 0x532f, 0x534e, 0x5370, 0x538f, 0x539d, 0x53ab, 0x53bc, 0x53cd, 0x53eb, 0x53f9, 0x6ccd, 0x6ce2, 0x11216};

        for (int off : offsets) {
            // Show bytes
            System.out.println("\n=== @ 0x" + Integer.toHexString(off));
            System.out.print("Bytes: ");
            for (int i = 0; i < 16; i++) {
                if (off + i < data.length)
                    System.out.printf("%02x ", data[off+i] & 0xFF);
            }
            System.out.println();

            // MS
            int ms = (data[off] & 0xFF) | ((data[off+1] & 0xFF) << 8);
            System.out.println("MS = " + ms + " bytes");

            // Bit Short type
            int byte2 = data[off + 2] & 0xFF;
            int byte3 = data[off + 3] & 0xFF;
            int byte4 = data[off + 4] & 0xFF;

            // Extract opcode (2 bits)
            int opcode = ((byte2 >> 6) & 0x03);
            System.out.println("byte2=0x" + String.format("%02x", byte2) + " = " + String.format("%8s", Integer.toBinaryString(byte2)).replace(' ', '0'));
            System.out.println("opcode (bits 7-6): " + opcode + " (" + (opcode == 0 ? "16-bit value" : opcode == 1 ? "8-bit value" : opcode == 2 ? "value=0" : "value=256") + ")");

            int type = 0;
            int bitsForType = 0;

            if (opcode == 0) {
                // 16 bits: byte2 bits 5-0 (6 bits) + byte3 all (8 bits) + byte4 bits 7-6 (2 bits)
                int val = 0;
                // byte2 bits 5-0
                for (int i = 2; i < 8; i++) val = (val << 1) | ((byte2 >> (7 - i)) & 1;
                // byte3 all 8 bits
                for (int i = 0; i < 8; i++) val = (val << 1) | ((byte3 >> (7 - i)) & 1);
                // byte4 bits 7-6
                for (int i = 0; i < 2; i++) val = (val << 1) | ((byte4 >> (7 - i)) & 1;
                type = val;
                bitsForType = 18;
                System.out.println("Type (16-bit): " + type + " (0x" + Integer.toHexString(type) + ")");
            } else if (opcode == 1) {
                // 8 bits: byte2 bits 5-0 (6 bits) + byte3 bits 7-6 (2 bits)
                int val = 0;
                for (int i = 2; i < 8; i++) val = (val << 1) | ((byte2 >> (7 - i)) & 1;
                for (int i = 0; i < 2; i++) val = (val << 1) | ((byte3 >> (7 - i)) & 1;
                type = val;
                bitsForType = 10;
                System.out.println("Type (8-bit): " + type + " (0x" + Integer.toHexString(type) + ")");
            } else if (opcode == 2) {
                type = 0;
                bitsForType = 2;
                System.out.println("Type = 0");
            } else {
                type = 256;
                bitsForType = 2;
                System.out.println("Type = 256");
            }

            System.out.println("Type name: " + getTypeName(type));
            System.out.println("Bits consumed so far: " + (16 + bitsForType) + " = " + ((16 + bitsForType) / 8) + " bytes + " + ((16 + bitsForType) % 8 + " bits");

            // 现在尝试解析 entity 读取 bitsize (RL = 32-bit = 4 bytes)
            // But bits are not byte-aligned after BS type!
            int bitPos = 16 + bitsForType;
            int byteIdx = off + (bitPos / 8);
            int bitOffset = bitPos % 8;

            // 尝试读 RL bitsize (32 bits)
            // 从当前 bitOffset 位置开始读 32 bits
            int bitsize = 0;
            if (byteIdx + 4 < data.length) {
                // Simple: read next 4 bytes starting at bitPos
                // Extract 32 bits: byte-aligned after bitPos
                // 现在 bit 位置 bit
                int startByte = off + 2;
                int bit_in_byte = (bitPos - 16) - bitsForType;

                System.out.println("After type: at byte " + (byteIdx - off) + ", bit " + bitOffset + " of object byte " + (byteIdx - off));

                // 读 32-bit RL - 但这可能不是 RL,因为 bitsize
                // 让我们直接看接下来的 8 字节
                System.out.print("Next 16 bytes (after type) in hex: ");
                for (int i = 0; i < 16; i++) {
                    if (byteIdx + i < data.length) {
                        System.out.printf("%02x ", data[byteIdx + i] & 0xFF);
                    }
                }
                System.out.println();
                System.out.print("ASCII: ");
                for (int i = 0; i < 16; i++) {
                    if (byteIdx + i < data.length) {
                        int c = data[byteIdx + i] & 0xFF;
                        if (c >= 32 && c < 127) System.out.print((char)c);
                        else System.out.print(".");
                    }
                }
                System.out.println();
            }
        }

        // 额外: 查找 "*Model_Space" 附近的完整对象数据
        System.out.println("\n\n=== 查找包含 *Model_Space @ 0x5a82 之前的对象 ===");
        // 回溯查找 MS 标记
        for (int back = 0; back < 200; back++) {
            int off = 0x5a82 - back;
            if (off < 0) break;
            int ms = (data[off] & 0xFF) | ((data[off+1] & 0xFF) << 8);
            if (ms > 20 && ms < 200) {
                // 这可能是一个对象开始
                byte b2 = data[off + 2];
                int op = (b2 >> 6) & 3;
                if (op == 1) { // 8-bit type
                    int val = 0;
                    for (int i = 2; i < 8; i++) val = (val << 1) | ((b2 >> (7 - i)) & 1;
                    int b3 = data[off + 3] & 0xFF;
                    for (int i = 0; i < 2; i++) val = (val << 1) | ((b3 >> (7 - i)) & 1;
                    if (val == 0x30) {
                        System.out.println("Found BLOCK_HEADER @ 0x" + Integer.toHexString(off) + " MS=" + ms + " (back=" + back);
                    }
                }
            }
        }

        System.out.println("\n\n=== 查找 block name 在对象数据中的位置 ===");
        for (int i = 0x5a60; i < 0x5ae0; i++) {
            int c = data[i] & 0xFF;
            if (c >= 32 && c < 127) System.out.print((char)c);
            else System.out.print(".");
        }
        System.out.println();
    }

    static String getTypeName(int type) {
        switch (type) {
            case 0x01: return "TEXT";
            case 0x07: return "INSERT";
            case 0x0F: return "CIRCLE";
            case 0x1F: return "LWPOLYLINE";
            case 0x30: return "BLOCK_HEADER";
            case 0x43: return "LAYER";
            default: return "type=0x" + Integer.toHexString(type);
        }
    }
}
