import io.dwg.core.io.*;
import io.dwg.core.version.DwgVersion;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class DebugTypes {
    public static void main(String[] args) throws Exception {
        byte[] data = Files.readAllBytes(Paths.get(
            "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));

        int start = 0x5000;
        int end = Math.min(data.length - 16, 0x12000);
        Map<Integer, Integer> typeCount = new TreeMap<>();
        int pos = start;
        int scanned = 0;
        int skipped = 0;

        while (pos < end && scanned < 1000) {
            if (pos >= data.length - 4) break;
            if (data[pos] == 0) { pos++; skipped++; continue; }

            try {
                byte[] sub = Arrays.copyOfRange(data, pos, data.length);
                ByteBufferBitInput buf = new ByteBufferBitInput(ByteBuffer.wrap(sub));
                BitStreamReader r = new BitStreamReader(buf, DwgVersion.R2000);

                int objSize = r.readModularShort();
                if (objSize <= 2 || objSize > 2000) { pos++; skipped++; continue; }

                int typeCode = r.readBitShort();
                if (typeCode < 0 || typeCode > 255) { pos++; skipped++; continue; }

                typeCount.put(typeCode, typeCount.getOrDefault(typeCode, 0) + 1);
                scanned++;

                // 显示前几个 type=48
                if (typeCode == 48 && scanned <= 5) {
                    System.out.printf("Found BLOCK_HEADER @ 0x%x: MS=%d, type=%d%n", pos, objSize, typeCode);
                }

                pos += 2 + objSize;
            } catch (Exception e) {
                pos++;
                skipped++;
            }
        }

        System.out.println("\n扫描: " + scanned + ", 跳过: " + skipped);
        System.out.println("\n类型分布:");
        for (Map.Entry<Integer, Integer> e : typeCount.entrySet()) {
            System.out.printf("  type=%3d (0x%02X): %4d  %s%n",
                e.getKey(), e.getKey(), e.getValue(), typeName(e.getKey()));
        }
    }

    static String typeName(int code) {
        switch(code) {
            case 0: return "UNUSED";
            case 5: return "ENDBLK";
            case 7: return "INSERT";
            case 48: return "BLOCK_HEADER";
            case 49: return "BLOCK_END";
            default: return "TYPE_" + code;
        }
    }
}
