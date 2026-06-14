import io.dwg.core.io.*;
import io.dwg.core.version.DwgVersion;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class ParseHandlesSection {
    public static void main(String[] args) throws Exception {
        byte[] data = Files.readAllBytes(Paths.get(
            "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));

        System.out.println("=== 解析 Handles Section ===\n");

        // Handles section 在 0x11943
        int handlesStart = 0x11943;

        System.out.println("Handles section @ 0x" + Integer.toHexString(handlesStart));
        System.out.print("Bytes: ");
        for (int i = 0; i < 32; i++) System.out.printf("%02X ", data[handlesStart + i] & 0xFF);
        System.out.println();

        // R2000 handles 使用页面式结构
        // 解析 handles
        List<long[]> handles = parseHandles(data, handlesStart, 8192);
        System.out.println("\n解析到 " + handles.size() + " 个 handle 条目");

        // 显示前 20 个
        System.out.println("\n前 20 个 handle 条目:");
        for (int i = 0; i < Math.min(20, handles.size()); i++) {
            long[] h = handles.get(i);
            System.out.printf("  [%d] handle=0x%x offset=0x%x (%d)%n", i, h[0], h[1], h[1]);
        }

        // 用 handles 中的 offset 来扫描对象
        System.out.println("\n=== 用 handle offsets 扫描对象 ===");
        Set<Integer> scannedOffsets = new HashSet<>();
        List<String> blockNames = new ArrayList<>();

        for (int i = 0; i < Math.min(handles.size(), 500); i++) {
            long[] h = handles.get(i);
            int offset = (int) h[1];

            // 检查 offset 是否有效
            if (offset < 0x5000 || offset > data.length - 4) continue;
            if (scannedOffsets.contains(offset)) continue;
            scannedOffsets.add(offset);

            if (data[offset] == 0) continue;

            try {
                byte[] sub = Arrays.copyOfRange(data, offset, data.length);
                ByteBufferBitInput buf = new ByteBufferBitInput(ByteBuffer.wrap(sub));
                BitStreamReader r = new BitStreamReader(buf, DwgVersion.R2000);

                int objSize = r.readModularShort();
                if (objSize <= 2 || objSize > 2000) continue;

                int typeCode = r.readBitShort();
                if (typeCode < 0 || typeCode > 255) continue;

                // 只处理 BLOCK_HEADER
                if (typeCode == 48) {
                    System.out.printf("BLOCK_HEADER @ offset=0x%x (handle=0x%x):%n", offset, h[0]);

                    // 尝试解析 name
                    // 跳过 common header 然后读 name
                    try {
                        r.readBitLong(); // bitsize
                        r.readHandle(); // entity handle
                        int eedSize = r.readModularShort();
                        if (eedSize > 0 && eedSize < 200) buf.seek(buf.position() + eedSize * 8L);
                        r.readHandle(); // owner
                        int numReactors = r.readBitLong();
                        for (int j = 0; j < Math.min(numReactors, 10); j++) {
                            try { r.readHandle(); } catch (Exception e) { break; }
                        }
                        try { r.readHandle(); } catch (Exception e) {} // xdict

                        // name: 1-byte length + ASCII
                        long namePos = buf.position();
                        buf.seek(((namePos + 7) / 8) * 8);
                        int nameLen = 0;
                        for (int j = 0; j < 8; j++) nameLen = (nameLen << 1) | (buf.readBit() ? 1 : 0);
                        if (nameLen > 0 && nameLen < 100) {
                            StringBuilder sb = new StringBuilder();
                            for (int j = 0; j < nameLen; j++) {
                                int ch = 0;
                                for (int k = 0; k < 8; k++) ch = (ch << 1) | (buf.readBit() ? 1 : 0);
                                sb.append((char)ch);
                            }
                            System.out.println("  name: '" + sb.toString() + "'");
                            blockNames.add(sb.toString());
                        }
                    } catch (Exception e) {
                        System.out.println("  parse error: " + e.getMessage());
                    }
                }
            } catch (Exception e) {}
        }

        System.out.println("\n找到的块名称: " + blockNames);
    }

    static List<long[]> parseHandles(byte[] data, int offset, int maxBytes) {
        List<long[]> result = new ArrayList<>();
        int pos = offset;
        int itemsRead = 0;

        while (pos < offset + maxBytes && pos < data.length - 4 && itemsRead < 2000) {
            int pageSize = ((data[pos] & 0xFF) << 8) | (data[pos+1] & 0xFF);
            pos += 2;
            if (pageSize <= 2 || pageSize > 2040) break;

            int pairsBytes = pageSize - 4;  // -2 for size, -2 for CRC
            int pairsRead = 0;
            long lastHandle = 0;
            long lastOffset = 0;

            while (pairsRead < pairsBytes && pos < data.length - 2 && itemsRead < 2000) {
                // handle delta
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
                }
                lastHandle += hDelta;

                // offset delta
                int oByte = data[pos++] & 0xFF;
                pairsRead++;
                long oDelta;
                if (oByte < 0x80) {
                    oDelta = oByte & 0x3F;  // 有符号 6-bit
                } else {
                    int lo = oByte & 0x7F;
                    int hi = data[pos++] & 0xFF;
                    pairsRead++;
                    int combined = lo | (hi << 7);
                    oDelta = combined;  // 可能有符号
                }
                lastOffset += oDelta;

                result.add(new long[]{lastHandle, lastOffset});
                itemsRead++;
            }
            pos += 2;  // CRC
        }
        return result;
    }
}
