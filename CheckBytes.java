import java.nio.file.*;

public class CheckBytes {
    public static void main(String[] args) throws Exception {
        byte[] data = Files.readAllBytes(Paths.get("210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));

        // Dump bytes around known object positions
        int[] positions = { 0x52b0, 0x6970, 0xa5c0, 0xc4e0, 0xccf0, 0xd1f0, 0xdbe0, 0xe9e0 };
        for (int pos : positions) {
            System.out.println("\nBytes at 0x" + Integer.toHexString(pos) + ":");
            for (int i = 0; i < 32; i++) {
                if (pos + i < data.length) {
                    System.out.print(String.format("%02x ", data[pos + i] & 0xFF));
                    if ((i + 1) % 16 == 0) System.out.println();
                }
            }
            System.out.println();
        }

        // Also check what's at 0x52b9 specifically
        System.out.println("\nObject boundary analysis:");
        System.out.println(String.format("  @0x52b9 = 0x%02x", data[0x52b9] & 0xFF));
        System.out.println(String.format("  @0x52ba = 0x%02x", data[0x52ba] & 0xFF));
        System.out.println(String.format("  @0x52bb = 0x%02x", data[0x52bb] & 0xFF));
        System.out.println(String.format("  @0x52bc = 0x%02x", data[0x52bc] & 0xFF));

        // Test various MS interpretations
        int byte1 = data[0x52b9] & 0xFF;
        int byte2 = data[0x52ba] & 0xFF;
        int byte3 = data[0x52bb] & 0xFF;
        System.out.println("\nInterpretations:");
        System.out.println("  1-byte MS: value=" + (byte1 & 0x7F) + ", continuation=" + ((byte1 >> 7) & 1));
        System.out.println("  2-byte LE16: value=" + (byte1 | (byte2 << 8)) + ", bit15=" + ((byte2 >> 7) & 1));
        System.out.println("  Next object if 1-byte (size=" + (byte1 & 0x7F) + "): 0x" +
                Integer.toHexString(0x52b9 + 1 + (byte1 & 0x7F)));
        System.out.println("  Next object if 2-byte (size=" + (byte1 | (byte2 & 0x7F) << 8) + "): 0x" +
                Integer.toHexString(0x52b9 + 2 + (byte1 | ((byte2 & 0x7F) << 8))));

        // For the INSERT at 0x6971
        System.out.println("\nINSERT analysis (@0x6971):");
        byte1 = data[0x6971] & 0xFF;
        byte2 = data[0x6972] & 0xFF;
        System.out.println(String.format("  byte1=0x%02x, byte2=0x%02x", byte1, byte2));
        System.out.println("  1-byte MS: value=" + (byte1 & 0x7F));
        System.out.println("  2-byte LE16: value=" + (byte1 | (byte2 << 8)));
        System.out.println(String.format("  data[0x6973]=0x%02x data[0x6974]=0x%02x",
                data[0x6973] & 0xFF, data[0x6974] & 0xFF));

        // Try scanning with 1-byte MS and see type codes
        System.out.println("\n1-byte MS scan from 0x52b9:");
        int pos = 0x52b9;
        for (int i = 0; i < 20; i++) {
            int sz = data[pos] & 0xFF;
            if ((sz & 0x80) == 0) {  // single byte
                int dstart = pos + 1;
                int tcode = ((data[dstart] & 0x3F) << 2) | ((data[dstart + 1] >> 6) & 3);
                System.out.println(String.format("  obj[%d] @0x%04x: 1-byte-MS size=%d, type=0x%02x (%s)",
                        i, pos, sz, tcode,
                        tcode == 0x30 ? "BLOCK_HEADER" : tcode == 0x07 ? "INSERT" : String.format("T%d", tcode)));
                pos = dstart + sz;
            } else {
                // multi-byte MS
                int sz2 = ((sz & 0x7F) << 7) | (data[pos + 1] & 0x7F);
                int dstart = pos + 2;
                int tcode = ((data[dstart] & 0x3F) << 2) | ((data[dstart + 1] >> 6) & 3);
                System.out.println(String.format("  obj[%d] @0x%04x: 2-byte-MS size=%d, type=0x%02x (%s)",
                        i, pos, sz2, tcode,
                        tcode == 0x30 ? "BLOCK_HEADER" : tcode == 0x07 ? "INSERT" : String.format("T%d", tcode)));
                pos = dstart + sz2;
            }
            if (pos >= data.length - 5) break;
        }
    }
}
