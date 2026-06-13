package run;

import io.dwg.core.io.ByteBufferBitInput;
import io.dwg.core.util.Lz77Decompressor;

import java.io.File;
import java.nio.file.Files;

public class PostPalette {
    public static void main(String[] args) throws Exception {
        for (String path : new String[]{"samples/2018/Dynblocks.dwg", "samples/2007/circle.dwg"}) {
            File f = new File(path);
            if (!f.exists()) continue;
            byte[] data = Files.readAllBytes(f.toPath());
            System.out.println("\n=== " + path + " (" + data.length + " bytes) ===");

            // Show data around palette area
            for (int offset : new int[]{0x400, 0x480, 0x500, 0x580, 0x600, 0x700, 0x780, 0x800, 0x900, 0x1000, 0x2000, 0x4000}) {
                if (offset + 48 < data.length) {
                    System.out.printf("  0x%X: ", offset);
                    for (int i = 0; i < 48; i++) System.out.printf("%02X ", data[offset + i] & 0xFF);
                    System.out.println();
                }
            }

            // Try to find page map: try reading compressed data at various post-palette offsets
            System.out.println("\n  Trying LZ77 decompression at various post-palette offsets:");
            int[] tryOffsets = {0x780, 0x800, 0x880, 0x900, 0xA00, 0xC00, 0x1000, 0x2000, 0x4000, 0x8000, 0x10000};
            int[] tryCompSizes = {64, 128, 256, 512, 768, 1024, 2048, 4096, 8192, 16384, 32768};

            outer:
            for (int offset : tryOffsets) {
                for (int compSize : tryCompSizes) {
                    if (offset + compSize >= data.length) continue;
                    try {
                        Lz77Decompressor lz = new Lz77Decompressor();
                        byte[] chunk = new byte[compSize];
                        System.arraycopy(data, offset, chunk, 0, compSize);
                        byte[] result = lz.decompress(chunk, compSize * 4);
                        if (result != null && result.length > 32 && hasValidData(result)) {
                            System.out.printf("  Offset 0x%X compSize=%d: decompressed to %d bytes%n",
                                offset, compSize, result.length);
                            System.out.printf("  First 48 bytes: ");
                            for (int i = 0; i < Math.min(48, result.length); i++)
                                System.out.printf("%02X ", result[i] & 0xFF);
                            System.out.println();
                            System.out.printf("  First 16 bytes as ASCII: ");
                            for (int i = 0; i < Math.min(16, result.length); i++)
                                System.out.printf("%c ", (result[i] >= 32 && result[i] < 127) ? result[i] : '.');
                            System.out.println();
                            continue outer;
                        }
                    } catch (Exception ignored) {}
                }
            }
        }
    }

    private static boolean hasValidData(byte[] d) {
        // More zeros than random would have
        int zeros = 0;
        for (byte b : d) if (b == 0) zeros++;
        return zeros > d.length / 20; // > 5% zeros suggests structured data
    }
}
