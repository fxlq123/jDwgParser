import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * 正确解析 Handles Section - 处理多字节 MC 编码
 */
public class CorrectHandleParse {

    public static void main(String[] args) throws Exception {
        byte[] data = Files.readAllBytes(Paths.get("210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));

        // 正确的 MC 解码: 连续读,直到 bit7=0
        // 每个字节贡献 7 bits, 从低到高累积
        
        int pos = 0x11943;
        int pageSize = ((data[pos] & 0xFF) << 8) | (data[pos+1] & 0xFF);
        pos += 2;
        int pageEnd = 0x11943 + 2 + (pageSize - 4); // -4 for size(2) + CRC(2)
        
        System.out.println("Handles Section:");
        System.out.println("  Page size (BE): " + pageSize + " bytes");
        System.out.println("  Page data: 0x" + Integer.toHexString(0x11943 + 2) + " - 0x" + Integer.toHexString(pageEnd));
        System.out.println();

        long handle = 0;
        long offset = 0;
        int validObjects = 0;

        Map<Long, Integer> handleOffsets = new HashMap<>();

        System.out.println("前 30 个对象:\n");
        for (int pair = 0; pair < 2000 && pos < pageEnd; pair++) {
            // handle delta: MC (variable length)
            long hDelta = readMC(data, pos);
            pos += getMCLength(data, pos);
            handle += hDelta;

            // offset delta: MC (variable length)
            long oDelta = readMC(data, pos);
            pos += getMCLength(data, pos);
            offset += oDelta;

            // 验证: 在 offset 处,检查 MS size 和 type
            if (offset >= 0 && offset < data.length - 4) {
                int off = (int)offset;
                int ms = (data[off] & 0xFF) | ((data[off+1] & 0xFF) << 8);
                // bit-level type
                int rawTypeByte = data[off + 2] & 0xFF;

                if (ms > 8 && ms < 4096 && (rawTypeByte > 0 && rawTypeByte < 200)) {
                    handleOffsets.put(handle, (int)offset);

                    if (validObjects < 30 || rawTypeByte == 0x30 || rawTypeByte == 0x07) {
                        System.out.printf("Pair %3d: h=0x%-4x (%-5d) offset=0x%-5x (%-6d) MS=%-4d byte3=0x%02x%n",
                            pair, handle, handle, offset, offset, ms, rawTypeByte);
                    }
                    validObjects++;
                }
            }

            // Page boundary: skip CRC 2 bytes then new page size
            if (pos >= pageEnd - 2) {
                pos += 2; // CRC
                if (pos < data.length - 2) {
                    int nextPageSize = ((data[pos] & 0xFF) << 8) | (data[pos+1] & 0xFF);
                    if (nextPageSize > 4 && nextPageSize < 2048) {
                        pos += 2;
                        pageEnd = pos + (nextPageSize - 4);
                    } else {
                        break;
                    }
                } else {
                    break;
                }
            }
        }

        System.out.println("\n共找到: " + validObjects + " 个有效 handle-offset 对");

        // 现在在所有有效 offset 处找 BLOCK_HEADER (0x30) 和 INSERT (0x07)
        System.out.println("\n=== 查找 BLOCK_HEADER 和 INSERT ===\n");
        
        int blockHeaders = 0;
        int inserts = 0;
        Set<String> blockNames = new TreeSet<>();
        
        for (Map.Entry<Long, Integer> entry : handleOffsets.entrySet()) {
            long h = entry.getKey();
            int off = entry.getValue();
            
            // 读取 MS
            int ms = (data[off] & 0xFF) | ((data[off+1] & 0xFF) << 8);
            if (ms < 8 || ms >= 4096) continue;
            
            // 用位解析读 type
            byte[] slice = new byte[ms + 16];
            System.arraycopy(data, off, slice, 0, Math.min(ms + 16, data.length - off));
            
            // 手动位解析: MS (16-bit LE) + BS type
            // After 16 bits for MS: 2-bit opcode then conditional
            // bits 16-17: opcode
            int byte2 = data[off + 2] & 0xFF;
            int opcode = (byte2 >> 6) & 0x03; // bits 6-7 of byte 2 (first 2 bits after MS)
            
            int type;
            int bitsConsumed = 16; // MS = 16 bits
            
            if (opcode == 0) { // 16 bits
                // lo byte: bits 2-7 of byte2 + bits 0-1 of byte3
                int lo = 0;
                for (int i = 2; i < 8; i++) lo = (lo << 1) | ((byte2 >> (7-i)) & 1);
                int byte3 = data[off + 3] & 0xFF;
                for (int i = 0; i < 2; i++) lo = (lo << 1) | ((byte3 >> (7-i)) & 1);
                // hi byte: bits 2-7 of byte3 + all of byte4
                int hi = 0;
                for (int i = 2; i < 8; i++) hi = (hi << 1) | ((byte3 >> (7-i)) & 1);
                int byte4 = data[off + 4] & 0xFF;
                for (int i = 0; i < 8; i++) hi = (hi << 1) | ((byte4 >> (7-i)) & 1);
                type = lo | (hi << 8);
                bitsConsumed += 18; // 2 + 16
            } else if (opcode == 1) { // 8 bits
                // next 8 bits: bits 2-7 of byte2 + bits 0-1 of byte3
                int val = 0;
                for (int i = 2; i < 8; i++) val = (val << 1) | ((byte2 >> (7-i)) & 1);
                int byte3 = data[off + 3] & 0xFF;
                for (int i = 0; i < 2; i++) val = (val << 1) | ((byte3 >> (7-i)) & 1);
                type = val;
                bitsConsumed += 10; // 2 + 8
            } else if (opcode == 2) { // value = 0
                type = 0;
                bitsConsumed += 2;
            } else { // opcode == 3, value = 256
                type = 256;
                bitsConsumed += 2;
            }
            
            if (type == 0x30) { // BLOCK_HEADER
                blockHeaders++;
                // Try to find the block name in the object data
                // Look for ASCII text after header fields
                String name = findAsciiText(data, off + (bitsConsumed / 8), ms);
                blockNames.add(name);
                System.out.printf("BLOCK_HEADER h=0x%x @ 0x%x: size=%d bytes, name='%s'%n",
                    h, off, ms, name);
            } else if (type == 0x07) { // INSERT
                inserts++;
                if (inserts < 10) {
                    System.out.printf("INSERT h=0x%x @ 0x%x: size=%d bytes%n", h, off, ms);
                }
            }
        }
        
        System.out.println("\nBLOCK_HEADER 总数: " + blockHeaders);
        System.out.println("INSERT 总数: " + inserts);
        System.out.println("\n块名称列表:");
        for (String name : blockNames) {
            System.out.println("  " + name);
        }
    }

    // Modular Char decoding: variable length, each byte contributes 7 bits
    // while bit 7 is set, continue reading
    private static long readMC(byte[] data, int pos) {
        long result = 0;
        int shift = 0;
        int idx = pos;
        while (idx < data.length) {
            int b = data[idx++] & 0xFF;
            result |= (long)(b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
        }
        return result;
    }

    private static int getMCLength(byte[] data, int pos) {
        int len = 0;
        int idx = pos;
        while (idx < data.length) {
            int b = data[idx++] & 0xFF;
            len++;
            if ((b & 0x80) == 0) break;
        }
        return len > 0 ? len : 1;
    }

    private static String findAsciiText(byte[] data, int start, int maxLen) {
        StringBuilder sb = new StringBuilder();
        int startPos = Math.max(0, start);
        int endPos = Math.min(data.length, startPos + maxLen);
        
        // Look for a run of printable ASCII characters
        for (int i = startPos; i < endPos; i++) {
            int b = data[i] & 0xFF;
            if (b >= 32 && b < 127) {
                sb.append((char)b);
                if (sb.length() > 32) break;
            } else if (sb.length() >= 2) {
                break;
            } else {
                sb.setLength(0);
            }
        }
        
        // If no text found, search for known patterns
        if (sb.length() < 2) {
            // Search for "*Model_Space", "*Paper_Space" or "SW_*" patterns
            for (int i = startPos; i < Math.min(startPos + 200, data.length - 12); i++) {
                if ((data[i] & 0xFF) == '*') {
                    String s = bytesToAscii(data, i, 16);
                    if (s.startsWith("*Model") || s.startsWith("*Paper")) return s;
                }
                if ((data[i] & 0xFF) == 'S' && (data[i+1] & 0xFF) == 'W' && (data[i+2] & 0xFF) == '_') {
                    return bytesToAscii(data, i, 24);
                }
            }
        }
        
        return sb.toString().trim();
    }

    private static String bytesToAscii(byte[] data, int start, int maxLen) {
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < Math.min(start + maxLen, data.length); i++) {
            int b = data[i] & 0xFF;
            if (b >= 32 && b < 127) sb.append((char)b);
            else if (sb.length() > 2) break;
        }
        return sb.toString();
    }
}
