import io.dwg.core.io.*;
import io.dwg.core.version.DwgVersion;
import io.dwg.core.type.DwgHandleRef;
import io.dwg.core.type.Point3D;
import io.dwg.entities.*;
import io.dwg.entities.concrete.*;
import io.dwg.sections.objects.ObjectTypeResolver;
import io.dwg.sections.classes.DwgClassRegistry;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class DebugParse {
    public static void main(String[] args) throws Exception {
        byte[] data = Files.readAllBytes(Paths.get(
            "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));
        DwgVersion version = DwgVersion.R2000;

        System.out.println("文件大小: " + data.length);
        System.out.println("已知: BLOCK_HEADER @ 0x52b9 (byte 0x72 = 114)\n");

        // 直接测试 offset 0x52b9
        System.out.println("=== 直接测试 offset 0x52b9 ===");
        testOffset(data, version, 0x52b9);

        // 测试 0x52b9 + 2 + 114 = 0x5335
        System.out.println("\n=== 测试 offset 0x5335 ===");
        testOffset(data, version, 0x5335);

        // 测试 0x5335 + 2 + 未知 = ?
        // 先扫描一段看看结构

        System.out.println("\n=== 扫描 0x5000-0x6000 的前 20 个对象 ===");
        scanAndShow(data, version, 0x5000, 0x6000, 20);
    }

    static void testOffset(byte[] data, DwgVersion version, int offset) {
        if (offset < 0 || offset >= data.length - 10) {
            System.out.println("offset 超出范围");
            return;
        }

        System.out.printf("文件 offset=0x%x (%d):%n", offset, offset);
        System.out.print("  字节: ");
        for (int i = 0; i < 16; i++) {
            System.out.printf("%02X ", data[offset + i] & 0xFF);
        }
        System.out.println();

        try {
            byte[] sub = Arrays.copyOfRange(data, offset, data.length);
            ByteBuffer bb = ByteBuffer.wrap(sub);
            ByteBufferBitInput buf = new ByteBufferBitInput(bb);
            BitStreamReader r = new BitStreamReader(buf, version);

            int msValue = r.readModularShort();
            System.out.printf("  MS value: %d (0x%02X)%n", msValue, msValue);

            int bsValue = r.readBitShort();
            System.out.printf("  BS value: %d (0x%02X) = %s%n", bsValue, bsValue,
                typeName(bsValue));

            // 验证
            if (offset == 0x52b9 && msValue == 114 && bsValue == 48) {
                System.out.println("  ✓ 验证: 这是 BLOCK_HEADER!");
            }

        } catch (Exception e) {
            System.out.println("  解析错误: " + e.getMessage());
        }
    }

    static void scanAndShow(byte[] data, DwgVersion version, int start, int end, int limit) {
        int pos = start;
        int count = 0;

        while (pos < end - 4 && count < limit) {
            if (pos == 0x52b9) {
                System.out.printf("\n*** BLOCK_HEADER @ 0x%x ***%n", pos);
            }

            // 快速检查 MS
            int firstByte = data[pos] & 0xFF;
            if (firstByte == 0) { pos++; continue; }

            int msValue;
            int msBytes;
            if (firstByte < 0x80) {
                msValue = firstByte;
                msBytes = 1;
            } else {
                if (pos + 1 >= end) break;
                int secondByte = data[pos + 1] & 0xFF;
                msValue = (firstByte & 0x7F) | ((secondByte & 0x7F) << 7);
                msBytes = 2;
            }

            if (msValue <= 2 || msValue > 2000) { pos++; continue; }

            // 检查 BS
            int bsOffset = pos + msBytes;
            if (bsOffset >= end) break;
            int b1 = data[bsOffset] & 0xFF;
            int opcode = (b1 >> 6) & 0x3;

            if (opcode != 0 && opcode != 1) { pos++; continue; }

            int typeCode;
            int bsBytes;
            if (opcode == 1) {
                int b2 = data[bsOffset + 1] & 0xFF;
                typeCode = ((b1 & 0x3F) << 2) | ((b2 >> 6) & 0x3);
                bsBytes = 2;
            } else {
                int b2 = data[bsOffset + 1] & 0xFF;
                int b3 = data[bsOffset + 2] & 0xFF;
                typeCode = (((b1 & 0x3F) << 2) | ((b2 >> 6) & 0x3))
                         | ((((b2 & 0x3F) << 2) | ((b3 >> 6) & 0x3)) << 8);
                bsBytes = 3;
            }

            if (typeCode < 0 || typeCode > 500) { pos++; continue; }

            // 打印
            System.out.printf("[%2d] @ 0x%x: MS=%3d bytes, BS: type=%3d (0x%02X) = %s",
                count, pos, msValue, typeCode, typeCode, typeName(typeCode));

            // 尝试实际解析
            try {
                byte[] sub = Arrays.copyOfRange(data, pos, data.length);
                ByteBuffer bb = ByteBuffer.wrap(sub);
                ByteBufferBitInput buf = new ByteBufferBitInput(bb);
                BitStreamReader r = new BitStreamReader(buf, version);

                int _ms = r.readModularShort();
                int _bs = r.readBitShort();

                if (_bs != typeCode) {
                    System.out.printf(" ← MISMATCH (got %d)%n", _bs);
                } else if (typeCode == 48) {
                    // BLOCK_HEADER - 尝试解析
                    try {
                        int bitsize = r.readBitLong();
                        long h = r.readHandle();
                        // EED
                        int eedSize = r.readModularShort();
                        if (eedSize > 0 && eedSize < 200) {
                            buf.seek(buf.position() + eedSize * 8L);
                        }
                        // owner
                        long owner = r.readHandle();
                        // num reactors
                        int nr = r.readBitLong();
                        for (int i = 0; i < Math.min(nr, 5); i++) {
                            try { r.readHandle(); } catch (Exception ex) { break; }
                        }
                        // xdict
                        try { r.readHandle(); } catch (Exception ex) {}

                        // block name: 1-byte len + ASCII
                        long namePos = buf.position();
                        buf.seek(((namePos + 7) / 8) * 8);
                        int nameLen = 0;
                        for (int i = 0; i < 8; i++) nameLen = (nameLen << 1) | (buf.readBit() ? 1 : 0);
                        if (nameLen > 0 && nameLen < 100) {
                            StringBuilder sb = new StringBuilder();
                            for (int i = 0; i < nameLen; i++) {
                                int ch = 0;
                                for (int j = 0; j < 8; j++) ch = (ch << 1) | (buf.readBit() ? 1 : 0);
                                sb.append((char)ch);
                            }
                            System.out.printf("  name='%s'", sb.toString());
                        }
                    } catch (Exception e) {}
                    System.out.println();
                } else {
                    System.out.println();
                }
            } catch (Exception e) {
                System.out.println(" ← 解析错误: " + e.getMessage());
            }

            int next = pos + msBytes + bsBytes + msValue;
            if (next <= pos) next = pos + 1;
            pos = next;
            count++;
        }
    }

    static String typeName(int code) {
        if (code < 0) return "?";
        DwgObjectType type = DwgObjectType.fromCode(code);
        if (type != null) return type.name();
        switch(code) {
            case 48: return "BLOCK_HEADER";
            case 49: return "BLOCK_END";
            case 7: return "INSERT";
            case 5: return "ENDBLK";
            default: return "TYPE_" + code;
        }
    }
}
