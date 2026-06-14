import io.dwg.core.io.*;
import io.dwg.core.version.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

public class DWGParser {
    // Multi-byte character / modular byte (7 bits per byte, big-endian)
    // bit 7 = continuation flag (1=continue, 0=stop)
    static long readMC(byte[] data, int[] posRef) {
        int idx = posRef[0];
        long result = 0;
        int count = 0;
        while (true) {
            int b = data[idx] & 0xFF;
            idx++;
            count++;
            result = (result << 7) | (b & 0x7F);
            if ((b & 0x80) == 0) break;
            if (count > 5) break;
        }
        posRef[0] = idx;
        return result;
    }

    // Modular short (16-bit LE words)
    static int readMS16(byte[] data, int[] posRef) {
        int idx = posRef[0];
        int result = 0;
        int shift = 0;
        while (true) {
            int lo = data[idx] & 0xFF;
            int hi = data[idx+1] & 0xFF;
            int w = lo | (hi << 8);
            result |= (w & 0x7FFF) << shift;
            idx += 2;
            if ((w & 0x8000) == 0) break;
            shift += 15;
        }
        posRef[0] = idx;
        return result;
    }

    static class HandleEntry {
        long handle;
        long offset;
        HandleEntry(long h, long o) { handle = h; offset = o; }
        public String toString() { return String.format("h=0x%x off=0x%x", handle, offset); }
    }

    static List<HandleEntry> parseHandlesSection(byte[] data, int startOffset) {
        List<HandleEntry> entries = new ArrayList<>();
        int[] pos = {startOffset};
        // Skip 6 bytes header (num_sections RS_BE, size RS_BE, crc RS_BE)
        pos[0] += 6;
        
        long handle = 0;
        long offset = 0;
        int safety = 5000;
        while (pos[0] < data.length - 4 && safety-- > 0) {
            long hdelta = readMC(data, pos);
            long odelta = readMC(data, pos);
            if (hdelta < 0 || odelta < 0 || hdelta > 1000000 || odelta > 1000000) break;
            handle += hdelta;
            offset += odelta;
            if (handle == 0 && odelta == 0) continue;
            if (offset < data.length) {
                entries.add(new HandleEntry(handle, offset));
            }
        }
        return entries;
    }

    public static void main(String[] args) throws Exception {
        byte[] data = Files.readAllBytes(Paths.get("210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));

        // Try various start offsets
        int[] candidates = {
            0x4f41, 0x4f4a, 0x4f4b, 0x4f50, 0x4f51, 
            0x4f60, 0x4f70, 0x4f80, 0x4f90, 0x4fa0,
            0x4f30, 0x4f20, 0x4f10, 0x4f00
        };
        
        System.out.println("=== Trying handles section parser ===\n");
        for (int start : candidates) {
            try {
                List<HandleEntry> entries = parseHandlesSection(data, start);
                if (entries.size() >= 50) {
                    System.out.println("Start=0x" + Integer.toHexString(start) + ": " + entries.size() + " entries");
                    for (int i = 0; i < Math.min(5, entries.size()); i++) {
                        HandleEntry e = entries.get(i);
                        System.out.println("  [" + i + "] handle=0x" + Long.toHexString(e.handle) +
                            " offset=0x" + Long.toHexString(e.offset));
                    }
                    System.out.println();
                }
            } catch (Exception e) {
                // ignore
            }
        }

        // Now find the one that gives 839 entries (from previous analysis)
        // Let's also try to find an encoding that gives us the right results
        System.out.println("\n=== Looking for block/insert objects ===");
        
        // From previous results: we expect first 0x52b9 with type=0x30
        // So let's try to parse object at 0x52b9, 0x52c5, etc. manually
        long[] testOffsets = {0x52b9, 0x52c5, 0x52d7};
        for (long offset : testOffsets) {
            try {
                int[] p = {(int)offset};
                int ms = readMS16(data, p);
                int dataStart = p[0];
                int objectEnd = dataStart + ms;
                if (ms > 0 && ms < 20000 && objectEnd < data.length) {
                    System.out.println("Object at 0x" + Long.toHexString(offset) +
                        ": MS=" + ms + ", data_start=0x" + Integer.toHexString(dataStart) +
                        ", end=0x" + Integer.toHexString(objectEnd));
                    
                    // Decode type
                    ByteBuffer bb = ByteBuffer.wrap(data, dataStart, ms);
                    BitStreamReader reader = new BitStreamReader(new ByteBufferBitInput(bb), DwgVersion.R2000);
                    
                    int typeCode = reader.readBitShort();
                    System.out.println("  Type code: 0x" + Integer.toHexString(typeCode) +
                        " (" + typeCode + ")");
                }
            } catch (Exception e) {
                System.out.println("  Error: " + e.getMessage());
            }
        }
    }
}
