import io.dwg.core.io.BitStreamReader;
import io.dwg.core.io.ByteBufferBitInput;
import io.dwg.core.version.DwgVersion;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * 调试器 - 分析 R2000 对象结构
 */
public class DebugR2000Objects {

    public static void main(String[] args) throws Exception {
        byte[] data = Files.readAllBytes(Paths.get("210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));
        System.out.println("文件大小: " + data.length + " bytes\n");

        // 统计前 200 个对象的类型码
        Map<Integer, Integer> typeCounts = new HashMap<>();
        List<int[]> foundObjects = new ArrayList<>();

        int pos = 0x5000;
        int scanCount = 0;

        System.out.println("=== 扫描前 200 个有效对象 ===\n");

        while (pos < data.length - 8 && scanCount < 200) {
            byte[] slice = Arrays.copyOfRange(data, pos, data.length);
            ByteBufferBitInput input = new ByteBufferBitInput(ByteBuffer.wrap(slice));
            BitStreamReader reader = new BitStreamReader(input, DwgVersion.R2000);

            int objSize;
            try {
                objSize = reader.readModularShort();
            } catch (Exception e) {
                pos++;
                continue;
            }

            if (objSize < 4 || objSize > 4096) {
                pos++;
                continue;
            }

            int typeCode;
            try {
                typeCode = reader.readBitShort();
            } catch (Exception e) {
                pos++;
                continue;
            }

            if (typeCode < 0 || typeCode > 1024) {
                pos++;
                continue;
            }

            typeCounts.put(typeCode, typeCounts.getOrDefault(typeCode, 0) + 1);
            foundObjects.add(new int[]{pos, objSize, typeCode});
            scanCount++;

            // 跳到下一个对象
            long dataStartBits = reader.position();
            pos += (int)((dataStartBits + objSize * 8L) / 8);
        }

        // 输出类型统计
        System.out.println("前 200 个对象的类型码统计:\n");
        List<Map.Entry<Integer, Integer>> sorted = new ArrayList<>(typeCounts.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

        for (Map.Entry<Integer, Integer> e : sorted) {
            String typeName = getTypeName(e.getKey());
            System.out.printf("  type=0x%03x (%4d): %3d 个 %s%n",
                e.getKey(), e.getKey(), e.getValue(), typeName);
        }

        // 详细查看前 10 个 type=0x07 和找 type=0x30
        System.out.println("\n=== 前 10 个 type=0x07 对象 详情 ===\n");
        int shown = 0;
        for (int[] obj : foundObjects) {
            if (obj[2] == 0x07 && shown < 10) {
                dumpObject(data, obj[0], obj[1], obj[2]);
                shown++;
            }
        }

        // 查看 type=0x30 对象
        System.out.println("\n=== type=0x30 对象 ===\n");
        shown = 0;
        for (int[] obj : foundObjects) {
            if (obj[2] == 0x30 && shown < 10) {
                dumpObject(data, obj[0], obj[1], obj[2]);
                shown++;
            }
        }
        if (shown == 0) {
            System.out.println("  未找到 type=0x30 对象");
            // 查看附近的 type
            System.out.println("  附近的 type: ");
            for (int i = 0x28; i <= 0x40; i++) {
                if (typeCounts.containsKey(i)) {
                    System.out.printf("    type=0x%02x: %d 个%n", i, typeCounts.get(i));
                }
            }
        }
    }

    private static String getTypeName(int typeCode) {
        switch (typeCode) {
            case 0x07: return "[INSERT]";
            case 0x30: return "[BLOCK_HEADER]";
            case 0x01: return "[TEXT]";
            case 0x0F: return "[CIRCLE]";
            case 0x08: return "[LINE]";
            case 0x02: return "[LWPOLYLINE]";
            case 0x03: return "[POLYLINE]";
            case 0x32: return "[LTYPE]";
            case 0x33: return "[LAYER]";
            case 0x34: return "[STYLE]";
            case 0x05: return "[ELLIPSE]";
            case 0x06: return "[ARC]";
            case 0x0E: return "[POINT]";
            default: return "";
        }
    }

    private static void dumpObject(byte[] data, int offset, int size, int typeCode) {
        System.out.printf("Object @ 0x%x: size=%d bytes, type=0x%03x%n", offset, size, typeCode);
        System.out.print("  前 32 字节: ");
        for (int i = 0; i < Math.min(size, 32); i++) {
            System.out.printf("%02X ", data[offset + i] & 0xFF);
        }
        System.out.println();

        // 尝试在对象中找可打印字符串
        System.out.print("  ASCII 查找: ");
        for (int i = 0; i < Math.min(size, 64); i++) {
            int b = data[offset + i] & 0xFF;
            if (b >= 32 && b < 127) System.out.print((char)b);
            else System.out.print(".");
        }
        System.out.println();
        System.out.println();
    }
}
