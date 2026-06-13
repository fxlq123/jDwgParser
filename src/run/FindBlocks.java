package run;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Try to decode R2018 object data by looking for BLOCK-related sections
 */
public class FindBlocks {
    
    static class Section {
        int headerOffset;
        long dataOffset;
        String name;
    }
    
    public static void main(String[] args) throws Exception {
        String path = "samples/2018/Dynblocks.dwg";
        byte[] data = Files.readAllBytes(Paths.get(path));
        
        // Find all section headers
        List<Section> sections = new ArrayList<>();
        Map<Long, String> sectionNameMap = new HashMap<>();
        
        for (int offset = 0x100; offset < data.length - 32; offset += 0x10) {
            long d0 = readLE32(data, offset);
            if (d0 < 0 || d0 > data.length) continue;
            boolean valid = true;
            for (int i = 1; i <= 5; i++) {
                long dv = readLE32(data, offset + i * 4);
                int highByte = (int)((dv >> 24) & 0xFF);
                if (highByte != 0x41) { valid = false; break; }
            }
            if (!valid) continue;
            Section s = new Section();
            s.headerOffset = offset;
            s.dataOffset = d0;
            
            // Try to extract name from header
            String name = extractName(data, offset);
            s.name = name;
            
            sections.add(s);
            
            // Map data offset to name
            if (!name.isEmpty()) {
                sectionNameMap.put(d0, name);
            }
        }
        
        System.out.println("Found " + sections.size() + " sections");
        
        // Print all with names
        for (int i = 0; i < sections.size(); i++) {
            Section s = sections.get(i);
            System.out.printf("[%d] header=0x%X data=0x%X '%s'%n",
                i, s.headerOffset, s.dataOffset, s.name);
        }
        
        // Now look at the section data for BLOCK patterns
        System.out.println("\n=== Looking at section data patterns ===");
        
        // Looking at the data at 0x74290 in detail:
        System.out.println("\n--- Section 2 data at 0x74290 (AppInfoDataList) 400 bytes:");
        dumpDetailed(data, 0x74290, 512);
        
        // Looking for BLOCK object patterns
        // Looking at section 0 (0x75650)
        System.out.println("\n--- Section 0 data at 0x75650:");
        dumpDetailed(data, 0x75650, 512);
        
        // Let me try to interpret the object data format
        // Pattern 31 3A = "1:" at 0x74290
        System.out.println("\n=== Looking for BLOCK definition sections ===");
        for (Section s : sections) {
            // Check data at section data offset
            if (s.dataOffset > 0 && s.dataOffset + 16 < data.length) {
                // Look for 0x8001 or similar codes at start
                int b0 = data[(int)s.dataOffset] & 0xFF;
                int b1 = data[(int)s.dataOffset + 1] & 0xFF;
                // Check for ASCII at start
                if ((char)b0 == ':' || (char)b0 == 'A' ||
                    (b0 >= 0x80 && b1 <= 0x0F)) {
                    System.out.printf("Section at 0x%X data=0x%X starts with: ",
                        s.headerOffset, s.dataOffset);
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < 16; i++) {
                        int b = data[(int)s.dataOffset + i] & 0xFF;
                        if (b >= 32 && b < 127) sb.append((char)b);
                        else sb.append('.');
                    }
                    System.out.println(sb.toString());
                }
            }
        }
        
        // Let me try to find blocks by examining the block reference section data
        // Let me check if the 5 handle references in section headers point to other sections
        System.out.println("\n=== Checking handle references ===");
        for (int i = 0; i < Math.min(10, sections.size()); i++) {
            Section s = sections.get(i);
            System.out.printf("Section %d (0x%X):%n", i, s.headerOffset);
            StringBuilder sb = new StringBuilder("  Handles: ");
            for (int j = 0; j < 5; j++) {
                long h = readLE32(data, s.headerOffset + 4 + j * 4);
                sb.append("0x").append(Long.toHexString(h)).append(" ");
            }
            System.out.println(sb);
        }
        
