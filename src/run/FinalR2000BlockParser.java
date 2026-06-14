package run;

import io.dwg.core.io.ByteBufferBitInput;
import io.dwg.core.io.BitStreamReader;
import io.dwg.core.version.DwgVersion;

import java.nio.ByteBuffer;
import java.nio.file.Paths;
import java.util.*;

/**
 * 最终版 R2000 块解析器
 *
 * 关键观察：
 * - BLOCK_HEADER type=0x05 的对象数据开头不是标准的 numReactors+handle
 * - 需要在对象数据中查找 ASCII 字符串（块名）
 * - 对象开头的字节: objSize(MS, 2 bytes LE) + typeCode(BS, 0x05 or 0x30) + ...
 *
 * 实际格式：
 * - MS(objSize) -> 2 bytes little-endian (LE)
 * - BS(typeCode, 0x05) -> opcode 00, read 16 bits LE
 * - ... then object-specific data
 *
 * 我们通过查找可打印ASCII字符串来定位块名字段
 */
public class FinalR2000BlockParser {

    public static void main(String[] args) throws Exception {
        String filename = "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg";
        byte[] data = java.nio.file.Files.readAllBytes(Paths.get(filename));

        System.out.println();
        System.out.println("==========================================================");
        System.out.println("  文件: " + filename);
        System.out.println("  版本: R2000 (AC1015)");
        System.out.println("  大小: " + data.length + " bytes");
        System.out.println("==========================================================");

        // === 第1步：解析 Handles section ===
        List<HandleEntry> handles = parseHandlesSection(data, 0x11943, 2000);
        System.out.println();
        System.out.println("[1/4] Handles Section: " + handles.size() + " entries");

        // === 第2步：扫描所有对象，找出 BLOCK_HEADER 和 INSERT ===
        List<BlockData> blockHeaders = new ArrayList<>();
        List<InsertData> inserts = new ArrayList<>();
        Map<Integer, Integer> typeStats = new LinkedHashMap<>();

        for (HandleEntry he : handles) {
            if (he.offset <= 0 || he.offset + 4 > data.length) continue;
            ObjectInfo oi = parseObjectAt(data, (int) he.offset);
            if (oi == null) continue;

            typeStats.merge(oi.typeCode, 1, Integer::sum);

            if (oi.typeCode == 0x05) {
                BlockData bd = extractBlockData(data, oi);
                bd.handle = he.handle;
                blockHeaders.add(bd);
            } else if (oi.typeCode == 0x07) {
                InsertData id = extractInsertInfo(data, oi);
                id.handle = he.handle;
                inserts.add(id);
            }
        }

        System.out.println();
        System.out.println("[2/4] 对象类型统计 (top 10):");
        List<Map.Entry<Integer, Integer>> sortedTypes = new ArrayList<>(typeStats.entrySet());
        sortedTypes.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        int shown = 0;
        for (Map.Entry<Integer, Integer> e : sortedTypes) {
            String tn = typeName(e.getKey());
            System.out.printf("  Type 0x%02X (%3d): %4d  %s%n",
                e.getKey(), e.getKey(), e.getValue(), tn);
            if (++shown >= 10) break;
        }

        // === 第3步：输出 BLOCK_HEADER ===
        System.out.println();
        System.out.println("==========================================================");
        System.out.println("[3/4] BLOCK_HEADER 定义 (type=0x05): 共 " + blockHeaders.size() + " 个");
        System.out.println("==========================================================");

        // 构建 block handle -> name 映射
        Map<Long, String> blockNameMap = new HashMap<>();
        int sysCount = 0, userCount = 0;

        for (BlockData bd : blockHeaders) {
            String name = bd.name.isEmpty() ? "(未命名)" : bd.name;
            blockNameMap.put(bd.handle, name);

            boolean isSystem = name.startsWith("*") || name.toLowerCase().contains("model") ||
                               name.toLowerCase().contains("paper");
            if (isSystem) sysCount++; else userCount++;

            String type = isSystem ? "系统块" : "用户块";
            System.out.println();
            System.out.println("  ━━ 块: " + name + " (" + type + ")");
            System.out.println("     句柄: 0x" + Long.toHexString(bd.handle));
            System.out.println("     偏移: 0x" + Integer.toHexString(bd.offset) + " (" + bd.offset + ")");
            System.out.println("     块名: " + bd.name);
            if (Double.isFinite(bd.baseX) && bd.baseX != 0) {
                System.out.printf("     基点: (%.4f, %.4f, %.4f)%n", bd.baseX, bd.baseY, bd.baseZ);
            } else {
                System.out.println("     基点: (0.0000, 0.0000, 0.0000)");
            }
            if (bd.flags != 0) {
                System.out.println("     标志: 0x" + Integer.toHexString(bd.flags));
            }
            if (!bd.xrefPath.isEmpty()) {
                System.out.println("     外部引用: " + bd.xrefPath);
            }
        }

        System.out.println();
        System.out.println("  系统块: " + sysCount + " 个, 用户块: " + userCount + " 个");

        // === 第4步：输出 INSERT 引用 ===
        System.out.println();
        System.out.println("==========================================================");
        System.out.println("[4/4] INSERT 块引用 (type=0x07): 共 " + inserts.size() + " 个");
        System.out.println("==========================================================");

        Map<String, Integer> insertCount = new LinkedHashMap<>();

        int idx = 1;
        for (InsertData id : inserts) {
            String refName = blockNameMap.getOrDefault(id.blockHandle,
                "未知块(handle=0x" + Long.toHexString(id.blockHandle) + ")");
            insertCount.merge(refName, 1, Integer::sum);

            System.out.println();
            System.out.println("  ━━ 引用 #" + idx + ": " + refName);
            System.out.println("     句柄: 0x" + Long.toHexString(id.handle));
            System.out.println("     块句柄: 0x" + Long.toHexString(id.blockHandle));
            if (Double.isFinite(id.x) && Math.abs(id.x) < 1e8) {
                System.out.printf("     插入点: (%.4f, %.4f, %.4f)%n", id.x, id.y, id.z);
            }
            if (Double.isFinite(id.sx) && (id.sx != 1.0 || id.sy != 1.0 || id.sz != 1.0) && Math.abs(id.sx) < 1000) {
                System.out.printf("     缩放: (%.4f, %.4f, %.4f)%n", id.sx, id.sy, id.sz);
            }
            if (Double.isFinite(id.rotation) && Math.abs(id.rotation) > 1e-6 && Math.abs(id.rotation) < 1000) {
                System.out.printf("     旋转: %.6f rad (%.4f 度)%n", id.rotation, id.rotation * 180.0 / Math.PI);
            }
            idx++;
        }

        // === 汇总：INSERT 按块名统计 ===
        System.out.println();
        System.out.println("==========================================================");
        System.out.println("  块引用统计汇总");
        System.out.println("==========================================================");
        List<Map.Entry<String, Integer>> sortedInsert = new ArrayList<>(insertCount.entrySet());
        sortedInsert.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        for (Map.Entry<String, Integer> e : sortedInsert) {
            System.out.printf("  %-45s : %d 次%n", e.getKey(), e.getValue());
        }

        // === 快速列表：用户块名 ===
        System.out.println();
        System.out.println("==========================================================");
        System.out.println("  用户定义块列表");
        System.out.println("==========================================================");
        for (BlockData bd : blockHeaders) {
            String name = bd.name.isEmpty() ? "(未命名)" : bd.name;
            boolean isSystem = name.startsWith("*") || name.toLowerCase().contains("model") ||
                               name.toLowerCase().contains("paper");
            if (!isSystem) {
                String count = insertCount.containsKey(name) ? insertCount.get(name) + " 次引用" : "未引用";
                System.out.println("  • " + name + "  [" + count + "]");
            }
        }

        // === 系统块列表 ===
        System.out.println();
        System.out.println("==========================================================");
        System.out.println("  系统块列表");
        System.out.println("==========================================================");
        for (BlockData bd : blockHeaders) {
            String name = bd.name.isEmpty() ? "(未命名)" : bd.name;
            boolean isSystem = name.startsWith("*") || name.toLowerCase().contains("model") ||
                               name.toLowerCase().contains("paper");
            if (isSystem) {
                System.out.println("  • " + name);
            }
        }

        System.out.println();
        System.out.println("==========================================================");
        System.out.println("  解析完成");
        System.out.println("==========================================================");
    }

