import java.nio.file.*;
import java.util.*;

public class BlockDetailVerify {
    static byte[] data;

    static void hexRow(int offset, int len) {
        System.out.print("  " + String.format("%04X", offset) + ": ");
        for (int i = 0; i < len && offset + i < data.length; i++) {
            System.out.print(String.format("%02X ", data[offset + i] & 0xFF));
        }
        System.out.println();
    }

    // Read MS (16-bit LE)
    static int readMS(int pos) {
        return (data[pos] & 0xFF) | ((data[pos + 1] & 0xFF) << 8);
    }

    // Read BS type code
    static int readBS(int pos) {
        int b0 = data[pos] & 0xFF;
        int b1 = data[pos + 1] & 0xFF;
        return ((b0 & 0x3F) << 2) | ((b1 >> 6) & 3);
    }

    // Read BL (32-bit LE)
    static long readBL(int pos) {
        return ((long)(data[pos] & 0xFF))
            | ((long)(data[pos + 1] & 0xFF) << 8)
            | ((long)(data[pos + 2] & 0xFF) << 16)
            | ((long)(data[pos + 3] & 0xFF) << 24);
    }

    // Get a single bit
    static int getBit(int byteIdx, int bitIdx) {
        if (byteIdx >= data.length) return 0;
        return (data[byteIdx] >> (7 - bitIdx)) & 1;
    }

    // Read H code (variable length handle)
    static long readH(int bitPos) {
        long val = 0;
        int bits = 0;
        for (int i = 0; i < 8; i++) {
            int b = getBit(bitPos / 8, bitPos % 8);
            val = (val << 1) | b;
            bitPos++;
            bits++;
            if (b == 1) break;
        }
        return (val << 32) | bits;  // encode bits in lower 32
    }

    static int getMS(int bitPos) {
        int w = (data[bitPos / 8] & 0xFF) | ((data[bitPos / 8 + 1] & 0xFF) << 8);
        return w & 0x7FFF;
    }

