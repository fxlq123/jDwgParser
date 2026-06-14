import java.nio.file.*;
import java.util.*;

/**
 * Final comprehensive block analysis:
 * 1. Scan all objects in the DWG file
 * 2. Find BLOCK_HEADER and INSERT objects
 * 3. Extract block names and reference counts
 */
public class FinalBlockAnalysis {

    static byte[] fileData;

    // Decode BS type code from 2 bytes (consumes 2 bytes)
    static int decodeTypeCode(byte b0, byte b1) {
        int opcode = (b0 >> 6) & 3;
        if (opcode == 1) {
            // 8-bit value: low 6 bits of b0 (high) + high 2 bits of b1 (low)
            return ((b0 & 0x3F) << 2) | ((b1 >> 6) & 3);
        } else if (opcode == 0) {
            // 16-bit LE: low 6 bits of b0 + all 8 bits of b1
            // Actually: 2 bytes together with opcode bits removed
            return ((b0 & 0x3F) << 8) | (b1 & 0xFF);
        } else if (opcode == 2) {
            return 0;
        } else {
            return 256;
        }
    }

    // Get bytes consumed by BS encoding
    static int bsConsumed(byte b0) {
        int opcode = (b0 >> 6) & 3;
        if (opcode == 0 || opcode == 1) return 2;
        return 1;
    }

    // Read an object: [MS size 2 bytes LE][object data...]
    // MS is 2-byte LE with bit 15 as continuation - simpler:
    // MS format for size: if high bit of byte 1 is 1, continue reading
    // For simplicity, try: size = (byte[1] << 8) | byte[0], ignoring continuation

    static class ObjectInfo {
        int fileOffset;
        int size;
        int dataStart;
        int typeCode;
        String name;
        long handle;
        List<Long> handleRefs = new ArrayList<>();
    }

    // Read object at offset, return object info or null
    static ObjectInfo readObject(int offset) {
        if (offset + 4 >= fileData.length) return null;

        // Try MS size: first 2 bytes as LE 16-bit
        int byte0 = fileData[offset] & 0xFF;
        int byte1 = fileData[offset + 1] & 0xFF;

        // Standard modular short: bit 15 (of 16-bit LE) = continuation
        // If bit 15 == 0, it's a single short (size = low 15 bits)
        // If bit 15 == 1, there are more words
        int size = 0;
        int curOffset = offset;
        boolean hasMore = true;
        int wordCount = 0;
        while (hasMore && wordCount < 4 && curOffset + 1 < fileData.length) {
            int lo = fileData[curOffset] & 0xFF;
            int hi = fileData[curOffset + 1] & 0xFF;
            int word = lo | (hi << 8);
            size = (size << 15) | (word & 0x7FFF);
            hasMore = (word & 0x8000) != 0;
            curOffset += 2;
            wordCount++;
        }

        // Validate size
        if (size < 2 || size > 20000 || curOffset + size > fileData.length) {
            return null;
        }

        ObjectInfo info = new ObjectInfo();
        info.fileOffset = offset;
        info.dataStart = curOffset;
        info.size = size;

        // Read type code from object data
        if (size >= 2) {
            int tb0 = fileData[info.dataStart] & 0xFF;
            int tb1 = fileData[info.dataStart + 1] & 0xFF;
            info.typeCode = decodeTypeCode((byte) tb0, (byte) tb1);
        }

        return info;
    }

