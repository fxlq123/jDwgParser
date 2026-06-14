import java.nio.file.*;
import java.util.*;

/**
 * Scan for BLOCK_HEADER (type 0x30) and INSERT (type 0x07) objects
 * with minimal validation - just dump the raw bytes when found.
 */
public class DumpObjTypes {
    static byte[] fileData;

    static class BitReader {
        byte[] data;
        int bitPos;
        BitReader(byte[] d) { this.data = d; this.bitPos = 0; }
        int readBit() {
            int byteIdx = bitPos >> 3;
            int bitIdx = 7 - (bitPos & 7);
            bitPos++;
            return (data[byteIdx] >> bitIdx) & 1;
        }
        int readBits(int n) { int r = 0; for (int i = 0; i < n; i++) r = (r << 1) | readBit(); return r; }
        long readBitsLong(int n) { long r = 0; for (int i = 0; i < n; i++) r = (r << 1) | readBit(); return r; }
        int readBS() {
            int op = readBits(2);
            if (op == 0) return (int) readBitsLong(16);
            if (op == 1) return readBits(8);
            if (op == 2) return 0;
            return 256;
        }
        int position() { return bitPos; }
    }

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
            if (shift > 60) break;
        }
        nextPos[0] = idx;
        return result;
    }

    static String hexDump(byte[] data, int offset, int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            if (i % 16 == 0) {
                if (i > 0) sb.append("\n");
                sb.append(String.format("  %04x: ", i));
            }
            sb.append(String.format("%02x ", data[offset + i]));
        }
        return sb.toString();
    }

    static String asciiDump(byte[] data, int offset, int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            int b = data[offset + i] & 0xFF;
            if (b >= 32 && b < 127) sb.append((char) b);
            else sb.append('.');
        }
        return sb.toString();
    }

    // Extract text from object data: look for [length byte] + [ascii bytes]
    static List<String> extractText(byte[] data, int offset, int length) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < length - 2; i++) {
            int b = data[offset + i] & 0xFF;
            if (b > 1 && b < 80) {
                // Check if next b bytes are ASCII
                if (i + 1 + b <= length) {
                    boolean allAscii = true;
                    for (int j = 1; j <= b; j++) {
                        int c = data[offset + i + j] & 0xFF;
                        if (c < 32 || c > 126) { allAscii = false; break; }
                    }
                    if (allAscii) {
                        StringBuilder sb = new StringBuilder();
                        for (int j = 1; j <= b; j++) {
                            sb.append((char) data[offset + i + j]);
                        }
                        result.add("[" + i + "]" + sb.toString());
                    }
                }
            }
        }
        return result;
    }

    public static void main(String[] args) throws Exception {
        fileData = Files.readAllBytes(Paths.get("210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));

        int start = 0x52b9;
        int end = Math.min(fileData.length, start + 50000);

        System.out.println("Scanning for objects with type 0x30 (BLOCK_HEADER) or 0x07 (INSERT)");
        System.out.println("Range: 0x" + Integer.toHexString(start) + " - 0x" + Integer.toHexString(end));
        System.out.println();

        int bhCount = 0;
        int insCount = 0;
        Map<Integer, Integer> typeFreq = new HashMap<>();

        for (int offset = start; offset < end - 4; offset++) {
            int[] nextPos = new int[1];
            int size = readMS2B(fileData, offset, nextPos);

            if (size <= 4 || size > 10000) continue;
            if (nextPos[0] + size > fileData.length) continue;

            int dataStart = nextPos[0];
            byte[] objData = new byte[size];
            try {
                System.arraycopy(fileData, dataStart, objData, 0, size);
            } catch (Exception e) { continue; }

            try {
                BitReader br = new BitReader(objData);
                int tc = br.readBS();

                typeFreq.merge(tc, 1, Integer::sum);

                if (tc == 0x30 && bhCount < 5) {
                    bhCount++;
                    System.out.println("=== BLOCK_HEADER (0x30) @ 0x" + Integer.toHexString(offset) +
                        " size=" + size + " ===");
                    System.out.println("Object data:");
                    System.out.println(hexDump(objData, 0, Math.min(128, objData.length)));
                    System.out.println("ASCII: " + asciiDump(objData, 0, Math.min(128, objData.length)));
                    System.out.println("Extracted text: " + extractText(objData, 0, Math.min(128, objData.length)));
                    System.out.println();
                }

                if (tc == 0x07 && insCount < 5) {
                    insCount++;
                    System.out.println("=== INSERT (0x07) @ 0x" + Integer.toHexString(offset) +
                        " size=" + size + " ===");
                    System.out.println("Object data:");
                    System.out.println(hexDump(objData, 0, Math.min(128, objData.length)));
                    System.out.println("ASCII: " + asciiDump(objData, 0, Math.min(128, objData.length)));
                    System.out.println("Extracted text: " + extractText(objData, 0, Math.min(128, objData.length)));
                    System.out.println();
                }
            } catch (Exception e) {
            }
        }

        System.out.println("\nFound: " + bhCount + " BLOCK_HEADER candidates, " + insCount + " INSERT candidates");
        System.out.println("Type frequency (top 20):");
        List<Map.Entry<Integer, Integer>> sorted = new ArrayList<>(typeFreq.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        for (int i = 0; i < Math.min(20, sorted.size()); i++) {
            System.out.println("  Type 0x" + String.format("%02x", sorted.get(i).getKey()) +
                " (" + sorted.get(i).getKey() + "): " + sorted.get(i).getValue());
        }
    }
}
