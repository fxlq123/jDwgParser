import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class DebugHandles {
    public static void main(String[] args) throws Exception {
        byte[] data = Files.readAllBytes(Paths.get(
            "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));

        List<long[]> handles = parseHandles(data, 0x11943, 8192);
        System.out.println("Handles: " + handles.size() + "\n");

        // 显示前 50 个和 handle 在 Blocks 区域的
        System.out.println("=== 前 50 个 handle 条目 ===");
        for (int i = 0; i < Math.min(50, handles.size()); i++) {
            long[] h = handles.get(i);
            String bytes = "";
            if (h[1] > 0 && h[1] < data.length - 4) {
                bytes = String.format("%02X %02X %02X %02X",
                    data[(int)h[1]] & 0xFF, data[(int)h[1]+1] & 0xFF,
                    data[(int)h[1]+2] & 0xFF, data[(int)h[1]+3] & 0xFF);
            }
            System.out.printf("[%2d] handle=0x%x  offset=0x%x (%d)  first-bytes: %s%n",
                i, h[0], h[1], h[1], bytes);
        }

        // 显示 offset 在 0x5400-0x5700 的
        System.out.println("\n=== offset 在 0x5400-0x5700 (Blocks 区域) ===");
        for (int i = 0; i < handles.size(); i++) {
            long[] h = handles.get(i);
            if (h[1] >= 0x5400 && h[1] <= 0x5700) {
                String bytes = "";
                if (h[1] > 0 && h[1] < data.length - 8) {
                    bytes = String.format("%02X %02X %02X %02X %02X %02X %02X %02X",
                        data[(int)h[1]] & 0xFF, data[(int)h[1]+1] & 0xFF,
                        data[(int)h[1]+2] & 0xFF, data[(int)h[1]+3] & 0xFF,
                        data[(int)h[1]+4] & 0xFF, data[(int)h[1]+5] & 0xFF,
                        data[(int)h[1]+6] & 0xFF, data[(int)h[1]+7] & 0xFF);
                }
                System.out.printf("[%2d] handle=0x%x  offset=0x%x  bytes: %s%n",
                    i, h[0], h[1], bytes);
            }
        }

        // offset 分布
        System.out.println("\n=== offset 分布 ===");
        long minOff = Long.MAX_VALUE, maxOff = 0;
        for (long[] h : handles) {
            if (h[1] > 0) {
                minOff = Math.min(minOff, h[1]);
                maxOff = Math.max(maxOff, h[1]);
            }
        }
        System.out.println("最小: 0x" + Long.toHexString(minOff) + "  最大: 0x" + Long.toHexString(maxOff));
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