    public static void main(String[] args) throws Exception {
        data = Files.readAllBytes(Paths.get("210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));

        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║          BLOCK HEADER 逐字节逐位验证                        ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();

        // Find all BLOCK_HEADER objects
        List<int[]> blocks = new ArrayList<>();
        for (int pos = 0x5200; pos < data.length - 4; pos++) {
            int raw = readMS(pos);
            if ((raw & 0x8000) != 0) continue;
            int size = raw & 0x7FFF;
            if (size < 8 || size > 30000 || pos + 2 + size > data.length) continue;
            int tc = readBS(pos + 2);
            if (tc == 0x30) {
                blocks.add(new int[]{pos, size});
                pos += 1 + size;
            }
        }

        System.out.println("Total BLOCK_HEADER objects: " + blocks.size());
        System.out.println();

        for (int i = 0; i < blocks.size(); i++) {
            int off = blocks.get(i)[0];
            int size = blocks.get(i)[1];

            System.out.println("════════════════════════════════════════════════════════");
            System.out.println("  BLOCK_HEADER #" + (i + 1) + " @ offset=0x" + String.format("%04X", off));
            System.out.println("════════════════════════════════════════════════════════");

            // Header bytes
            System.out.println("\n  [Raw Hex - First 32 bytes]");
            for (int row = 0; row < 32; row += 16) {
                hexRow(off + row, Math.min(16, 32 - row));
            }

            // Object structure
            int msVal = readMS(off);
            int bsType = readBS(off + 2);
            System.out.println("\n  [Object Structure]");
            System.out.println("    Offset        = 0x" + String.format("%04X", off) + " (" + off + ")");
            System.out.println("    Bytes 0-1     = 0x" + String.format("%04X", msVal) + " -> MS(objSize) = " + (msVal & 0x7FFF));
            System.out.println("    Byte 2        = 0x" + String.format("%02X", data[off + 2] & 0xFF) + " (opcode bits)");
            System.out.println("    Byte 3        = 0x" + String.format("%02X", data[off + 3] & 0xFF));
            System.out.println("    BS type code  = 0x" + String.format("%02X", bsType) + " (expected 0x30)");

            // BS type analysis
            int b2 = data[off + 2] & 0xFF;
            int b3 = data[off + 3] & 0xFF;
            int opcode = (b2 >> 6) & 3;
            int typeBits = ((b2 & 0x3F) << 2) | ((b3 >> 6) & 3);
            System.out.println("    opcode        = " + opcode + " (expected 1)");
            System.out.println("    type bits     = " + typeBits + " (0x" + String.format("%02X", typeBits) + ")");
            System.out.println("    Bit positions = BS opcode+type at bits " + ((off + 2) * 8) + "-" + ((off + 4) * 8 - 1));
            System.out.println("    Next byte     = 0x" + String.format("%02X", data[off + 4] & 0xFF) + " @ bit " + ((off + 4) * 8));
            System.out.println("    Next byte     = 0x" + String.format("%02X", data[off + 5] & 0xFF) + " @ bit " + ((off + 5) * 8));

            // Entity common data
            int ecStart = off + 4;  // data after MS(2B) + BS(2B) = 4B
            System.out.println("\n  [Entity Common Data - starts @ byte " + ecStart + " (bit " + (ecStart * 8) + ")]");

            // bitsize (BL, 4 bytes)
            long bitsize = readBL(ecStart);
            System.out.println("    bitsize(BL)   = 0x" + Long.toHexString(bitsize) + " (" + bitsize + ") @ bits " + (ecStart * 8) + "-" + (ecStart * 8 + 31));

            // entity handle (H code)
            int ehBit = (ecStart + 4) * 8;
            long ehEnc = readH(ehBit);
            long ehVal = ehEnc >> 32;
            int ehBits = (int)(ehEnc & 0xFFFFFFFFL);
            System.out.println("    entity_handle = 0x" + Long.toHexString(ehVal) + " (" + ehBits + " bits) @ bit " + ehBit);

            // EED size (MS, 16-bit)
            int eedBit = ehBit + ehBits;
            int eedVal = getMS(eedBit);
            System.out.println("    EED_size(MS)  = " + eedVal + " (0x" + String.format("%X", eedVal) + ") @ bit " + eedBit);

            // owner handle (H code)
            int owBit = eedBit + 16;
            long owEnc = readH(owBit);
            long owVal = owEnc >> 32;
            int owBits = (int)(owEnc & 0xFFFFFFFFL);
            System.out.println("    owner_handle  = 0x" + Long.toHexString(owVal) + " (" + owBits + " bits) @ bit " + owBit);

            // Block name field (text string)
            int nameStart = owBit + owBits;
            int nameByteStart = (nameStart + 7) / 8;
            if (nameByteStart % 2 == 1) nameByteStart++;  // align to word
            System.out.println("\n  [Block-Specific Data - starts @ byte " + nameByteStart + " (bit " + nameStart + ")]");
            System.out.println("    First 16 bytes after EC:");
            hexRow(nameByteStart, 16);

            // Find block name string
            System.out.println("\n  [Text Strings in Object]");
            int end = off + 2 + size;
            int found = 0;
            for (int j = nameByteStart; j < end - 2 && found < 10; j++) {
                int len = data[j] & 0xFF;
                if (len < 1 || len > 80 || j + 1 + len > end) continue;
                StringBuilder sb = new StringBuilder();
                boolean valid = true;
                for (int k = 0; k < len; k++) {
                    int c = data[j + 1 + k] & 0xFF;
                    if (c < 32 || c > 126) { valid = false; break; }
                    sb.append((char)c);
                }
                if (valid && sb.length() >= 2 && sb.toString().matches("[A-Za-z_*][A-Za-z0-9_*\\-]*")) {
                    System.out.println("    @ byte 0x" + String.format("%04X", j + 1) + " len=" + len + " : \"" + sb + "\"");
                    found++;
                }
            }
            if (found == 0) System.out.println("    (none)");
            System.out.println();
        }
    }
}
