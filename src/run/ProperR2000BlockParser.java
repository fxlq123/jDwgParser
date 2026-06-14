package run;

import io.dwg.core.io.ByteBufferBitInput;
import io.dwg.core.io.BitStreamReader;
import io.dwg.core.version.DwgVersion;

import java.nio.ByteBuffer;
import java.nio.file.Paths;
import java.util.*;

/**
 * 最终 R2000 块解析器
 *
 * 基于 jDwgParser 代码库的正确字段顺序：
 *   objSize(MS) → typeCode(BS) → bitsize(RL) → handle(H) →
 *   EED(loop) → commonEntity → block-specified
 *
 * BLOCK_HEADER (type = 0x30)
 * INSERT (type = 0x07)
 */
public class ProperR2000BlockParser {

    public static void main(String[] args) throws Exception {
        String filename = "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg";
        byte[] data = java.nio.file.Files.readAllBytes(Paths.get(filename));

        System.out.println();
        System.out.println("=================================================");
        System.out.println("  DWG 块信息解析 (R2000 / AC1015)");
        System.out.println("  文件: " + filename);
        System.out.println("  文件大小: " + data.length + " bytes");
        System.out.println("=================================================");

        // === 第1步: 解析 Handles section 获取 handle → offset 映射 ===
        List<HandleEntry> handles = parseHandlesSection(data, 0x11943, 3000);
        System.out.println();
        System.out.println("[1/3] 句柄表: " + handles.size() + " 条记录");

        // === 第2步: 扫描所有对象 ===
        Map<Long, BlockInfo> blockMap = new LinkedHashMap<>();
        List<InsertInfo> insertList = new ArrayList<>();
        Map<Integer, Integer> typeStats = new LinkedHashMap<>();

        int objectsParsed = 0;
        int parseFailures = 0;

        for (HandleEntry he : handles) {
            if (he.offset <= 0 || he.offset > data.length - 4) continue;

            ByteBufferBitInput bbuf = new ByteBufferBitInput(ByteBuffer.wrap(data));
            bbuf.seek((long) he.offset * 8);
            BitStreamReader r = new BitStreamReader(bbuf, DwgVersion.R2000);

            try {
                // 1. objSize (MS)
                int objSize = r.readModularShort();
                if (objSize <= 0 || objSize > 0x4000) continue;

                // 2. typeCode (BS)
                int typeCode = r.readBitShort();
                typeStats.merge(typeCode, 1, Integer::sum);
                objectsParsed++;

                if (typeCode == 0x30) {  // BLOCK_HEADER
                    BlockInfo bi = parseBlockHeader(r, he.handle);
                    bi.offset = (int)he.offset;
                    bi.objSize = objSize;
                    blockMap.put(he.handle, bi);
                } else if (typeCode == 0x07) {  // INSERT
                    InsertInfo ii = parseInsert(r, he.handle);
                    ii.offset = (int)he.offset;
                    ii.objSize = objSize;
                    insertList.add(ii);
                }
            } catch (Exception e) {
                parseFailures++;
            }
        }

        System.out.println();
        System.out.println("[2/3] 扫描结果: 共解析 " + objectsParsed + " 个对象，失败 " + parseFailures + " 个");

        // 类型统计
        System.out.println("  对象类型统计（Top 10）:");
        List<Map.Entry<Integer, Integer>> sorted = new ArrayList<>(typeStats.entrySet());
        sorted.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        int cnt = 0;
        for (Map.Entry<Integer, Integer> e : sorted) {
            System.out.printf("    Type 0x%02X: %4d 个  %s%n",
                e.getKey(), e.getValue(), getTypeName(e.getKey()));
            if (++cnt >= 10) break;
        }

        // === 第3步: 输出 BLOCK_HEADER ===
        System.out.println();
        System.out.println("=================================================");
        System.out.println("  块定义 (BLOCK_HEADER) — 共 " + blockMap.size() + " 个");
        System.out.println("=================================================");

        int sysBlock = 0, userBlock = 0;
        for (Map.Entry<Long, BlockInfo> entry : blockMap.entrySet()) {
            BlockInfo bi = entry.getValue();
            String name = bi.name.isEmpty() ? "(未命名)" : bi.name;

            boolean isSystem = name.startsWith("*") || name.toLowerCase().contains("model") ||
                               name.toLowerCase().contains("paper");
            if (isSystem) sysBlock++; else userBlock++;

            System.out.println();
            System.out.println("  ━━━━ " + (isSystem ? "【系统块】" : "【用户块】") + " " + name);
            System.out.println("     句柄: 0x" + Long.toHexString(bi.handle));
            System.out.println("     文件偏移: 0x" + Integer.toHexString(bi.offset));
            System.out.println("     对象大小: " + bi.objSize + " bytes");
            if (Double.isFinite(bi.baseX) && (bi.baseX != 0 || bi.baseY != 0 || bi.baseZ != 0)) {
                System.out.printf("     基点: (%.4f, %.4f, %.4f)%n", bi.baseX, bi.baseY, bi.baseZ);
            } else {
                System.out.println("     基点: (0.0000, 0.0000, 0.0000)");
            }
            if (bi.flags != 0) {
                System.out.println("     标志位: 0x" + Integer.toHexString(bi.flags));
            }
            if (bi.xrefPath != null && !bi.xrefPath.isEmpty()) {
                System.out.println("     外部引用路径: " + bi.xrefPath);
            }
        }

        System.out.println();
        System.out.println("  统计: " + sysBlock + " 个系统块, " + userBlock + " 个用户块");

        // === 第4步: 输出 INSERT ===
        System.out.println();
        System.out.println("=================================================");
        System.out.println("  块引用 (INSERT) — 共 " + insertList.size() + " 个");
        System.out.println("=================================================");

        Map<String, Integer> refCount = new LinkedHashMap<>();
        Map<Long, String> handleToName = new HashMap<>();
        for (Map.Entry<Long, BlockInfo> be : blockMap.entrySet()) {
            handleToName.put(be.getKey(),
                be.getValue().name.isEmpty() ? "(未命名)" : be.getValue().name);
        }

        int idx = 1;
        for (InsertInfo ii : insertList) {
            String refName = handleToName.getOrDefault(ii.blockHandle,
                "未知块(handle=0x" + Long.toHexString(ii.blockHandle) + ")");
            refCount.merge(refName, 1, Integer::sum);

            System.out.println();
            System.out.println("  ━━━━ 引用 #" + idx + ": " + refName);
            System.out.println("     句柄: 0x" + Long.toHexString(ii.handle));
            System.out.println("     引用块句柄: 0x" + Long.toHexString(ii.blockHandle));
            if (Double.isFinite(ii.insX) && Math.abs(ii.insX) < 1e6) {
                System.out.printf("     插入点: (%.4f, %.4f, %.4f)%n", ii.insX, ii.insY, ii.insZ);
            }
            if (Double.isFinite(ii.scaleX) && Math.abs(ii.scaleX) < 1e3) {
                System.out.printf("     缩放: (%.4f, %.4f, %.4f)%n", ii.scaleX, ii.scaleY, ii.scaleZ);
            }
            if (Double.isFinite(ii.rotation) && Math.abs(ii.rotation) > 1e-6 && Math.abs(ii.rotation) < 100) {
                System.out.printf("     旋转角度: %.6f 弧度 (%.4f°)%n", ii.rotation, ii.rotation * 180.0 / Math.PI);
            }
            idx++;
        }

        // === 第5步: 引用统计汇总 ===
        System.out.println();
        System.out.println("=================================================");
        System.out.println("  块引用统计");
        System.out.println("=================================================");
        List<Map.Entry<String, Integer>> sortedRef = new ArrayList<>(refCount.entrySet());
        sortedRef.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        for (Map.Entry<String, Integer> e : sortedRef) {
            System.out.printf("  %-50s : %4d 次%n", e.getKey(), (int)e.getValue());
        }

        // === 第6步: 块列表快速视图 ===
        System.out.println();
        System.out.println("=================================================");
        System.out.println("  全部块列表");
        System.out.println("=================================================");
        for (Map.Entry<Long, BlockInfo> be : blockMap.entrySet()) {
            BlockInfo bi = be.getValue();
            String name = bi.name.isEmpty() ? "(未命名)" : bi.name;
            String count = refCount.getOrDefault(name, 0) > 0
                ? refCount.get(name) + " 次引用" : "未引用";
            boolean isSystem = name.startsWith("*") || name.toLowerCase().contains("model") ||
                               name.toLowerCase().contains("paper");
            System.out.printf("  %s  %-40s [handle=0x%s, %s]%n",
                isSystem ? "◆" : "◇",
                name,
                Long.toHexString(bi.handle),
                count);
        }

        System.out.println();
        System.out.println("=================================================");
        System.out.println("  解析完成");
        System.out.println("=================================================");
    }

