import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class DebugHandles2 {
    public static void main(String[] args) throws Exception {
        byte[] data = Files.readAllBytes(Paths.get(
            "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));
        System.out.println("文件大小: " + data.length + " (0x" + Integer.toHexString(data.length) + ")");

        List<long[]> handles = parseHandles(data, 0x11943, 8192);
        System.out.println("Handles: " + handles.size());

        // 显示 30 个合理范围内的 handle 和 offset
        System.out.println("\n=== 30 个 handle 条目 (正确的无符号 offset) ===");
        for (int i = 0; i < Math.min(60, handles.size()); i++) {
            long[] h = handles.get(i);
            // offset 是累积的，应该是正值
            long off = h[1] & 0xFFFFFFFFL; // 无符号
            String bytes = "";
            if (off < data.length - 8) {
                bytes = String.format("%02X %02X %02X %02X %02X %02X %02X %02X",
                    data[(int)off] & 0xFF, data[(int)off+1] & 0xFF,
                    data[(int)off+2] & 0xFF, data[(int)off+3] & 0xFF,
                    data[(int)off+4] & 0xFF, data[(int)off+5] & 0xFF,
                    data[(int)off+6] & 0xFF, data[(int)off+7] & 0xFF);
            }
            System.out.printf("[%2d] handle=0x%x  offset=0x%x  bytes: %s%n", i, h[0], off, bytes);
        }

        // 现在用无符号的方式重新解析
        System.out.println("\n=== 用正确的累积方式重解析 (有符号 delta 累积) ===");
        List<long[]> handles2 = parseHandlesV2(data, 0x11943, 8192);
        long minO = Long.MAX_VALUE, maxO = 0;
        long minH = Long.MAX_VALUE, maxH = 0;
        for (long[] h : handles2) {
            if (h[1] > 0) {
                minO = Math.min(minO, h[1]);
                maxO = Math.max(maxO, h[1]);
            }
            if (h[0] > 0) {
                minH = Math.min(minH, h[0]);
                maxH = Math.max(maxH, h[0]);
            }
        }
        System.out.println("handle 范围: 0x" + Long.toHexString(minH) + " - 0x" + Long.toHexString(maxH));
        System.out.println("offset 范围: 0x" + Long.toHexString(minO) + " - 0x" + Long.toHexString(maxO));
        System.out.println("offset 范围: " + minO + " - " + maxO);

        // 现在检查不同的 base: 0x50B9 (Section 间隙)
        System.out.println("\n=== 测试 base=0x50B9, 扫描前 50 个对象 ===");
        int base = 0x50B9;
        for (int i = 0; i < Math.min(50, handles2.size()); i++) {
            long[] h = handles2.get(i);
            int absOffset = base + (int)(h[1] & 0xFFFFFFFFL);
            if (absOffset > 0 && absOffset < data.length - 8) {
                // 解析 objSize (LE uint16)
                int objSize = (data[absOffset] & 0xFF) | ((data[absOffset+1] & 0xFF) << 8);
                // typeCode (BS)
                int b2 = data[absOffset+2] & 0xFF;
                int opcode = (b2 >> 6) & 0x3;
                int typeCode = -1;
                if (opcode == 1) {
                    int b3 = data[absOffset+3] & 0xFF;
                    int val8 = ((b2 & 0x3F) << 2) | ((b3 >> 6) & 0x3);
                    typeCode = val8;
                } else if (opcode == 0) {
                    int b3 = data[absOffset+3] & 0xFF;
                    int b4 = data[absOffset+4] & 0xFF;
                    int lo8 = ((b2 & 0x3F) << 2) | ((b3 >> 6) & 0x3);
                    int hi8 = ((b3 & 0x3F) << 2) | ((b4 >> 6) & 0x3);
                    typeCode = lo8 | (hi8 << 8);
                }
                String tn = typeName(typeCode);
                String bytes = String.format("%02X %02X %02X %02X %02X %02X %02X %02X",
                    data[absOffset] & 0xFF, data[absOffset+1] & 0xFF,
                    data[absOffset+2] & 0xFF, data[absOffset+3] & 0xFF,
                    data[absOffset+4] & 0xFF, data[absOffset+5] & 0xFF,
                    data[absOffset+6] & 0xFF, data[absOffset+7] & 0xFF);
                System.out.printf("[%2d] h=0x%x o=0x%04x(+%d) oSize=%5d tCode=%5d %s  bytes: %s%n",
                    i, h[0], absOffset, h[1], objSize, typeCode, tn, bytes);
            }
        }

        // 测试不同的 base
        System.out.println("\n=== 测试不同的 base 地址 ===");
        int[] testBases = { 0x5000, 0x50B9, 0x5400, 0x5480, 0x4F00, 0x4800, 0x5484, 0x5474 };
        for (int b : testBases) {
            int valid = 0;
            int blkHeader = 0, blkEnd = 0, insert = 0, dict = 0;
            for (long[] h : handles2) {
                int absOffset = b + (int)(h[1] & 0xFFFFFFFFL);
                if (absOffset > 0 && absOffset < data.length - 4) {
                    int objSize = (data[absOffset] & 0xFF) | ((data[absOffset+1] & 0xFF) << 8);
                    if (objSize > 0 && objSize <= 500) {
                        int b2 = data[absOffset+2] & 0xFF;
                        int opcode = (b2 >> 6) & 0x3;
                        if (opcode == 1) {
                            int b3 = data[absOffset+3] & 0xFF;
                            int typeCode = ((b2 & 0x3F) << 2) | ((b3 >> 6) & 0x3);
                            valid++;
                            if (typeCode == 48) blkHeader++;
                            if (typeCode == 49) blkEnd++;
                            if (typeCode == 7) insert++;
                            if (typeCode == 42) dict++;
                        }
                    }
                }
            }
            System.out.printf("base=0x%04x: valid=%d  BLOCK_HEADER=%d  BLOCK_END=%d  INSERT=%d  DICTIONARY=%d%n",
                b, valid, blkHeader, blkEnd, insert, dict);
        }
    }

    static String typeName(int code) {
        String[] names = {
            "UNUSED", "TEXT", "ATTDEF", "ATTRIB", "SEQEND", "ENDBLK", "", "INSERT", "MINSERT",
            "", "V2D", "V3D", "VMESH", "VPF", "VPFF",
            "PL2D", "PL3D", "ARC", "CIRCLE", "LINE",
            "DIMO", "DIML", "DIMA", "DIM3",
            "DIM2", "DIMR", "DIMD", "POINT", "FACE",
            "PLPF", "PLPM", "SOLID", "TRACE", "SHAPE", "VPORT",
            "EL", "SPL", "REG", "S3D", "BODY", "RAY", "XLINE", "DICT",
            "", "MTEXT", "LEAD", "TOL", "MLINE", "BLKH", "BLKE",
            "LTYPE", "LAYER", "STYLE"
        };
        if (code >= 0 && code < names.length && names[code] != null && !names[code].isEmpty()) return names[code];
        switch(code) {
            case 0x3C: return "GROUP";
            case 0x4B: return "LWPLINE";
            case 0x4C: return "HATCH";
            case 0x4D: return "XRECORD";
            case 0x50: return "LAYOUT";
            default: return "?";
        }
    }

    // 版本 2: 正确累积 (handle_delta 始终正, offset_delta 有符号)
    static List<long[]> parseHandlesV2(byte[] data, int offset, int maxBytes) {
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
                // handle delta (无符号 modular short)
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

                // offset delta (有符号 modular short)
                int oByte = data[pos++] & 0xFF;
                pairsRead++;
                long oDelta;
                if (oByte < 0x80) {
                    // 7-bit 有符号扩展
                    oDelta = (oByte & 0x40) != 0 ? (long)oByte - 128 : (long)oByte;
                } else {
                    int lo = oByte & 0x7F;
                    int hi = data[pos++] & 0xFF;
                    pairsRead++;
                    // 15-bit 有符号扩展
                    int combined = lo | (hi << 7);
                    oDelta = (combined & 0x4000) != 0 ? (long)combined - 32768 : (long)combined;
                }
                lastOffset += oDelta;
                result.add(new long[]{ lastHandle, lastOffset });
            }
            pos += 2;  // CRC
        }
        return result;
    }

    static List<long[]> parseHandles(byte[] data, int offset, int maxBytes) {
        return parseHandlesV2(data, offset, maxBytes);
    }
}
