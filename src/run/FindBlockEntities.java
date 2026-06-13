package run;

import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Search for block objects and understand the DWG object data encoding
 * by examining regions with known block-related text patterns
 */
public class FindBlockEntities {
    
    public static void main(String[] args) throws Exception {
        String path = "samples/2018/Dynblocks.dwg";
        byte[] data = Files.readAllBytes(Paths.get(path));
        
        System.out.println("=== Searching for block entity patterns ===");
        System.out.println("File size: 0x" + Integer.toHexString(data.length));
        
        // Search for block-related object class names (in ASCII and UTF-16LE)
        // These are potential DXF class names for block objects
        searchPatterns(data, new String[]{
            "AcDbBlockTable",
            "AcDbBlockTableRecord",
            "AcDbBlockBegin",
            "AcDbBlockEnd",
            "AcDbBlockReference",
            "BLOCK_RECORD",
            "ENHANCEDBLOCK",
            "INSERT",
        });
        
        // Search for common DWG object codes in the file
        System.out.println("\n=== Searching for DWG object patterns in encoded data ===");
        examineEncodedData(data);
        
        // Look at regions around the block-related text we found
        System.out.println("\n=== Detailed examination of block-related regions ===");
        
        // Around 0x1FB6D1 (ENHANCEDBLOCKCP) - this is a custom object
        examineObjectRegion(data, 0x1FB6D1 - 256, 512);
        
        // Around 0x1FB967 (AcDbDynamicBlockReferencePurgePreventer)
        examineObjectRegion(data, 0x1FB967 - 128, 384);
        
        // Around 0x20586B (ACAD_ENHANCEDBLOCKHISTORY)
        examineObjectRegion(data, 0x20586B - 128, 384);
        
        // Try to find standard block table records - search for common patterns
        System.out.println("\n=== Searching for BLOCK definition patterns ===");
        searchForBlockRecords(data);
        
        // Look at section data for block table record patterns
        System.out.println("\n=== Analyzing section data at various offsets ===");
        examineSectionDataForBlocks(data);
        
        // Try to understand the object encoding by looking at simpler entities first
        System.out.println("\n=== Trying to understand object encoding ===");
        analyzeObjectEncoding(data);
    }
    
    static void searchPatterns(byte[] data, String[] patterns) {
        for (String pattern : patterns) {
            // Search in plain ASCII
            int asciiCount = 0;
            int asciiFirst = -1;
            for (int i = 0; i < data.length - pattern.length(); i++) {
                boolean match = true;
                for (int j = 0; j < pattern.length(); j++) {
                    if ((data[i+j] & 0xFF) != pattern.charAt(j)) {
                        match = false; break;
                    }
                }
                if (match) {
                    asciiCount++;
                    if (asciiFirst == -1) asciiFirst = i;
                    if (asciiCount <= 5) {
                        StringBuilder sb = new StringBuilder();
                        int start = Math.max(0, i - 4);
                        for (int j = start; j < Math.min(data.length, i + pattern.length() + 32); j++) {
                            int b = data[j] & 0xFF;
                            sb.append((b >= 32 && b < 127) ? (char)b : '.');
                        }
                        System.out.printf("  ASCII 0x%X: '%s'%n", i, sb.toString());
                    }
                }
            }
            
            // Search in UTF-16LE  
            int utfCount = 0;
            int utfFirst = -1;
            byte[] utfPattern = new byte[pattern.length() * 2];
            for (int i = 0; i < pattern.length(); i++) {
                utfPattern[i*2] = (byte)pattern.charAt(i);
                utfPattern[i*2+1] = 0;
            }
            for (int i = 0; i < data.length - utfPattern.length; i++) {
                boolean match = true;
                for (int j = 0; j < utfPattern.length; j++) {
                    if (data[i+j] != utfPattern[j]) {
                        match = false; break;
                    }
                }
                if (match) {
                    utfCount++;
                    if (utfFirst == -1) utfFirst = i;
                    if (utfCount <= 5) {
                        StringBuilder sb = new StringBuilder();
                        int start = Math.max(0, i - 4);
                        for (int j = start; j < Math.min(data.length, i + utfPattern.length + 32); j++) {
                            int b = data[j] & 0xFF;
                            sb.append((b >= 32 && b < 127) ? (char)b : '.');
                        }
                        System.out.printf("  UTF16 0x%X: '%s'%n", i, sb.toString());
                    }
                }
            }
            
            if (asciiCount > 0 || utfCount > 0) {
                System.out.printf("  '%s': ASCII=%d (first@0x%X), UTF16LE=%d (first@0x%X)%n",
                    pattern, asciiCount, asciiFirst, utfCount, utfFirst);
            }
        }
    }
    
