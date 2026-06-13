package run;

import java.io.File;
import java.io.FileInputStream;

public class InspectHeader {
    public static void main(String[] args) throws Exception {
        String path = "samples/2018/Dynblocks.dwg";
        File f = new File(path);
        byte[] data = new byte[(int) f.length()];
        FileInputStream fis = new FileInputStream(f);
        fis.read(data);
        fis.close();

        System.out.println("File size: " + data.length + " bytes");
        String version = new String(data, 0, 6);
        System.out.println("Version string: " + version);

        System.out.println("\n=== Bytes 0x00-0xFF:");
        dumpHex(data, 0, 256);

        System.out.println("\n=== Bytes 0x100-0x1FF:");
        dumpHex(data, 0x100, 256);

        System.out.println("\n=== Bytes 0x200-0x3FF:");
        dumpHex(data, 0x200, 512);

        // Check LE32 values at key offsets
        System.out.println("\n=== LE32 values at key offsets:");
        int[] offsets = new int[] {0x06, 0x0A, 0x0C, 0x0E, 0x10, 0x14, 0x18, 0x1C, 0x20, 0x40, 0x60, 0x7C, 0x80, 0x100};
        for (int off : offsets) {
            if (off + 4 <= data.length) {
                long val = ((long) (data[off] & 0xFF)) | ((long) (data[off+1] & 0xFF) << 8) |
                          ((long) (data[off+2] & 0xFF) << 16) | ((long) (data[off+3] & 0xFF) << 24);
                System.out.printf("  0x%02X: 0x%08X (%d)%n", off, val, val);
            }
        }
    }

    private static void dumpHex(byte[] data, int offset, int length) {
        for (int row = 0; row * 16 < length; row++) {
            int off = offset + row * 16;
            System.out.printf("  0x%04X: ", off);
            for (int col = 0; col < 16 && off + col < data.length; col++) {
                System.out.printf("%02X ", data[off + col] & 0xFF);
            }
            System.out.print("  ");
            for (int col = 0; col < 16 && off + col < data.length; col++) {
                byte b = data[off + col];
                System.out.printf("%c", (b >= 32 && b < 127) ? (char) b : '.');
            }
            System.out.println();
        }
    }
}
