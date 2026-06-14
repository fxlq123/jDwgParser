package run;

import java.nio.file.Paths;
import java.util.*;
import java.nio.charset.StandardCharsets;

public class DebugHandleOffset {
    public static void main(String[] args) throws Exception {
        String filename = "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg";
        byte[] data = java.nio.file.Files.readAllBytes(Paths.get(filename));

        // 先解析 handles
        List<long[]> handles = parseHandles(data, 0x11943, 3000);
        System.out.println("解析到 " + handles.size() + " handles");

        // 打印前 30 个 handle 的原始字节
        System.out.println();
        System.out.println("Handle 0x548f-0x5500 (已知有 *Paper_Space):");
        for (int i = 0x5484; i < Math.min(0x5530, data.length); i++) {
            if (i % 16 == 0) System.out.printf("%n  0x%04X: ", i);
            System.out.printf("%02X ", data[i] & 0xFF);
        }
        System.out.println();

        // 打印 0x548f: *Paper_Space 在 0x548f
        System.out.println();
        System.out.println("从 0x5484 开始的详细字节:");
        for (int i = 0x5484; i < 0x54B8; i++) {
            System.out.printf("  0x%04X: 0x%02X (%d) = %c%n",
                i, data[i] & 0xFF, data[i] & 0xFF,
                (data[i] & 0xFF) >= 32 && (data[i] & 0xFF) < 127 ? (char)(data[i] & 0xFF) : '.');
        }

        // 检查 MS 读取方式
        System.out.println();
        System.out.println("=== 从 0x5484 解析 objSize:");
        int b0 = data[0x5484] & 0xFF;
        int b1 = data[0x5485] & 0xFF;
        System.out.println("  LE uint16 = " + (b0 | (b1 << 8)));
        System.out.println("  最高位: b0=" + b0 + " b1=" + b1);
        // 实际 objSize 字节值

        // 让我看看所有 handles 里 offset 在 0x5484 附近的条目
        System.out.println();
        System.out.println("Handles 里 offset 在 0x5400-0x5500 的条目:");
        for (long[] h : handles) {
            if (h[1] >= 0x5400 && h[1] <= 0x5500) {
                System.out.printf("  handle=0x%x offset=0x%x%n", h[0], h[1]);
                // 打印偏移处的前 10 字节
                StringBuilder sb = new StringBuilder();
                for (int k = 0; k < 10; k++) {
                    int idx = (int)h[1] + k;
                    if (idx < data.length) sb.append(String.format("%02X ", data[idx] & 0xFF));
                }
                System.out.println("    字节: " + sb);
            }
        }
    }

    static List<long[]> parseHandles(byte[] data, int offset, int maxBytes) {
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
                    oDelta = (oByte > 63) ? (long)oByte - 128 : oByte;
                } else {
                    int lo = oByte & 0x7F;
                    int hi = data[pos++] & 0xFF;
                    pairsRead++;
                    int combined = lo | (hi << 7);
                    oDelta = (combined > 16383) ? (long)combined - 32768 : combined;
                }
                lastOffset += oDelta;
                result.add(new long[]{ lastHandle, lastOffset });
            }
            pos += 2;
        }
        return result;
    }
}
