import java.nio.file.*;
import java.util.*;

/**
 * Manually decode BLOCK_HEADER and INSERT objects to understand field structure.
 */
public class DeepDecode {
    static byte[] fileData;

    static int readMS2B(byte[] data, int pos, int[] nextPos) {
        int result = 0;
        int idx = pos;
        int shift = 0;
        while (true) {
            int lo = data[idx] & 0xFF;
            int hi = data[idx + 1] & 0xFF;
            int w = lo | (hi << 8);
            result |= (w & 0x7FFF) << shift;
            idx += 2;
            if ((w & 0x8000) == 0) break;
            shift += 15;
        }
        nextPos[0] = idx;
        return result;
    }

    static class BR {
        byte[] data;
        int pos;
        BR(byte[] d) { this.data = d; this.pos = 0; }
        int bit() {
            int bi = pos >> 3;
            int biti = 7 - (pos & 7);
            pos++;
            return (data[bi] >> biti) & 1;
        }
        int bits(int n) { int r = 0; for (int i = 0; i < n; i++) r = (r << 1) | bit(); return r; }
        long bitsl(int n) { long r = 0; for (int i = 0; i < n; i++) r = (r << 1) | bit(); return r; }
        int bs() { int op = bits(2); return op==0?(int)bitsl(16):(op==1?bits(8):(op==2?0:256)); }
        long h() { int code = bits(4); int counter = bits(4); return ((long)code << 16) | counter; }
        double bd() {
            int op = bits(2);
            if (op == 0) { long r = bitsl(64); return Double.longBitsToDouble(r); }
            if (op == 1) { long r = bitsl(56); return (double) r; }
            return (op == 2) ? 1.0 : 0.0;
        }
        double rd() { long r = bitsl(64); return Double.longBitsToDouble(r); }
        int p() { return pos; }
        void skip(int n) { pos += n; }

        // Try to read text at current bit position (byte-aligned)
        String tryText() {
            int old = pos;
            try {
                if (pos % 8 != 0) return "";
                int bytePos = pos / 8;
                int len = data[bytePos] & 0xFF;
                if (len <= 0 || len > 80 || bytePos + 1 + len > data.length) return "";
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < len; i++) {
                    int c = data[bytePos + 1 + i] & 0xFF;
                    if (c < 32 || c > 126) return "";
                    sb.append((char) c);
                }
                return sb.toString();
            } finally { }
        }

