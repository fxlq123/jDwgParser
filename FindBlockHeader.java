import io.dwg.core.io.*;
import io.dwg.core.version.DwgVersion;
import io.dwg.entities.*;
import io.dwg.entities.concrete.*;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class FindBlockHeader {
    public static void main(String[] args) throws Exception {
        byte[] data = Files.readAllBytes(Paths.get(
            "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));
        DwgVersion version = DwgVersion.R2000;

        System.out.println("=== 搜索 type=48 (BLOCK_HEADER) ===");

        int start = 0x5000;
        int end = Math.min(data.length - 16, 0x12000);
        List<int[]> blockHeaders = new ArrayList<>();

        for (int pos = start; pos < end; ) {
            if (pos >= data.length - 4) break;
            if (data[pos] == 0) { pos++; continue; }

            try {
                byte[] sub = Arrays.copyOfRange(data, pos, data.length);
                ByteBufferBitInput buf = new ByteBufferBitInput(ByteBuffer.wrap(sub));
                BitStreamReader r = new BitStreamReader(buf, version);

                int objSize = r.readModularShort();
                if (objSize <= 2 || objSize > 2000) { pos++; continue; }

                int typeCode = r.readBitShort();
                if (typeCode < 0 || typeCode > 255) { pos++; continue; }

                if (typeCode == 48) {
                    blockHeaders.add(new int[]{pos, objSize, typeCode});
                }

                pos += 2 + objSize;
            } catch (Exception e) {
                pos++;
            }
        }

        System.out.println("找到 " + blockHeaders.size() + " 个 type=48");
        for (int[] bh : blockHeaders) {
            System.out.printf("  @ 0x%x: objSize=%d, type=%d%n", bh[0], bh[1], bh[2]);
        }

        // 也检查 type=72 (0x48)
        System.out.println("\n=== 搜索 type=72 (0x48) ===");
        List<int[]> type72 = new ArrayList<>();
        int pos = start;
        while (pos < end) {
            if (pos >= data.length - 4) break;
            if (data[pos] == 0) { pos++; continue; }

            try {
                byte[] sub = Arrays.copyOfRange(data, pos, data.length);
                ByteBufferBitInput buf = new ByteBufferBitInput(ByteBuffer.wrap(sub));
                BitStreamReader r = new BitStreamReader(buf, version);

                int objSize = r.readModularShort();
                if (objSize <= 2 || objSize > 2000) { pos++; continue; }

                int typeCode = r.readBitShort();
                if (typeCode < 0 || typeCode > 255) { pos++; continue; }

                if (typeCode == 72) {
                    type72.add(new int[]{pos, objSize, typeCode});
                }

                pos += 2 + objSize;
            } catch (Exception e) {
                pos++;
            }
        }

        System.out.println("找到 " + type72.size() + " 个 type=72");
        for (int[] t : type72) {
            System.out.printf("  @ 0x%x: objSize=%d, type=%d%n", t[0], t[1], t[2]);
        }

        // 显示所有类型
        System.out.println("\n=== 所有类型 ===");
        Map<Integer, Integer> allTypes = new TreeMap<>();
        pos = start;
        while (pos < end) {
            if (pos >= data.length - 4) break;
            if (data[pos] == 0) { pos++; continue; }

            try {
                byte[] sub = Arrays.copyOfRange(data, pos, data.length);
                ByteBufferBitInput buf = new ByteBufferBitInput(ByteBuffer.wrap(sub));
                BitStreamReader r = new BitStreamReader(buf, version);

                int objSize = r.readModularShort();
                if (objSize <= 2 || objSize > 2000) { pos++; continue; }

                int typeCode = r.readBitShort();
                if (typeCode < 0 || typeCode > 255) { pos++; continue; }

                allTypes.put(typeCode, allTypes.getOrDefault(typeCode, 0) + 1);
                pos += 2 + objSize;
            } catch (Exception e) {
                pos++;
            }
        }

        System.out.println("类型分布:");
        for (Map.Entry<Integer, Integer> e : allTypes.entrySet()) {
            DwgObjectType t = DwgObjectType.fromCode(e.getKey());
            String name = t != null ? t.name() : "UNKNOWN";
            System.out.printf("  type=%3d (0x%02X): %4d  %s%n", e.getKey(), e.getKey(), e.getValue(), name);
        }

        // 详细分析第一个 BLOCK_END (type=49) @ 0x52b9 - 114 = 0x522d
        System.out.println("\n=== 分析 0x52b9 附近的 BLOCK_END ===");
        int target = 0x522d; // 0x52b9 - 114 = 0x522d
        System.out.printf("@ 0x%x:%n", target);
        for (int i = 0; i < 10; i++) {
            System.out.printf("  byte[%d] = 0x%02X%n", i, data[target + i] & 0xFF);
        }

        byte[] sub = Arrays.copyOfRange(data, target, data.length);
        ByteBufferBitInput buf = new ByteBufferBitInput(ByteBuffer.wrap(sub));
        BitStreamReader r = new BitStreamReader(buf, version);
        int ms = r.readModularShort();
        int tc = r.readBitShort();
        System.out.println("MS = " + ms + ", type = " + tc);
    }
}