        // Try to decode section data - look for "AcDb block table
        // Looking for DWG object codes
        // Check: 0x0A, 0x00 etc.
        System.out.println("\n=== Looking at section data structure ===");
        for (int i = 0; i < sections.size(); i++) {
            Section s = sections.get(i);
            if (s.dataOffset > 0 && s.dataOffset + 64 < data.length) {
                // Check if first 32 bytes look like DWG object data
                byte[] sectData = new byte[64];
                System.arraycopy(data, (int)s.dataOffset, sectData, 0, 64);
                // Count number of non-zero bytes
                int nonzero = 0;
                for (byte b : sectData) if (b != 0) nonzero++;
                if (nonzero > 40) {
                    System.out.printf("Section %d (data=0x%X): %s%n",
                        i, s.dataOffset,
                        hexAndAscii(data, (int)s.dataOffset, 48));
                }
            }
        }
    }
    
    static String extractName(byte[] data, int offset) {
        // Look for text patterns after the 8 dwords (32 bytes)
        int searchStart = offset + 32;
        int searchEnd = Math.min(data.length, offset + 256);
        
        for (int pos = searchStart; pos < searchEnd - 16; pos++) {
            // Try "03 00 00 00" pattern
            int count = (int)readLE32(data, pos);
            if (count >= 1 && count <= 10) {
                int len16 = (int)readLE16(data, pos + 4);
                if (len16 > 0 && len16 <= 64 && pos + 6 + len16 * 2 < data.length) {
                    StringBuilder sb = new StringBuilder();
                    boolean valid = true;
                    for (int i = 0; i < len16; i++) {
                        if (data[pos + 6 + i * 2 + 1] != 0) { valid = false; break; }
                        char c = (char)(data[pos + 6 + i * 2] & 0xFF);
                        if (c >= 32 && c < 127) sb.append(c);
                        else if (c != 0) { valid = false; break; }
                    }
                    if (valid && sb.length() >= 3) return sb.toString();
                }
            }
            // Try "07 00" UTF-16LE pattern
            int byte0 = data[pos] & 0xFF;
            int byte1 = data[pos + 1] & 0xFF;
            if (byte0 >= 1 && byte0 <= 16 && byte1 == 0) {
                int len = byte0;
                StringBuilder sb = new StringBuilder();
                boolean valid = true;
                for (int i = 0; i < len && pos + 2 + i * 2 < data.length; i++) {
                    if (data[pos + 2 + i * 2 + 1] != 0) { valid = false; break; }
                    char c = (char)(data[pos + 2 + i * 2] & 0xFF);
                    if (c >= 32 && c < 127) sb.append(c);
                    else if (c != 0) { valid = false; break; }
                }
                if (valid && sb.length() >= 3) return sb.toString();
            }
        }
        return "";
    }
    
    static String hexAndAscii(byte[] data, int offset, int length) {
        StringBuilder hex = new StringBuilder();
        StringBuilder ascii = new StringBuilder();
        for (int i = 0; i < length && offset + i < data.length; i++) {
            int b = data[offset + i] & 0xFF;
            hex.append(String.format("%02X ", b));
            ascii.append((b >= 32 && b < 127) ? (char)b : '.');
        }
        return hex.toString() + " | " + ascii.toString();
    }
    
    static void dumpDetailed(byte[] data, int offset, int length) {
        for (int row = 0; row * 16 < length; row++) {
            int off = offset + row * 16;
            System.out.printf("  0x%05X: ", off);
            StringBuilder sb = new StringBuilder();
            StringBuilder ascii = new StringBuilder();
            for (int col = 0; col < 16 && off + col < data.length; col++) {
                int b = data[off + col] & 0xFF;
                sb.append(String.format("%02X ", b));
                ascii.append((b >= 32 && b < 127) ? (char)b : '.');
            }
            System.out.printf("%-48s | %s%n", sb, ascii);
        }
    }
    
    static long readLE32(byte[] data, int offset) {
        return ((long)(data[offset] & 0xFF)) | 
               ((long)(data[offset+1] & 0xFF) << 8) | 
               ((long)(data[offset+2] & 0xFF) << 16) | 
               ((long)(data[offset+3] & 0xFF) << 24);
    }
    
    static int readLE16(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset+1] & 0xFF) << 8);
    }
}
