package run;

import io.dwg.core.util.ByteUtils;

import java.io.File;
import java.nio.file.Files;

public class FullHeader {
    public static void main(String[] args) throws Exception {
        String path = "samples/2018/Dynblocks.dwg";
        byte[] data = Files.readAllBytes(new File(path).toPath());

        System.out.println("=== Full dump 0x80-0x500 ===");
        for (int row = 0; row * 16 < 0x480; row++) {
            int offset = 0x80 + row * 16;
            System.out.printf("  0x%04X: ", offset);
            for (int col = 0; col < 16; col++) {
                System.out.printf("%02X ", data[offset + col] & 0xFF);
            }
            System.out.print("  ");
            for (int col = 0; col < 16; col++) {
                byte b = data[offset + col];
                System.out.printf("%c", (b >= 32 && b < 127) ? b : '.');
            }
            System.out.println();
        }

        // Try deinterleaving at various block sizes
        System.out.println("\n=== Deinterleave experiments ===");
        for (int blocks : new int[]{3, 4, 5, 8, 16, 32}) {
            for (int dataSize : new int[]{239, 252, 255, 512, 1024}) {
                int totalInput = blocks * dataSize;
                if (0x80 + totalInput > data.length) continue;
                try {
                    byte[] result = new byte[blocks * dataSize];
                    for (int i = 0; i < blocks; i++) {
                        for (int j = 0; j < dataSize; j++) {
                            result[i * dataSize + j] = data[0x80 + i + j * blocks];
                        }
                    }
                    // Check for readable content
                    int printable = 0;
                    for (byte b : result) {
                        if ((b >= 0x20 && b < 0x7F) || b == 0 || b == 1 || b == 7) printable++;
                    }
                    if (printable > result.length / 2) {
                        System.out.printf("  blocks=%d dataSize=%d: %d printable bytes (%.1f%%)%n",
                            blocks, dataSize, printable, 100.0*printable/result.length);
                        System.out.printf("  First 48 bytes: ");
                        for (int i = 0; i < Math.min(48, result.length); i++)
                            System.out.printf("%02X ", result[i] & 0xFF);
                        System.out.println();
                    }
                } catch (Exception ignored) {}
            }
        }

        // Analyze the deinterleaved data for R2007-style: 3 blocks × 239
        {
            int blocks = 3, dataSize = 239;
            byte[] result = new byte[blocks * dataSize];
            for (int i = 0; i < blocks; i++) {
                for (int j = 0; j < dataSize; j++) {
                    result[i * dataSize + j] = data[0x80 + i + j * blocks];
                }
            }
            System.out.println("\n=== 3×239 deinterleaved data ===");
            // Look for strings and numbers
            for (int i = 0; i < result.length - 16; i += 8) {
                long v = ByteUtils.readLE64(result, i);
                if (v > 0 && v < 0x00FFFFFFL) {
                    System.out.printf("  [%04X] LE64 = %d (0x%08X)%n", i, v, v);
                }
            }
            // First 256 bytes as hex
            System.out.print("\n  First 256 bytes (16 per row):\n  ");
            for (int i = 0; i < 256 && i < result.length; i++) {
                System.out.printf("%02X ", result[i] & 0xFF);
                if ((i + 1) % 16 == 0) System.out.print("\n  ");
            }
            System.out.println();

            // Find "rurban" (0x72,0x75,0x72,0x62,0x61,0x6E) in the deinterleaved data
            byte[] pattern = {0x72, 0x00, 0x75, 0x00, 0x72, 0x00, 0x62, 0x00, 0x61, 0x00, 0x6E, 0x00};
            for (int i = 0; i < result.length - pattern.length; i++) {
                boolean match = true;
                for (int j = 0; j < pattern.length; j++) {
                    if ((result[i + j] & 0xFF) != pattern[j]) { match = false; break; }
                }
                if (match) {
                    System.out.printf("  'rurban' found at deinterleaved offset 0x%X%n", i);
                }
            }
        }
    }
}
