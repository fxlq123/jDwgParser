package run;

import io.dwg.core.io.ByteBufferBitInput;
import io.dwg.core.io.BitStreamReader;
import io.dwg.core.version.DwgVersion;

import java.nio.ByteBuffer;
import java.nio.file.Paths;
import java.util.*;

/**
 * 在 BLOCK_HEADER 对象数据中，从 typeCode 之后的不同位置尝试读取 blockName
 * 找出给出有意义的块名称的字段位置
 */
public class SearchBlockNameInObject {

    public static void main(String[] args) throws Exception {
        String filename = "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg";
        byte[] data = java.nio.file.Files.readAllBytes(Paths.get(filename));

        // 扫描找出所有 type=0x30 (BLOCK_HEADER) 和 type=0x07 (INSERT) 的对象
        System.out.println("=== 扫描对象表 ===\n");
        Map<Long, Integer> handleToOffset = new HashMap<>();
        List<long[]> blockHeaders = new ArrayList<>();
        List<long[]> inserts = new ArrayList<>();

        ByteBufferBitInput bbuf0 = new ByteBufferBitInput(ByteBuffer.wrap(data));
        bbuf0.seek((long) 0x11943 * 8);
        BitStreamReader r0 = new BitStreamReader(bbuf0, DwgVersion.R2000);

        int totalRead = 0;
        while (totalRead < 4000) {
            try {
                int pageSize = r0.readBigEndianShort();
                totalRead += 2;
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
                    totalRead += bytes;

                    lastHandle += hDelta;
                    lastOffset += oDelta;

                    if (lastOffset > 0 && lastOffset < data.length - 4) {
                        try {
                            ByteBufferBitInput testBuf = new ByteBufferBitInput(ByteBuffer.wrap(data));
                            testBuf.seek(lastOffset * 8);
                            BitStreamReader tr = new BitStreamReader(testBuf, DwgVersion.R2000);
                            int objSz = tr.readModularShort();
                            if (objSz > 0 && objSz < 0x4000) {
                                int tc = tr.readBitShort();
                                if (tc == 0x30) {
                                    blockHeaders.add(new long[]{lastHandle, lastOffset, tc, objSz});
                                } else if (tc == 0x07) {
                                    inserts.add(new long[]{lastHandle, lastOffset, tc, objSz});
                                }
                            }
                        } catch (Exception ex) {}
                    }
                }

                long curPos = r0.position();
                if ((curPos % 8) != 0) {
                    int padding = 8 - (int) (curPos % 8);
                    r0.getInput().readBits(padding);
                }
                r0.readBigEndianShort();
                totalRead += 2;
            } catch (Exception e) {
                break;
            }
        }

        System.out.println("找到 " + blockHeaders.size() + " 个 BLOCK_HEADER, " + inserts.size() + " 个 INSERT");
        for (long[] bh : blockHeaders) {
            System.out.printf("  BLOCK_HEADER: handle=0x%x, offset=0x%x, size=%d%n", bh[0], bh[1], bh[3]);
        }

        // 对于每个 BLOCK_HEADER，尝试在对象体中的不同位置读取 blockName
        System.out.println("\n=== 搜索块名称位置 ===\n");

