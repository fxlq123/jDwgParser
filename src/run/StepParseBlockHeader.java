package run;

import io.dwg.core.io.ByteBufferBitInput;
import io.dwg.core.io.BitStreamReader;
import io.dwg.core.version.DwgVersion;

import java.nio.ByteBuffer;
import java.nio.file.Paths;

/**
 * 使用 BitStreamReader 逐步解析 BLOCK_HEADER
 * 逐字段读取并打印每个结果
 */
public class StepParseBlockHeader {

    public static void main(String[] args) throws Exception {
        String filename = "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg";
        byte[] data = java.nio.file.Files.readAllBytes(Paths.get(filename));

        // 解析几个已知的 BLOCK_HEADER
        int[] offsets = { 0x111c1, 0x6f0c, 0x705b };
        // 还解析系统块 handle 0x1
        int[] systemOffsets = { 0x52b9 };

        for (int offset : offsets) {
            stepParse(data, offset, "BLOCK_HEADER @ 0x" + Integer.toHexString(offset));
            System.out.println();
        }

        for (int offset : systemOffsets) {
            stepParse(data, offset, "System BLOCK_HEADER @ 0x" + Integer.toHexString(offset));
            System.out.println();
        }
    }

    private static void stepParse(byte[] data, int offset, String label) {
        System.out.println("==================================================");
        System.out.println(label);
        System.out.println("==================================================");

        ByteBufferBitInput bbuf = new ByteBufferBitInput(ByteBuffer.wrap(data));
        bbuf.seek((long) offset * 8);
        BitStreamReader r = new BitStreamReader(bbuf, DwgVersion.R2000);

        long startBit = bbuf.position();
        System.out.println("  起始 bit 位置: " + startBit + " (= byte " + (startBit / 8) + ")");

        // 1. objSize (MS)
        int objSize = r.readModularShort();
        long msEndBit = bbuf.position();
        System.out.println("  [1] objSize (MS) = " + objSize + " (0x" + Integer.toHexString(objSize) + ")");
        System.out.println("     消耗 bits: " + (msEndBit - startBit));

        // 2. typeCode (BS)
        int typeCode = r.readBitShort();
        long bsEndBit = bbuf.position();
        System.out.println("  [2] typeCode (BS) = " + typeCode + " (0x" + Integer.toHexString(typeCode) + ")");
        System.out.println("     消耗 bits: " + (bsEndBit - msEndBit));
        System.out.println("     当前 byte 位置: " + (bsEndBit / 8) + " (bit " + (bsEndBit % 8) + ")");

        System.out.println();
        System.out.println("  --- 尝试解析 common data ---");

        // 3. 尝试 numReactors (BL)
        try {
            int numReactors = r.readBitLong();
            System.out.println("  [3] numReactors (BL) = " + numReactors);
            long afterBL = bbuf.position();
            System.out.println("     当前位置: byte " + (afterBL / 8) + " bit " + (afterBL % 8));

            // 4. 如果是 entity, 读 entity flags (2+2 bits)
            if (typeCode == 0x05 || typeCode == 0x07) { // BLOCK_HEADER 或 INSERT
                int entityMode = bbuf.readBits(2);
                int lineTypeFlags = bbuf.readBits(2);
                System.out.println("  [4] entityMode = " + entityMode + ", lineTypeFlags = " + lineTypeFlags);
            }

            // 5. ownerHandle (H)
            long ownerHandle = r.readHandle();
            System.out.println("  [5] ownerHandle (H) = 0x" + Long.toHexString(ownerHandle));

            // 6. reactor handles
            for (int i = 0; i < Math.min(numReactors, 5); i++) {
                long h = r.readHandle();
                System.out.println("  [6." + i + "] reactorHandle = 0x" + Long.toHexString(h));
            }

            // 7. xdict handle (如果有 xdict 位)
            // 跳过...

            // 现在尝试读 blockName (T)
            System.out.println();
            System.out.println("  --- 尝试读取 block specific data ---");

            // 方法 A: 直接 readText
            try {
                String nameA = r.readText();
                System.out.println("  [A] blockName (readText) = '" + nameA + "' (len=" + nameA.length() + ")");
                System.out.println("     当前位置: byte " + (bbuf.position() / 8));

                // 尝试读 flags
                int flags = r.readBitShort();
                System.out.println("  [A+] flags (BS) = " + flags);

                // 尝试读 base point
                double bx = r.readBitDouble();
                double by = r.readBitDouble();
                double bz = r.readBitDouble();
                System.out.println("  [A+] basePoint = (" + bx + ", " + by + ", " + bz + ")");
            } catch (Exception ex) {
                System.out.println("  [A] readText 失败: " + ex.getMessage());
            }

        } catch (Exception e) {
            System.out.println("  解析 common data 失败: " + e.getMessage());
            e.printStackTrace();
        }

        // 打印剩余字节内容
        System.out.println();
        System.out.println("  --- 对象体字节 (从 byte " + (bsEndBit/8) + " 开始) ---");
        int objStartByte = (int)(bsEndBit / 8) + 2;
        for (int i = 0; i < Math.min(32, data.length - objStartByte); i++) {
            int b = data[offset + 2 + i] & 0xFF;
            if (i % 16 == 0) System.out.printf("    +%03X: ", i);
            System.out.printf("%02X ", b);
            if (i % 16 == 15) System.out.println();
        }
        System.out.println();

        // 从对象体开始读取实际的字节内容（ASCII解释）
        System.out.println("  对象体 ASCII 解释:");
        StringBuilder sb = new StringBuilder("    ");
        int bodyStart = offset + 2;  // 跳过 objSize
        int bodyEnd = bodyStart + objSize;
        for (int i = bodyStart; i < bodyEnd && i < data.length; i++) {
            int b = data[i] & 0xFF;
            char c = (b >= 32 && b < 127) ? (char) b : '.';
            sb.append(c);
            if ((i - bodyStart + 1) % 16 == 0) {
                System.out.println(sb.toString());
                sb.setLength(4);
            }
        }
        System.out.println(sb.toString());
    }
}
