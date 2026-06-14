package run;

import io.dwg.core.io.ByteBufferBitInput;
import io.dwg.core.io.BitStreamReader;
import io.dwg.core.version.DwgVersion;

import java.nio.ByteBuffer;
import java.nio.file.Paths;
import java.util.*;

/**
 * 直接从原始字节解析 R2000 BLOCK_HEADER 和 INSERT
 * 策略：
 * 1. 解析 Handles section 得到 handle -> offset 映射
 * 2. 对每个 offset 定位对象，检查是否 type=0x05 (BLOCK_HEADER) 或 type=0x07 (INSERT)
 * 3. 在 BLOCK_HEADER 对象数据中查找 ASCII 字符串作为块名
 * 4. 在 INSERT 对象数据中查找块句柄引用
 */
public class R2000BlockExtractor {

    public static void main(String[] args) throws Exception {
        String filename = "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg";
        byte[] data = java.nio.file.Files.readAllBytes(Paths.get(filename));

        // 1. 解析 Handles section
        List<HandleEntry> handles = parseHandlesSection(data, 0x11943, 2000);
        System.out.println("找到 " + handles.size() + " 个句柄条目");

        // 2. 构建句柄映射和块信息
        Map<Long, BlockInfo> blockMap = new HashMap<>();
        Map<Long, InsertInfo> insertMap = new HashMap<>();

        for (HandleEntry he : handles) {
            if (he.offset < 0 || he.offset + 4 > data.length) continue;

            try {
                ByteBufferBitInput bbuf = new ByteBufferBitInput(ByteBuffer.wrap(data));
                bbuf.seek((long) he.offset * 8);
                BitStreamReader r = new BitStreamReader(bbuf, DwgVersion.R2000);

                int objSize = r.readModularShort();
                if (objSize <= 0 || objSize > 0x4000) continue;

                int typeCode = r.readBitShort();

                if (typeCode == 0x05) {
                    // BLOCK_HEADER - 提取块名和其他信息
                    BlockInfo bi = extractBlockInfo(data, (int) he.offset, objSize, he.handle);
                    if (bi != null) blockMap.put(he.handle, bi);
                } else if (typeCode == 0x07) {
                    // INSERT - 提取块句柄引用
                    InsertInfo ii = extractInsertInfo(data, he.offset, objSize, he.handle);
                    if (ii != null) insertMap.put(he.handle, ii);
                }
            } catch (Exception e) {
                // skip
            }
        }

        // 3. 输出结果
        System.out.println();
        System.out.println("==========================================================");
        System.out.println("  BLOCK 定义 (共 " + blockMap.size() + " 个)");
        System.out.println("==========================================================");
        for (BlockInfo bi : blockMap.values()) {
            System.out.println();
            System.out.println("  句柄: 0x" + Long.toHexString(bi.handle));
            System.out.println("  块名: " + bi.name);
            System.out.println("  基点: (" + bi.baseX + ", " + bi.baseY + ", " + bi.baseZ + ")");
            System.out.println("  标志: 0x" + Integer.toHexString(bi.flags));
        }

        System.out.println();
        System.out.println("==========================================================");
        System.out.println("  INSERT 引用 (共 " + insertMap.size() + " 个)");
        System.out.println("==========================================================");
        for (InsertInfo ii : insertMap.values()) {
            String refBlockName = "未知(handle=0x" + Long.toHexString(ii.blockHandle) + ")";
            for (BlockInfo bi : blockMap.values()) {
                if (bi.handle == ii.blockHandle) {
                    refBlockName = bi.name;
                    break;
                }
            }
            System.out.println();
            System.out.println("  INSERT 句柄: 0x" + Long.toHexString(ii.handle));
            System.out.println("  引用块: " + refBlockName);
            System.out.println("  插入点: (" + ii.x + ", " + ii.y + ", " + ii.z + ")");
            System.out.println("  缩放: (" + ii.sx + ", " + ii.sy + ", " + ii.sz + ")");
            System.out.println("  旋转: " + ii.rotation + " rad");
        }

        // 4. 按块名统计 INSERT 引用
        System.out.println();
        System.out.println("==========================================================");
        System.out.println("  块引用统计");
        System.out.println("==========================================================");
        Map<String, Integer> refCount = new LinkedHashMap<>();
        for (InsertInfo ii : insertMap.values()) {
            String name = "未知(handle=0x" + Long.toHexString(ii.blockHandle) + ")";
            for (BlockInfo bi : blockMap.values()) {
                if (bi.handle == ii.blockHandle) {
                    name = bi.name;
                    break;
                }
            }
            refCount.merge(name, 1, Integer::sum);
        }
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(refCount.entrySet());
        sorted.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        for (Map.Entry<String, Integer> e : sorted) {
            System.out.printf("  %-40s : %d 次%n", e.getKey(), e.getValue());
        }

        // 5. 系统块 vs 用户块
        System.out.println();
        System.out.println("==========================================================");
        System.out.println("  系统块 vs 用户块");
        System.out.println("==========================================================");
        System.out.println("  系统块 (* 开头):");
        for (BlockInfo bi : blockMap.values()) {
            if (bi.name.startsWith("*") || bi.name.startsWith("*Model") || bi.name.startsWith("*Paper")) {
                System.out.println("    • " + bi.name);
            }
        }
        System.out.println("  用户块:");
        for (BlockInfo bi : blockMap.values()) {
            if (!bi.name.startsWith("*") && !bi.name.startsWith("*Model") && !bi.name.startsWith("*Paper")) {
                System.out.println("    • " + bi.name);
            }
        }
    }

