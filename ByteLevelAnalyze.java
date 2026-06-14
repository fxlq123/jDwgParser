import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 手动分析 R2000 对象格式 - 字节级追踪
 */
public class ByteLevelAnalyze {

    public static void main(String[] args) throws Exception {
        byte[] data = Files.readAllBytes(Paths.get("210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));

        System.out.println("=== 字节级分析: 0x52b9 位置 ===\n");

        // 显示 0x52b9 - 0x5330:
        int start = 0x52b9;
        for (int i = 0; i < 128; i++) {
            if (i % 16 == 0) {
                System.out.printf("%04x: ", start + i);
            }
            int b = data[start + i] & 0xFF;
            System.out.printf("%02x ", b);
            if (i % 16 == 15) {
                System.out.print(" |");
                for (int j = i - 15; j <= i; j++) {
                    int c = data[start + j] & 0xFF;
                    if (c >= 32 && c < 127) System.out.print((char)c);
                    else System.out.print(".");
                }
                System.out.println("|");
            }
        }

        System.out.println("\n=== 0x5a82 附近 (*Model_Space) ===\n");

        int start2 = 0x5a82 - 32;
        for (int i = 0; i < 128; i++) {
            if (i % 16 == 0) {
                System.out.printf("%04x: ", start2 + i);
            }
            int b = data[start2 + i] & 0xFF;
            System.out.printf("%02x ", b);
            if (i % 16 == 15) {
                System.out.print(" |");
                for (int j = i - 15; j <= i; j++) {
                    int c = data[start2 + j] & 0xFF;
                    if (c >= 32 && c < 127) System.out.print((char)c);
                    else System.out.print(".");
                }
                System.out.println("|");
            }
        }

        // 解析 Handles 部分位置 0x11943
        System.out.println("\n=== Handles Section @ 0x11943 ===\n");
        int hs = 0x11943;
        for (int i = 0; i < 64; i++) {
            if (i % 16 == 0) {
                System.out.printf("%04x: ", hs + i);
            }
            int b = data[hs + i] & 0xFF;
            System.out.printf("%02x ", b);
            if (i % 16 == 15) {
                System.out.print(" |");
                for (int j = i - 15; j <= i; j++) {
                    int c = data[hs + j] & 0xFF;
                    if (c >= 32 && c < 127) System.out.print((char)c);
                    else System.out.print(".");
                }
                System.out.println("|");
            }
        }

        // 手动解析 Handles (正确方式: 2-byte page size, then handle_delta + offset_delta)
        System.out.println("\n=== 手动解析 Handles Section ===\n");

        int pos = hs;
        // Page size: 2 bytes (BE)
        int pageSize = ((data[pos] & 0xFF) << 8) | (data[pos+1] & 0xFF);
        pos += 2;
        System.out.println("Page size (BE 2 bytes): " + pageSize + " (0x" + Integer.toHexString(pageSize) + ")");

        // Now parse handle/offset pairs
        long handle = 0;
        long offset = 0;
        int pairsRead = 0;
        int pageDataEnd = hs + 2 + (pageSize - 4); // -4 for size(2) + CRC(2)

        System.out.println("First 20 pairs (handle_delta, offset_delta):\n");

        while (pos < pageDataEnd && pairsRead < 20) {
            // handle delta: MC (modular char)
            int hByte = data[pos++] & 0xFF;
            long hDelta;
            if (hByte < 0x80) {
                hDelta = hByte;
            } else {
                int lo = hByte & 0x7F;
                int hi = data[pos++] & 0xFF;
                hDelta = lo | (hi << 7);
            }
            handle += hDelta;

            // offset delta: MC
            int oByte = data[pos++] & 0xFF;
            long oDelta;
            if (oByte < 0x80) {
                oDelta = oByte;
            } else {
                int lo = oByte & 0x7F;
                int hi = data[pos++] & 0xFF;
                oDelta = lo | (hi << 7);
            }
            offset += oDelta;

            System.out.printf("Pair %d: handle_delta=0x%02x(%d) -> handle=0x%x | offset_delta=0x%04x(%d) -> offset=0x%x%n",
                pairsRead, hByte, hByte, handle, oByte, oDelta, offset);

            pairsRead++;
        }

        System.out.println("\n=== 在解析的 offset 处查找对象 ===\n");

        // 重新解析所有 handle-offset 对
        pos = hs + 2;
        handle = 0;
        offset = 0;
        pairsRead = 0;
        int pairsInPage = 0;

        while (pos < data.length - 4 && pairsInPage < 1000) {
            // handle delta
            int hByte = data[pos++] & 0xFF;
            long hDelta;
            if (hByte < 0x80) {
                hDelta = hByte;
            } else {
                int lo = hByte & 0x7F;
                int hi = data[pos++] & 0xFF;
                hDelta = lo | (hi << 7);
            }
            handle += hDelta;

            // offset delta
            int oByte = data[pos++] & 0xFF;
            long oDelta;
            if (oByte < 0x80) {
                oDelta = oByte;
            } else {
                int lo = oByte & 0x7F;
                int hi = data[pos++] & 0xFF;
                oDelta = lo | (hi << 7);
            }
            offset += oDelta;

            // 在 offset 处检查对象
            if (offset >= 0 && offset < data.length - 4) {
                int off = (int)offset;
                // 读取 MS: 16-bit LE
                int ms = (data[off] & 0xFF) | ((data[off+1] & 0xFF) << 8);
                int type = data[off + 2] & 0xFF;
                if (ms > 10 && ms < 3000 && (type >= 1 && type < 255)) {
                    if (pairsRead < 10) {
                        System.out.printf("h=0x%x: offset=0x%x MS=%d type=0x%02x%n",
                            handle, offset, ms, type);
                    }
                    pairsRead++;
                }
            }

            pairsInPage++;
            // page boundary check
            if (pos >= pageDataEnd) {
                // skip CRC
                pos += 2;
                // next page
                if (pos < data.length - 2) {
                    int nextPageSize = ((data[pos] & 0xFF) << 8) | (data[pos+1] & 0xFF);
                    if (nextPageSize > 2 && nextPageSize < 2040) {
                        pos += 2;
                        pageDataEnd = pos + (nextPageSize - 4);
                    } else {
                        break;
                    }
                } else {
                    break;
                }
            }
        }

        System.out.println("\n共解析: " + pairsRead + " 个有效对象");
    }
}
