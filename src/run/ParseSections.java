package run;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Parse section headers and try to identify section names
 */
public class ParseSections {
    
    static class Section {
        int headerOffset;
        long dataOffset;
        long[] handles = new long[5];
        long[] hash = new long[2];
        String name = "";
        String sectionType = "";
        int count = 0;
        
        @Override
        public String toString() {
            return String.format("Section(header=0x%X, data=0x%X, name='%s', type='%s', count=%d)",
                headerOffset, dataOffset, name, sectionType, count);
        }
    }
    
    public static void main(String[] args) throws Exception {
        String path = "samples/2018/Dynblocks.dwg";
        byte[] data = Files.readAllBytes(new File(path).toPath());
        System.out.println("File: " + data.length + " bytes");

        // Find all section headers
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
            for (int i = 0; i < 5; i++) {
                s.handles[i] = readLE32(data, offset + 4 + i * 4);
            }
            s.hash[0] = readLE32(data, offset + 24);
            s.hash[1] = readLE32(data, offset + 28);
            
            // Try to find section name in the header data
            // After the 8 dwords (32 bytes), try to find text
            int textOffset = offset + 32;
            StringBuilder sb = new StringBuilder();
            
            // Try UTF-16LE name
            int countVal = (int)readLE32(data, textOffset);
            textOffset += 4;
            
            // Read string length (next LE16 or LE32)
            int strLen = (int)readLE16(data, textOffset);
            textOffset += 2;
            
            if (strLen > 0 && strLen < 256 && textOffset + strLen * 2 < data.length) {
                // Try UTF-16LE
                boolean isAscii = true;
                StringBuilder nameSb = new StringBuilder();
                for (int i = 0; i < strLen; i++) {
                    if (data[textOffset + i * 2 + 1] != 0) {
                        isAscii = false;
                        break;
                    }
                    char c = (char)(data[textOffset + i * 2] & 0xFF);
                    if (c >= 32 && c < 127) nameSb.append(c);
                    else if (c != 0) { isAscii = false; break; }
                }
                if (isAscii && nameSb.length() > 0) {
                    s.name = nameSb.toString();
                }
            }
            
            // Try to find the next "Hey" or other text pattern
            // Actually "Hey" at 0x150 in the first section might be the section TYPE
            int tryOffset = offset + 0x50;
            if (tryOffset + 3 < data.length) {
                String tag = "" + (char)(data[tryOffset] & 0xFF) +
                    (char)(data[tryOffset+1] & 0xFF) + (char)(data[tryOffset+2] & 0xFF);
                if (Character.isLetterOrDigit(tag.charAt(0)) &&
                    Character.isLetterOrDigit(tag.charAt(1)) &&
                    Character.isLetterOrDigit(tag.charAt(2))) {
                    s.sectionType = tag;
                }
            }
            
            sections.add(s);
        }
        
        System.out.println("Found " + sections.size() + " sections:");
        for (int i = 0; i < Math.min(50, sections.size()); i++) {
            Section s = sections.get(i);
            // Try to find section name from context (look for ASCII strings in header)
            String foundName = extractSectionName(data, s.headerOffset);
            if (!foundName.isEmpty()) s.name = foundName;
            System.out.println("  [" + i + "] " + s);
            
            // Show bytes at data offset
            if (i < 15) {
                System.out.print("    Data: ");
                for (int j = 0; j < 32 && s.dataOffset + j < data.length; j++) {
                    byte b = data[(int)s.dataOffset + j];
                    if (b >= 32 && b < 127) System.out.print((char)b);
                    else System.out.print(".");
                }
                System.out.println();
                System.out.print("    Hex:  ");
                for (int j = 0; j < 32 && s.dataOffset + j < data.length; j++) {
                    System.out.printf("%02X ", data[(int)s.dataOffset + j] & 0xFF);
                }
                System.out.println();
            }
        }
        
        // Now let's try to understand the encoding format of section data
        // Let's look at specific section data
        System.out.println("\n=== Section data analysis ===");
        
        // Let's look at the section at 0x15C0 which contained "AppInfoDataList"
        for (Section s : sections) {
            if (s.name.contains("AppInfo") || s.name.contains("Preview")) {
                System.out.println("\nSection with name '" + s.name + "' header:");
                dumpBytes(data, s.headerOffset, 128);
                System.out.println("Data at 0x" + Long.toHexString(s.dataOffset) + ":");
                dumpBytes(data, (int)s.dataOffset, 256);
            }
        }
        