    // =========================================================
    // Handles section 解析
    // =========================================================
    private static List<HandleEntry> parseHandlesSection(byte[] data, int offset, int size) {
        List<HandleEntry> result = new ArrayList<>();
        ByteBufferBitInput bbuf = new ByteBufferBitInput(ByteBuffer.wrap(data));
        bbuf.seek((long) offset * 8);
        BitStreamReader r = new BitStreamReader(bbuf, DwgVersion.R2000);

        int totalRead = 0;
        while (totalRead < size - 4) {
            try {
                int pageSize = r.readBigEndianShort();
                totalRead += 2;
                if (pageSize <= 2 || pageSize > 2040) break;

                long lastOffset = 0;
                long lastHandle = 0;
                int pairsSize = pageSize - 2;
                int pairsRead = 0;

                while (pairsRead < pairsSize) {
                    long before = r.position();
                    int hDelta = r.readUnsignedModularChar();
                    if (hDelta == 0) break;
                    int oDelta = r.readModularChar();
                    long after = r.position();
                    pairsRead += (int) ((after - before) / 8);
                    totalRead += (int) ((after - before) / 8);

                    lastHandle += hDelta;
                    lastOffset += oDelta;
                    result.add(new HandleEntry(lastHandle, lastOffset));
                }

                // 对齐到字节
                long currentPos = r.position();
                if ((currentPos % 8) != 0) {
                    int padding = 8 - (int) (currentPos % 8);
                    r.getInput().readBits(padding);
                }
                r.readBigEndianShort(); // CRC
                totalRead += 2;
            } catch (Exception e) {
                break;
            }
        }
        return result;
    }

    // =========================================================
    // 从 BLOCK_HEADER 对象提取信息
    // =========================================================
    private static BlockInfo extractBlockInfo(byte[] data, int offset, int objSize, long handle) {
        try {
            // 提取这个对象的数据字节（从 MS+BS 之后开始）
            // 先从字节层面找到可打印字符串
            int startByte = offset + 4; // 跳过 MS(2) + BS区域(2) - 粗略估计
            int endByte = offset + 2 + objSize; // objSize 从 type_code 后开始计算？

            if (startByte >= data.length || endByte > data.length) return null;

            // 方法：直接在对象数据中查找 ASCII 字符串
            String foundName = findPrintableString(data, startByte, endByte - startByte);

            // 也尝试从 known patterns 提取
            String altName = extractBlockName(data, offset, objSize);

            if (altName != null && !altName.isEmpty()) foundName = altName;

            BlockInfo bi = new BlockInfo();
            bi.handle = handle;
            bi.name = foundName != null ? foundName : "(未找到块名)";

            // 尝试找到基点（从块名之后的位置）
            // 查找 block name 后的几个字节，然后尝试读 double
            try {
                int afterName = findNameEndOffset(data, startByte, endByte);
                if (afterName > 0) {
                    // 从 afterName 后读取 flags(BS) + 3 doubles
                    ByteBufferBitInput bbuf = new ByteBufferBitInput(ByteBuffer.wrap(data));
                    bbuf.seek((long) afterName * 8);
                    BitStreamReader r = new BitStreamReader(bbuf, DwgVersion.R2000);
                    try {
                        int flags = r.readBitShort();
                        bi.flags = flags;
                        double x = r.readBitDouble();
                        double y = r.readBitDouble();
                        double z = r.readBitDouble();
                        bi.baseX = x;
                        bi.baseY = y;
                        bi.baseZ = z;
                    } catch (Exception e) {
                        // try another position
                    }
                }
            } catch (Exception e) {
                // ignore
            }

            return bi;
        } catch (Exception e) {
            return null;
        }
    }

