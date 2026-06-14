import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class DebugHandles3 {
    public static void main(String[] args) throws Exception {
        byte[] data = Files.readAllBytes(Paths.get(
            "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));

        // 先看 handles section 开头的 32 字节
        System.out.println("=== Handles section 前 64 字节 (0x11943) ===");
        for (int i = 0; i < 64; i++) {
            if (i % 16 == 0) System.out.printf("\n0x%04x: ", 0x11943 + i);
            System.out.printf("%02X ", data[0x11943 + i] & 0xFF);
        }
        System.out.println();

        // 先看第一个 page
        System.out.println("\n=== 手动解析第一个 page ===");
        int pos = 0x11943;
        int pageSize = ((data[pos] & 0xFF) << 8) | (data[pos+1] & 0xFF);
        System.out.println("Page 1 size (bytes 0,1): 0x" + Integer.toHexString(pageSize) + " = " + pageSize);

        // 显示 pairs 数据
        int pairsStart = pos + 2;
        int pairsEnd = pairsStart + pageSize - 2;  // -2 for CRC
        System.out.println("Pairs bytes (" + (pairsEnd - pairsStart) + " bytes):");
        for (int i = 0; i < Math.min(pairsEnd - pairsStart, 64); i++) {
            if (i % 16 == 0) System.out.printf("\n  [%4d] ", i);
            System.out.printf("%02X ", data[pairsStart + i] & 0xFF);
        }
        System.out.println();

        // 用 3 种方式解析
        System.out.println("\n=== 方式 1: handleDelta 无符号, offsetDelta 无符号, 两者累积 ===");
        testParseV1(data, 0x11943, 8192);

        System.out.println("\n=== 方式 2: handleDelta 无符号, offset 为偏移序列的绝对位置 (不累积) ===");
        testParseV2(data, 0x11943, 8192);

        System.out.println("\n=== 方式 3: 在 section 中逐字节查找 (handle, LE uint32 offset) ===");
        testParseV3(data, 0x11943, 8192);
    }

    static void testParseV1(byte[] data, int offset, int maxBytes) {
        // V1: 两者都是无符号 modular short, 累积
        List<long[]> result = new ArrayList<>();
        int pos = offset;
        while (pos < offset + maxBytes && pos < data.length - 4) {
            int pageSize = ((data[pos] & 0xFF) << 8) | (data[pos+1] & 0xFF);
            pos += 2;
            if (pageSize <= 2 || pageSize > 2040) break;
            int pairsSize = pageSize - 2;
            int pairsRead = 0;
            long lastHandle = 0;
            long lastOffset = 0;
            while (pairsRead < pairsSize && pos < data.length - 2) {
                int hByte = data[pos++] & 0xFF;
                pairsRead++;
                long hDelta;
                if (hByte < 0x80) {
                    if (hByte == 0) break;
                    hDelta = hByte;
                } else {
                    int lo = hByte & 0x7F;
                    int hi = data[pos++] & 0xFF;
                    pairsRead++;
                    hDelta = lo | ((long)hi << 7);
                    if (hDelta == 0) break;
                }
                lastHandle += hDelta;
                int oByte = data[pos++] & 0xFF;
                pairsRead++;
                long oDelta;
                if (oByte < 0x80) {
                    oDelta = oByte;
                } else {
                    int lo = oByte & 0x7F;
                    int hi = data[pos++] & 0xFF;
                    pairsRead++;
                    oDelta = lo | ((long)hi << 7);
                }
                lastOffset += oDelta;
                result.add(new long[]{ lastHandle, lastOffset });
            }
            pos += 2;
        }
        showParsed(result, data, 20);

        // 测试 base 地址
        System.out.println("\n测试 base 地址:");
        for (int base = 0x4000; base < 0x6000; base += 16) {
            int valid = 0, bh = 0, be = 0, ins = 0;
            for (long[] h : result) {
                int abs = base + (int)h[1];
                if (abs > 0 && abs < data.length - 4) {
                    int os = (data[abs] & 0xFF) | ((data[abs+1] & 0xFF) << 8);
                    if (os > 0 && os <= 500) {
                        int b2 = data[abs+2] & 0xFF;
                        int opcode = (b2 >> 6) & 0x3;
                        if (opcode == 1) {
                            int b3 = data[abs+3] & 0xFF;
                            int tc = ((b2 & 0x3F) << 2) | ((b3 >> 6) & 0x3);
                            valid++;
                            if (tc == 48) bh++;
                            if (tc == 49) be++;
                            if (tc == 7) ins++;
                        }
                    }
                }
            }
            if (valid > 50) System.out.printf("base=0x%04x: v=%d bh=%d be=%d ins=%d%n", base, valid, bh, be, ins);
        }
    }

    static void testParseV2(byte[] data, int offset, int maxBytes) {
        // V2: handle 累积, offset 每个 pair 都是绝对偏移
        List<long[]> result = new ArrayList<>();
        int pos = offset;
        while (pos < offset + maxBytes && pos < data.length - 4) {
            int pageSize = ((data[pos] & 0xFF) << 8) | (data[pos+1] & 0xFF);
            pos += 2;
            if (pageSize <= 2 || pageSize > 2040) break;
            int pairsSize = pageSize - 2;
            int pairsRead = 0;
            long lastHandle = 0;
            while (pairsRead < pairsSize && pos < data.length - 2) {
                int hByte = data[pos++] & 0xFF;
                pairsRead++;
                long hDelta;
                if (hByte < 0x80) {
                    if (hByte == 0) break;
                    hDelta = hByte;
                } else {
                    int lo = hByte & 0x7F;
                    int hi = data[pos++] & 0xFF;
                    pairsRead++;
                    hDelta = lo | ((long)hi << 7);
                    if (hDelta == 0) break;
                }
                lastHandle += hDelta;
                int oByte = data[pos++] & 0xFF;
                pairsRead++;
                long offs;
                if (oByte < 0x80) {
                    offs = oByte;
                } else {
                    int lo = oByte & 0x7F;
                    int hi = data[pos++] & 0xFF;
                    pairsRead++;
                    offs = lo | ((long)hi << 7);
                }
                result.add(new long[]{ lastHandle, offs });
            }
            pos += 2;
        }
        showParsed(result, data, 15);
    }

    static void testParseV3(byte[] data, int offset, int maxBytes) {
        // 直接扫描: 看实际字节结构
        // 可能是 (handle: MS, offset: LE uint32)
        System.out.println("显示前 80 个字节的序列:");
        for (int i = 0; i < 80; i++) {
            int b = data[offset + i] & 0xFF;
            if (i % 8 == 0) System.out.printf("\n  [%3d] ", i);
            System.out.printf("%02X ", b);
        }
        System.out.println();
    }

    static void showParsed(List<long[]> result, byte[] data, int count) {
        System.out.println("解析了 " + result.size() + " 条目");
        if (result.isEmpty()) return;
        long minO = Long.MAX_VALUE, maxO = 0;
        for (long[] h : result) {
            if (h[1] > 0) { minO = Math.min(minO, h[1]); maxO = Math.max(maxO, h[1]); }
        }
        System.out.println("offset 范围: " + minO + " - " + maxO + " (0x" + Long.toHexString(minO) + "-0x" + Long.toHexString(maxO) + ")");
        System.out.println("前 " + count + " 条:");
        for (int i = 0; i < Math.min(count, result.size()); i++) {
            long[] h = result.get(i);
            String bytes = "";
            if (h[1] >= 0 && h[1] < data.length - 4) {
                bytes = String.format("%02X %02X %02X %02X",
                    data[(int)h[1]] & 0xFF, data[(int)h[1]+1] & 0xFF,
                    data[(int)h[1]+2] & 0xFF, data[(int)h[1]+3] & 0xFF);
            }
            System.out.printf("  [%2d] h=0x%x  o=%d(0x%x)  ->%s%n", i, h[0], h[1], h[1], bytes);
        }
    }
}