    static void examineEncodedData(byte[] data) {
        // Look at the encoded object data sections
        // Try to decode section data at 0x74290 as bit-encoded DWG objects
        
        System.out.println("Looking for object start patterns in section data...");
        
        // Search for common object start codes
        // In DWG, objects have a header: type, handles, then data
        int sectionDataOffsets[] = {
            0x74290, 0x75650, 0x756F0,  // early sections
            0x77FF0, 0x77E30, 0x7CF90,
            0x7BDD0, 0x66990, 0x6C8D0,
            0x6A1F0, 0x51AB0, 0x5FF30,
        };
        
        for (int offset : sectionDataOffsets) {
            if (offset < 0 || offset >= data.length) continue;
            examineSingleSection(data, offset);
        }
    }
    
    static void examineSingleSection(byte[] data, int offset) {
        System.out.printf("%nSection data at 0x%X:%n", offset);
        
        // First 64 bytes as hex and ascii
        for (int row = 0; row < 4; row++) {
            int off = offset + row * 16;
            StringBuilder hex = new StringBuilder();
            StringBuilder ascii = new StringBuilder();
            for (int col = 0; col < 16 && off + col < data.length; col++) {
                int b = data[off + col] & 0xFF;
                hex.append(String.format("%02X ", b));
                ascii.append((b >= 32 && b < 127) ? (char)b : '.');
            }
            System.out.printf("  0x%05X: %s | %s%n", off, hex.toString(), ascii.toString());
        }
        
        // Look for text patterns in 256 bytes
        StringBuilder textRun = new StringBuilder();
        int runStart = -1;
        for (int i = 0; i < 256 && offset + i < data.length; i++) {
            int b = data[offset + i] & 0xFF;
            if (b >= 32 && b < 127) {
                if (runStart == -1) runStart = offset + i;
                textRun.append((char)b);
            } else {
                if (runStart != -1 && textRun.length() >= 4) {
                    System.out.printf("  Text run @0x%X: '%s'%n", runStart, textRun.toString());
                }
                runStart = -1;
                textRun = new StringBuilder();
            }
        }
        if (runStart != -1 && textRun.length() >= 4) {
            System.out.printf("  Text run @0x%X: '%s'%n", runStart, textRun.toString());
        }
    }
    
    static void examineObjectRegion(byte[] data, int start, int length) {
        System.out.printf("%n--- Object region 0x%X (length=%d) ---%n", start, length);
        int rowCount = 0;
        for (int row = 0; row * 16 < length && start + row * 16 < data.length; row++) {
            int off = start + row * 16;
            StringBuilder hex = new StringBuilder();
            StringBuilder ascii = new StringBuilder();
            for (int col = 0; col < 16 && off + col < data.length; col++) {
                int b = data[off + col] & 0xFF;
                hex.append(String.format("%02X ", b));
                ascii.append((b >= 32 && b < 127) ? (char)b : '.');
            }
            System.out.printf("  0x%05X: %s | %s%n", off, hex.toString(), ascii.toString());
            if (rowCount++ > 30) break;
        }
    }
    