    // ============ Handles section 解析 ============
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

                long lastHandle = 0;
                long lastOffset = 0;
                int pairsSize = pageSize - 2;
                int pairsRead = 0;

                while (pairsRead < pairsSize) {
                    long before = r.position();
                    int hDelta = r.readUnsignedModularChar();
                    if (hDelta == 0) break;
                    int oDelta = r.readModularChar();
                    long after = r.position();
                    int bytes = (int) ((after - before) / 8);
                    pairsRead += bytes;
                    totalRead += bytes;

                    lastHandle += hDelta;
                    lastOffset += oDelta;
                    result.add(new HandleEntry(lastHandle, lastOffset));
                }

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

    // ============ 对象级解析 ============
    private static ObjectInfo parseObjectAt(byte[] data, int offset) {
        try {
            ByteBufferBitInput bbuf = new ByteBufferBitInput(ByteBuffer.wrap(data));
            bbuf.seek((long) offset * 8);
            BitStreamReader r = new BitStreamReader(bbuf, DwgVersion.R2000);

            int objSize = r.readModularShort();
            if (objSize <= 0 || objSize > 0x4000) return null;

            int typeCode = r.readBitShort();
            if (typeCode < 0 || typeCode > 0x200) return null;

            ObjectInfo oi = new ObjectInfo();
            oi.offset = offset;
            oi.objSize = objSize;
            oi.typeCode = typeCode;
            oi.dataStartBit = bbuf.position(); // 数据起始bit位置（MS+BS之后）
            return oi;
        } catch (Exception e) {
            return null;
        }
    }

