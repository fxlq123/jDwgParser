package run;

import io.dwg.core.io.ByteBufferBitInput;
import io.dwg.core.util.ByteUtils;
import io.dwg.core.util.Lz77Decompressor;
import io.dwg.format.r2007.R2007PageMapParser;
import io.dwg.format.r2007.R2007SectionMapParser;
import io.dwg.format.r2007.R2007SystemPageReader;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class R2010Header {
    public static void main(String[] args) throws Exception {
        String path = "samples/2018/Dynblocks.dwg";
        File f = new File(path);
        if (!f.exists()) { System.out.println("Not found"); return; }
        byte[] data = Files.readAllBytes(f.toPath());
        System.out.println("File: " + data.length + " bytes");

        // Approach 1: Try to find page map by scanning for compressed pages
        // The 0x480 offset is standard for DWG; page map starts right after header
        System.out.println("\n=== Scanning page map from 0x480 ===");
        long pageOffset = 0x480L;

        // Try reading system page at various offsets
        // Typical page sizes: 1024, 2048, 4096 bytes compressed/uncompressed
        int[] trySizes = {512, 1024, 2048, 4096, 8192, 10000, 20000, 40000};

        for (int compSize : trySizes) {
            for (int uncompSize : new int[]{compSize * 2, compSize * 4, compSize}) {
                if (pageOffset + compSize >= data.length) continue;
                try {
                    byte[] pageData = R2007SystemPageReader.readSystemPage(
                        new ByteBufferBitInput(java.nio.ByteBuffer.wrap(data)),
                        pageOffset, compSize, uncompSize, 0);
                    if (pageData != null && pageData.length > 16) {
                        long total = 0;
                        for (byte b : pageData) total += b & 0xFF;
                        boolean hasVariety = hasStructuredData(pageData);
                        if (hasVariety) {
                            System.out.printf("  pageOffset=0x%X comp=%d uncomp=%d -> %d bytes valid%n",
                                pageOffset, compSize, uncompSize, pageData.length);
                            System.out.print("  First 48: ");
                            for (int i = 0; i < Math.min(48, pageData.length); i++)
                                System.out.printf("%02X ", pageData[i] & 0xFF);
                            System.out.println();

                            // Try as page map
                            try {
                                List<R2007PageMapParser.PageMapEntry> entries =
                                    R2007PageMapParser.parsePageMap(pageData);
                                if (entries != null && entries.size() > 0) {
                                    System.out.printf("  Page map entries: %d%n", entries.size());
                                    long totalSize = 0;
                                    for (R2007PageMapParser.PageMapEntry e : entries) {
                                        totalSize += e.size;
                                    }
                                    System.out.printf("  First: pageId=%d size=%d, Total: %d%n",
                                        entries.get(0).pageId, entries.get(0).size, totalSize);

                                    // Try to find section map by finding a page with specific size
                                    for (R2007PageMapParser.PageMapEntry e : entries) {
                                        if (e.size > 0 && e.size < 100000) {
                                            // Try reading this page
                                            long sectionMapFileOffset = 0x480L + pageOffset - 0x480L + 0 /* calc from pages */;
                                        }
                                    }
                                    break;
                                }
                            } catch (Exception ex) {}
                        }
                    }
                } catch (Exception ignored) {}
            }
        }

        // Approach 2: Scan raw bytes at 0x480, 0x580, 0x680, etc. looking for section map pattern
        System.out.println("\n=== Scanning at various offsets for section map ===");
        for (int offset : new int[]{0x480, 0x488, 0x490, 0x4A0, 0x500, 0x580, 0x600, 0x700, 0x800, 0x1000}) {
            System.out.printf("  At 0x%X: ", offset);
            for (int i = 0; i < 32; i++) {
                System.out.printf("%02X ", data[offset + i] & 0xFF);
            }
            System.out.println();
        }

        // Approach 3: Find "AcDb:" strings in the file to locate section map
        System.out.println("\n=== Scanning for 'AcDb:' strings ===");
        byte[] pattern = {0x41, 0x00, 0x63, 0x00, 0x44, 0x00, 0x62, 0x00, 0x3A, 0x00}; // "AcDb:" in UTF-16LE
        for (int i = 0; i < data.length - pattern.length; i++) {
            boolean match = true;
            for (int j = 0; j < pattern.length; j++) {
                if ((data[i + j] & 0xFF) != pattern[j]) { match = false; break; }
            }
            if (match) {
                System.out.printf("  Found 'AcDb:' at 0x%X%n", i);
                // Dump surrounding context
                int start = Math.max(0, i - 32);
                int end = Math.min(data.length, i + 128);
                System.out.printf("  Context [%X-%X]: ", start, Math.min(start + 64, end));
                for (int k = start; k < Math.min(start + 64, end); k++) {
                    System.out.printf("%02X ", data[k] & 0xFF);
                }
                System.out.println();
                break;
            }
        }

        // Also try ASCII "AcDb:"
        byte[] patternAscii = {0x41, 0x63, 0x44, 0x62, 0x3A}; // "AcDb:" in ASCII
        int count = 0;
        System.out.println("\n=== Scanning for ASCII 'AcDb:' ===");
        for (int i = 0; i < data.length - patternAscii.length; i++) {
            boolean match = true;
            for (int j = 0; j < patternAscii.length; j++) {
                if ((data[i + j] & 0xFF) != patternAscii[j]) { match = false; break; }
            }
            if (match) {
                count++;
                if (count <= 10) {
                    System.out.printf("  [%d] Found at 0x%X%n", count, i);
                }
                i += patternAscii.length - 1;
            }
        }
        System.out.println("  Total: " + count + " occurrences");

        // Approach 4: Interpret data at 0x120 as plain header
        System.out.println("\n=== Plain data at 0x120 (try as header) ===");
        int plainOff = 0x120;
        // Find strings and numbers
        for (int i = plainOff; i < Math.min(plainOff + 512, data.length - 8); i += 4) {
            long v = ByteUtils.readLE64(data, i);
            if (v > 0 && v < 10000000 && (v % 4 == 0 || v % 8 == 0)) {
                System.out.printf("  [%04X] LE64 = %d (0x%X)%n", i, v, v);
            }
        }
    }

    private static boolean hasStructuredData(byte[] d) {
        // Check for 0x00 bytes and non-random patterns
        int zeros = 0;
        for (byte b : d) if (b == 0) zeros++;
        if (zeros > d.length / 10 && zeros < d.length / 2) return true;

        // Check for repeated "01 00 00 00" pattern
        int repeats = 0;
        for (int i = 0; i < d.length - 4; i += 4) {
            if (d[i] == 1 && d[i+1] == 0 && d[i+2] == 0 && d[i+3] == 0) repeats++;
        }
        return repeats >= 2;
    }
}
