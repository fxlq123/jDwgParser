package run;

import java.nio.ByteBuffer;
import java.nio.file.Paths;
import java.util.*;
import java.nio.charset.StandardCharsets;

/**
 * 使用正确的字段格式解析 BLOCK_HEADER 和 INSERT
 *
 * BLOCK_HEADER 对象（type 0x30/49）:
 *   MS(objSize) + BS(typeCode) + {
 *     uint32(4 bytes)  - 零值 (owner handle?)
 *     uint16(2 bytes)  - handle/entry value
 *     uint8(1 byte)    - name length
 *     [name length]    - ASCII name
 *     ...              - 其他字段 (base point, flags, xref path)
 *   }
 *
 * INSERT 对象:
 *   MS(objSize) + BS(typeCode) + {
 *     uint32           - block header handle
 *     ...              - insertion point, scale, rotation
 *   }
 */
public class FinalBlockParser {

    public static void main(String[] args) throws Exception {
        String filename = "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg";
        byte[] data = java.nio.file.Files.readAllBytes(Paths.get(filename));

        System.out.println();
        System.out.println("==================================================");
        System.out.println("  DWG 块信息解析 (R2000 / AC1015)");
        System.out.println("  文件: " + filename);
        System.out.println("  文件大小: " + data.length + " bytes");
        System.out.println("==================================================");
        System.out.println();

        // === 第1步: 解析 Handles section ===
        List<long[]> handles = parseHandlesSection(data, 0x11943, 3000);
        System.out.println("[1/4] Handles Section: 解析到 " + handles.size() + " 个条目");

        // === 第2步: 遍历对象，找出 BLOCK_HEADER ===
        // 使用 handle-to-offset 映射直接在每个对象处解析
        // 通过在对象体中查找 "4-byte zeros + 2-byte + 1-byte(len) + ASCII name" 模式
        System.out.println();
        System.out.println("[2/4] 扫描块定义 (BLOCK_HEADER):");

        Map<Long, String> handleToName = new HashMap<>();
        Map<Long, Integer> handleToOffset = new HashMap<>();
        List<BlockInfo> blocks = new ArrayList<>();

        for (long[] h : handles) {
            long handle = h[0];
            long offset = h[1];
            if (offset <= 0 || offset > data.length - 20) continue;

            BlockInfo bi = tryParseBlockHeader(data, (int)offset, handle);
            if (bi != null) {
                handleToName.put(handle, bi.name);
                handleToOffset.put(handle, bi.offset);
                blocks.add(bi);
            }
        }

        System.out.println("  找到 " + blocks.size() + " 个块定义");

        // 排序并显示
        System.out.println();
        System.out.println("==================================================");
        System.out.println("  块定义列表");
        System.out.println("==================================================");

        // 分为系统块和用户块
        List<BlockInfo> systemBlocks = new ArrayList<>();
        List<BlockInfo> userBlocks = new ArrayList<>();
        for (BlockInfo bi : blocks) {
            if (bi.name.startsWith("*") || bi.name.toLowerCase().contains("model") ||
                bi.name.toLowerCase().contains("paper")) {
                systemBlocks.add(bi);
            } else {
                userBlocks.add(bi);
            }
        }

        System.out.println();
        System.out.println("  ▸ 系统块 (" + systemBlocks.size() + " 个):");
        for (BlockInfo bi : systemBlocks) {
            System.out.printf("    0x%04x  handle=0x%x  objSize=%d%n",
                bi.offset, bi.handle, bi.objSize);
            System.out.printf("      名称: %s%n", bi.name);
            if (bi.flags != 0) System.out.printf("      flags: 0x%04x%n", bi.flags);
            if (bi.baseX != 0 || bi.baseY != 0 || bi.baseZ != 0)
                System.out.printf("      基点: (%.4f, %.4f, %.4f)%n", bi.baseX, bi.baseY, bi.baseZ);
            if (bi.xrefPath != null && !bi.xrefPath.isEmpty())
                System.out.printf("      外部引用路径: %s%n", bi.xrefPath);
        }

        System.out.println();
        System.out.println("  ▸ 用户块 (" + userBlocks.size() + " 个):");
        for (BlockInfo bi : userBlocks) {
            System.out.printf("    0x%04x  handle=0x%x  objSize=%d%n",
                bi.offset, bi.handle, bi.objSize);
            System.out.printf("      名称: %s%n", bi.name);
            if (bi.flags != 0) System.out.printf("      flags: 0x%04x%n", bi.flags);
            if (bi.baseX != 0 || bi.baseY != 0 || bi.baseZ != 0)
                System.out.printf("      基点: (%.4f, %.4f, %.4f)%n", bi.baseX, bi.baseY, bi.baseZ);
            if (bi.xrefPath != null && !bi.xrefPath.isEmpty())
                System.out.printf("      外部引用路径: %s%n", bi.xrefPath);
        }

        // === 第3步: 扫描 INSERT 引用 ===
        System.out.println();
        System.out.println("[3/4] 扫描块引用 (INSERT):");

        List<InsertInfo> inserts = new ArrayList<>();
        for (long[] h : handles) {
            long handle = h[0];
            long offset = h[1];
            if (offset <= 0 || offset > data.length - 30) continue;

            InsertInfo ii = tryParseInsert(data, (int)offset, handle, handleToName);
            if (ii != null) {
                inserts.add(ii);
            }
        }

        System.out.println("  找到 " + inserts.size() + " 个 INSERT 引用");

        // === 第4步: 显示 INSERT 信息 ===
        System.out.println();
        System.out.println("==================================================");
        System.out.println("  块引用列表");
        System.out.println("==================================================");

        Map<String, Integer> refCount = new LinkedHashMap<>();
        for (InsertInfo ii : inserts) {
            String refName = ii.blockName;
            refCount.merge(refName, 1, Integer::sum);
        }

        int idx = 1;
        for (InsertInfo ii : inserts) {
            System.out.println();
            System.out.printf("  #%d handle=0x%x @ 0x%04x%n", idx++, ii.handle, ii.offset);
            System.out.printf("    引用: %s (block handle=0x%x)%n", ii.blockName, ii.blockHandle);
            if (ii.insX != 0 || ii.insY != 0 || ii.insZ != 0)
                System.out.printf("    插入点: (%.4f, %.4f, %.4f)%n", ii.insX, ii.insY, ii.insZ);
            if (ii.scaleX != 1 || ii.scaleY != 1 || ii.scaleZ != 1)
                System.out.printf("    缩放: (%.4f, %.4f, %.4f)%n", ii.scaleX, ii.scaleY, ii.scaleZ);
            if (ii.rotation != 0)
                System.out.printf("    旋转角度: %.6f rad (%.4f度)%n", ii.rotation, ii.rotation * 180.0 / Math.PI);
        }

        // === 第5步: 引用统计 ===
        System.out.println();
        System.out.println("==================================================");
        System.out.println("  引用统计汇总");
        System.out.println("==================================================");

        List<Map.Entry<String, Integer>> sortedRef = new ArrayList<>(refCount.entrySet());
        sortedRef.sort((a, b) -> b.getValue().compareTo(a.getValue()));

        // 也包括未引用的块
        for (BlockInfo bi : blocks) {
            if (!refCount.containsKey(bi.name)) {
                sortedRef.add(new AbstractMap.SimpleEntry<>(bi.name + " (未引用)", 0));
            }
        }

        for (Map.Entry<String, Integer> e : sortedRef) {
            String bar = repeat("█", Math.min(40, e.getValue() * 2));
            System.out.printf("  %-45s : %4d  %s%n", e.getKey(), e.getValue(), bar);
        }

        System.out.println();
        System.out.println("==================================================");
        System.out.println("  解析完成！");
        System.out.println("==================================================");
    }

