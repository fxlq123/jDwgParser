package run;

import io.dwg.core.util.ByteUtils;
import io.dwg.core.util.Lz77Decompressor;

import java.io.File;
import java.nio.file.Files;

public class TryHeader {
    public static void main(String[] args) throws Exception {
        String[] files = {
            "samples/2018/Dynblocks.dwg",
            "samples/2007/circle.dwg",
        };

        for (String path : files) {
            File f = new File(path);
            if (!f.exists()) { System.out.println("SKIP: " + path); continue; }

            byte[] data = Files.readAllBytes(f.toPath());
            String sig = new String(data, 0, 6);
            System.out.println("\n=== " + path + " [" + sig + "] ===");

            // Approach 1: Raw bytes 0x80-0x3d8 (no deinterleaving)
            byte[] rawHeader = new byte[0x358];
            System.arraycopy(data, 0x80, rawHeader, 0, 0x358);

            // Approach 2: Deinterleaved (R2007 style)
            byte[] deinterleaved = deinterleave(rawHeader);

            // Try both approaches
            tryDecoded("raw", rawHeader);
            tryDecoded("deinterleaved", deinterleaved);
        }
    }

    private static byte[] deinterleave(byte[] data) {
        if (data == null || data.length < 765) return null;
        try {
            final int BLOCK_COUNT = 3;
            final int DATA_SIZE = 239;
            byte[] result = new byte[BLOCK_COUNT * DATA_SIZE];
            for (int i = 0; i < BLOCK_COUNT; i++) {
                for (int j = 0; j < DATA_SIZE; j++) {
                    result[i * DATA_SIZE + j] = data[i + j * BLOCK_COUNT];
                }
            }
            return result;
        } catch (Exception e) { return null; }
    }

    private static void tryDecoded(String label, byte[] decoded) {
        if (decoded == null || decoded.length < 32) return;

        int comprLen = (int) ByteUtils.readLE32(decoded, 24);
        System.out.println(label + ": comprLen=" + comprLen + " (0x" +
            Integer.toHexString(comprLen) + ")");

        if (comprLen > 0 && comprLen < decoded.length - 32) {
            byte[] compressed = new byte[comprLen];
            System.arraycopy(decoded, 32, compressed, 0, comprLen);
            try {
                Lz77Decompressor lz77 = new Lz77Decompressor();
                byte[] decompressed = lz77.decompress(compressed, 1000);
                if (decompressed != null) {
                    System.out.println(label + ": decompressed " + decompressed.length + " bytes");
                    // Show first 64 bytes
                    System.out.print("  ");
                    for (int i = 0; i < Math.min(64, decompressed.length); i++) {
                        System.out.printf("%02X ", decompressed[i] & 0xFF);
                        if ((i + 1) % 16 == 0) System.out.print("\n  ");
                    }
                    System.out.println();

                    // Try reading some LE64 fields
                    for (int off : new int[]{24, 56, 80, 88, 176, 192, 200, 216}) {
                        if (off + 8 <= decompressed.length) {
                            long v = ByteUtils.readLE64(decompressed, off);
                            System.out.printf("  decompressed[%d]: 0x%016X (%d)%n", off, v, v);
                        }
                    }
                }
            } catch (Exception e) {
                System.out.println(label + ": LZ77 failed: " + e.getMessage());
            }
        } else {
            // Treat as already-decompressed data
            System.out.println(label + ": treating as plain data, first bytes:");
            for (int i = 0; i < Math.min(64, decoded.length); i++) {
                System.out.printf("%02X ", decoded[i] & 0xFF);
                if ((i + 1) % 16 == 0) System.out.println();
            }
        }
    }
}
