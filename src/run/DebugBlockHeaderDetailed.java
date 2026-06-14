package run;

import io.dwg.core.io.ByteBufferBitInput;
import io.dwg.core.io.BitStreamReader;
import io.dwg.core.version.DwgVersion;

import java.nio.ByteBuffer;
import java.nio.file.Paths;

/**
 * 详细调试 BLOCK_HEADER 对象
 * 逐步跟踪每个字段的 bit 位置和值
 */
public class DebugBlockHeaderDetailed {

    public static void main(String[] args) throws Exception {
        String filename = "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg";
        byte[] data = java.nio.file.Files.readAllBytes(Paths.get(filename));

        // 测试已知的 BLOCK_HEADER offset (从之前分析可知 0x52b9 可能是一个系统块)
        // 先扫描整个 handles section，找出所有 type = 0x30 的对象
        System.out.println("=== 扫描 type=0x30 (BLOCK_HEADER) 的对象 ===");

        java.util.List<long[]> candidates = new java.util.ArrayList<>();

        ByteBufferBitInput bbuf0 = new ByteBufferBitInput(ByteBuffer.wrap(data));
        bbuf0.seek((long) 0x11943 * 8);
        BitStreamReader r0 = new BitStreamReader(bbuf0, DwgVersion.R2000);

        int totalRead = 0;
        int maxBytes = 4000;

        while (totalRead < maxBytes) {
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

                    // 在这个 offset 处检查 type
                    if (lastOffset > 0 && lastOffset < data.length - 4) {
                        try {
                            ByteBufferBitInput testBuf = new ByteBufferBitInput(ByteBuffer.wrap(data));
                            testBuf.seek(lastOffset * 8);
                            BitStreamReader tr = new BitStreamReader(testBuf, DwgVersion.R2000);
                            int objSz = tr.readModularShort();
                            if (objSz > 0 && objSz < 0x4000) {
                                int tc = tr.readBitShort();
                                if (tc == 0x30 || tc == 0x07) {
                                    candidates.add(new long[]{lastHandle, lastOffset, tc, objSz});
                                }
                            }
                        } catch (Exception ex) { /* ignore */ }
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

        System.out.println("找到候选对象: " + candidates.size() + " 个");
        for (long[] c : candidates) {
            String type = (c[2] == 0x30) ? "BLOCK_HEADER" : "INSERT";
            System.out.printf("  handle=0x%x, offset=0x%x, type=0x%x (%s), size=%d%n",
                c[0], c[1], c[2], type, c[3]);
        }

        // 详细调试前 5 个 BLOCK_HEADER
        int count = 0;
        for (long[] c : candidates) {
            if (c[2] != 0x30) continue;
            if (count++ >= 3) break;
            System.out.println();
            debugBlockHeader(data, (int) c[1], c[0]);
        }

        // 详细调试前 3 个 INSERT
        count = 0;
        for (long[] c : candidates) {
            if (c[2] != 0x07) continue;
            if (count++ >= 2) break;
            System.out.println();
            debugInsert(data, (int) c[1], c[0]);
        }
    }

    private static void debugBlockHeader(byte[] data, int offset, long handle) {
        System.out.println("============================================================");
        System.out.println("  详细调试 BLOCK_HEADER @ 0x" + Integer.toHexString(offset) +
                          " (handle=0x" + Long.toHexString(handle) + ")");
        System.out.println("============================================================");

        // 打印前 80 字节的 hex + ASCII
        System.out.println("  原始字节:");
        StringBuilder hexLine = new StringBuilder();
        StringBuilder asciiLine = new StringBuilder();
        for (int i = 0; i < 80 && offset + i < data.length; i++) {
            int b = data[offset + i] & 0xFF;
            hexLine.append(String.format("%02X ", b));
            char c = (b >= 32 && b < 127) ? (char)b : '.';
            asciiLine.append(c);
            if ((i + 1) % 16 == 0) {
                System.out.printf("    +%03X: %s %s%n", i - 15, hexLine.toString(), asciiLine.toString());
                hexLine.setLength(0);
                asciiLine.setLength(0);
            }
        }

        // 逐步解析
        ByteBufferBitInput bbuf = new ByteBufferBitInput(ByteBuffer.wrap(data));
        bbuf.seek((long) offset * 8);
        BitStreamReader r = new BitStreamReader(bbuf, DwgVersion.R2000);

        long startPos = bbuf.position();
        System.out.println();
        System.out.println("  起始 bit 位置: " + startPos);

        try {
            // 1. objSize (MS)
            int objSize = r.readModularShort();
            System.out.println("  [1] objSize = " + objSize + " (bits " + startPos + "-" + bbuf.position() + ")");

            // 2. typeCode (BS)
            int typeCode = r.readBitShort();
            System.out.println("  [2] typeCode = 0x" + Integer.toHexString(typeCode) + " = " + typeCode +
                              " (bits used so far: " + (bbuf.position() - startPos) + ")");

            // 3. bitsize (RL = 4 raw bytes)
            long bitsize = r.readBitLongLong();
            System.out.println("  [3] bitsize (RL) = " + bitsize);

            // 4. object handle (H)
            long objHandle = r.readHandle();
            System.out.println("  [4] object_handle (H) = 0x" + Long.toHexString(objHandle));

            // 5. EED loop
            int eedCount = 0;
            while (true) {
                int eedSize = r.readBitShort();
                if (eedSize <= 0 || eedSize > 0x4000) {
                    System.out.println("  [5] EED size = " + eedSize + " (终止)" +
                                      " (已读取 " + eedCount + " 个 EED 项)");
                    break;
                }
                eedCount++;
                long eedAppidH = r.readHandle();
                for (int i = 0; i < eedSize; i++) r.getInput().readBits(8);
                System.out.println("  [5.EED" + eedCount + "] size=" + eedSize +
                                  ", appid_handle=0x" + Long.toHexString(eedAppidH));
            }

            // === Common Entity Data ===
            System.out.println();
            System.out.println("  --- Common Entity Data ---");

            boolean previewExists = r.getInput().readBit();
            System.out.println("  [C1] preview_exists = " + previewExists);

            if (previewExists) {
                long ps = r.readBitLongLong();
                System.out.println("       preview_size = " + ps);
                if (ps > 0 && ps < 0x100000) {
                    r.seek(r.position() + ps * 8L);
                }
            }

            r.getInput().readBits(2);  // entmode
            System.out.println("  [C2] entmode (consumed)");

            int numReact = r.readBitLong();
            System.out.println("  [C3] num_reactors = " + numReact);

            r.getInput().readBit();  // nolinks
            System.out.println("  [C4] nolinks (consumed)");

            int color = r.readBitShort();
            System.out.println("  [C5] color = " + color);

            double ltypeScale = r.readBitDouble();
            System.out.printf("  [C6] ltype_scale = %.6f%n", ltypeScale);

            r.getInput().readBits(2);  // ltype_flags
            r.getInput().readBits(2);  // plotstyle_flags
            System.out.println("  [C7] ltype_flags + plotstyle_flags (consumed)");

            int invisible = r.readBitShort();
            System.out.println("  [C8] invisible = " + invisible);

            r.getInput().readBits(8);  // linewt
            System.out.println("  [C9] linewt (consumed)");

            // === Block specific ===
            System.out.println();
            System.out.println("  --- Block Header Specific ---");

            long beforeText = bbuf.position();
            System.out.println("  当前 bit 位置: " + beforeText + " (byte " + (beforeText/8) + ")");

            // blockName (TU/TV) — 对于 R2000，应是 ASCII text
            String blockName = r.readText();  // 直接用 readText() 而不是 readVariableText()
            System.out.println("  [B1] blockName = '" + blockName + "' (length=" + blockName.length() + ")");

            int flags = r.readBitShort();
            System.out.println("  [B2] flags = " + flags + " (0x" + Integer.toHexString(flags) + ")");

            double[] bp = r.read3RawDouble();
            System.out.printf("  [B3] basePoint = (%.6f, %.6f, %.6f)%n", bp[0], bp[1], bp[2]);

            try {
                String xrefPath = r.readText();
                System.out.println("  [B4] xrefPath = '" + xrefPath + "'");
            } catch (Exception e) {
                System.out.println("  [B4] xrefPath 解析失败: " + e.getMessage());
            }

        } catch (Exception e) {
            System.out.println("  !!! 解析失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void debugInsert(byte[] data, int offset, long handle) {
        System.out.println("============================================================");
        System.out.println("  详细调试 INSERT @ 0x" + Integer.toHexString(offset) +
                          " (handle=0x" + Long.toHexString(handle) + ")");
        System.out.println("============================================================");

        // 打印前 80 字节
        StringBuilder hexLine = new StringBuilder();
        StringBuilder asciiLine = new StringBuilder();
        for (int i = 0; i < 80 && offset + i < data.length; i++) {
            int b = data[offset + i] & 0xFF;
            hexLine.append(String.format("%02X ", b));
            char c = (b >= 32 && b < 127) ? (char)b : '.';
            asciiLine.append(c);
            if ((i + 1) % 16 == 0) {
                System.out.printf("    +%03X: %s %s%n", i - 15, hexLine.toString(), asciiLine.toString());
                hexLine.setLength(0);
                asciiLine.setLength(0);
            }
        }

        ByteBufferBitInput bbuf = new ByteBufferBitInput(ByteBuffer.wrap(data));
        bbuf.seek((long) offset * 8);
        BitStreamReader r = new BitStreamReader(bbuf, DwgVersion.R2000);

        long startPos = bbuf.position();

        try {
            int objSize = r.readModularShort();
            System.out.println("  [1] objSize = " + objSize);

            int typeCode = r.readBitShort();
            System.out.println("  [2] typeCode = 0x" + Integer.toHexString(typeCode));

            r.readBitLongLong();  // bitsize
            r.readHandle();  // obj handle

            // EED loop
            while (true) {
                int eedSize = r.readBitShort();
                if (eedSize <= 0 || eedSize > 0x4000) break;
                r.readHandle();
                for (int i = 0; i < eedSize; i++) r.getInput().readBits(8);
            }

            // common
            boolean previewExists = r.getInput().readBit();
            if (previewExists) {
                long ps = r.readBitLongLong();
                if (ps > 0 && ps < 0x100000) r.seek(r.position() + ps * 8L);
            }
            r.getInput().readBits(2);
            r.readBitLong();  // num_reactors
            r.getInput().readBit();  // nolinks
            r.readBitShort();  // color
            r.readBitDouble(); // ltype_scale
            r.getInput().readBits(2);
            r.getInput().readBits(2);
            r.readBitShort();  // invisible
            r.getInput().readBits(8);  // linewt

            // INSERT specific
            long blockHandle = r.readHandle();
            System.out.println("  块引用句柄: 0x" + Long.toHexString(blockHandle));

            double[] ip = r.read3BitDouble();
            System.out.printf("  插入点: (%.4f, %.4f, %.4f)%n", ip[0], ip[1], ip[2]);

            double[] sc = r.read3BitDouble();
            System.out.printf("  缩放: (%.4f, %.4f, %.4f)%n", sc[0], sc[1], sc[2]);

            double rot = r.readBitDouble();
            System.out.printf("  旋转: %.6f rad (%.4f 度)%n", rot, rot * 180.0 / Math.PI);

            r.getInput().readBit();  // attr follows
            r.readBitShort();  // attr count

        } catch (Exception e) {
            System.out.println("  !!! 解析失败: " + e.getMessage());
        }
    }
}