    // ============ BLOCK_HEADER 数据提取 ============
    private static BlockData extractBlockData(byte[] data, ObjectInfo oi) {
        BlockData bd = new BlockData();
        bd.offset = oi.offset;

        try {
            // 从对象数据中查找可打印字符串 (块名)
            int objStartByte = oi.offset;
            // 粗略估计：MS = 2 bytes，BS = 2 bytes，所以从 offset+4 开始查找
            int searchStart = objStartByte + 4;
            int searchEnd = objStartByte + Math.min(oi.objSize + 4, 200);
            searchEnd = Math.min(searchEnd, data.length);

            // 查找 ASCII 字符串
            String bestString = "";
            int bestLen = 0;
            StringBuilder current = new StringBuilder();

            for (int i = searchStart; i < searchEnd; i++) {
                int b = data[i] & 0xFF;
                // 块名字符: 字母、数字、常见符号
                if (b >= 32 && b <= 126) {
                    current.append((char) b);
                } else {
                    if (current.length() > bestLen && current.length() >= 2) {
                        boolean hasLetter = false;
                        for (int j = 0; j < current.length(); j++) {
                            char c = current.charAt(j);
                            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '_') {
                                hasLetter = true;
                                break;
                            }
                        }
                        if (hasLetter) {
                            bestString = current.toString();
                            bestLen = current.length();
                        }
                    }
                    current.setLength(0);
                }
            }
            // check last string
            if (current.length() > bestLen && current.length() >= 2) {
                boolean hasLetter = false;
                for (int j = 0; j < current.length(); j++) {
                    char c = current.charAt(j);
                    if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '_') {
                        hasLetter = true;
                        break;
                    }
                }
                if (hasLetter) bestString = current.toString();
            }

            bd.name = bestString;

            // 尝试读取块名之后的 flags 和基点
            // 使用 BitStream 从 dataStartBit 之后尝试
            ByteBufferBitInput bbuf = new ByteBufferBitInput(ByteBuffer.wrap(data));
            BitStreamReader r = new BitStreamReader(bbuf, DwgVersion.R2000);

            // 尝试多个起始位置
            double[] bestBase = {0, 0, 0};
            int bestFlags = 0;
            boolean foundGoodBase = false;

