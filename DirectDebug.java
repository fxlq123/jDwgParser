import io.dwg.core.io.*;
import io.dwg.core.version.DwgVersion;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class DirectDebug {
    public static void main(String[] args) throws Exception {
        byte[] data = Files.readAllBytes(Paths.get(
            "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));

        System.out.println("=== 直接解析 0x52b9 处的 BLOCK_HEADER ===");

        // 已知: @ 0x52b9 有 BLOCK_HEADER (MS=114, BS=48)
        int pos = 0x52b9;

        // 显示原始字节
        System.out.print("原始字节: ");
        for (int i = 0; i < 20; i++) System.out.printf("%02X ", data[pos+i] & 0xFF);
        System.out.println();

        // 用 BitStreamReader 解析
        byte[] sub = Arrays.copyOfRange(data, pos, data.length);
        ByteBufferBitInput buf = new ByteBufferBitInput(ByteBuffer.wrap(sub));
        BitStreamReader r = new BitStreamReader(buf, DwgVersion.R2000);

        System.out.println("\n1. readModularShort() = " + r.readModularShort());
        System.out.println("   position after MS: " + buf.position());

        int tc = r.readBitShort();
        System.out.println("2. readBitShort() = " + tc + " (0x" + Integer.toHexString(tc) + ")");
        System.out.println("   position after BS: " + buf.position());

        System.out.println("\n期望: MS=114, BS=48 (BLOCK_HEADER)");

        // 尝试解析
        if (tc == 48) {
            System.out.println("\n✓ 正确识别为 BLOCK_HEADER!");

            // 继续解析 common header
            System.out.println("\n3. Common Header:");
            try {
                int bitsize = r.readBitLong();
                System.out.println("   bitsize: " + bitsize);

                long entHandle = r.readHandle();
                System.out.println("   entity handle: " + entHandle);

                // EED
                int eedSize = r.readModularShort();
                System.out.println("   EED size: " + eedSize);
                if (eedSize > 0 && eedSize < 200) {
                    buf.seek(buf.position() + eedSize * 8L);
                }

                // owner
                long owner = r.readHandle();
                System.out.println("   owner: " + owner);

                // num reactors
                int numReactors = r.readBitLong();
                System.out.println("   num reactors: " + numReactors);
                for (int i = 0; i < numReactors; i++) {
                    try {
                        long rh = r.readHandle();
                        System.out.println("     reactor[" + i + "]: " + rh);
                    } catch (Exception e) { break; }
                }

                // xdict
                try {
                    long xdict = r.readHandle();
                    System.out.println("   xdict: " + xdict);
                } catch (Exception e) {}

                // BLOCK_HEADER 特有
                // name: 1-byte length + ASCII
                System.out.println("\n4. BLOCK_HEADER 特有字段:");
                long namePos = buf.position();
                buf.seek(((namePos + 7) / 8) * 8); // 对齐到字节
                int nameLen = 0;
                for (int i = 0; i < 8; i++) nameLen = (nameLen << 1) | (buf.readBit() ? 1 : 0);
                System.out.println("   name length: " + nameLen);
                if (nameLen > 0 && nameLen < 100) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < nameLen; i++) {
                        int ch = 0;
                        for (int j = 0; j < 8; j++) ch = (ch << 1) | (buf.readBit() ? 1 : 0);
                        sb.append((char)ch);
                    }
                    System.out.println("   name: '" + sb.toString() + "'");
                }

                // flags
                int flags = r.readBitShort();
                System.out.println("   flags: " + flags);

                // base point (3 BD)
                double bx = r.readBitDouble();
                double by = r.readBitDouble();
                double bz = r.readBitDouble();
                System.out.printf("   base point: (%.3f, %.3f, %.3f)%n", bx, by, bz);

                // xref path
                long xrefPos = buf.position();
                buf.seek(((xrefPos + 7) / 8) * 8);
                int xrefLen = 0;
                for (int i = 0; i < 8; i++) xrefLen = (xrefLen << 1) | (buf.readBit() ? 1 : 0);
                System.out.println("   xref length: " + xrefLen);
                if (xrefLen > 0 && xrefLen < 100) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < xrefLen; i++) {
                        int ch = 0;
                        for (int j = 0; j < 8; j++) ch = (ch << 1) | (buf.readBit() ? 1 : 0);
                        sb.append((char)ch);
                    }
                    System.out.println("   xref path: '" + sb.toString() + "'");
                }

            } catch (Exception e) {
                System.out.println("   解析错误: " + e);
                e.printStackTrace();
            }
        } else {
            System.out.println("\n✗ typeCode 不对!");
            System.out.println("   字节分析:");
            System.out.println("   byte[0-1] (MS): 0x72 0x00");
            System.out.println("   byte[2-3] (BS): 0x4C 0x12");

            // 手动检查 BS
            int b1 = data[pos+2] & 0xFF;
            int b2 = data[pos+3] & 0xFF;
            System.out.println("   b1 = 0x" + Integer.toHexString(b1) + " = " + Integer.toBinaryString(b1));
            System.out.println("   b2 = 0x" + Integer.toHexString(b2) + " = " + Integer.toBinaryString(b2));
        }

        // 扫描一段，统计类型
        System.out.println("\n\n=== 扫描 0x5000-0x6000 的类型分布 ===");
        Map<Integer, Integer> tcCount = new LinkedHashMap<>();
        int scanned = 0;
        int skipped = 0;

        for (int scan = 0x5000; scan < 0x6000; ) {
            if (data[scan] == 0) { scan++; skipped++; continue; }

            byte[] s = Arrays.copyOfRange(data, scan, data.length);
            ByteBufferBitInput b = new ByteBufferBitInput(ByteBuffer.wrap(s));
            BitStreamReader r2 = new BitStreamReader(b, DwgVersion.R2000);

            try {
                int ms = r2.readModularShort();
                if (ms <= 2 || ms > 2000) { scan++; continue; }
                int code = r2.readBitShort();
                if (code >= 0 && code <= 255) {
                    tcCount.put(code, tcCount.getOrDefault(code, 0) + 1);
                    scanned++;
                }
                scan += 2;
            } catch (Exception e) {
                scan++;
            }
        }

        System.out.println("扫描了 " + scanned + " 个对象, 跳过 " + skipped);
        System.out.println("\n类型分布:");
        List<Map.Entry<Integer, Integer>> sorted = new ArrayList<>(tcCount.entrySet());
        sorted.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        for (Map.Entry<Integer, Integer> e : sorted) {
            System.out.printf("  type=%3d (0x%02X): %4d%n", e.getKey(), e.getKey(), e.getValue());
        }
    }
}
