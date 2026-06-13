package run;

import io.dwg.format.r2007.R2007SectionMapParser;
import io.dwg.format.r2007.R2007PageMapParser;
import io.dwg.core.io.ByteBufferBitInput;
import io.dwg.format.r2007.R2007SystemPageReader;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

/**
 * Try parsing R2018 file section map without Reed-Solomon decoding
 */
public class TryParseSections {
    public static void main(String[] args) throws Exception {
        String path = "samples/2018/Dynblocks.dwg";
        byte[] data = Files.readAllBytes(new File(path).toPath());
        System.out.println("File: " + data.length + " bytes");

        // Try parsing section map at different offsets:
        // 1. Try offset 0x100 (after 0x80 encoded region)
        tryParseSectionMap(data, 0x100, "0x100 (after encoded header)");
        tryParseSectionMap(data, 0x1A0, "0x1A0 (second structured block)");
        tryParseSectionMap(data, 0x80, "0x80 (encoded header region)");
        
        // 2. Try page map at 0x100
        tryParsePageMap(data, 0x100, "0x100 as page map");
        tryParsePageMap(data, 0x1A0, "0x1A0 as page map");
        
        // 3. Show the data at 0x100 with LE32 interpretation
        dumpLE32(data, 0x100, 32, "0x100 header block 1");
        dumpLE32(data, 0x1A0, 32, "0x1A0 header block 2");
        
        // 4. Check if the first dword is actually a section data offset
        // Try reading the data at those offsets to see if it looks like section data
        long offset1 = readLE32(data, 0x100);
        long offset2 = readLE32(data, 0x1A0);
        System.out.printf("\nBlock1 first LE32: 0x%X (%d)\n", offset1, offset1);
        System.out.printf("Block2 first LE32: 0x%X (%d)\n", offset2, offset2);
        
        if (offset1 > 0 && offset1 < data.length) {
            dumpBytes(data, (int) offset1, 64, "Data at block1 offset");
        }
        if (offset2 > 0 && offset2 < data.length) {
            dumpBytes(data, (int) offset2, 64, "Data at block2 offset");
        }
        
        // 5. Let's look at offset 0x15C0 which contained "AppInfoData"
        System.out.println("\n=== Data at 0x15C0 ===");
        dumpBytes(data, 0x15C0, 128, "0x15C0 (AppInfoData section)");
        dumpLE32(data, 0x15C0, 32, "0x15C0 as LE32");
        
        // 6. Check section at 0x1F00
        dumpBytes(data, 0x1F00, 128, "0x1F00");
    }
    
    static void tryParseSectionMap(byte[] data, int offset, String label) {
        System.out.println("\n=== Try section map at " + label + " ===");
        try {
            int size = Math.min(1024, data.length - offset);
            byte[] slice = new byte[size];
            System.arraycopy(data, offset, slice, 0, size);
            List<R2007SectionMapParser.SectionMapEntry> entries = 
                R2007SectionMapParser.parseSectionMap(slice);
            System.out.println("  Entries found: " + entries.size());
            for (R2007SectionMapParser.SectionMapEntry e : entries) {
                System.out.println("    " + e.toString());
            }
        } catch (Exception e) {
            System.out.println("  Error: " + e.getMessage());
        }
    }
    
    static void tryParsePageMap(byte[] data, int offset, String label) {
        System.out.println("\n=== Try page map at " + label + " ===");
        try {
            int size = Math.min(1024, data.length - offset);
            byte[] slice = new byte[size];
            System.arraycopy(data, offset, slice, 0, size);
            List<R2007PageMapParser.PageMapEntry> entries = 
                R2007PageMapParser.parsePageMap(slice);
            System.out.println("  Entries: " + entries.size());
            for (int i = 0; i < Math.min(5, entries.size()); i++) {
                System.out.println("    [" + i + "] pageId=" + entries.get(i).pageId + 
                    " size=" + entries.get(i).size);
            }
        } catch (Exception e) {
            System.out.println("  Error: " + e.getMessage());
        }
    }
    
    static void dumpLE32(byte[] data, int offset, int count, String label) {
        System.out.println("\n=== " + label + " (LE32) ===");
        for (int i = 0; i < count && offset + i * 4 + 4 <= data.length; i++) {
            long val = readLE32(data, offset + i * 4);
            byte[] ascii = new byte[4];
            ascii[0] = data[offset + i * 4];
            ascii[1] = data[offset + i * 4 + 1];
            ascii[2] = data[offset + i * 4 + 2];
            ascii[3] = data[offset + i * 4 + 3];
            String asciiStr = "";
            for (byte b : ascii) {
                asciiStr += (b >= 32 && b < 127) ? (char) b : '.';
            }
            System.out.printf("  [%d] 0x%08X (%d) '%s'\n", i, val, val, asciiStr);
            if (val == 0) break;
        }
    }
    
    static long readLE32(byte[] data, int offset) {
        return ((long)(data[offset] & 0xFF)) | 
               ((long)(data[offset+1] & 0xFF) << 8) | 
               ((long)(data[offset+2] & 0xFF) << 16) | 
               ((long)(data[offset+3] & 0xFF) << 24);
    }
    
    static void dumpBytes(byte[] data, int offset, int length, String label) {
        System.out.println("\n=== " + label + " ===");
        for (int row = 0; row * 16 < length; row++) {
            int off = offset + row * 16;
            System.out.printf("  0x%04X: ", off);
            for (int col = 0; col < 16 && off + col < data.length; col++) {
                System.out.printf("%02X ", data[off + col] & 0xFF);
            }
            System.out.print("  ");
            for (int col = 0; col < 16 && off + col < data.length; col++) {
                byte b = data[off + col];
                System.out.printf("%c", (b >= 32 && b < 127) ? (char) b : '.');
            }
            System.out.println();
        }
    }
}
