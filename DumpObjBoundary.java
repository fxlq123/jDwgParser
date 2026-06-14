import java.nio.file.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class DumpObjBoundary {
    static byte[] data;

    static void dumpLine(int start, int length) {
        int end = Math.min(start + length, data.length);
        System.out.print(String.format("0x%04x: ", start));
        for (int i = start; i < end; i++) {
            System.out.print(String.format("%02x ", data[i] & 0xFF));
        }
        System.out.print("  ");
        for (int i = start; i < end; i++) {
            int c = data[i] & 0xFF;
            if (c >= 32 && c <= 126) System.out.print((char) c);
            else System.out.print(".");
        }
        System.out.println();
    }

    public static void main(String[] args) throws Exception {
        data = Files.readAllBytes(Paths.get("210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));

        // Dump the area from 0x5420 (near ACAD_MLINESTYLE) to 0x5680
        System.out.println("=== Area around _D_1, _D_2 etc. ===");
        for (int pos = 0x5420; pos < 0x5680; pos += 32) {
            dumpLine(pos, 32);
        }

        System.out.println("\n=== Area around 0x5bf4 (SLDTEXTSTYLE0) ===");
        for (int pos = 0x5b80; pos < 0x5da0; pos += 32) {
            dumpLine(pos, 32);
        }

        System.out.println("\n=== Area around 0x89fc (I-Me5) ===");
        for (int pos = 0x8980; pos < 0x8a80; pos += 32) {
            dumpLine(pos, 32);
        }
    }
}
