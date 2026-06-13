package run;

import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Deep analysis of the block-related regions identified in the file
 */
public class BlockAnalysis {
    
    public static void main(String[] args) throws Exception {
        String path = "samples/2018/Dynblocks.dwg";
        byte[] data = Files.readAllBytes(Paths.get(path));
        
        System.out.println("=== Block-related text regions ===");
        System.out.println("File size: " + data.length + " bytes (0x" + Integer.toHexString(data.length) + ")");
        System.out.println();
        
        // Analyze regions identified from previous search
        int[] interestingOffsets = {
            0x26FF,      // "AcDb_Thumbnail_Schema"
            0x1FB6D1,    // "ENHANCEDBLOCKCP"
            0x1FB6DD,    // "NCEDBLOCKCP@@"
            0x1FB967,    // "AcDbDynamicBlockR"
            0x20586B,    // "D_ENHANCEDBLOCKH"
            0x205877,    // "NCEDBLOCKHISTORY"
            0x2136E9,    // "AcDb:AcDsPrototype_1bx"
        };
        
        for (int offset : interestingOffsets) {
            examineRegion(data, offset, 256);
        }
        
        // Now let's look at the entire tail section of the file
        // The file has data section towards the end
        System.out.println("\n=== Tail section analysis (0x1F0000 to end) ===");
        examineLargeRegion(data, 0x1F0000, data.length - 0x1F0000);
        
        // Search for all "AcDb" occurrences
        System.out.println("\n=== All AcDb occurrences ===");
        searchAllAcDb(data);
        
        // Now search for block-related DXF patterns
        System.out.println("\n=== Block table related patterns ===");
        searchBlockPatterns(data);
    }
    
    static void examineRegion(byte[] data, int offset, int length) {
        int start = Math.max(0, offset - 32);
        int end = Math.min(data.length, offset + length);
        System.out.printf("--- Region around 0x%X (bytes 0x%X to 0x%X) ---%n",
            offset, start, end);
        
        // Show context
        for (int row = start & ~0xF; row < end; row += 16) {
            StringBuilder hex = new StringBuilder();
            StringBuilder ascii = new StringBuilder();
            for (int col = 0; col < 16 && row + col < data.length; col++) {
                int b = data[row + col] & 0xFF;
                hex.append(String.format("%02X ", b));
                ascii.append((b >= 32 && b < 127) ? (char)b : '.');
            }
            String marker = (row <= offset && row + 16 > offset) ? " <--" : "";
            System.out.printf("  0x%05X: %-48s | %s%s%n", row, hex, ascii, marker);
        }
        System.out.println();
    }
    
    static void examineLargeRegion(byte[] data, int start, int length) {
        // Look for text patterns in the large region
        int textStart = -1;
        StringBuilder textBuilder = new StringBuilder();
        
        for (int i = start; i < Math.min(data.length, start + length); i++) {
            int b = data[i] & 0xFF;
            if (b >= 32 && b < 127) {
                if (textStart == -1) {
                    textStart = i;
                    textBuilder = new StringBuilder();
                }
                textBuilder.append((char)b);
            } else {
                if (textStart != -1 && textBuilder.length() >= 4) {
                    String text = textBuilder.toString();
                    if (text.length() >= 8 || 
                        text.toLowerCase().contains("block") ||
                        text.toLowerCase().contains("acdb") ||
                        text.toLowerCase().contains("insert") ||
                        text.toLowerCase().contains("layer") ||
                        text.toLowerCase().contains("line") ||
                        text.toLowerCase().contains("circle") ||
                        text.toLowerCase().contains("arc")) {
                        System.out.printf("  0x%05X: '%s'%n", textStart, text);
                    }
                }
                textStart = -1;
            }
        }
        
        if (textStart != -1 && textBuilder.length() >= 4) {
            String text = textBuilder.toString();
            if (text.length() >= 8 || text.toLowerCase().contains("block") ||
                text.toLowerCase().contains("acdb")) {
                System.out.printf("  0x%05X: '%s'%n", textStart, text);
            }
        }
    }
    
    static void searchAllAcDb(byte[] data) {
        byte[] acdb = {0x41, 0x63, 0x44, 0x62};  // "AcDb"
        int count = 0;
        for (int i = 0; i < data.length - 4; i++) {
            if (data[i] == acdb[0] && data[i+1] == acdb[1] &&
                data[i+2] == acdb[2] && data[i+3] == acdb[3]) {
                // Extract surrounding text
                StringBuilder sb = new StringBuilder();
                int start = Math.max(0, i - 16);
                for (int j = start; j < Math.min(data.length, i + 64); j++) {
                    int b = data[j] & 0xFF;
                    if (b >= 32 && b < 127) sb.append((char)b);
                    else sb.append('.');
                }
                System.out.printf("  0x%05X: %s%n", i, sb.toString());
                count++;
                if (count >= 30) break;
            }
        }
        System.out.println("  Total: " + count + " occurrences");
    }
    
    static void searchBlockPatterns(byte[] data) {
        // Search for block-related text
        String[] patterns = {
            "BLOCK", "INSERT", "ENDBLK", "BLOCK_RECORD",
            "AcDbBlockTable", "AcDbBlockBegin", "AcDbBlockEnd",
            "AcDbBlockReference", "AcDbBlock",
            "DynamicBlock", "ENHANCEDBLOCK"
        };
        
        for (String pattern : patterns) {
            int count = 0;
            int first = -1;
            for (int i = 0; i < data.length - pattern.length(); i++) {
                boolean match = true;
                for (int j = 0; j < pattern.length(); j++) {
                    if ((char)(data[i+j] & 0xFF) != pattern.charAt(j)) {
                        match = false; break;
                    }
                }
                if (match) {
                    count++;
                    if (first == -1) first = i;
                    if (count <= 3) {
                        StringBuilder sb = new StringBuilder();
                        int start = Math.max(0, i - 8);
                        for (int j = start; j < Math.min(data.length, i + pattern.length() + 16); j++) {
                            int b = data[j] & 0xFF;
                            if (b >= 32 && b < 127) sb.append((char)b);
                            else sb.append('.');
                        }
                        System.out.printf("  '%s' at 0x%X: %s%n", pattern, i, sb.toString());
                    }
                }
            }
            if (count > 0) {
                System.out.printf("  Total '%s' occurrences: %d (first at 0x%X)%n", pattern, count, first);
            }
        }
    }
}
