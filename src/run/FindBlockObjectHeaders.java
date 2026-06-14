package run;

import io.dwg.core.io.ByteBufferBitInput;
import io.dwg.core.io.BitStreamReader;
import io.dwg.core.version.DwgVersion;

import java.nio.ByteBuffer;
import java.nio.file.Paths;
import java.util.*;

/**
 * 在每个块名字符串之前的区域中查找对象头（MS + BS）
 */
public class FindBlockObjectHeaders {

    public static void main(String[] args) throws Exception {
        String filename = "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg";
        byte[] data = java.nio.file.Files.readAllBytes(Paths.get(filename));

        // 块名字符串位置
        int[][] positions = {
            {0x548f, 12},  // *Paper_Space
            {0x5a82, 12},  // *Model_Space
            {0x54b8, 9},   // SW_NOTE_0
            {0x5668, 21},  // SW_CENTERMARKSYMBOL_0
            {0x5968, 22},  // SW_CHAMFER_DIMENSION_0
            {0x5814, 21},  // SW_CENTERMARKSYMBOL_1 (从之前的搜索)
            {0x5511, 13},  // SW_SFSYMBOL_0
        };

        String[] names = {
            "*Paper_Space", "*Model_Space", "SW_NOTE_0",
            "SW_CENTERMARKSYMBOL_0", "SW_CHAMFER_DIMENSION_0",
            "SW_CENTERMARKSYMBOL_1", "SW_SFSYMBOL_0"
        };

        for (int i = 0; i < names.length; i++) {
            System.out.println("=== " + names[i] + " @ 0x" +
                Integer.toHexString(positions[i][0]) + " (len=" + positions[i][1] + ") ===");

            int namePos = positions[i][0];
            int nameLen = positions[i][1];

            // 打印字符串前 80 字节到后 30 字节
            int start = Math.max(0, namePos - 80);
            int end = Math.min(data.length, namePos + nameLen + 30);

            System.out.println("\n  原始字节:");
            StringBuilder hex = new StringBuilder();
            StringBuilder ascii = new StringBuilder();
            for (int j = start; j < end; j++) {
                int b = data[j] & 0xFF;
                hex.append(String.format("%02X ", b));
                ascii.append((b >= 32 && b < 127) ? (char)b : '.');
                if ((j - start + 1) % 16 == 0) {
                    System.out.printf("    0x%04X: %s  %s%n", j - 15, hex.toString(), ascii.toString());
                    hex.setLength(0);
                    ascii.setLength(0);
                }
            }
            if (hex.length() > 0) {
                while (hex.length() < 16*3) hex.append(" ");
                System.out.printf("    0x%04X: %s  %s%n", start + (end - start) / 16 * 16, hex.toString(), ascii.toString());
            }

            // 在字符串前的区域中搜索对象头 (MS + BS)
            // MS: LE uint16 with bit15 = 0 (continuation bit)
            // BS: 2 bits opcode + value
            // 尝试在 namePos - 100 到 namePos - 2 范围内
            System.out.println("\n  搜索可能的对象头位置:");
            for (int searchPos = Math.max(0, namePos - 150); searchPos < namePos - 2; searchPos++) {
                try {
                    ByteBufferBitInput bbuf = new ByteBufferBitInput(ByteBuffer.wrap(data));
                    bbuf.seek((long) searchPos * 8);
                    BitStreamReader r = new BitStreamReader(bbuf, DwgVersion.R2000);

                    int objSize = r.readModularShort();
                    // objSize 应该 > 0 且 <= 500
                    if (objSize <= 0 || objSize > 500) continue;

                    long afterMS = bbuf.position();
                    int typeCode = r.readBitShort();

                    // 找出有趣的 typeCode 值
                    // BLOCK_HEADER typeCode 可能不是 0x30...让我找能够读到合理值的
                    if (typeCode >= 0 && typeCode < 200) {
                        // 检查从这个位置到块名是否有合理的字节数
                        int bytesToName = namePos - searchPos;
                        // 对象头+字段通常是: 2(MS) + 2(BS) + common + specific
                        if (bytesToName >= 4 && bytesToName <= 100) {
                            System.out.printf("    offset=0x%04X: objSize=%d, typeCode=0x%02X(%d), bytesToName=%d%n",
                                searchPos, objSize, typeCode, typeCode, bytesToName);
                        }
                    }
                } catch (Exception e) {}
            }

            System.out.println();

            // 从字符串前 50 字节开始，逐字节尝试解析对象
            // 尝试在字符串前找到: [object header] ... [handle value] ... [1-byte length][name]
            // 关键观察: 字符串前的 4 字节模式:
            // *Paper_Space: 00 48 E9 0C [name starts]
            // *Model_Space: 00 47 E9 0C [name starts]
            // SW_NOTE_0:    00 73 A9 09 [name starts]
            // SW_CENTER...: 80 71 69 15 [name starts]
            // SW_CHAMFER...:80 B8 A9 16 [name starts]

            // 字节 -1 = 字符串长度 ✓
            // 字节 -2/-3 = 某种 handle/偏移？
            // 字节 -4 = 标志？

            // 让我检查 -2 和 -3 字节作为 LE uint16:
            int b_4 = data[namePos - 4] & 0xFF;
            int b_3 = data[namePos - 3] & 0xFF;
            int b_2 = data[namePos - 2] & 0xFF;
            int b_1 = data[namePos - 1] & 0xFF;

            System.out.println("  块名字段前 4 字节分析:");
            System.out.printf("    -4: 0x%02X, -3: 0x%02X, -2: 0x%02X, -1: 0x%02X (len=%d)%n",
                b_4, b_3, b_2, b_1, nameLen);

            // -2,-3 作为 LE uint16:
            int handle16 = (b_3 << 8) | b_2;  // 注意字节序
            int handleLE = b_2 | (b_3 << 8);
            System.out.println("    bytes -2,-3 as BE uint16: 0x" + Integer.toHexString(handle16) + " = " + handle16);
            System.out.println("    bytes -2,-3 as LE uint16: 0x" + Integer.toHexString(handleLE) + " = " + handleLE);
            System.out.println("    bytes -4,-3,-2,-1 as LE uint32: 0x" +
                Integer.toHexString(b_1 | (b_2<<8) | (b_3<<16) | (b_4<<24)));

            System.out.println();
        }

        // 在文件中寻找 BLOCK_HEADER 对象
        // 另一个方法：使用 Handles section 找到所有对象，然后在每个位置尝试用新理解的格式解析
        System.out.println("\n=== 从 Handles section 读取对象并尝试解析块名 ===\n");

        ByteBufferBitInput bbuf0 = new ByteBufferBitInput(ByteBuffer.wrap(data));
        bbuf0.seek((long) 0x11943 * 8);
        BitStreamReader r0 = new BitStreamReader(bbuf0, DwgVersion.R2000);

        List<long[]> handles = new ArrayList<>();
        try {
            while (true) {
                int pageSize = r0.readBigEndianShort();
                if (pageSize <= 2 || pageSize > 2040) break;

                long lastHandle = 0;
                long lastOffset = 0;
                int pairsSize = pageSize - 2;
                int pairsRead = 0;

                while (pairsRead < pairsSize) {
                    long before = r0.position();
                    int hDelta = r0.readUnsignedModularChar();
                    if (hDelta == 0) break;
                    int oDelta = r0.readModularChar();
                    long after = r0.position();
                    int bytes = (int) ((after - before) / 8);
                    pairsRead += bytes;

                    lastHandle += hDelta;
                    lastOffset += oDelta;
                    handles.add(new long[]{ lastHandle, lastOffset });
                }

                long curPos = r0.position();
                if ((curPos % 8) != 0) {
                    int padding = 8 - (int) (curPos % 8);
                    r0.getInput().readBits(padding);
                }
                r0.readBigEndianShort(); // CRC
            }
        } catch (Exception e) {}

        System.out.println("Handles: " + handles.size() + " entries");

        // 对于每个对象，尝试用新格式解析：
        // 先读 MS(objSize), 然后 BS(typeCode), 然后跳过 objSize 字节找字符串
        // 实际上我们想找：在对象体中，某处有 "1-byte length + ASCII name" 的模式
        System.out.println("\n在对象中寻找 '1-byte length + ASCII text' 模式:\n");

        int count = 0;
        for (long[] h : handles) {
            long handle = h[0];
            long offset = h[1];
            if (offset <= 0 || offset > data.length - 50) continue;

            try {
                // 读对象头
                ByteBufferBitInput bbuf = new ByteBufferBitInput(ByteBuffer.wrap(data));
                bbuf.seek((long) offset * 8);
                BitStreamReader r = new BitStreamReader(bbuf, DwgVersion.R2000);

                int objSize = r.readModularShort();
                if (objSize <= 0 || objSize > 500) continue;
                int typeCode = r.readBitShort();

                // 对象体从 (offset*8) + position after MS+BS 开始
                // 但实际上对象体是字节对齐的...让我直接从 offset+4 起尝试简单的字节级搜索
                // 在对象的后半部分查找 "length-byte + ASCII" 模式
                int searchStart = (int) offset + 4;
                int searchEnd = (int) offset + objSize;

                for (int pos = searchStart; pos < searchEnd; pos++) {
                    int lenByte = data[pos] & 0xFF;
                    if (lenByte >= 3 && lenByte <= 50) {
                        // 检查接下来 lenByte 字节是否全是 ASCII
                        boolean valid = true;
                        StringBuilder sb = new StringBuilder();
                        for (int k = 0; k < lenByte && pos + 1 + k < data.length; k++) {
                            int c = data[pos + 1 + k] & 0xFF;
                            if (c < 32 || c > 126) { valid = false; break; }
                            sb.append((char)c);
                        }
                        if (valid && sb.length() == lenByte) {
                            // 找到潜在的块名！
                            String name = sb.toString();
                            if (name.startsWith("SW_") || name.startsWith("*") ||
                                name.startsWith("AcDb") || name.contains("DIMENSION")) {
                                System.out.printf("  handle=0x%x offset=0x%x objSize=%d typeCode=0x%02X | namePos=+%d name='%s'%n",
                                    handle, (int)offset, objSize, typeCode, pos - (int)offset, name);
                                count++;
                                if (count > 30) break;
                            }
                        }
                    }
                }
            } catch (Exception e) {}
        }
    }
}
