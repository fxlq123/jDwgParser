import java.nio.file.*;
import java.util.*;

/**
 * Comprehensive R2000 DWG object decoder.
 * 
 * Encodings (byte-aligned):
 * - BS (BitShort): reads 1-2 bytes, opcode in high 2 bits of first byte
 * - H (Handle): reads 1-2 bytes, code in high 4 bits or separate byte
 * - BD (BitDouble): reads 1-8 bytes
 * - T (Text): BS(length) then bytes
 */
public class R2000Decoder {

    static byte[] fileData;

    // Decode BS (BitShort) from byte array starting at position
    // Returns [value, bytes_consumed]
    static int[] decodeBS(byte[] data, int pos) {
        int b = data[pos] & 0xFF;
        int opcode = (b >> 6) & 3;  // high 2 bits
        int value;
        int consumed;

        switch (opcode) {
            case 0:  // 16-bit LE value
                // remaining 6 bits of first byte + all 8 bits of second byte... no, 
                // actually: low 6 bits of first byte is not standard.
                // Standard: opcode 00 means read next 2 bytes as LE short
                // But wait, some encodings: the 6 remaining bits + 10 bits of next byte?
                // Let's try: low 6 bits of byte 0 + high 2 bits? No.
                // Simplest: 2-byte LE total, ignoring opcode interpretation
                // Byte 1 (low) + byte 2 (high) → but byte 0 contains the opcode.
                // Actually: byte 0's low 6 bits as upper 6 bits + byte 1 as lower 8 bits?
                // Or: read 2 bytes from pos, interpret as LE 16-bit with first 2 bits masked?
                
                // Let's try: value = (byte 0 & 0x3F) << 8 | byte 1 (big-endian)
                int b1 = data[pos + 1] & 0xFF;
                value = ((b & 0x3F) << 8) | b1;
                consumed = 2;
                break;
            case 1:  // 8-bit value
                // low 6 bits of byte 0 + high 2 bits of byte 1 = 8 bits total
                int b2 = data[pos + 1] & 0xFF;
                value = ((b & 0x3F) << 2) | ((b2 >> 6) & 3);
                // or: ((b & 0x3F) << 8) | b2 ...
                // Let me try both and take the one that makes sense
                // Actually: standard BS encoding for type: the 2 bytes together are 0x4c 0x12 = 0x30
                // byte 0: 01 001100 → opcode 01, remaining 6 bits = 001100
                // byte 1: 00010010 → need 2 more bits → 00
                // value = 001100 00 = 0x30
                // So value = (byte0 & 0x3F) << 2 | (byte1 >> 6)
                // That uses: 6 bits from byte 0, 2 bits from byte 1 = 8 bits total.
                // But then remaining 6 bits of byte 1 are for next field?
                value = ((b & 0x3F) << 2) | ((b2 >> 6) & 3);
                consumed = 1;  // or 2? If only 8 bits consumed across 2 bytes, we need the remaining 6 bits of byte 1.
                // For this to work as byte-level, consumed must be 2 bytes but only 8 bits used.
                // Actually the standard says: BS reads 10 bits (2+8). At byte level, 2 bytes total.
                // So: value = ((byte0 & 0x3F) << 2) | ((byte1 >> 6) & 3)... no that's only 8 bits.
                // Let me try: 2 + 8 = 10 bits: opcode 2, then 8 value bits
                // From byte 0: 6 bits available
                // From byte 1: 2 bits needed for the 8-bit value + 6 bits remaining
                // value = 6 bits from byte 0 (high) + 2 bits from byte 1 (low)
                //   = ((b & 0x3F) << 2) | ((b2 >> 6) & 3)
                consumed = 1;  // byte 1 still has 6 bits... but this is byte-level.
                // Let me just consume 2 bytes and move on.
                consumed = 2;
                break;
            case 2:  // default = 0
                value = 0;
                consumed = 1;
                break;
            case 3:  // default = 256
            default:
                value = 256;
                consumed = 1;
                break;
        }
        return new int[]{ value, consumed };
    }