        String text() {
            int len = bs();
            if (len <= 0 || len > 4096) return "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < len; i++) {
                int c = bits(8);
                sb.append(c >= 32 && c < 127 ? (char)c : '.');
            }
            return sb.toString();
        }
    }

    static void decodeObject(int offset, String label) {
        System.out.println("\n==========" + label + " @ 0x" + Integer.toHexString(offset) + " ==========");
        int[] np = new int[1];
        int size = readMS2B(fileData, offset, np);
        int dataStart = np[0];
        System.out.println("MS size = " + size + " (data starts at 0x" + Integer.toHexString(dataStart) + ")");

        byte[] obj = new byte[size];
        System.arraycopy(fileData, dataStart, obj, 0, size);

        System.out.println("\nObject data dump (" + size + " bytes):");
        for (int i = 0; i < Math.min(size, 256); i++) {
            if (i % 16 == 0) {
                if (i > 0) System.out.println();
                System.out.print(String.format("  %04x: ", i));
            }
            System.out.print(String.format("%02x ", obj[i]));
        }
        System.out.println();

        // ASCII
        System.out.print("  ASCII: ");
        for (int i = 0; i < Math.min(size, 128); i++) {
            int b = obj[i] & 0xFF;
            System.out.print(b >= 32 && b < 127 ? (char)b : '.');
        }
        System.out.println();

        // Decode bit field by field
        System.out.println("\nBit-field decode:");
        BR br = new BR(obj);

        // Type code (BS)
        int tc = br.bs();
        System.out.println(String.format("  [bit %3d] BS type_code = 0x%02x (%d)", br.p() - 10, tc, tc));

        // Try multiple field interpretations
        System.out.println("\n  --- Attempt 1: bitsize(RL,32) + handle(H) + eed(BS) + text(T) ---");
        try {
            BR br1 = new BR(obj);
            br1.pos = 10;  // skip type code

            int bitsize = (int) br1.bitsl(32);
            System.out.println(String.format("  [bit %3d] RL bitsize = %d (expected <= %d)", br1.pos - 32, bitsize, size*8));

            long handle = br1.h();
            System.out.println(String.format("  [bit %3d] H handle = 0x%x", br1.pos - 8, handle));

            int eed = br1.bs();
            System.out.println(String.format("  [bit %3d] BS eed_size = %d", br1.pos - 10, eed));

            if (eed > 0 && eed < 512) {
                int esz = br1.bits(8);
                System.out.println(String.format("  [bit %3d] byte eed_data_size = %d", br1.pos - 8, esz));
                br1.skip(esz * 8);
            }

            // Try reading text (block name or xref path)
            String t1 = br1.text();
            System.out.println(String.format("  [bit %3d] T text = '%s'", br1.pos, t1));

            String t2 = br1.text();
            System.out.println(String.format("  [bit %3d] T text2 = '%s'", br1.pos, t2));
        } catch (Exception e) {
            System.out.println("  (error: " + e.getMessage() + ")");
        }

        // Attempt 2: type + bitsize + common non-entity format
        System.out.println("\n  --- Attempt 2: RL bitsize + H handle + owner_handle(H) + num_reactors(BS) + reactors + ... ---");
        try {
            BR br2 = new BR(obj);
            br2.pos = 10;

            int bitsize = (int) br2.bitsl(32);
            System.out.println(String.format("  [bit %3d] RL bitsize = %d", br2.pos - 32, bitsize));

            long handle = br2.h();
            System.out.println(String.format("  [bit %3d] H object_handle = 0x%x", br2.pos - 8, handle));

            int numReactors = br2.bs();
            System.out.println(String.format("  [bit %3d] BS num_reactors = %d", br2.pos - 10, numReactors));

            for (int i = 0; i < Math.min(numReactors, 10); i++) {
                long rh = br2.h();
                System.out.println(String.format("  [bit %3d] H reactor[%d] = 0x%x", br2.pos - 8, i, rh));
            }

            long xdict = br2.h();
            System.out.println(String.format("  [bit %3d] H xdict_handle = 0x%x", br2.pos - 8, xdict));

            // Try text fields
            String tn1 = br2.text();
            System.out.println(String.format("  [bit %3d] T text1 = '%s'", br2.pos, tn1));
            String tn2 = br2.text();
            System.out.println(String.format("  [bit %3d] T text2 = '%s'", br2.pos, tn2));
        } catch (Exception e) {
            System.out.println("  (error: " + e.getMessage() + ")");
        }

        // Show raw bit sequence of first 64 bits
        System.out.println("\n  Raw bits (first 128 bits after type code):");
        System.out.print("  ");
        for (int i = 10; i < Math.min(138, size * 8); i++) {
            int bi = i >> 3;
            int biti = 7 - (i & 7);
            int bitv = (obj[bi] >> biti) & 1;
            if ((i - 10) > 0 && (i - 10) % 8 == 0) System.out.print(" ");
            if ((i - 10) > 0 && (i - 10) % 32 == 0) System.out.print("\n  ");
            System.out.print(bitv);
        }
        System.out.println();

        // Try: byte-aligned scan for text after type code
        System.out.println("\n  Byte-text scan (find [len][ascii] patterns in object):");
        for (int bp = 0; bp < Math.min(size - 2, 200); bp++) {
            int len = obj[bp] & 0xFF;
            if (len >= 1 && len <= 80 && bp + 1 + len <= size) {
                boolean valid = true;
                for (int i = 1; i <= len; i++) {
                    int c = obj[bp + i] & 0xFF;
                    if (c < 32 || c > 126) { valid = false; break; }
                }
                if (valid) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 1; i <= len; i++) sb.append((char) obj[bp + i]);
                    System.out.println(String.format("    byte[%d] len=%d: '%s'", bp, len, sb.toString()));
                }
            }
        }
    }

    public static void main(String[] args) throws Exception {
        fileData = Files.readAllBytes(Paths.get("210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));

        // Known positions from previous scan
        decodeObject(0x52b9, "BLOCK_HEADER #1");
        decodeObject(0x6454, "BLOCK_HEADER #2");
        decodeObject(0x6971, "INSERT #1");
        decodeObject(0xd3a6, "BLOCK_HEADER #3");
    }
}