    static void searchForBlockRecords(byte[] data) {
        // Search for specific block-related DXF class names
        String[] blockClasses = {
            "AcDbBlockTable", "AcDbBlockTableRecord",
            "AcDbBlockBegin", "AcDbBlockEnd", "AcDbBlockReference",
            "AcDbDictionary", "AcDbSymbolTable", "AcDbLayerTable",
            "AcDbLine", "AcDbCircle", "AcDbArc", "AcDbPolyline"
        };
        
        // Search in ASCII
        for (String className : blockClasses) {
            int count = 0;
            int first = -1;
            for (int i = 0; i < data.length - className.length(); i++) {
                boolean match = true;
                for (int j = 0; j < className.length(); j++) {
                    if ((data[i+j] & 0xFF) != className.charAt(j)) {
                        match = false; break;
                    }
                }
                if (match) {
                    count++;
                    if (first == -1) first = i;
                    if (count <= 3) {
                        StringBuilder sb = new StringBuilder();
                        int contextStart = Math.max(0, i - 8);
                        for (int j = contextStart; j < Math.min(data.length, i + className.length() + 16); j++) {
                            int b = data[j] & 0xFF;
                            sb.append((b >= 32 && b < 127) ? (char)b : '.');
                        }
                        System.out.printf("  0x%X '%s' context: %s%n", i, className, sb.toString());
                    }
                }
            }
            if (count > 0) {
                System.out.printf("  '%s' found %d times (first@0x%X)%n", className, count, first);
            }
        }
        
        // Search for class names in UTF-16LE
        System.out.println("\nUTF-16LE search:");
        for (String className : blockClasses) {
            byte[] utfPattern = new byte[className.length() * 2];
            for (int i = 0; i < className.length(); i++) {
                utfPattern[i*2] = (byte)className.charAt(i);
                utfPattern[i*2+1] = 0;
            }
            
            int count = 0;
            int first = -1;
            for (int i = 0; i < data.length - utfPattern.length; i++) {
                boolean match = true;
                for (int j = 0; j < utfPattern.length; j++) {
                    if (data[i+j] != utfPattern[j]) {
                        match = false; break;
                    }
                }
                if (match) {
                    count++;
                    if (first == -1) first = i;
                    if (count <= 2) {
                        StringBuilder sb = new StringBuilder();
                        int contextStart = Math.max(0, i - 8);
                        for (int j = contextStart; j < Math.min(data.length, i + utfPattern.length + 16); j++) {
                            int b = data[j] & 0xFF;
                            sb.append((b >= 32 && b < 127) ? (char)b : '.');
                        }
                        System.out.printf("  0x%X UTF16 '%s': %s%n", i, className, sb.toString());
                    }
                }
            }
            if (count > 0) {
                System.out.printf("  UTF16 '%s' found %d times (first@0x%X)%n", className, count, first);
            }
        }
    }
    