            // 从 dataStartBit 之后的若干位置尝试
            long startBit = oi.dataStartBit;
            for (int skipBits = 0; skipBits <= 128; skipBits += 2) {
                try {
                    bbuf.seek(startBit + skipBits);
                    // 尝试：T(blockName) 之后是 BS(flags) + 3BD(base point)
                    // 先尝试直接读 flags (BS)
                    int flags = r.readBitShort();
                    if (flags >= 0 && flags <= 0x7F) {
                        double x = r.readBitDouble();
                        double y = r.readBitDouble();
                        double z = r.readBitDouble();
                        if (Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z) &&
                            Math.abs(x) < 1e8 && Math.abs(y) < 1e8 && Math.abs(z) < 1e8 &&
                            !(x == 0 && y == 0 && z == 0)) {
                            // 找到合理的值
                            bestBase[0] = x;
                            bestBase[1] = y;
                            bestBase[2] = z;
                            bestFlags = flags;
                            foundGoodBase = true;
                            break;
                        }
                    }
                } catch (Exception ex) {
                    // continue
                }
            }

            if (foundGoodBase) {
                bd.baseX = bestBase[0];
                bd.baseY = bestBase[1];
                bd.baseZ = bestBase[2];
                bd.flags = bestFlags;
            }
        } catch (Exception e) {
            // 出错时已设置默认值
        }
        return bd;
    }

    // ============ INSERT 数据提取 ============
    private static InsertData extractInsertInfo(byte[] data, ObjectInfo oi) {
        InsertData id = new InsertData();
        id.offset = oi.offset;

        try {
            // INSERT: 在对象数据中找一个合理的 handle (引用块的句柄)
            // 然后是 3BD(insertion point) + 3BD(scale) + BD(rotation)
            long startBit = oi.dataStartBit;
            ByteBufferBitInput bbuf = new ByteBufferBitInput(ByteBuffer.wrap(data));

            // 尝试多个跳过长度，寻找 block handle + 3BD 组合
            for (int skipBits = 0; skipBits <= 200; skipBits += 2) {
                try {
                    bbuf.seek(startBit + skipBits);
                    BitStreamReader r = new BitStreamReader(bbuf, DwgVersion.R2000);
                    long blockHandle = r.readHandle();
                    if (blockHandle > 0 && blockHandle < 0x5000) {
                        double x = r.readBitDouble();
                        double y = r.readBitDouble();
                        double z = r.readBitDouble();
                        if (Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z) &&
                            Math.abs(x) < 1e8 && Math.abs(y) < 1e8 && Math.abs(z) < 1e8) {
                            double sx = r.readBitDouble();
                            double sy = r.readBitDouble();
                            double sz = r.readBitDouble();
                            double rot = r.readBitDouble();
                            if (Double.isFinite(sx) && Double.isFinite(sy) && Double.isFinite(sz) &&
                                Math.abs(sx) < 1000 && Math.abs(sy) < 1000 && Math.abs(sz) < 1000 &&
                                Double.isFinite(rot) && Math.abs(rot) < 1000) {
                                id.blockHandle = blockHandle;
                                id.x = x;
                                id.y = y;
                                id.z = z;
                                id.sx = sx;
                                id.sy = sy;
                                id.sz = sz;
                                id.rotation = rot;
                                return id;
                            }
                        }
                    }
                } catch (Exception ex) {
                    // continue
                }
            }
        } catch (Exception e) {
            // skip
        }
        return id;
    }

    // ============ 辅助：类型名称 ============
    private static String typeName(int tc) {
        return switch (tc) {
            case 0x01 -> "TEXT";
            case 0x02 -> "ATTRIB";
            case 0x03 -> "ATTDEF";
            case 0x05 -> "BLOCK_HEADER";
            case 0x06 -> "BLOCK_ENDBLK";
            case 0x07 -> "INSERT";
            case 0x08 -> "MINSERT";
            case 0x0F -> "LINE";
            case 0x11 -> "CIRCLE";
            case 0x12 -> "ARC";
            case 0x13 -> "LINE";
            case 0x14 -> "SPLINE";
            case 0x15 -> "ELLIPSE";
            case 0x1A -> "POINT";
            case 0x1F -> "SOLID";
            case 0x20 -> "TRACE";
            case 0x25 -> "LWPOLYLINE";
            case 0x27 -> "HATCH";
            case 0x28 -> "XRECORD";
            case 0x2B -> "MTEXT";
            case 0x2C -> "MTEXT";
            case 0x2E -> "LEADER";
            case 0x2F -> "TOLERANCE";
            case 0x30 -> "BLOCK_HEADER(alt)";
            case 0x31 -> "BLOCK_END";
            case 0x32 -> "INSERT(alt)";
            case 0x36 -> "DICTIONARY";
            case 0x3B -> "LAYOUT";
            case 0x43 -> "OLE2FRAME";
            case 0x4B -> "DICTIONARYVAR";
            case 0x4C -> "PLACEHOLDER";
            case 0x100 -> "LAYER";
            case 0x102 -> "STYLE";
            case 0x103 -> "LTYPE";
            case 0x104 -> "DIMSTYLE";
            case 0x107 -> "VIEWPORT";
            case 0x108 -> "APPID";
            case 0x110 -> "MLINESTYLE";
            case 0x1F7 -> "DIMENSION_LINEAR";
            default -> "UNKNOWN";
        };
    }

    // ============ 数据类 ============
    private static class HandleEntry {
        long handle;
        long offset;
        HandleEntry(long h, long o) { handle = h; offset = o; }
    }

    private static class ObjectInfo {
        int offset;
        int objSize;
        int typeCode;
        long dataStartBit;
    }

    private static class BlockData {
        long handle;
        int offset;
        String name = "";
        double baseX, baseY, baseZ;
        int flags;
        String xrefPath = "";
    }

    private static class InsertData {
        long handle;
        int offset;
        long blockHandle;
        double x, y, z;
        double sx = 1.0, sy = 1.0, sz = 1.0;
        double rotation;
    }
}
