package run;

import io.dwg.core.io.ByteBufferBitInput;
import io.dwg.format.r2007.R2007PageMapParser;
import io.dwg.format.r2007.R2007SystemPageReader;
import io.dwg.format.r2007.R2007SectionMapParser;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.List;

/**
 * Detailed scan of R2018 file - look for compressed sections and object data
 */
public class DetailScan {
    public static void main(String[] args) throws Exception {
        String path = "samples/2018/Dynblocks.dwg";
        byte[] data = Files.readAllBytes(new File(path).toPath());
        System.out.println("File: " + data.length + " bytes (0x" + Integer.toHexString(data.length) + ")");

        // 1. Look at data after PNG (0x13BB+4 for IEND CRC)
        // IEND at 0x13AF, chunk: 4(length) + 4(type) + 0(data) + 4(CRC) = 12 bytes
        // After IEND: 0x13AF + 12 = 0x13BB
        System.out.println("\n=== Data after PNG (0x13BB-0x1500) ===");
        dumpHex(data, 0x13BB, 320);

        // 2. Look at data around section sentinel (0x212302)
        System.out.println("\n=== Data around section sentinel (0x212300-0x212400) ===");
        dumpHex(data, 0x212300, 256);

        // 3. Look at data around AcDb: (0x2136E0-0x213800)
        System.out.println("\n=== Data around 'AcDb:' (0x2136E0-0x213800) ===");
        dumpHex(data, 0x2136E0, 288);

        // 4. Look for all section sentinels (8D A1 C4 B8) - standard DWG section markers
        System.out.println("\n=== All section sentinels (8D A1 C4 B8) ===");
        int count = 0;
        for (int i = 0; i < data.length - 4; i++) {
            if ((data[i] & 0xFF) == 0x8D && (data[i+1] & 0xFF) == 0xA1 &&
                (data[i+2] & 0xFF) == 0xC4 && (data[i+3] & 0xFF) == 0xB8) {
                count++;
                System.out.printf("  [0x%X] ", i);
                // Show 16 bytes before and after
                for (int j = Math.max(0, i-4); j < Math.min(data.length, i+32); j++)
                    System.out.printf("%02X ", data[j] & 0xFF);
                System.out.println();
                if (count > 30) break;
            }
        }
        System.out.println("  Total: " + count);

        // 5. Check entropy of different regions to find compressed vs uncompressed
        System.out.println("\n=== Entropy analysis (512-byte blocks) ===");
        for (int offset = 0x13BB; offset < data.length; offset += 0x20000) {
            int blockSize = Math.min(512, data.length - offset);
            double ent = entropy(data, offset, blockSize);
            double ratio = printableRatio(data, offset, blockSize);
            System.out.printf("  0x%X: entropy=%.3f, printable=%.1f%%%n",
                offset, ent, ratio * 100);
        }

        // 6. Try reading section map at various offsets near the end
        System.out.println("\n=== Trying to parse section data at sentinel ===");
        // The sentinel at 0x212302 - let's try to understand what follows
        // In R2000 format, section sentinel (8D A1 C4 B8) is followed by section data
        // and then section end sentinel (BE 17 46 86)
        int sentinelOffset = 0x212302;
        System.out.println("Bytes around sentinel:");
        dumpHex(data, sentinelOffset, 64);

        // Try reading page map starting from various positions
        // Maybe R2010+ has page map and section map near the end
        System.out.println("\n=== Searching for page map in last 1MB ===");
        long startSearch = Math.max(0, data.length - 1024 * 1024);
        int found = 0;
        
        for (long pOffset = startSearch; pOffset < data.length - 256; pOffset += 16) {
            for (int compSize = 1024; compSize <= 32768; compSize *= 2) {
                for (int uncompSize = 4096; uncompSize <= 131072; uncompSize *= 2) {
                    if (uncompSize < compSize) continue;
                    if (pOffset + compSize >= data.length) continue;
                    try {
                        byte[] pageMap = R2007SystemPageReader.readSystemPage(
                            new ByteBufferBitInput(ByteBuffer.wrap(data)),
                            pOffset, compSize, uncompSize, 0);
                        if (pageMap != null && pageMap.length > 16) {
                            List<R2007PageMapParser.PageMapEntry> entries =
                                R2007PageMapParser.parsePageMap(pageMap);
                            if (entries != null && entries.size() >= 3 && entries.size() < 200) {
                                boolean valid = true;
                                long totalSize = 0;
                                for (R2007PageMapParser.PageMapEntry e : entries) {
                                    if (e.size <= 0 || e.size > data.length) { valid = false; break; }
                                    totalSize += e.size;
                                }
                                if (valid && totalSize > 0 && totalSize < data.length) {
                                    System.out.printf("*** PAGE MAP at 0x%X comp=%d uncomp=%d -> %d entries%n",
                                        pOffset, compSize, uncompSize, entries.size());
                                    for (int i = 0; i < Math.min(entries.size(), 10); i++) {
                                        R2007PageMapParser.PageMapEntry e = entries.get(i);
                                        System.out.printf("  [%d] pageId=%d size=%d%n", i, e.pageId, e.size);
                                    }
                                    found++;
                                    return;
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
        }
        System.out.println("  Found page maps: " + found);
    }

    private static void dumpHex(byte[] data, int offset, int length) {
        length = Math.min(length, data.length - offset);
        for (int row = 0; row * 16 < length; row++) {
            int off = offset + row * 16;
            System.out.printf("  0x%06X: ", off);
            for (int col = 0; col < 16 && off + col < data.length; col++) {
                System.out.printf("%02X ", data[off + col] & 0xFF);
            }
            System.out.print("  ");
            for (int col = 0; col < 16 && off + col < data.length; col++) {
                byte b = data[off + col];
                System.out.printf("%c", (b >= 32 && b < 127) ? b : '.');
            }
            System.out.println();
        }
    }

    private static double entropy(byte[] data, int offset, int length) {
        long[] freq = new long[256];
        for (int i = 0; i < length && offset + i < data.length; i++) {
            freq[data[offset + i] & 0xFF]++;
        }
        double ent = 0;
        for (int i = 0; i < 256; i++) {
            if (freq[i] > 0) {
                double p = (double) freq[i] / length;
                ent -= p * Math.log(p) / Math.log(2);
            }
        }
        return ent;
    }

    private static double printableRatio(byte[] data, int offset, int length) {
        int printable = 0;
        for (int i = 0; i < length && offset + i < data.length; i++) {
            byte b = data[offset + i];
            if ((b >= 32 && b < 127) || b == 0 || b == 0x0D || b == 0x0A) printable++;
        }
        return (double) printable / length;
    }
}
