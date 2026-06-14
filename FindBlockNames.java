import io.dwg.core.io.*;
import io.dwg.core.version.DwgVersion;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class FindBlockNames {
    public static void main(String[] args) throws Exception {
        byte[] data = Files.readAllBytes(Paths.get(
            "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));

        System.out.println("=== 查找所有 BLOCK_HEADER 和它们的名称 ===\n");

        int pos = 0x52b9;  // 第一个对象
        int maxPos = 0x12000;

        while (pos < maxPos) {
            if (pos >= data.length - 4) break;
            if (data[pos] == 0) { pos++; continue; }

            try {
                byte[] sub = Arrays.copyOfRange(data, pos, data.length);
                ByteBufferBitInput buf = new ByteBufferBitInput(ByteBuffer.wrap(sub));
                BitStreamReader r = new BitStreamReader(buf, DwgVersion.R2000);

                int objSize = r.readModularShort();
                if (objSize <= 2 || objSize > 2000) { pos++; continue; }

                int typeCode = r.readBitShort();
                if (typeCode < 0 || typeCode > 255) { pos++; continue; }

                // 只处理 BLOCK_HEADER
                if (typeCode == 48) {
                    System.out.printf("BLOCK_HEADER @ 0x%x (size=%d):%n", pos, objSize);

                    // 显示原始字节
                    System.out.print("  bytes: ");
                    for (int i = 0; i < Math.min(objSize, 40); i++) {
                        System.out.printf("%02X ", data[pos + i] & 0xFF);
                    }
                    System.out.println();

                    // 跳过 common header，尝试找 name
                    // entity common: bitsize(4) + entity_handle(?) + EED + owner + numReactors + xdict
                    // 假设 common header 大约 20-30 字节

                    System.out.print("  扫描 ASCII 文本:");
                    for (int start = 4; start < objSize - 2; start++) {
                        int b = data[pos + start] & 0xFF;
                        if (b >= 32 && b < 127) {
                            StringBuilder sb = new StringBuilder();
                            for (int i = start; i < Math.min(start + 32, pos + objSize); i++) {
                                int bb = data[pos + i] & 0xFF;
                                if (bb >= 32 && bb < 127) sb.append((char)bb);
                                else break;
                            }
                            if (sb.length() >= 3) {
                                System.out.printf(" [byte %d]: '%s'", start, sb.toString());
                            }
                        }
                    }
                    System.out.println();
                    System.out.println();
                }

                pos += 2 + objSize;
            } catch (Exception e) {
                pos++;
            }
        }
    }
}
