package run;

import io.dwg.core.io.ByteBufferBitInput;
import io.dwg.core.io.SectionInputStream;
import io.dwg.format.r2007.R2007FileStructureHandler;
import io.dwg.format.r2007.R2007PageMapParser;
import io.dwg.format.r2007.R2007SectionMapParser;
import io.dwg.format.r2007.R2007SystemPageReader;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Try to read R2018 file using R2007-style page/section map reading
 * but with guessed parameters since the RS header decoding fails
 */
public class R2010Manual {
    public static void main(String[] args) throws Exception {
        String path = "samples/2018/Dynblocks.dwg";
        byte[] data = Files.readAllBytes(new File(path).toPath());
        System.out.println("File: " + data.length + " bytes (0x" +
            Long.toHexString(data.length) + ")");

        // Key observation: the color palette is 768 bytes at 0x480
        // So the page map likely starts at 0x480 + 0x300 = 0x780
        //
        // For R2007: pageMapOffset=0, sizeComp=112, sizeUncomp=320
        // For R2018: pageMapOffset should point to after the palette

        // Try various page map offsets and sizes
        int[] pageOffsets = {0x300, 0x380, 0x400, 0x500, 0x600, 0x700, 0x800, 0x1000, 0x1800, 0x2000, 0x4000, 0x8000};
        int[] pageComp = {128, 256, 512, 768, 1024, 1536, 2048, 4096, 8192, 16384, 32768};
        int[] pageUncomp = {1024, 2048, 4096, 8192, 16384, 32768, 65536, 131072};
        int[] corrections = {0, 1, 2, 4, 8, 16, 32, 64, 128};

        System.out.println("\n=== Searching for page map ===");

        int found = 0;
        for (int pOffset : pageOffsets) {
            for (int pComp : pageComp) {
                if (0x480 + pOffset + pComp >= data.length) continue;
                for (int pUncomp : pageUncomp) {
                    if (pUncomp < pComp) continue;
                    for (int corr : corrections) {
                        try {
                            byte[] pageMapData = R2007SystemPageReader.readSystemPage(
                                new ByteBufferBitInput(ByteBuffer.wrap(data)),
                                0x480L + pOffset, pComp, pUncomp, corr);
                            if (pageMapData == null || pageMapData.length < 16) continue;

                            List<R2007PageMapParser.PageMapEntry> entries =
                                R2007PageMapParser.parsePageMap(pageMapData);
                            if (entries == null || entries.size() < 3 || entries.size() > 500) continue;

                            // Validate entries
                            boolean valid = true;
                            long totalSize = 0;
                            for (R2007PageMapParser.PageMapEntry e : entries) {
                                if (e.size <= 0 || e.size > data.length / 2) { valid = false; break; }
                                if (e.pageId < 0 || e.pageId > entries.size() * 10) { valid = false; break; }
                                totalSize += e.size;
                            }
                            if (!valid || totalSize <= 0 || totalSize > data.length) continue;

                            // Try to find section map - iterate through entries
                            for (R2007PageMapParser.PageMapEntry secEntry : entries) {
                                // Try this entry as section map
                                long secFileOffset = 0x480L + pOffset + totalPageOffsetBefore(entries, secEntry.pageId);
                                // Actually, pages are sequential after the page map
                                // Let's try: pages start at pageMapOffset + pageMapSizeComp (round up to page boundary)
                                continue;
                            }

                            // Simpler: try each entry as section map
                            for (int i = 0; i < entries.size(); i++) {
                                R2007PageMapParser.PageMapEntry e = entries.get(i);
                                // Try reading this page as section map
                                long pageStart = 0x480L + pOffset + pComp; // right after page map
                                // Actually need to accumulate page offsets
                                long offset = pageStart;
                                for (int j = 0; j < i; j++) {
                                    offset += entries.get(j).size;
                                }
                                // Align to 16-byte boundary
                                offset = ((offset + 15) / 16) * 16;

                                // Try reading the page
                                try {
                                    byte[] sectionPage = R2007SystemPageReader.readSystemPage(
                                        new ByteBufferBitInput(ByteBuffer.wrap(data)),
                                        offset, e.size, e.size * 4, corr);
                                    if (sectionPage != null && sectionPage.length > 32) {
                                        // Try as section map
                                        List<R2007SectionMapParser.SectionMapEntry> sections =
                                            R2007SectionMapParser.parseSectionMap(sectionPage);
                                        if (sections != null && sections.size() >= 3) {
                                            System.out.printf("*** SECTION MAP FOUND: pOffset=0x%X pComp=%d pUncomp=%d corr=%d%n",
                                                pOffset, pComp, pUncomp, corr);
                                            System.out.printf("  Page map entries: %d, section map at page #%d (%d sections)%n",
                                                entries.size(), i, sections.size());
                                            for (R2007SectionMapParser.SectionMapEntry s : sections) {
                                                System.out.printf("    Section: '%s' dataSize=%d pages=%d%n",
                                                    s.sectionName, s.dataSize, s.numPages);
                                            }
                                            found++;
                                            break;
                                        }
                                    }
                                } catch (Exception ignored) {}
                            }
                            if (found > 0) break;
                        } catch (Exception ignored) {}
                    }
                    if (found > 0) break;
                }
                if (found > 0) break;
            }
            if (found > 0) break;
        }

        if (found == 0) {
            System.out.println("\n=== Page map not found. Trying alternative approaches ===");

            // Approach: try reading raw bytes at 0x480 + various offsets, LZ77 decompress
            System.out.println("\n--- LZ77 with large chunks ---");
            for (int offset : new int[]{0x480, 0x600, 0x780, 0x800, 0x1000, 0x1800, 0x2000, 0x3000}) {
                for (int size : new int[]{4096, 8192, 16384, 32768, 65536}) {
                    if (offset + size >= data.length) continue;
                    try {
                        byte[] chunk = new byte[size];
                        System.arraycopy(data, offset, chunk, 0, size);
                        io.dwg.core.util.Lz77Decompressor lz =
                            new io.dwg.core.util.Lz77Decompressor();
                        byte[] result = lz.decompress(chunk, size * 8);
                        if (result != null && result.length > 256) {
                            int zeros = 0;
                            for (byte b : result) if (b == 0) zeros++;
                            if (zeros > result.length / 10 && zeros < result.length * 0.7) {
                                System.out.printf("  offset=0x%X size=%d -> result=%d bytes (zeros=%d=%.1f%%)%n",
                                    offset, size, result.length, zeros, 100.0*zeros/result.length);
                                System.out.print("  First 64 bytes: ");
                                for (int i = 0; i < Math.min(64, result.length); i++)
                                    System.out.printf("%02X ", result[i] & 0xFF);
                                System.out.println();
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
        }

        System.out.println("\n=== Done. Found: " + found + " ===");
    }

    private static long totalPageOffsetBefore(List<R2007PageMapParser.PageMapEntry> entries, int pageId) {
        long offset = 0;
        for (R2007PageMapParser.PageMapEntry e : entries) {
            if (e.pageId < pageId) offset += e.size;
        }
        return offset;
    }
}
