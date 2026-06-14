package run;

import java.nio.ByteBuffer;
import java.nio.file.Paths;

/**
 * 分析 R2000 文件的所有 section，找出实际的对象数据位置
 */
public class R2000SectionExplorer {

    public static void main(String[] args) throws Exception {
        String filename = "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg";
        byte[] data = java.nio.file.Files.readAllBytes(Paths.get(filename));

        System.out.println("文件大小: " + data.length + " 字节");
        System.out.println();

        // 打印前16字节的十六进制
        System.out.println("文件头部 (前32字节):");
        for (int i = 0; i < 32; i++) {
            if (i % 16 == 0) System.out.print("  " + String.format("%04X: ", i));
            System.out.print(String.format("%02X ", data[i]));
            if (i % 16 == 15) {
                for (int j = i - 15; j <= i; j++) {
                    char c = (char) (data[j] & 0xFF);
                    System.out.print((c >= 32 && c < 127) ? c : '.');
                }
                System.out.println();
            }
        }
        System.out.println();

        // 解析 header 后的 section locators
        ByteBuffer bb = ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int offset = 0x14; // 从 DWG header 内部开始寻找

        // 先看 libredwg 标准 R2000 header 格式
        // 0x00-0x05: "AC1015"
        // 0x06-0x0B: 6 bytes reserved
        // 0x0C: RC unknown
        // 0x0D-0x10: RL preview address
        // 0x11: RC unknown
        // 0x12: RC maintenance version
        // 0x13-0x14: RS codepage
        // 0x15-0x18: RL section count
        // 0x19-...: locators (RC + RL + RL = 9 bytes each)

        System.out.println("尝试解析 R2000 header:");
        String ver = new String(data, 0, 6, java.nio.charset.StandardCharsets.US_ASCII);
        System.out.println("  Version: " + ver);

        offset = 0x15;
        int sectionCount = bb.getInt(offset);
        offset += 4;
        System.out.println("  Section count: " + sectionCount);

        // 每个 locator: number (RC 1字节) + seeker (RL 4字节) + size (RL 4字节) = 9字节
        long[][] locators = new long[sectionCount][3];
        for (int i = 0; i < sectionCount; i++) {
            int num = data[offset] & 0xFF;
            offset += 1;
            long seeker = bb.getInt(offset) & 0xFFFFFFFFL;
            offset += 4;
            long size = bb.getInt(offset) & 0xFFFFFFFFL;
            offset += 4;
            locators[i] = new long[] { num, seeker, size };
            System.out.println("  Section #" + num + " @ 0x" + Long.toHexString(seeker) +
                " size=" + size);
        }

        // 分析每个 section 的前32字节
        System.out.println();
        System.out.println("各 section 的内容分析:");
        for (int i = 0; i < sectionCount; i++) {
            long secOff = locators[i][1];
            long secSize = locators[i][2];
            int secNum = (int) locators[i][0];

            System.out.println("--- Section #" + secNum + " @ 0x" +
                Long.toHexString(secOff) + " size=" + secSize + " ---");

            int printSize = (int) Math.min(secSize, 48L);
            StringBuilder hex = new StringBuilder();
            StringBuilder ascii = new StringBuilder();
            for (int j = 0; j < printSize; j++) {
                int b = data[(int) secOff + j] & 0xFF;
                hex.append(String.format("%02X ", b));
                char c = (char) b;
                ascii.append((c >= 32 && c < 127) ? c : '.');
                if ((j + 1) % 16 == 0) {
                    System.out.println("  " + hex + " |" + ascii + "|");
                    hex.setLength(0);
                    ascii.setLength(0);
                }
            }
            if (hex.length() > 0) {
                while (hex.length() < 48) hex.append("   ");
                System.out.println("  " + hex + " |" + ascii + "|");
            }
            System.out.println();
        }

        // 关键问题: R2000 的对象数据不是存储在单独的 Objects section 中
        // 对象数据可能直接存储在文件的某个区域，或者通过 Handles section 中的偏移来定位
        // 让我们检查 Handles section (通常是 #2)
        System.out.println();
        System.out.println("=== Handles Section (Section #2) 详细分析 ===");
        int handlesIdx = -1;
        for (int i = 0; i < sectionCount; i++) {
            if (locators[i][0] == 2) handlesIdx = i;
        }
        if (handlesIdx >= 0) {
            long handOff = locators[handlesIdx][1];
            long handSize = locators[handlesIdx][2];
            System.out.println("Handles section @ 0x" + Long.toHexString(handOff) +
                " size=" + handSize);

            // 打印前64字节
            System.out.println("前64字节:");
            for (int i = 0; i < Math.min(handSize, 64L); i++) {
                if (i % 16 == 0) System.out.print("  " + String.format("%04X: ", i));
                System.out.print(String.format("%02X ", data[(int) handOff + i]));
                if (i % 16 == 15) System.out.println();
            }
            System.out.println();
        }

        // 最后，检查 Section #3 (可能是对象数据的头部？)
        System.out.println();
        System.out.println("=== Section #3 详细分析 (可能不是实际的对象数据) ===");
        int objIdx = -1;
        for (int i = 0; i < sectionCount; i++) {
            if (locators[i][0] == 3) objIdx = i;
        }
        if (objIdx >= 0) {
            long objOff = locators[objIdx][1];
            long objSize = locators[objIdx][2];
            System.out.println("Section #3 @ 0x" + Long.toHexString(objOff) + " size=" + objSize);

            // 打印全部内容
            System.out.println("内容:");
            for (int i = 0; i < objSize; i++) {
                if (i % 16 == 0) System.out.print("  " + String.format("%04X: ", i));
                System.out.print(String.format("%02X ", data[(int) objOff + i]));
                if (i % 16 == 15) System.out.println();
            }
            System.out.println();
        }

        // 重要：R2000 可能在 locator 之后直接存储对象数据
        // 或者对象数据存储在某个大的 section 中
        // 让我们检查 section #1 (Classes section, 通常是 #1)
        System.out.println();
        System.out.println("=== Section #1 (Classes) 详细分析 ===");
        int classIdx = -1;
        for (int i = 0; i < sectionCount; i++) {
            if (locators[i][0] == 1) classIdx = i;
        }
        if (classIdx >= 0) {
            long clsOff = locators[classIdx][1];
            long clsSize = locators[classIdx][2];
            System.out.println("Classes section @ 0x" + Long.toHexString(clsOff) + " size=" + clsSize);
            System.out.println("前48字节:");
            for (int i = 0; i < Math.min(clsSize, 48L); i++) {
                if (i % 16 == 0) System.out.print("  " + String.format("%04X: ", i));
                System.out.print(String.format("%02X ", data[(int) clsOff + i]));
                if (i % 16 == 15) System.out.println();
            }
            System.out.println();
        }

        // 检查文件中的大段数据，看看是否直接在某个地方存储对象
        // 检查 Header section (#0) 之后的区域直到文件末尾
        // 或者检查每个 section locator 之后的大段数据
        System.out.println();
        System.out.println("=== 检查大段数据可能是对象流的位置 ===");

        // 按偏移量排序 locators
        java.util.Arrays.sort(locators, (a, b) -> Long.compare(a[1], b[1]));
        for (int i = 0; i < locators.length; i++) {
            long start = locators[i][1];
            long end = start + locators[i][2];
            System.out.println("  Section #" + locators[i][0] +
                ": 0x" + Long.toHexString(start) + " - 0x" + Long.toHexString(end) +
                " (" + locators[i][2] + " bytes)");
        }

        // 看看文件的剩余部分在哪里
        long maxEnd = 0;
        for (long[] loc : locators) maxEnd = Math.max(maxEnd, loc[1] + loc[2]);
        System.out.println("  文件末尾: 0x" + Long.toHexString(data.length));
        System.out.println("  最后一个 section 结束于: 0x" + Long.toHexString(maxEnd));

        // 检查最大的 section (可能是对象数据)
        System.out.println();
        System.out.println("=== 最大的 section 内容分析 ===");
        int maxSectionIdx = 0;
        long maxSize = 0;
        for (int i = 0; i < sectionCount; i++) {
            if (locators[i][2] > maxSize) {
                maxSize = locators[i][2];
                maxSectionIdx = i;
            }
        }

        long maxSecOff = locators[maxSectionIdx][1];
        long maxSecSize = locators[maxSectionIdx][2];
        int maxSecNum = (int) locators[maxSectionIdx][0];
        System.out.println("最大 section: #" + maxSecNum +
            " @ 0x" + Long.toHexString(maxSecOff) + " size=" + maxSecSize);

        // 打印这个 section 的前64字节
        System.out.println("前64字节:");
        for (int i = 0; i < Math.min(maxSecSize, 64L); i++) {
            if (i % 16 == 0) System.out.print("  " + String.format("%04X: ", i));
            System.out.print(String.format("%02X ", data[(int) maxSecOff + i]));
            if (i % 16 == 15) System.out.println();
        }
    }
}
