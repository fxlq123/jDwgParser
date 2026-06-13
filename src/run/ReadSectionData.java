package run;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

/**
 * Read section data at offsets pointed to by section table at 0x100
 */
public class ReadSectionData {
    public static void main(String[] args) throws Exception {
        String path = "samples/2018/Dynblocks.dwg";
        byte[] data = Files.readAllBytes(new File(path).toPath());
        System.out.println("File: " + data.length + " bytes");

        // Find all section header blocks in the file
        // A section header starts with a dword that is a valid file offset
        // followed by 5 dwords that all start with 0x41XX (end with "dA" in LE)
        List<Integer> sectionHeaders = new ArrayList<>();
        for (int offset = 0x100; offset < data.length - 32; offset += 0x10) {
            // Check if this looks like a section header
            long d0 = readLE32(data, offset);
            if (d0 < 0 || d0 > data.length) continue;
            // Check if next 5 dwords all have high byte 0x41 (or similar pattern)
            boolean valid = true;
            for (int i = 1; i <= 5; i++) {
                long dv = readLE32(data, offset + i * 4);
                // Check if the high byte is 0x41 (in LE, this is byte 3)
                int highByte = (int)((dv >> 24) & 0xFF);
                if (highByte != 0x41) { valid = false; break; }
            }
            if (valid) {
                sectionHeaders.add(offset);
            }
        }
        System.out.println("Found " + sectionHeaders.size() + " section headers:");
        for (int i = 0; i < Math.min(20, sectionHeaders.size()); i++) {
            int off = sectionHeaders.get(i);
            long d0 = readLE32(data, off);
            System.out.printf("  [%d] 0x%X -> data_offset=0x%X (%d)%n", i, off, d0, d0);
        }
        
        // Read data at each section's data offset
        System.out.println("\n=== Section data at referenced offsets ===");
        for (int i = 0; i < Math.min(10, sectionHeaders.size()); i++) {
            int sh = sectionHeaders.get(i);
            long dataOffset = readLE32(data, sh);
            if (dataOffset > 0 && dataOffset < data.length - 16) {
                System.out.printf("%n--- Header at 0x%X, data at 0x%X (%d) ---%n", 
                    sh, dataOffset, dataOffset);
                dumpBytes(data, (int)dataOffset, 128);
                
                // Try to find section name in the header (it usually follows the handle refs)
                // Scan header for ASCII or UTF-16LE text
                System.out.println("  Header context (128 bytes from 0x" + Integer.toHexString(sh+32) + "):");
                dumpBytes(data, sh+32, 64);
            }
        }
        
        // Analyze the header at 0x100 more carefully - look for a list of section references
        System.out.println("\n=== Detailed analysis of 0x100 header block ===");
        System.out.println("  Size: " + (long)readLE32(data, 0x100));
        for (int i = 1; i <= 5; i++) {
            long h = readLE32(data, 0x100 + i * 4);
            System.out.printf("  Ref[%d]: 0x%X (%d)%n", i, h, h);
        }
        // After the 5 refs (at offset 0x114), there are 2 hash dwords and then data
        System.out.println("  Hash values:");
        System.out.printf("    [6]: 0x%X%n", readLE32(data, 0x118));
        System.out.printf("    [7]: 0x%X%n", readLE32(data, 0x11C));
        System.out.println("  Data after refs (starting at 0x120):");
        dumpBytes(data, 0x120, 64);
        
        // Now look at what 0x80-0xFF might be - perhaps it's the actual section map
        // in compressed form, and the data at 0x100 is ALREADY the decompressed data
        // The 0x80 region might just be the header's encoding of the same data
        
        // Check: decompress 0x80 with the system page reader (assuming it's compressed)
        System.out.println("\n=== Checking if 0x80 is compressed with same alg as sections ===");
        // Let's look at data at 0x75650 (the first section's data offset) to see compression format
        System.out.println("  Data at 0x75650 (referenced by section at 0x100):");
        dumpBytes(data, 0x75650, 128);
        
        // Is the data at 0x75650 compressed? Let me check entropy
        double e1 = entropy(Arrays.copyOfRange(data, 0x75650, Math.min(data.length, 0x75650 + 256)));
        System.out.println("  Entropy at 0x75650: " + e1);
    }
    
    static long readLE32(byte[] data, int offset) {
        return ((long)(data[offset] & 0xFF)) | 
               ((long)(data[offset+1] & 0xFF) << 8) | 
               ((long)(data[offset+2] & 0xFF) << 16) | 
               ((long)(data[offset+3] & 0xFF) << 24);
    }
    
    static void dumpBytes(byte[] data, int offset, int length) {
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
    
    static double entropy(byte[] data) {
        long[] freq = new long[256];
        for (byte b : data) freq[b & 0xFF]++;
        double ent = 0;
        for (int i = 0; i < 256; i++) {
            if (freq[i] > 0) {
                double p = (double) freq[i] / data.length;
                ent -= p * Math.log(p) / Math.log(2);
            }
        }
        return ent;
    }
}
