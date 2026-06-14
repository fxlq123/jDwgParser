package run;

import java.nio.file.Paths;
import java.util.*;
import java.nio.charset.StandardCharsets;

public class BitwiseBlockParser {

    static class BitInput {
        byte[] data;
        int bitPos;  // absolute bit position

        BitInput(byte[] d, int startByte) {
            data = d;
            bitPos = startByte * 8;
        }

        int readBits(int n) {
            int result = 0;
            for (int i = 0; i < n; i++) {
                int byteIdx = bitPos / 8;
                int bitInByte = 7 - (bitPos % 8);  // MSB first
                int bit = (data[byteIdx] >> bitInByte) & 1;
                result = (result << 1) | bit;
                bitPos++;
            }
            return result;
        }

        int readByte() { return readBits(8); }

        long readRawLong() {  // read 32 bits as LE uint32
            int b0 = readByte();
            int b1 = readByte();
            int b2 = readByte();
            int b3 = readByte();
            return ((long)b0) | ((long)b1 << 8) | ((long)b2 << 16) | ((long)b3 << 24);
        }

        long readHandle() {  // read 32 bits as LE
            return readRawLong();
        }

        int readBitShort() {  // BS: 2-bit opcode + value
            int opcode = readBits(2);
            if (opcode == 0) {  // 16-bit value (LE)
                int lo = readByte();
                int hi = readByte();
                return lo | (hi << 8);
            } else if (opcode == 1) {  // 8-bit value
                return readByte();
            } else if (opcode == 2) {  // value = 0
                return 0;
            } else {  // value = 256
                return 256;
            }
        }

        int readBitLong() {  // BL: 2-bit opcode
            int opcode = readBits(2);
            if (opcode == 0) return (int)readRawLong();
            else if (opcode == 1) return readBitShort();
            else if (opcode == 2) return 0;
            else return 256;
        }

        double readBitDouble() {  // BD: 2-bit opcode
            int opcode = readBits(2);
            if (opcode == 0) {  // 8 raw bytes as LE double
                long bits = 0;
                for (int i = 0; i < 8; i++) {
                    bits |= ((long)readByte()) << (i * 8);
                }
                return Double.longBitsToDouble(bits);
            } else if (opcode == 1) {  // 1 byte
                return readByte();
            } else if (opcode == 2) {  // 0.0
                return 0.0;
            } else {  // 1.0
                return 1.0;
            }
        }

        double readRawDouble() {
            long bits = 0;
            for (int i = 0; i < 8; i++) {
                bits |= ((long)readByte()) << (i * 8);
            }
            return Double.longBitsToDouble(bits);
        }

        int readModularShort() {
            int b = readByte();
            if (b < 128) return b;  // single byte
            int lo = b & 0x7F;
            int hi = readByte();
            return lo | (hi << 7);
        }

        String readText(int len) {
            byte[] b = new byte[len];
            for (int i = 0; i < len; i++) b[i] = (byte)readByte();
            return new String(b, StandardCharsets.US_ASCII);
        }

        int getBytePos() { return bitPos / 8; }
        int getBitInByte() { return bitPos % 8; }
    }

    static class BlockInfo {
        long handle;
        int offset;
        int objSize;
        int typeCode;
        long bitsize;
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
        double scaleX = 1, scaleY = 1, scaleZ = 1;
        double rotation;
    }