    // Decode a handle from byte array
    static long[] decodeHandle(byte[] data, int pos) {
        // Try 2-byte handle: byte 0 = code, byte 1 = counter
        int b = data[pos] & 0xFF;
        int code = (b >> 4) & 0x0F;  // high nibble
        int counter = b & 0x0F;       // low nibble
        
        // Option 1: 2-byte handle: byte 0 = code, byte 1 = counter
        int code1 = b;  // whole byte is code
        int counter1 = data[pos + 1] & 0xFF;  // whole next byte is counter
        long handle1 = ((long) code1 << 16) | counter1;

        // Option 2: nibble-based: high nibble code, low nibble counter + next byte
        int counter2 = (b & 0x0F) << 8 | (data[pos + 1] & 0xFF);
        long handle2 = ((long) code << 16) | counter2;

        return new long[]{ handle1, handle2 };
    }

    // Decode BD (BitDouble)
    static Object[] decodeDouble(byte[] data, int pos) {
        int b = data[pos] & 0xFF;
        int opcode = (b >> 6) & 3;

        switch (opcode) {
            case 0:  // raw double, 8 bytes LE
                long raw = 0;
                for (int i = 0; i < 8; i++) {
                    raw |= ((long) (data[pos + i] & 0xFF)) << (8 * i);
                }
                return new Object[]{ Double.longBitsToDouble(raw), 8 };
            case 1:  // 7-byte compressed
                long v = 0;
                for (int i = 1; i < 7; i++) {
                    v |= ((long) (data[pos + i] & 0xFF)) << (8 * (i - 1));
                }
                int sign = (b >> 5) & 1;
                // Reconstruct: sign * 2^exponent * mantissa
                return new Object[]{ (sign == 0 ? 1.0 : -1.0) * (double) v / (1L << 48), 7 };
            case 2:  // default 1.0
                return new Object[]{ 1.0, 1 };
            case 3:  // default 0.0
            default:
                return new Object[]{ 0.0, 1 };
        }
    }