        for (long[] bh : blockHeaders) {
            int offset = (int) bh[1];
            long handle = bh[0];
            int objSize = (int) bh[3];

            System.out.println("--- 对象 @ 0x" + Integer.toHexString(offset) +
                              " (handle=0x" + Long.toHexString(handle) + ", size=" + objSize + ") ---");

            // 打印对象数据（从 offset 开始）
            StringBuilder hexLine = new StringBuilder();
            StringBuilder asciiLine = new StringBuilder();
            for (int i = 0; i < Math.min(objSize + 8, data.length - offset); i++) {
                int b = data[offset + i] & 0xFF;
                hexLine.append(String.format("%02X ", b));
                char c = (b >= 32 && b < 127) ? (char)b : '.';
                asciiLine.append(c);
                if ((i + 1) % 16 == 0) {
                    System.out.printf("    +%03X: %s %s%n", i - 15, hexLine, asciiLine);
                    hexLine.setLength(0);
                    asciiLine.setLength(0);
                }
            }
            if (hexLine.length() > 0) {
                while (hexLine.length() < 16 * 3) hexLine.append(" ");
                System.out.printf("    +%03X: %s %s%n", (objSize + 8) / 16 * 16, hexLine, asciiLine);
            }

            // 方法1: 在对象体中，从不同 bit 位置尝试 readText()
            // readText = BS(length) + length bytes of ASCII
            // objSize 和 typeCode 之后的字节位置大约是 byte 4 (即 offset + 4)
            // 但实际上 BS 可能不是整字节对齐
            
            // 先计算 objSize + typeCode 消耗的 bits
            // objSize (MS) = 16 bits (因为 continuation bit = 0)
            // typeCode (BS, opcode=01) = 2 + 8 = 10 bits
            // 所以从 offset*8 + 26 bits 开始是对象体
            
            // 让我验证: 在每个 bit 位置从 offset*8 开始尝试读取 objSize + typeCode
            ByteBufferBitInput verifyBuf = new ByteBufferBitInput(ByteBuffer.wrap(data));
            verifyBuf.seek((long) offset * 8);
            BitStreamReader vr = new BitStreamReader(verifyBuf, DwgVersion.R2000);

            int vObjSize = vr.readModularShort();
            int vTypeCode = vr.readBitShort();
            long bodyStartBit = verifyBuf.position();
            System.out.println("  验证: objSize=" + vObjSize + ", typeCode=0x" + Integer.toHexString(vTypeCode));
            System.out.println("  对象体起始 bit: " + bodyStartBit + " (byte " + (bodyStartBit/8) +
                              ", bit offset: " + (bodyStartBit % 8) + ")");

            // 现在从 bodyStartBit 开始尝试不同的读取方式
            // 尝试: 假设对象体开头是 "文本长度(BS) + 文本"
            // 在不同 bit 位置尝试读取文本，寻找有意义的名称
            
            int bytePos = (int)(bodyStartBit / 8);
            int bitOffset = (int)(bodyStartBit % 8);

            System.out.println("\n  --- 在对象体不同 bit 偏移处尝试读取文本 ---");

            // 尝试 bitOffset = 0-7
            for (int bitSkip = 0; bitSkip < 8; bitSkip++) {
                try {
                    ByteBufferBitInput tBuf = new ByteBufferBitInput(ByteBuffer.wrap(data));
                    tBuf.seek(bodyStartBit + bitSkip);
                    BitStreamReader tr = new BitStreamReader(tBuf, DwgVersion.R2000);

                    // 尝试 BS(length) + ASCII bytes
                    int len = tr.readBitShort();
                    if (len >= 1 && len <= 64) {  // 合理的块名称长度
                        StringBuilder sb = new StringBuilder();
                        boolean printable = true;
                        for (int i = 0; i < len; i++) {
                            int b = tr.getInput().readBits(8) & 0xFF;
                            char c = (char) b;
                            if (b < 32 || b > 126) {
                                if (c == '\n' || c == '\r' || c == '\t') {
                                    // 允许部分控制字符，但通常块名不会有这些
                                    printable = false;
                                    break;
                                }
                                printable = false;
                                break;
                            }
                            sb.append(c);
                        }
                        if (printable && sb.length() >= 2) {
                            String name = sb.toString();
                            // 排除纯数字或纯符号的名称
                            boolean hasLetter = false;
                            for (int i = 0; i < name.length(); i++) {
                                char c = name.charAt(i);
                                if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '_' ||
                                    (c >= '0' && c <= '9')) {
                                    hasLetter = true;
                                    // 允许字母数字
                                } else {
                                    hasLetter = false;  // 有非字母数字字符
                                    // 继续检查（可能是块名中的其他字符）
                                }
                            }
                            if (hasLetter) {
                                System.out.printf("    bit+%d: len=%d, name='%s'%n", bitSkip, len, name);
                            }
                        }
                    }
                } catch (Exception e) {}
            }