    // Scan for text in object data
    static List<String> extractTextFields(int dataStart, int size) {
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < size - 2; i++) {
            int len = fileData[dataStart + i] & 0xFF;
            if (len >= 1 && len <= 120 && i + 1 + len <= size) {
                boolean valid = true;
                for (int j = 0; j < len; j++) {
                    int c = fileData[dataStart + i + 1 + j] & 0xFF;
                    if (c < 32 || c > 126) { valid = false; break; }
                }
                if (valid) {
                    StringBuilder sb = new StringBuilder();
                    for (int j = 0; j < len; j++) {
                        sb.append((char) fileData[dataStart + i + 1 + j]);
                    }
                    texts.add(i + ":" + sb.toString());
                }
            }
        }
        return texts;
    }

    // Extract handle references from object data (looking for 0xXX 0x01 patterns etc)
    static List<Long> extractHandleRefs(int dataStart, int size) {
        List<Long> refs = new ArrayList<>();
        // Skip first 2 bytes (type code)
        for (int i = 2; i < size - 2; i++) {
            // Look for 2-byte handles: code byte + counter byte
            // Common pattern: 0x22 0x01 (handle 0x0122 in LE)
            // Or: 0x01 0x22 (handle 0x2201 in LE)
            int b0 = fileData[dataStart + i] & 0xFF;
            int b1 = fileData[dataStart + i + 1] & 0xFF;

            // Try LE: counter in byte0, code in byte1
            // handle = (code << 16) | counter
            // In LE bytes: [counter][code]
            long handleLE = ((long) b1 << 16) | b0;
            // Try BE: [code][counter]
            long handleBE = ((long) b0 << 16) | b1;

            // Filter for reasonable handles
            if (b0 > 0 && b0 < 200 && b1 > 0 && b1 < 200) {
                if (!refs.contains(handleLE)) refs.add(handleLE);
                if (!refs.contains(handleBE)) refs.add(handleBE);
            }
        }
        return refs;
    }

    public static void main(String[] args) throws Exception {
        fileData = Files.readAllBytes(Paths.get("210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));

        int scanStart = 0x5200;
        int scanEnd = Math.min(fileData.length, 0x0f000);

        System.out.println("=== DWG Block Definition Analysis ===");
        System.out.println("File size: " + fileData.length + " bytes");
        System.out.println("Scan range: 0x" + Integer.toHexString(scanStart) + " - 0x" + Integer.toHexString(scanEnd));
        System.out.println();

        // Phase 1: Scan all objects, find BLOCK_HEADER and INSERT
        List<ObjectInfo> blockHeaders = new ArrayList<>();
        List<ObjectInfo> inserts = new ArrayList<>();
        Map<Integer, Integer> typeDist = new HashMap<>();

        int objCount = 0;
        int offset = scanStart;
        while (offset < scanEnd - 16 && objCount < 2000) {
            ObjectInfo info = readObject(offset);
            if (info != null) {
                typeDist.merge(info.typeCode, 1, Integer::sum);

                if (info.typeCode == 0x30) {  // BLOCK_HEADER
                    info.name = "";
                    // Look for block name in object data (text fields)
                    List<String> texts = extractTextFields(info.dataStart, info.size);
                    for (String t : texts) {
                        int colon = t.indexOf(':');
                        String txt = t.substring(colon + 1);
                        if (txt.length() >= 3 && txt.matches("[A-Za-z0-9_*\\- ]{3,80}")
                                && !txt.matches("[0-9]+")) {
                            if (info.name.isEmpty()) {
                                info.name = txt;
                            } else {
                                info.name += " | " + txt;
                            }
                        }
                    }
                    blockHeaders.add(info);
                } else if (info.typeCode == 0x07) {  // INSERT
                    info.handleRefs = extractHandleRefs(info.dataStart, info.size);
                    inserts.add(info);
                }

                offset = info.dataStart + info.size;
            } else {
                offset++;
            }
            objCount++;
        }

        // Phase 2: Try to find block names in object data
        // For each BLOCK_HEADER, scan for text
        System.out.println("\n=== BLOCK_HEADER objects found ===");
        Map<Integer, String> blockHandles = new HashMap<>();  // dataOffset -> name
        for (int i = 0; i < blockHeaders.size(); i++) {
            ObjectInfo bh = blockHeaders.get(i);
            System.out.println(String.format("  BH#%d @0x%04x size=%d", i, bh.fileOffset, bh.size));
            System.out.println(String.format("    Type code: 0x%02x", bh.typeCode));
            System.out.println(String.format("    Text found: %s", bh.name.isEmpty() ? "(none)" : bh.name));

            // Extract all text fields
            List<String> allTexts = extractTextFields(bh.dataStart, bh.size);
            if (!allTexts.isEmpty()) {
                System.out.print("    All texts: ");
                for (int j = 0; j < Math.min(8, allTexts.size()); j++) {
                    System.out.print("[" + allTexts.get(j) + "] ");
                }
                System.out.println();
            }

            // First bytes hex dump (first 32 bytes)
            System.out.print("    First bytes: ");
            for (int k = 0; k < Math.min(32, bh.size); k++) {
                System.out.print(String.format("%02x ", fileData[bh.dataStart + k] & 0xFF));
            }
            System.out.println();
        }

        // Phase 3: Find block names by scanning for [len][text] in the full file
        // and try to correlate with object positions
        System.out.println("\n=== Block names found in file ===");
        List<int[]> blockNameLocations = new ArrayList<>();  // [offset, length]
        for (int off = scanStart; off < scanEnd; off++) {
            int len = fileData[off] & 0xFF;
            if (len >= 3 && len <= 80 && off + 1 + len <= fileData.length) {
                boolean valid = true;
                boolean hasLetter = false;
                for (int j = 0; j < len; j++) {
                    int c = fileData[off + 1 + j] & 0xFF;
                    if (c < 32 || c > 126) { valid = false; break; }
                    if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) hasLetter = true;
                }
                if (valid && hasLetter) {
                    String name = "";
                    for (int j = 0; j < len; j++) {
                        name += (char) fileData[off + 1 + j];
                    }
                    if (name.length() >= 3 && !name.matches(".*\\s.*") && !name.contains("..")) {
                        // Try to see if this is inside a known object
                        for (ObjectInfo bh : blockHeaders) {
                            if (off >= bh.dataStart && off < bh.dataStart + bh.size) {
                                System.out.println(String.format("  @0x%04x [inside BH @0x%04x] '%s'",
                                        off, bh.fileOffset, name));
                                if (bh.name.isEmpty() || bh.name.length() > name.length()) {
                                    bh.name = name;
                                }
                            }
                        }
                        // Also check names in insert
                    }
                }
            }
        }

        // Phase 4: Count INSERT references
        System.out.println("\n=== INSERT objects found: " + inserts.size() + " ===");
        Map<String, Integer> refCount = new HashMap<>();
        Map<String, Double> pointX = new HashMap<>();

        // For each INSERT, extract block name reference
        for (int i = 0; i < inserts.size(); i++) {
            ObjectInfo ins = inserts.get(i);
            // Try to find a block name in INSERT object data (for "block name")
            List<String> insTexts = extractTextFields(ins.dataStart, ins.size);
            String blockRef = "";
            for (String t : insTexts) {
                int colon = t.indexOf(':');
                String txt = t.substring(colon + 1);
                if (txt.length() >= 3 && !txt.startsWith("ACAD") && !txt.equals("SSR")
                        && txt.matches("[A-Za-z0-9_*\\-]+")) {
                    blockRef = txt;
                    break;
                }
            }

            // Try to find block_header_handle: look for common handle values
            // Print first bytes
            if (i < 5) {
                System.out.println(String.format("  INSERT#%d @0x%04x size=%d",
                        i, ins.fileOffset, ins.size));
                System.out.print("    First bytes: ");
                for (int k = 0; k < Math.min(48, ins.size); k++) {
                    System.out.print(String.format("%02x ", fileData[ins.dataStart + k] & 0xFF));
                }
                System.out.println();
                System.out.println("    Texts: " + insTexts);
                System.out.println("    Block ref: " + blockRef);
            }

            // Count by block reference
            if (!blockRef.isEmpty()) {
                refCount.merge(blockRef, 1, Integer::sum);
            } else {
                refCount.merge("<unknown>", 1, Integer::sum);
            }
        }

        // Phase 5: Summary
        System.out.println("\n=== FINAL SUMMARY ===");
        System.out.println("Total BLOCK_HEADER found: " + blockHeaders.size());
        System.out.println("Total INSERT found: " + inserts.size());

        System.out.println("\nBlock definitions:");
        for (int i = 0; i < blockHeaders.size(); i++) {
            ObjectInfo bh = blockHeaders.get(i);
            System.out.println(String.format("  %d. '%s' (@0x%04x, %d bytes)",
                    i + 1, bh.name.isEmpty() ? "(unnamed)" : bh.name, bh.fileOffset, bh.size));
        }

        System.out.println("\nBlock INSERT references:");
        List<Map.Entry<String, Integer>> sortedRefs = new ArrayList<>(refCount.entrySet());
        sortedRefs.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        for (Map.Entry<String, Integer> e : sortedRefs) {
            System.out.println(String.format("  '%s': %d INSERT(s)", e.getKey(), e.getValue()));
        }

        // Type distribution
        System.out.println("\nObject type distribution (top 10):");
        List<Map.Entry<Integer, Integer>> sortedTypes = new ArrayList<>(typeDist.entrySet());
        sortedTypes.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        for (int i = 0; i < Math.min(10, sortedTypes.size()); i++) {
            System.out.println(String.format("  Type 0x%02x (%d): %d occurrences",
                    sortedTypes.get(i).getKey(), sortedTypes.get(i).getKey(),
                    sortedTypes.get(i).getValue()));
        }
    }
}