    public static void main(String[] args) throws Exception {
        String filename = "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg";
        byte[] data = java.nio.file.Files.readAllBytes(Paths.get(filename));

        System.out.println();
        System.out.println("=== *Paper_Space 对象详细解析 (offset=0x5484) ===");
        debugParseBlock(data, 0x5484, 0xE948);

        System.out.println();
        System.out.println("=== *Model_Space 对象详细解析 (offset=0x5A77) ===");
        debugParseBlock(data, 0x5A77, 0xE947);

        // 解析 handles 并扫描所有块
        System.out.println();
        System.out.println("=== 扫描所有对象 ===");

        List<long[]> handles = parseHandles(data, 0x11943, 5000);
        System.out.println("解析到 " + handles.size() + " 个 handles");

        List<BlockInfo> blocks = new ArrayList<>();
        Map<Long, BlockInfo> blockByHandle = new HashMap<>();
        List<InsertInfo> inserts = new ArrayList<>();

        int tried = 0;
        for (long[] h : handles) {
            long handle = h[0];
            long offset = h[1];
            if (offset <= 0 || offset > data.length - 20) continue;

            tried++;
            BlockInfo bi = tryParseBlock(data, (int)offset, handle);
            if (bi != null) {
                blocks.add(bi);
                blockByHandle.put(handle, bi);
            }
        }

        System.out.println("尝试解析 " + tried + " 个对象，找到 " + blocks.size() + " 个块定义");

        // 打印块定义
        System.out.println();
        System.out.println("==================================================");
        System.out.println("  找到的块定义:");
        System.out.println("==================================================");
        for (BlockInfo bi : blocks) {
            System.out.printf("%n  handle=0x%x (0x%x)  objSize=%d  type=%d%n",
                bi.handle, bi.offset, bi.objSize, bi.typeCode);
            System.out.printf("    名称: %s%n", bi.name);
            if (bi.flags != 0) System.out.printf("    flags: 0x%04x%n", bi.flags);
            System.out.printf("    基点: (%.4f, %.4f, %.4f)%n", bi.baseX, bi.baseY, bi.baseZ);
            if (bi.xrefPath != null && !bi.xrefPath.isEmpty())
                System.out.printf("    xref: %s%n", bi.xrefPath);
        }

        // 扫描 INSERT
        // 构建 name -> blockHandle 映射
        Map<Long, String> handleToName = new HashMap<>();
        for (BlockInfo bi : blocks) handleToName.put(bi.handle, bi.name);

        System.out.println();
        System.out.println("=== 扫描 INSERT 引用 ===");
        int insertCount = 0;
        for (long[] h : handles) {
            long handle = h[0];
            long offset = h[1];
            if (offset <= 0 || offset > data.length - 30) continue;

            // 跳过已知的 block
            if (blockByHandle.containsKey(handle)) continue;

            InsertInfo ii = tryParseInsert(data, (int)offset, handle, handleToName);
            if (ii != null) {
                inserts.add(ii);
                insertCount++;
            }
        }
        System.out.println("找到 " + insertCount + " 个 INSERT");

        // 打印 INSERT
        System.out.println();
        System.out.println("==================================================");
        System.out.println("  找到的 INSERT 引用:");
        System.out.println("==================================================");
        int idx = 1;
        Map<String, Integer> refCount = new LinkedHashMap<>();
        for (InsertInfo ii : inserts) {
            refCount.merge(ii.blockName, 1, Integer::sum);
            System.out.printf("%n  #%d handle=0x%x @ 0x%x%n", idx++, ii.handle, ii.offset);
            System.out.printf("    引用: %s (block=0x%x)%n", ii.blockName, ii.blockHandle);
            System.out.printf("    插入点: (%.4f, %.4f, %.4f)%n", ii.insX, ii.insY, ii.insZ);
            if (ii.scaleX != 1 || ii.scaleY != 1 || ii.scaleZ != 1)
                System.out.printf("    缩放: (%.4f, %.4f, %.4f)%n", ii.scaleX, ii.scaleY, ii.scaleZ);
            if (ii.rotation != 0)
                System.out.printf("    旋转: %.4f 度%n", ii.rotation * 180.0 / Math.PI);
        }

        // 统计
        System.out.println();
        System.out.println("==================================================");
        System.out.println("  引用统计:");
        System.out.println("==================================================");
        // 加入未引用块
        for (BlockInfo bi : blocks) {
            if (!refCount.containsKey(bi.name)) refCount.put(bi.name + " (未引用)", 0);
        }
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(refCount.entrySet());
        sorted.sort((a, b) -> b.getValue() - a.getValue());
        for (Map.Entry<String, Integer> e : sorted) {
            String bar = "";
            for (int i = 0; i < Math.min(50, e.getValue() * 3); i++) bar += "█";
            System.out.printf("  %-45s : %4d  %s%n", e.getKey(), e.getValue(), bar);
        }
    }

