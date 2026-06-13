package run;

import io.dwg.core.io.ByteBufferBitInput;
import io.dwg.format.r2007.R2007FileHeader;
import io.dwg.format.r2007.R2007FileStructureHandler;
import io.dwg.format.r2007.R2007PageMapParser;
import io.dwg.format.r2007.R2007SystemPageReader;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.List;

/**
 * Scan the R2018 file for DWG section signatures and patterns
 */
public class FileScan {
    public static void main(String[] args) throws Exception {
        String path = "samples/2018/Dynblocks.dwg";
        byte[] data = Files.readAllBytes(new File(path).toPath());
        System.out.println("File: " + data.length + " bytes");

        // Scan for known patterns
        System.out.println("\n=== Scanning for known patterns ===");
        // 1. PNG signature: 89 50 4E 47 0D 0A 1A 0A
        for (int i = 0; i < data.length - 8; i++) {
            if (data[i] == (byte)0x89 && data[i+1] == (byte)0x50 && data[i+2] == (byte)0x4E && data[i+3] == (byte)0x47) {
                if (data[i+4] == 0x0D && data[i+5] == 0x0A && data[i+6] == 0x1A && data[i+7] == 0x0A) {
                    System.out.printf("  PNG at 0x%X%n", i);
                    // Parse PNG structure
                    int offset = i + 8;
                    while (offset < data.length - 12) {
                        int length = ((data[offset] & 0xFF) << 24) | ((data[offset+1] & 0xFF) << 16) |
                                     ((data[offset+2] & 0xFF) << 8) | (data[offset+3] & 0xFF);
                        byte[] chunkType = new byte[4];
                        System.arraycopy(data, offset+4, chunkType, 0, 4);
                        String type = new String(chunkType);
                        System.out.printf("    -> chunk '%s' at 0x%X, length=%d%n", type, offset+4, length);
                        if (type.equals("IEND")) break;
                        offset += 4 + 4 + length + 4; // length + type + data + CRC
                        if (length > data.length / 10) break;
                    }
                }
            }
        }

        // 2. DWG section start sentinel (8D A1 C4 B8)  
        for (int i = 0; i < data.length - 4; i++) {
            if ((data[i] & 0xFF) == 0x8D && (data[i+1] & 0xFF) == 0xA1 &&
                (data[i+2] & 0xFF) == 0xC4 && (data[i+3] & 0xFF) == 0xB8) {
                System.out.printf("  Section sentinel 8D A1 C4 B8 at 0x%X%n", i);
                if (i > 5) {
                    System.out.print("  Context: ");
                    for (int j = Math.max(0, i-8); j < Math.min(data.length, i+24); j++)
                        System.out.printf("%02X ", data[j] & 0xFF);
                    System.out.println();
                }
            }
        }

        // 3. Scan for ASCII section names like "AcDb:"
        byte[] acdb = "AcDb:".getBytes();
        int count = 0;
        for (int i = 0; i < data.length - acdb.length; i++) {
            boolean match = true;
            for (int j = 0; j < acdb.length; j++) {
                if ((data[i+j] & 0xFF) != acdb[j]) { match = false; break; }
            }
            if (match) {
                count++;
                if (count <= 20) {
                    System.out.printf("  'AcDb:' at 0x%X%n", i);
                    System.out.print("    Context: ");
                    for (int j = Math.max(0, i-8); j < Math.min(data.length, i+48); j++)
                        System.out.printf("%02X ", data[j] & 0xFF);
                    System.out.println();
                }
            }
        }
        System.out.println("  Total 'AcDb:' occurrences: " + count);

        // 4. Scan for UTF-16LE section names
        System.out.println("\n=== Scanning for UTF-16LE 'AcDb:' ===");
        int count16 = 0;
        for (int i = 0; i < data.length - 12; i += 1) {
            if (data[i] == 'A' && data[i+1] == 0 && data[i+2] == 'c' && data[i+3] == 0 &&
                data[i+4] == 'D' && data[i+5] == 0 && data[i+6] == 'b' && data[i+7] == 0 &&
                data[i+8] == ':' && data[i+9] == 0) {
                count16++;
                if (count16 <= 10) {
                    System.out.printf("  UTF-16LE 'AcDb:' at 0x%X%n", i);
                    StringBuilder sb = new StringBuilder();
                    for (int j = i; j < Math.min(data.length, i+64); j += 2) {
                        if (data[j] >= 32 && data[j] < 127) sb.append((char)data[j]);
                        else if (data[j] == 0) sb.append(".");
                        else sb.append("?");
                    }
                    System.out.println("    -> '" + sb + "'");
                }
            }
        }
        System.out.println("  Total UTF-16LE 'AcDb:' occurrences: " + count16);

        // 5. Dump bytes around the end of the file (might contain trailer/section info)
        System.out.println("\n=== Last 256 bytes of file ===");
        for (int row = 0; row * 16 < 256; row++) {
            int offset = data.length - 256 + row * 16;
            System.out.printf("  0x%06X: ", offset);
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

        // 6. Try reading with the R2007 handler but with header bypass
        System.out.println("\n=== Trying direct page map read at offset 0x480 ===");
        // For R2007: page map at 0x480 + pageMapOffset (usually 0). Let's try:
        // Maybe R2010+ has the same structure but different header encoding
        // Let's try to read the page map at 0x480 with various sizes
        for (int compSize : new int[]{256, 512, 1024, 2048, 4096, 8192, 16384, 32768, 65536}) {
            for (int uncompSize : new int[]{1024, 4096, 16384, 65536, 131072, 262144}) {
                if (uncompSize < compSize || 0x480 + compSize >= data.length) continue;
                try {
                    byte[] pageMap = R2007SystemPageReader.readSystemPage(
                        new ByteBufferBitInput(ByteBuffer.wrap(data)),
                        0x480L, compSize, uncompSize, 0);
                    if (pageMap != null && pageMap.length > 16) {
                        List<R2007PageMapParser.PageMapEntry> entries =
                            R2007PageMapParser.parsePageMap(pageMap);
                        if (entries != null && entries.size() >= 3 && entries.size() < 500) {
                            // Check validity
                            boolean valid = true;
                            long totalSize = 0;
                            for (R2007PageMapParser.PageMapEntry e : entries) {
                                if (e.size <= 0 || e.size > data.length) { valid = false; break; }
                                totalSize += e.size;
                            }
                            if (valid && totalSize > 0 && totalSize < data.length) {
                                System.out.printf("*** PAGE MAP FOUND: comp=%d uncomp=%d -> %d entries%n",
                                    compSize, uncompSize, entries.size());
                                for (int i = 0; i < Math.min(entries.size(), 10); i++) {
                                    R2007PageMapParser.PageMapEntry e = entries.get(i);
                                    System.out.printf("  [%d] pageId=%d size=%d%n", i, e.pageId, e.size);
                                }
                                return;
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        System.out.println("  No valid page map at 0x480");
    }
}
