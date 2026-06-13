package run;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Decode R2018 section data - try to understand the object encoding format
 * and extract block/insert information
 */
public class DecodeObjects {
    
    static class Section {
        int headerOffset;
        long dataOffset;
        List<Long> handles = new ArrayList<>();
        long hash1, hash2;
        String name = "";
        int count;
    }
    
    static class DwgObject {
        long handle;
        int type;
        String typeName;
        String name;
        long blockHeader;
        long blockDef;
        String blockName;
        int flags;
        double x, y, z;
        double scaleX, scaleY, scaleZ;
        double rotation;
        List<double[]> points = new ArrayList<>();
        String rawData;
    }
    
    public static void main(String[] args) throws Exception {
        String path = "samples/2018/Dynblocks.dwg";
        byte[] data = Files.readAllBytes(Paths.get(path));
        
        // Find all sections
        List<Section> sections = findSections(data);
        
        System.out.println("Found " + sections.size() + " sections");
        
        // Print sections with names
        for (int i = 0; i < sections.size(); i++) {
            Section s = sections.get(i);
            if (!s.name.isEmpty()) {
                System.out.printf("[%d] header=0x%X data=0x%X name='%s' count=%d%n",
                    i, s.headerOffset, s.dataOffset, s.name, s.count);
            }
        }
        
        // Now examine section data for block-related patterns
        System.out.println("\n=== Examining section data ===");
        
        // Look at section data at high offsets (which likely contain object data)
        for (int i = 0; i < sections.size(); i++) {
            Section s = sections.get(i);
            if (s.dataOffset < 0x100 || s.dataOffset >= data.length - 16) continue;
            
            // Try to detect if this section contains object data
            // Look for known object codes: BLOCK (0x02), INSERT (0x68), etc.
            // in the encoded stream
            
            int start = (int)s.dataOffset;
            int end = Math.min(data.length, start + 512);
            
            // Look for patterns that indicate object start
            byte[] sect = new byte[end - start];
            System.arraycopy(data, start, sect, 0, sect.length);
            
            // Check for ASCII text "AcDb" which indicates object class
            String ascii = extractAscii(data, start, Math.min(256, sect.length));
            if (ascii.toLowerCase().contains("acdb") || 
                ascii.toLowerCase().contains("block") ||
                ascii.toLowerCase().contains("insert")) {
                System.out.printf("Section %d (0x%X): Found object text%n  %s%n",
                    i, s.dataOffset, ascii.substring(0, Math.min(120, ascii.length())));
            }
            
            // Look for handle patterns
            // DWG objects often start with handle reference codes
            if (hasHandlePattern(data, start)) {
                System.out.printf("Section %d (0x%X): Handle pattern detected%n", i, s.dataOffset);
                dumpHex(data, start, 64);
            }
        }
        
        // Try to decode the object encoding at section 0 data
        System.out.println("\n=== Section 0 data (at 0x75650) ===");
        decodeSectionData(data, 0x75650, 256);
        
        System.out.println("\n=== Section 2 data (at 0x74290) ===");
        decodeSectionData(data, 0x74290, 256);
        
        System.out.println("\n=== Section 73 (at 0x1F5070) - near end ===");
        decodeSectionData(data, 0x1F5070, 256);
        
        // Look for "AcDbBlockTable" or similar in the whole file
        System.out.println("\n=== Searching for block-related objects ===");
        searchForBlocks(data);
        
        // Try to decode with bit-level operations (like DWG's BS/BL/BD)
        System.out.println("\n=== Bit-level decode attempt ===");
        tryBitDecode(data, 0x74290, 256);
        
        // Now try to find block by looking for block-related DXF codes
        System.out.println("\n=== Looking for INSERT entities (DXF code 66/67/68) ===");
        findInsertEntities(data);
    }
    