    static void debugParseBlock(byte[] data, int offset, long expectedHandle) {
        BitInput bi = new BitInput(data, offset);
        System.out.println("起始字节偏移: 0x" + Integer.toHexString(offset));

        int objSize = bi.readModularShort();
        System.out.println("objSize (MS): " + objSize);

        int typeCode = bi.readBitShort();
        System.out.println("typeCode (BS): " + typeCode + " (0x" + Integer.toHexString(typeCode) + ")");

        long bitsize = bi.readRawLong();
        System.out.println("bitsize (RL): " + bitsize);

        long handle = bi.readHandle();
        System.out.println("handle (H): 0x" + Long.toHexString(handle));

        // EED loop
        System.out.println("读取 EED...");
        int eedSize = bi.readBitShort();
        System.out.println("  eedSize: " + eedSize);
        int loopCount = 0;
        while (eedSize != 0 && eedSize > 0 && loopCount < 100) {
            long eedHandle = bi.readHandle();
            for (int i = 0; i < eedSize; i++) bi.readByte();
            System.out.println("  loop " + loopCount + ": handle=0x" + Long.toHexString(eedHandle) + " size=" + eedSize);
            eedSize = bi.readBitShort();
            loopCount++;
        }
        System.out.println("  最终 eedSize=" + eedSize + " (应该是0，表示循环结束)");

        // num reactors
        int numReactors = bi.readBitLong();
        System.out.println("numReactors (BL): " + numReactors);

        // reactor handles (skip)
        for (int i = 0; i < numReactors; i++) {
            long rh = bi.readHandle();
        }
        if (numReactors > 0)
            System.out.println("  跳过 " + numReactors + " 个 reactor handles");

        // xdict handle
        long xdictHandle = bi.readHandle();
        System.out.println("xdictHandle (H): 0x" + Long.toHexString(xdictHandle));

        // owner handle
        long ownerHandle = bi.readHandle();
        System.out.println("ownerHandle (H): 0x" + Long.toHexString(ownerHandle));

        // 实体特定数据... 对于 BLOCK_HEADER:
        // blockName (TU)
        int nameLen = bi.readBitShort();
        System.out.println("blockName length (BS): " + nameLen);
        if (nameLen > 0 && nameLen < 100) {
            String name = bi.readText(nameLen);
            System.out.println("blockName: '" + name + "'");
        }

        // flags (BS)
        int flags = bi.readBitShort();
        System.out.println("flags (BS): " + flags + " (0x" + Integer.toHexString(flags) + ")");

        // base point (3BD or 3RD)
        System.out.println("尝试读取 base point (3BD)...");
        double bx = bi.readBitDouble();
        double by = bi.readBitDouble();
        double bz = bi.readBitDouble();
        System.out.println("  BD: (" + bx + ", " + by + ", " + bz + ")");

        // 当前字节位置
        System.out.println("当前字节偏移: 0x" + Integer.toHexString(bi.getBytePos()));

        // xref path
        int xrefLen = bi.readBitShort();
        System.out.println("xrefPath length (BS): " + xrefLen);
        if (xrefLen > 0 && xrefLen < 1000) {
            String xref = bi.readText(xrefLen);
            System.out.println("xrefPath: '" + xref + "'");
        }
    }

    static BlockInfo tryParseBlock(byte[] data, int offset, long handle) {
        try {
            BitInput bi = new BitInput(data, offset);
            int objSize = bi.readModularShort();
            if (objSize <= 0 || objSize > 300) return null;
            if (offset + objSize + 2 > data.length) return null;

            int typeCode = bi.readBitShort();
            if (typeCode < 0 || typeCode > 500) return null;

            // bitsize
            bi.readRawLong();
            // handle (skip, we already have it from handles section)
            bi.readHandle();

            // EED loop
            int eedSize = bi.readBitShort();
            int guard = 0;
            while (eedSize != 0 && eedSize > 0 && guard < 100) {
                bi.readHandle();
                for (int i = 0; i < eedSize; i++) bi.readByte();
                eedSize = bi.readBitShort();
                guard++;
            }

            // num reactors
            int numReactors = bi.readBitLong();
            for (int i = 0; i < numReactors; i++) bi.readHandle();

            // xdict handle
            bi.readHandle();
            // owner handle
            bi.readHandle();

            // blockName (TU)
            int nameLen = bi.readBitShort();
            if (nameLen <= 0 || nameLen > 100) return null;
            if (bi.getBytePos() + nameLen > data.length) return null;

            String name = bi.readText(nameLen);
            // 过滤: 必须是合理的块名
            if (name.length() < 3) return null;
            boolean ascii = true;
            for (int i = 0; i < name.length(); i++) {
                char c = name.charAt(i);
                if (c < 32 || c > 126) { ascii = false; break; }
            }
            if (!ascii) return null;

            // 检查名称模式: 必须以字母、数字或 * 开头
            char first = name.charAt(0);
            if (!(Character.isLetterOrDigit(first) || first == '*' || first == '_')) return null;

            // 确认这不是一个实体类型名称
            if (name.startsWith("AcDb")) return null;

            BlockInfo bl = new BlockInfo();
            bl.handle = handle;
            bl.offset = offset;
            bl.objSize = objSize;
            bl.typeCode = typeCode;
            bl.name = name;

            // flags
            bl.flags = bi.readBitShort();

            // base point (3BD)
            bl.baseX = bi.readBitDouble();
            bl.baseY = bi.readBitDouble();
            bl.baseZ = bi.readBitDouble();

            // 检查坐标合理性
            if (!Double.isFinite(bl.baseX) || Math.abs(bl.baseX) > 1e8) bl.baseX = 0;
            if (!Double.isFinite(bl.baseY) || Math.abs(bl.baseY) > 1e8) bl.baseY = 0;
            if (!Double.isFinite(bl.baseZ) || Math.abs(bl.baseZ) > 1e8) bl.baseZ = 0;

            // xref path
            try {
                int xrefLen = bi.readBitShort();
                if (xrefLen > 0 && xrefLen < 1000 && bi.getBytePos() + xrefLen <= data.length) {
                    bl.xrefPath = bi.readText(xrefLen);
                }
            } catch (Exception e) {}

            return bl;
        } catch (Exception e) {
            return null;
        }
    }

