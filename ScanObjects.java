import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * 扫描对象连续对象 - 正确的 R2000 对象连续扫描
 */
public class ScanObjects {
    public static void main(String[] args) throws Exception {
        byte[] data = Files.readAllBytes(Paths.get("210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));
        System.out.println("文件大小: " + data.length + " bytes");

        int pos = 0x52b9;
        int objects = 0;
        Map<Integer, Integer> typeCounts = new HashMap<>();
        int blockHeaders = 0;
        int inserts = 0;
        
        System.out.println("\n从 0x52b9 开始扫描对象:\n");

        // 扫描最多 1000 个对象或直到文件结束
        while (pos < data.length - 8 && objects < 1000) {
            // Read MS (16-bit LE, with continuation if bit 15 set)
            // 但实际上,让我们简化:每次读 2 字节 LE,检查 bit15
            // bit 15 set → continue reading more 2-byte words
            int lo = data[pos] & 0xFF;
            int hi = data[pos+1] & 0xFF;
            int ms = lo | (hi << 8);
            boolean cont = (ms & 0x8000) != 0;
            int objSize = ms & 0x7FFF;
            pos += 2;
            if (cont) {
                // 继续读更多 16-bit words
                while (true) {
                    lo = data[pos] & 0xFF;
                    hi = data[pos+1] & 0xFF;
                    int w = lo | (hi << 8);
                    pos += 2;
                    objSize = objSize | ((w & 0x7FFF) << 15); // This is getting complex
                    if ((w & 0x8000) == 0) break;
                }
            }
            
            if (objSize < 2 || objSize > 8192 || pos + objSize > data.length) {
                // 无效对象, 停止
                System.out.println("停止 @ pos=" + pos + "  size=" + objSize);
                break;
            }

            // 现在在 pos 处读 BS type
            int byte2 = data[pos] & 0xFF;
            int byte3 = data[pos+1] & 0xFF;
            int opcode = (byte2 >> 6) & 0x03;

            int type;
            int typeBits;
            
            if (opcode == 0) { // 16-bit value
                int val = 0;
                for (int i = 2; i < 8; i++) val = (val << 1) | ((byte2 >> (7-i)) & 1;
                for (int i = 0; i < 8; i++) val = (val << 1) | ((byte3 >> (7-i)) & 1;
                int byte4 = data[pos+2] & 0xFF;
                for (int i = 0; i < 2; i++) val = (val << 1) | ((byte4 >> (7-i)) & 1;
                type = val;
                typeBits = 18; // 2+16
            } else if (opcode == 1) { // 8-bit value
                int val = 0;
                for (int i = 2; i < 8; i++) val = (val << 1) | ((byte2 >> (7-i)) & 1;
                for (int i = 0; i < 2; i++) val = (val << 1) | ((byte3 >> (7-i)) & 1;
                type = val;
                typeBits = 10; // 2+8
            } else if (opcode == 2) {
                type = 0;
                typeBits = 2;
            } else {
                type = 256;
                typeBits = 2;
            }
            
            objects++;
            typeCounts.put(type, typeCounts.getOrDefault(type, 0) + 1);

            if (type == 0x30) blockHeaders++;
            if (type == 0x07) inserts++;
            
            if (objects <= 50) {
                String name = findName(data, pos, Math.min(objSize, 80));
                System.out.printf("Obj #%3d @ 0x%-5x size=%-4d type=0x%-4x %s%n",
                    objects, pos - 2, objSize, type,
                    (type == 0x30 ? "[BLOCK_HEADER " + name + "]" : 
                     type == 0x07 ? "[INSERT]" : getTypeName(type));
            }

            // 跳到下一个对象
            pos += objSize;
        }

        System.out.println("\n共扫描 " + objects + " 个对象");
        System.out.println("BLOCK_HEADER (0x30): " + blockHeaders + " 个");
        System.out.println("INSERT (0x07): " + inserts + " 个");

        System.out.println("\n类型统计:");
        List<Map.Entry<Integer, Integer>> sorted = new ArrayList<>(typeCounts.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue());
        for (Map.Entry<Integer, Integer> e : sorted) {
            if (e.getValue() > 0) {
                System.out.println("  type=0x" + String.format("%04x", e.getKey()) + " (" + e.getKey() + "): " + e.getValue() + " 个 " + getTypeName(e.getKey()));
            }
        }
    }

    static String getTypeName(int type) {
        switch (type) {
            case 0x01: return "TEXT";
            case 0x07: return "INSERT";
            case 0x0F: return "CIRCLE";
            case 0x1F: return "LWPOLYLINE";
            case 0x30: return "BLOCK_HEADER";
            default: return "";
        }
    }

    static String findName(byte[] data, int start, int maxLen) {
        StringBuilder sb = new StringBuilder();
        int end = Math.min(start + maxLen, data.length);
        // 跳过前几个字节可能不是文本的内容
        for (int i = start + 16; i < end; i++) {
            int b = data[i] & 0xFF;
            if (b >= 32 && b < 127) {
                sb.append((char)b);
                if (sb.length() > 20) break;
            } else if (sb.length() > 2) {
                break;
            } else {
                sb.setLength(0);
            }
        }
        return "'" + sb.toString().trim() + "'";
    }
}
