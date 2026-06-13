package run;

import io.dwg.core.io.ByteBufferBitInput;
import io.dwg.core.util.Lz77Decompressor;
import io.dwg.format.r2007.R2007PageMapParser;
import io.dwg.format.r2007.R2007SectionMapParser;
import io.dwg.format.r2007.R2007SystemPageReader;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.List;

public class PostPaletteMap {
    public static void main(String[] args) throws Exception {
        String path = "samples/2018/Dynblocks.dwg";
        byte[] data = Files.readAllBytes(new File(path).toPath());
        System.out.println("File: " + data.length + " bytes");

        // Try reading page map at various offsets after the palette
        int[] offsets = {0x780, 0x790, 0x7A0, 0x7C0, 0x800, 0x880, 0x900, 0xA00, 0xC00, 0x1000, 0x2000, 0x3000, 0x4000, 0x5000, 0x6000, 0x7000, 0x8000};
        int[] compSizes = {128, 256, 512, 768, 1024, 1536, 2048, 4096, 8192, 16384, 32768, 65536, 131072, 262144};
        int[] uncompSizes = {512, 1024, 2048, 4096, 8192, 16384, 32768, 65536, 131072, 262144, 524288};
        int[] corrections = {0, 1, 2, 4, 8, 16, 32, 64, 128, 256};

        System.out.println("\n=== Trying system page reader at various offsets ===");
        for (int offset : offsets) {
            for (int comp : compSizes) {
                if (offset + comp >= data.length) continue;
                for (int uncomp : uncompSizes) {
                    if (uncomp < comp) continue;
                    for (int corr : corrections) {
                        try {
                            byte[] result = R2007SystemPageReader.readSystemPage(
                                new ByteBufferBitInput(ByteBuffer.wrap(data)),
                                offset, comp, uncomp, corr);
                            if (result != null && result.length > 0) {
                                // Check if result looks like page map (has structured data with reasonable entries)
                                int zeros = 0;
                                for (byte b : result) if (b == 0) zeros++;
                                int highBytes = 0;
                                for (byte b : result) if ((b & 0xFF) >= 0x80) highBytes++;

                                if (zeros > 5 && zeros < result.length * 0.8 && highBytes < result.length * 0.3) {
                                    // Could be valid page map
                                    List<R2007PageMapParser.PageMapEntry> entries =
                                        R2007PageMapParser.parsePageMap(result);
                                    if (entries != null && entries.size() > 0) {
                                        // Validate entries
                                        boolean valid = true;
                                        long totalSize = 0;
                                        for (R2007PageMapParser.PageMapEntry e : entries) {
                                            if (e.size <= 0 || e.size > data.length / 2) { valid = false; break; }
                                            if (e.pageId < 0 || e.pageId > entries.size() * 10) { valid = false; break; }
                                            totalSize += e.size;
                                        }
                                        if (valid && totalSize > 0 && totalSize < data.length) {
                                            System.out.printf("*** VALID: offset=0x%X comp=%d uncomp=%d corr=%d entries=%d total=%d%n",
                                                offset, comp, uncomp, corr, entries.size(), totalSize);
                                            for (int i = 0; i < Math.min(entries.size(), 8); i++) {
                                                System.out.printf("    [%d] pageId=%d size=%d%n", i, entries.get(i).pageId, entries.get(i).size);
                                            }
                                            return;
                                        }
                                    }
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
        }
        System.out.println("No valid page map found with brute force");

        // Alternative: Try plain LZ77 decompress at offset 0x780
        System.out.println("\n=== Plain LZ77 at 0x780 ===");
        for (int compSize : new int[]{256, 512, 1024, 2048, 4096, 8192, 16384}) {
            if (0x780 + compSize >= data.length) continue;
            byte[] chunk = new byte[compSize];
            System.arraycopy(data, 0x780, chunk, 0, compSize);
            try {
                Lz77Decompressor lz = new Lz77Decompressor();
                byte[] result = lz.decompress(chunk, compSize * 4);
                if (result != null && result.length > 100) {
                    int zeros = 0;
                    for (byte b : result) if (b == 0) zeros++;
                    System.out.printf("  LZ77 0x780 comp=%d -> %d bytes (zeros: %d/%d = %.1f%%)%n",
                        compSize, result.length, zeros, result.length, 100.0*zeros/result.length);
                    System.out.print("  First 64 bytes: ");
                    for (int i = 0; i < Math.min(64, result.length); i++)
                        System.out.printf("%02X ", result[i] & 0xFF);
                    System.out.println();
                }
            } catch (Exception ignored) {}
        }

        // Dump raw bytes at 0x780
        System.out.println("\n=== Raw bytes 0x780-0x980 ===");
        for (int row = 0; row < 32; row++) {
            int offset = 0x780 + row * 16;
            System.out.printf("  0x%04X: ", offset);
            for (int col = 0; col < 16; col++) {
                System.out.printf("%02X ", data[offset + col] & 0xFF);
            }
            System.out.println();
        }
    }
}