    static InsertInfo tryParseInsert(byte[] data, int offset, long handle, Map<Long, String> handleToName) {
        try {
            BitInput bi = new BitInput(data, offset);
            int objSize = bi.readModularShort();
            if (objSize <= 0 || objSize > 300) return null;
            if (offset + objSize + 2 > data.length) return null;

            int typeCode = bi.readBitShort();
            if (typeCode < 0 || typeCode > 500) return null;

            bi.readRawLong();  // bitsize
            bi.readHandle();   // own handle

            // EED
            int eedSize = bi.readBitShort();
            int guard = 0;
            while (eedSize != 0 && eedSize > 0 && guard < 100) {
                bi.readHandle();
                for (int i = 0; i < eedSize; i++) bi.readByte();
                eedSize = bi.readBitShort();
                guard++;
            }

            int numReactors = bi.readBitLong();
            for (int i = 0; i < numReactors; i++) bi.readHandle();

            bi.readHandle();  // xdict
            bi.readHandle();  // owner

            // INSERT specific:
            long blockHeaderHandle = bi.readHandle();
            if (!handleToName.containsKey(blockHeaderHandle)) return null;

            InsertInfo ii = new InsertInfo();
            ii.handle = handle;
            ii.offset = offset;
            ii.blockHandle = blockHeaderHandle;
            ii.blockName = handleToName.get(blockHeaderHandle);

            // insertion point (3BD)
            ii.insX = bi.readBitDouble();
            ii.insY = bi.readBitDouble();
            ii.insZ = bi.readBitDouble();
            if (!Double.isFinite(ii.insX) || Math.abs(ii.insX) > 1e8) ii.insX = 0;
            if (!Double.isFinite(ii.insY) || Math.abs(ii.insY) > 1e8) ii.insY = 0;
            if (!Double.isFinite(ii.insZ) || Math.abs(ii.insZ) > 1e8) ii.insZ = 0;

            // scale (3BD)
            ii.scaleX = bi.readBitDouble();
            ii.scaleY = bi.readBitDouble();
            ii.scaleZ = bi.readBitDouble();
            if (!Double.isFinite(ii.scaleX) || Math.abs(ii.scaleX) > 1e4) ii.scaleX = 1;
            if (!Double.isFinite(ii.scaleY) || Math.abs(ii.scaleY) > 1e4) ii.scaleY = 1;
            if (!Double.isFinite(ii.scaleZ) || Math.abs(ii.scaleZ) > 1e4) ii.scaleZ = 1;

            // rotation angle (BD)
            ii.rotation = bi.readBitDouble();
            if (!Double.isFinite(ii.rotation)) ii.rotation = 0;

            return ii;
        } catch (Exception e) {
            return null;
        }
    }

    static List<long[]> parseHandles(byte[] data, int offset, int maxBytes) {
        List<long[]> result = new ArrayList<>();
        int pos = offset;
        while (pos < offset + maxBytes && pos < data.length - 4) {
            int pageSize = ((data[pos] & 0xFF) << 8) | (data[pos+1] & 0xFF);
            pos += 2;
            if (pageSize <= 2 || pageSize > 2040) break;
            int pairsSize = pageSize - 2;
            int pairsRead = 0;
            long lastHandle = 0;
            long lastOffset = 0;
            while (pairsRead < pairsSize && pos < data.length - 2) {
                int hByte = data[pos++] & 0xFF;
                pairsRead++;
                long hDelta;
                if (hByte < 0x80) {
                    if (hByte == 0) break;
                    hDelta = hByte;
                } else {
                    int lo = hByte & 0x7F;
                    int hi = data[pos++] & 0xFF;
                    pairsRead++;
                    hDelta = lo | ((long)hi << 7);
                    if (hDelta == 0) break;
                }
                lastHandle += hDelta;

                int oByte = data[pos++] & 0xFF;
                pairsRead++;
                long oDelta;
                if (oByte < 0x80) {
                    oDelta = (oByte > 63) ? (long)oByte - 128 : oByte;
                } else {
                    int lo = oByte & 0x7F;
                    int hi = data[pos++] & 0xFF;
                    pairsRead++;
                    int combined = lo | (hi << 7);
                    oDelta = (combined > 16383) ? (long)combined - 32768 : combined;
                }
                lastOffset += oDelta;
                result.add(new long[]{ lastHandle, lastOffset });
            }
            pos += 2;
        }
        return result;
    }
}