    static List<Section> findSections(byte[] data) {
        List<Section> sections = new ArrayList<>();
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
            
            // Extract handles
            for (int i = 0; i < 5; i++) {
                s.handles.add(readLE32(data, offset + 4 + i * 4));
            }
            
            // Extract hash
            s.hash1 = readLE32(data, offset + 24);
            s.hash2 = readLE32(data, offset + 28);
            
            // Try to find count and name in the following bytes
            for (int pos = offset + 32; pos < Math.min(data.length, offset + 256); pos++) {
                int b0 = data[pos] & 0xFF;
                int b1 = (pos + 1 < data.length) ? (data[pos+1] & 0xFF) : 0;
                if (b0 > 0 && b0 <= 32 && b1 == 0) {
                    // Possible UTF-16LE text length marker
                    int len = b0;
                    StringBuilder sb = new StringBuilder();
                    boolean validText = true;
                    for (int i = 0; i < len && pos + 2 + i * 2 + 1 < data.length; i++) {
                        if (data[pos + 2 + i * 2 + 1] != 0) { validText = false; break; }
                        char c = (char)(data[pos + 2 + i * 2] & 0xFF);
                        if (c >= 32 && c < 127) sb.append(c);
                        else if (c != 0) { validText = false; break; }
                    }
                    if (validText && sb.length() >= 2) {
                        s.name = sb.toString();
                        if (pos - 4 >= offset + 32) {
                            s.count = (int)readLE32(data, pos - 4);
                        }
                        break;
                    }
                }
            }
            
            sections.add(s);
        }
        return sections;
    }
    
    static void decodeSectionData(byte[] data, int offset, int length) {
        // Look at the raw bytes
        System.out.printf("Offset 0x%X (%d bytes):%n", offset, length);
        
        // Hex dump
        for (int row = 0; row * 16 < length; row++) {
            int off = offset + row * 16;
            if (off >= data.length) break;
            StringBuilder hex = new StringBuilder();
            StringBuilder ascii = new StringBuilder();
            for (int col = 0; col < 16 && off + col < data.length; col++) {
                int b = data[off + col] & 0xFF;
                hex.append(String.format("%02X ", b));
                ascii.append((b >= 32 && b < 127) ? (char)b : '.');
            }
            System.out.printf("  0x%05X: %-48s | %s%n", off, hex, ascii);
        }
        
        // Try to identify the encoding
        // Look for repeating codes or patterns
        int[] codes = new int[256];
        for (int i = 0; i < length && offset + i < data.length; i++) {
            codes[data[offset + i] & 0xFF]++;
        }
        System.out.println("  Most frequent bytes:");
        int[] freq = new int[256];
        for (int i = 0; i < 256; i++) freq[i] = i;
        // Sort by count (simple bubble for small array)
        for (int i = 0; i < 255; i++) {
            for (int j = i + 1; j < 256; j++) {
                if (codes[freq[j]] > codes[freq[i]]) {
                    int t = freq[i]; freq[i] = freq[j]; freq[j] = t;
                }
            }
        }
        for (int i = 0; i < 10; i++) {
            if (codes[freq[i]] > 0) {
                System.out.printf("    0x%02X ('%c'): %d times%n",
                    freq[i], (freq[i] >= 32 && freq[i] < 127) ? (char)freq[i] : '.',
                    codes[freq[i]]);
            }
        }
    }
    
    static String extractAscii(byte[] data, int offset, int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length && offset + i < data.length; i++) {
            int b = data[offset + i] & 0xFF;
            if (b >= 32 && b < 127) sb.append((char)b);
            else sb.append('.');
        }
        return sb.toString();
    }
    
    static boolean hasHandlePattern(byte[] data, int offset) {
        // Check for 0x41 high byte pattern in dwords
        for (int i = 0; i < 3 && offset + i * 4 + 3 < data.length; i++) {
            int hb = data[offset + i * 4 + 3] & 0xFF;
            if (hb == 0x41) return true;
        }
        return false;
    }
    
    static void dumpHex(byte[] data, int offset, int length) {
        for (int row = 0; row * 16 < length; row++) {
            int off = offset + row * 16;
            if (off >= data.length) break;
            StringBuilder hex = new StringBuilder();
            StringBuilder ascii = new StringBuilder();
            for (int col = 0; col < 16 && off + col < data.length; col++) {
                int b = data[off + col] & 0xFF;
                hex.append(String.format("%02X ", b));
                ascii.append((b >= 32 && b < 127) ? (char)b : '.');
            }
            System.out.printf("  0x%05X: %-48s | %s%n", off, hex, ascii);
        }
    }
    
    static void searchForBlocks(byte[] data) {
        // Search for "BLOCK" in various encodings
        // ASCII: 42 4C 4F 43 4B
        byte[][] patterns = {
            {0x41, 0x63, 0x44, 0x62},  // "AcDb"
            {0x42, 0x4C, 0x4F, 0x43, 0x4B},  // "BLOCK"
            {0x42, 0x4C, 0x4F, 0x43, 0x4B, 0x5F},  // "BLOCK_"
        };
        
        for (byte[] pattern : patterns) {
            int count = 0;
            int lastPos = -1;
            for (int i = 0; i < data.length - pattern.length; i++) {
                boolean match = true;
                for (int j = 0; j < pattern.length; j++) {
                    if (data[i+j] != pattern[j]) { match = false; break; }
                }
                if (match) {
                    count++;
                    lastPos = i;
                    if (count <= 5) {
                        System.out.printf("  Pattern found at 0x%X: ", i);
                        StringBuilder sb = new StringBuilder();
                        for (int j = 0; j < Math.min(pattern.length + 32, 64) && i + j < data.length; j++) {
                            int b = data[i + j] & 0xFF;
                            sb.append(String.format("%02X ", b));
                        }
                        System.out.println(sb.toString());
                    }
                }
            }
            System.out.println("  Total matches: " + count + " last at 0x" + Integer.toHexString(lastPos));
        }
    }
    
    static void tryBitDecode(byte[] data, int offset, int length) {
        // DWG uses bit-level encoding: BS (bit-short), BL (bit-long), BD (bit-double)
        // Try to interpret as bit stream
        int bitPos = 0;
        
        // Try to read as sequence of variable-length integers
        System.out.println("  First 32 bytes interpreted as various types:");
        for (int i = 0; i < Math.min(length, 32) && offset + i < data.length; i++) {
            System.out.printf("  byte[%d]=0x%02X (%d) '%c'%n",
                i, data[offset+i] & 0xFF, data[offset+i] & 0xFF,
                (data[offset+i] >= 32 && data[offset+i] < 127) ? (char)data[offset+i] : '.');
        }
        
        // Try to interpret as (code, value) pairs
        System.out.println("  Trying (tag, value) pair interpretation:");
        int pos = offset;
        int limit = Math.min(data.length, offset + length);
        while (pos < limit - 4) {
            int tag = data[pos] & 0xFF;
            pos++;
            
            // Common DWG tags:
            // 0x00-0x0F: short values
            // 0x10-0x1F: longer values  
            // 0x80+: control codes
            
            if (tag == 0xFF || tag == 0x00) {
                // Probably end of section or padding
                break;
            }
            
            // Try to read value
            if ((tag & 0xF0) == 0x00) {
                // Maybe 1 byte value
                int val = data[pos] & 0xFF;
                System.out.printf("  (0x%02X, 0x%02X) = (%d, %d)%n", tag, val, tag, val);
                pos++;
            } else if ((tag & 0xF0) == 0x10) {
                // Maybe 2 byte value
                int val = (data[pos] & 0xFF) | ((data[pos+1] & 0xFF) << 8);
                System.out.printf("  (0x%02X, 0x%04X) = (%d, %d)%n", tag, val, tag, val);
                pos += 2;
            } else {
                // Unknown
                int next = data[pos] & 0xFF;
                System.out.printf("  (0x%02X, next=0x%02X)%n", tag, next);
                if (pos > offset + 64) break;
                pos++;
            }
        }
    }
    
    static void findInsertEntities(byte[] data) {
        // In DWG, INSERT has object type 0x68 (in R13-R2000)
        // For modern versions, look for block references by handle pattern
        
        // Try to find blocks by searching for known block object patterns
        // BLOCK object type is often indicated by:
        // - Object type code 0x02 (BLOCK_HEADER/ENDblk)
        // - INSERT type code varies
        
        // Search for patterns that look like block references
        for (int offset = 0; offset < data.length - 16; offset++) {
            // Look for handle reference pattern followed by object data
            // Handle pattern: xx xx xx 41 (0x41 high byte)
            // Block handles often 0x4164xxxx
            int h3 = data[offset + 3] & 0xFF;
            if (h3 == 0x41) {
                // Check if next bytes look like entity data
                // Look for X/Y/Z coordinates (doubles) after the handle
                long handle = readLE32(data, offset);
                // Look for doubles in the next 32 bytes
                int doubleCount = 0;
                int textStart = -1;
                for (int checkOff = offset + 4; checkOff < Math.min(data.length, offset + 64); checkOff++) {
                    int b = data[checkOff] & 0xFF;
                    if (b >= 'A' && b <= 'z' && textStart == -1) {
                        textStart = checkOff;
                    }
                }
                if (textStart != -1) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < 32 && textStart + i < data.length; i++) {
                        int b = data[textStart + i] & 0xFF;
                        if (b >= 32 && b < 127) sb.append((char)b);
                    }
                    String text = sb.toString();
                    if (text.toLowerCase().contains("block") || text.toLowerCase().contains("insert") ||
                        text.toLowerCase().contains("acdb")) {
                        System.out.printf("  Potential block at 0x%X handle=0x%X text='%s'%n",
                            offset, handle, text.substring(0, Math.min(50, text.length())));
                    }
                }
            }
        }
    }
    
    static long readLE32(byte[] data, int offset) {
        return ((long)(data[offset] & 0xFF)) | 
               ((long)(data[offset+1] & 0xFF) << 8) | 
               ((long)(data[offset+2] & 0xFF) << 16) | 
               ((long)(data[offset+3] & 0xFF) << 24);
    }
}