    static void examineSectionDataForBlocks(byte[] data) {
        // Look at section data offsets referenced by section headers
        // The section headers at offsets like 0x100, 0x1A0, etc. reference data
        
        System.out.println("Scanning sections for block data...");
        
        // Found: 101 sections in the file
        // Key observation: sections point to object data at various offsets
        // Let's look at the CONTENT of the referenced data areas
        
        // Specific section data offsets from our parsing:
        int dataOffsets[] = {
            0x74290,  // AppInfoDataList
            0x75650,  // Section 0
            0x756F0,  // Section 1
            0x77FF0,  // Section 5
            0x77E30,  // Section 6
            0x7CF90,  // Section 7
            0x7BDD0,  // Section 8
            0x66990,  // Section 9
            0x6C8D0,  // Section 10
            0x6A1F0,  // Section 11
            0x51AB0,  // Section 12
            0x5FF30,  // Section 13
            
            // Late sections
            0xB0,     // Section 26
            0xE1D0,   // Section 27
            
            // High offset sections
            0xF4930,  // Section 28
            0xF2A50,  // Section 29
            0xF8CB0,  // Section 30
            0xE6090,  // Section 31
            
            // Very high offsets  
            0x171070, // Section 51
            0x17F210, // Section 52
            0x1654F0, // Section 53
            0x1633B0, // Section 54
            
            // Object map related sections near end
            0x1F5070, // Section 73
            0x1F1B90, // Section 74
            0x1FDE30, // Section 75
            0x1F8B30, // Section 76
            
            // Entity data sections
            0x1E7570, // Section 77
            0x1E0990, // Section 78
            0x1EE9F0, // Section 79
            0x1D5990, // Section 80
            0x1D09F0, // Section 81
            0x1DFA10, // Section 82
        };
        
        for (int offset : dataOffsets) {
            if (offset < 0 || offset >= data.length) continue;
            System.out.printf("%nSection data @0x%X:%n", offset);
            
            // Show 16 bytes of hex
            StringBuilder ascii = new StringBuilder();
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 16 && offset + i < data.length; i++) {
                int b = data[offset + i] & 0xFF;
                hex.append(String.format("%02X ", b));
                ascii.append((b >= 32 && b < 127) ? (char)b : '.');
            }
            System.out.printf("  %s | %s%n", hex.toString(), ascii.toString());
            
            // Check if this looks like encoded object data
            // Look for text runs
            StringBuilder textRun = new StringBuilder();
            int textStart = -1;
            for (int i = 0; i < 128 && offset + i < data.length; i++) {
                int b = data[offset + i] & 0xFF;
                if (b >= 32 && b < 127) {
                    if (textStart == -1) textStart = offset + i;
                    textRun.append((char)b);
                } else {
                    if (textStart != -1 && textRun.length() >= 8) {
                        System.out.printf("  Text: '%s' @0x%X%n", textRun.toString(), textStart);
                    }
                    textStart = -1;
                    textRun = new StringBuilder();
                }
            }
            if (textStart != -1 && textRun.length() >= 8) {
                System.out.printf("  Text: '%s' @0x%X%n", textRun.toString(), textStart);
            }
        }
    }
    
    static void analyzeObjectEncoding(byte[] data) {
        // Try to understand the encoding format used in the file
        // Look at the byte patterns around known text
        
        // Examine the area around 0x74290 more carefully - this is object data
        // using bit-packing encoding
        
        System.out.println("Analyzing encoding at 0x74290...");
        
        // Look for patterns that indicate the start of an object
        // In DWG R2007+, object data starts with:
        // - Object type code (BOT)
        // - Handle to owner (H, 3 bytes)
        // - Handle to object itself (H, 3 bytes)
        // - Entity-specific data
        
        // Try to find object boundaries by looking for repeating structures
        int offset = 0x74290;
        int limit = Math.min(data.length, offset + 1024);
        
        // Count bytes by frequency in first 512 bytes
        int[] freq = new int[256];
        for (int i = offset; i < Math.min(offset + 512, data.length); i++) {
            freq[data[i] & 0xFF]++;
        }
        
        System.out.println("  Top 15 byte frequencies:");
        int[] indices = new int[256];
        for (int i = 0; i < 256; i++) indices[i] = i;
        for (int i = 0; i < 255; i++) {
            for (int j = i+1; j < 256; j++) {
                if (freq[indices[j]] > freq[indices[i]]) {
                    int t = indices[i]; indices[i] = indices[j]; indices[j] = t;
                }
            }
        }
        for (int i = 0; i < 15; i++) {
            System.out.printf("    0x%02X (%d): '%c' - %d occurrences%n",
                indices[i], indices[i],
                (indices[i] >= 32 && indices[i] < 127) ? (char)indices[i] : '.',
                freq[indices[i]]);
        }
        
        // Try to find patterns that look like handles (0x41 prefix in LE32)
        System.out.println("\n  Looking for handle patterns in section data:");
        for (int i = offset; i < Math.min(offset + 256, data.length - 3); i++) {
            if ((data[i+3] & 0xFF) == 0x41) {
                long handle = ((long)(data[i] & 0xFF)) |
                             ((long)(data[i+1] & 0xFF) << 8) |
                             ((long)(data[i+2] & 0xFF) << 16) |
                             ((long)(data[i+3] & 0xFF) << 24);
                if (handle > 0x41000000L && handle < 0x42000000L) {
                    System.out.printf("    Handle 0x%X at offset 0x%X%n", handle, i);
                }
            }
        }
    }
}
