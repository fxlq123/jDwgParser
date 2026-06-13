package run;

import java.io.File;
import java.nio.file.Files;

/**
 * Scan file to find non-zero data regions
 */
public class ZeroScan {
    public static void main(String[] args) throws Exception {
        String path = "samples/2018/Dynblocks.dwg";
        byte[] data = Files.readAllBytes(new File(path).toPath());
        System.out.println("File: " + data.length + " bytes");

        // Find contiguous zero regions
        System.out.println("\n=== Zero/Non-Zero Region Map ===");
        int i = 0;
        while (i < data.length) {
            // Find start of non-zero
            while (i < data.length && data[i] == 0) i++;
            if (i >= data.length) break;
            int startNZ = i;
            // Find start of zeros (>= 32 consecutive zeros)
            int zeroCount = 0;
            while (i < data.length) {
                if (data[i] == 0) {
                    zeroCount++;
                    if (zeroCount >= 32) break;
                } else {
                    zeroCount = 0;
                }
                i++;
            }
            int endNZ = i - zeroCount;
            System.out.printf("  0x%06X - 0x%06X (%d bytes)%n",
                startNZ, endNZ, endNZ - startNZ);
            // Show first 64 bytes
            int showLen = Math.min(64, endNZ - startNZ);
            dumpBytes(data, startNZ, showLen);
        }
    }

    private static void dumpBytes(byte[] data, int offset, int length) {
        System.out.print("    ");
        for (int j = 0; j < length; j++) {
            System.out.printf("%02X ", data[offset + j] & 0xFF);
        }
        System.out.print("  ");
        for (int j = 0; j < length; j++) {
            byte b = data[offset + j];
            System.out.printf("%c", (b >= 32 && b < 127) ? b : '.');
        }
        System.out.println();
    }
}