    // ========== Handles section 解析 ==========
    private static List<HandleEntry> parseHandlesSection(byte[] data, int offset, int maxBytes) {
        List<HandleEntry> result = new ArrayList<>();
        ByteBufferBitInput bbuf = new ByteBufferBitInput(ByteBuffer.wrap(data));
        bbuf.seek((long) offset * 8);
        BitStreamReader r = new BitStreamReader(bbuf, DwgVersion.R2000);

        int totalRead = 0;
        while (totalRead < maxBytes) {
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

                // padding to byte boundary + CRC
                long curPos = r.position();
                if ((curPos % 8) != 0) {
                    int padding = 8 - (int) (curPos % 8);
                    r.getInput().readBits(padding);
                }
                r.readBigEndianShort();
                totalRead += 2;
            } catch (Exception e) {
                break;
            }
        }
        return result;
    }

    // ========== BLOCK_HEADER 解析 (type=0x30) ==========
    private static BlockInfo parseBlockHeader(BitStreamReader r, long handle) {
        BlockInfo bi = new BlockInfo();
        bi.handle = handle;

        try {
            // === Entity Header ===
            // bitsize (RL = 4 raw bytes)
            r.readBitLongLong();  // consume bitsize
            // object handle (H)
            long objH = r.readHandle();
            // EED loop
            while (true) {
                int eedSize = r.readBitShort();
                if (eedSize <= 0 || eedSize > 0x4000) break;
                // appid handle
                r.readHandle();
                // eedSize data bytes
                for (int i = 0; i < eedSize; i++) {
                    r.getInput().readBits(8);
                }
            }

            // === Common Entity Data ===
            // preview_exists (B)
            boolean previewExists = r.getInput().readBit();
            if (previewExists) {
                long previewSize = r.readBitLongLong();
                if (previewSize > 0 && previewSize < 0x100000) {
                    r.seek(r.position() + previewSize * 8L);
                }
            }
            // entmode (BB = 2 bits)
            r.getInput().readBits(2);
            // num_reactors (BL)
            int numReact = r.readBitLong();
            // nolinks (B) [R13-R2002 inclusive]
            r.getInput().readBit();
            // color (BS) [R13-R2003]
            r.readBitShort();
            // ltype_scale (BD) [R13+]
            r.readBitDouble();
            // ltype_flags (BB) + plotstyle_flags (BB)
            r.getInput().readBits(2);
            r.getInput().readBits(2);
            // invisible (BS)
            r.readBitShort();
            // linewt (RC)
            r.getInput().readBits(8);

            // === Block specific fields ===
            // blockName (TU)
            bi.name = r.readVariableText();
            // flags (BS)
            bi.flags = r.readBitShort();
            // basePoint (3RD)
            double[] bp = r.read3RawDouble();
            bi.baseX = bp[0];
            bi.baseY = bp[1];
            bi.baseZ = bp[2];
            // xrefPath (TU)
            bi.xrefPath = r.readVariableText();

        } catch (Exception e) {
            // 记录部分解析信息
        }

        return bi;
    }

    // ========== INSERT 解析 (type=0x07) ==========
    private static InsertInfo parseInsert(BitStreamReader r, long handle) {
        InsertInfo ii = new InsertInfo();
        ii.handle = handle;

        try {
            // === Entity Header ===
            // bitsize (RL)
            r.readBitLongLong();
            // object handle (H)
            long objH = r.readHandle();
            // EED loop
            while (true) {
                int eedSize = r.readBitShort();
                if (eedSize <= 0 || eedSize > 0x4000) break;
                r.readHandle();
                for (int i = 0; i < eedSize; i++) r.getInput().readBits(8);
            }

            // === Common Entity Data (same as BLOCK_HEADER) ===
            boolean previewExists = r.getInput().readBit();
            if (previewExists) {
                long previewSize = r.readBitLongLong();
                if (previewSize > 0 && previewSize < 0x100000) {
                    r.seek(r.position() + previewSize * 8L);
                }
            }
            r.getInput().readBits(2);  // entmode
            int numReact = r.readBitLong();
            r.getInput().readBit();     // nolinks
            r.readBitShort();           // color
            r.readBitDouble();          // ltype_scale
            r.getInput().readBits(2);   // ltype_flags
            r.getInput().readBits(2);   // plotstyle_flags
            r.readBitShort();           // invisible
            r.getInput().readBits(8);   // linewt

            // === INSERT specific fields ===
            // block_header handle (H)
            ii.blockHandle = r.readHandle();
            // insertion point (3BD)
            double[] ip = r.read3BitDouble();
            ii.insX = ip[0];
            ii.insY = ip[1];
            ii.insZ = ip[2];
            // scale (3BD)
            double[] sc = r.read3BitDouble();
            ii.scaleX = sc[0];
            ii.scaleY = sc[1];
            ii.scaleZ = sc[2];
            // rotation angle (BD)
            ii.rotation = r.readBitDouble();
            // attribute follows (B) + attribute count (BS)
            r.getInput().readBit();
            r.readBitShort();

        } catch (Exception e) {
            // 记录部分解析信息
        }

        return ii;
    }

    // ========== 类型名辅助 ==========
    private static String getTypeName(int typeCode) {
        return switch (typeCode) {
            case 0x07 -> "INSERT";
            case 0x08 -> "MINSERT";
            case 0x0F -> "LINE";
            case 0x11 -> "CIRCLE";
            case 0x12 -> "ARC";
            case 0x14 -> "SPLINE";
            case 0x15 -> "ELLIPSE";
            case 0x1A -> "POINT";
            case 0x1F -> "SOLID";
            case 0x25 -> "LWPOLYLINE";
            case 0x27 -> "HATCH";
            case 0x2B -> "MTEXT";
            case 0x30 -> "BLOCK_HEADER";
            case 0x31 -> "BLOCK_END";
            case 0x1F7 -> "DIMENSION";
            default -> "UNKNOWN";
        };
    }

    // ========== 数据类 ==========
    static class HandleEntry {
        long handle;
        long offset;
        HandleEntry(long h, long o) { handle = h; offset = o; }
    }

    static class BlockInfo {
        long handle;
        int offset;
        int objSize;
        String name = "";
        int flags;
        double baseX, baseY, baseZ;
        String xrefPath = "";
    }

    static class InsertInfo {
        long handle;
        int offset;
        int objSize;
        long blockHandle;
        double insX, insY, insZ;
        double scaleX = 1.0, scaleY = 1.0, scaleZ = 1.0;
        double rotation;
    }
}
