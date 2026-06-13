package run;

import io.dwg.core.io.ByteBufferBitInput;
import io.dwg.format.r2007.R2007PageMapParser;
import io.dwg.format.r2007.R2007SectionMapParser;
import io.dwg.format.r2007.R2007SystemPageReader;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.List;

public class R2010PageMap {
    public static void main(String[] args) throws Exception {
        String path = "samples/2018/Dynblocks.dwg";
        byte[] data = Files.readAllBytes(new File(path).toPath());
        System.out.println("File: " + data.length + " bytes");

        // Try reading page map at various offsets with various sizes
        int[] offsets = {0x780, 0x788, 0x790, 0x7A0, 0x7C0, 0x7E0, 0x800, 0x880, 0x900, 0xA00};
        int[] compSizes = {128, 256, 512, 768, 1024, 1536, 2048, 3000, 4096, 8192, 16384, 32768};
        int[] uncompSizes = {1024, 2048, 4096, 8192, 16384, 32768, 65536, 131072};
        int[] corrections = {0, 1, 2, 4, 8, 16, 32, 64};

        int best = 0;
        int bestOffset = 0, bestComp = 0, bestUncomp = 0, bestCorr = 0;

        outer:
        for (int offset : offsets) {
            for (int comp : compSizes) {
                for (int uncomp : uncompSizes) {
                    if (uncomp < comp) continue;
                    if (offset + comp >= data.length) continue;
                    for (int corr : corrections) {
                        try {
                            byte[] result = R2007SystemPageReader.readSystemPage(
                                new ByteBufferBitInput(ByteBuffer.wrap(data)),
                                offset, comp, uncomp, corr);
                            if (result != null && result.length > 32) {
                                List<R2007PageMapParser.PageMapEntry> entries =
                                    R2007PageMapParser.parsePageMap(result);
                                if (entries != null && entries.size() > 0 && entries.size() < 500) {
                                    // Check if entries are reasonable
                                    boolean valid = true;
                                    long totalSize = 0;
                                    for (R2007PageMapParser.PageMapEntry e : entries) {
                                        if (e.size <= 0 || e.size > 1000000) { valid = false; break; }
                                        totalSize += e.size;
                                    }
                                    if (valid && totalSize > 0 && totalSize < data.length) {
                                        System.out.printf("FOUND! offset=0x%X comp=%d uncomp=%d corr=%d -> %d entries, total=%d bytes%n",
                                            offset, comp, uncomp, corr, entries.size(), totalSize);
                                        for (int i = 0; i < Math.min(entries.size(), 10); i++) {
                                            R2007PageMapParser.PageMapEntry e = entries.get(i);
                                            System.out.printf("  [%d] pageId=%d size=%d%n", i, e.pageId, e.size);
                                        }

                                        // Try to find and read section map
                                        // Section map is the page with sectionsMapId
                                        // For now, try the largest page
                                        long cumulativeOffset = 0;
                                        for (int idx = 0; idx < entries.size(); idx++) {
                                            long sectionMapFileOffset = 0x480L + offset - 0x480L + 0 /* TODO: find correct base */;
                                            // Actually the page data starts right after the page map
                                            // The page map refers to pages in the file at offset + cumulativeOffset
                                            // Need to determine: where does page data start?
                                            // For R2007: pages start at 0x480 + pageMapOffset
                                            // For R2018: pages might start at offset
                                        }
                                        break outer;
                                    }
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
        }

        // Alternative approach: scan for potential page map by looking at what readSystemPage produces
        System.out.println("\n=== Scanning large chunks ===");
        for (int offset : new int[]{0x780, 0x800, 0x1000, 0x2000}) {
            for (int comp : new int[]{2048, 4096, 8192, 16384}) {
                if (offset + comp >= data.length) continue;
                try {
                    byte[] result = R2007SystemPageReader.readSystemPage(
                        new ByteBufferBitInput(ByteBuffer.wrap(data)),
                        offset, comp, comp * 4, 0);
                    if (result != null && result.length > 100) {
                        int zeros = 0;
                        for (byte b : result) if (b == 0) zeros++;
                        if (zeros > result.length / 10 && zeros < result.length * 0.9) {
                            System.out.printf("offset=0x%X comp=%d: result=%d bytes zeros=%d (%.1f%%)%n",
                                offset, comp, result.length, zeros, 100.0*zeros/result.length);
                            System.out.print("  First 64: ");
                            for (int i = 0; i < Math.min(64, result.length); i++)
                                System.out.printf("%02X ", result[i] & 0xFF);
                            System.out.println();

                            // Try as page map
                            List<R2007PageMapParser.PageMapEntry> entries =
                                R2007PageMapParser.parsePageMap(result);
                            if (entries != null && entries.size() > 1) {
                                System.out.printf("  Page map entries: %d%n", entries.size());
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
    }
}