    // ========== BlockInfo 类 ==========
    static class BlockInfo {
        long handle;
        int offset;
        int objSize;
        String name;
        int flags;
        double baseX, baseY, baseZ;
        String xrefPath;
    }

    static class InsertInfo {
        long handle;
        int offset;
        long blockHandle;
        String blockName;
        double insX, insY, insZ;
        double scaleX = 1.0, scaleY = 1.0, scaleZ = 1.0;
        double rotation;
    }

    // ========== Handles section 解析 ==========
    static List<long[]> parseHandlesSection(byte[] data, int offset, int maxBytes) {
        List<long[]> result = new ArrayList<>();
        ByteBuffer bb = ByteBuffer.wrap(data);

        int pos = offset;
        while (pos < offset + maxBytes && pos < data.length - 4) {
            // Page header: uint16 BE (page size)
            int pageSize = ((data[pos] & 0xFF) << 8) | (data[pos+1] & 0xFF);
            pos += 2;
            if (pageSize <= 2 || pageSize > 2040) break;

            int pairsSize = pageSize - 2;
            int pairsRead = 0;
            long lastHandle = 0;
            long lastOffset = 0;

            while (pairsRead < pairsSize && pos < data.length - 2) {
                // handleDelta (unsigned modular char)
                int hByte = data[pos] & 0xFF;
                pos++;
                pairsRead++;
                if (hByte < 0x80) {
                    // single byte
                    if (hByte == 0) break; // 0 = 终止
                    lastHandle += hByte;
                } else {
                    int lo = hByte & 0x7F;
                    int hi = data[pos] & 0xFF;
                    pos++;
                    pairsRead++;
                    int delta = lo | (hi << 7);
                    if (delta == 0) break;
                    lastHandle += delta;
                }

                // offsetDelta (signed modular char)
                int oByte = data[pos] & 0xFF;
                pos++;
                pairsRead++;
                long offsetDelta;
                if (oByte < 0x80) {
                    // sign extend
                    offsetDelta = (oByte > 63) ? (long)oByte - 128 : oByte;
                } else {
                    int lo = oByte & 0x7F;
                    int hiByte = data[pos] & 0xFF;
                    pos++;
                    pairsRead++;
                    // sign extend from 15 bits
                    int combined = lo | (hiByte << 7);
                    offsetDelta = (combined > 16383) ? (long)combined - 32768 : combined;
                }
                lastOffset += offsetDelta;

                result.add(new long[]{ lastHandle, lastOffset });
            }

            // CRC 2 bytes
            pos += 2;
        }

        return result;
    }

