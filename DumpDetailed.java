import java.nio.file.*;

public class DumpDetailed {
    static byte[] data;

    static void dumpLine(int pos, int len) {
        System.out.print(String.format("0x%04x: ", pos));
        for (int i = 0; i < len; i++) {
            if (pos + i < data.length) {
                System.out.print(String.format("%02x ", data[pos + i] & 0xFF));
                if ((i + 1) % 8 == 0) System.out.print(" ");
            }
        }
        System.out.print(" | ");
        for (int i = 0; i < len; i++) {
            if (pos + i < data.length) {
                int c = data[pos + i] & 0xFF;
                if (c >= 32 && c <= 126) System.out.print((char) c);
                else System.out.print(".");
            }
        }
        System.out.println();
    }

    static String readTextFromPos(int pos) {
        int len = data[pos] & 0xFF;
        if (len < 1 || len > 50 || pos + 1 + len > data.length) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            int c = data[pos + 1 + i] & 0xFF;
            if (c < 32 || c > 126) return null;
            sb.append((char) c);
        }
        return sb.toString();
    }

    public static void main(String[] args) throws Exception {
        data = Files.readAllBytes(Paths.get("210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));

        // Manual scan: start at 0x52b9, use 2-byte LE16 for MS
        System.out.println("=== MANUAL SCAN with 2-byte LE16 MS ===");
        int pos = 0x52b9;
        for (int i = 0; i < 30; i++) {
            if (pos + 3 >= data.length) break;

            int lo = data[pos] & 0xFF;
            int hi = data[pos + 1] & 0xFF;
            int word = lo | (hi << 8);
            int size = word & 0x7FFF;
            boolean cont = (word & 0x8000) != 0;

            int dataStart = pos + 2;
            int type = -1;
            if (!cont && dataStart + 1 < data.length && size > 2 && size < 20000) {
                int b0 = data[dataStart] & 0xFF;
                int b1 = data[dataStart + 1] & 0xFF;
                type = ((b0 & 0x3F) << 2) | ((b1 >> 6) & 3);
            }

            System.out.println(String.format(
                "obj[%2d] @0x%04x: MS(LE16)=0x%04x, size=%d, cont=%b, type=0x%02x(%s), next@0x%04x",
                i, pos, word, size, cont, type,
                type == 0x30 ? "BH" : type == 0x07 ? "INS" : type == 0x01 ? "LYR" : "?",
                dataStart + size));

            if (type == 0x30 || type == 0x07) {
                // Show raw bytes
                dumpLine(pos, Math.min(48, size + 4));
                // Search for text in object
                for (int t = dataStart; t < dataStart + size - 2; t++) {
                    String text = readTextFromPos(t);
                    if (text != null && text.length() >= 3 && text.matches("[A-Za-z_*][A-Za-z0-9_*]*")) {
                        System.out.println("         -> text @" + Integer.toHexString(t) + ": " + text);
                    }
                }
            }

            if (cont) {
                pos++;  // try next position
            } else {
                pos = dataStart + size;
            }

            if (pos >= data.length - 4) break;
        }
    }
}
