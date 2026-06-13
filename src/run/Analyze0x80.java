package run;

import java.io.File;
import java.nio.file.Files;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Detailed analysis of the 128-byte region at 0x80-0xFF
 * Try various decompression/decoding approaches
 */
public class Analyze0x80 {
    public static void main(String[] args) throws Exception {
        String path = "samples/2018/Dynblocks.dwg";
        byte[] data = Files.readAllBytes(new File(path).toPath());
        
        System.out.println("=== 0x80-0xFF (128 bytes) - Hex dump ===");
        dumpHex(data, 0x80, 128);
        
        System.out.println("\n=== 0x80-0xFF as LE32 values ===");
        for (int i = 0; i < 32; i++) {
            int off = 0x80 + i * 4;
            long val = readLE32(data, off);
            System.out.printf("  [%d] 0x%08X (%d)\n", i, val, val);
        }
        
        // Try LZ77 decompression
        System.out.println("\n=== Try LZ77 decompress 0x80 ===");
        try {
            byte[] compressed = new byte[128];
            System.arraycopy(data, 0x80, compressed, 0, 128);
            // Try raw inflate
            try {
                Inflater inf = new Inflater(true);
                inf.setInput(compressed);
                byte[] out = new byte[4096];
                int len = inf.inflate(out);
                inf.end();
                System.out.println("  Inflate (raw): " + len + " bytes");
                dumpHex(out, 0, Math.min(len, 64));
            } catch (DataFormatException e) {
                System.out.println("  Inflate (raw) failed: " + e.getMessage());
            }
            
            // Try zlib inflate
            try {
                Inflater inf = new Inflater(false);
                inf.setInput(compressed);
                byte[] out = new byte[4096];
                int len = inf.inflate(out);
                inf.end();
                System.out.println("  Inflate (zlib): " + len + " bytes");
                dumpHex(out, 0, Math.min(len, 64));
            } catch (DataFormatException e) {
                System.out.println("  Inflate (zlib) failed: " + e.getMessage());
            }
        } catch (Exception e) {
            System.out.println("  LZ77 failed: " + e.getMessage());
        }
        
        // Try to XOR with common keys
        System.out.println("\n=== Try XOR decoding 0x80 ===");
        int[] keys = {0x00, 0xFF, 0xAA, 0x55, 0x7E, 0x8D, 0x1F};
        for (int key : keys) {
            byte[] result = new byte[64];
            boolean printable = true;
            for (int i = 0; i < 64; i++) {
                result[i] = (byte)((data[0x80 + i] & 0xFF) ^ key);
                if (result[i] != 0 && (result[i] < 32 || result[i] > 126)) printable = false;
            }
            if (printable) {
                System.out.println("  XOR 0x" + Integer.toHexString(key) + ":");
                for (int i = 0; i < 64; i++) System.out.printf("%02X ", result[i]);
                System.out.println();
            }
        }
        
        // Analyze the 128 bytes as bit pattern
        System.out.println("\n=== Bit analysis of 0x80 ===");
        int zeroBits = 0, oneBits = 0;
        for (int i = 0; i < 128; i++) {
            byte b = data[0x80 + i];
            for (int bit = 0; bit < 8; bit++) {
                if ((b & (1 << bit)) != 0) oneBits++;
                else zeroBits++;
            }
        }
        System.out.println("  Zero bits: " + zeroBits + " (" + (zeroBits*100.0/1024) + "%)");
        System.out.println("  One bits: " + oneBits + " (" + (oneBits*100.0/1024) + "%)");
        
        // Check if the 128 bytes could be RS(128, 112) encoded
        // i.e., 112 data bytes + 16 ECC bytes
        System.out.println("\n=== Is last 16 bytes ECC? ===");
        byte[] last16 = new byte[16];
        System.arraycopy(data, 0x80 + 112, last16, 0, 16);
        System.out.print("  Last 16: ");
        for (byte b : last16) System.out.printf("%02X ", b & 0xFF);
        System.out.println();
        
        byte[] first112 = new byte[112];
        System.arraycopy(data, 0x80, first112, 0, 112);
        System.out.println("  First 112 bytes entropy: " + entropy(first112));
        
        // Maybe the 128 bytes is NOT encoded - maybe it IS the header data
        // Let me try to read as 16 LE64 values (page map entries)
        System.out.println("\n=== Try as 16 LE64 values ===");
        for (int i = 0; i < 16; i++) {
            long val = readLE64(data, 0x80 + i * 8);
            System.out.printf("  [%d] 0x%016X (%d)\n", i, val, val);
        }
        
        // Now try to understand section header structure at 0x100
        System.out.println("\n=== Section header structure analysis ===");
        
        // The structure at 0x100:
        //   dword 0: size (0x00075650 = 480848)
        //   dword 1-5: 5 handles/refs
        //   dword 6-7: hash?
        //   dword 8: count?
        //   then: name, data
        
        // Let me verify by looking at all section header blocks
        int[] sectionHeaders = new int[] {0x100, 0x1A0, 0x15C0, 0x1F00, 0x2888, 0x2941, 0x2ADA};
        for (int sh : sectionHeaders) {
            if (sh + 48 >= data.length) continue;
            System.out.printf("\n--- Section header at 0x%X ---%n", sh);
            long v0 = readLE32(data, sh);
            System.out.printf("  [0] 0x%08X (%d)%n", v0, v0);
            for (int i = 1; i <= 5; i++) {
                long v = readLE32(data, sh + i * 4);
                System.out.printf("  [%d] 0x%08X (%d) '%s'%n", i, v, v, 
                    new String(new char[]{(char)(v & 0xFF), (char)((v>>8)&0xFF), (char)((v>>16)&0xFF), (char)((v>>24)&0xFF)}));
            }
            long v6 = readLE32(data, sh + 24);
            long v7 = readLE32(data, sh + 28);
            long v8 = readLE32(data, sh + 32);
            System.out.printf("  [6] 0x%08X%n  [7] 0x%08X%n  [8] 0x%08X%n", v6, v7, v8);
        }
    }
    
    static void dumpHex(byte[] data, int offset, int length) {
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
    
    static long readLE32(byte[] data, int offset) {
        return ((long)(data[offset] & 0xFF)) | 
               ((long)(data[offset+1] & 0xFF) << 8) | 
               ((long)(data[offset+2] & 0xFF) << 16) | 
               ((long)(data[offset+3] & 0xFF) << 24);
    }
    
    static long readLE64(byte[] data, int offset) {
        long val = 0;
        for (int i = 0; i < 8; i++) {
            val |= ((long)(data[offset+i] & 0xFF)) << (i * 8);
        }
        return val;
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