            // 还尝试直接在对象体字节中查找 ASCII 字符串
            System.out.println("\n  --- 在对象体字节中查找 ASCII 字符串 ---");
            StringBuilder cur = new StringBuilder();
            int startByte = (int)(bodyStartBit / 8);
            int endByte = Math.min(startByte + objSize + 8, data.length);
            for (int i = startByte; i < endByte; i++) {
                int b = data[i] & 0xFF;
                if (b >= 32 && b <= 126) {
                    cur.append((char)b);
                } else {
                    if (cur.length() >= 3) {
                        System.out.printf("    byte+%d (0x%x): '%s' (len=%d)%n",
                            i - startByte - cur.length(), i - startByte - cur.length(), cur.toString(), cur.length());
                    }
                    cur.setLength(0);
                }
            }
            if (cur.length() >= 3) {
                System.out.printf("    byte+%d (0x%x): '%s' (len=%d)%n",
                    endByte - startByte - cur.length(), endByte - startByte - cur.length(), cur.toString(), cur.length());
            }
            System.out.println();
        }

        // 同样分析 INSERT 对象
        System.out.println("=== 分析 INSERT 对象 ===\n");
        for (int idx = 0; idx < Math.min(3, inserts.size()); idx++) {
            long[] ins = inserts.get(idx);
            int offset = (int) ins[1];
            long handle = ins[0];
            int objSize = (int) ins[3];

            System.out.println("--- INSERT @ 0x" + Integer.toHexString(offset) +
                              " (handle=0x" + Long.toHexString(handle) + ", size=" + objSize + ") ---");

            // 打印字节
            StringBuilder hexLine = new StringBuilder();
            StringBuilder asciiLine = new StringBuilder();
            for (int i = 0; i < Math.min(objSize + 8, data.length - offset); i++) {
                int b = data[offset + i] & 0xFF;
                hexLine.append(String.format("%02X ", b));
                char c = (b >= 32 && b < 127) ? (char)b : '.';
                asciiLine.append(c);
                if ((i + 1) % 16 == 0) {
                    System.out.printf("    +%03X: %s %s%n", i - 15, hexLine, asciiLine);
                    hexLine.setLength(0);
                    asciiLine.setLength(0);
                }
            }
            if (hexLine.length() > 0) {
                while (hexLine.length() < 16 * 3) hexLine.append(" ");
                System.out.printf("    +%03X: %s %s%n", 0, hexLine, asciiLine);
            }

            // 验证 objSize + typeCode
            ByteBufferBitInput vBuf = new ByteBufferBitInput(ByteBuffer.wrap(data));
            vBuf.seek((long) offset * 8);
            BitStreamReader vr = new BitStreamReader(vBuf, DwgVersion.R2000);
            int vObjSize = vr.readModularShort();
            int vTypeCode = vr.readBitShort();
            long bodyStartBit = vBuf.position();
            System.out.println("  objSize=" + vObjSize + ", typeCode=0x" + Integer.toHexString(vTypeCode));
            System.out.println("  bodyStartBit: " + bodyStartBit + " (byte " + (bodyStartBit/8) + ", bit " + (bodyStartBit%8) + ")");

            // 尝试读取 block header handle
            System.out.println("\n  尝试读取 block header handle:");
            for (int bitSkip = 0; bitSkip < 32; bitSkip++) {
                try {
                    ByteBufferBitInput hBuf = new ByteBufferBitInput(ByteBuffer.wrap(data));
                    hBuf.seek(bodyStartBit + bitSkip);
                    BitStreamReader hr = new BitStreamReader(hBuf, DwgVersion.R2000);
                    long bh = hr.readHandle();
                    if (bh > 0 && bh < 0x100000) {
                        System.out.printf("    bit+%d: block_handle=0x%x%n", bitSkip, bh);
                        // 再尝试读取插入点
                        try {
                            double[] ip = hr.read3BitDouble();
                            if (Double.isFinite(ip[0]) && Math.abs(ip[0]) < 1e6 &&
                                !(ip[0] == 0 && ip[1] == 0 && ip[2] == 0)) {
                                System.out.printf("       插入点: (%.4f, %.4f, %.4f)%n", ip[0], ip[1], ip[2]);
                            }
                        } catch (Exception e) {}
                    }
                } catch (Exception e) {}
            }

            // 也尝试直接读取字节中的 handle 格式
            // Handle: 1 byte code[4:7] + count[0:3], then count bytes
            System.out.println("\n  直接在字节中查找 handle 模式:");
            int bodyByteStart = (int)(bodyStartBit / 8);
            for (int byteSkip = 0; byteSkip < 20; byteSkip++) {
                try {
                    int hdrByte = data[bodyByteStart + byteSkip] & 0xFF;
                    int count = hdrByte & 0x0F;
                    if (count > 0 && count <= 6 && bodyByteStart + byteSkip + count < data.length) {
                        long h = 0;
                        for (int i = 0; i < count; i++) {
                            h = (h << 8) | (data[bodyByteStart + byteSkip + 1 + i] & 0xFF);
                        }
                        if (h > 0 && h < 0x100000) {
                            System.out.printf("    byte+%d: handle=0x%x, count=%d, code=0x%02x%n",
                                byteSkip, h, count, hdrByte >> 4);
                        }
                    }
                } catch (Exception e) {}
            }
            System.out.println();
        }
    }
}
