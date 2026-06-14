import java.nio.file.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

public class AnalyzeSmallInserts {
    static byte[] data;

    static void dumpObject(int offset, int size) {
        System.out.println(String.format("=== Object @0x%04x, size=%d bytes ===", offset, size));
        for (int i = 0; i < size + 4; i += 16) {
            int pos = offset + i;
            if (pos >= data.length) break;
            System.out.print(String.format("0x%04x: ", pos));
            for (int j = 0; j < 16 && pos + j < data.length; j++) {
                System.out.print(String.format("%02x ", data[pos + j] & 0xFF));
            }
            System.out.print("  ");
            for (int j = 0; j < 16 && pos + j < data.length; j++) {
                int c = data[pos + j] & 0xFF;
                if (c >= 32 && c <= 126) System.out.print((char) c);
                else System.out.print(".");
            }
            System.out.println();
        }
        System.out.println();
    }

    public static void main(String[] args) throws Exception {
        data = Files.readAllBytes(Paths.get("210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));

        // Small INSERT objects (38-40 bytes)
        int[] smallInsertOffsets = {0xa5c2, 0xc4e9, 0xccf3, 0xd1f7, 0xd223, 0xdbdf, 0xddd1,
                                      0xea77, 0xf809, 0xf835, 0xf861, 0xf938};

        for (int offset : smallInsertOffsets) {
            int lo = data[offset] & 0xFF;
            int hi = data[offset + 1] & 0xFF;
            int size = (lo | (hi << 8)) & 0x7FFF;
            dumpObject(offset, size);
        }

        // Large INSERT (512 bytes)
        dumpObject(0x6971, 60);  // just show first 60 bytes

        // BLOCK_HEADER objects
        System.out.println("=== BLOCK_HEADER objects ===");
        dumpObject(0x52b9, 114);
        dumpObject(0xd3a6, Math.min(80, 1554));
        dumpObject(0xd4b8, Math.min(80, 1746));
    }
}