        // Look for sections with BLOCK-related data
        System.out.println("\n=== Looking for BLOCK sections ===");
        for (Section s : sections) {
            // Check section data for BLOCK patterns
            if (s.dataOffset > 0 && s.dataOffset + 32 < data.length) {
                // Check for "AcDbBlockTable" or "BLOCK" patterns
                String textRegion = extractAscii(data, (int)s.dataOffset, 128);
                if (textRegion.toLowerCase().contains("block") || 
                    textRegion.toLowerCase().contains("insert") ||
                    textRegion.toLowerCase().contains("line") ||
                    textRegion.toLowerCase().contains("circle")) {
                    System.out.println("Block candidate at 0x" + 
                        Long.toHexString(s.dataOffset) + " -> " + textRegion.substring(0, Math.min(60, textRegion.length())));
                }
            }
        }
        
        // Show the overall file structure
        System.out.println("\n=== File structure summary ===");
        System.out.println("File size: " + data.length + " bytes (0x" + 
            Integer.toHexString(data.length) + ")");
        System.out.println("Section headers: " + sections.size());
        // Find the range of section data offsets
        long minOffset = Long.MAX_VALUE, maxOffset = 0;
        for (Section s : sections) {
            minOffset = Math.min(minOffset, s.dataOffset);
            maxOffset = Math.max(maxOffset, s.dataOffset);
        }
        System.out.println("Section data range: 0x" + Long.toHexString(minOffset) + 
            " - 0x" + Long.toHexString(maxOffset));
        System.out.println("Header region: 0x0 - 0x" + Long.toHexString(maxOffset + 100000));
    }
    
    static String extractSectionName(byte[] data, int offset) {
        // Look for ASCII/UTF-16LE text patterns after the 8-dword header
        int searchStart = offset + 32;
        int searchEnd = Math.min(data.length, offset + 256);
        
        // Try pattern: "03 00 00 00" + "10 00" (or similar count + length)
        for (int pos = searchStart; pos < searchEnd - 16; pos++) {
            // Try to find "03 00 00 00" (count = 3) or "01 00 00 00" (count = 1)
            int count = (int)readLE32(data, pos);
            if (count >= 1 && count <= 10) {
                int len16 = (int)readLE16(data, pos + 4);
                if (len16 > 0 && len16 <= 64 && pos + 6 + len16 * 2 < data.length) {
                    // Try UTF-16LE name
                    StringBuilder sb = new StringBuilder();
                    boolean valid = true;
                    for (int i = 0; i < len16; i++) {
                        if (data[pos + 6 + i * 2 + 1] != 0) { valid = false; break; }
                        char c = (char)(data[pos + 6 + i * 2] & 0xFF);
                        if (c >= 32 && c < 127) sb.append(c);
                        else if (c != 0) { valid = false; break; }
                    }
                    if (valid && sb.length() >= 3 && sb.length() == len16) {
                        return sb.toString();
                    }
                }
            }
        }
        
        // Try pattern: "07 00" followed by "rurban" style text
        for (int pos = searchStart; pos < searchEnd - 8; pos++) {
            if (data[pos] >= 1 && data[pos] <= 16 && data[pos + 1] == 0) {
                int len = data[pos] & 0xFF;
                StringBuilder sb = new StringBuilder();
                boolean valid = true;
                for (int i = 0; i < len && pos + 2 + i * 2 < data.length; i++) {
                    if (data[pos + 2 + i * 2 + 1] != 0) { valid = false; break; }
                    char c = (char)(data[pos + 2 + i * 2] & 0xFF);
                    if (c >= 32 && c < 127) sb.append(c);
                    else if (c != 0) { valid = false; break; }
                }
                if (valid && sb.length() >= 3 && sb.length() == len) {
                    return sb.toString();
                }
            }
        }
        
        return "";
    }
    
    static String extractAscii(byte[] data, int offset, int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length && offset + i < data.length; i++) {
            byte b = data[offset + i];
            if (b >= 32 && b < 127) sb.append((char)b);
            else sb.append('.');
        }
        return sb.toString();
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
}
