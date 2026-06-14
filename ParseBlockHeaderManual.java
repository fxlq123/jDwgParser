import io.dwg.core.io.*;
import io.dwg.core.version.DwgVersion;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class ParseBlockHeaderManual {
    public static void main(String[] args) throws Exception {
        byte[] data = Files.readAllBytes(Paths.get(
            "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));

        int pos = 0x52b9;
        System.out.println("=== 手动解析 BLOCK_HEADER @ 0x" + Integer.toHexString(pos) + " ===");

        System.out.print("字节: ");
        for (int i = 0; i < 30; i++) System.out.printf("%02X ", data[pos+i] & 0xFF);
        System.out.println();

        // 字节分析:
        // 0-1: MS = 114 (72 00 LE16 = 114)
        // 2-3: BS = 48 (00 4C = 0x0030 = 48)
        // 4+: common header + block_header data

        byte[] sub = Arrays.copyOfRange(data, pos, data.length);
        ByteBufferBitInput buf = new ByteBufferBitInput(ByteBuffer.wrap(sub));
        BitStreamReader r = new BitStreamReader(buf, DwgVersion.R2000);

        int ms = r.readModularShort();
        System.out.println("MS = " + ms);

        int typeCode = r.readBitShort();
        System.out.println("typeCode = " + typeCode);

        // common header
        System.out.println("\n--- Common Header ---");
        System.out.println("position = " + buf.position() + " bits");

        // 手动读取 common header
        // 跳过 entity common - 尝试直接找到 name

        // 先跳过所有可能的字段
        // entity common: bitsize, entity_handle, EED_size, owner, numReactors, reactors, xdict
        // 然后是 block_header: name, flags, base_point, xref_path

        // 让我看看从不同位置开始读取 name
        System.out.println("\n--- 尝试不同的 name 读取方式 ---");

        // 尝试1: 从 byte[4] 开始读 (假设前面都是 1 字节编码)
        // MS (2 bytes) + BS (2 bytes) = 4 bytes, 然后 name 长度
        System.out.print("\n尝试1: name 从 byte[4] 开始:");
        buf.seek(4 * 8L);  // 从 bit 32 开始
        int nameLen = 0;
        for (int i = 0; i < 8; i++) nameLen = (nameLen << 1) | (buf.readBit() ? 1 : 0);
        System.out.println(" len=" + nameLen);
        if (nameLen > 0 && nameLen < 100) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < nameLen; i++) {
                int ch = 0;
                for (int j = 0; j < 8; j++) ch = (ch << 1) | (buf.readBit() ? 1 : 0);
                sb.append((char)ch);
            }
            System.out.println("  name = '" + sb.toString() + "'");
        }

        // 尝试2: 跳过一些 bits
        System.out.print("\n尝试2: 跳过 64 bits 然后读 name:");
        buf.seek(32 + 64);
        nameLen = 0;
        for (int i = 0; i < 8; i++) nameLen = (nameLen << 1) | (buf.readBit() ? 1 : 0);
        System.out.println(" len=" + nameLen);
        if (nameLen > 0 && nameLen < 100) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < nameLen; i++) {
                int ch = 0;
                for (int j = 0; j < 8; j++) ch = (ch << 1) | (buf.readBit() ? 1 : 0);
                sb.append((char)ch);
            }
            System.out.println("  name = '" + sb.toString() + "'");
        }

        // 尝试3: 直接扫描 byte[4..20] 找有意义的 ASCII
        System.out.println("\n尝试3: 扫描 byte[4..30] 找 ASCII:");
        for (int start = 4; start < 30; start++) {
            int b = data[pos + start] & 0xFF;
            if (b >= 32 && b < 127) {
                StringBuilder sb = new StringBuilder();
                for (int i = start; i < Math.min(start + 32, data.length); i++) {
                    int bb = data[pos + i] & 0xFF;
                    if (bb >= 32 && bb < 127) sb.append((char)bb);
                    else break;
                }
                if (sb.length() > 2) {
                    System.out.println("  byte[" + start + "]: '" + sb.toString() + "'");
                }
            }
        }

        // 尝试4: 字节数组格式的 name
        // R2000 的 name 可能是: 1-byte length + chars
        System.out.println("\n尝试4: 1-byte len name 从不同位置:");
        for (int start = 4; start < 20; start++) {
            int len = data[pos + start] & 0xFF;
            if (len >= 1 && len <= 30) {
                StringBuilder sb = new StringBuilder();
                boolean valid = true;
                for (int i = 0; i < len && start + 1 + i < data.length; i++) {
                    int ch = data[pos + start + 1 + i] & 0xFF;
                    if (ch >= 32 && ch < 127) sb.append((char)ch);
                    else { valid = false; break; }
                }
                if (valid && sb.length() == len) {
                    System.out.println("  byte[" + start + "] len=" + len + ": '" + sb.toString() + "'");
                }
            }
        }

        // 尝试5: 2-byte length
        System.out.println("\n尝试5: 2-byte len name (MS format):");
        for (int start = 4; start < 20; start++) {
            int len = (data[pos + start] & 0xFF) | ((data[pos + start + 1] & 0xFF) << 8);
            if (len >= 1 && len <= 30) {
                StringBuilder sb = new StringBuilder();
                boolean valid = true;
                for (int i = 0; i < len && start + 2 + i < data.length; i++) {
                    int ch = data[pos + start + 2 + i] & 0xFF;
                    if (ch >= 32 && ch < 127) sb.append((char)ch);
                    else { valid = false; break; }
                }
                if (valid && sb.length() == len) {
                    System.out.println("  byte[" + start + "] len=" + len + ": '" + sb.toString() + "'");
                }
            }
        }
    }
}
