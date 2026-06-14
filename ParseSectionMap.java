import java.nio.file.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

public class ParseSectionMap {
    static byte[] data;

    static void dumpRange(String label, int start, int length) {
        System.out.println("--- " + label + " (0x" + Integer.toHexString(start) + ") ---");
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
        System.out.println();
    }

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

    public static void main(String[] args) throws Exception {
        data = Files.readAllBytes(Paths.get("210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));
        ByteBuffer bb = ByteBuffer.wrap(data);
        bb.order(ByteOrder.LITTLE_ENDIAN);

        // Look at section map area - R2000 stores section info near the end
        // Header value at 0x2c = 0x11943 = 72003
        dumpRange("0x11943 area", 0x11943, 200);

        // Also check what's around 0x11900
        dumpRange("0x11900 area", 0x11900, 200);

        // Check at 0x4e00 (header value at 0x24-0x27)
        int v24 = bb.getInt(0x24);
        System.out.println("Int at 0x24: " + v24 + " (0x" + Integer.toHexString(v24) + ")");

        // Let's look for a potential section map:
        // Format could be: [num_sections][num_entries x (section_type, offset, size)]
        // Try positions near the end
        for (int tryPos = data.length - 500; tryPos < data.length - 12; tryPos += 4) {
            int numSections = bb.getInt(tryPos);
            if (numSections > 0 && numSections < 20) {
                // Check if this looks like a section map
                int pos = tryPos + 4;
                boolean valid = true;
                List<int[]> entries = new ArrayList<>();
                for (int s = 0; s < numSections; s++) {
                    if (pos + 12 >= data.length) { valid = false; break; }
                    int type = bb.getInt(pos);
                    int offset = bb.getInt(pos + 4);
                    int size = bb.getInt(pos + 8);
                    if (type < 0 || type > 50 || offset < 0 || offset >= data.length ||
                        size <= 0 || size >= data.length) { valid = false; break; }
                    entries.add(new int[]{type, offset, size});
                    pos += 12;
                }
                if (valid) {
                    System.out.println("\n=== Section map candidate at 0x" + Integer.toHexString(tryPos) +
                            " with " + numSections + " entries ===");
                    for (int[] e : entries) {
                        System.out.println(String.format("  Type %d: offset=0x%04x, size=%d", e[0], e[1], e[2]));
                    }
                }
            }
        }

        // Now try: maybe handles section is somewhere specific
        // In AC1015, section IDs: 0=ImageHeader, 1=Classes, 2=Handles
        // Check what's at offset found in header
        dumpRange("At 0x3500 (from header)", 0x3500, 64);
        dumpRange("At 0x1500 (search area)", 0x1500, 64);
        dumpRange("At 0x07d0 (from header value 2000)", 0x07d0, 64);
    }
}
