import java.nio.file.*;

public class DumpMore {
    static byte[] data;

    static void dumpRange(int start, int len) {
        for (int i = 0; i < len; i += 16) {
            int pos = start + i;
            System.out.print(String.format("0x%04x: ", pos));
            for (int j = 0; j < 16; j++) {
                if (pos + j < data.length) {
                    System.out.print(String.format("%02x ", data[pos + j] & 0xFF));
                    if (j == 7) System.out.print(" ");
                }
            }
            System.out.print("  |");
            for (int j = 0; j < 16; j++) {
                if (pos + j < data.length) {
                    int c = data[pos + j] & 0xFF;
                    if (c >= 32 && c <= 126) System.out.print((char) c);
                    else System.out.print(".");
                }
            }
            System.out.println("|");
        }
    }

    public static void main(String[] args) throws Exception {
        data = Files.readAllBytes(Paths.get("210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));

        System.out.println("=== OBJECT BOUNDARY: 0x52b0 to 0x5400 ===");
        dumpRange(0x52b0, 0x5400 - 0x52b0);

        System.out.println("\n=== INSERT AREA: 0x6960 to 0x6c00 ===");
        dumpRange(0x6960, 0x6c00 - 0x6960);

        // Now try multi-word MS: read up to 4 words (8 bytes) when bit15 is set
        System.out.println("\n=== MULTI-WORD MS SCAN ===");
        int pos = 0x52b9;
        for (int i = 0; i < 30; i++) {
            if (pos + 1 >= data.length) break;
            int cur = pos;
            int size = 0;
            int shift = 0;
            boolean hasMore = true;
            int words = 0;
            while (hasMore && cur + 1 < data.length && words < 4) {
                int lo = data[cur] & 0xFF;
                int hi = data[cur + 1] & 0xFF;
                int word = lo | (hi << 8);
                size |= (word & 0x7FFF) << shift;
                hasMore = (word & 0x8000) != 0;
                cur += 2;
                shift += 15;
                words++;
            }

            if (size <= 2 || size > 20000) { pos++; continue; }

            int dataStart = cur;
            int b0 = data[dataStart] & 0xFF;
            int b1 = data[dataStart + 1] & 0xFF;
            int type = ((b0 & 0x3F) << 2) | ((b1 >> 6) & 3);

            System.out.println(String.format(
                "obj[%2d] @0x%04x: size=%d, type=0x%02x (%s), next=0x%04x",
                i, pos, size, type,
                type == 0x30 ? "BH" : type == 0x07 ? "INS" : type == 0x01 ? "LYR" : type == 0x04 ? "CIR" : type == 0x19 ? "LIN" : "?",
                dataStart + size));

            if (type == 0x30 || type == 0x07) {
                // Show raw bytes of object
                int objectLen = Math.min(64, size + 4);
                StringBuilder ascii = new StringBuilder();
                System.out.print("    ");
                for (int j = 0; j < objectLen; j++) {
                    if (pos + j < data.length) {
                        int b = data[pos + j] & 0xFF;
                        System.out.print(String.format("%02x ", b));
                        if (b >= 32 && b <= 126) ascii.append((char) b);
                        else ascii.append(".");
                    }
                }
                System.out.println("\n    " + ascii.toString());
            }

            pos = dataStart + size;
        }
    }
}
