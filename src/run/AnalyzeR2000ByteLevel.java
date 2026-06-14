package run;

import java.nio.file.Paths;

/**
 * 字节级分析 R2000 BLOCK 和 INSERT 对象的精确字段结构
 * 目标：找出每个字段的确切起始位置
 */
public class AnalyzeR2000ByteLevel {

    static byte[] data;

    public static void main(String[] args) throws Exception {
        String filename = "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg";
        data = java.nio.file.Files.readAllBytes(Paths.get(filename));

        // 已知的 BLOCK (0x31) offsets
        long[] blockOffsets = {23203, 23159, 21636, 21677, 21719};

        System.out.println("=== BLOCK 字节级字段分析 ===");
        for (long offset : blockOffsets) {
            analyzeBlock(offset);
        }

        // INSERT offsets (0x07)
        long[] insertOffsets = {50409, 52467};
        System.out.println("\n=== INSERT 字节级字段分析 ===");
        for (long offset : insertOffsets) {
            analyzeInsert(offset);
        }

        // 关键：分析 BLOCK 特有的字符串字段位置
        // 在 data 中搜索 "Paper_Space" 或 "Model_Space" 等字符串
        System.out.println("\n=== 搜索已知的块名字符串 ===");
        byte[] search = "Paper_Space".getBytes();
        for (int i = 0; i < data.length - search.length; i++) {
            boolean matched = true;
            for (int j = 0; j < search.length; j++) {
                if ((data[i + j] & 0xFF) != (search[j] & 0xFF)) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                System.out.println("'Paper_Space' 在 offset=" + i);
                // 显示附近的 40 字节
                int start = Math.max(0, i - 20);
                int end = Math.min(data.length - 1, i + 30);
                System.out.print("  hex: ");
                for (int k = start; k <= end; k++) {
                    System.out.printf("%02X ", data[k] & 0xFF);
                }
                System.out.println();
                System.out.print("  asc: ");
                for (int k = start; k <= end; k++) {
                    int b = data[k] & 0xFF;
                    System.out.print((b >= 32 && b < 127) ? (char)b : '.');
                }
                System.out.println();
            }
        }
    }

    private static void analyzeBlock(long offset) {
        int objSize = data[(int)offset] & 0xFF | (data[(int)offset + 1] & 0xFF) << 8;
        int typeCode = data[(int)offset + 2] & 0xFF | (data[(int)offset + 3] & 0xFF) << 8;

        System.out.println("\n[BLOCK @offset=" + offset + " size=" + objSize + " type=0x" + Integer.toHexString(typeCode) + "]");

        // 16 字节一行 dump
        for (int row = 0; row < Math.min(5, (objSize + 15) / 16); row++) {
            System.out.printf("%4d: ", row * 16);
            for (int c = 0; c < 16; c++) {
                int idx = (int)offset + row * 16 + c;
                if (idx < data.length) System.out.printf("%02X ", data[idx] & 0xFF);
                else System.out.print("   ");
            }
            System.out.print(" | ");
            for (int c = 0; c < 16; c++) {
                int idx = (int)offset + row * 16 + c;
                if (idx < data.length) {
                    int b = data[idx] & 0xFF;
                    System.out.print((b >= 32 && b < 127) ? (char)b : '.');
                } else System.out.print(" ");
            }
            System.out.println();
        }

        // 从 offset + 4 开始的字节可能是 bitsize(RL)
        int bitsize = readLE32((int)offset + 4);
        System.out.println("LE32 @offset+4 (bitsize candidate): " + bitsize);

        // offset + 8 开始是 handle(H): 格式: 1 byte code[high4]+count[low4], count bytes
        int byte8 = data[(int)offset + 8] & 0xFF;
        int handleCode = byte8 >> 4;
        int handleCounter = byte8 & 0x0F;
        System.out.println("offset+8 (H-first-byte): 0x" + Integer.toHexString(byte8) + " code=" + handleCode + " count=" + handleCounter);

        if (handleCounter > 0 && handleCounter < 8) {
            long handleVal = 0;
            for (int i = 0; i < handleCounter; i++) {
                handleVal = (handleVal << 8) | (data[(int)offset + 9 + i] & 0xFF);
            }
            System.out.println("  entity_handle value: 0x" + Long.toHexString(handleVal) + " (" + handleVal + ")");
        }

        // 读取字符串长度：
        // 如果 ent_handle counter=1, ent_handle 占 2 bytes (1 header + 1 value)
        // offset+10 之后可能是 EED_size(BS)
        if (handleCounter == 1) {
            int eedByte = data[(int)offset + 10] & 0xFF;
            System.out.println("offset+10 (EED_size BS?): 0x" + Integer.toHexString(eedByte));
        }
    }

    private static void analyzeInsert(long offset) {
        int objSize = data[(int)offset] & 0xFF | (data[(int)offset + 1] & 0xFF) << 8;
        int typeCode = data[(int)offset + 2] & 0xFF | (data[(int)offset + 3] & 0xFF) << 8;

        System.out.println("\n[INSERT @offset=" + offset + " size=" + objSize + " type=0x" + Integer.toHexString(typeCode) + "]");

        // 前 16 字节 dump (byte-aligned)
        for (int row = 0; row < Math.min(4, (objSize + 15) / 16); row++) {
            System.out.printf("%4d: ", row * 16);
            for (int c = 0; c < 16; c++) {
                int idx = (int)offset + row * 16 + c;
                if (idx < data.length) System.out.printf("%02X ", data[idx] & 0xFF);
                else System.out.print("   ");
            }
            System.out.print(" | ");
            for (int c = 0; c < 16; c++) {
                int idx = (int)offset + row * 16 + c;
                if (idx < data.length) {
                    int b = data[idx] & 0xFF;
                    System.out.print((b >= 32 && b < 127) ? (char)b : '.');
                } else System.out.print(" ");
            }
            System.out.println();
        }
    }

    private static int readLE32(int offset) {
        if (offset + 3 >= data.length) return 0;
        long b0 = data[offset] & 0xFF;
        long b1 = data[offset + 1] & 0xFF;
        long b2 = data[offset + 2] & 0xFF;
        long b3 = data[offset + 3] & 0xFF;
        return (int)(b0 | (b1 << 8) | (b2 << 16) | (b3 << 24));
    }
}