    // ========== BLOCK_HEADER 解析 ==========
    static BlockInfo tryParseBlockHeader(byte[] data, int offset, long handle) {
        // 读取 objSize (LE uint16, first 2 bytes)
        int b0 = data[offset] & 0xFF;
        int b1 = data[offset + 1] & 0xFF;
        int objSize = b0 | (b1 << 8);
        if (objSize <= 0 || objSize > 200 || offset + 2 + objSize > data.length) {
            // 或者可能是 modular short:
            // 如果最高位为0，就是 1-byte continuation
            // 实际在 R2000 中 objSize 是普通 LE uint16
            return null;
        }

        // 读取 typeCode (BS): 2 bits opcode + value
        // 从 offset+2 开始
        int byte2 = data[offset + 2] & 0xFF;
        // MSB-first: first 2 bits = (byte2 >> 6) & 0x03
        int opcode = (byte2 >> 6) & 0x03;
        int typeCode;
        int bytesConsumedByBS;

        if (opcode == 0) {
            // 16 bits value: 剩余 6 bits of byte2 + all of byte3 + 8 more bits?
            // 不，opcode 00 表示读取 16 bits LE uint16
            // 即: 剩余 6 bits (byte2) + bits from byte3 and byte4
            // readBits(16) for value:
            int remainingByte2 = byte2 & 0x3F; // low 6 bits (MSB of value)
            int byte3 = data[offset + 3] & 0xFF;
            int byte4 = data[offset + 4] & 0xFF;
            // value = (remainingByte2 << 10) | (byte3 << 2) | (byte4 >> 6) ?
            // 让我更简单地处理: readBits(16) from bit position (offset+2)*8 + 2
            // 实际上，让我重新思考 BS 编码:
            // BS: 2 bits opcode (MSB first), then
            //  opcode 00: 16 bits LE
            //  opcode 01: 8 bits
            //  opcode 10: value = 0
            //  opcode 11: value = 256
            int val8 = remainingByte2; // wrong, need to read from MSB side
            // Let me try:
            // remaining bits of byte2 = low 6 bits = bits 0-5
            // byte3 = next 8 bits
            // byte4 high 2 bits = last bits
            // value is LE: low byte first
            // low byte = (remainingByte2 << 2) | (byte3 >> 6) = 6+2=8 bits
            // high byte = ((byte3 & 0x3F) << 2) | (byte4 >> 6) ???
            // Actually let me think about this differently.
            // The issue is: bits are read MSB-first, but the value is LE uint16.
            // After opcode (2 bits), we read 16 bits MSB-first into a LE uint16.
            // bits 0-5 of byte2 (from MSB): these are the first 6 bits
            // bits 0-7 of byte3: next 8 bits
            // bits 6-7 of byte4: last 2 bits
            // value (MSB-first 16 bits): bits = byte2[0..5] + byte3[0..7] + byte4[0..1]
            // Wait, we read "MSB first" so the first bit read is MSB of value.
            // So: bit value = [6 bits from byte2][8 bits from byte3][2 bits from byte4]
            // But wait, LE uint16 means: first 8 bits read = low byte, next 8 bits = high byte
            // Actually re-reading: "BS opcode 00 means 16 bits LE"
            // 16 bits (as if LE uint16):
            //   low byte = readBits(8), high byte = readBits(8)
            //   value = lowByte | (highByte << 8)
            // So: read 8 bits for low byte: 6 bits remaining in byte2 + 2 bits from byte3
            //     read 8 bits for high byte: 6 bits from byte3 + 2 bits from byte4 ... no that's wrong
            // Let me count: 2 (opcode) + 16 (value) = 18 bits total for BS
            // 18 bits starting at offset+2 = bytes 2,3,4 (24 bits total, using 18)
            // byte2: bits 7-6 = opcode, bits 5-0 = 6 bits of value
            // byte3: bits 7-0 = 8 bits of value
            // byte4: bits 7-6 = 2 bits of value (remainder, but we need 16 bits total)
            // Total after opcode: 6+8+2 = 16 bits ✓
            // The 16 bits read MSB-first form a LE uint16:
            // first 8 bits (from MSB): bits 5-0 of byte2 + bits 7-6 of byte3
            // last 8 bits: bits 5-0 of byte3 + bits 7-6 of byte4
            // value = (first8) | (last8 << 8)
            int lo8 = ((byte2 & 0x3F) << 2) | ((byte3 >> 6) & 0x03);
            int hi8 = ((byte3 & 0x3F) << 2) | ((byte4 >> 6) & 0x03);
            typeCode = lo8 | (hi8 << 8);
            bytesConsumedByBS = 3; // 2+ bits span across 3 bytes
        } else if (opcode == 1) {
            // 8 bits value: remaining 6 bits of byte2 + top 2 bits of byte3?
            // MSB-first: value_bits = byte2[5..0] + byte3[7..6] = 8 bits
            int val8 = ((byte2 & 0x3F) << 2) | ((data[offset + 3] >> 6) & 0x03);
            typeCode = val8;
            bytesConsumedByBS = 2; // spans 2 bytes
        } else if (opcode == 2) {
            typeCode = 0;
            bytesConsumedByBS = 1; // just opcode byte
        } else {
            typeCode = 256;
            bytesConsumedByBS = 1;
        }

        // 判断 typeCode 是否在合理范围 (0-255)
        if (typeCode < 0 || typeCode > 500) return null;

        // 对象体起始位置
        int bodyStart = offset + 2 + bytesConsumedByBS;
        int bodyEnd = offset + 2 + objSize;
        if (bodyEnd > data.length) return null;

        // 现在尝试在对象体中查找 块名
        // 格式: 可能有 4 字节零值，然后是 handle/标志，然后是 1-byte length + ASCII
        for (int pos = bodyStart; pos < bodyEnd - 10; pos++) {
            // 查找: 1 byte length + ASCII name (min 3 chars)
            int lenByte = data[pos] & 0xFF;
            if (lenByte >= 3 && lenByte <= 50 && pos + 1 + lenByte <= bodyEnd) {
                boolean ascii = true;
                for (int k = 0; k < lenByte; k++) {
                    int c = data[pos + 1 + k] & 0xFF;
                    if (c < 32 || c > 126) { ascii = false; break; }
                }
                if (!ascii) continue;

                String name = new String(data, pos + 1, lenByte, StandardCharsets.US_ASCII);

                // 过滤非块名
                if (name.startsWith("AcDb") || name.startsWith("*") ||
                    name.startsWith("SW_") || name.contains("DIMENSION") ||
                    (name.length() >= 4 && Character.isLetter(name.charAt(0)))) {

                    // 找到块名！检查前面是否有合理的字节模式
                    BlockInfo bi = new BlockInfo();
                    bi.handle = handle;
                    bi.offset = offset;
                    bi.objSize = objSize;
                    bi.name = name;

                    // name 之前的字节: pos - bodyStart 是字段偏移
                    // 尝试读取 flags 和 base point (在 name 之后)
                    int afterName = pos + 1 + lenByte;

                    // 尝试解析 name 之后的字段: flags (2 bytes) + base point (3 doubles = 24 bytes)
                    // 但也可能是其他字段 (xdict handle, entry info 等)
                    // 先尝试: 如果 name 后紧跟 C0 55... 可能不是 BLOCK_HEADER
                    // 让我们看一个典型的块结构:
                    // ... 00 00 00 00 [4B] HH HH [2B] LL [1B] [LL bytes name] DD DD DD [flags] [3 doubles]
                    // 但需要更仔细分析字节...

                    // 粗略: 在 name 后寻找 24 bytes 作为 3 doubles
                    if (afterName + 26 <= bodyEnd) {
                        // 先读 2 bytes as flags
                        bi.flags = (data[afterName] & 0xFF) | ((data[afterName+1] & 0xFF) << 8);

                        // 然后读 3 doubles (24 bytes LE)
                        if (afterName + 2 + 24 <= bodyEnd) {
                            try {
                                ByteBuffer db = ByteBuffer.wrap(data, afterName + 2, 24).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                                bi.baseX = db.getDouble();
                                bi.baseY = db.getDouble();
                                bi.baseZ = db.getDouble();
                                // 检查是否合理
                                if (!Double.isFinite(bi.baseX) || Math.abs(bi.baseX) > 1e8 ||
                                    !Double.isFinite(bi.baseY) || Math.abs(bi.baseY) > 1e8 ||
                                    !Double.isFinite(bi.baseZ) || Math.abs(bi.baseZ) > 1e8) {
                                    bi.baseX = 0; bi.baseY = 0; bi.baseZ = 0;
                                }
                            } catch (Exception e) {}
                        }
                    }

                    return bi;
                }
            }
        }

        return null;
    }