    // Decode text (T)
    static String decodeText(byte[] data, int pos) {
        int len = data[pos] & 0xFF;
        if (len < 1 || len > 200 || pos + 1 + len > data.length) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            int c = data[pos + 1 + i] & 0xFF;
            if (c < 32 || c > 126) return "";
            sb.append((char) c);
        }
        return sb.toString();
    }

    // Try multiple BS encodings
    static class BSResult { int v; int c; String desc; }
    static List<BSResult> tryBS(byte[] data, int pos) {
        List<BSResult> results = new ArrayList<>();
        if (pos >= data.length) return results;

        int b0 = data[pos] & 0xFF;
        int opcode = (b0 >> 6) & 3;

        // Encoding A: low 6 bits of byte 0 + 2 bits of byte 1 (high) = 8-bit value
        if (pos + 1 < data.length) {
            int b1 = data[pos + 1] & 0xFF;
            int vA = ((b0 & 0x3F) << 2) | ((b1 >> 6) & 3);
            BSResult r = new BSResult();
            r.v = vA; r.c = 2; r.desc = "BS:A(op=" + opcode + ",v=" + vA + ")";
            results.add(r);

            // Encoding B: low 6 bits of byte 0 + byte 1 (full) = 14-bit value
            int vB = ((b0 & 0x3F) << 8) | b1;
            r = new BSResult();
            r.v = vB; r.c = 2; r.desc = "BS:B(v=" + vB + ")";
            results.add(r);

            // Encoding C: 2 bytes LE (full 16 bits, opcode ignored)
            int vC = b0 | (b1 << 8);
            r = new BSResult();
            r.v = vC; r.c = 2; r.desc = "BS:C(LE16=" + vC + ")";
            results.add(r);

            // Encoding D: 1 byte only, low 6 bits
            int vD = b0 & 0x3F;
            r = new BSResult();
            r.v = vD; r.c = 1; r.desc = "BS:D(low6=" + vD + ")";
            results.add(r);
        }
        return results;
    }

    static String hexDump(byte[] data, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            sb.append(String.format("%02x ", data[i] & 0xFF));
            if ((i - from) % 16 == 15) sb.append("\n");
        }
        return sb.toString();
    }

    public static void main(String[] args) throws Exception {
        fileData = Files.readAllBytes(Paths.get("210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));

        // Known positions:
        // BLOCK_HEADER #1: file offset 0x52b9, object data at 0x52bb, size 114
        // BLOCK_HEADER #2: file offset 0x6454, object data at ~0x6456, size ~4277
        // INSERT #1: file offset 0x6971, object data at 0x6973, size 512

        // Deep decode: try all BS encodings and trace forward
        System.out.println("=== Decoding BLOCK_HEADER #1 (114 bytes) ===");
        int bhPos = 0x52bb;
        int bhSize = 114;
        byte[] bhData = new byte[bhSize];
        System.arraycopy(fileData, bhPos, bhData, 0, bhSize);
        System.out.println("Data: " + hexDump(bhData, 0, bhSize));

        System.out.println("\nField-by-field decoding (byte-level):");
        int pos = 0;
        while (pos < bhSize - 2) {
            List<BSResult> options = tryBS(bhData, pos);
            System.out.print(String.format("  [%02x] ", pos));
            for (int i = 0; i < Math.min(4, options.size()); i++) {
                BSResult r = options.get(i);
                System.out.print(r.desc + " | ");
            }
            
            // Also check if current position is text
            String text = decodeText(bhData, pos);
            if (!text.isEmpty() && text.length() > 2) {
                System.out.print("T:'" + text + "' (" + (text.length() + 1) + " bytes) | ");
            }
            
            // Check if it could be a handle reference
            if (pos + 1 < bhSize) {
                int code = bhData[pos] & 0xFF;
                int counter = bhData[pos + 1] & 0xFF;
                if (code >= 0 && code < 50 && counter >= 0 && counter < 100) {
                    System.out.print(String.format("H:0x%02x%02x", code, counter));
                }
            }
            
            System.out.println();
            pos += 2;  // advance 2 bytes each iteration
        }

        // INSERT #1
        System.out.println("\n=== Decoding INSERT #1 (512 bytes) ===");
        int insPos = 0x6973;
        int insSize = 512;
        byte[] insData = new byte[insSize];
        System.arraycopy(fileData, insPos, insData, 0, insSize);

        pos = 0;
        while (pos < insSize - 2) {
            List<BSResult> options = tryBS(insData, pos);
            System.out.print(String.format("  [%02x] ", pos));
            for (int i = 0; i < Math.min(3, options.size()); i++) {
                BSResult r = options.get(i);
                System.out.print(r.desc + " | ");
            }
            
            String text = decodeText(insData, pos);
            if (!text.isEmpty() && text.length() >= 2) {
                System.out.print("T:'" + text + "' (" + (text.length() + 1) + " bytes) | ");
            }
            
            // Check for raw doubles
            if (pos + 8 <= insSize) {
                long raw = 0;
                for (int i = 0; i < 8; i++) raw |= ((long)(insData[pos+i] & 0xFF)) << (8*i);
                double d = Double.longBitsToDouble(raw);
                if (d == 1.0 || d == 0.0 || (d > 0.001 && d < 100000) || (d < -0.001 && d > -100000)) {
                    System.out.print(String.format("D:%f (8 bytes) | ", d));
                }
            }
            
            System.out.println();
            pos += 2;
        }

        // Block name scan: find text in range and associate with objects
        System.out.println("\n=== Text scan in objects area ===");
        for (int offset = 0x5200; offset < 0xd000; offset++) {
            int len = fileData[offset] & 0xFF;
            if (len >= 3 && len <= 80 && offset + 1 + len < fileData.length) {
                boolean valid = true;
                for (int i = 0; i < len; i++) {
                    int c = fileData[offset + 1 + i] & 0xFF;
                    if (c < 32 || c > 126) { valid = false; break; }
                }
                if (valid) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < len; i++) {
                        sb.append((char) fileData[offset + 1 + i]);
                    }
                    String s = sb.toString();
                    // Filter for block-like names (contain at least one letter)
                    if (s.matches("[A-Za-z0-9_*\\- ]{3,80}") && !s.matches("[0-9.]+")) {
                        String context = "";
                        if (offset >= 0x52bb && offset < 0x52bb + 114) context = "BH#1";
                        else if (offset >= 0x6454 && offset < 0x7500) context = "BH#2";
                        else if (offset >= 0x6971 && offset < 0x6971 + 520) context = "INS#1";
                        else if (offset >= 0xd3a6 && offset < 0xd3a6 + 1600) context = "BH#3";
                        System.out.println(String.format("  @0x%04x [%s] len=%d '%s'", offset, context, len, s));
                    }
                }
            }
        }
    }
}