    // 在字节范围内查找可打印字符串 (4 字符以上)
    private static String findPrintableString(byte[] data, int start, int length) {
        int maxLen = Math.min(length, 200);
        StringBuilder best = new StringBuilder();
        StringBuilder current = new StringBuilder();

        for (int i = 0; i < maxLen; i++) {
            int b = data[start + i] & 0xFF;
            if (b >= 32 && b <= 126) {
                current.append((char) b);
                if (current.length() > best.length() && current.length() >= 3) {
                    best = new StringBuilder(current);
                }
            } else {
                if (current.length() > best.length()) {
                    best = new StringBuilder(current);
                }
                current.setLength(0);
            }
        }
        return best.length() >= 3 ? best.toString() : null;
    }

    // 尝试更精确地从对象中提取块名 - 使用 bit-level 读取
    private static String extractBlockName(byte[] data, int offset, int objSize) {
        try {
            // 从头解析: MS + BS + 实体特定部分
            ByteBufferBitInput bbuf = new ByteBufferBitInput(ByteBuffer.wrap(data));
            bbuf.seek((long) offset * 8);
            BitStreamReader r = new BitStreamReader(bbuf, DwgVersion.R2000);

            r.readModularShort(); // 跳过 objSize
            r.readBitShort(); // 跳过 typeCode

            // 尝试多种字段组合来找到 block name
            // 方法1: 先尝试直接读取文本（假设前面是 entity common data）

            // 假设: numReactors(BL) + ownerHandle(H) + [optional reactors] + blockName(T)
            // 尝试 numReactors = 0 的情况（即 BL opcode=10，只有2位）
            // 实际上我们需要测试多种可能

            // 简化：尝试多个位置读取 T
            for (int startBit : new int[]{0, 2, 8, 10, 16, 18, 26, 32, 40}) {
                try {
                    ByteBufferBitInput tb = new ByteBufferBitInput(ByteBuffer.wrap(data));
                    tb.seek((long) offset * 8 + 26 + startBit); // 26 = MS(16) + BS(10)
                    BitStreamReader tr = new BitStreamReader(tb, DwgVersion.R2000);
                    String name = tr.readText();
                    if (name != null && name.length() >= 3 && isPrintable(name)) {
                        // 检查：从块名后读取 flags 和 base point
                        try {
                            int flags = tr.readBitShort();
                            double x = tr.readBitDouble();
                            double y = tr.readBitDouble();
                            double z = tr.readBitDouble();
                            // 检查合理性：flags 应该 < 256，坐标应该是合理的数字
                            if (flags >= 0 && flags < 0xFF &&
                                Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)) {
                                return name + " [flags=0x" + Integer.toHexString(flags) +
                                    " base=(" + x + "," + y + "," + z + ")]";
                            }
                            // 返回至少 name
                            return name;
                        } catch (Exception ex) {
                            return name;
                        }
                    }
                } catch (Exception ex) {
                    // 尝试下一个位置
                }
            }

            // 方法2: 直接在对象字节范围内查找字符串
            int startByte = offset + 2; // MS之后
            int endByte = Math.min(offset + 2 + objSize, data.length);
            String s = findPrintableString(data, startByte, endByte - startByte);
            return s;
        } catch (Exception e) {
            return null;
        }
    }

    private static int findNameEndOffset(byte[] data, int start, int end) {
        // 找到第一个字符串结束后的位置
        int i = start;
        while (i < end && i < start + 50) {
            int b = data[i] & 0xFF;
            if (b < 32 || b > 126) break;
            i++;
        }
        return i > start ? i : -1;
    }

    private static boolean isPrintable(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 32 || c > 126) return false;
        }
        return true;
    }

    // =========================================================
    // 从 INSERT 对象提取信息
    // =========================================================
    private static InsertInfo extractInsertInfo(byte[] data, int offset, int objSize, long handle) {
        try {
            ByteBufferBitInput bbuf = new ByteBufferBitInput(ByteBuffer.wrap(data));
            bbuf.seek((long) offset * 8);
            BitStreamReader r = new BitStreamReader(bbuf, DwgVersion.R2000);

            r.readModularShort(); // objSize
            r.readBitShort(); // typeCode

            // 尝试多种可能的字段顺序
            // INSERT 结构：numReactors(BL) + entityMode(2bits) + ltypeFlags(2bits) +
            //              ownerHandle(H) + reactorHandles(H*N) + blockHeaderHandle(H) +
            //              insertionPoint(3BD) + scale(3BD) + rotation(BD)

            // 测试不同的可能性：
            for (int test = 0; test < 8; test++) {
                try {
                    InsertInfo ii = tryParseInsert(data, offset, test);
                    if (ii != null) {
                        ii.handle = handle;
                        return ii;
                    }
                } catch (Exception e) {
                    // continue
                }
            }

            // fallback: 至少提取块句柄引用
            InsertInfo ii = new InsertInfo();
            ii.handle = handle;
            // 在对象数据中查找一个看起来像 handle 的值
            // 简化：跳过 common data，然后尝试读取 H + 3BD + 3BD + BD
            return ii;
        } catch (Exception e) {
            return null;
        }
    }

    private static InsertInfo tryParseInsert(byte[] data, int offset, int testMode) {
        ByteBufferBitInput bbuf = new ByteBufferBitInput(ByteBuffer.wrap(data));
        bbuf.seek((long) offset * 8);
        BitStreamReader r = new BitStreamReader(bbuf, DwgVersion.R2000);

        r.readModularShort();
        r.readBitShort();

        // testMode 决定读取模式
        switch (testMode) {
            case 0: // numReactors=0 (BL opcode=10, 2 bits) + entity flags + ownerHandle
                // 先尝试 BL opcode=10 (即 readBitLong 返回 0, 只有2位)
                {
                    long pos1 = bbuf.position();
                    int numR = r.readBitLong();
                    if (numR == 0 || numR < 8) {
                        // entity mode
                        r.getInput().readBits(2);
                        r.getInput().readBits(2);
                        long ownerH = r.readHandle();
                        // block header handle
                        long blockH = r.readHandle();
                        if (blockH > 0 && blockH < 0x100000) {
                            double x = r.readBitDouble();
                            double y = r.readBitDouble();
                            double z = r.readBitDouble();
                            double sx = r.readBitDouble();
                            double sy = r.readBitDouble();
                            double sz = r.readBitDouble();
                            double rot = r.readBitDouble();
                            InsertInfo ii = new InsertInfo();
                            ii.blockHandle = blockH;
                            ii.x = x;
                            ii.y = y;
                            ii.z = z;
                            ii.sx = sx;
                            ii.sy = sy;
                            ii.sz = sz;
                            ii.rotation = rot;
                            return ii;
                        }
                    }
                }
                break;
            case 1: // 直接跳过几个字节然后读取 H + BD...
                {
                    // 跳过 8 bytes 然后开始读取
                    bbuf.seek((long) offset * 8 + 26 + 32); // MS+BS + 4 bytes
                    long blockH = r.readHandle();
                    if (blockH > 0 && blockH < 0x100000) {
                        double x = r.readBitDouble();
                        double y = r.readBitDouble();
                        double z = r.readBitDouble();
                        if (Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)) {
                            InsertInfo ii = new InsertInfo();
                            ii.blockHandle = blockH;
                            ii.x = x;
                            ii.y = y;
                            ii.z = z;
                            return ii;
                        }
                    }
                }
                break;
            case 2: // 读取 H (忽略 entity common)，然后找块句柄
                {
                    // 尝试：直接找第一个看起来合理的 handle 值
                    // 在 offset+4 到 offset+objSize 的范围内尝试读取 H
                    for (int bitPos = 0; bitPos < 120; bitPos += 2) {
                        try {
                            ByteBufferBitInput tb = new ByteBufferBitInput(ByteBuffer.wrap(data));
                            tb.seek((long) offset * 8 + 26 + bitPos);
                            BitStreamReader tr = new BitStreamReader(tb, DwgVersion.R2000);
                            long h = tr.readHandle();
                            if (h > 0 && h < 0x5000) {
                                // 后面尝试读取 3BD+3BD+BD
                                double x = tr.readBitDouble();
                                double y = tr.readBitDouble();
                                double z = tr.readBitDouble();
                                double sx = tr.readBitDouble();
                                double sy = tr.readBitDouble();
                                double sz = tr.readBitDouble();
                                double rot = tr.readBitDouble();
                                if (Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z) &&
                                    Math.abs(x) < 1e8 && Math.abs(y) < 1e8) {
                                    InsertInfo ii = new InsertInfo();
                                    ii.blockHandle = h;
                                    ii.x = x;
                                    ii.y = y;
                                    ii.z = z;
                                    ii.sx = sx;
                                    ii.sy = sy;
                                    ii.sz = sz;
                                    ii.rotation = rot;
                                    return ii;
                                }
                            }
                        } catch (Exception ex) {
                            // continue
                        }
                    }
                }
                break;
        }
        return null;
    }

    // =========================================================
    // 数据类
    // =========================================================
    private static class HandleEntry {
        long handle;
        long offset;

        HandleEntry(long h, long o) {
            handle = h;
            offset = o;
        }
    }

    private static class BlockInfo {
        long handle;
        String name;
        double baseX, baseY, baseZ;
        int flags;
    }

    private static class InsertInfo {
        long handle;
        long blockHandle;
        double x, y, z;
        double sx, sy, sz;
        double rotation;
    }
}