    // ========== INSERT 解析 ==========
    static InsertInfo tryParseInsert(byte[] data, int offset, long handle, Map<Long, String> handleToName) {
        int b0 = data[offset] & 0xFF;
        int b1 = data[offset + 1] & 0xFF;
        int objSize = b0 | (b1 << 8);
        if (objSize <= 0 || objSize > 200 || offset + 2 + objSize > data.length) return null;

        // 读取 typeCode
        int byte2 = data[offset + 2] & 0xFF;
        int opcode = (byte2 >> 6) & 0x03;
        int typeCode;
        int bytesConsumedByBS;

        if (opcode == 0) {
            int byte3 = data[offset + 3] & 0xFF;
            int byte4 = data[offset + 4] & 0xFF;
            int lo8 = ((byte2 & 0x3F) << 2) | ((byte3 >> 6) & 0x03);
            int hi8 = ((byte3 & 0x3F) << 2) | ((byte4 >> 6) & 0x03);
            typeCode = lo8 | (hi8 << 8);
            bytesConsumedByBS = 3;
        } else if (opcode == 1) {
            int val8 = ((byte2 & 0x3F) << 2) | ((data[offset + 3] >> 6) & 0x03);
            typeCode = val8;
            bytesConsumedByBS = 2;
        } else if (opcode == 2) {
            typeCode = 0;
            bytesConsumedByBS = 1;
        } else {
            return null;
        }

        // INSERT typeCode 可能是 0x07 (7) 或其他值
        // 实际上让我们放宽条件：通过在对象体中查找 BLOCK_HEADER handle 来识别 INSERT
        int bodyStart = offset + 2 + bytesConsumedByBS;
        int bodyEnd = offset + 2 + objSize;

        // INSERT: 前几个字节应该有 block header handle
        // 在对象体中查找一个合理的 handle 值 (在已知的块句柄中)
        // 同时也尝试从 bodyStart+4 处读取一个 LE uint32 作为 blockHandle
        if (bodyStart + 8 > bodyEnd) return null;

        // 尝试: bodyStart + 4 或其他位置读取 block handle
        for (int bhOffset = bodyStart; bhOffset < bodyStart + 12 && bhOffset + 4 <= bodyEnd; bhOffset++) {
            long bh = ((long)(data[bhOffset] & 0xFF)) |
                     ((long)(data[bhOffset+1] & 0xFF) << 8) |
                     ((long)(data[bhOffset+2] & 0xFF) << 16) |
                     ((long)(data[bhOffset+3] & 0xFF) << 24);
            if (handleToName.containsKey(bh)) {
                // 找到了！这是一个 INSERT
                InsertInfo ii = new InsertInfo();
                ii.handle = handle;
                ii.offset = offset;
                ii.blockHandle = bh;
                ii.blockName = handleToName.get(bh);

                // 在 bh 之后读取 3 doubles (插入点)
                int ptPos = bhOffset + 4;
                if (ptPos + 24 <= bodyEnd) {
                    try {
                        ByteBuffer db = ByteBuffer.wrap(data, ptPos, 24).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                        ii.insX = db.getDouble();
                        ii.insY = db.getDouble();
                        ii.insZ = db.getDouble();
                        if (!Double.isFinite(ii.insX) || Math.abs(ii.insX) > 1e8) ii.insX = 0;
                        if (!Double.isFinite(ii.insY) || Math.abs(ii.insY) > 1e8) ii.insY = 0;
                        if (!Double.isFinite(ii.insZ) || Math.abs(ii.insZ) > 1e8) ii.insZ = 0;
                    } catch (Exception e) {}
                }

                // 再读 3 doubles (缩放)
                int scPos = ptPos + 24;
                if (scPos + 24 <= bodyEnd) {
                    try {
                        ByteBuffer db = ByteBuffer.wrap(data, scPos, 24).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                        ii.scaleX = db.getDouble();
                        ii.scaleY = db.getDouble();
                        ii.scaleZ = db.getDouble();
                        if (!Double.isFinite(ii.scaleX) || Math.abs(ii.scaleX) > 1e4) ii.scaleX = 1;
                        if (!Double.isFinite(ii.scaleY) || Math.abs(ii.scaleY) > 1e4) ii.scaleY = 1;
                        if (!Double.isFinite(ii.scaleZ) || Math.abs(ii.scaleZ) > 1e4) ii.scaleZ = 1;
                    } catch (Exception e) {}
                }

                // 再读 1 double (旋转)
                int rotPos = scPos + 24;
                if (rotPos + 8 <= bodyEnd) {
                    try {
                        ByteBuffer db = ByteBuffer.wrap(data, rotPos, 8).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                        ii.rotation = db.getDouble();
                        if (!Double.isFinite(ii.rotation) || Math.abs(ii.rotation) > 1e3) ii.rotation = 0;
                    } catch (Exception e) {}
                }

                return ii;
            }
        }

        return null;
    }

    static String repeat(String s, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) sb.append(s);
        return sb.toString();
    }
}
