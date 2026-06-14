import java.nio.file.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class FindHandlesSection {
    static byte[] data;

    static int readMC(int pos, int[] nextPos) {
        // Modular character: variable length encoding
        // High bit of each byte = continuation flag (1 = more bytes follow)
        // Remaining 7 bits = data, big-endian (MSB first)
        int result = 0;
        int curIdx = pos;
        boolean hasMore = true;
        int iterations = 0;
        while (hasMore && iterations < 10 && curIdx < data.length) {
            int b = data[curIdx] & 0xFF;
            result = (result << 7) | (b & 0x7F);
            hasMore = (b & 0x80) != 0;
            curIdx++;
            iterations++;
        }
        nextPos[0] = curIdx;
        return result;
    }

    public static void main(String[] args) throws Exception {
        data = Files.readAllBytes(Paths.get("210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));

        // R2000 file header analysis
        System.out.println("=== DWG R2000 File Analysis ===");
        System.out.println("File size: " + data.length + " (0x" + Integer.toHexString(data.length) + ")");

        // Check header
        String headerStr = new String(data, 0, 6);
        System.out.println("Header: " + headerStr);

        // Byte 0x06: Maintenance version (LE UInt32)
        ByteBuffer bb = ByteBuffer.wrap(data);
        bb.order(ByteOrder.LITTLE_ENDIAN);

        // Header structure for R2000:
        // 0x00: 6 bytes version string
        // 0x06: UInt16 maintenance version
        // 0x08: UInt32 number of records
        // 0x0C: ... see OpenDesign spec

        System.out.println("\n--- File header dump (first 64 bytes) ---");
        for (int i = 0; i < 64; i += 16) {
            System.out.print(String.format("0x%04x: ", i));
            for (int j = 0; j < 16 && i + j < data.length; j++) {
                System.out.print(String.format("%02x ", data[i + j] & 0xFF));
            }
            System.out.println();
        }

        // R2000 Section map: at offset 0x15 (from DWG spec)
        // Actually, let me search for section descriptors at various positions
        System.out.println("\n--- Looking for Section map / Handles section ---");

        // In R2000, section locator is at offset 0x25, pointing to "Section Map"
        // Each section entry: UInt32 section_type + UInt32 offset + UInt32 size
        // Known section types: 0=Image Header, 1=Classes, 2=Handles

        // Let's look at offsets 0x20 to 0x80
        for (int offset = 0x20; offset < 0x100; offset += 4) {
            int val = (data[offset] & 0xFF) | ((data[offset+1] & 0xFF) << 8) |
                       ((data[offset+2] & 0xFF) << 16) | ((data[offset+3] & 0xFF) << 24);
            if (val > 0 && val < data.length) {
                System.out.println(String.format(" 0x%04x: LE_UINT32 = %d (0x%04x)", offset, val, val));
            }
        }

        // Try: parse section map at offset found in header
        int headerOffset = bb.getInt(0x15);  // Could be 0x15 or another position
        System.out.println("\nInt at 0x15: " + headerOffset);
        int headerOffset2 = bb.getInt(0x25);
        System.out.println("Int at 0x25: " + headerOffset2);

        // According to OpenDesign: for AC1015 (R2000),
        // offset 0x08: ULONG file size
        // offset 0x0C: ULONG image header position (0)
        // offset 0x10: ULONG image header size
        // offset 0x14: ULONG image header size again?
        // offset 0x19: ULONG total number of records in classes
        // offset 0x25: ULONG offset to section map
        // Let's try various positions

        System.out.println("\n--- Parsing candidate handles sections ---");
        int[] candidates = {0x0800, 0x0500, 0x0400, 0x0300, 0x0200, 0x0100};
        for (int candidate : candidates) {
            try {
                System.out.println("\n  Trying offset 0x" + Integer.toHexString(candidate));
                // Try to read as [type][offset][size] triplets
                int[] nextPos = new int[1];
                int count = 0;
                int pos = candidate;
                while (count < 5 && pos + 12 < data.length) {
                    int type = bb.getInt(pos);
                    int offsetS = bb.getInt(pos + 4);
                    int sizeS = bb.getInt(pos + 8);
                    pos += 12;
                    if (offsetS >= 0 && offsetS < data.length && sizeS > 0 && sizeS < data.length) {
                        System.out.println(String.format("    Entry[%d]: type=%d, offset=0x%04x, size=%d",
                                count, type, offsetS, sizeS));
                        count++;
                    } else {
                        break;
                    }
                }
            } catch (Exception e) {
                System.out.println("  Error: " + e.getMessage());
            }
        }

        // Alternative: search for known block names and their positions
        // Then trace backwards to find the object header
        System.out.println("\n--- Known object positions (from previous analysis) ---");
        int[] bhPositions = {0x52b9, 0x532e};
        int[] insPositions = {0x6971};

        for (int pos : bhPositions) {
            int sizeW = (data[pos] & 0xFF) | ((data[pos+1] & 0xFF) << 8);
            int typeW = ((data[pos+2] & 0x3F) << 2) | ((data[pos+3] >> 6) & 3);
            System.out.println(String.format("  BLOCK_HEADER @ 0x%04x: size_WORD=%d, type_WORD=0x%02x",
                    pos, sizeW, typeW));
        }

        // Now, let's try to read at the end of file (section map is near end)
        System.out.println("\n--- Last 128 bytes of the file ---");
        for (int i = Math.max(0, data.length - 128); i < data.length; i += 16) {
            System.out.print(String.format("0x%04x: ", i));
            for (int j = 0; j < 16 && i + j < data.length; j++) {
                System.out.print(String.format("%02x ", data[i + j] & 0xFF));
            }
            System.out.println();
        }

        // Try: reading section map at position pointed to by last ULONG in file
        int lastOffset = bb.getInt(data.length - 4);
        System.out.println("\nLast ULONG of file: " + lastOffset + " (0x" + Integer.toHexString(lastOffset & 0xFFFFFFF) + ")");
        int secondLastOffset = bb.getInt(data.length - 8);
        System.out.println("Second last ULONG: " + secondLastOffset);

        // Actually, let me look at a range from the end of the file more carefully
        System.out.println("\n--- Int32 values near end of file ---");
        for (int i = data.length - 64; i < data.length; i += 4) {
            int v = bb.getInt(i);
            if (v > 0 && v < data.length) {
                System.out.println(String.format("  @0x%04x: %d (0x%04x)", i, v, v));
            }
        }
    }
}
