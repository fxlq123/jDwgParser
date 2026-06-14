import java.nio.file.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

public class FindAllObjects {
    static byte[] data;
    static ByteBuffer bb;

    // Modular Character (7 bits per byte, MSB=continuation flag)
    static int readMC(int pos, int[] nextPos) {
        int result = 0;
        int curIdx = pos;
        boolean hasMore = true;
        int iters = 0;
        while (hasMore && iters < 8 && curIdx < data.length) {
            int b = data[curIdx] & 0xFF;
            result = (result << 7) | (b & 0x7F);
            hasMore = (b & 0x80) != 0;
            curIdx++;
            iters++;
        }
        nextPos[0] = curIdx;
        return result;
    }

    // Modular Short: 16-bit LE words, bit 15 = continuation flag
    static int readMS16(int pos, int[] nextPos) {
        int result = 0;
        int curIdx = pos;
        boolean hasMore = true;
        int iters = 0;
        while (hasMore && iters < 4 && curIdx + 1 < data.length) {
            int lo = data[curIdx] & 0xFF;
            int hi = data[curIdx + 1] & 0xFF;
            int word = lo | (hi << 8);
            result |= (word & 0x7FFF) << (15 * iters);
            hasMore = (word & 0x8000) != 0;
            curIdx += 2;
            iters++;
        }
        nextPos[0] = curIdx;
        return result;
    }

    static void dumpRange(int start, int length) {
        for (int i = 0; i < length; i += 16) {
            int pos = start + i;
            if (pos >= data.length) break;
            System.out.print(String.format("0x%04x: ", pos));
            for (int j = 0; j < 16 && pos + j < data.length; j++) {
                System.out.print(String.format("%02x ", data[pos + j] & 0xFF));
            }
            System.out.print("  ");
            for (int j = 0; j < 16 && pos + j < data.length; j++) {
                int c = data[pos + j] & 0xFF;
                if (c >= 32 && c <= 126) System.out.print((char) c);
                else System.out.print(".");
            }
            System.out.println();
        }
    }

    public static void main(String[] args) throws Exception {
        data = Files.readAllBytes(Paths.get("210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));
        bb = ByteBuffer.wrap(data);
        bb.order(ByteOrder.LITTLE_ENDIAN);

        System.out.println("=== APPROACH: Try parsing Handles Section as MC(handle)+MC(offset) pairs ===\n");

        // Based on previous analysis: objects start around 0x52b9
        // Let's try to find the handles section by looking for
        // "handle 1 = offset 0x52b9" type patterns

        // Try the data starting at various positions, looking for:
        // handle=1 (MC=0x01) followed by offset=0x52b9 (MC=?)
        // 0x52b9 = binary 101001010111001 = 17 bits → 3 bytes MC:
        // byte1: 10100101 (0xA5, hi bit=1 → continue)
        // byte2: 01010111 (0x57, hi bit=0 → done)
        // Wait, 0x52b9 = 21177. Let me compute properly:
        // 21177 = 101001010111001 binary (15 bits)
        // 7 bits at a time from MSB:
        //   group1: 1010010 = 82, group2: 1011100 = 92, group3: 1 = 1
        // But if it has continuation flags: (1<<7)|82 = 210, (1<<7)|92 = 220, 1
        // bytes: 0xD2, 0xDC, 0x01
        // Hmm that's one interpretation. But there are different interpretations.

        // Let me try a simpler approach: scan the file looking for the pattern
        // [MC=1][something that encodes offset around 0x52b9]

        // But first: let's try to find ALL valid objects using word-MS encoding
        // by scanning the object area sequentially (objects are NOT contiguous in files!)

        System.out.println("=== Testing word-MS sequential scan from 0x52b9 ===");
        int pos = 0x52b9;
        int objCount = 0;
        int validObjects = 0;
        TreeMap<Integer, String> objectsByType = new TreeMap<>();
        TreeMap<Integer, Integer> typeCounts = new TreeMap<>();

        // Known valid positions from type-pattern matching
        // Let's scan at positions where we got valid results before
        int[] candidatePositions = {
            0x52b9, 0x532e, 0x5c98, 0x6971, 0x6f52, 0x72db, 0x72f4,
            0x7822, 0x7851, 0x79c9, 0x7ab4, 0x7b9f,
            0xa1df, 0xa5c2, 0xaca0, 0xafd1, 0xb00b, 0xb0ee, 0xb128, 0xb20f, 0xb249,
            0xc4e9, 0xccf3, 0xd1f7, 0xd223, 0xd3a6, 0xd4b8, 0xdbdf, 0xddd1,
            0xe052, 0xe0b0, 0xe32c, 0xea77, 0xec63,
            0xf19c, 0xf809, 0xf835, 0xf861, 0xf938, 0xfa71,
            0x10092, 0x108d9, 0x1139a,
            0xbf16, 0xbf41, 0xc422
        };

        // Also: let's try to find the actual pattern by looking at raw bytes at
        // key positions

        System.out.println("\n=== Detailed analysis at known BLOCK_HEADER positions ===\n");

        // @0x52b9:
        System.out.println("@0x52b9 (first BLOCK_HEADER):");
        dumpRange(0x52b9, 48);
        System.out.println();

        // @0x6971:
        System.out.println("@0x6971 (INSERT with SSR):");
        dumpRange(0x6971, 80);
        System.out.println();

        // @0xb00b:
        System.out.println("@0xb00b (BLOCK_HEADER with I-Me5):");
        dumpRange(0xb00b, 64);
        System.out.println();

        // Now: let's look at where the "known" block names are stored
        // Search for some block names directly
        String[] blockNames = {"_D_5", "SW_CENTERMARKSYMBOL_0", "Layout2", "I-Me5", "SSR", "SLDTEXTSTYLE", "SLDDIMSTYLE"};

        System.out.println("\n=== Block name positions in file ===\n");
        for (String name : blockNames) {
            byte[] nb = name.getBytes();
            for (int i = 0; i < data.length - nb.length; i++) {
                boolean match = true;
                for (int j = 0; j < nb.length; j++) {
                    if ((data[i + j] & 0xFF) != nb[j]) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    System.out.println(String.format("  '%s' @ 0x%04x:", name, i));
                    // Show surrounding bytes
                    int start = Math.max(0, i - 20);
                    int end = Math.min(data.length, i + name.length() + 20);
                    System.out.print("    ");
                    for (int k = start; k < end; k++) {
                        int c = data[k] & 0xFF;
                        if (k == i) System.out.print("[");
                        if (c >= 32 && c <= 126) System.out.print((char) c);
                        else System.out.print(".");
                        if (k == i + name.length() - 1) System.out.print("]");
                    }
                    System.out.println();

                    // Show hex too
                    System.out.print("    ");
                    for (int k = start; k < end; k++) {
                        if (k == i) System.out.print("[");
                        System.out.print(String.format("%02x ", data[k] & 0xFF));
                        if (k == i + name.length() - 1) System.out.print("]");
                    }
                    System.out.println();
                }
            }
        }
    }
}
